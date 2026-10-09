// OpenMinis Desktop — main window content: resizable sidebar (conversation list) + chat pane.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.i18n.I18n
import com.openminis.desktop.i18n.tr

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
import com.openminis.desktop.core.ConversationSearch
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
                        Text(tr("chat.drop_files"), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            state.pendingApproval?.let { ApprovalDialog(state, it) }
            if (state.showSettings) SettingsDialog(state) { state.showSettings = false }
            if (state.showScheduled) ScheduledTasksWindow(state) { state.showScheduled = false }
            state.toast?.let { msg ->
                AlertDialog(onDismissRequest = { state.toast = null }, confirmButton = {
                    TextButton(onClick = { state.toast = null }) { Text(tr("common.ok")) }
                }, text = { Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { SelectionContainer { Text(msg) } } })
            }
        }
    }
}

// ---------------------------------------------------------------- Sidebar

@Composable
private fun Sidebar(state: AppState, modifier: Modifier) {
    var filter by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(state.searchFocusTick) { if (state.searchFocusTick > 0) runCatching { searchFocus.requestFocus() } }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OpenMinis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.showScheduled = true }) { Icon(Icons.Default.Schedule, tr("sidebar.scheduled")) }
            IconButton(onClick = { state.showSettings = true }) { Icon(Icons.Default.Settings, tr("sidebar.settings")) }
        }
        Spacer(Modifier.height(6.dp))
        FilledTonalButton(onClick = { state.newChat() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(tr("sidebar.new_chat"))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text(tr("sidebar.search")) }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (filter.isNotEmpty()) {
                { IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, tr("sidebar.search_clear")) } }
            } else null,
            modifier = Modifier.fillMaxWidth().tabMovesFocus().focusRequester(searchFocus)
                .onPreviewKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape && filter.isNotEmpty()) { filter = ""; true } else false }
                .semantics { contentDescription = tr("sidebar.search") }, textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        val terms = remember(filter) { ConversationSearch.terms(filter) }
        val hits = ConversationSearch.search(state.conversations, filter) { it.displayTitle() }
        LazyColumn(Modifier.weight(1f)) {
            if (terms.isEmpty()) {
                val pinned = hits.filter { it.conversation.pinned }
                if (pinned.isNotEmpty()) {
                    item(key = "h-pinned") { SidebarHeader(tr("sidebar.pinned")) }
                    items(pinned, key = { it.conversation.id }) { h -> ConversationRow(state, h.conversation, terms, null) }
                    item(key = "h-recent") { SidebarHeader(tr("sidebar.recent")) }
                }
                items(hits.filterNot { it.conversation.pinned }, key = { it.conversation.id }) { h -> ConversationRow(state, h.conversation, terms, null) }
            } else {
                if (hits.isEmpty()) item(key = "none") {
                    Text(tr("sidebar.no_results"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
                }
                items(hits, key = { it.conversation.id }) { h -> ConversationRow(state, h.conversation, terms, h.snippet) }
            }
        }
        Text(
            tr("sidebar.workspace", state.settings.workspace().absolutePath),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { state.openInExplorer(state.settings.workspace()) }.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SidebarHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp))
}

