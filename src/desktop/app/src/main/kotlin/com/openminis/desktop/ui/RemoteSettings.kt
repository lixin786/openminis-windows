// OpenMinis Desktop — Settings › System › Phone link (batch13, MCP-SERVER.md).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.openminis.desktop.i18n.tr
import com.openminis.desktop.remote.LanAddresses
import com.openminis.desktop.remote.RemoteMcpController
import com.openminis.desktop.remote.RemoteMcpSettings
import com.openminis.desktop.ui.icons.Lucide
import com.openminis.desktop.remote.McpHttpCore
import com.openminis.desktop.remote.RemoteAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Audit/connection timestamps: local wall clock, same in every language. */
internal fun auditTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

/** Bytes as the audit list shows them: 0 B, 812 B, 3.4 KB, 1.2 MB. */
internal fun humanBytes(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
}

/** i18n key of an audit outcome; unknown values fall back to the raw word. */
internal fun outcomeKey(outcome: String): String? = when (outcome) {
    RemoteAudit.OK, RemoteAudit.ERROR, RemoteAudit.TIMEOUT, RemoteAudit.UNKNOWN_TOOL -> "remote.audit.outcome.$outcome"
    else -> null
}

/** The token shown on screen: first and last 4 characters only; Copy puts the full string on the clipboard. */
internal fun maskToken(t: String): String = if (t.length <= 8) "••••" else t.take(4) + "••••••••" + t.takeLast(4)

