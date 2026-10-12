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

    fun prepare(src: String): Prepared {
        val code = CodeMask(src)
        val sb = StringBuilder(src.length + 16)
        val formulas = mutableListOf<Formula>()
        fun add(tex: String, display: Boolean) {
            sb.append(if (display) blockToken(formulas.size) else inlineToken(formulas.size))
            formulas += Formula(tex, display)
        }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (code.isCode(i) || escaped(src, i)) { sb.append(c); i++; continue }
            when {
                src.startsWith("$$", i) -> {
                    val end = findClose(src, i + 2, "$$", code)
                    val tex = if (end >= 0) src.substring(i + 2, end).trim() else ""
                    if (tex.isNotEmpty()) { add(fold(tex), true); i = end + 2 } else { sb.append("\\$\\$"); i += 2 }
                }
                src.startsWith("\\[", i) -> {
                    val end = findClose(src, i + 2, "\\]", code)
                    val tex = if (end >= 0) src.substring(i + 2, end).trim() else ""
                    if (tex.isNotEmpty()) { add(fold(tex), true); i = end + 2 } else { sb.append("\\["); i += 2 }
                }
                src.startsWith("\\(", i) -> {
                    val end = findClose(src, i + 2, "\\)", code)
                    val tex = if (end >= 0) src.substring(i + 2, end).trim() else ""
                    if (tex.isNotEmpty() && !Regex("\n[ \t\r]*\n").containsMatchIn(tex)) { add(fold(tex), false); i = end + 2 }
                    else { sb.append("\\("); i += 2 }
                }
                c == '$' -> when (val r = scanDollar(src, i, code)) {
                    is Scan.Math -> { add(fold(r.tex), false); i = r.next }
                    is Scan.Literal -> { sb.append(src.substring(i, r.next).replace("$", "\\$")); i = r.next }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return Prepared(sb.toString(), formulas)
    }

    /** Folds line breaks inside a formula (TeX treats them as spaces). */
    private fun fold(tex: String) = tex.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    private fun findClose(s: String, from: Int, close: String, code: CodeMask): Int {
        var j = from
        while (j <= s.length - close.length) {
            if (code.isCode(j)) return -1 // never run a formula into code
            if (s.startsWith(close, j) && !escaped(s, j)) return j
            j++
        }
        return -1
    }

    private sealed interface Scan { data class Math(val tex: String, val next: Int) : Scan; data class Literal(val next: Int) : Scan }

    private fun scanDollar(s: String, open: Int, code: CodeMask): Scan {
        val first = open + 1
        if (first >= s.length || s[first].isWhitespace()) return Scan.Literal(first)
        var j = first
        while (j < s.length) {
            if (code.isCode(j)) return Scan.Literal(first)
            if (s[j] == '\n' && blankLineFollows(s, j)) return Scan.Literal(first) // not across paragraphs
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

    private fun blankLineFollows(s: String, nl: Int): Boolean {
        var k = nl + 1
        while (k < s.length && (s[k] == ' ' || s[k] == '\t' || s[k] == '\r')) k++
        return k >= s.length || s[k] == '\n'
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