/** [text] with every search term bolded and tinted. */
@Composable
private fun highlighted(text: String, terms: List<String>): AnnotatedString {
    val ranges = ConversationSearch.matchRanges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ConversationRow(state: AppState, c: Conversation, terms: List<String>, snippet: String?) {
    val selected = c.id == state.selectedId
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val renaming = state.renamingId == c.id
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(hover)
            .onPointerEvent(PointerEventType.Press) { ev -> if (ev.buttons.isSecondaryPressed) { state.select(c.id); menuOpen = true } }
            .combinedClickable(onClick = { state.select(c.id) }, onDoubleClick = { state.select(c.id); state.renamingId = c.id })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (renaming) RenameField(state, c)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.pinned) Icon(Icons.Default.PushPin, tr("sidebar.pinned"), Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
                Text(highlighted(c.displayTitle(), terms), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (snippet != null) Text(highlighted(snippet, terms), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (if (state.runningConversationId == c.id) tr("sidebar.running_prefix") else "") + SimpleDateFormat("MM-dd HH:mm").format(Date(c.updatedAt)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if ((hovered || selected || menuOpen) && !renaming) Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.MoreVert, tr("chat.more"), modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(tr("chat.rename")) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                    trailingIcon = { Text("F2", style = MaterialTheme.typography.labelSmall) },
                    onClick = { menuOpen = false; state.renamingId = c.id })
                DropdownMenuItem(text = { Text(tr(if (c.pinned) "chat.unpin" else "chat.pin")) }, leadingIcon = { Icon(Icons.Default.PushPin, null) },
                    onClick = { menuOpen = false; state.togglePin(c.id) })
                DropdownMenuItem(text = { Text(tr("common.delete")) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                    onClick = { menuOpen = false; confirmDelete = true })
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("chat.delete.title")) },
            text = { Text(tr("chat.delete.body", c.displayTitle())) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; state.delete(c.id) }) { Text(tr("common.delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("common.cancel")) } },
        )
    }
}

/** Inline rename: Enter saves, Esc cancels, leaving the field saves. Starts with the whole title selected. */
@Composable
private fun RenameField(state: AppState, c: Conversation) {
    val initial = c.displayTitle()
    var value by remember(c.id) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun commit() { if (!done) { done = true; state.rename(c.id, value.text) } }
    fun cancel() { if (!done) { done = true; state.renamingId = null } }
    LaunchedEffect(c.id) { runCatching { focus.requestFocus() } }
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = { value = it }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
            .onFocusChanged { f -> if (f.isFocused) hadFocus = true else if (hadFocus) commit() }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) false
                else when (ev.key) {
                    Key.Enter, Key.NumPadEnter -> { commit(); true }
                    Key.Escape -> { cancel(); true }
                    else -> false
                }
            }
            .semantics { contentDescription = tr("chat.rename") },
    )
    Text(tr("chat.rename.hint"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            ?: conv?.id?.let { state.scheduledStreaming[it] }
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
                    if (state.runningConversationId == conv?.id || conv?.id?.let { state.scheduledRunning.containsKey(it) } == true) {
                        item(key = "streaming") {
                            if (streaming != null) MessageItem(state, streaming, isLast = false, live = true)
                            else Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(tr("chat.thinking_progress"))
                            }
                        }
                    }
                }
                val canScrollDown by remember(listState) { derivedStateOf { listState.canScrollForward } }
                if (canScrollDown) {
                    val scope = rememberCoroutineScope()
                    SmallFloatingActionButton(
                        onClick = { scope.launch { if (total > 0) listState.animateScrollToItem(total - 1, Int.MAX_VALUE / 2) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Default.KeyboardArrowDown, tr("chat.scroll_bottom")) }
                }
            }
        }
        if (state.runningConversationId == conv?.id) SubAgentBlock(state)
        conv?.id?.let { id -> state.scheduledRunning[id]?.let { label ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("chat.scheduled_running", label), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.stopScheduled(id) }) { Text(tr("common.stop")) }
            }
        } }
        conv?.id?.let { state.chatNotices[it] }?.let { notice ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ⓘ $notice", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { state.chatNotices.remove(conv.id) }) { Text(tr("common.dismiss")) }
            }
        }
        val status = state.statusText.takeIf { state.runningConversationId == conv?.id || state.compactingConversationId == conv?.id }
        if (status != null || state.compactingConversationId == conv?.id) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(status ?: tr("chat.compacting"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Composer(state)
    }
}

/** [P1-2] Sub agents running for the current turn: agent, task title, current tool, elapsed; expandable output tail. */
@Composable
private fun SubAgentBlock(state: AppState) {
    val active = state.subAgentRuns.filter { it.isActive }
    if (active.isEmpty()) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active.size) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        active.forEach { r ->
            var open by remember(r.jobId) { mutableStateOf(false) }
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.clickable { open = !open }.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (r.state == "running") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        val elapsed = (now - r.startedAt).coerceAtLeast(0) / 1000
                        Text("${if (open) "▾" else "▸"} " + tr("subagent.row", r.agent, r.title, I18n.trOr("subagent.state.${r.state}", r.state)) +
                            (r.currentTool?.let { " · $it" } ?: "") + " · ${elapsed}s · ${r.model}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open && r.latestText.isNotBlank()) {
                        Text(r.latestText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactDivider(state: AppState, convId: String, mk: CompactMarker, isLatest: Boolean) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  " + tr(if (mk.automatic) "compact.divider_auto" else "compact.divider", mk.compactedCount) + "  ",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { show = true }) { Text(tr("compact.view_summary")) }
        if (isLatest) TextButton(onClick = { state.revertCompact(convId) }, enabled = !state.isRunning) { Text(tr("common.undo")) }
        HorizontalDivider(Modifier.weight(1f))
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(tr("compact.summary_title")) },
            text = {
                Column {
                    Text(tr("compact.written_by", mk.model ?: tr("compact.the_model"), SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(mk.createdAt))),
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                    MonoBlock(mk.summary)
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text(tr("common.close")) } },
        )
    }
}

