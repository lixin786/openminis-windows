// OpenMinis Desktop — main window content: resizable sidebar (conversation list) + chat pane.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.mikepenz.markdown.m3.Markdown
import com.openminis.desktop.core.Attachment
import com.openminis.desktop.core.AttachmentKind
import com.openminis.desktop.core.AttachmentStore
import com.openminis.desktop.core.CompactMarker
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import com.openminis.desktop.core.ChatMessage
import com.openminis.desktop.core.ChatRole
import com.openminis.desktop.core.Conversation
import com.openminis.desktop.core.DesktopProviderFactory
import com.openminis.desktop.core.ProviderKind
import com.openminis.desktop.core.Round
import com.openminis.desktop.core.ToolCall
import com.openminis.desktop.core.ToolStatus
import java.awt.Cursor
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Files / images from a drag-and-drop or the clipboard. Files win; an image is only taken when
 * the transfer carries no plain text (so copying cells from Excel still pastes text).
 * Returns true when something was attached.
 */
internal fun attachFromTransferable(state: AppState, t: Transferable, preferTextOverImage: Boolean): Boolean {
    return try {
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty()
            if (files.isEmpty()) return false
            state.attachFiles(files); true
        } else if (t.isDataFlavorSupported(DataFlavor.imageFlavor) &&
            !(preferTextOverImage && t.isDataFlavorSupported(DataFlavor.stringFlavor))
        ) {
            val img = t.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return false
            state.attachImage(img); true
        } else false
    } catch (e: Exception) {
        com.openminis.app.logging.AppLogger.warning("Attach", "transfer failed: ${e.message}")
        false
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun App(state: AppState) {
    val dropTarget = remember(state) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { state.dragHover = true }
            override fun onExited(event: DragAndDropEvent) { state.dragHover = false }
            override fun onEnded(event: DragAndDropEvent) { state.dragHover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                state.dragHover = false
                return attachFromTransferable(state, event.awtTransferable, preferTextOverImage = false)
            }
        }
    }
    MinisTheme(state.settings.darkTheme) {
        Surface(
            Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
            color = MaterialTheme.colorScheme.background,
        ) {
            var sidebarWidth by remember { mutableStateOf(270.dp) }
            val density = LocalDensity.current
            Row(Modifier.fillMaxSize()) {
                Sidebar(state, Modifier.width(sidebarWidth).fillMaxHeight())
                // Drag handle to resize the sidebar.
                Box(
                    Modifier.width(5.dp).fillMaxHeight()
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                sidebarWidth = (sidebarWidth + with(density) { delta.toDp() }).coerceIn(180.dp, 520.dp)
                            },
                        )
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                )
                ChatPane(state, Modifier.weight(1f).fillMaxHeight())
            }
            if (state.dragHover) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
                        Text("Drop files to attach them to this chat", Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            state.pendingApproval?.let { ApprovalDialog(state, it) }
            if (state.showSettings) SettingsDialog(state) { state.showSettings = false }
            state.toast?.let { msg ->
                AlertDialog(onDismissRequest = { state.toast = null }, confirmButton = {
                    TextButton(onClick = { state.toast = null }) { Text("OK") }
                }, text = { Text(msg) })
            }
        }
    }
}

// ---------------------------------------------------------------- Sidebar