@Composable
internal fun RemoteSection(state: AppState, s: RemoteMcpSettings, onChange: (RemoteMcpSettings) -> Unit) {
    val tick = state.remoteTick
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }
    var confirmRotate by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.TriangleAlert, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(tr("remote.warning_plaintext"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    Toggle(tr("remote.enable"), s.enabled) { onChange(s.copy(enabled = it)) }
    Hint(tr("remote.privacy_boundary"))
    Toggle(tr("remote.deny_sensitive"), s.denySensitive) { onChange(s.copy(denySensitive = it)) }
    Hint(tr("remote.deny_sensitive_hint"))
    NumberField(tr("remote.port"), s.port, Modifier.fillMaxWidth()) { onChange(s.copy(port = it.coerceIn(1024, 65535))) }
    Hint(tr("remote.apply_on_save"))

    Section(tr("remote.status"))
    val st = remember(tick) { state.remote.server.status }
    if (!st.running) Hint(tr("remote.not_running"))
    st.bindings.forEach { b -> Text(tr("remote.listening", "${b.address}:${b.port}", b.nic), style = MaterialTheme.typography.bodyMedium) }
    st.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(enabled = st.running, onClick = { scope.launch(Dispatchers.IO) { state.remote.rebind() } }) {
        Icon(Lucide.RefreshCw, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("remote.rebind"))
    }

    Section(tr("remote.connect"))
    Hint(tr("remote.connect_hint"))
    val token = remember(tick) { state.remote.tokens.get() }
    when (remember(tick, token) { state.remote.tokens.notice }) {
        com.openminis.desktop.remote.RemoteTokenStore.Notice.REGENERATED_UNREADABLE ->
            Text(tr("remote.token_regenerated"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        com.openminis.desktop.remote.RemoteTokenStore.Notice.NOT_SAVED ->
            Text(tr("remote.token_not_saved"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        null -> {}
    }
    val addrs = remember(tick, st) { st.bindings.map { it.address }.ifEmpty { LanAddresses.current().map { it.second.hostAddress } } }
    if (addrs.isEmpty()) Hint(tr("remote.no_lan"))
    addrs.forEach { a ->
        val full = RemoteMcpController.connectionString(a, s.port, token)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(RemoteMcpController.connectionString(a, s.port, maskToken(token)), fontFamily = LocalMonoFont.current,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(full)); copied = a }) {
                Icon(if (copied == a) Lucide.Check else Lucide.Copy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(tr(if (copied == a) "remote.copied" else "remote.copy"))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        if (!confirmRotate) {
            OutlinedButton(onClick = { confirmRotate = true }) {
                Icon(Lucide.KeyRound, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("remote.rotate"))
            }
        } else {
            Button(onClick = { state.remote.rotateToken(); confirmRotate = false; copied = null },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(tr("remote.rotate_confirm")) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { confirmRotate = false }) { Text(tr("common.cancel")) }
        }
    }
    Hint(tr("remote.rotate_hint"))

    ConnectionsSection(state)
    AuditSection(state)
}

/** 13.3: who is connected right now, with Disconnect; plus devices refused after a disconnect. */
@Composable
private fun ConnectionsSection(state: AppState) {
    val tick = state.remoteTick
    var poll by remember { mutableStateOf(0) }
    // Sessions change without a settings event (a phone connects, idles out); re-read every 2 s while visible.
    LaunchedEffect(Unit) { while (true) { delay(2_000); poll++ } }
    val sessions = remember(tick, poll) { state.remote.core.sessionInfos() }
    val blocked = remember(tick, poll) { state.remote.core.blockedAddresses() }

    Section(tr("remote.sessions"))
    if (sessions.isEmpty()) Hint(tr("remote.sessions.none"))
    sessions.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(Modifier.weight(1f)) {
                Text(tr("remote.sessions.client", s.clientName.ifBlank { tr("remote.sessions.unnamed") }, s.remote),
                    style = MaterialTheme.typography.bodyMedium)
                Text(tr("remote.sessions.detail", auditTime(s.createdAt), auditTime(s.lastUsed), s.calls.toString()) +
                    if (s.initialized) "" else " · " + tr("remote.sessions.handshaking"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { state.remote.kick(s.id) }) {
                Icon(Lucide.X, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("remote.sessions.kick"))
            }
        }
    }
    Hint(tr("remote.sessions.kick_hint", (McpHttpCore.KICK_BLOCK_MS / 60_000).toString()))
    blocked.forEach { (ip, left) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(tr("remote.sessions.blocked", ip, ((left + 59_999) / 60_000).toString()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.remote.unblock(ip) }) { Text(tr("remote.sessions.unblock")) }
        }
    }
}

/** 13.3: the audit log (newest first), with Refresh and Clear. Never shows or stores the token or file contents. */
@Composable
private fun AuditSection(state: AppState) {
    val tick = state.remoteTick
    var reload by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<RemoteAudit.Entry>>(emptyList()) }
    LaunchedEffect(tick, reload) { entries = withContext(Dispatchers.IO) { state.remote.audit.entries(AUDIT_SHOWN) } }

    Section(tr("remote.audit"))
    Hint(tr("remote.audit.hint"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { reload++ }) {
            Icon(Lucide.RefreshCw, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("remote.audit.refresh"))
        }
        Spacer(Modifier.width(8.dp))
        if (!confirmClear) {
            OutlinedButton(enabled = entries.isNotEmpty(), onClick = { confirmClear = true }) {
                Icon(Lucide.Trash, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("remote.audit.clear"))
            }
        } else {
            Button(onClick = { state.remote.clearAudit(); confirmClear = false; reload++ },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(tr("remote.audit.clear_confirm")) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { confirmClear = false }) { Text(tr("common.cancel")) }
        }
    }
    if (entries.isEmpty()) Hint(tr("remote.audit.empty"))
    entries.forEach { e ->
        val failed = e.outcome != RemoteAudit.OK
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(auditTime(e.time), fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(e.ip, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text(e.tool, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text(outcomeKey(e.outcome)?.let { tr(it) } ?: e.outcome, style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(humanBytes(e.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.path.isNotEmpty()) Text(e.path, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    if (entries.size >= AUDIT_SHOWN) Hint(tr("remote.audit.truncated", AUDIT_SHOWN.toString()))
}

/** The page shows the newest this many; the file keeps more (capped at 2 MB). */
internal const val AUDIT_SHOWN = 200
