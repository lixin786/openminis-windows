// OpenMinis Desktop — settings window (providers/API keys, agent, tools/shell, memory, skills).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.core.DesktopProviderFactory
import com.openminis.desktop.core.ProviderKind
import com.openminis.desktop.core.ShellKind
import com.openminis.desktop.tools.ShellRunner
import java.io.File
import javax.swing.JFileChooser

@Composable
fun SettingsDialog(state: AppState, onClose: () -> Unit) {
    var draft by remember { mutableStateOf(state.settings) }
    var editing by remember { mutableStateOf(state.settings.activeProvider) }
    var subDraft by remember { mutableStateOf(state.subAgentConfig) }
    var mcpDraft by remember { mutableStateOf(state.mcp.servers) }
    val save = { state.updateSettings(draft); state.updateSubAgentConfig(subDraft); if (mcpDraft != state.mcp.servers) state.updateMcpServers(mcpDraft); onClose() }
    DialogWindow(
        onCloseRequest = onClose, title = "OpenMinis Settings",
        state = rememberDialogState(size = settingsDialogSize()),
        // Esc closes without saving; Ctrl+S / Ctrl+Enter saves.
        onPreviewKeyEvent = { ev ->
            if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
            else when {
                ev.key == androidx.compose.ui.input.key.Key.Escape -> { onClose(); true }
                ev.isCtrlPressed && (ev.key == androidx.compose.ui.input.key.Key.S || ev.key == androidx.compose.ui.input.key.Key.Enter) -> { save(); true }
                else -> false
            }
        },
    ) {
        MinisTheme(state.settings.darkTheme) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Section("Model provider")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Active provider:", Modifier.width(140.dp))
                            Dropdown(draft.activeProvider, ProviderKind.entries, { it.label }, { draft = draft.copy(activeProvider = it); editing = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Edit credentials for:", Modifier.width(140.dp))
                            Dropdown(editing, ProviderKind.entries, { k -> k.label + if (draft.provider(k).apiKey.isNotBlank()) "  ✓" else "" }, { editing = it })
                        }
                        val ps = draft.provider(editing)
                        var showKey by remember(editing) { mutableStateOf(false) }
                        OutlinedTextField(
                            value = ps.apiKey, onValueChange = { draft = draft.withProvider(editing, ps.copy(apiKey = it.trim())) },
                            label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show/hide")
                                }
                            },
                        )
                        OutlinedTextField(
                            value = ps.baseUrl, onValueChange = { draft = draft.withProvider(editing, ps.copy(baseUrl = it)) },
                            label = { Text("Base URL (blank = ${editing.defaultBaseUrl})") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                        )
                        OutlinedTextField(
                            value = ps.model, onValueChange = { draft = draft.withProvider(editing, ps.copy(model = it)) },
                            label = { Text("Model id (blank = ${editing.defaultModel})") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                            supportingText = { Text("Suggestions: " + DesktopProviderFactory.suggestedModels(editing).take(6).joinToString(", ")) },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Image input:", Modifier.width(140.dp))
                            Dropdown(
                                ps.imageInput, listOf(null, true, false),
                                { when (it) { null -> "Auto (catalog / provider default)"; true -> "On — model accepts images"; false -> "Off — send a text placeholder" } },
                                { draft = draft.withProvider(editing, ps.copy(imageInput = it)) },
                            )
                        }
                        Hint("Keys are stored in plain text in ${state.paths.settingsFile.absolutePath} (inside your user profile).")

                        Section("Fallback models")
                        Hint("When the active model fails with a retryable error (429 rate limit, 5xx / overloaded, network error after 3 retries) the turn moves to the next model below, in order. Each entry uses that provider's API key above.")
                        val chain = draft.fallbackModels
                        Text("0.  ${draft.primaryModel().label}  (active model)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        chain.forEachIndexed { i, ref ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}.  ${ref.label}" + if (draft.provider(ref.provider).apiKey.isBlank() && ref.provider != ProviderKind.OPENAI_COMPATIBLE) "  (no API key — will be skipped)" else "",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.toMutableList().also { l -> java.util.Collections.swap(l, i, i - 1) }) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.toMutableList().also { l -> java.util.Collections.swap(l, i, i + 1) }) }, enabled = i < chain.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Delete, "Remove") }
                            }
                        }
                        var addKind by remember { mutableStateOf(ProviderKind.OPENAI) }
                        var addModel by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dropdown(addKind, ProviderKind.entries, { it.label }, { addKind = it; addModel = "" })
                            OutlinedTextField(
                                value = addModel, onValueChange = { addModel = it }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus(),
                                label = { Text("Model id (blank = ${draft.provider(addKind).effectiveModel(addKind)})") },
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val ref = com.openminis.desktop.core.ModelRef(addKind, addModel.trim().ifBlank { draft.provider(addKind).effectiveModel(addKind) })
                                if (ref !in chain) draft = draft.copy(fallbackModels = chain + ref)
                                addModel = ""
                            }) { Text("Add") }
                        }
                        Hint("Suggestions: " + DesktopProviderFactory.suggestedModels(addKind).take(5).joinToString(", "))
                        Toggle("Fall back on any error (not only retryable ones)", draft.fallbackOnAnyError) { draft = draft.copy(fallbackOnAnyError = it) }

                        Section("Network")
                        NetworkSection(draft.network) { draft = draft.copy(network = it) }

                        Section("Context & compaction")
                        Toggle("Compact automatically when the conversation nears the context window", draft.autoCompact) { draft = draft.copy(autoCompact = it) }
                        NumberField("Context window override in tokens (0 = auto from the model catalog)", draft.contextWindowOverride, Modifier.fillMaxWidth()) {
                            draft = draft.copy(contextWindowOverride = it.coerceIn(0, 10_000_000))
                        }
                        NumberField("Inline text attachments up to N characters", draft.maxInlineTextChars, Modifier.fillMaxWidth()) {
                            draft = draft.copy(maxInlineTextChars = it.coerceIn(1000, 2_000_000))
                        }
                        Hint("Manual compaction: the Compact button in the chat toolbar, Chat → Compact context, or type /compact.")

                        Section("Agent")
                        OutlinedTextField(
                            value = draft.systemPrompt, onValueChange = { draft = draft.copy(systemPrompt = it) },
                            label = { Text("Custom instructions (appended to the system prompt)") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).tabMovesFocus(), maxLines = 8,
                        )
                        Row {
                            NumberField("Max output tokens", draft.maxOutputTokens, Modifier.weight(1f)) { draft = draft.copy(maxOutputTokens = it.coerceIn(256, 200_000)) }
                            Spacer(Modifier.width(12.dp))
                            NumberField("Max tool rounds per turn", draft.maxToolRounds, Modifier.weight(1f)) { draft = draft.copy(maxToolRounds = it.coerceIn(1, 500)) }
                        }
                        Toggle("Dark theme (unset = follow system)", draft.darkTheme ?: false) { draft = draft.copy(darkTheme = it) }

                        Section("Tools & shell")
                        Toggle("Enable tools (shell, files, memory)", draft.toolsEnabled) { draft = draft.copy(toolsEnabled = it) }
                        Toggle("Ask before running every shell command", draft.confirmShellCommands) { draft = draft.copy(confirmShellCommands = it) }
                        Toggle("Ask before writing files outside the workspace", draft.confirmWritesOutsideWorkspace) { draft = draft.copy(confirmWritesOutsideWorkspace = it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Shell:", Modifier.width(140.dp))
                            Dropdown(
                                draft.shell, ShellKind.entries,
                                { it.label + if (it == ShellKind.WSL && !ShellRunner.wslAvailable) " (not detected)" else "" },
                                { draft = draft.copy(shell = it) },
                            )
                        }
                        if (!AppPaths.isWindows) Hint("Not running on Windows: commands use /bin/bash (development fallback).")
                        NumberField("Shell timeout (seconds)", draft.shellTimeoutSeconds, Modifier.fillMaxWidth()) { draft = draft.copy(shellTimeoutSeconds = it.coerceIn(1, 1800)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft.workspaceDir, onValueChange = { draft = draft.copy(workspaceDir = it) },
                                label = { Text("Workspace folder (blank = ${AppPaths.defaultWorkspace().absolutePath})") },
                                singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus(),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val chooser = JFileChooser(draft.workspace()).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) draft = draft.copy(workspaceDir = chooser.selectedFile.absolutePath)
                            }) { Text("Browse…") }
                        }

                        Section("MCP servers")
                        McpSection(state, mcpDraft) { mcpDraft = it }

                        Section("Sub agents")
                        SubAgentSection(subDraft, draft, state.toolNames()) { subDraft = it }

                        Section("Memory")
                        Toggle("Enable memory (memory_write / memory_get + prompt context)", draft.memoryEnabled) { draft = draft.copy(memoryEnabled = it) }
                        Row {
                            OutlinedButton(onClick = { state.openFile(state.memory.globalFile, "# GLOBAL memory\n\nFacts and preferences the agent should always know.\n") }) { Text("Edit GLOBAL.md") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { state.openInExplorer(state.memory.dir) }) { Text("Open memory folder") }
                        }

                        Section("Skills")
                        Toggle("Enable skills (SKILL.md folders)", draft.skillsEnabled) { draft = draft.copy(skillsEnabled = it) }
                        val skills = remember { runCatching { state.skills.load() }.getOrDefault(emptyList()) }
                        Hint("${skills.size} skill(s) found. Put each skill in its own folder containing SKILL.md under ${state.paths.skillsDir.absolutePath} or <workspace>\\.skills.")
                        skills.take(30).forEach { Text("• ${it.name} — ${it.description.take(100)}", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = { state.openInExplorer(state.paths.skillsDir) }) { Text("Open skills folder") }

                        Section("Data")
                        Hint("App data: ${state.paths.root.absolutePath}")
                        OutlinedButton(onClick = { state.openInExplorer(state.paths.root) }) { Text("Open data folder") }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("Cancel (Esc)") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { save() }) { Text("Save (Ctrl+S)") }
                    }
                }
            }
        }
    }
}

