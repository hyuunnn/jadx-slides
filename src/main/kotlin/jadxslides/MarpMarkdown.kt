package jadxslides

/**
 * Structural rules of a Markdown deck: front matter and fenced code.
 * Port of ida-slides' marp_markdown.py so engine detection and token
 * rewriting agree on where the front matter ends and what is inside a fence.
 */
object MarpMarkdown {

    private val FENCE_RE = Regex("^\\s{0,3}(`{3,}|~{3,})")
    private val SEPARATOR_RE = Regex("^---\\s*$")

    /** Index of the closing `---` of a leading front-matter block, or -1. */
    fun frontMatterEnd(lines: List<String>): Int {
        if (lines.isEmpty() || !SEPARATOR_RE.matches(lines[0])) return -1
        for (i in 1 until lines.size) {
            if (SEPARATOR_RE.matches(lines[i])) return i
        }
        return -1
    }

    /** The lines inside a leading YAML front-matter block (empty if none). */
    fun frontMatterLines(text: String): List<String> {
        val lines = text.lines()
        val end = frontMatterEnd(lines)
        return if (end >= 0) lines.subList(1, end) else emptyList()
    }

    /**
     * Pair each line with `inCode` using CommonMark fence tracking: a closing
     * fence must repeat the opening character and be at least as long — an
     * inner ``` does not close a ```` block, and a tilde fence is not closed
     * by a backtick fence. Fence marker lines themselves count as code.
     */
    fun iterFenced(lines: List<String>): List<Pair<String, Boolean>> {
        var fence: String? = null
        val out = ArrayList<Pair<String, Boolean>>(lines.size)
        for (line in lines) {
            val m = FENCE_RE.find(line)
            if (m != null) {
                val marker = m.groupValues[1]
                val f = fence
                if (f == null) {
                    fence = marker
                } else if (marker[0] == f[0] && marker.length >= f.length) {
                    fence = null
                }
                out.add(line to true)
                continue
            }
            out.add(line to (fence != null))
        }
        return out
    }

    /** Normalize a front-matter scalar: drop unquoted inline comments and quotes. */
    private fun yamlScalar(raw: String): String {
        var v = raw.trim()
        if (v.firstOrNull() !in listOf('\'', '"')) {
            v = v.substringBefore('#').trim()
        }
        if (v.length >= 2 && v.first() == v.last() && v.first() in listOf('\'', '"')) {
            v = v.substring(1, v.length - 1)
        }
        return v
    }

    private val FM_KEY_RE = Regex("^([A-Za-z_-]+)\\s*:\\s*(.*)$")

    /** Top-level front-matter keys mapped to their normalized scalar values. */
    fun frontMatterKeys(text: String): Map<String, String> {
        val keys = LinkedHashMap<String, String>()
        for (line in frontMatterLines(text)) {
            val m = FM_KEY_RE.find(line) ?: continue
            keys[m.groupValues[1]] = yamlScalar(m.groupValues[2])
        }
        return keys
    }
}
