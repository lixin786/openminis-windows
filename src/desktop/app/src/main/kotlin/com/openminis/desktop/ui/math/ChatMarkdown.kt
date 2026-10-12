// OpenMinis Desktop — math formulas in chat (P2-4).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui.math

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.MARKDOWN_TAG_URL
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.codeSpanStyle
import com.mikepenz.markdown.utils.linkTextSpanStyle
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Formulas of the message being composed (token n → formulas[n]). */
private val LocalFormulas = compositionLocalOf<List<MathSyntax.Formula>> { emptyList() }

/** Test hook: receives the scroll state of every block formula wider than its bubble. */
internal val LocalMathScrollProbe = staticCompositionLocalOf<((ScrollState) -> Unit)?> { null }

/**
 * Chat-bubble markdown with LaTeX math. The stock renderer is kept; only the components that draw text
 * (paragraph — which list items use too —, headings, block quote, table) are swapped for versions whose
 * inline content holds one placeholder *per formula* (the library's own inline-image slot shares a single
 * size across a paragraph). A message without math still goes through [MathSyntax.prepare], which
 * escapes literal `$` so the GFM parser cannot swallow prices. [renderMath] false (Settings › Appearance ›
 * "Render math" off) shows every formula as its source text.
 */
@Composable
fun ChatMarkdown(content: String, typography: MarkdownTypography, modifier: Modifier = Modifier, renderMath: Boolean = true) {
    val prepared = remember(content, renderMath) { MathSyntax.prepare(content, renderMath) }
    if (prepared.formulas.isEmpty()) {
        Markdown(content = prepared.markdown, modifier = modifier, typography = typography)
        return
    }
    val components = remember { mathComponents() }
    CompositionLocalProvider(LocalFormulas provides prepared.formulas) {
        Markdown(
            content = prepared.markdown, modifier = modifier, typography = typography, components = components,
            // only reached by text the swapped components do not draw (e.g. link text inside images): show the source
            annotator = markdownAnnotator { c, n -> appendTokens(c, n, prepared.formulas) { _, f -> append(source(f)) } },
        )
    }
}

private fun source(f: MathSyntax.Formula) = if (f.display) "$$${f.tex}$$" else "$${f.tex}$"

private fun mathComponents(): MarkdownComponents = markdownComponents(
    paragraph = { MathText(it.content, it.node, it.typography.paragraph) },
    heading1 = { MathText(it.content, it.node, it.typography.h1, MarkdownTokenTypes.ATX_CONTENT) },
    heading2 = { MathText(it.content, it.node, it.typography.h2, MarkdownTokenTypes.ATX_CONTENT) },
    heading3 = { MathText(it.content, it.node, it.typography.h3, MarkdownTokenTypes.ATX_CONTENT) },
    heading4 = { MathText(it.content, it.node, it.typography.h4, MarkdownTokenTypes.ATX_CONTENT) },
    heading5 = { MathText(it.content, it.node, it.typography.h5, MarkdownTokenTypes.ATX_CONTENT) },
    heading6 = { MathText(it.content, it.node, it.typography.h6, MarkdownTokenTypes.ATX_CONTENT) },
    setextHeading1 = { MathText(it.content, it.node, it.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT) },
    setextHeading2 = { MathText(it.content, it.node, it.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT) },
    blockQuote = { MathBlockQuote(it.content, it.node, it.typography.quote) },
    table = { MathTable(it.content, it.node, it.typography.text) },
)

/** Appends a TEXT node, expanding formula tokens through [onFormula]; false = not ours, library default. */
private fun AnnotatedString.Builder.appendTokens(
    content: String, node: ASTNode, formulas: List<MathSyntax.Formula>,
    onFormula: AnnotatedString.Builder.(Int, MathSyntax.Formula) -> Unit,
): Boolean {
    if (node.type != MarkdownTokenTypes.TEXT) return false
    val s = content.substring(node.startOffset, node.endOffset)
    if (s.none { it == MathSyntax.OPEN || it == MathSyntax.BLOCK_OPEN }) return false
    var i = 0
    while (i < s.length) {
        val o = s.indexOfFirst(i) { it == MathSyntax.OPEN || it == MathSyntax.BLOCK_OPEN }
        if (o < 0) { append(s.substring(i)); break }
        val close = s.indexOf(if (s[o] == MathSyntax.OPEN) MathSyntax.CLOSE else MathSyntax.BLOCK_CLOSE, o)
        if (close < 0) { append(s.substring(i)); break }
        append(s.substring(i, o))
        val idx = s.substring(o + 1, close).toIntOrNull()
        if (idx != null && idx in formulas.indices) onFormula(idx, formulas[idx])
        i = close + 1
    }
    return true
}

