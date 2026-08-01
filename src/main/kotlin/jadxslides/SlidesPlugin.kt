package jadxslides

import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.gui.JadxGuiContext
import jadx.gui.ui.MainWindow
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.handler.CefFocusHandlerAdapter
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.KeyboardFocusManager
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

class SlidesPlugin : JadxPlugin {

    override fun getPluginInfo() = JadxPluginInfo(
        "jadx-slides",
        "jadx-slides",
        "Marp / Slidev slide decks in a jadx-gui tab, with clickable @refs into the decompiled code",
        "https://github.com/hyuunnn/jadx-slides",
        "jadx-slides",
    )

    override fun init(context: JadxPluginContext) {
        // jadx re-instantiates plugins on every project open: long-lived
        // state (bridge, CefApp, open deck) lives in the Slides object,
        // only the context references are swapped
        Slides.ctx = context
        val gui = context.guiContext ?: return
        Slides.gui = gui
        gui.addMenuAction("Open Slides…") { Slides.openAction() }
        runCatching {
            gui.registerGlobalKeyBinding("jadx-slides:open", "ctrl shift M") { Slides.openAction() }
        }
    }
}

/** Process-wide state; survives jadx's per-project plugin re-instantiation. */
object Slides {
    private val log = LoggerFactory.getLogger(Slides::class.java)

    init {
        // jadx closes the plugin classloader during quit (closeAll), and the
        // quit path may be the FIRST execution of some code: any class that
        // would load lazily there dies in NoClassDefFoundError. Load the
        // kotlin.Result failure machinery (used by every runCatching catch
        // path) and the quit-path task classes now, while loading works.
        runCatching { throw IllegalStateException("warm ResultKt") }
        CloseTask::class.java
    }

    /** Named task instead of a lambda: quit-path classes must never load
     * lazily (see the init block). */
    private class CloseTask(private val s: DeckSession) : Runnable {
        override fun run() {
            try {
                s.close()
            } catch (t: Throwable) {
                log.warn("session close failed", t)
            }
        }
    }

    @Volatile var ctx: JadxPluginContext? = null
    @Volatile var gui: JadxGuiContext? = null
    @Volatile var session: DeckSession? = null

    private var panel: SlidesPanel? = null // EDT-only
    private var node: SlidesNode? = null
    private var cefClient: CefClient? = null
    private var cefBrowser: CefBrowser? = null
    private var lastDir: File? = null
    @Volatile private var lastOpened: File? = null // for Reload after a cancelled quit
    private var macOpensWarned = false
    private var docked = false // EDT-only
    private var movingPanel = false // tab close caused by dock toggle, not the user
    private var cefCleanupInstalled = false
    private var keyGuardInstalled = false
    @Volatile private var cefStarting = false // first browser build in flight

    /** Bumped on every open/close; an openDeck whose generation is stale
     * must discard (and close) the session it prepared instead of
     * publishing it — otherwise overlapping opens leak Slidev processes. */
    private val openGen = java.util.concurrent.atomic.AtomicLong()

    /** True while the CEF browser owns the keyboard (last click was on slides). */
    @Volatile private var browserHasKeyboard = false

    private val NAV_KEYS = setOf(
        java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.VK_RIGHT,
        java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.VK_DOWN,
        java.awt.event.KeyEvent.VK_PAGE_UP, java.awt.event.KeyEvent.VK_PAGE_DOWN,
        java.awt.event.KeyEvent.VK_HOME, java.awt.event.KeyEvent.VK_END,
        java.awt.event.KeyEvent.VK_SPACE,
    )

    /**
     * macOS delivers key events to the native browser AND down the AWT
     * pipeline (whose focus owner can silently revert to the code area), so
     * arrow keys moved slides and code together. While the browser owns the
     * keyboard, swallow navigation keys before Swing can dispatch them —
     * the native side still gets them, so the deck keeps working.
     */
    private fun installKeyGuard() {
        if (keyGuardInstalled) return
        keyGuardInstalled = true
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        kfm.addKeyEventDispatcher { e -> browserHasKeyboard && e.keyCode in NAV_KEYS }
        kfm.addPropertyChangeListener("focusOwner") { ev ->
            val owner = ev.newValue as? java.awt.Component
            if (owner != null && owner !== panel?.browserComponent) {
                browserHasKeyboard = false // user clicked into a Swing component
            }
        }
    }

