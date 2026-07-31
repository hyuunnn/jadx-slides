package jadxslides

import jadx.gui.ui.MainWindow
import org.slf4j.LoggerFactory
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

/**
 * Injects the slides panel into jadx-gui's main window, side by side with
 * the code tabs: the TabbedPane is reparented into a new horizontal
 * JSplitPane which takes its old slot. jadx's own references to the
 * TabbedPane keep working — Swing components don't care who their parent is.
 *
 * All methods must run on the EDT.
 */
object DockManager {
    private val LOG = LoggerFactory.getLogger(DockManager::class.java)

    private var split: JSplitPane? = null
    private var restore: (() -> Unit)? = null

    val isDocked: Boolean get() = split != null

    fun dock(mw: MainWindow, panel: JComponent): Boolean {
        if (split != null) return true
        val tabbed = mw.tabbedPane
        val parent = tabbed.parent ?: return false

        val sp = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        sp.isContinuousLayout = true
        sp.resizeWeight = 0.6

        when {
            parent is JSplitPane && parent.rightComponent === tabbed -> {
                sp.leftComponent = tabbed
                sp.rightComponent = panel
                parent.rightComponent = sp
                restore = { parent.rightComponent = tabbed }
            }
            parent is JSplitPane && parent.leftComponent === tabbed -> {
                sp.leftComponent = tabbed
                sp.rightComponent = panel
                parent.leftComponent = sp
                restore = { parent.leftComponent = tabbed }
            }
            else -> {
                val container = parent as? Container ?: return false
                val idx = container.components.indexOfFirst { it === tabbed }
                if (idx < 0) {
                    LOG.warn("jadx-slides: TabbedPane not found in its parent, cannot dock")
                    return false
                }
                sp.leftComponent = tabbed
                sp.rightComponent = panel
                container.add(sp, idx)
                restore = {
                    container.remove(sp)
                    container.add(tabbed, idx)
                }
            }
        }
        split = sp
        parent.revalidate()
        parent.repaint()
        SwingUtilities.invokeLater { sp.setDividerLocation(0.6) }
        return true
    }

    fun undock() {
        val sp = split ?: return
        val parent = sp.parent
        sp.rightComponent = null
        restore?.invoke() // reparents the TabbedPane back into its old slot
        split = null
        restore = null
        parent?.revalidate()
        parent?.repaint()
    }
}
