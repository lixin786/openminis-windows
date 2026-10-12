// OpenMinis Desktop — math formulas in chat (P2-4).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui.math

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.m3.markdownTypography
import kotlin.test.*

class MathRenderTest {
    private fun prep(s: String) = MathSyntax.prepare(s)
    private fun tex(s: String) = prep(s).formulas.map { it.tex }
    private val I = MathSyntax::inlineToken
    private val B = MathSyntax::blockToken

    // ---- delimiters ------------------------------------------------------------------------------

    @Test fun inlineDelimiters() {
        val p = prep("设 \$a^2\$，当 \\(x\\) 时。")
        assertEquals("设 ${I(0)}，当 ${I(1)} 时。", p.markdown)
        assertEquals(listOf(MathSyntax.Formula("a^2", false), MathSyntax.Formula("x", false)), p.formulas)
    }

    @Test fun blockDelimiters() {
        val p = prep("前\n\$\$ E=mc^2 \$\$\n中\n\\[ a+b \\]\n后")
        assertEquals("前\n${B(0)}\n中\n${B(1)}\n后", p.markdown)
        assertEquals(listOf(MathSyntax.Formula("E=mc^2", true), MathSyntax.Formula("a+b", true)), p.formulas)
    }

    @Test fun multiLineBlockIsFoldedOntoOneLine() {
        val p = prep("1. 解：\n   \$\$\n   x = \\frac{1}{2}\n   \$\$\n2. 下一项")
        assertEquals("1. 解：\n   ${B(0)}\n2. 下一项", p.markdown) // the list keeps its shape
        assertEquals("x = \\frac{1}{2}", p.formulas.single().tex)
    }

    @Test fun inlineDoesNotCrossParagraphs() {
        assertEquals(emptyList(), tex("开头 \$a\n\n下一段 b\$"))
        assertEquals(listOf("a\n b".replace("\n", " ")), tex("跨行 \$a\n b\$ 同段"))
    }

    // ---- money -----------------------------------------------------------------------------------

    @Test fun pricesAreNotMath() {
        assertEquals(listOf("x+1"), tex("价格 \$5 与 \$x+1\$ 同时"))
        assertEquals(emptyList(), tex("从 \$5 涨到 \$10，区间 \$5-\$10"))
        assertEquals(emptyList(), tex("costs \$ 5 and \$6"))
        assertEquals(emptyList(), tex("\$5 and \$6 each"))
        assertEquals(listOf("a"), tex("\$a\$ then \$5"))
        assertEquals(emptyList(), tex("\$20,000 到 \$30,000"))
    }

    @Test fun literalDollarsAreEscapedForTheMarkdownParser() {
        assertEquals("从 \\\$5 涨到 \\\$10", prep("从 \$5 涨到 \$10").markdown)
        assertEquals("没有公式也转义 \\\$5", prep("没有公式也转义 \$5").markdown)
    }

    // ---- code and escapes --------------------------------------------------------------------------

    @Test fun codeIsSkipped() {
        assertEquals(emptyList(), tex("代码 `\$x^2\$` 不渲染"))
        assertEquals(emptyList(), tex("双反引号 `` a `\$x\$` b `` 不渲染"))
        assertEquals(emptyList(), tex("```\ncost = \$5; \$x\$ \$\$y\$\$\n```\n"))
        assertEquals(emptyList(), tex("~~~\n\$x\$\n~~~\n"))
        assertEquals(listOf("y"), tex("```\n\$x\$\n```\n后面 \$y\$"))
        assertEquals(emptyList(), tex("```kotlin\nval s = \"\$x\$\"\n")) // unclosed fence (streaming) is still code
        assertEquals("代码 `\$5`", prep("代码 `\$5`").markdown) // not escaped inside code
        assertEquals(emptyList(), tex("\$\$ a `code` \$\$".replace("`code`", "`x\$\$`"))) // never run into code
    }

    @Test fun escapedDollarsAreLiteral() {
        assertEquals(emptyList(), tex("转义 \\\$x\\\$ 不渲染"))
        assertEquals(emptyList(), tex("\\\$\\\$ x \\\$\\\$"))
    }

    // ---- streaming ---------------------------------------------------------------------------------

    @Test fun unclosedDelimitersStayLiteralWhileStreaming() {
        val s1 = prep("正在输出 \$\$ \\int_0^1")
        assertTrue(s1.formulas.isEmpty()); assertTrue(s1.markdown.startsWith("正在输出 \\\$\\\$"))
        assertTrue(prep("正在输出 \\[ \\int").formulas.isEmpty())
        assertTrue(prep("正在输出 \\( x").formulas.isEmpty())
        assertTrue(prep("行内 \$\\frac{a}").formulas.isEmpty())
        // the same text once the closer has arrived
        assertEquals(listOf("\\int_0^1 x"), tex("正在输出 \$\$ \\int_0^1 x \$\$"))
    }

    // ---- renderer ----------------------------------------------------------------------------------

    @Test fun badFormulasFallBackInsteadOfThrowing() {
        // JLaTeXMath is lenient with *incomplete* input (\frac{a}{, x^ render as best they can); these do fail:
        for (bad in listOf("\\foo{x}", "\\left( x", "\\begin{matrix} a", "a}")) {
            val r = MathRenderer.render(bad, 32f, -0x1000000, display = false)
            assertTrue(r is MathRenderer.Result.Error && r.message.isNotBlank(), "$bad -> $r")
        }
    }