@Composable
private fun TopBar(state: AppState, conv: Conversation?) {
    val s = state.settings
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(conv?.displayTitle() ?: "OpenMinis", style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val reading = conv?.id?.let { state.contextReadings[it] }
        if (reading != null && reading.window > 0) {
            val pct = (reading.measuredTokens * 100L / reading.window).toInt()
            val near = reading.compactThreshold > 0 && reading.measuredTokens >= reading.compactThreshold * 0.8
            Text(
                tr("toolbar.ctx", fmtK(reading.measuredTokens), fmtK(reading.window), pct),
                style = MaterialTheme.typography.labelMedium,
                color = if (near) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        IconButton(
            onClick = { state.compactNow() },
            enabled = conv != null && conv.messages.isNotEmpty() && !state.isRunning && state.compactingConversationId == null,
        ) { Icon(Icons.Default.Compress, tr("toolbar.compact")) }
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
            value = s.thinkingLevel, options = listOf("OFF", "LOW", "MEDIUM", "HIGH"), prefix = tr("toolbar.thinking_prefix"),
            label = { I18n.trOr("thinking.$it", it.lowercase().replaceFirstChar(Char::uppercase)) },
            onSelect = { state.updateSettings(s.copy(thinkingLevel = it)) },
        )
    }
}

@Composable
private fun EmptyState(state: AppState) {
    val s = state.settings
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(tr("empty.headline"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${s.activeProvider.label} · ${s.provider().effectiveModel(s.activeProvider)}" +
                (if (s.toolsEnabled) tr("empty.tools_on", s.shell.label) else tr("empty.tools_off")),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.provider().apiKey.isBlank() && s.activeProvider != ProviderKind.OPENAI_COMPATIBLE) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = { state.showSettings = true }) { Text(tr("empty.add_key")) }
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
            var editing by remember(m.id) { mutableStateOf(false) }
            if (editing) EditUserMessage(state, m) { editing = false }
            else {
                if (m.text.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.widthIn(max = 720.dp),
                    ) {
                        SelectionContainer { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                    }
                }
                if (!live) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (m.text.isNotBlank()) IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, tr("common.copy"), Modifier.size(15.dp))
                    }
                    IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp), enabled = !state.isRunning) {
                        Icon(Icons.Default.Edit, tr("msg.edit"), Modifier.size(15.dp))
                    }
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
                        Icon(Icons.Default.ContentCopy, tr("common.copy"), Modifier.size(16.dp))
                    }
                    if (isLast) IconButton(onClick = { state.regenerate() }, modifier = Modifier.size(30.dp), enabled = !state.isRunning) {
                        Icon(Icons.Default.Refresh, tr("msg.regenerate"), Modifier.size(16.dp))
                    }
                    m.modelLabel?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                    if (m.inputTokens != null || m.outputTokens != null) {
                        Text(tr("msg.tokens", m.inputTokens ?: 0, m.outputTokens ?: 0), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Inline editor for a sent user message: Ctrl+Enter resends from here, Esc cancels. */
@Composable
private fun EditUserMessage(state: AppState, m: ChatMessage, onDone: () -> Unit) {
    var value by remember(m.id) { mutableStateOf(TextFieldValue(m.text, TextRange(m.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(m.id) { runCatching { focus.requestFocus() } }
    val dropped = state.selected?.messages?.let { list -> list.size - 1 - list.indexOfFirst { it.id == m.id } } ?: 0
    fun save() { if (state.editAndResend(m.id, value.text)) onDone() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        OutlinedTextField(
            value = value, onValueChange = { value = it }, textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(min = 320.dp, max = 720.dp).focusRequester(focus).onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) false
                else if ((ev.key == Key.Enter || ev.key == Key.NumPadEnter) && ev.isCtrlPressed) { save(); true }
                else if (ev.key == Key.Escape) { onDone(); true } else false
            }.semantics { contentDescription = tr("msg.edit") },
        )
        if (dropped > 0) Text(tr("msg.edit.drops", dropped), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onDone) { Text(tr("common.cancel_esc")) }
            Button(onClick = { save() }, enabled = !state.isRunning && (value.text.isNotBlank() || m.attachments.isNotEmpty())) { Text(tr("msg.edit.resend")) }
        }
    }
}

@Composable
private fun RoundView(r: Round) {
    if (r.thinking.isNotBlank()) {
        var open by remember { mutableStateOf(false) }
        Text(
            if (open) tr("msg.thinking_open") else tr("msg.thinking_closed", r.thinking.length),
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
    if (ToolPresentation.shouldGroup(r.toolCalls)) ToolGroup(r.toolCalls) else r.toolCalls.forEach { ToolCallCard(it) }
}

/** A finished batch of calls folded under one header; failures stay listed under it even when folded. */
@Composable
private fun ToolGroup(calls: List<ToolCall>) {
    var open by remember { mutableStateOf(false) }
    val failed = calls.filter(ToolPresentation::isFailure)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(if (failed.isEmpty()) tr("tool.group", calls.size) else tr("tool.group_failed", calls.size, failed.size),
            style = MaterialTheme.typography.labelLarge, color = if (failed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f))
        Text(calls.map { ToolPresentation.displayName(it.name) }.distinct().joinToString(", "), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, tr(if (open) "tool.collapse" else "tool.expand"), Modifier.size(18.dp))
    }
    Column(Modifier.padding(start = 12.dp)) {
        (if (open) calls else failed).forEach { ToolCallCard(it) }
    }
}

private fun kindIcon(k: ToolPresentation.Kind) = when (k) {
    ToolPresentation.Kind.SHELL -> Icons.Default.Terminal
    ToolPresentation.Kind.FILE -> Icons.Default.Description
    ToolPresentation.Kind.MEMORY -> Icons.Default.Psychology
    ToolPresentation.Kind.MCP -> Icons.Default.Extension
    ToolPresentation.Kind.SUBAGENT -> Icons.Default.Groups
    ToolPresentation.Kind.SCHEDULED -> Icons.Default.Schedule
    ToolPresentation.Kind.OTHER -> Icons.Default.Build
}

@Composable
private fun ToolCallCard(tc: ToolCall) {
    val failure = ToolPresentation.isFailure(tc)
    // Failed calls open by themselves the first time they are shown.
    var open by remember(tc.id) { mutableStateOf(failure) }
    val clipboard = LocalClipboardManager.current
    val running = tc.status == ToolStatus.RUNNING || tc.status == ToolStatus.PENDING
    val tint = when {
        failure -> MaterialTheme.colorScheme.error
        tc.status == ToolStatus.DONE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val summary = remember(tc.argsJson) { toolSummary(tc) }
    val errorLine = ToolPresentation.errorLine(tc)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (failure) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(kindIcon(ToolPresentation.kind(tc.name)), tr("tool.status.${tc.status.name}"), tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(ToolPresentation.displayName(tc.name), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(errorLine ?: summary, style = MaterialTheme.typography.bodySmall, fontFamily = if (errorLine == null) FontFamily.Monospace else null,
                    color = if (errorLine != null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(tr("tool.status.${tc.status.name}").takeIf { running || failure }, tc.durationMs?.let(ToolPresentation::duration)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.padding(horizontal = 4.dp),
                )
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, tr(if (open) "tool.collapse" else "tool.expand"), Modifier.size(18.dp))
            }
            if (open) {
                Spacer(Modifier.height(6.dp))
                SectionLabel(tr("tool.arguments")) { clipboard.setText(AnnotatedString(prettyJson(tc.argsJson))) }
                MonoBlock(prettyJson(tc.argsJson))
                tc.result?.let { full ->
                    val c = remember(full) { ToolPresentation.clip(full) }
                    SectionLabel(tr("tool.result")) { clipboard.setText(AnnotatedString(full)) }
                    MonoBlock(c.text)
                    if (c.truncated) Text(tr("tool.truncated", c.text.length, c.total), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ContentCopy, tr("tool.copy", label), Modifier.size(14.dp)) }
    }
}

@Composable
private fun MonoBlock(text: String) {
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        SelectionContainer {
            Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
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
            val chooser = javax.swing.JFileChooser().apply { isMultiSelectionEnabled = true; dialogTitle = tr("attach.dialog_title") }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) state.attachFiles(chooser.selectedFiles.toList())
        }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.AttachFile, tr("composer.attach")) }
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 260.dp).focusRequester(focus)
                .semantics { contentDescription = tr("composer.a11y") }
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
            placeholder = { Text(tr("composer.placeholder")) },
            maxLines = 12,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        if (state.isRunning) {
            FilledTonalIconButton(onClick = { state.stop() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Stop, tr("composer.stop")) }
        } else {
            FilledIconButton(onClick = { submit() }, enabled = value.text.isNotBlank() || pending.isNotEmpty(), modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.Send, tr("composer.send"))
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
                    AttachmentStore.humanSize(a.sizeBytes) + " · " + when (a.kind) { AttachmentKind.IMAGE -> tr("attach.kind.image"); AttachmentKind.TEXT -> tr("attach.kind.text"); else -> tr("attach.kind.file") },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Close, tr("attach.remove", a.fileName), Modifier.size(16.dp)) }
        }
    }
}

