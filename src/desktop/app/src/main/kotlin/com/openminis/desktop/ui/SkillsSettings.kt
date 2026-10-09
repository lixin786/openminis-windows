// OpenMinis Desktop — Settings › Skills (P1-3): installed skills with an on/off switch, detail, open
// folder, delete; import from a GitHub / SKILL.md URL, a .zip / .skill file, a local folder, or pasted
// SKILL.md text (upstream SkillImportSheet: URL / Paste / File, plus the desktop folder import).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.desktop.skills.Skill
import com.openminis.desktop.skills.SkillImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal fun SkillsSection(state: AppState, disabled: List<String>, onDisabledChange: (List<String>) -> Unit) {
    var reload by remember { mutableStateOf(0) }
    val skills = remember(reload) { runCatching { state.skills.load() }.getOrDefault(emptyList()) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError

    fun run(label: String, block: () -> SkillImportResult) {
        busy = true; message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            r.onSuccess { res ->
                message = (tr("skills.installed", res.skill.name, res.skill.dir.absolutePath) + (res.warning?.let { "\n$it" } ?: "")) to (res.warning != null)
                if (res.skill.id in disabled) onDisabledChange(disabled - res.skill.id)
                reload++
            }.onFailure { e -> message = tr("skills.failed", label, e.message) to true }
        }
    }

    Hint(tr("skills.hint", skills.size, state.paths.skillsDir.absolutePath))
    skills.forEach { sk ->
        SkillRow(sk, enabled = sk.id !in disabled,
            onToggle = { on -> onDisabledChange(if (on) disabled - sk.id else (disabled + sk.id).distinct()) },
            onOpen = { state.openInExplorer(sk.dir) },
            onDelete = {
                runCatching { state.skillImporter.delete(sk, state.skillRoots()) }
                    .onSuccess { message = tr("skills.deleted", sk.name) to false; reload++ }
                    .onFailure { message = tr("skills.delete_failed", it.message) to true }
            })
    }

    Spacer(Modifier.height(8.dp))
    Text(tr("skills.import_title"), style = MaterialTheme.typography.titleSmall)
    var url by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus(),
            label = { Text(tr("skills.url_label")) })
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = url.isNotBlank() && !busy, onClick = { val u = url.trim(); run(tr("skills.op.url")) { state.skillImporter.importFromUrl(u) }; url = "" }) { Text(tr("skills.import_url")) }
    }
    Row {
        OutlinedButton(enabled = !busy, onClick = {
            pickFile(dirs = false)?.let { f -> run(tr("skills.op.file", f.name)) {
                if (f.name.endsWith(".md", true)) state.skillImporter.importFromContent(f.readText(Charsets.UTF_8)) else state.skillImporter.importFromArchive(f)
            } }
        }) { Text(tr("skills.import_file")) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = !busy, onClick = { pickFile(dirs = true)?.let { d -> run(tr("skills.op.folder")) { state.skillImporter.importFromFolder(d) } } }) { Text(tr("skills.import_folder")) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { state.openInExplorer(state.paths.skillsDir) }) { Text(tr("skills.open_folder")) }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { reload++ }) { Text(tr("skills.rescan")) }
    }
    var paste by remember { mutableStateOf("") }
    OutlinedTextField(paste, { paste = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("skills.paste_label")) })
    OutlinedButton(enabled = paste.isNotBlank() && !busy, onClick = { val t = paste; run(tr("skills.op.paste")) { state.skillImporter.importFromContent(t) }; paste = "" }) { Text(tr("skills.import_pasted")) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
    message?.let { (t, err) ->
        Text(t, style = MaterialTheme.typography.bodySmall, color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SkillRow(sk: Skill, enabled: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    var open by remember(sk.id) { mutableStateOf(false) }
    var confirmDelete by remember(sk.id) { mutableStateOf(false) }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(enabled, onToggle)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { open = !open }, modifier = Modifier.weight(1f)) {
                    Text("${if (open) "▾" else "▸"} ${sk.name} — ${sk.description.take(90)}", Modifier.fillMaxWidth(), maxLines = 1)
                }
                IconButton(onClick = onOpen) { Icon(Icons.Default.FolderOpen, tr("skills.open_one")) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, tr("skills.delete_one")) }
            }
            if (confirmDelete) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("skills.delete_q", sk.dir.absolutePath), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = false }) { Text(tr("common.keep")) }
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(tr("common.delete"), color = MaterialTheme.colorScheme.error) }
            }
            if (open) {
                Text(tr("skills.version", sk.version, sk.file.absolutePath), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sk.description, style = MaterialTheme.typography.bodySmall)
                Text(sk.body.take(1500) + if (sk.body.length > 1500) "\n…" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun pickFile(dirs: Boolean): File? {
    val fc = JFileChooser().apply {
        fileSelectionMode = if (dirs) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        if (!dirs) fileFilter = FileNameExtensionFilter(tr("skills.filter"), "zip", "skill", "md")
        dialogTitle = if (dirs) tr("skills.choose_folder") else tr("skills.choose_file")
    }
    return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
}
