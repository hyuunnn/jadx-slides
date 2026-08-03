package jadxslides

import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.gui.JadxGuiContext
import jadx.gui.ui.MainWindow
import org.cef.CefClient
import org.cef.browser.CefBrowser
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
        // at startup, into the launching terminal — so the flags are visible
        // and copyable before the user even opens a deck
        Slides.printMacOpensHintOnce()
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
    }

    // field initializer, not a bare statement — the compiler drops unused
    // pure expressions and the preload silently vanishes (seen with
    // SlidesPanel.DetachRun)
    @Suppress("unused")
    private val closeTaskPreload: Class<*> = CloseTask::class.java

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

    // written on the EDT but read by the quit worker / shutdown hook —
    // without volatile the teardown can see stale nulls and skip disposal
    @Volatile private var panel: SlidesPanel? = null
    private var node: SlidesNode? = null // EDT-only
    @Volatile private var cefClient: CefClient? = null
    @Volatile private var cefBrowser: CefBrowser? = null

    /** A session being prepared on the open thread; quit must close it too —
     * it is in nobody else's queue until the EDT publish step runs. */
    @Volatile private var pendingSession: DeckSession? = null
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

    /**
     * Called from the bridge's /kbd endpoint: the injected page script saw a
     * real pointer-down inside the deck. This is the ONLY setter of
     * [browserHasKeyboard] — CEF focus callbacks are not used as a signal
     * because CefClient's internal setFocus echo re-fires onGotFocus in an
     * endless loop on macOS (observed live), which kept re-arming the flag
     * after the user had already clicked back into the code area.
     * (If the deck is ALSO open in an external browser its clicks ping too;
     * the isShowing guard plus the focusOwner listener make that harmless.)
     */
    fun deckPointerDown() {
        SwingUtilities.invokeLater {
            if (panel?.browserComponent?.isShowing != true) return@invokeLater
            browserHasKeyboard = true
            // keys reach BOTH the native browser and the AWT focus owner —
            // park the AWT side so only the deck moves
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
        }
    }

    /** Injected into every page the embedded browser loads (all engines). */
    private fun kbdPingJs(): String =
        "(function(){if(window.__jadxSlidesKbd)return;window.__jadxSlidesKbd=1;" +
                "document.addEventListener('mousedown',function(){" +
                "fetch('http://127.0.0.1:${bridge.port}/kbd',{mode:'no-cors'})" +
                ".catch(function(){});},true);})();"

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
        kfm.addKeyEventDispatcher { e ->
            browserHasKeyboard &&
                    e.keyCode in NAV_KEYS &&
                    // clearGlobalFocusOwner leaves the owner null, so the flag
                    // can survive interactions that never take focus (menus,
                    // scrollbars) — never swallow keys an open menu needs,
                    // or when the deck isn't even visible
                    panel?.browserComponent?.isShowing == true &&
                    javax.swing.MenuSelectionManager.defaultManager().selectedPath.isEmpty()
        }
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
        // jadx runs plugin MENU actions on its background executor, not the
        // EDT (the key-binding path IS the EDT) — a Swing file dialog must
        // never be built off the EDT, and hopping also serializes lastDir
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { openAction() }
            return
        }
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
        var s: DeckSession? = null
        try {
            val text = file.readText(Charsets.UTF_8).removePrefix("﻿")
            val engine = Engines.detect(file.toPath(), text)
            val session0 = DeckSession(file, engine)
            s = session0
            if (!registerPending(session0)) {
                // quit already drained the closer — close inline, right here
                CloseTask(session0).run()
                return
            }
            val err = session0.prepare(bridge)
            if (err != null) {
                clearPending(session0)
                closeSessionAsync(session0)
                showError(err)
                return
            }
            SwingUtilities.invokeLater {
                clearPending(session0)
                // publish is atomic against quit's supersede — the winner
                // takes over the bridge, a loser is closed
                if (!tryPublish(gen, session0)) {
                    closeSessionAsync(session0)
                    return@invokeLater
                }
                showView(session0)
            }
        } catch (t: Throwable) {
            log.error("failed to open deck {}", file, t)
            // a session created before the throw holds a scheduler thread
            // and possibly sibling files — don't leak them
            s?.let { clearPending(it) }
            closeSessionAsync(s)
            showError("Failed to open the deck: ${t.message}")
        }
    }

    /** Clear pendingSession only if it is still ours — a newer open may
     * already have replaced it with its own in-flight session. */
    @Synchronized
    private fun clearPending(mine: DeckSession) {
        if (pendingSession === mine) pendingSession = null
    }

    /** Register an in-flight session, unless quit already drained the close
     * worker — then refuse so the caller closes it inline instead of racing
     * an exit that will never run the EDT continuation. */
    @Synchronized
    private fun registerPending(s: DeckSession): Boolean {
        if (closer.isShutdown) return false
        pendingSession = s
        return true
    }

    /** Atomically supersede current + in-flight sessions (quit/tab-close). */
    @Synchronized
    private fun takeSessionsForClose(): List<DeckSession> {
        openGen.incrementAndGet()
        val taken = listOfNotNull(session, pendingSession)
        session = null
        pendingSession = null
        return taken
    }

    /** EDT publish under the generation check, atomic against quit's
     * supersede — returns false if this open lost the race. */
    @Synchronized
    private fun tryPublish(gen: Long, s: DeckSession): Boolean {
        if (gen != openGen.get()) return false
        val old = session
        session = s
        s.publishTo(bridge)
        if (old != null) closeSessionAsync(old)
        return true
    }

    /** EDT: open/select the slides tab and attach (or navigate) the browser. */
    private fun showView(s: DeckSession) {
        val mw = mainWindow() ?: return
        // quit cleanup must exist for EVERY deck, including the
        // browser-fallback paths — a Slidev child must never outlive jadx
        // just because the embedded browser was unavailable
        installCefCleanup(mw)
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
            if (p.browserComponent == null) {
                // detached by a cancelled quit — the browser is still alive
                // (quit never destroys it), just reparent it
                p.attachBrowser(existing.uiComponent)
            }
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
                        // owned by disposeCef from this moment — assigning
                        // only after createBrowser leaked the client (and its
                        // focus handler) whenever createBrowser threw
                        cefClient = client
                        // NO CefFocusHandler — two failure modes were traced
                        // to using CEF focus callbacks as the keyboard
                        // signal: calling setFocus inside them recursed to a
                        // StackOverflowError, and CefClient's own setFocus
                        // echo re-fires onGotFocus in an endless loop that
                        // outlasts any debounce, re-arming the flag after
                        // the user clicked back into the code area. Instead
                        // the deck page itself reports real pointer-downs
                        // (script below → /kbd → deckPointerDown).
                        client.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                            override fun onLoadEnd(
                                browser: CefBrowser,
                                frame: org.cef.browser.CefFrame?,
                                httpStatusCode: Int,
                            ) {
                                if (frame == null || !frame.isMain) return
                                browser.executeJavaScript(kbdPingJs(), frame.url, 0)
                            }
                        })
                        installKeyGuard()
                        // always the CURRENT session's url — s may be stale
                        val browser = client.createBrowser(cur.url, false, false)
                        cefBrowser = browser
                        p.attachBrowser(browser.uiComponent)
                        p.focusSoon()
                    } catch (t: Throwable) {
                        log.error("JCEF browser creation failed — falling back to the system browser", t)
                        disposeCef() // release the half-built client/browser
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

    /** `JADX_GUI_OPTS=…` relaunch command for the missing --add-opens flags. */
    private fun relaunchCommand(missing: List<String>): String {
        val opts = missing.joinToString(" ") { "--add-opens=java.desktop/$it=ALL-UNNAMED" }
        return "JADX_GUI_OPTS=\"$opts\" jadx-gui"
    }

    // once per process, not per project open; init may run on a different
    // thread on a later project open
    @Volatile private var optsHintPrinted = false

    /** Print the relaunch command to the terminal jadx was started from —
     * a place it can always be copied from, unlike a dialog. */
    fun printMacOpensHintOnce() {
        if (optsHintPrinted) return
        val missing = CefHolder.macOpensMissing()
        if (missing.isEmpty()) return
        optsHintPrinted = true
        println("jadx-slides: the embedded slides view needs JVM flags; relaunch with:")
        println("  " + relaunchCommand(missing))
    }

    private fun useBrowserFallback(s: DeckSession, p: SlidesPanel, missing: List<String>) {
        val cmd = relaunchCommand(missing)
        p.showStatus(
            "The embedded browser needs JVM flags jadx wasn't started with.<br>" +
                    "The deck was opened in the system browser instead.<br><br>" +
                    "To embed slides here, relaunch with:<br><code>$cmd</code>",
        )
        openExternal(s.url)
        if (!macOpensWarned) {
            macOpensWarned = true
            JOptionPane.showMessageDialog(
                mainWindow(),
                "jadx-slides: the embedded browser needs extra JVM flags on macOS.\n\n" +
                        "Relaunch jadx-gui with:\n\n$cmd\n\n" +
                        "Until then decks open in the system browser (jump links still work).",
                "jadx-slides",
                JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }

    /**
     * Quit lifecycle. jadx's quit flow is windowClosing → (cancelable save
     * prompt) → background thread → closeAll() (plugin classloader closes!)
     * → dispose() → System.exit(0). Our rules, each learned from a distinct
     * native crash:
     *  - detach the browser at windowClosing, BEFORE dispose()
     *  - destroy NOTHING of CEF at quit (see detachCefForQuit)
     *  - no lazy class loads anywhere on the quit path
     * If the user cancels the save prompt the window survives with the view
     * released; Reload reattaches the still-alive browser.
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
                // definite quit, and still BEFORE jadx's System.exit (the bg
                // thread waits for this dispose to finish) — the perfect
                // moment to defuse CEF's native shutdown
                neutralizeCefShutdown()
                // File→Exit skips windowClosing entirely — detach here on
                // the EDT so the browser leaves the hierarchy as early as
                // that path allows (no-op when windowClosing already ran)
                detachCefForQuit()
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
        takeSessionsForClose().forEach { closeSessionAsync(it) }
        detachCefForQuit()
        panel?.showStatus(
            "View released for shutdown.<br>" +
                    "If you cancelled quitting, press Reload to restore the deck.",
        )
    }

    /**
     * Quit-path CEF handling: DETACH ONLY, destroy nothing. Every macOS
     * quit crash so far was a live JCEF observer touching partially
     * destroyed state — a browser closed too early (CefHandler
     * setVisibility), a browser still parented at dispose
     * (util_mac::UpdateView), or a torn-down context (TempWindowMac,
     * JCEFApplication's event monitor). Keeping browser/client/context
     * fully alive but unparented until the process dies leaves the
     * observers nothing dangling to dereference; the OS reclaims it all.
     */
    private fun detachCefForQuit() {
        panel?.detachBrowserNow()
        browserHasKeyboard = false
    }

    /** Real quit (worker/shutdown-hook thread, never the EDT): drain the
     * close worker so a JVM exit can't strand a Slidev child, then tear the
     * browser down if windowClosing didn't run (jadx's Exit menu path).
     * Everything here is idempotent and free of lazy class loads. */
    private fun quitCleanup() {
        try {
            neutralizeCefShutdown() // backup for the shutdown-hook race
            takeSessionsForClose().forEach { closeSessionAsync(it) }
            closer.shutdown()
            try {
                closer.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            detachCefForQuit()
        } catch (t: Throwable) {
            log.error("quit cleanup failed", t)
        }
    }

    /**
     * A full native CefShutdown on macOS crashes AFTER our cleanup: JCEF's
     * swizzled NSApplication event monitor keeps running and dereferences
     * state the shutdown destroyed (std::set in the JCEFApplication load
     * block). The process is exiting anyway, so mark CefApp TERMINATED —
     * jcefmaven's shutdown hook then no-ops instead of tearing CEF down.
     * Definite-quit paths only: a terminated CefApp cannot be revived.
     */
    private fun neutralizeCefShutdown() {
        try {
            val f = org.cef.CefApp::class.java.getDeclaredField("state_")
            f.isAccessible = true
            f.set(null, org.cef.CefApp.CefAppState.TERMINATED)
        } catch (t: Throwable) {
            log.debug("could not neutralize CEF shutdown", t)
        }
    }

    /** Full disposal — mid-run failure cleanup ONLY (a half-built client
     * whose createBrowser threw). Never called on the quit path: quit must
     * not destroy CEF objects (detachCefForQuit explains why). */
    @Synchronized
    private fun disposeCef() {
        if (cefBrowser == null && cefClient == null) {
            return
        }
        // detach first: a still-parented browser keeps receiving AWT UI
        // updates against a dying native view (util_mac::UpdateView)
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
            detachCefForQuit()
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
            if (CefHolder.macOpensMissing().isNotEmpty()) {
                // browser-fallback mode: the external browser reloads itself
                // via the /version poll — re-entering showView would open
                // one more duplicate browser tab per click
                bridge.bumpVersion()
                return
            }
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
