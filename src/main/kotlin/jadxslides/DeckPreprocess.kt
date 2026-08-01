package jadxslides

/**
 * Rewrite `@` reference tokens into clickable anchors at preprocess time.
 *
 * The anchor fires a fetch() against the local bridge (`/jump?t=<token>`),
 * which works identically in static Marp HTML and in Slidev's Vue SPA — no
 * JS injection into the rendered DOM is needed. Token forms:
 *
 *   @com.example.app.MainActivity          class (original or renamed FQN)
 *   @com.example.app.MainActivity:42       class, line 42 of the decompiled code
 *   @com.example.app.MainActivity.onCreate member of a class
 *   @Lcom/example/app/MainActivity;        smali class descriptor
 *   @Lcom/foo/Bar;->run()V                 smali method descriptor
 *   @MainActivity                          bare short class name
 *
 * Tokens inside fenced code blocks, inline backtick spans, and the front
 * matter are left alone so decks can document the syntax itself.
 */
object DeckPreprocess {

    // smali descriptor first so it wins over the FQN alternative;
    // the FQN alternative must not swallow a trailing sentence dot
    private const val NAME_PATTERN =
        "L[\\w/$]+;(?:->[\\w$<>]+(?:\\([^)\\s]*\\)[\\w/$;\\[]*)?)?" +
                "|[A-Za-z_$][\\w.$]*[\\w$]" +
                "|[A-Za-z_$]"

    // the lookbehind also rejects '/' and '\' so @-segments inside URLs and
    // paths (assets/@logo.png, node_modules/@marp-team/…) stay untouched
    val TOKEN_RE = Regex("(?<![A-Za-z0-9_@/\\\\])@($NAME_PATTERN)(?::(\\d+))?")

    // raw HTML style/script blocks: CSS at-rules (@media, @apply, …) and JS
    // decorators must not be rewritten into anchors
    private val HTML_RAW_OPEN = Regex("(?i)<(style|script)\\b")
    private val HTML_RAW_CLOSE = mapOf(
        "style" to Regex("(?i)</style\\s*>"),
        "script" to Regex("(?i)</script\\s*>"),
    )

    // backreference so double-backtick spans (``@x``) match as ONE span —
    // `[^`]*` would see two empty spans and leave the token exposed
    private val INLINE_CODE_RE = Regex("(`+)[^`]*?\\1")

    private const val LINK_STYLE =
        "color:#4fc3f7;text-decoration:underline;cursor:pointer;font-weight:600"

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** The anchor a token is rewritten into; bridgePort is fixed per session. */
    private fun anchor(token: String, line: String?, bridgePort: Int): String {
        val full = if (line != null) "$token:$line" else token
        val esc = htmlEscape(full)
        return "<a href=\"javascript:void(0)\" style=\"$LINK_STYLE\" " +
                "onclick=\"fetch('http://127.0.0.1:$bridgePort/jump?t='+" +
                "encodeURIComponent('$esc'));return false;\">@$esc</a>"
    }

    private fun rewriteLine(line: String, bridgePort: Int): String {
        if ('@' !in line) return line
        val codeSpans = INLINE_CODE_RE.findAll(line).map { it.range }.toList()
        return TOKEN_RE.replace(line) { m ->
            if (codeSpans.any { m.range.first in it }) {
                m.value // inside inline code — leave as-is
            } else {
                anchor(m.groupValues[1], m.groupValues[2].ifEmpty { null }, bridgePort)
            }
        }
    }

    /** Rewrite all tokens in deck text, skipping front matter and code fences. */
    fun rewrite(text: String, bridgePort: Int): String {
        val lines = text.lines()
        val fmEnd = MarpMarkdown.frontMatterEnd(lines)
        val body = if (fmEnd >= 0) lines.subList(fmEnd + 1, lines.size) else lines
        val head = if (fmEnd >= 0) lines.subList(0, fmEnd + 1) else emptyList()

        val out = ArrayList<String>(lines.size)
        out.addAll(head)
        // the two line-state machines are mutually exclusive, raw HTML first:
        // a line-leading ``` inside a <script> template literal must not open
        // a phantom fence (which would then hide the </script> forever)
        val fences = MarpMarkdown.FenceTracker()
        var rawTag: String? = null // inside a <style>/<script> block
        for (line in body) {
            // a `<style>` mentioned in backticks is prose, not an open tag —
            // match tags against the line with inline-code spans blanked out
            val masked = INLINE_CODE_RE.replace(line) { " ".repeat(it.value.length) }
            val tag = rawTag
            if (tag != null) {
                out.add(line)
                if (HTML_RAW_CLOSE.getValue(tag).containsMatchIn(masked)) rawTag = null
                continue
            }
            if (fences.feed(line)) {
                out.add(line)
                continue
            }
            val open = HTML_RAW_OPEN.find(masked)
            if (open != null) {
                out.add(line) // leave the whole opening line alone
                val t = open.groupValues[1].lowercase()
                if (!HTML_RAW_CLOSE.getValue(t).containsMatchIn(masked.substring(open.range.last + 1))) {
                    rawTag = t
                }
                continue
            }
            out.add(rewriteLine(line, bridgePort))
        }
        // text.lines() keeps a trailing empty element, so the join already
        // reproduces a trailing newline — appending another would grow the file
        return out.joinToString("\n")
    }
}
