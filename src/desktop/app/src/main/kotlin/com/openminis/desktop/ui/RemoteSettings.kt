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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
}
