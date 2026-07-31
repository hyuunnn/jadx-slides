package jadxslides

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The slides view content: a small toolbar plus the JCEF browser component
 * (or a status label until the browser is ready). Long-lived — it is
 * re-added to a fresh SlidesContentPanel each time the tab is reopened.
 */
class SlidesPanel(
    private val onReload: () -> Unit,
    private val onOpenExternal: () -> Unit,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    private val title = JLabel("jadx-slides", SwingConstants.LEFT)
    private val status = JLabel("Starting…", SwingConstants.CENTER)
    private val content = JPanel(BorderLayout())
    private val dockToggle = JButton("Dock")

    var browserComponent: Component? = null
        private set

    init {
        minimumSize = Dimension(240, 150)
        val bar = JToolBar()
        bar.isFloatable = false
        title.border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
        bar.add(title)
        bar.add(Box.createHorizontalGlue())
        bar.add(JButton("Reload").apply { addActionListener { onReload() } })
        bar.add(JButton("Browser").apply {
            toolTipText = "Open the deck in the system browser"
            addActionListener { onOpenExternal() }
        })
        bar.add(dockToggle.apply {
            toolTipText = "Show the slides beside the code (split the main window)"
            addActionListener { Slides.toggleDock() }
        })
        bar.add(JButton("Close").apply { addActionListener { onClose() } })
        add(bar, BorderLayout.NORTH)

        content.add(status, BorderLayout.CENTER)
        add(content, BorderLayout.CENTER)
    }

    fun setDeckName(name: String) = onEdt {
        // the label stays the plugin name; the open deck shows as a tooltip
        title.toolTipText = name
    }

    fun setDockedUi(docked: Boolean) = onEdt {
        dockToggle.text = if (docked) "Tab" else "Dock"
        dockToggle.toolTipText = if (docked) {
            "Move the slides back into a regular tab"
        } else {
            "Show the slides beside the code (split the main window)"
        }
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

    /** Focus the deck (e.g. right after it is attached) so arrow keys work. */
    fun focusSoon() {
        val t = Timer(350) { browserComponent?.requestFocusInWindow() }
        t.isRepeats = false
        t.start()
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}
