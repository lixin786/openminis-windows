// OpenMinis Desktop — main window content: resizable sidebar (conversation list) + chat pane.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.ui.icons.Lucide
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.openminis.desktop.mcp.McpChatScope
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
    MinisTheme(state.settings) { CompositionLocalProvider(LocalAppState provides state) {
        Surface(
            Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
            color = MaterialTheme.colorScheme.background,
        ) {
            var sidebarWidth by remember { mutableStateOf(270.dp) }
            val density = LocalDensity.current
            Row(Modifier.fillMaxSize()) {
                if (!state.sidebarCollapsed) Sidebar(state, Modifier.width(sidebarWidth).fillMaxHeight())
                // Drag handle to resize the sidebar.
                if (!state.sidebarCollapsed) Box(
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
            state.pendingApproval?.let { if (!isInline(state, it)) ApprovalDialog(state, it) }
            if (state.showSettings) SettingsDialog(state) { state.showSettings = false }
            if (state.showScheduled) ScheduledTasksWindow(state) { state.showScheduled = false }
            state.restartRequired?.let { RestartRequiredDialog(state, it) }
            state.toast?.let { msg ->
                AlertDialog(onDismissRequest = { state.toast = null }, confirmButton = {
                    TextButton(onClick = { state.toast = null }) { Text(tr("common.ok")) }
                }, text = { Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { SelectionContainer { Text(msg) } } })
            }
        }
    } }
}

// ---------------------------------------------------------------- Sidebar

@Composable
private fun Sidebar(state: AppState, modifier: Modifier) {
    var filter by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(state.searchFocusTick) { if (state.searchFocusTick > 0) runCatching { searchFocus.requestFocus() } }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(26.dp)
            Spacer(Modifier.width(8.dp))
            val chrome = LocalWindowChrome.current
            Box(Modifier.weight(1f).height(34.dp).titleBarDrag(chrome, TITLE_REGION_SIDE), contentAlignment = Alignment.CenterStart) {
                Text("OpenMinis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (chrome != null) TitleBarMenu(chrome, TITLE_REGION_SIDE)
            }
            IconButton(onClick = { state.showScheduled = true }, modifier = Modifier.size(34.dp)) { Icon(Lucide.CalendarClock, tr("sidebar.scheduled"), Modifier.size(18.dp)) }
            IconButton(onClick = { state.openSettings() }, modifier = Modifier.size(34.dp)) { Icon(Lucide.Settings, tr("sidebar.settings"), Modifier.size(18.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        FilledTonalButton(onClick = { state.newChat() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Lucide.MessageSquarePlus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("sidebar.new_chat"))
        }
        Spacer(Modifier.height(8.dp))
        // Compact search pill (36 dp) instead of a 56 dp outlined field.
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Search, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (filter.isEmpty()) Text(tr("sidebar.search") + "  Ctrl+F", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1)
                androidx.compose.foundation.text.BasicTextField(
                    value = filter, onValueChange = { filter = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().tabMovesFocus().focusRequester(searchFocus)
                        .onPreviewKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape && filter.isNotEmpty()) { filter = ""; true } else false }
                        .semantics { contentDescription = tr("sidebar.search") },
                )
            }
            if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.X, tr("sidebar.search_clear"), Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        val terms = remember(filter) { ConversationSearch.terms(filter) }
        val hits = ConversationSearch.search(state.conversations, filter) { it.displayTitle() }
        // Relative times ("3 分钟前") refresh every 30 s.
        val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
        LazyColumn(Modifier.weight(1f)) {
            if (terms.isEmpty()) {
                // Pinned, then Today / Yesterday / Previous 7 days / Earlier (UI-REFRESH §3.1).
                ShellModel.sections(hits.map { it.conversation }, now).forEach { sec ->
                    item(key = "h-${sec.headerKey}") { SidebarHeader(tr(sec.headerKey)) }
                    items(sec.conversations, key = { it.id }) { c -> ConversationRow(state, c, terms, null, now) }
                }
            } else {
                if (hits.isEmpty()) item(key = "none") {
                    Text(tr("sidebar.no_results"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
                }
                items(hits, key = { it.conversation.id }) { h -> ConversationRow(state, h.conversation, terms, h.snippet, now) }
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
private fun ConversationRow(state: AppState, c: Conversation, terms: List<String>, snippet: String?, now: Long) {
    val selected = c.id == state.selectedId
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val renaming = state.renamingId == c.id
    val running = state.runningConversationId == c.id || state.scheduledRunning.containsKey(c.id)
    val unread = !running && state.isUnread(c)
    LaunchedEffect(selected, c.updatedAt) { if (selected) state.markSeen(c) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp))
            .background(when {
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                else -> androidx.compose.ui.graphics.Color.Transparent
            })
            .hoverable(hover)
            .onPointerEvent(PointerEventType.Press) { ev -> if (ev.buttons.isSecondaryPressed) { state.select(c.id); menuOpen = true } }
            .combinedClickable(onClick = { state.select(c.id) }, onDoubleClick = { state.select(c.id); state.renamingId = c.id })
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left dot: running (accent, pulsing) or unread (accent); keeps titles aligned when absent.
        Box(Modifier.width(10.dp), contentAlignment = Alignment.Center) {
            if (running || unread) {
                val pulse = if (running) {
                    val t = rememberInfiniteTransition()
                    t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse)).value
                } else 1f
                Box(Modifier.size(6.dp).alpha(pulse).background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = tr(if (running) "sidebar.running" else "sidebar.unread") })
            }
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            if (renaming) RenameField(state, c)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.pinned) Icon(Lucide.Pin, tr("sidebar.pinned"), Modifier.size(13.dp).padding(end = 3.dp), tint = MaterialTheme.colorScheme.primary)
                Text(highlighted(c.displayTitle(), terms), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (snippet != null) Text(highlighted(snippet, terms), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!renaming) Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.CenterEnd) {
            // Relative time; on hover (or with the menu open) the "…" menu takes its place.
            if (hovered || menuOpen) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(26.dp)) {
                    Icon(Lucide.Ellipsis, tr("chat.more"), modifier = Modifier.size(16.dp))
                }
            } else {
                val rel = ShellModel.relative(c.updatedAt, now)
                Text(if (running) tr("sidebar.running") else tr(rel.key, rel.arg), style = MaterialTheme.typography.labelSmall,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(tr("chat.rename")) }, leadingIcon = { Icon(Lucide.Pencil, null, Modifier.size(16.dp)) },
                    trailingIcon = { Text("F2", style = MaterialTheme.typography.labelSmall) },
                    onClick = { menuOpen = false; state.renamingId = c.id })
                DropdownMenuItem(text = { Text(tr(if (c.pinned) "chat.unpin" else "chat.pin")) }, leadingIcon = { Icon(Lucide.Pin, null, Modifier.size(16.dp)) },
                    onClick = { menuOpen = false; state.togglePin(c.id) })
                DropdownMenuItem(text = { Text(tr("common.delete"), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Lucide.Trash, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
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
                    ) { Icon(Lucide.ArrowDownToLine, tr("chat.scroll_bottom")) }
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
        state.pendingApproval?.takeIf { isInline(state, it) }?.let { InlineApproval(state, it) }
        // The start screen hosts the composer in its centre; otherwise it sits at the bottom.
        val emptyChat = messages.isEmpty() && streaming == null
        if (!emptyChat) Composer(state)
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
    // [immersive] This row is the window's title bar: blank space drags, caption buttons sit flush right.
    val chrome = LocalWindowChrome.current
    val menus = LocalAppMenus.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(42.dp)) {
    val narrow = maxWidth < 820.dp
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (menus != null) { AppMenuButton(menus); Spacer(Modifier.width(2.dp)) }
        IconButton(onClick = { state.sidebarCollapsed = !state.sidebarCollapsed }, modifier = Modifier.size(34.dp)) {
            Icon(Lucide.PanelLeft, tr(if (state.sidebarCollapsed) "toolbar.show_sidebar" else "toolbar.hide_sidebar"), Modifier.size(18.dp))
        }
        if (state.sidebarCollapsed) { Spacer(Modifier.width(4.dp)); BrandMark(22.dp) }
        Spacer(Modifier.width(6.dp))
        // [immersive] Pill + chip shrink first, so the drag region keeps at least 56 dp and the buttons stay on screen
        // (1366×768 @150 %). Narrow windows also drop the context reading.
        LeadThenFill(Modifier.weight(1f).fillMaxHeight(), minFill = if (chrome != null) 56.dp else 0.dp,
            lead = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelPill(state, compact = narrow)
                    Spacer(Modifier.width(6.dp))
                    ThinkingChip(state)
                    Spacer(Modifier.width(10.dp))
                }
            },
            fill = {
                Box(Modifier.fillMaxSize().titleBarDrag(chrome, TITLE_REGION_TOP), contentAlignment = Alignment.CenterStart) {
                    Text(conv?.displayTitle().orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (chrome?.active == false) 0.6f else 1f),
                        overflow = TextOverflow.Ellipsis)
                    if (chrome != null) TitleBarMenu(chrome, TITLE_REGION_TOP)
                }
            })
        val reading = conv?.id?.let { state.contextReadings[it] }
        if (reading != null && reading.window > 0 && !narrow) {
            val pct = (reading.measuredTokens * 100L / reading.window).toInt()
            val near = reading.compactThreshold > 0 && reading.measuredTokens >= reading.compactThreshold * 0.8
            Text(
                tr("toolbar.ctx", fmtK(reading.measuredTokens), fmtK(reading.window), pct),
                style = MaterialTheme.typography.labelSmall,
                color = if (near) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (conv != null && state.settings.skillsEnabled && state.settings.toolsEnabled) SkillChatButton(state, conv)
        if (conv != null && state.mcp.servers.isNotEmpty()) McpChatButton(state, conv)
        IconButton(
            onClick = { state.compactNow() }, modifier = Modifier.size(34.dp),
            enabled = conv != null && conv.messages.isNotEmpty() && !state.isRunning && state.compactingConversationId == null,
        ) { Icon(Lucide.Shrink, tr("toolbar.compact"), Modifier.size(18.dp)) }
        IconButton(onClick = { state.sidebarCollapsed = false; state.searchFocusTick++ }, modifier = Modifier.size(34.dp)) {
            Icon(Lucide.Search, tr("toolbar.search"), Modifier.size(18.dp))
        }
        IconButton(onClick = { state.exportSelected() }, enabled = conv != null && conv.messages.isNotEmpty(), modifier = Modifier.size(34.dp)) {
            Icon(Lucide.Download, tr("toolbar.export"), Modifier.size(18.dp))
        }
    }
        if (chrome != null) CaptionButtons(chrome, Modifier.fillMaxHeight())
    }
    }
}

/**
 * [immersive] [lead] gets at most (width − [minFill]) and keeps its natural width; [fill] takes all the rest.
 * Unlike Row weights this never leaves slack, so whatever follows stays flush right.
 */
@Composable
private fun LeadThenFill(modifier: Modifier, minFill: androidx.compose.ui.unit.Dp, lead: @Composable () -> Unit, fill: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(contents = listOf(lead, fill), modifier = modifier) { (leadM, fillM), c ->
        val minF = minFill.roundToPx().coerceAtMost(c.maxWidth)
        val leadP = leadM.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = (c.maxWidth - minF).coerceAtLeast(0), maxHeight = c.maxHeight)) }
        val lw = leadP.maxOfOrNull { it.width } ?: 0
        val fw = (c.maxWidth - lw).coerceAtLeast(0)
        val fillP = fillM.map { it.measure(androidx.compose.ui.unit.Constraints.fixed(fw, c.maxHeight)) }
        layout(c.maxWidth, c.maxHeight) {
            leadP.forEach { it.place(0, (c.maxHeight - it.height) / 2) }
            fillP.forEach { it.place(lw, 0) }
        }
    }
}

