package spike.math

/**
 * Finds math in a chat message before it reaches the markdown renderer.
 *
 * Block math: `$$…$$` and `\[…\]` (may span lines). Inline math: `$…$` and `\(…\)`.
 * Never inside fenced code (``` / ~~~) or inline code spans. An unclosed delimiter stays literal text
 * (a streaming reply shows the raw `$$` until the closing one arrives).
 *
 * Inline `$` follows pandoc's rule plus one guard against prices:
 *  - the opening `$` is not followed by whitespace, the closing `$` is not preceded by whitespace
 *    and not followed by a digit;
 *  - if, while looking for the closer, another `$` turns up that cannot close, the opener is
 *    literal and scanning restarts at that `$` — so `价格 $5 与 $x+1$` gives one formula `x+1`,
 *    and `$5-$10` gives none.
 *  - `\$` is an escaped dollar, never a delimiter.
 */
object MathSyntax {
    const val OPEN = '\uE000'
    const val CLOSE = '\uE001'

    sealed interface Segment {
        data class Markdown(val text: String) : Segment
        data class Block(val tex: String) : Segment
    }

    /** Inline-math pass result: markdown with each formula replaced by `OPEN index CLOSE`. */
    data class Inline(val markdown: String, val formulas: List<String>)

    fun token(i: Int) = "$OPEN$i$CLOSE"

    // ---- block pass --------------------------------------------------------------------------

    fun splitBlocks(src: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val md = StringBuilder()
        fun flush() { if (md.isNotBlank()) out += Segment.Markdown(md.toString()); md.clear() }
        val code = CodeMask(src)
        var i = 0
        while (i < src.length) {
            if (!code.isCode(i) && !escaped(src, i)) {
                val (open, close) = when {
                    src.startsWith("$$", i) -> "$$" to "$$"
                    src.startsWith("\\[", i) -> "\\[" to "\\]"
                    else -> null to null
                }
                if (open != null && close != null) {
                    val end = findClose(src, i + open.length, close, code)
                    if (end >= 0) {
                        val tex = src.substring(i + open.length, end).trim()
                        if (tex.isNotEmpty()) { flush(); out += Segment.Block(tex); i = end + close.length; continue }
                    }
                }
            }
            md.append(src[i]); i++
        }
        flush()
        return out
    }

    private fun findClose(s: String, from: Int, close: String, code: CodeMask): Int {
        var j = from
        while (j <= s.length - close.length) {
            if (code.isCode(j)) return -1  // never run a formula into a code block
            if (s.startsWith(close, j) && !escaped(s, j)) return j
            j++
        }
        return -1
    }

    // ---- inline pass -------------------------------------------------------------------------

    fun extractInline(md: String): Inline {
        val code = CodeMask(md)
        val sb = StringBuilder()
        val formulas = mutableListOf<String>()
        var i = 0
        while (i < md.length) {
            val c = md[i]
            if (!code.isCode(i) && !escaped(md, i)) {
                // Every literal '$' is escaped for the markdown parser: intellij-markdown's GFM flavour has its own
                // $-math nodes, which the renderer silently drops (`从 $5 涨到 $10` lost its middle).
                if (md.startsWith("$$", i)) { sb.append("\\$\\$"); i += 2; continue } // an unclosed block delimiter stays literal
                if (c == '$') {
                    when (val r = scanDollar(md, i, code)) {
                        is Scan.Math -> { sb.append(token(formulas.size)); formulas += r.tex; i = r.next; continue }
                        is Scan.Literal -> { sb.append(md.substring(i, r.next).replace("$", "\\$")); i = r.next; continue }
                    }
                }
                if (md.startsWith("\\(", i)) {
                    val end = findClose(md, i + 2, "\\)", code)
                    if (end > i + 2) { sb.append(token(formulas.size)); formulas += md.substring(i + 2, end).trim(); i = end + 2; continue }
                }
            }
            sb.append(c); i++
        }
        return Inline(sb.toString(), formulas)
    }

    private sealed interface Scan { data class Math(val tex: String, val next: Int) : Scan; data class Literal(val next: Int) : Scan }

    private fun scanDollar(s: String, open: Int, code: CodeMask): Scan {
        val first = open + 1
        if (first >= s.length || s[first].isWhitespace()) return Scan.Literal(first)
        var j = first
        while (j < s.length) {
            if (code.isCode(j) || s[j] == '\n' && j + 1 < s.length && s[j + 1] == '\n') return Scan.Literal(first) // no formula across paragraphs/code
            if (s[j] == '$' && !escaped(s, j)) {
                val okLeft = j > first && !s[j - 1].isWhitespace()
                val okRight = j + 1 >= s.length || !s[j + 1].isDigit()
                return if (okLeft && okRight && !s.startsWith("$$", j)) Scan.Math(s.substring(first, j), j + 1)
                else Scan.Literal(j) // literal opener; rescan from this '$'
            }
            j++
        }
        return Scan.Literal(first)
    }

    private fun escaped(s: String, i: Int): Boolean {
        var n = 0; var k = i - 1
        while (k >= 0 && s[k] == '\\') { n++; k-- }
        return n % 2 == 1
    }

    /** Marks fenced code blocks and inline code spans. */
    private class CodeMask(s: String) {
        private val mask = BooleanArray(s.length)
        init {
            var i = 0
            var lineStart = true
            var fence: String? = null
            while (i < s.length) {
                if (lineStart) {
                    val k = s.indexOfFirstNonSpace(i)
                    val f = when { s.startsWith("```", k) -> "```"; s.startsWith("~~~", k) -> "~~~"; else -> null }
                    if (fence == null && f != null) fence = f
                    else if (fence != null && f == fence) { val e = s.lineEnd(i); for (x in i until e) mask[x] = true; fence = null; i = e; continue }
                }
                if (fence != null) { val e = s.lineEnd(i); for (x in i until e) mask[x] = true; i = e; lineStart = true; continue }
                if (s[i] == '`') {
                    var run = 0; while (i + run < s.length && s[i + run] == '`') run++
                    val ticks = "`".repeat(run)
                    var close = s.indexOf(ticks, i + run)
                    while (close >= 0 && close + run < s.length && s[close + run] == '`') close = s.indexOf(ticks, close + run + 1)
                    if (close >= 0) { for (x in i until close + run) mask[x] = true; i = close + run; lineStart = false; continue }
                    i += run; lineStart = false; continue
                }
                lineStart = s[i] == '\n'
                i++
            }
        }
        fun isCode(i: Int) = mask[i]
        private fun String.indexOfFirstNonSpace(from: Int): Int { var k = from; while (k < length && (this[k] == ' ' || this[k] == '\t')) k++; return k }
        private fun String.lineEnd(from: Int): Int { val n = indexOf('\n', from); return if (n < 0) length else n + 1 }
    }
}
