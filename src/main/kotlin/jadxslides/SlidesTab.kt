package jadxslides

import jadx.gui.treemodel.JClass
import jadx.gui.treemodel.JNode
import jadx.gui.ui.panel.ContentPanel
import jadx.gui.ui.tab.TabbedPane
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.UIManager

/**
 * The slides tab: a custom JNode whose content panel hosts the (long-lived)
 * SlidesPanel. Opening it goes through the regular TabsController flow, so
 * it behaves like any other tab — no layout surgery on the main window.
 * jadx skips unknown node types when persisting open tabs, so this tab is
 * simply not restored on project reopen (by design).
 */
class SlidesNode : JNode() {

    override fun getJParent(): JClass? = null

    override fun makeString(): String = "Slides"

    override fun getName(): String = "Slides"

    override fun getIcon(): Icon? = UIManager.getIcon("FileView.fileIcon")

    override fun hasContent(): Boolean = true

    override fun supportsQuickTabs(): Boolean = false

    override fun getContentPanel(tabbedPane: TabbedPane): ContentPanel =
        SlidesContentPanel(tabbedPane, this)
}

class SlidesContentPanel(tabbedPane: TabbedPane, node: JNode) : ContentPanel(tabbedPane, node) {

    init {
        layout = BorderLayout()
        Slides.panelOrCreate().let { add(it, BorderLayout.CENTER) }
    }

    override fun loadSettings() {
        // nothing font/theme-specific to apply — the deck renders in JCEF
    }

    override fun supportsQuickTabs(): Boolean = false

    override fun dispose() {
        Slides.onTabClosed()
    }
}