// ---------------------------------------------------------------- Approval

/**
 * Keyboard: Enter = Allow, Esc = Deny, Ctrl+Enter or A = the request's remember option (MCP "Always allow",
 * shell "Allow for this session") when it offers one. The Allow button takes focus
 * when the dialog opens (Tab / Shift+Tab move between the buttons, Space/Enter press the focused one).
 */
@Composable
private fun ApprovalDialog(state: AppState, p: PendingApproval) {
    val allowFocus = remember { FocusRequester() }
    val rememberLabel = if (p.request.onAlwaysAllow != null) p.request.rememberLabel ?: tr("approval.always") else null
    LaunchedEffect(p) { kotlinx.coroutines.delay(60); runCatching { allowFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { state.answerApproval(allow = false) },   // Esc
        modifier = Modifier.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                (ev.key == Key.Enter || ev.key == Key.NumPadEnter) && ev.isCtrlPressed -> { if (rememberLabel != null) state.answerApproval(allow = true, dontAskAgain = true); true }
                ev.key == Key.A && !ev.isCtrlPressed && !ev.isAltPressed && rememberLabel != null -> { state.answerApproval(allow = true, dontAskAgain = true); true }
                ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { state.answerApproval(allow = true); true }
                ev.key == Key.Escape -> { state.answerApproval(allow = false); true }
                else -> false
            }
        }.semantics { contentDescription = tr("approval.a11y", p.request.summary) },
        icon = { Icon(Icons.Default.Terminal, null) },
        title = { Text(p.request.summary) },
        text = {
            Column {
                Text(tr("approval.intro"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                MonoBlock(p.request.detail)
                Spacer(Modifier.height(6.dp))
                Text(tr("approval.keys") + (rememberLabel?.let { tr("approval.keys_remember", it) } ?: ""), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row {
                if (rememberLabel != null) TextButton(onClick = { state.answerApproval(allow = true, dontAskAgain = true) },
                    modifier = Modifier.semantics { contentDescription = tr("approval.remember_a11y", rememberLabel) }) { Text(tr("approval.remember_button", rememberLabel)) }
                Button(onClick = { state.answerApproval(allow = true) },
                    modifier = Modifier.focusRequester(allowFocus).semantics { contentDescription = tr("approval.allow") }) { Text(tr("approval.allow")) }
            }
        },
        dismissButton = {
            TextButton(onClick = { state.answerApproval(allow = false) },
                modifier = Modifier.semantics { contentDescription = tr("approval.deny") }) { Text(tr("approval.deny")) }
        },
    )
}
