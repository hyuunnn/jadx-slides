package jadxslides

import jadx.gui.ui.MainWindow
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The slides view: a small toolbar plus the JCEF browser component (or a
 * status label until the browser is ready). Lives either docked inside the
 * main window (via DockManager) or in its own window — the toggle moves the
 * same panel between the two.
 */
class SlidesPanel(
    private val onReload: () -> Unit,
    private val onOpenExternal: () -> Unit,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    private val title = JLabel("", SwingConstants.LEFT)
    private val status = JLabel("Starting…", SwingConstants.CENTER)
    private val content = JPanel(BorderLayout())
    private val dockToggle = JButton("Window")

    var browserComponent: Component? = null
        private set
    private var frame: JFrame? = null

    init {
        minimumSize = Dimension(240, 150)
        val bar = JToolBar()
        bar.isFloatable = false
        title.border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
        bar.add(title)
        bar.add(javax.swing.Box.createHorizontalGlue())
        bar.add(JButton("Reload").apply { addActionListener { onReload() } })
        bar.add(JButton("Browser").apply {
            toolTipText = "Open the deck in the system browser"
            addActionListener { onOpenExternal() }
        })
        bar.add(dockToggle.apply { addActionListener { toggleDock() } })
        bar.add(JButton("Close").apply { addActionListener { onClose() } })
        add(bar, BorderLayout.NORTH)

        content.add(status, BorderLayout.CENTER)
        add(content, BorderLayout.CENTER)
    }

    fun setDeckName(name: String) = onEdt {
        title.text = name
        frame?.title = "jadx-slides — $name"
    }

    fun showStatus(text: String) = onEdt {
        status.text = "<html><div style='text-align:center;padding:12px'>$text</div></html>"
        if (browserComponent == null) {
            content.removeAll()
            content.add(status, BorderLayout.CENTER)
            content.revalidate()
            content.repaint()
        }
    }

    fun attachBrowser(comp: Component) = onEdt {
        browserComponent = comp
        content.removeAll()
        content.add(comp, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    /** Hand keyboard focus back to the deck so arrow keys keep driving slides. */
    fun focusSoon() {
        val t = Timer(350) { browserComponent?.requestFocusInWindow() }
        t.isRepeats = false
        t.start()
    }

    private fun toggleDock() {
        val mw = Slides.mainWindow() ?: return
        if (frame == null) {
            DockManager.undock()
            openWindow(mw)
            dockToggle.text = "Dock"
        } else {
            closeWindow()
            DockManager.dock(mw, this)
            dockToggle.text = "Window"
        }
    }

    /** Used when docking is impossible — show the panel in its own window. */
    fun forceWindow(mw: MainWindow) {
        if (frame == null) {
            openWindow(mw)
            dockToggle.text = "Dock"
        }
    }

    private fun openWindow(mw: MainWindow) {
        val f = JFrame("jadx-slides — ${title.text}")
        f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        f.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                closeWindow()
                DockManager.dock(mw, this@SlidesPanel)
                dockToggle.text = "Window"
            }
        })
        f.contentPane.add(this)
        f.setSize(960, 720)
        val loc = mw.locationOnScreen
        f.setLocation(loc.x + mw.width - 400, loc.y + 60)
        f.isVisible = true
        frame = f
    }

    fun closeWindow() {
        frame?.let {
            it.contentPane.remove(this)
            it.dispose()
        }
        frame = null
    }

    /** Detach from wherever the panel currently lives (dock or window). */
    fun detach() {
        DockManager.undock()
        closeWindow()
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}
