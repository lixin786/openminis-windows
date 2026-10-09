// OpenMinis Desktop — settings window (providers/API keys, agent, tools/shell, memory, skills).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.i18n.AppLanguage
import com.openminis.desktop.i18n.I18n
import com.openminis.desktop.i18n.tr

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
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
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
        onCloseRequest = onClose, title = tr("settings.title"),
        state = rememberFittedDialogState(720.dp, 820.dp), resizable = true,
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
        MinisTheme(state.settings) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                        Section(tr("settings.language"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.language.label"), Modifier.width(140.dp))
                            Dropdown(AppLanguage.of(draft.language), AppLanguage.entries,
                                { if (it == AppLanguage.SYSTEM) tr("settings.language.system", AppLanguage.of(I18n.resolve("system")).nativeName) else it.nativeName },
                                { draft = draft.copy(language = it.code) })
                        }
                        Hint(tr("settings.language.hint"))

                        Section(tr("settings.provider"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.active_provider"), Modifier.width(140.dp))
                            Dropdown(draft.activeProvider, ProviderKind.entries, { it.label }, { draft = draft.copy(activeProvider = it); editing = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.edit_for"), Modifier.width(140.dp))
                            Dropdown(editing, ProviderKind.entries, { k -> k.label + if (draft.provider(k).apiKey.isNotBlank()) "  ✓" else "" }, { editing = it })
                        }
                        val ps = draft.provider(editing)
                        var showKey by remember(editing) { mutableStateOf(false) }
                        OutlinedTextField(
                            value = ps.apiKey, onValueChange = { draft = draft.withProvider(editing, ps.copy(apiKey = it.trim())) },
                            label = { Text(tr("settings.api_key")) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, tr("settings.show_hide"))
                                }
                            },
                        )
                        OutlinedTextField(
                            value = ps.baseUrl, onValueChange = { draft = draft.withProvider(editing, ps.copy(baseUrl = it)) },
                            label = { Text(tr("settings.base_url", editing.defaultBaseUrl)) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                        )
                        OutlinedTextField(
                            value = ps.model, onValueChange = { draft = draft.withProvider(editing, ps.copy(model = it)) },
                            label = { Text(tr("settings.model_id", editing.defaultModel)) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                            supportingText = { Text(tr("settings.suggestions", DesktopProviderFactory.suggestedModels(editing).take(6).joinToString(", "))) },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.image_input"), Modifier.width(140.dp))
                            Dropdown(
                                ps.imageInput, listOf(null, true, false),
                                { when (it) { null -> tr("settings.image.auto"); true -> tr("settings.image.on"); false -> tr("settings.image.off") } },
                                { draft = draft.withProvider(editing, ps.copy(imageInput = it)) },
                            )
                        }
                        Hint(tr("settings.keys_plain", state.paths.settingsFile.absolutePath))

                        Section(tr("settings.fallback"))
                        Hint(tr("settings.fallback.hint"))
                        val chain = draft.fallbackModels
                        Text(tr("settings.fallback.active", draft.primaryModel().label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        chain.forEachIndexed { i, ref ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}.  ${ref.label}" + if (draft.provider(ref.provider).apiKey.isBlank() && ref.provider != ProviderKind.OPENAI_COMPATIBLE) tr("settings.fallback.no_key") else "",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.toMutableList().also { l -> java.util.Collections.swap(l, i, i - 1) }) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, tr("common.move_up")) }
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.toMutableList().also { l -> java.util.Collections.swap(l, i, i + 1) }) }, enabled = i < chain.lastIndex) { Icon(Icons.Default.ArrowDownward, tr("common.move_down")) }
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Delete, tr("common.remove")) }
                            }
                        }
                        var addKind by remember { mutableStateOf(ProviderKind.OPENAI) }
                        var addModel by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dropdown(addKind, ProviderKind.entries, { it.label }, { addKind = it; addModel = "" })
                            OutlinedTextField(
                                value = addModel, onValueChange = { addModel = it }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus(),
                                label = { Text(tr("settings.model_id", draft.provider(addKind).effectiveModel(addKind))) },
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val ref = com.openminis.desktop.core.ModelRef(addKind, addModel.trim().ifBlank { draft.provider(addKind).effectiveModel(addKind) })
                                if (ref !in chain) draft = draft.copy(fallbackModels = chain + ref)
                                addModel = ""
                            }) { Text(tr("common.add")) }
                        }
                        Hint(tr("settings.suggestions", DesktopProviderFactory.suggestedModels(addKind).take(5).joinToString(", ")))
                        Toggle(tr("settings.fallback.any_error"), draft.fallbackOnAnyError) { draft = draft.copy(fallbackOnAnyError = it) }

                        Section(tr("settings.network"))
                        NetworkSection(draft.network) { draft = draft.copy(network = it) }

                        Section(tr("settings.context"))
                        Toggle(tr("settings.auto_compact"), draft.autoCompact) { draft = draft.copy(autoCompact = it) }
                        NumberField(tr("settings.ctx_override"), draft.contextWindowOverride, Modifier.fillMaxWidth()) {
                            draft = draft.copy(contextWindowOverride = it.coerceIn(0, 10_000_000))
                        }
                        NumberField(tr("settings.inline_chars"), draft.maxInlineTextChars, Modifier.fillMaxWidth()) {
                            draft = draft.copy(maxInlineTextChars = it.coerceIn(1000, 2_000_000))
                        }
                        Hint(tr("settings.manual_compact"))

                        Section(tr("settings.agent"))
                        OutlinedTextField(
                            value = draft.systemPrompt, onValueChange = { draft = draft.copy(systemPrompt = it) },
                            label = { Text(tr("settings.custom_instructions")) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).tabMovesFocus(), maxLines = 8,
                        )
                        Row {
                            NumberField(tr("settings.max_output"), draft.maxOutputTokens, Modifier.weight(1f)) { draft = draft.copy(maxOutputTokens = it.coerceIn(256, 200_000)) }
                            Spacer(Modifier.width(12.dp))
                            NumberField(tr("settings.max_rounds"), draft.maxToolRounds, Modifier.weight(1f)) { draft = draft.copy(maxToolRounds = it.coerceIn(1, 500)) }
                        }
                        AppearanceSection(draft) { draft = it }

                        Section(tr("settings.tools"))
                        Toggle(tr("settings.tools_enable"), draft.toolsEnabled) { draft = draft.copy(toolsEnabled = it) }
                        Toggle(tr("settings.confirm_shell"), draft.confirmShellCommands) { draft = draft.copy(confirmShellCommands = it) }
                        Toggle(tr("settings.readonly_auto"), draft.autoApproveReadOnlyShell) { draft = draft.copy(autoApproveReadOnlyShell = it) }
                        Hint(tr("settings.security_hint"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.shell"), Modifier.width(140.dp))
                            Dropdown(
                                draft.shell, ShellKind.entries,
                                { I18n.trOr("shell.${it.name}", it.label) + if (it == ShellKind.WSL && !ShellRunner.wslAvailable) tr("settings.not_detected") else "" },
                                { draft = draft.copy(shell = it) },
                            )
                        }
                        if (!AppPaths.isWindows) Hint(tr("settings.not_windows"))
                        NumberField(tr("settings.shell_timeout"), draft.shellTimeoutSeconds, Modifier.fillMaxWidth()) { draft = draft.copy(shellTimeoutSeconds = it.coerceIn(1, 1800)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft.workspaceDir, onValueChange = { draft = draft.copy(workspaceDir = it) },
                                label = { Text(tr("settings.workspace", AppPaths.defaultWorkspace().absolutePath)) },
                                singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus(),
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val chooser = JFileChooser(draft.workspace()).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) draft = draft.copy(workspaceDir = chooser.selectedFile.absolutePath)
                            }) { Text(tr("settings.browse")) }
                        }

                        Section(tr("settings.mcp"))
                        McpSection(state, mcpDraft) { mcpDraft = it }

                        Section(tr("settings.subagents"))
                        SubAgentSection(subDraft, draft, state.toolNames()) { subDraft = it }

                        Section(tr("settings.memory"))
                        Toggle(tr("settings.memory_enable"), draft.memoryEnabled) { draft = draft.copy(memoryEnabled = it) }
                        Row {
                            OutlinedButton(onClick = { state.openFile(state.memory.globalFile, "# GLOBAL memory\n\nFacts and preferences the agent should always know.\n") }) { Text(tr("settings.edit_global")) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { state.openInExplorer(state.memory.dir) }) { Text(tr("settings.open_memory")) }
                        }

                        Section(tr("settings.skills"))
                        Toggle(tr("settings.skills_enable"), draft.skillsEnabled) { draft = draft.copy(skillsEnabled = it) }
                        SkillsSection(state, draft.disabledSkills) { draft = draft.copy(disabledSkills = it) }

                        Section(tr("settings.data"))
                        Hint(tr("settings.app_data", state.paths.root.absolutePath))
                        OutlinedButton(onClick = { state.openInExplorer(state.paths.root) }) { Text(tr("menu.open_data")) }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text(tr("common.cancel_esc")) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { save() }) { Text(tr("settings.save")) }
                    }
                }
            }
        }
    }
}

