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

    val TOKEN_RE = Regex("(?<![A-Za-z0-9_@])@($NAME_PATTERN)(?::(\\d+))?")

    private val INLINE_CODE_RE = Regex("`[^`]*`")

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
        for ((line, inCode) in MarpMarkdown.iterFenced(body)) {
            out.add(if (inCode) line else rewriteLine(line, bridgePort))
        }
        return out.joinToString("\n") + if (text.endsWith("\n")) "\n" else ""
    }
}