@Composable
private fun Sidebar(state: AppState, modifier: Modifier) {
    var filter by remember { mutableStateOf("") }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OpenMinis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.showSettings = true }) { Icon(Icons.Default.Settings, "Settings (Ctrl+,)") }
        }
        Spacer(Modifier.height(6.dp))
        FilledTonalButton(onClick = { state.newChat() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("New chat  (Ctrl+N)")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text("Search chats") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().tabMovesFocus().semantics { contentDescription = "Search chats" }, textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        val items = state.conversations.filter {
            filter.isBlank() || it.title.contains(filter, true) || it.messages.any { m -> m.displayText.contains(filter, true) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { c -> ConversationRow(state, c) }
        }
        Text(
            "Workspace: ${state.settings.workspace().absolutePath}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { state.openInExplorer(state.settings.workspace()) }.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ConversationRow(state: AppState, c: Conversation) {
    val selected = c.id == state.selectedId
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(hover)
            .clickable { state.select(c.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(
                (if (state.runningConversationId == c.id) "● running · " else "") + SimpleDateFormat("MM-dd HH:mm").format(Date(c.updatedAt)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hovered || selected) {
            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.DeleteOutline, "Delete", modifier = Modifier.size(18.dp))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete chat?") },
            text = { Text("“${c.title}” will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; state.delete(c.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Chat pane

@Composable
private fun ChatPane(state: AppState, modifier: Modifier) {
    val conv = state.selected
    Column(modifier) {
        TopBar(state, conv)
        HorizontalDivider()
        val messages = conv?.messages.orEmpty()
        val streaming = state.streaming.takeIf { state.runningConversationId == conv?.id }
        val listState = rememberLazyListState()
        val total = messages.size + (if (streaming != null || state.runningConversationId == conv?.id) 1 else 0)
        // Follow the stream while the user is at the bottom.
        LaunchedEffect(total, streaming?.displayText?.length, streaming?.rounds?.sumOf { it.toolCalls.size }) {
            if (total > 0) {
                val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= total - 2 } ?: true
                if (atBottom) listState.scrollToItem(total - 1, Int.MAX_VALUE / 2)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && streaming == null) {
                EmptyState(state)
            } else {
                val markers = conv?.compactMarkers.orEmpty()
                // Message index after which each marker's divider goes (anchor ids may be "<msgId>#<round>").
                val dividerAfter = remember(messages, markers) {
                    markers.associateBy { mk -> messages.indexOfFirst { it.id == mk.lastCompactedMessageId.substringBefore('#') } }
                }
                val lastCompactedIdx = dividerAfter.keys.maxOrNull() ?: -1
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
                    itemsIndexed(messages, key = { _, m -> m.id }) { idx, m ->
                        Box(Modifier.alpha(if (idx <= lastCompactedIdx) 0.55f else 1f)) {
                            MessageItem(state, m, isLast = m === messages.last())
                        }
                        dividerAfter[idx]?.let { mk -> CompactDivider(state, conv!!.id, mk, isLatest = mk === markers.last()) }
                    }
                    if (state.runningConversationId == conv?.id) {
                        item(key = "streaming") {
                            if (streaming != null) MessageItem(state, streaming, isLast = false, live = true)
                            else Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Thinking…")
                            }
                        }
                    }
                }
            }
        }
        val status = state.statusText.takeIf { state.runningConversationId == conv?.id || state.compactingConversationId == conv?.id }
        if (status != null || state.compactingConversationId == conv?.id) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(status ?: "Compacting context…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Composer(state)
    }
}

@Composable
private fun CompactDivider(state: AppState, convId: String, mk: CompactMarker, isLatest: Boolean) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  Context compacted${if (mk.automatic) " automatically" else ""} · ${mk.compactedCount} message(s) summarized  ",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { show = true }) { Text("View summary") }
        if (isLatest) TextButton(onClick = { state.revertCompact(convId) }, enabled = !state.isRunning) { Text("Undo") }
        HorizontalDivider(Modifier.weight(1f))
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Context summary") },
            text = {
                Column {
                    Text("Written by ${mk.model ?: "the model"} · ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(mk.createdAt))}",
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                    MonoBlock(mk.summary)
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun TopBar(state: AppState, conv: Conversation?) {
    val s = state.settings
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(conv?.title ?: "OpenMinis", style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val reading = conv?.id?.let { state.contextReadings[it] }
        if (reading != null && reading.window > 0) {
            val pct = (reading.measuredTokens * 100L / reading.window).toInt()
            val near = reading.compactThreshold > 0 && reading.measuredTokens >= reading.compactThreshold * 0.8
            Text(
                "ctx ≈${fmtK(reading.measuredTokens)} / ${fmtK(reading.window)} ($pct%)",
                style = MaterialTheme.typography.labelMedium,
                color = if (near) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        IconButton(
            onClick = { state.compactNow() },
            enabled = conv != null && conv.messages.isNotEmpty() && !state.isRunning && state.compactingConversationId == null,
        ) { Icon(Icons.Default.Compress, "Compact context (/compact)") }
        Dropdown(
            value = s.activeProvider, options = ProviderKind.entries, label = { it.label },
            onSelect = { state.updateSettings(s.copy(activeProvider = it)) },
        )
        val kind = s.activeProvider
        val ps = s.provider(kind)
        val current = ps.effectiveModel(kind)
        Dropdown(
            value = current,
            options = (listOf(current) + DesktopProviderFactory.suggestedModels(kind)).distinct(),
            label = { it },
            onSelect = { state.updateSettings(s.withProvider(kind, ps.copy(model = it))) },
        )
        Dropdown(
            value = s.thinkingLevel, options = listOf("OFF", "LOW", "MEDIUM", "HIGH"), prefix = "Thinking: ",
            label = { it.lowercase().replaceFirstChar(Char::uppercase) },
            onSelect = { state.updateSettings(s.copy(thinkingLevel = it)) },
        )
    }
}

@Composable
private fun EmptyState(state: AppState) {
    val s = state.settings
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("How can I help?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${s.activeProvider.label} · ${s.provider().effectiveModel(s.activeProvider)}" +
                (if (s.toolsEnabled) " · tools: shell (${s.shell.label}), files, memory" else " · tools off"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.provider().apiKey.isBlank() && s.activeProvider != ProviderKind.OPENAI_COMPATIBLE) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = { state.showSettings = true }) { Text("Add an API key") }
        }
    }
}

@Composable
private fun MessageItem(state: AppState, m: ChatMessage, isLast: Boolean, live: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).widthIn(max = 980.dp)) {
        if (m.role == ChatRole.USER) {
            if (m.attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        m.attachments.forEach { a -> AttachmentChip(state, a, onRemove = null) }
                    }
                }
            }
            if (m.text.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.widthIn(max = 720.dp),
                ) {
                    SelectionContainer { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                }
            }
        } else {
            m.notices.forEach { n ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(n, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            m.rounds.forEach { r -> RoundView(r) }
            m.error?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SelectionContainer { Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(10.dp)) }
                }
            }
            if (live) {
                LinearProgressIndicator(Modifier.width(80.dp).padding(top = 6.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(m.displayText)) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp))
                    }
                    if (isLast) IconButton(onClick = { state.regenerate() }, modifier = Modifier.size(30.dp), enabled = !state.isRunning) {
                        Icon(Icons.Default.Refresh, "Regenerate", Modifier.size(16.dp))
                    }
                    m.modelLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                    if (m.inputTokens != null || m.outputTokens != null) {
                        Text("↑${m.inputTokens ?: 0} ↓${m.outputTokens ?: 0} tokens", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundView(r: Round) {
    if (r.thinking.isNotBlank()) {
        var open by remember { mutableStateOf(false) }
        Text(
            if (open) "▾ Thinking" else "▸ Thinking (${r.thinking.length} chars)",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) SelectionContainer {
            Text(r.thinking, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
    }
    if (r.text.isNotBlank()) {
        SelectionContainer {
            val t = MaterialTheme.typography
            Markdown(
                content = r.text, modifier = Modifier.fillMaxWidth(),
                typography = com.mikepenz.markdown.m3.markdownTypography(
                    h1 = t.headlineSmall, h2 = t.titleLarge, h3 = t.titleMedium,
                    h4 = t.titleSmall, h5 = t.titleSmall, h6 = t.titleSmall,
                ),
            )
        }
    }
    r.toolCalls.forEach { ToolCallCard(it) }
}

@Composable
private fun ToolCallCard(tc: ToolCall) {
    var open by remember { mutableStateOf(false) }
    val (icon, tint) = when (tc.status) {
        ToolStatus.DONE -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        ToolStatus.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
        ToolStatus.DENIED -> Icons.Default.Block to MaterialTheme.colorScheme.error
        else -> Icons.Default.Build to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val summary = remember(tc.argsJson) { toolSummary(tc) }
    Surface(
        shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tc.status == ToolStatus.RUNNING || tc.status == ToolStatus.PENDING)
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(icon, tc.status.name.lowercase(), tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(tc.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                tc.durationMs?.let { Text("${it}ms", style = MaterialTheme.typography.labelSmall) }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            }
            if (open) {
                Spacer(Modifier.height(6.dp))
                Text("Arguments", style = MaterialTheme.typography.labelSmall)
                MonoBlock(prettyJson(tc.argsJson))
                tc.result?.let {
                    Text("Result", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    MonoBlock(it.take(20_000))
                }
            }
        }
    }
}

@Composable
private fun MonoBlock(text: String) {
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        SelectionContainer {
            Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

internal fun toolSummary(tc: ToolCall): String = runCatching {
    val o = org.json.JSONObject(tc.argsJson)
    when (tc.name) {
        "shell" -> o.optString("command")
        "file_read", "file_write", "file_edit", "list_dir" -> o.optString("path")
        "memory_write" -> o.optString("content").lineSequence().firstOrNull().orEmpty()
        "memory_get" -> o.optString("keywords")
        else -> tc.argsJson
    }.replace('\n', ' ')
}.getOrDefault(tc.argsJson.take(120))

internal fun fmtK(n: Int): String = if (n >= 1000) "${(n + 500) / 1000}k" else n.toString()

private fun prettyJson(s: String): String = runCatching { org.json.JSONObject(s).toString(2) }.getOrDefault(s)

// ---------------------------------------------------------------- Composer

@Composable
private fun Composer(state: AppState) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.composerFocusTick) { runCatching { focus.requestFocus() } }
    fun submit() {
        if (state.send(value.text)) value = TextFieldValue("")
    }
    val pending = state.pending()
    if (pending.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp).horizontalScroll(rememberScrollState())) {
            pending.forEach { a -> AttachmentChip(state, a, onRemove = { state.removePending(a) }) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
        IconButton(onClick = {
            val chooser = javax.swing.JFileChooser().apply { isMultiSelectionEnabled = true; dialogTitle = "Attach files" }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) state.attachFiles(chooser.selectedFiles.toList())
        }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.AttachFile, "Attach files (or drag & drop, Ctrl+V)") }
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 260.dp).focusRequester(focus)
                .semantics { contentDescription = "Message input. Enter sends, Shift+Enter new line, Tab moves focus" }
                .tabMovesFocus()
                .onPreviewKeyEvent { ev ->
                    // Enter = send, Shift+Enter = newline. Don't steal Enter while an IME is composing.
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter) &&
                        !ev.isShiftPressed && !ev.isAltPressed && value.composition == null
                    ) {
                        submit(); true
                    } else if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter) &&
                        ev.isShiftPressed && value.composition == null
                    ) {
                        // Shift+Enter: insert a newline at the caret (replacing any selection).
                        val sel = value.selection
                        val t = value.text.replaceRange(sel.min, sel.max, "\n")
                        value = TextFieldValue(t, TextRange(sel.min + 1)); true
                    } else if (ev.type == KeyEventType.KeyDown && ev.key == Key.V && ev.isCtrlPressed && !ev.isAltPressed) {
                        // Ctrl+V: files / screenshots become attachments; plain text pastes normally.
                        val clip = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
                        clip != null && attachFromTransferable(state, clip, preferTextOverImage = true)
                    } else if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape && state.isRunning) {
                        state.stop(); true
                    } else false
                },
            placeholder = { Text("Message OpenMinis — Enter to send · drop/paste files · /compact") },
            maxLines = 12,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        if (state.isRunning) {
            FilledTonalIconButton(onClick = { state.stop() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Stop, "Stop (Esc)") }
        } else {
            FilledIconButton(onClick = { submit() }, enabled = value.text.isNotBlank() || pending.isNotEmpty(), modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.Send, "Send")
            }
        }
    }
}

// ---------------------------------------------------------------- Attachments

private val thumbCache = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 64
}

private fun thumbnail(f: File): ImageBitmap? = synchronized(thumbCache) {
    thumbCache.getOrPut(f.absolutePath + f.lastModified()) {
        runCatching {
            val img = javax.imageio.ImageIO.read(f) ?: return@runCatching null
            val scale = 96.0 / maxOf(img.width, img.height)
            val w = maxOf(1, (img.width * minOf(1.0, scale)).toInt()); val h = maxOf(1, (img.height * minOf(1.0, scale)).toInt())
            val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, w, h, null); dispose()
            }
            out.toComposeImageBitmap()
        }.getOrNull()
    }
}

