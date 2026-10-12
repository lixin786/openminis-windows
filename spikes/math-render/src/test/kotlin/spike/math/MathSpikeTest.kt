package spike.math

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class MathSpikeTest {
    private fun inl(s: String) = MathSyntax.extractInline(s)

    @Test fun pricesAreNotMath() {
        assertEquals(listOf("x+1"), inl("价格 \$5 与 \$x+1\$ 同时").formulas)
        assertEquals(emptyList(), inl("从 \$5 涨到 \$10，区间 \$5-\$10").formulas)
        assertEquals(emptyList(), inl("costs \$ 5 and \$6").formulas)
        assertEquals(listOf("a"), inl("\$a\$ then \$5").formulas)
        // literal dollars reach the markdown parser escaped (its GFM flavour would eat $…$ spans)
        assertEquals("从 \\\$5 涨到 \\\$10", inl("从 \$5 涨到 \$10").markdown)
    }

    @Test fun escapesAndCodeAreSkipped() {
        assertEquals(emptyList(), inl("转义 \\\$x\\\$ 不渲染").formulas)
        assertEquals(emptyList(), inl("代码 `\$x^2\$` 不渲染").formulas)
        assertEquals(emptyList(), inl("```\ncost = \$5; \$x\$\n```\n").formulas)
        assertEquals(listOf("y"), inl("```\n\$x\$\n```\n后面 \$y\$").formulas)
        assertEquals(listOf("a+b"), inl("括号 \\(a+b\\) 也算").formulas)
    }

    @Test fun inlineTokensReplaceFormulas() {
        val r = inl("设 \$a^2\$，当 \$x\$。")
        assertEquals("设 ${MathSyntax.token(0)}，当 ${MathSyntax.token(1)}。", r.markdown)
    }

    @Test fun blocks() {
        val s = MathSyntax.splitBlocks("前\n\$\$ E=mc^2 \$\$\n中 \\[ a \\] 后")
        assertEquals(listOf<MathSyntax.Segment>(MathSyntax.Segment.Markdown("前\n"), MathSyntax.Segment.Block("E=mc^2"), MathSyntax.Segment.Markdown("\n中 "), MathSyntax.Segment.Block("a"), MathSyntax.Segment.Markdown(" 后")), s)
        // unclosed (streaming) stays literal, and is not re-read as inline math
        val u = MathSyntax.splitBlocks("正在输出 \$\$ \\int_0^1")
        assertEquals(1, u.size); assertTrue(u[0] is MathSyntax.Segment.Markdown)
        assertEquals(emptyList(), inl((u[0] as MathSyntax.Segment.Markdown).text).formulas)
        // \$\$ inside a code fence is not a block
        assertTrue(MathSyntax.splitBlocks("```\n\$\$x\$\$\n```\n").none { it is MathSyntax.Segment.Block })
    }

    @Test fun badFormulasFallBackInsteadOfThrowing() {
        // JLaTeXMath is lenient with *incomplete* input (\\frac{a}{, x^, {a render as best it can); these do fail:
        for (bad in listOf("\\foo{x}", "\\left( x", "\\begin{matrix} a", "a}")) {
            val r = MathRenderer.render(bad, 32f, -0x1000000, display = false)
            assertTrue(r is MathRenderer.Result.Error, "$bad -> $r")
        }
        val ok = MathRenderer.render("\\frac{\\sin x}{x}", 32f, -0x1000000, display = false)
        assertTrue(ok is MathRenderer.Result.Ok && ok.depthPx > 0 && ok.ascentPx > 0, ok.toString())
        val zh = MathRenderer.render("\\text{速度}=v", 32f, -0x1000000, display = false)
        assertTrue(zh is MathRenderer.Result.Ok, zh.toString())
    }

    /** -Dspike.shots=/dir writes PNGs at 100% and 200% for visual review. */
    @Test fun screenshots() {
        val out = System.getProperty("spike.shots")?.let(::File) ?: return
        out.mkdirs()
        for ((scale, base) in listOf(2f to true, 2f to false, 1f to false)) {
            val w = (1240 * scale).toInt(); val h = (1700 * scale).toInt()
            val scene = ImageComposeScene(w, h, Density(scale)) { MaterialTheme { Surface { SpikeContent(showBaselines = base, onToggle = null) } } }
            try {
                repeat(3) { scene.render(it * 16_000_000L) }
                val img = scene.render(100_000_000L)
                File(out, "spike-${(scale * 100).toInt()}${if (base) "-baselines" else ""}.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { scene.close() }
        }
    }
}
