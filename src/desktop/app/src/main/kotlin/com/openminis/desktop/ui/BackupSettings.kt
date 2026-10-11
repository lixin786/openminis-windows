// OpenMinis Desktop — P2-6 UI: Settings › System › Backup, the restore danger confirmation, About › updates.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.BuildConfig
import com.openminis.desktop.core.Backup
import com.openminis.desktop.core.BackupRejected
import com.openminis.desktop.core.UpdateCheck
import com.openminis.desktop.i18n.tr
import com.openminis.desktop.ui.icons.Lucide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The word the user types to let a restore through (spec §5.4); not translated on purpose. */
const val RESTORE_CONFIRM_WORD = "restore"

fun restoreAllowed(typed: String) = typed.trim() == RESTORE_CONFIRM_WORD

fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun rejectText(e: Throwable): String =
    if (e is BackupRejected) tr(e.key, e.arg) else tr("backup.failed", e.message ?: e.javaClass.simpleName)

private fun pickFile(title: String, save: Boolean, defaultName: String? = null): File? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
    if (defaultName != null) d.file = defaultName
    if (!save) d.setFilenameFilter { _, name -> name.lowercase().endsWith(".zip") }
    d.isVisible = true
    val name = d.file ?: return null
    val f = File(d.directory, name)
    return if (save && !f.name.lowercase().endsWith(".zip")) File(f.parentFile, f.name + ".zip") else f
}

@Composable
fun BackupSection(state: AppState, onRestored: () -> Unit) {
    val scope = rememberCoroutineScope()
    var includeSecrets by remember { mutableStateOf(false) }   // spec §2: off by default, never remembered
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<File, Backup.Inspection>?>(null) }

    Section(tr("backup.section"))
    Hint(tr("backup.hint_includes"))
    Hint(tr("backup.hint_excludes"))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Checkbox(checked = includeSecrets, onCheckedChange = { includeSecrets = it }, enabled = busy == null)
        Text(tr("backup.include_secrets"), style = MaterialTheme.typography.bodyMedium)
    }
    Hint(tr("backup.include_secrets_hint"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = busy == null, onClick = {
            val target = pickFile(tr("backup.save_title"), save = true, defaultName = Backup.defaultName()) ?: return@Button
            busy = "backup"; progress = 0f
            scope.launch(Dispatchers.IO) {
                val r = runCatching {
                    Backup.create(state.paths.root, target, includeSecrets, BuildConfig.VERSION_NAME) { d, t ->
                        if (t > 0) scope.launch(Dispatchers.Main) { progress = d.toFloat() / t }
                    }
                }
                withContext(Dispatchers.Main) {
                    busy = null
                    state.toast = r.fold({ tr("backup.done", it.file.absolutePath, humanSize(it.bytes)) }, { rejectText(it) })
                }
            }
        }) { Icon(Lucide.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("backup.now")) }
        if (busy == "backup") { Spacer(Modifier.width(12.dp)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(160.dp)) }
    }

    Section(tr("restore.section"))
    Hint(tr("restore.hint"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = busy == null && pending == null, onClick = {
            restoreError = null
            if (state.busyForRestore) { restoreError = tr("restore.busy"); return@OutlinedButton }
            val zip = pickFile(tr("restore.open_title"), save = false) ?: return@OutlinedButton
            busy = "inspect"
            scope.launch(Dispatchers.IO) {
                val r = runCatching { Backup.inspect(zip) }
                withContext(Dispatchers.Main) { busy = null; r.onSuccess { pending = zip to it }.onFailure { restoreError = rejectText(it) } }
            }
        }) { Icon(Lucide.RefreshCw, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("restore.pick")) }
        if (busy == "inspect" || busy == "restore") { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
    }
    restoreError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

    pending?.let { (zip, insp) ->
        RestoreDangerCard(zip, insp, busy = busy == "restore", onCancel = { pending = null }) {
            if (state.busyForRestore) { restoreError = tr("restore.busy"); pending = null; return@RestoreDangerCard }
            busy = "restore"
            scope.launch(Dispatchers.IO) {
                val r = runCatching {
                    Backup.restore(state.paths.root, zip, state.backupsDir, BuildConfig.VERSION_NAME,
                        beforeSwap = { state.freezeForRestore() })
                }
                withContext(Dispatchers.Main) {
                    busy = null
                    r.onSuccess { pending = null; state.restartRequired = it; onRestored() }
                        .onFailure { pending = null; restoreError = rejectText(it) }
                }
            }
        }
    }
}

