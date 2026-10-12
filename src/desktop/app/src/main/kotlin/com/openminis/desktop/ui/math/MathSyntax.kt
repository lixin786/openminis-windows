// OpenMinis Desktop — math formulas in chat (P2-4).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui.math

/**
 * Finds math in a chat message before it reaches the markdown renderer and replaces each formula with a
 * private-use token the chat components expand (inline `\uE000 n \uE001`, block `\uE002 n \uE003`).
 *
 * - Block: `$$…$$`, `\[…\]` (may span lines; newlines inside are folded so the token stays on its line,
 *   which keeps lists and quotes intact). Inline: `$…$`, `\(…\)`.
 * - Never inside fenced code (``` / ~~~) or inline code spans.
 * - Unclosed delimiters stay literal text: a streaming reply shows `$$ \int…` raw until the closer arrives.
 * - Inline `$` follows pandoc (opener not followed by whitespace; closer not preceded by whitespace and not
 *   followed by a digit) plus a price guard: if another `$` that cannot close turns up first, the opener
 *   is literal and scanning restarts there — `价格 $5 与 $x+1$` → one formula `x+1`; `$5-$10` → none.
 * - `\$` is an escaped dollar. Every literal `$` is re-escaped for the markdown parser, because
 *   intellij-markdown's GFM flavour has its own `$` math nodes that the renderer drops silently
 *   (`从 $5 涨到 $10` used to lose its middle).
 */
object MathSyntax {
    const val OPEN = '\uE000'
    const val CLOSE = '\uE001'
    const val BLOCK_OPEN = '\uE002'
    const val BLOCK_CLOSE = '\uE003'

    data class Formula(val tex: String, val display: Boolean)

    /** [markdown] carries the tokens; token n refers to [formulas]`[n]`. */
    data class Prepared(val markdown: String, val formulas: List<Formula>)

    fun inlineToken(i: Int) = "$OPEN$i$CLOSE"
    fun blockToken(i: Int) = "$BLOCK_OPEN$i$BLOCK_CLOSE"

    /**
     * @param render false = the "Render math" setting is off: no tokens, every recognised formula is shown as its
     *   source (each ASCII punctuation character backslash-escaped so markdown keeps `\\`, `_`, `*` … as typed).
     */
    fun prepare(src: String, render: Boolean = true): Prepared {
        val code = CodeMask(src)
        val sb = StringBuilder(src.length + 16)
        val formulas = mutableListOf<Formula>()
        fun add(raw: String, tex: String, display: Boolean) {
            if (!render) { sb.append(escapeAll(raw)); return }
            sb.append(if (display) blockToken(formulas.size) else inlineToken(formulas.size))
            formulas += Formula(fold(tex), display)
        }
        fun delimited(i: Int, open: String, close: String, display: Boolean): Int {
            val end = findClose(src, i + open.length, close, code, blockLimit(src, i, open))
            val tex = if (end >= 0) src.substring(i + open.length, end).trim() else ""
            if (tex.isEmpty()) { sb.append(open.replace("$", "\\$")); return i + open.length }
            add(src.substring(i, end + close.length), tex, display)
            return end + close.length
        }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (code.isCode(i) || escaped(src, i)) { sb.append(c); i++; continue }
            i = when {
                src.startsWith("$$", i) -> delimited(i, "$$", "$$", true)
                src.startsWith("\\[", i) -> delimited(i, "\\[", "\\]", true)
                src.startsWith("\\(", i) -> delimited(i, "\\(", "\\)", false)
                c == '$' -> when (val r = scanDollar(src, i, code, blockLimit(src, i, "$"))) {
                    is Scan.Math -> { add(src.substring(i, r.next), r.tex, false); r.next }
                    is Scan.Literal -> { sb.append(src.substring(i, r.next).replace("$", "\\$")); r.next }
                }
                else -> { sb.append(c); i + 1 }
            }
        }
        return Prepared(sb.toString(), formulas)
    }

    /** Folds line breaks (and the indentation around them) inside a formula into one space, as TeX would. */
    private fun fold(tex: String) = tex.replace(LINE_BREAK, " ")
    private val LINE_BREAK = Regex("[ \\t]*\\r?\\n[ \\t]*") // with the indentation of list items / quotes

    private fun escapeAll(raw: String) = buildString(raw.length * 2) {
        for (ch in raw) { if (ch.code in 33..126 && !ch.isLetterOrDigit()) append('\\'); append(ch) }
    }

    private val HEADING = Regex("^ {0,3}#{1,6}(\\s|$)")
    private val TABLE_ROW = Regex("^\\s*\\|")
    /** Lines that start another block whatever the opener: heading, quote, table row, fence, rule / setext underline. */
    private val HARD_START = Regex("^\\s*(#{1,6}(\\s|$)|>|\\||```|~~~|(-{3,}|={3,}|\\*{3,}|_{3,})\\s*$)")
    private val LIST_MARKER = Regex("^\\s*([-*+]|\\d{1,9}[.)])(\\s|$)")

    /**
     * How far the closer of an opener at [pos] may be searched for: a formula never crosses a block boundary.
     * - Opener in a heading or table row: that line only.
     * - Otherwise up to the next blank line or a line starting another block (heading, quote, table row, fence,
     *   rule, list item). A display opener standing alone on its line (`$$` / `\[` … the formula … `$$`) may run
     *   past lines that merely look like list items (`+ b` inside an equation).
     */
    private fun blockLimit(s: String, pos: Int, open: String): Int {
        val ls = s.lastIndexOf('\n', pos - 1) + 1
        val le = s.indexOf('\n', pos).let { if (it < 0) s.length else it }
        val line = s.substring(ls, le)
        if (HEADING.containsMatchIn(line) || TABLE_ROW.containsMatchIn(line)) return le
        val standalone = open != "$" && line.trim() == open
        var k = le
        while (k < s.length) {
            val next = k + 1
            val ne = s.indexOf('\n', next).let { if (it < 0) s.length else it }
            val l = s.substring(next, ne)
            if (l.isBlank() || HARD_START.containsMatchIn(l) || (!standalone && LIST_MARKER.containsMatchIn(l))) return k
            k = ne
        }
        return s.length
    }

    private fun findClose(s: String, from: Int, close: String, code: CodeMask, limit: Int): Int {
        var j = from
        while (j <= minOf(s.length, limit) - close.length) {
            if (code.isCode(j)) return -1 // never run a formula into code
            if (s.startsWith(close, j) && !escaped(s, j)) return j
            j++
        }
        return -1
    }

    private sealed interface Scan { data class Math(val tex: String, val next: Int) : Scan; data class Literal(val next: Int) : Scan }

    private fun scanDollar(s: String, open: Int, code: CodeMask, limit: Int): Scan {
        val first = open + 1
        if (first >= s.length || s[first].isWhitespace()) return Scan.Literal(first)
        var j = first
        while (j < minOf(s.length, limit)) {
            if (code.isCode(j)) return Scan.Literal(first)
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

    /** Marks fenced code blocks (``` / ~~~, an unclosed fence runs to the end) and inline code spans. */
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
