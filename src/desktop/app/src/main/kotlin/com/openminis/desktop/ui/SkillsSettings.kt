// OpenMinis Desktop — Settings › Skills (P1-3): installed skills with an on/off switch, detail, open
// folder, delete; import from a GitHub / SKILL.md URL, a .zip / .skill file, a local folder, or pasted
// SKILL.md text (upstream SkillImportSheet: URL / Paste / File, plus the desktop folder import).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

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
                message = ("Installed «${res.skill.name}» → ${res.skill.dir.absolutePath}" + (res.warning?.let { "\n$it" } ?: "")) to (res.warning != null)
                if (res.skill.id in disabled) onDisabledChange(disabled - res.skill.id)
                reload++
            }.onFailure { e -> message = "$label failed: ${e.message}" to true }
        }
    }

    Hint("${skills.size} skill(s). Each skill is a folder with SKILL.md under ${state.paths.skillsDir.absolutePath} (imports go here) or <workspace>\\.skills. Switched-off skills stay on disk but are not offered to the agent.")
    skills.forEach { sk ->
        SkillRow(sk, enabled = sk.id !in disabled,
            onToggle = { on -> onDisabledChange(if (on) disabled - sk.id else (disabled + sk.id).distinct()) },
            onOpen = { state.openInExplorer(sk.dir) },
            onDelete = {
                runCatching { state.skillImporter.delete(sk, state.skillRoots()) }
                    .onSuccess { message = "Deleted «${sk.name}»." to false; reload++ }
                    .onFailure { message = "Delete failed: ${it.message}" to true }
            })
    }

    Spacer(Modifier.height(8.dp))
    Text("Import a skill", style = MaterialTheme.typography.titleSmall)
    var url by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus(),
            label = { Text("GitHub folder / SKILL.md URL (e.g. https://github.com/user/repo/tree/main/skills/x)") })
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = url.isNotBlank() && !busy, onClick = { val u = url.trim(); run("Import from URL") { state.skillImporter.importFromUrl(u) }; url = "" }) { Text("Import URL") }
    }
    Row {
        OutlinedButton(enabled = !busy, onClick = {
            pickFile(dirs = false)?.let { f -> run("Import ${f.name}") {
                if (f.name.endsWith(".md", true)) state.skillImporter.importFromContent(f.readText(Charsets.UTF_8)) else state.skillImporter.importFromArchive(f)
            } }
        }) { Text("Import .zip / .skill / SKILL.md file…") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = !busy, onClick = { pickFile(dirs = true)?.let { d -> run("Import folder") { state.skillImporter.importFromFolder(d) } } }) { Text("Import folder…") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { state.openInExplorer(state.paths.skillsDir) }) { Text("Open skills folder") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { reload++ }) { Text("Rescan") }
    }
    var paste by remember { mutableStateOf("") }
    OutlinedTextField(paste, { paste = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
        label = { Text("…or paste SKILL.md content (--- name: … description: … --- then instructions)") })
    OutlinedButton(enabled = paste.isNotBlank() && !busy, onClick = { val t = paste; run("Import pasted SKILL.md") { state.skillImporter.importFromContent(t) }; paste = "" }) { Text("Import pasted") }
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
                IconButton(onClick = onOpen) { Icon(Icons.Default.FolderOpen, "Open skill folder") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete skill") }
            }
            if (confirmDelete) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Delete the folder ${sk.dir.absolutePath}?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            if (open) {
                Text("Version ${sk.version} · ${sk.file.absolutePath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sk.description, style = MaterialTheme.typography.bodySmall)
                Text(sk.body.take(1500) + if (sk.body.length > 1500) "\n…" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun pickFile(dirs: Boolean): File? {
    val fc = JFileChooser().apply {
        fileSelectionMode = if (dirs) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        if (!dirs) fileFilter = FileNameExtensionFilter("Skill (.zip, .skill, SKILL.md)", "zip", "skill", "md")
        dialogTitle = if (dirs) "Choose a skill folder (contains SKILL.md)" else "Choose a skill archive or SKILL.md"
    }
    return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
}
