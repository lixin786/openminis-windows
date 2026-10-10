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
import androidx.compose.material3.Checkbox
import com.openminis.desktop.security.ApprovalKind
import com.openminis.desktop.security.ApprovalRisk
import com.openminis.desktop.security.Risk

// UI-REFRESH §1 — approvals as first-class UI. Tiers come from ApprovalRequest.risk:
//   LOW    → one line above the composer (no modal), Enter = allow / Esc = cancel while it has focus;
//   MEDIUM → card dialog; HIGH → card + "I understand" tick or typing the object's name before the main button works.
// Keyboard (dialog): Enter = main button (only when enabled), Esc = cancel, Ctrl+Enter / A = remember option when offered.

/** LOW risk shows inline, unless a modal (settings) would hide the line — then it falls back to the dialog. */
internal fun isInline(state: AppState, p: PendingApproval): Boolean =
    p.request.risk == Risk.LOW && !state.showSettings && !state.showScheduled

private fun approvalIcon(k: ApprovalKind) = when (k) {
    ApprovalKind.SHELL -> Icons.Default.Terminal
    ApprovalKind.FILE -> Icons.Default.Description
    ApprovalKind.MCP_START, ApprovalKind.MCP_CALL -> Icons.Default.Extension
    ApprovalKind.SCHEDULED -> Icons.Default.Schedule
    ApprovalKind.SUBAGENT -> Icons.Default.Groups
    ApprovalKind.OTHER -> Icons.Default.Security
}

@Composable
internal fun InlineApproval(state: AppState, p: PendingApproval) {
    val allowFocus = remember { FocusRequester() }
    LaunchedEffect(p) { kotlinx.coroutines.delay(60); runCatching { allowFocus.requestFocus() } }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Enter, Key.NumPadEnter -> { state.answerApproval(allow = true); true }
                    Key.Escape -> { state.answerApproval(allow = false); true }
                    else -> false
                }
            }
            .semantics { contentDescription = tr("approval.a11y", p.request.title) },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(approvalIcon(p.request.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("approval.inline", p.request.title), style = MaterialTheme.typography.labelLarge)
                Text(p.request.detail, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { state.answerApproval(allow = false) }) { Text(tr("approval.cancel")) }
            Button(onClick = { state.answerApproval(allow = true) }, modifier = Modifier.focusRequester(allowFocus),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text(p.request.actionLabel ?: tr("approval.allow_plain")) }
        }
    }
}

@Composable
internal fun ApprovalDialog(state: AppState, p: PendingApproval) {
    val r = p.request
    val high = r.risk == Risk.HIGH
    val mainFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    var understood by remember(p) { mutableStateOf(false) }
    var typed by remember(p) { mutableStateOf("") }
    val armed = !high || (if (r.confirmPhrase != null) ApprovalRisk.phraseMatches(r.confirmPhrase, typed) else understood)
    // [security] HIGH never offers a remember option, whatever the request carries.
    val rememberLabel = if (!high && r.onAlwaysAllow != null) r.rememberLabel ?: tr("approval.always") else null
    // HIGH starts focused on Cancel (Enter must not run it by reflex); others on the main button.
    LaunchedEffect(p) { kotlinx.coroutines.delay(60); runCatching { if (high) cancelFocus.requestFocus() else mainFocus.requestFocus() } }
    val error = MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = { state.answerApproval(allow = false) },   // Esc
        modifier = Modifier.widthIn(min = 460.dp, max = 640.dp).onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                (ev.key == Key.Enter || ev.key == Key.NumPadEnter) && ev.isCtrlPressed -> { if (rememberLabel != null) state.answerApproval(allow = true, dontAskAgain = true); true }
                ev.key == Key.A && !ev.isCtrlPressed && !ev.isAltPressed && rememberLabel != null -> { state.answerApproval(allow = true, dontAskAgain = true); true }
                ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { if (armed) state.answerApproval(allow = true); true }
                ev.key == Key.Escape -> { state.answerApproval(allow = false); true }
                else -> false
            }
        }.semantics { contentDescription = tr("approval.a11y", r.title) },
        icon = { Icon(approvalIcon(r.kind), null, tint = if (high) error else MaterialTheme.colorScheme.primary) },
        title = { Text(r.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (r.consequence.isNotBlank()) Text(r.consequence, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (high) FontWeight.SemiBold else null)
                if (r.title != r.summary) Text(r.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("approval.raw"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(r.detail)) }, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.ContentCopy, tr("approval.copy"), Modifier.size(14.dp))
                    }
                }
                MonoBlock(r.detail)
                if (r.riskNotes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            r.riskNotes.forEach { note ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.Warning, null, Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(6.dp))
                                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
                if (high) {
                    Spacer(Modifier.height(8.dp))
                    if (r.confirmPhrase != null) {
                        Text(tr("approval.type_to_confirm", r.confirmPhrase), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(typed, { typed = it }, singleLine = true, isError = typed.isNotEmpty() && !armed,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFont.current),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = tr("approval.type_to_confirm", r.confirmPhrase) })
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { understood = !understood }) {
                            Checkbox(understood, { understood = it }, modifier = Modifier.semantics { contentDescription = tr("approval.understand") })
                            Text(tr("approval.understand"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (high) tr("approval.keys_high") else tr("approval.keys2")) + (rememberLabel?.let { tr("approval.keys_remember", it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rememberLabel != null) OutlinedButton(onClick = { state.answerApproval(allow = true, dontAskAgain = true) },
                    modifier = Modifier.semantics { contentDescription = tr("approval.remember_a11y", rememberLabel) }) { Text(rememberLabel) }
                Spacer(Modifier.width(8.dp))
                val label = r.actionLabel ?: tr(if (high) "approval.run_anyway" else "approval.allow_plain")
                Button(
                    onClick = { state.answerApproval(allow = true) }, enabled = armed,
                    colors = if (high) ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.focusRequester(mainFocus).semantics { contentDescription = label },
                ) { Text(label) }
            }
        },
        dismissButton = {
            TextButton(onClick = { state.answerApproval(allow = false) },
                modifier = Modifier.focusRequester(cancelFocus).semantics { contentDescription = tr("approval.cancel") }) { Text(tr("approval.cancel")) }
        },
    )
}