@Composable
internal fun AttachmentChip(state: AppState, a: Attachment, onRemove: (() -> Unit)?) {
    val file = remember(a.id) { state.attachmentFile(a) }
    val thumb by produceState<ImageBitmap?>(null, a.id) {
        if (a.kind == AttachmentKind.IMAGE) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { thumbnail(file) }
    }
    Surface(
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 6.dp).height(44.dp).clickable { state.openFile(file) },
    ) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = thumb
            if (t != null) Image(t, a.fileName, Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            else Icon(
                when (a.kind) { AttachmentKind.IMAGE -> Icons.Default.Image; AttachmentKind.TEXT -> Icons.Default.Description; else -> Icons.Default.InsertDriveFile },
                null, Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.widthIn(max = 180.dp)) {
                Text(a.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Text(
                    AttachmentStore.humanSize(a.sizeBytes) + " · " + when (a.kind) { AttachmentKind.IMAGE -> "image"; AttachmentKind.TEXT -> "text (inlined)"; else -> "file (path given to agent)" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Close, "Remove ${a.fileName}", Modifier.size(16.dp)) }
        }
    }
}

// ---------------------------------------------------------------- Approval

/**
 * Keyboard: Enter = Allow, Esc = Deny, Ctrl+Enter or A = Always allow. The Allow button takes focus
 * when the dialog opens (Tab / Shift+Tab move between the buttons, Space/Enter press the focused one).
 */