/** Settings › Appearance: theme mode, accent, text size, UI font, code font, live preview. */
@Composable private fun AppearanceSection(d: com.openminis.desktop.core.AppSettings, onChange: (com.openminis.desktop.core.AppSettings) -> Unit) {
    val installed = remember {
        runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList() }.getOrDefault(emptyList())
    }
    val fontLabel: (String) -> String = { if (it.isBlank()) tr("appearance.font_default") else it }
    Section(tr("appearance.title"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("appearance.theme"), Modifier.width(140.dp))
        Dropdown(Appearance.ThemeMode.of(d.darkTheme), Appearance.ThemeMode.entries, { tr("appearance.theme.${it.name.lowercase()}") },
            { onChange(d.copy(darkTheme = it.dark)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(tr("appearance.accent"), Modifier.width(140.dp))
        val current = Appearance.Accent.of(d.accent)
        Appearance.Accent.entries.forEach { a ->
            val name = tr("appearance.accent.${a.id}")
            Box(
                Modifier.padding(end = 6.dp).size(26.dp)
                    .background(androidx.compose.ui.graphics.Color(a.light), androidx.compose.foundation.shape.CircleShape)
                    .border(if (a == current) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onChange(d.copy(accent = a.id)) }
                    .semantics { contentDescription = name; selected = a == current },
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(tr("appearance.accent.${current.id}"), style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("appearance.size"), Modifier.width(140.dp))
        val cur = Appearance.SCALES.minByOrNull { kotlin.math.abs(it - Appearance.clampScale(d.fontScale)) } ?: 1f
        Dropdown(cur, Appearance.SCALES, { "${Appearance.percent(it)} %" + if (it == 1f) tr("appearance.size_default") else "" },
            { onChange(d.copy(fontScale = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("appearance.ui_font"), Modifier.width(140.dp))
        Dropdown(d.uiFont, Appearance.fontChoices(installed, Appearance.UI_RECOMMENDED, d.uiFont), fontLabel, { onChange(d.copy(uiFont = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("appearance.mono_font"), Modifier.width(140.dp))
        Dropdown(d.monoFont, Appearance.fontChoices(installed, Appearance.MONO_RECOMMENDED, d.monoFont), fontLabel, { onChange(d.copy(monoFont = it)) })
    }
    // Preview in the draft's settings (the dialog itself keeps the saved ones until Save).
    MinisTheme(d) {
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(tr("appearance.preview_text"), style = MaterialTheme.typography.bodyMedium)
                Text(Appearance.MONO_PREVIEW, fontFamily = LocalMonoFont.current,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Hint(tr("appearance.hint"))
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

@Composable private fun NetworkSection(n: com.openminis.desktop.net.NetworkSettings, onChange: (com.openminis.desktop.net.NetworkSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("net.proxy"), Modifier.width(140.dp))
        Dropdown(n.proxyMode, com.openminis.desktop.net.ProxyMode.entries, { I18n.trOr("net.mode.${it.name}", it.label) }, { onChange(n.copy(proxyMode = it)) })
    }
    when (n.proxyMode) {
        com.openminis.desktop.net.ProxyMode.SYSTEM -> Hint(tr("net.hint.system"))
        com.openminis.desktop.net.ProxyMode.NONE -> Hint(tr("net.hint.none"))
        com.openminis.desktop.net.ProxyMode.MANUAL -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("net.type"), Modifier.width(140.dp))
                Dropdown(n.proxyType, com.openminis.desktop.net.ProxyType.entries, { if (it == com.openminis.desktop.net.ProxyType.HTTP) "HTTP / HTTPS (CONNECT)" else "SOCKS5" }, { onChange(n.copy(proxyType = it)) })
            }
            Row {
                OutlinedTextField(n.proxyHost, { onChange(n.copy(proxyHost = it.trim())) }, label = { Text(tr("net.host")) }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
                Spacer(Modifier.width(8.dp))
                NumberField(tr("net.port"), n.proxyPort, Modifier.width(120.dp)) { onChange(n.copy(proxyPort = it.coerceIn(0, 65535))) }
            }
            Row {
                OutlinedTextField(n.proxyUser, { onChange(n.copy(proxyUser = it)) }, label = { Text(tr("net.user")) }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(n.proxyPassword, { onChange(n.copy(proxyPassword = it)) }, label = { Text(tr("net.password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f).tabMovesFocus())
            }
            OutlinedTextField(n.bypass, { onChange(n.copy(bypass = it)) }, label = { Text(tr("net.bypass")) },
                singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
        }
    }
    // [hotfix2] no clamp per keystroke: clamping "20" → "2" → 3 while the user edits used to leave a
    // 3-second watchdog behind. The watchdog clamps (and treats < 3 as the 20 s default) when it arms.
    NumberField(tr("net.connect_timeout"), n.connectTimeoutSeconds, Modifier.fillMaxWidth()) { onChange(n.copy(connectTimeoutSeconds = it)) }
    Hint(tr("net.connect_timeout.hint"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("net.http"), Modifier.width(140.dp))
        Dropdown(n.httpProtocol, com.openminis.desktop.net.HttpProtocolMode.entries, { I18n.trOr("net.http.${it.name}", it.label) }, { onChange(n.copy(httpProtocol = it)) })
    }
    Hint(when (n.httpProtocol) {
        com.openminis.desktop.net.HttpProtocolMode.AUTO -> tr("net.http.hint.auto")
        com.openminis.desktop.net.HttpProtocolMode.HTTP1_ONLY -> tr("net.http.hint.h1")
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
