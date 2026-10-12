package spike.math

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.codeSpanStyle
import com.mikepenz.markdown.utils.linkTextSpanStyle
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/** Spike diagnostics: draw each line's baseline (red) and line box (grey); report line heights. */
val LocalShowBaselines = compositionLocalOf { false }
val LocalLineReport = compositionLocalOf<(String, List<Int>) -> Unit> { { _, _ -> } }

private val WARN = Color(0xFFE0A100)

/**
 * A chat message with math. Block formulas are split out first; each markdown run goes to the
 * stock `Markdown` with a custom *paragraph* component that turns inline formulas into
 * per-formula `InlineTextContent` (the library's own inline-image slot shares one size across a
 * paragraph, so it cannot hold formulas of different sizes).
 */
@Composable
fun MathMarkdown(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { MathSyntax.splitBlocks(text) }
    Column(modifier) {
        segments.forEach { seg ->
            when (seg) {
                is MathSyntax.Segment.Block -> MathBlock(seg.tex)
                is MathSyntax.Segment.Markdown -> {
                    val inline = remember(seg.text) { MathSyntax.extractInline(seg.text) }
                    Markdown(
                        inline.markdown,
                        modifier = Modifier.fillMaxWidth(),
                        // anywhere other than a paragraph (headings, list items, tables) the spike shows the source
                        annotator = markdownAnnotator { content, node -> appendTokens(content, node, inline.formulas, null) },
                        components = markdownComponents(paragraph = { MathParagraph(it.content, it.node, inline.formulas) }),
                    )
                }
            }
        }
    }
}

/** Appends a TEXT node, expanding formula tokens via [onFormula] (or as `$source$` when null). */
private fun AnnotatedString.Builder.appendTokens(content: String, node: ASTNode, formulas: List<String>, onFormula: (AnnotatedString.Builder.(Int) -> Unit)?): Boolean {
    if (node.type != MarkdownTokenTypes.TEXT) return false
    val s = content.substring(node.startOffset, node.endOffset)
    if (MathSyntax.OPEN !in s) return false
    var i = 0
    while (i < s.length) {
        val o = s.indexOf(MathSyntax.OPEN, i)
        val c = if (o >= 0) s.indexOf(MathSyntax.CLOSE, o) else -1
        if (o < 0 || c < 0) { append(s.substring(i)); break }
        append(s.substring(i, o))
        val idx = s.substring(o + 1, c).toIntOrNull()
        if (idx != null && idx in formulas.indices) { if (onFormula != null) onFormula(idx) else append("$${formulas[idx]}$") }
        i = c + 1
    }
    return true
}

@Composable
private fun MathParagraph(content: String, node: ASTNode, formulas: List<String>) {
    val typography = LocalMarkdownTypography.current
    val style = typography.paragraph
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface
    val textPx = with(density) { (if (style.fontSize.isSpecified) style.fontSize else 16.sp).toPx() }
    val argb = color.toArgb()
    val monoStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = WARN)

    val inline = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, node, typography.linkTextSpanStyle, typography.codeSpanStyle, markdownAnnotator { c, n ->
            appendTokens(c, n, formulas) { idx ->
                when (val r = MathRenderer.render(formulas[idx], textPx, argb, display = false)) {
                    is MathRenderer.Result.Ok -> {
                        val id = "math:$idx"
                        inline[id] = inlineFormula(r, density)
                        appendInlineContent(id, "$${formulas[idx]}$")
                    }
                    is MathRenderer.Result.Error -> withStyle(monoStyle) { append("⚠$${formulas[idx]}$") }
                }
            }
        })
        pop()
    }

    val show = LocalShowBaselines.current
    val report = LocalLineReport.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    MarkdownBasicText(
        annotated, style = style, color = color, inlineContent = inline,
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val l = layout
            if (show && l != null) for (line in 0 until l.lineCount) {
                drawRect(Color.Gray.copy(alpha = 0.5f), Offset(0f, l.getLineTop(line)), androidx.compose.ui.geometry.Size(size.width, l.getLineBottom(line) - l.getLineTop(line)), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                drawLine(Color.Red.copy(alpha = 0.7f), Offset(0f, l.getLineBaseline(line)), Offset(size.width, l.getLineBaseline(line)), 1f)
            }
        },
        onTextLayout = { r ->
            layout = r
            report(annotated.text.take(12), (0 until r.lineCount).map { (r.getLineBottom(it) - r.getLineTop(it)).toInt() })
        },
    )
}

/**
 * Placeholder = formula width × height *above the baseline*, aligned AboveBaseline: the placeholder
 * bottom sits on the text baseline, so the formula's own baseline lands exactly there. The part below
 * the baseline (depth) is drawn outside the placeholder (Compose does not clip), so it does not push
 * the line box up the way a full-height placeholder would.
 */
private fun inlineFormula(r: MathRenderer.Result.Ok, density: androidx.compose.ui.unit.Density): InlineTextContent = with(density) {
    InlineTextContent(Placeholder(r.widthPx.toSp(), maxOf(1, r.ascentPx).toSp(), PlaceholderVerticalAlign.AboveBaseline)) {
        Box(Modifier.fillMaxSize().drawBehind { drawImage(r.bitmap, Offset(0f, 0f)) })
    }
}

@Composable
fun MathBlock(tex: String) {
    val style = MaterialTheme.typography.bodyLarge // same as the markdown paragraph style (m3 default)
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface
    val textPx = with(density) { (if (style.fontSize.isSpecified) style.fontSize else 16.sp).toPx() }
    when (val r = MathRenderer.render(tex, textPx, color.toArgb(), display = true)) {
        is MathRenderer.Result.Ok -> BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            val fits = r.widthPx <= constraints.maxWidth
            val img = Modifier
                .layout { m, _ -> val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(r.widthPx, r.bitmap.height)); layout(p.width, p.height) { p.place(0, 0) } }
                .drawBehind { drawImage(r.bitmap) }
            if (fits) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Box(img) }
            else Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { Box(img) }
        }
        is MathRenderer.Result.Error -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("⚠ 公式解析失败：${r.message}", color = WARN, style = MaterialTheme.typography.labelMedium)
            Text("$$$tex$$", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}
