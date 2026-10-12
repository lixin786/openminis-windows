package spike.math

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * JLaTeXMath → pixel-exact bitmap. Everything is in device pixels: the caller passes the font size
 * already multiplied by the screen density, and draws the bitmap 1:1 (no resampling = no blur).
 */
object MathRenderer {
    /** KaTeX draws math at 1.21em; Computer Modern next to a sans UI font looks right a bit below that. */
    const val SIZE_FACTOR = 1.15f

    sealed interface Result {
        /** [ascentPx] = height above the baseline, [depthPx] = below it; bitmap height = ascent + depth. */
        data class Ok(val bitmap: ImageBitmap, val widthPx: Int, val ascentPx: Int, val depthPx: Int) : Result
        data class Error(val message: String) : Result
    }

    private data class Key(val tex: String, val sizePx: Float, val argb: Int, val display: Boolean)

    private val cache = object : LinkedHashMap<Key, Result>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Result>?) = size > 512
    }

    /** The system font JLaTeXMath falls back to for Chinese (inside or outside \text{}), or null. */
    val cjkFont: String? by lazy { registerCjkFallback() }

    @Synchronized
    fun render(tex: String, textSizePx: Float, argb: Int, display: Boolean): Result {
        cjkFont // make sure the fallback is registered before the first parse
        return cache.getOrPut(Key(tex, textSizePx, argb, display)) { renderUncached(tex, textSizePx * SIZE_FACTOR, argb, display) }
    }

    private fun renderUncached(tex: String, sizePx: Float, argb: Int, display: Boolean): Result = try {
        val formula = TeXFormula(tex)
        val icon = formula.createTeXIcon(if (display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT, sizePx)
        icon.setForeground(java.awt.Color(argb, true))
        val w = maxOf(1, icon.iconWidth)
        val h = maxOf(1, icon.iconHeight)
        val depth = icon.iconDepth.coerceIn(0, h)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            icon.paintIcon(null, g, 0, 0)
        } finally { g.dispose() }
        Result.Ok(img.toComposeImageBitmap(), w, h - depth, depth)
    } catch (t: Throwable) {
        Result.Error(t.message?.lineSequence()?.firstOrNull()?.take(120) ?: t.javaClass.simpleName)
    }

    private val CJK_CANDIDATES = listOf(
        "Microsoft YaHei", "Microsoft YaHei UI", "DengXian", "SimHei", "SimSun",          // Windows
        "PingFang SC", "Hiragino Sans GB",                                                 // macOS
        "Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei",   // Linux
    )

    private fun registerCjkFallback(): String? {
        val families = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }.getOrDefault(emptySet())
        val font = CJK_CANDIDATES.firstOrNull { it in families } ?: return null
        listOf(
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
        ).forEach { runCatching { TeXFormula.registerExternalFont(it, font) } }
        return font
    }
}