    private val bridgeLazy = lazy {
        BridgeServer().also {
            // (relocation rewrites this reference at shadow time)
            it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        }
    }
    val bridge: BridgeServer get() = bridgeLazy.value

    fun mainWindow(): MainWindow? = gui?.mainFrame as? MainWindow

    fun panelOrCreate(): SlidesPanel {
        panel?.let { return it }
        val p = SlidesPanel(
            onReload = { reloadView() },
            onOpenExternal = { session?.let { openExternal(it.url) } },
            onClose = { closeAction() },
        )
        panel = p
        return p
    }

    fun openAction() {
        val mw = mainWindow() ?: return
        val chooser = JFileChooser(lastDir ?: File(System.getProperty("user.home")))
        chooser.fileFilter = FileNameExtensionFilter(
            "Slide decks (*.md, *.html)", "md", "markdown", "html", "htm",
        )
        if (chooser.showOpenDialog(mw) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        lastDir = file.parentFile
        Thread({ openDeck(file) }, "jadx-slides-open").apply { isDaemon = true }.start()
    }

    /** Tab dispose calls this — tear the session down but leave the tab alone. */
    fun onTabClosed() {
        if (movingPanel) return // the dock toggle is relocating the panel, not closing
        openGen.incrementAndGet() // supersede any in-flight open
        val s = session
        session = null
        closeSessionAsync(s)
        cefBrowser?.loadURL("about:blank")
        browserHasKeyboard = false // nothing owns the keyboard anymore
    }

    /** All session teardown funnels through one worker: DeckSession.close
     * blocks (child-process shutdown, in-flight render) so it must stay off
     * the EDT, and serializing closes means quitCleanup can simply drain
     * this executor to guarantee no Slidev child outlives the JVM. */
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "jadx-slides-close").apply { isDaemon = true }
    }

    private fun closeSessionAsync(s: DeckSession?) {
        if (s == null) return
        try {
            closer.execute(CloseTask(s))
        } catch (t: Throwable) {
            // executor already drained by quit — close inline as last resort
            log.warn("close task rejected, closing inline", t)
            CloseTask(s).run()
        }
    }

    fun closeAction() {
        movingPanel = false
        onTabClosed()
        SwingUtilities.invokeLater {
            if (docked) {
                DockManager.undock()
                docked = false
                panel?.setDockedUi(false)
            }
            val mw = mainWindow() ?: return@invokeLater
            node?.let { runCatching { mw.tabsController.closeTab(it, false) } }
        }
    }

    /** EDT: move the panel between the slides tab and a split beside the code. */
    fun toggleDock() {
        val mw = mainWindow() ?: return
        val p = panelOrCreate()
        if (!docked) {
            movingPanel = true
            try {
                node?.let { runCatching { mw.tabsController.closeTab(it, false) } }
            } finally {
                movingPanel = false
            }
            if (DockManager.dock(mw, p)) {
                docked = true
                p.setDockedUi(true)
            } else {
                // docking failed — fall back to the tab
                val n = node ?: SlidesNode().also { node = it }
                mw.tabsController.selectTab(n)
            }
        } else {
            DockManager.undock()
            docked = false
            p.setDockedUi(false)
            val n = node ?: SlidesNode().also { node = it }
            mw.tabsController.selectTab(n)
        }
    }

    /** Background thread: render pipeline, then hand off to the EDT.
     * Publishing happens on the EDT under a generation check so overlapping
     * opens (or a close racing an open) can't leak the prepared session. */
    private fun openDeck(file: File) {
        val gen = openGen.incrementAndGet()
        lastOpened = file
        try {
            val text = file.readText(Charsets.UTF_8).removePrefix("﻿")
            val engine = Engines.detect(file.toPath(), text)
            val s = DeckSession(file, engine)
            val err = s.prepare(bridge)
            if (err != null) {
                closeSessionAsync(s)
                showError(err)
                return
            }
            SwingUtilities.invokeLater {
                if (gen != openGen.get()) {
                    // a newer open or a close superseded this one
                    closeSessionAsync(s)
                    return@invokeLater
                }
                val old = session
                session = s
                closeSessionAsync(old)
                showView(s)
            }
        } catch (t: Throwable) {
            log.error("failed to open deck {}", file, t)
            showError("Failed to open the deck: ${t.message}")
        }
    }

    /** EDT: open/select the slides tab and attach (or navigate) the browser. */
    private fun showView(s: DeckSession) {
        val mw = mainWindow() ?: return
        val p = panelOrCreate()
        p.setDeckName(s.source.name)
        if (p.browserComponent == null) p.showStatus("Preparing view…")

        if (!docked) {
            val n = node ?: SlidesNode().also { node = it }
            mw.tabsController.selectTab(n)
        }

        val missing = CefHolder.macOpensMissing()
        if (missing.isNotEmpty()) {
            useBrowserFallback(s, p, missing)
            return
        }

        val existing = cefBrowser
        if (existing != null) {
            existing.loadURL(s.url)
            p.focusSoon()
            return
        }
        if (cefStarting) {
            // the first build is still in flight (possibly a ~100MB native
            // download); it navigates to the current session when it lands —
            // building a second browser here would orphan a native renderer
            return
        }
        cefStarting = true
        Thread({
            try {
                val app = CefHolder.getOrBuild { msg -> p.showStatus(msg) }
                SwingUtilities.invokeLater {
                    // the EDT body runs outside the outer catch's dynamic
                    // scope — its own try/finally must clear cefStarting, or
                    // one createBrowser failure disables the view for good
                    try {
                        val cur = session
                        if (cur == null) {
                            // the deck was closed while CEF was building —
                            // nothing to show
                            return@invokeLater
                        }
                        val client = app.createClient()
                        // keys reach both the native browser and whichever
                        // Swing component holds the AWT focus (the code area
                        // — so arrow keys moved slides AND code); park the
                        // AWT focus while the browser owns the keyboard
                        client.addFocusHandler(object : CefFocusHandlerAdapter() {
                            override fun onGotFocus(browser: CefBrowser) {
                                if (browserHasKeyboard) return
                                browserHasKeyboard = true
                                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .clearGlobalFocusOwner()
                                browser.setFocus(true)
                            }

                            override fun onTakeFocus(browser: CefBrowser, next: Boolean) {
                                browserHasKeyboard = false
                            }
                        })
                        installKeyGuard()
                        // always the CURRENT session's url — s may be stale
                        val browser = client.createBrowser(cur.url, false, false)
                        cefClient = client
                        cefBrowser = browser
                        installCefCleanup(mw)
                        p.attachBrowser(browser.uiComponent)
                        p.focusSoon()
                    } catch (t: Throwable) {
                        log.error("JCEF browser creation failed — falling back to the system browser", t)
                        p.showStatus(
                            "Embedded browser unavailable (${t.message})<br>" +
                                    "The deck was opened in the system browser instead.",
                        )
                        session?.let { openExternal(it.url) }
                    } finally {
                        cefStarting = false
                    }
                }
            } catch (t: Throwable) {
                cefStarting = false
                log.error("JCEF init failed — falling back to the system browser", t)
                p.showStatus(
                    "Embedded browser unavailable (${t.message})<br>" +
                            "The deck was opened in the system browser instead.",
                )
                session?.let { openExternal(it.url) }
            }
        }, "jadx-slides-cef").apply { isDaemon = true }.start()
    }

    private fun useBrowserFallback(s: DeckSession, p: SlidesPanel, missing: List<String>) {
        val opts = missing.joinToString(" ") { "--add-opens=java.desktop/$it=ALL-UNNAMED" }
        p.showStatus(
            "The embedded browser needs JVM flags jadx wasn't started with.<br>" +
                    "The deck was opened in the system browser instead.<br><br>" +
                    "To embed slides here, relaunch with:<br><code>JADX_GUI_OPTS=\"$opts\" jadx-gui</code>",
        )
        openExternal(s.url)
        if (!macOpensWarned) {
            macOpensWarned = true
            JOptionPane.showMessageDialog(
                mainWindow(),
                "jadx-slides: the embedded browser needs extra JVM flags on macOS.\n\n" +
                        "Relaunch jadx-gui with:\n\nJADX_GUI_OPTS=\"$opts\" jadx-gui\n\n" +
                        "Until then decks open in the system browser (jump links still work).",
                "jadx-slides",
                JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }

    /**
     * Close the browser before CEF's native teardown. jadx's quit flow is
     * windowClosing → (cancelable save prompt) → background thread →
     * dispose() → System.exit(0): a browser still parented at dispose/exit
     * time gets AWT UI updates against a dying native view and crashes in
     * util_mac::UpdateView. So the browser dies at windowClosing — BEFORE
     * dispose — which is the empirically crash-free ordering. If the user
     * cancels the save prompt the window survives with the view released;
     * the panel says so and the Reload button rebuilds the browser.
     */
    private fun installCefCleanup(mw: MainWindow) {
        if (cefCleanupInstalled) return
        cefCleanupInstalled = true
        // created here, while the classloader is open, and reused by both
        // hooks — a lambda allocated inside windowClosed would need a class
        // load that can race jadx's classloader close
        val quitTask = Runnable { quitCleanup() }
        mw.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                // window-X and Cmd+Q pass here BEFORE jadx's background quit
                // (its bg thread blocks on the EDT we're occupying), i.e.
                // while the classloader is still open — do the real cleanup
                // now. If the user cancelled the save prompt the window
                // survives; Reload fully restores the deck.
                beginQuitCleanup()
            }

            override fun windowClosed(e: java.awt.event.WindowEvent) {
                // windowClosed fires on the EDT but quitCleanup blocks on
                // process shutdown — run it on a worker the JVM waits for.
                // This (plus the shutdown hook) also covers jadx's Exit menu,
                // which quits WITHOUT ever firing windowClosing.
                Thread(quitTask, "jadx-slides-quit").start()
            }
        })
        Runtime.getRuntime().addShutdownHook(Thread(quitTask, "jadx-slides-cef-cleanup"))
    }

    /** EDT, windowClosing — the classloader is guaranteed open here. Kill
     * the browser (must precede dispose(): util_mac::UpdateView crash) and
     * kick the session close so all its classes load now. */
    private fun beginQuitCleanup() {
        openGen.incrementAndGet()
        val s = session
        session = null
        closeSessionAsync(s)
        disposeCef()
        panel?.showStatus(
            "View released for shutdown.<br>" +
                    "If you cancelled quitting, press Reload to restore the deck.",
        )
    }

    /** Real quit (worker/shutdown-hook thread, never the EDT): drain the
     * close worker so a JVM exit can't strand a Slidev child, then tear the
     * browser down if windowClosing didn't run (jadx's Exit menu path).
     * Everything here is idempotent and free of lazy class loads. */
    private fun quitCleanup() {
        try {
            openGen.incrementAndGet()
            val s = session
            session = null
            closeSessionAsync(s)
            closer.shutdown()
            try {
                closer.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            disposeCef()
        } catch (t: Throwable) {
            log.error("quit cleanup failed", t)
        }
    }

    @Synchronized // shutdown hook and EDT can race here
    private fun disposeCef() {
        if (cefBrowser == null && cefClient == null) {
            return // already torn down — avoid late-shutdown work entirely
        }
        // detach first — synchronously, even from the shutdown hook: a
        // still-parented browser keeps receiving AWT UI updates and crashes
        // in util_mac::UpdateView during teardown
        panel?.detachBrowserNow()
        try {
            cefBrowser?.close(true)
        } catch (t: Throwable) {
            log.debug("browser close failed", t)
        }
        cefBrowser = null
        try {
            cefClient?.dispose()
        } catch (t: Throwable) {
            log.debug("client dispose failed", t)
        }
        cefClient = null
        browserHasKeyboard = false
    }

    /** CEF's macOS Cmd+Q handler lands here (AppKit thread): clean up the
     * browser, then hand the quit to jadx's normal window-close flow. */
    fun requestQuit() {
        SwingUtilities.invokeLater {
            disposeCef()
            val mw = mainWindow()
            if (mw != null) {
                mw.dispatchEvent(
                    java.awt.event.WindowEvent(mw, java.awt.event.WindowEvent.WINDOW_CLOSING),
                )
            } else {
                Runtime.getRuntime().exit(0)
            }
        }
    }

    private fun reloadView() {
        val s = session
        if (s == null) {
            // a cancelled quit tore the session down — fully reopen the deck
            val f = lastOpened ?: return
            Thread({ openDeck(f) }, "jadx-slides-open").apply { isDaemon = true }.start()
            return
        }
        if (cefBrowser == null) {
            // the view was released but the session survived — just rebuild
            showView(s)
            return
        }
        // EDT-safe: bumpVersion is just an atomic increment
        bridge.bumpVersion()
        if (s.engine == Engine.SLIDEV) cefBrowser?.loadURL(s.url) else cefBrowser?.reload()
    }

    fun openExternal(url: String) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (e: Exception) {
            log.warn("cannot open system browser", e)
        }
    }

    private fun showError(msg: String) {
        log.warn("jadx-slides: {}", msg)
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(mainWindow(), msg, "jadx-slides", JOptionPane.ERROR_MESSAGE)
        }
    }
}