@Composable
private fun ApprovalDialog(state: AppState, p: PendingApproval) {
    val allowFocus = remember { FocusRequester() }
    LaunchedEffect(p) { kotlinx.coroutines.delay(60); runCatching { allowFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { state.answerApproval(allow = false) },   // Esc
        modifier = Modifier.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                (ev.key == Key.Enter || ev.key == Key.NumPadEnter) && ev.isCtrlPressed -> { state.answerApproval(allow = true, dontAskAgain = true); true }
                ev.key == Key.A && !ev.isCtrlPressed && !ev.isAltPressed -> { state.answerApproval(allow = true, dontAskAgain = true); true }
                ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { state.answerApproval(allow = true); true }
                ev.key == Key.Escape -> { state.answerApproval(allow = false); true }
                else -> false
            }
        }.semantics { contentDescription = "Approval needed: ${p.request.summary}" },
        icon = { Icon(Icons.Default.Terminal, null) },
        title = { Text(p.request.summary) },
        text = {
            Column {
                Text("The agent wants to run this on your computer:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                MonoBlock(p.request.detail)
                Spacer(Modifier.height(6.dp))
                Text("Enter = Allow · Esc = Deny · Ctrl+Enter or A = Always allow", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { state.answerApproval(allow = true, dontAskAgain = true) },
                    modifier = Modifier.semantics { contentDescription = "Always allow (Ctrl+Enter or A)" }) { Text("Always allow (Ctrl+Enter / A)") }
                Button(onClick = { state.answerApproval(allow = true) },
                    modifier = Modifier.focusRequester(allowFocus).semantics { contentDescription = "Allow (Enter)" }) { Text("Allow (Enter)") }
            }
        },
        dismissButton = {
            TextButton(onClick = { state.answerApproval(allow = false) },
                modifier = Modifier.semantics { contentDescription = "Deny (Esc)" }) { Text("Deny (Esc)") }
        },
    )
}
