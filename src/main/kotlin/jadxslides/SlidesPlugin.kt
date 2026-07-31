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
        gui.addMenuAction("Close Slides") { Slides.closeAction() }
        runCatching {
            gui.registerGlobalKeyBinding("jadx-slides:open", "ctrl shift M") { Slides.openAction() }
        }
    }
}

/** Process-wide state; survives jadx's per-project plugin re-instantiation. */
object Slides {
    private val log = LoggerFactory.getLogger(Slides::class.java)

    @Volatile var ctx: JadxPluginContext? = null
    @Volatile var gui: JadxGuiContext? = null
    @Volatile var session: DeckSession? = null

    private var panel: SlidesPanel? = null // EDT-only
    private var node: SlidesNode? = null
    private var cefClient: CefClient? = null
    private var cefBrowser: CefBrowser? = null
    private var lastDir: File? = null
    private var macOpensWarned = false
    private var docked = false // EDT-only
    private var movingPanel = false // tab close caused by dock toggle, not the user

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
        val s = session
        session = null
        s?.close()
        cefBrowser?.loadURL("about:blank")
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

    /** Background thread: render pipeline, then hand off to the EDT. */
    private fun openDeck(file: File) {
        try {
            session?.close()
            session = null

            val text = file.readText(Charsets.UTF_8).removePrefix("﻿")
            val engine = Engines.detect(file.toPath(), text)
            val s = DeckSession(file, engine)
            val err = s.prepare(bridge)
            if (err != null) {
                showError(err)
                return
            }
            session = s
            SwingUtilities.invokeLater { showView(s) }
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
        Thread({
            try {
                val app = CefHolder.getOrBuild { msg -> p.showStatus(msg) }
                SwingUtilities.invokeLater {
                    val client = app.createClient()
                    // keys reach both the native browser and whichever Swing
                    // component holds the AWT focus (the code area — so arrow
                    // keys moved slides AND code); park the AWT focus while
                    // the browser owns the keyboard
                    client.addFocusHandler(object : CefFocusHandlerAdapter() {
                        private var browserFocus = false

                        override fun onGotFocus(browser: CefBrowser) {
                            if (browserFocus) return
                            browserFocus = true
                            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                .clearGlobalFocusOwner()
                            browser.setFocus(true)
                        }

                        override fun onTakeFocus(browser: CefBrowser, next: Boolean) {
                            browserFocus = false
                        }
                    })
                    val browser = client.createBrowser(s.url, false, false)
                    cefClient = client
                    cefBrowser = browser
                    p.attachBrowser(browser.uiComponent)
                    p.focusSoon()
                }
            } catch (t: Throwable) {
                log.error("JCEF init failed — falling back to the system browser", t)
                p.showStatus(
                    "Embedded browser unavailable (${t.message})<br>" +
                            "The deck was opened in the system browser instead.",
                )
                openExternal(s.url)
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

    private fun reloadView() {
        val s = session ?: return
        Thread({
            bridge.bumpVersion()
            SwingUtilities.invokeLater {
                if (s.engine == Engine.SLIDEV) cefBrowser?.loadURL(s.url) else cefBrowser?.reload()
            }
        }, "jadx-slides-reload").apply { isDaemon = true }.start()
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