@Composable internal fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable internal fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

@Composable internal fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f))
        // Focusable with Tab, toggled with Space; the label is the accessible name.
        Switch(checked = checked, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

/** Settings window size clamped to the usable screen (HiDPI laptops). */
internal fun settingsDialogSize(): DpSize {
    val s = com.openminis.desktop.core.WindowPlacements.screens().firstOrNull()
    val w = if (s != null) minOf(720, (s.width * 0.9).toInt()) else 720
    val h = if (s != null) minOf(820, (s.height * 0.9).toInt()) else 820
    return DpSize(w.dp, h.dp)
}

@Composable private fun NetworkSection(n: com.openminis.desktop.net.NetworkSettings, onChange: (com.openminis.desktop.net.NetworkSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Proxy:", Modifier.width(140.dp))
        Dropdown(n.proxyMode, com.openminis.desktop.net.ProxyMode.entries, { it.label }, { onChange(n.copy(proxyMode = it)) })
    }
    when (n.proxyMode) {
        com.openminis.desktop.net.ProxyMode.SYSTEM -> Hint("Uses HTTPS_PROXY / HTTP_PROXY / ALL_PROXY / NO_PROXY when set, otherwise the Windows proxy settings (Settings → Network & Internet → Proxy, including a PAC script / auto-detect where the JDK supports it).")
        com.openminis.desktop.net.ProxyMode.NONE -> Hint("All connections go direct.")
        com.openminis.desktop.net.ProxyMode.MANUAL -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Type:", Modifier.width(140.dp))
                Dropdown(n.proxyType, com.openminis.desktop.net.ProxyType.entries, { if (it == com.openminis.desktop.net.ProxyType.HTTP) "HTTP / HTTPS (CONNECT)" else "SOCKS5" }, { onChange(n.copy(proxyType = it)) })
            }
            Row {
                OutlinedTextField(n.proxyHost, { onChange(n.copy(proxyHost = it.trim())) }, label = { Text("Host (e.g. 127.0.0.1)") }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
                Spacer(Modifier.width(8.dp))
                NumberField("Port", n.proxyPort, Modifier.width(120.dp)) { onChange(n.copy(proxyPort = it.coerceIn(0, 65535))) }
            }
            Row {
                OutlinedTextField(n.proxyUser, { onChange(n.copy(proxyUser = it)) }, label = { Text("User (optional)") }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(n.proxyPassword, { onChange(n.copy(proxyPassword = it)) }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f).tabMovesFocus())
            }
            OutlinedTextField(n.bypass, { onChange(n.copy(bypass = it)) }, label = { Text("Bypass (no proxy) for: ; separated, *.example.com, 10.*, <local>") },
                singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
        }
    }
    // [hotfix2] no clamp per keystroke: clamping "20" → "2" → 3 while the user edits used to leave a
    // 3-second watchdog behind. The watchdog clamps (and treats < 3 as the 20 s default) when it arms.
    NumberField("Connect + TLS handshake timeout (seconds, 3–300)", n.connectTimeoutSeconds, Modifier.fillMaxWidth()) { onChange(n.copy(connectTimeoutSeconds = it)) }
    Hint("A connection that has not finished its TLS handshake within this time is aborted and reported (then retried / handed to a fallback model).")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("HTTP protocol:", Modifier.width(140.dp))
        Dropdown(n.httpProtocol, com.openminis.desktop.net.HttpProtocolMode.entries, { it.label }, { onChange(n.copy(httpProtocol = it)) })
    }
    Hint(when (n.httpProtocol) {
        com.openminis.desktop.net.HttpProtocolMode.AUTO -> "HTTP/2 when the server offers it. If a server resets an HTTP/2 stream (\"stream was reset\"), that host is switched to HTTP/1.1 for the rest of the session and the request is retried immediately."
        com.openminis.desktop.net.HttpProtocolMode.HTTP1_ONLY -> "Every connection uses HTTP/1.1 (like curl --http1.1). Use this for gateways / relays whose HTTP/2 streaming is broken."
    })
}

@Composable internal fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text, singleLine = true, label = { Text(label) }, modifier = modifier.tabMovesFocus(),
        onValueChange = { t -> text = t.filter(Char::isDigit).take(7); text.toIntOrNull()?.let(onChange) },
    )
}

@Suppress("unused") private fun File.display() = absolutePath
