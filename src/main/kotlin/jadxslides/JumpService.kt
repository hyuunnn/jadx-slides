package jadxslides

import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaNode
import jadx.gui.ui.MainWindow
import jadx.gui.ui.codearea.AbstractCodeContentPanel
import org.slf4j.LoggerFactory
import javax.swing.Timer

/**
 * Resolve an `@` token and jump the jadx-gui code view. Runs on the EDT
 * (the bridge defers to it). Uses jadx-gui internals — safe because the
 * plugin classloader's parent is the application classloader.
 */
object JumpService {
    private val LOG = LoggerFactory.getLogger(JumpService::class.java)

    data class Ref(val name: String, val member: String?, val line: Int?)

    fun parse(raw: String): Ref {
        var t = raw.trim().removePrefix("@").trim()
        var line: Int? = null
        val lm = Regex("^(.*):(\\d+)$").find(t)
        if (lm != null) {
            t = lm.groupValues[1]
            line = lm.groupValues[2].toInt()
        }
        if (t.startsWith("L") && t.contains(";")) {
            val cls = t.substringBefore(";").removePrefix("L").replace('/', '.')
            val member = t.substringAfter("->", "").substringBefore("(").ifBlank { null }
            return Ref(cls, member, line)
        }
        return Ref(t, null, line)
    }

    fun jump(raw: String) {
        try {
            doJump(raw)
        } catch (t: Throwable) {
            LOG.error("jadx-slides: jump failed for '{}'", raw, t)
        }
    }

    private fun doJump(raw: String) {
        val gui = Slides.gui ?: return
        val mw = gui.mainFrame as? MainWindow ?: return
        val decompiler = Slides.ctx?.decompiler ?: mw.wrapper.decompiler
        val ref = parse(raw)

        val target = resolve(decompiler, ref)
        if (target == null) {
            LOG.warn("jadx-slides: no such class/member: @{}", raw)
            return
        }
        val node = mw.cacheObject.nodeCache.makeFrom(target)
        mw.tabsController.codeJump(node)
        val line = ref.line
        if (line != null) {
            // code loads asynchronously after codeJump; retry until the
            // area has content, then keep verifying — jadx's own def-pos
            // jump can land after ours and clobber it
            scrollToLine(mw, line, attempt = 0, verified = 0)
        }
        Slides.session?.focusSlidesSoon()
    }

    private fun resolve(d: JadxDecompiler, ref: Ref): JavaNode? {
        if (ref.member != null) {
            val cls = resolveClass(d, ref.name) ?: return null
            return resolveMember(d, cls, ref.member) ?: cls
        }
        val name = ref.name
        resolveClass(d, name)?.let { return it }

        val i = name.lastIndexOf('.')
        if (i > 0) {
            val cls = resolveClass(d, name.substring(0, i))
            if (cls != null) {
                return resolveMember(d, cls, name.substring(i + 1)) ?: cls
            }
        }
        if ('.' !in name) {
            return shortNameSearch(d, name)
        }
        return null
    }

    private fun resolveClass(d: JadxDecompiler, name: String): JavaClass? {
        d.searchJavaClassByOrigFullName(name)?.let { return it }
        runCatching { d.searchJavaClassByAliasFullName(name) }.getOrNull()?.let { return it }
        // inner classes may be written with '$'
        val dotted = name.replace('$', '.')
        if (dotted != name) {
            d.searchJavaClassByOrigFullName(dotted)?.let { return it }
            runCatching { d.searchJavaClassByAliasFullName(dotted) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun resolveMember(d: JadxDecompiler, cls: JavaClass, member: String): JavaNode? {
        cls.methods.firstOrNull { it.name == member }?.let { return it }
        cls.fields.firstOrNull { it.name == member }?.let { return it }
        // original (pre-rename) member names
        return runCatching {
            val mth = cls.classNode.methods.firstOrNull {
                it.name == member || it.alias == member
            }
            if (mth != null) d.getJavaNodeByRef(mth) else null
        }.getOrNull()
    }

    private fun shortNameSearch(d: JadxDecompiler, short: String): JavaClass? {
        val classes = runCatching { d.classesWithInners }.getOrElse { d.classes }
        // original names first (memory of the original binary), then aliases
        classes.firstOrNull { it.rawName.substringAfterLast('.') == short }?.let { return it }
        return classes.firstOrNull { it.name == short }
    }

    private fun scrollToLine(mw: MainWindow, line: Int, attempt: Int, verified: Int) {
        if (attempt > 30) return
        val timer = Timer(150) {
            val panel = mw.tabbedPane.selectedContentPanel as? AbstractCodeContentPanel
            val area = panel?.codeArea
            if (area == null || area.document.length == 0) {
                scrollToLine(mw, line, attempt + 1, verified)
                return@Timer
            }
            try {
                val ln = line.coerceIn(1, area.lineCount) - 1
                val offset = area.getLineStartOffset(ln)
                if (area.caretLineNumber != ln) {
                    area.scrollToPos(offset)
                }
                // verify twice more: jadx's queued def-pos jump may override
                if (verified < 2) {
                    scrollToLine(mw, line, attempt + 1, verified + 1)
                }
            } catch (e: Exception) {
                LOG.debug("line scroll failed", e)
            }
        }
        timer.isRepeats = false
        timer.start()
    }
}