/** "model | provider" pill (UI-REFRESH §3.2); click = provider + model menu. */
@Composable
private fun ModelPill(state: AppState, compact: Boolean = false) {
    val s = state.settings
    val kind = s.activeProvider
    val ps = s.provider(kind)
    val current = ps.effectiveModel(kind)
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    Box {
        Surface(
            shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { open = true }
                .semantics { contentDescription = tr("toolbar.model_a11y", current, kind.label) },
        ) {
            Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Sparkles, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(current, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 150.dp else 260.dp))
                // Narrow window: the provider badge goes (it is in the pill's menu and its a11y label).
                if (!compact) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(1.dp).height(14.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(6.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Text(ShellModel.providerBadge(kind, ps.effectiveBaseUrl(kind)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
                }
                Icon(Lucide.ChevronDown, null, Modifier.size(16.dp).padding(start = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(tr("toolbar.provider"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            ProviderKind.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.label + if (s.provider(k).apiKey.isBlank() && k != ProviderKind.OPENAI_COMPATIBLE) tr("toolbar.no_key") else "") },
                    leadingIcon = { if (k == kind) Icon(Lucide.Check, null, Modifier.size(16.dp)) else Spacer(Modifier.size(16.dp)) },
                    onClick = { state.updateSettings(s.copy(activeProvider = k)) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(tr("toolbar.model"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            (listOf(current) + DesktopProviderFactory.suggestedModels(kind)).distinct().forEach { m ->
                DropdownMenuItem(
                    text = { Text(m, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { if (m == current) Icon(Lucide.Check, null, Modifier.size(16.dp)) else Spacer(Modifier.size(16.dp)) },
                    onClick = { open = false; state.updateSettings(s.withProvider(kind, ps.copy(model = m))) },
                )
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(custom, { custom = it }, singleLine = true, placeholder = { Text(tr("toolbar.custom_model")) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = LocalMonoFont.current), modifier = Modifier.weight(1f)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter) && custom.isNotBlank()) {
                                state.updateSettings(s.withProvider(kind, ps.copy(model = custom.trim()))); custom = ""; open = false; true
                            } else false
                        })
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(text = { Text(tr("toolbar.model_settings")) }, leadingIcon = { Icon(Lucide.Settings, null, Modifier.size(16.dp)) },
                onClick = { open = false; state.openSettings(SettingsPage.MODEL) })
        }
    }
}

@Composable
private fun ThinkingChip(state: AppState) {
    val s = state.settings
    var open by remember { mutableStateOf(false) }
    val label: (String) -> String = { I18n.trOr("thinking.$it", it.lowercase().replaceFirstChar(Char::uppercase)) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics { contentDescription = tr("toolbar.thinking_prefix") + label(s.thinkingLevel) },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Brain, null, Modifier.size(14.dp), tint = if (s.thinkingLevel == "OFF") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(tr("toolbar.thinking_prefix") + label(s.thinkingLevel), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("OFF", "LOW", "MEDIUM", "HIGH").forEach { lv ->
                DropdownMenuItem(text = { Text(tr("toolbar.thinking_prefix") + label(lv)) },
                    leadingIcon = { if (lv == s.thinkingLevel) Icon(Lucide.Check, null, Modifier.size(16.dp)) else Spacer(Modifier.size(16.dp)) },
                    onClick = { open = false; state.updateSettings(s.copy(thinkingLevel = lv)) })
            }
        }
    }
}

/** Toolbar "Skills a/b" + panel: switch Settings' skills on/off for this chat (P1-3, twin of the MCP panel). */
@Composable
private fun SkillChatButton(state: AppState, conv: Conversation) {
    var open by remember { mutableStateOf(false) }
    // Re-read the skills folder when the panel opens (imports / deletes happen in Settings or Explorer).
    val all = remember(open) { runCatching { state.skills.load() }.getOrDefault(emptyList()) }
    if (all.isEmpty()) return
    val disabled = state.settings.disabledSkills
    val (on, enabled) = com.openminis.desktop.skills.SkillChatScope.counts(all, disabled, conv.skillsOff)
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Lucide.BookOpen, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(tr("chatskills.badge", on, enabled), style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(380.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(tr("chatskills.title"), style = MaterialTheme.typography.titleSmall)
                Text(tr("chatskills.hint"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.isRunning && state.runningConversationId == conv.id)
                    Text(tr("chatmcp.next_turn"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(6.dp))
                com.openminis.desktop.skills.SkillChatScope.rows(all, disabled, conv.skillsOff).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(row.onInChat, { state.setChatSkill(conv.id, row.skill.id, it) }, enabled = row.globallyEnabled,
                            modifier = Modifier.semantics { contentDescription = tr("chatskills.toggle", row.skill.name) })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (row.globallyEnabled) row.skill.description.ifBlank { row.skill.id } else tr("chatmcp.off_in_settings"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { open = false; state.openSettings(SettingsPage.SKILLS) }) { Text(tr("chatskills.open_settings")) }
            }
        }
    }
}

/** Toolbar "MCP a/b" + panel: switch Settings' servers on/off for this chat, see their state and tools. */
@Composable
private fun McpChatButton(state: AppState, conv: Conversation) {
    @Suppress("UNUSED_VARIABLE") val tick = state.mcpTick  // recompose on server status changes
    var open by remember { mutableStateOf(false) }
    val servers = state.mcp.servers
    val (on, enabled) = McpChatScope.counts(servers, conv.mcpOff)
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Lucide.Puzzle, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(tr("chatmcp.badge", on, enabled), style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(380.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(tr("chatmcp.title"), style = MaterialTheme.typography.titleSmall)
                Text(tr("chatmcp.hint"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.isRunning && state.runningConversationId == conv.id)
                    Text(tr("chatmcp.next_turn"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(6.dp))
                McpChatScope.rows(servers, conv.mcpOff).forEach { row -> McpChatRow(state, conv, row) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { open = false; state.openSettings(SettingsPage.MCP) }) { Text(tr("chatmcp.open_settings")) }
            }
        }
    }
}

@Composable
private fun McpChatRow(state: AppState, conv: Conversation, row: McpChatScope.Row) {
    val sv = row.server
    val st = state.mcp.status(sv.name)
    var showTools by remember(sv.name) { mutableStateOf(false) }
    val tools = state.mcp.tools(sv.name)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(row.onInChat, { state.setChatMcp(conv.id, sv.name, it) }, enabled = row.globallyEnabled,
                modifier = Modifier.semantics { contentDescription = tr("chatmcp.toggle", sv.name) })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(sv.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val stText = when {
                    !row.globallyEnabled -> tr("chatmcp.off_in_settings")
                    st.state == "running" -> tr("mcp.state.running", st.toolCount)
                    else -> I18n.trOr("mcp.state.${st.state}", st.state)
                }
                Text(stText, style = MaterialTheme.typography.labelSmall,
                    color = if (st.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tools.isNotEmpty()) IconButton(onClick = { showTools = !showTools }, modifier = Modifier.size(28.dp)) {
                Icon(if (showTools) Lucide.ChevronUp else Lucide.ChevronDown, tr(if (showTools) "tool.collapse" else "tool.expand"), Modifier.size(18.dp))
            }
            if (row.onInChat && sv.isStdio && st.state != "running" && st.state != "starting")
                TextButton(onClick = { state.connectMcp(sv) }) { Text(tr("mcp.connect")) }
        }
        st.error?.takeIf { row.globallyEnabled }?.let {
            Text(it.take(300), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (showTools) tools.forEach { t ->
            Text("· " + t.name, style = MaterialTheme.typography.labelSmall, fontFamily = LocalMonoFont.current,
                color = if (row.onInChat) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Open WebUI–style start screen (UI-REFRESH §3.3): mark, value line, three runnable examples, big composer. */
@Composable
private fun EmptyState(state: AppState) {
    val s = state.settings
    val needsKey = s.provider().apiKey.isBlank() && s.activeProvider != ProviderKind.OPENAI_COMPATIBLE
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 32.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(52.dp)
            Spacer(Modifier.height(14.dp))
            Text(tr("empty.headline"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(tr("empty.value"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                "${s.provider().effectiveModel(s.activeProvider)} · ${ShellModel.providerBadge(s.activeProvider, s.provider().effectiveBaseUrl(s.activeProvider))}" +
                    (if (s.toolsEnabled) tr("empty.tools_on", s.shell.label) else tr("empty.tools_off")),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            if (needsKey) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = { state.openSettings(SettingsPage.MODEL) }) { Text(tr("empty.add_key")) }
            }
            Spacer(Modifier.height(22.dp))
            Composer(state, big = true)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EMPTY_EXAMPLES.forEach { (key, icon) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !state.isRunning && !needsKey) { state.send(tr("$key.prompt")) }
                            .semantics { contentDescription = tr("$key.title") },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                            Text(tr("$key.title"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(tr("$key.subtitle"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** Examples are real actions the agent can run with the default tools (sent in the UI language). */
private val EMPTY_EXAMPLES = listOf(
    "empty.ex1" to Lucide.FolderOpen,
    "empty.ex2" to Lucide.SquareTerminal,
    "empty.ex3" to Lucide.Languages,
)

/** The app mark: an accent rounded square with an "M" monogram. */
@Composable
internal fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(
        androidx.compose.ui.graphics.Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))),
        contentAlignment = Alignment.Center) {
        Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.52f).sp, fontFamily = BundledInter)
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
                        Icon(Lucide.Copy, tr("common.copy"), Modifier.size(15.dp))
                    }
                    IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp), enabled = !state.isRunning) {
                        Icon(Lucide.Pencil, tr("msg.edit"), Modifier.size(15.dp))
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
                        Icon(Lucide.Copy, tr("common.copy"), Modifier.size(16.dp))
                    }
                    if (isLast) IconButton(onClick = { state.regenerate() }, modifier = Modifier.size(30.dp), enabled = !state.isRunning) {
                        Icon(Lucide.RefreshCw, tr("msg.regenerate"), Modifier.size(16.dp))
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
            // P2-4: LaTeX math ($…$, $$…$$, \(…\), \[…\]) via JLaTeXMath; also escapes literal $ for the GFM parser.
            com.openminis.desktop.ui.math.ChatMarkdown(
                content = r.text, modifier = Modifier.fillMaxWidth(),
                renderMath = LocalAppState.current?.settings?.renderMath ?: true,
                typography = com.mikepenz.markdown.m3.markdownTypography(
                    h1 = t.headlineSmall, h2 = t.titleLarge, h3 = t.titleMedium,
                    h4 = t.titleSmall, h5 = t.titleSmall, h6 = t.titleSmall,
                ),
            )
        }
    }
    if (ToolPresentation.shouldGroupSegments(r.toolCalls)) ToolGroup(r.toolCalls) else ToolSegments(r.toolCalls)
}

/** Lets deep cards reach the app (retry a declined call) without threading state through every composable. */
internal val LocalAppState = androidx.compose.runtime.staticCompositionLocalOf<AppState?> { null }

@Composable
private fun ToolSegments(calls: List<ToolCall>) {
    ToolPresentation.segments(calls).forEach { seg ->
        when (seg) {
            is ToolPresentation.Single -> ToolCallCard(seg.call)
            is ToolPresentation.ReadOnlyRun -> ReadOnlyChip(seg.calls)
        }
    }
}

/** "✓ 7 read-only operations" (UI-REFRESH §2.2): consecutive successful look-ups folded into one chip. */
@Composable
private fun ReadOnlyChip(calls: List<ToolCall>) {
    var open by remember(calls.first().id) { mutableStateOf(false) }
    val total = calls.sumOf { it.durationMs ?: 0L }
    Column(Modifier.padding(vertical = 3.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { open = !open }
                .semantics { contentDescription = tr("tool.readonly_chip", calls.size) }) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Check, null, Modifier.size(14.dp), tint = LocalSuccessColor.current)
                Spacer(Modifier.width(6.dp))
                Text(tr("tool.readonly_chip", calls.size), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text(ToolPresentation.duration(total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, tr(if (open) "tool.collapse" else "tool.expand"), Modifier.size(16.dp))
            }
        }
        if (open) Column(Modifier.padding(start = 12.dp)) { calls.forEach { ToolCallCard(it) } }
    }
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
        Icon(if (failed.isEmpty()) Lucide.Check else Lucide.CircleAlert, null, Modifier.size(16.dp),
            tint = if (failed.isEmpty()) LocalSuccessColor.current else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(if (failed.isEmpty()) tr("tool.group", calls.size) else tr("tool.group_failed", calls.size, failed.size),
            style = MaterialTheme.typography.labelLarge, color = if (failed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f))
        Text(calls.map { ToolPresentation.displayName(it.name) }.distinct().joinToString(", "), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(ToolPresentation.duration(calls.sumOf { it.durationMs ?: 0L }), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, tr(if (open) "tool.collapse" else "tool.expand"), Modifier.size(18.dp))
    }
    Column(Modifier.padding(start = 12.dp)) {
        if (open) ToolSegments(calls) else failed.forEach { ToolCallCard(it) }
    }
}

internal fun kindIcon(k: ToolPresentation.Kind) = when (k) {
    ToolPresentation.Kind.SHELL -> Lucide.SquareTerminal
    ToolPresentation.Kind.FILE -> Lucide.FileText
    ToolPresentation.Kind.MEMORY -> Lucide.Brain
    ToolPresentation.Kind.MCP -> Lucide.Puzzle
    ToolPresentation.Kind.SUBAGENT -> Lucide.Users
    ToolPresentation.Kind.SCHEDULED -> Lucide.CalendarClock
    ToolPresentation.Kind.BROWSER -> Lucide.Globe
    ToolPresentation.Kind.OTHER -> Lucide.Wrench
}

/**
 * UI-REFRESH §2: `[state] [kind] name · one-line summary ........ time · ▾`, collapsed by default.
 * Running = accent + spinner + live elapsed; success = green check + total; failed = error mark + first
 * error line + "Show details"; denied = neutral "Cancelled" + "Ask again".
 */
@Composable
private fun ToolCallCard(tc: ToolCall) {
    val st = ToolPresentation.cardState(tc)
    var open by remember(tc.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val app = LocalAppState.current
    val success = LocalSuccessColor.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = when (st) {
        ToolPresentation.CardState.RUNNING -> MaterialTheme.colorScheme.primary
        ToolPresentation.CardState.SUCCESS -> success
        ToolPresentation.CardState.FAILED -> MaterialTheme.colorScheme.error
        ToolPresentation.CardState.DENIED -> muted
    }
    val summary = remember(tc.argsJson) { toolSummary(tc) }
    val errorLine = if (st == ToolPresentation.CardState.FAILED) ToolPresentation.errorLine(tc) else null
    val now by produceState(System.currentTimeMillis(), st) {
        while (st == ToolPresentation.CardState.RUNNING) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(200) }
    }
    val timeText = when (st) {
        ToolPresentation.CardState.RUNNING -> ToolPresentation.elapsed(tc, now) ?: tr("tool.status.${tc.status.name}")
        ToolPresentation.CardState.DENIED -> tr("tool.cancelled")
        else -> tc.durationMs?.let(ToolPresentation::duration).orEmpty()
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when (st) {
            ToolPresentation.CardState.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            ToolPresentation.CardState.DENIED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (st == ToolPresentation.CardState.RUNNING) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics { contentDescription = tr("tool.card_a11y", ToolPresentation.displayName(tc.name), tr("tool.state.${st.name}")) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (st) {
                    ToolPresentation.CardState.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = tint)
                    ToolPresentation.CardState.SUCCESS -> Icon(Lucide.CircleCheck, tr("tool.state.SUCCESS"), tint = tint, modifier = Modifier.size(16.dp))
                    ToolPresentation.CardState.FAILED -> Icon(Lucide.CircleAlert, tr("tool.state.FAILED"), tint = tint, modifier = Modifier.size(16.dp))
                    ToolPresentation.CardState.DENIED -> Icon(Lucide.Ban, tr("tool.state.DENIED"), tint = tint, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Icon(kindIcon(ToolPresentation.kind(tc.name)), null, tint = muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(ToolPresentation.displayName(tc.name), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    color = if (st == ToolPresentation.CardState.DENIED) muted else androidx.compose.ui.graphics.Color.Unspecified)
                Text(" · ", style = MaterialTheme.typography.labelLarge, color = muted)
                Text(errorLine ?: summary, style = MaterialTheme.typography.bodySmall,
                    color = if (errorLine != null) MaterialTheme.colorScheme.error else muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(timeText, style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.padding(horizontal = 4.dp))
                Icon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, tr(if (open) "tool.collapse" else "tool.expand"), Modifier.size(18.dp))
            }
            if (!open && st == ToolPresentation.CardState.FAILED) {
                Text(tr("tool.show_details"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 42.dp, top = 2.dp))
            }
            if (st == ToolPresentation.CardState.DENIED && app != null && !app.isRunning) {
                TextButton(onClick = { app.requestRetry(tc) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.padding(start = 34.dp).heightIn(min = 28.dp)) {
                    Icon(Lucide.RefreshCw, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                    Text(tr("tool.ask_again"), style = MaterialTheme.typography.labelMedium)
                }
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
        IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) { Icon(Lucide.Copy, tr("tool.copy", label), Modifier.size(14.dp)) }
    }
}

@Composable
internal fun MonoBlock(text: String) {
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        SelectionContainer {
            Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(text, fontFamily = LocalMonoFont.current, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
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
private fun Composer(state: AppState, big: Boolean = false) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.composerFocusTick) { runCatching { focus.requestFocus() } }
    LaunchedEffect(state.composerPrefill) {
        state.composerPrefill?.let { value = TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)); state.composerPrefill = null; runCatching { focus.requestFocus() } }
    }
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
        }, modifier = Modifier.size(52.dp)) { Icon(Lucide.Paperclip, tr("composer.attach")) }
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f).heightIn(min = if (big) 96.dp else 52.dp, max = 260.dp).focusRequester(focus)
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
            shape = RoundedCornerShape(if (big) 18.dp else 14.dp),
        )
        Spacer(Modifier.width(10.dp))
        if (state.isRunning) {
            FilledTonalIconButton(onClick = { state.stop() }, modifier = Modifier.size(52.dp)) { Icon(Lucide.Square, tr("composer.stop")) }
        } else {
            FilledIconButton(onClick = { submit() }, enabled = value.text.isNotBlank() || pending.isNotEmpty(), modifier = Modifier.size(52.dp)) {
                Icon(Lucide.SendHorizontal, tr("composer.send"))
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
                when (a.kind) { AttachmentKind.IMAGE -> Lucide.Image; AttachmentKind.TEXT -> Lucide.FileText; else -> Lucide.File },
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
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) { Icon(Lucide.X, tr("attach.remove", a.fileName), Modifier.size(16.dp)) }
        }
    }
}