private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
    for (k in from until length) if (pred(this[k])) return k
    return -1
}

private fun TextStyle.sizePx(d: Density) = with(d) { (if (fontSize.isSpecified) fontSize else 16.sp).toPx() }

/**
 * One markdown text node with its formulas. Inline formulas become placeholders; a block formula inside the
 * node (a `$$` line in a list item or right under a sentence) splits the text and is drawn centred between
 * the halves. [singleLine] (table cells) draws block formulas inline at text size instead.
 */
@Composable
private fun MathText(
    content: String, node: ASTNode, style: TextStyle,
    contentChildType: IElementType? = null, modifier: Modifier = Modifier,
    color: Color = LocalMarkdownColors.current.text, singleLine: Boolean = false,
) {
    val formulas = LocalFormulas.current
    val typography = LocalMarkdownTypography.current
    val density = LocalDensity.current
    val textPx = style.sizePx(density)
    val argb = color.toArgb()
    val warn = SpanStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
    val target = contentChildType?.let { node.findChildOfType(it) } ?: node

    val inline = HashMap<String, InlineTextContent>()
    val blocks = ArrayList<Pair<Int, MathSyntax.Formula>>()
    val annotated = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, target, typography.linkTextSpanStyle, typography.codeSpanStyle, markdownAnnotator { c, n ->
            appendTokens(c, n, formulas) { idx, f ->
                if (f.display && !singleLine) { blocks += length to f; return@appendTokens }
                when (val r = MathRenderer.render(f.tex, textPx, argb, display = false)) {
                    is MathRenderer.Result.Ok -> {
                        val id = "math:$idx"
                        inline[id] = inlineFormula(r, density)
                        appendInlineContent(id, source(f)) // copying the selection yields the LaTeX source
                    }
                    is MathRenderer.Result.Error -> withStyle(warn) { append("⚠" + source(f)) }
                }
            }
        })
        pop()
    }

    if (blocks.isEmpty()) {
        LinkText(annotated, style, color, inline, modifier.fillMaxWidth(), singleLine)
        return
    }
    Column(modifier.fillMaxWidth()) {
        var from = 0
        for ((at, f) in blocks) {
            annotated.trimmedPiece(from, at)?.let { LinkText(it, style, color, inline, Modifier.fillMaxWidth(), false) }
            MathBlock(f.tex, style, color)
            from = at
        }
        annotated.trimmedPiece(from, annotated.length)?.let { LinkText(it, style, color, inline, Modifier.fillMaxWidth(), false) }
    }
}

private fun AnnotatedString.trimmedPiece(start: Int, end: Int): AnnotatedString? {
    var s = start; var e = end
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    return if (s < e) subSequence(s, e) else null
}

/**
 * Placeholder = formula width × height *above its baseline*, aligned AboveBaseline: the placeholder bottom sits on
 * the text baseline, so the formula's baseline lands on it. The depth below the baseline is drawn outside the
 * placeholder (no clipping), so it does not push the line box up. Verified on Windows @200 %: lines holding
 * only low formulas keep their height; ∑ / fractions grow a line by ~10 %.
 */
private fun inlineFormula(r: MathRenderer.Result.Ok, density: Density): InlineTextContent = with(density) {
    InlineTextContent(Placeholder(r.widthPx.toSp(), maxOf(1, r.ascentPx).toSp(), PlaceholderVerticalAlign.AboveBaseline)) {
        Box(Modifier.fillMaxSize().drawBehind { drawImage(r.bitmap, Offset(0f, 0f)) })
    }
}

/** Library MarkdownText's link handling, kept because that composable cannot take our inline content. */
@Composable
private fun LinkText(text: AnnotatedString, style: TextStyle, color: Color, inline: Map<String, InlineTextContent>, modifier: Modifier, singleLine: Boolean) {
    val uriHandler = LocalUriHandler.current
    val references = LocalReferenceLinkHandler.current
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val hasUrl = text.getStringAnnotations(MARKDOWN_TAG_URL, 0, text.length).isNotEmpty()
    val m = if (!hasUrl) modifier else modifier.pointerInput(text) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val target = layout.value?.let { l ->
                val pos = l.getOffsetForPosition(down.position)
                text.getStringAnnotations(MARKDOWN_TAG_URL, pos, pos).reversed().firstOrNull()?.let { references.find(it.item) }
            } ?: return@awaitEachGesture
            down.consume()
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            up.consume()
            runCatching { uriHandler.openUri(target) }
        }
    }
    MarkdownBasicText(
        text, style = style, modifier = m, color = color, inlineContent = inline,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { layout.value = it },
    )
}