/** UI-REFRESH §1 high-risk card: error strip, what is overwritten, type the word, error-coloured primary button. */
@Composable
fun RestoreDangerCard(zip: File, insp: Backup.Inspection, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember(zip) { mutableStateOf("") }
    val m = insp.manifest
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.TriangleAlert, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(tr("restore.confirm_title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Text(tr("restore.confirm_overwrites"), style = MaterialTheme.typography.bodySmall)
            Text(tr("restore.confirm_source", zip.name, Backup.localTime(m.createdAt), m.version.ifBlank { "?" }, insp.fileCount.toString()),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text(tr(if (m.includesSecrets) "restore.confirm_with_secrets" else "restore.confirm_without_secrets"),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text(tr("restore.confirm_safety"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, enabled = !busy,
                label = { Text(tr("restore.type_word", RESTORE_CONFIRM_WORD)) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy, onClick = onCancel) { Text(tr("common.cancel")) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && restoreAllowed(typed), onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                    Text(tr("restore.confirm_button"))
                }
            }
        }
    }
}

/** Shown in the main window after a restore; nothing else should run until the app restarts. */
@Composable
fun RestartRequiredDialog(state: AppState, r: Backup.Restored) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("restore.done_title")) },
        text = { Text(tr("restore.done_text", r.fileCount.toString(), r.preRestore.absolutePath)) },
        confirmButton = { Button(onClick = { state.requestRestart() }) { Text(tr("restore.restart_now")) } },
    )
}

/** About › Check for updates: one request on click, through the proxy settings, no download. */
@Composable
fun UpdateCheckSection(state: AppState) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<UpdateCheck.Outcome?>(null) }
    Section(tr("update.section"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !checking, onClick = {
            checking = true; outcome = null
            scope.launch(Dispatchers.IO) {
                val o = UpdateCheck.check(BuildConfig.VERSION_NAME)
                withContext(Dispatchers.Main) { outcome = o; checking = false }
            }
        }) { Icon(Lucide.RefreshCw, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("update.check")) }
        if (checking) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
    }
    when (val o = outcome) {
        is UpdateCheck.Outcome.Newer -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(tr("update.newer", o.tag), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(o.url)) } }) { Text(tr("update.open_release")) }
        }
        is UpdateCheck.Outcome.UpToDate -> Hint(tr("update.latest", BuildConfig.VERSION_NAME))
        UpdateCheck.Outcome.NoRelease -> Hint(tr("update.no_release"))
        is UpdateCheck.Outcome.Failed -> Hint(tr("update.failed", o.reason))
        null -> {}
    }
}

/** About › old backups: pre-restore zips and folders a restore set aside; each can be deleted. */
@Composable
fun OldBackupsSection(state: AppState) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    val items = remember(tick) { Backup.leftovers(state.paths.root, state.backupsDir).map { it to Backup.sizeOf(it) } }
    Section(tr("backup.old_section"))
    Hint(tr("backup.old_hint", state.backupsDir.absolutePath))
    if (items.isEmpty()) Hint(tr("backup.old_none"))
    items.forEach { (f, size) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("${f.name} · ${humanSize(size)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (confirmDelete == f) {
                TextButton(onClick = { confirmDelete = null }) { Text(tr("common.cancel")) }
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch(Dispatchers.IO) { f.deleteRecursively(); withContext(Dispatchers.Main) { tick++ } }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr("backup.old_delete_confirm")) }
            } else IconButton(onClick = { confirmDelete = f }) { Icon(Lucide.Trash, tr("backup.old_delete"), Modifier.size(16.dp)) }
        }
    }
    OutlinedButton(onClick = { state.openInExplorer(state.backupsDir) }) { Icon(Lucide.FolderOpen, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("backup.old_open")) }
}