    @Test fun goodFormulasHaveBaselineMetrics() {
        val r = MathRenderer.render("\\frac{\\sin x}{x}", 32f, -0x1000000, display = false)
        assertTrue(r is MathRenderer.Result.Ok && r.depthPx > 0 && r.ascentPx > 0 && r.bitmap.height == r.ascentPx + r.depthPx, r.toString())
        val x = MathRenderer.render("x", 32f, -0x1000000, display = false) as MathRenderer.Result.Ok
        assertTrue(x.ascentPx < r.ascentPx)
        assertTrue(MathRenderer.render("\\text{速度}=v", 32f, -0x1000000, display = false) is MathRenderer.Result.Ok)
        assertSame(r, MathRenderer.render("\\frac{\\sin x}{x}", 32f, -0x1000000, display = false)) // cached
        // rendered at device pixels: 2x font size → about 2x bitmap
        val r2 = MathRenderer.render("\\frac{\\sin x}{x}", 64f, -0x1000000, display = false) as MathRenderer.Result.Ok
        assertTrue(r2.widthPx in (r.widthPx * 2 - 3)..(r.widthPx * 2 + 3), "${r.widthPx} vs ${r2.widthPx}")
    }

    // ---- composed in a chat bubble -----------------------------------------------------------------

    private fun compose(text: String, w: Int = 900, h: Int = 1400, density: Float = 1f, probe: ((ScrollState) -> Unit)? = null): ImageComposeScene =
        ImageComposeScene(w, h, Density(density)) {
            MaterialTheme {
                Surface {
                    CompositionLocalProvider(LocalMathScrollProbe provides probe) {
                        SelectionContainer { ChatMarkdown(text, markdownTypography(), Modifier.fillMaxWidth()) }
                    }
                }
            }
        }

    @Test fun everyTextComponentRendersMath() {
        val text = """
            # 标题 ${'$'}h_1^{a}${'$'}
            段落 ${'$'}p_{a}${'$'} 与 ${'$'}5 价格。

            - 列表 ${'$'}l_{a}${'$'}
            1. 有序 ${'$'}o_{a}${'$'}
               ${'$'}${'$'} o_{block} ${'$'}${'$'}

            > 引用 ${'$'}q_{a}${'$'}

            | 列 | 值 |
            |---|---|
            | ${'$'}t_{a}${'$'} | ${'$'}${'$'} t_{b} ${'$'}${'$'} |

            $$ d_{a} $$
            错误 ${'$'}\foo${'$'} 之后 ${'$'}after_{a}${'$'}
            $$ \begin{matrix} a $$
            最后一句。
        """.trimIndent()
        val scene = compose(text, density = 2f)
        try {
            repeat(3) { scene.render(it * 16_000_000L) }
            // -Dopenminis.screenshots=<dir> also writes the bubble for visual review
            System.getProperty("openminis.screenshots")?.let { dir ->
                java.io.File(dir).mkdirs()
                java.io.File(dir, "math-components-200.png").writeBytes(scene.render(100_000_000L).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            }
        } finally { scene.close() }
        for (t in listOf("h_1^{a}", "p_{a}", "l_{a}", "o_{a}", "o_{block}", "q_{a}", "t_{a}", "t_{b}", "d_{a}", "after_{a}", "\\foo", "\\begin{matrix} a"))
            assertTrue(t in MathRenderer.rendered, "not rendered: $t")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun wideBlockScrollsHorizontally() {
        val wide = "(a+b)^{10} = a^{10} + 10a^9b + 45a^8b^2 + 120a^7b^3 + 210a^6b^4 + 252a^5b^5 + 210a^4b^6 + 120a^3b^7 + 45a^2b^8 + 10ab^9 + b^{10}"
        val states = mutableListOf<ScrollState>()
        val scene = compose("\$\$ $wide \$\$", w = 360, h = 300, probe = { if (it !in states) states += it })
        try {
            repeat(3) { scene.render(it * 16_000_000L) }
            val s = states.single()
            assertTrue(s.maxValue > 0, "the formula should overflow a 360 px bubble (maxValue=${s.maxValue})")
            assertEquals(0, s.value)
            var t = 100_000_000L
            // touchpad: horizontal delta
            repeat(5) { scene.sendPointerEvent(PointerEventType.Scroll, Offset(180f, 30f), scrollDelta = Offset(3f, 0f)); scene.render(t); t += 16_000_000L }
            val afterTouchpad = s.value
            assertTrue(afterTouchpad > 0, "horizontal wheel/touchpad delta should scroll")
            // mouse wheel with Shift
            repeat(5) {
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(180f, 30f), scrollDelta = Offset(0f, 3f),
                    keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true))
                scene.render(t); t += 16_000_000L
            }
            assertTrue(s.value > afterTouchpad, "Shift + wheel should scroll further (${s.value} vs $afterTouchpad)")
        } finally { scene.close() }
    }

    @Test fun narrowBlockDoesNotScroll() {
        val states = mutableListOf<ScrollState>()
        val scene = compose("\$\$ E=mc^2 \$\$", w = 360, h = 200, probe = { states += it })
        try { repeat(2) { scene.render(it * 16_000_000L) } } finally { scene.close() }
        assertTrue(states.isEmpty(), "a formula that fits is centred, not put in a scroller")
    }
}