/** Display formula, centred; wider than the bubble → horizontal scroll (wheel/touchpad) with a scrollbar. */
@Composable
private fun MathBlock(tex: String, style: TextStyle, color: Color) {
    val density = LocalDensity.current
    when (val r = MathRenderer.render(tex, style.sizePx(density), color.toArgb(), display = true)) {
        is MathRenderer.Result.Ok -> BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = tex }) {
            val image = Modifier
                .layout { m, _ -> val p = m.measure(Constraints.fixed(r.widthPx, r.bitmap.height)); layout(p.width, p.height) { p.place(0, 0) } }
                .drawBehind { drawImage(r.bitmap) }
            if (r.widthPx <= constraints.maxWidth) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Box(image) }
            } else {
                val scroll = rememberScrollState()
                LocalMathScrollProbe.current?.invoke(scroll)
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) { Box(image) }
                    HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth().padding(top = 2.dp))
                }
            }
        }
        is MathRenderer.Result.Error -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("⚠ " + r.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            Text("$$$tex$$", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

/** MarkdownBlockQuote with [MathText] (the library's calls MarkdownParagraph directly). */
@Composable
private fun MathBlockQuote(content: String, node: ASTNode, style: TextStyle) {
    val barColor = LocalMarkdownColors.current.text
    val thickness = LocalMarkdownDimens.current.blockQuoteThickness
    val padding = LocalMarkdownPadding.current
    Column(
        Modifier.drawBehind {
            val x = padding.blockQuoteBar.calculateLeftPadding(layoutDirection).toPx()
            drawLine(barColor, Offset(x, padding.blockQuoteBar.calculateTopPadding().toPx()),
                Offset(x, size.height - padding.blockQuoteBar.calculateBottomPadding().toPx()), thickness.toPx())
        }.padding(padding.blockQuote)
    ) {
        val quote = node.findChildOfType(MarkdownElementTypes.PARAGRAPH)
        if (quote != null) MathText(content, quote, style, modifier = Modifier.padding(padding.blockQuoteText))
        val nested = node.findChildOfType(MarkdownElementTypes.BLOCK_QUOTE)
        if (nested != null) {
            if (quote != null) Spacer(Modifier.height(8.dp))
            MathBlockQuote(content, nested, style)
        }
    }
}

/** MarkdownTable with [MathText] cells (the library's cells skip the annotator entirely). */
@Composable
private fun MathTable(content: String, node: ASTNode, style: TextStyle) {
    val dimens = LocalMarkdownDimens.current
    val columns = remember(node) { node.findChildOfType(GFMElementTypes.HEADER)?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0 }
    val tableWidth: Dp = dimens.tableCellWidth * columns
    BoxWithConstraints(Modifier.background(LocalMarkdownColors.current.tableBackground, RoundedCornerShape(dimens.tableCornerSize)).widthIn(max = dimens.tableMaxWidth)) {
        val scrollable = maxWidth <= tableWidth
        Column(if (scrollable) Modifier.horizontalScroll(rememberScrollState()).requiredWidth(tableWidth) else Modifier.fillMaxWidth()) {
            node.children.forEach { row ->
                when (row.type) {
                    GFMElementTypes.HEADER, GFMElementTypes.ROW -> Row(Modifier.widthIn(tableWidth), verticalAlignment = Alignment.CenterVertically) {
                        val cellStyle = if (row.type == GFMElementTypes.HEADER) style.copy(fontWeight = FontWeight.Bold) else style
                        row.children.filter { it.type == GFMTokenTypes.CELL }.forEach { cell ->
                            Box(Modifier.weight(1f).padding(dimens.tableCellPadding)) {
                                MathText(content, cell, cellStyle, color = LocalMarkdownColors.current.tableText, singleLine = true)
                            }
                        }
                    }
                    GFMTokenTypes.TABLE_SEPARATOR -> MarkdownDivider()
                }
            }
        }
    }
}
