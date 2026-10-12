// OpenMinis Desktop — settings window (providers/API keys, agent, tools/shell, memory, skills).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.ui.icons.Lucide
import com.openminis.desktop.i18n.AppLanguage
import com.openminis.desktop.i18n.I18n
import com.openminis.desktop.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Settings pages (UI-REFRESH §3.4): rail group → section list → detail. */
enum class SettingsPage(val group: SettingsGroup, val key: String) {
    MODEL(SettingsGroup.GENERAL, "settings.page.model"),
    APPEARANCE(SettingsGroup.GENERAL, "settings.page.appearance"),
    NETWORK(SettingsGroup.GENERAL, "settings.page.network"),
    TOOLS(SettingsGroup.AGENT, "settings.page.tools"),
    MCP(SettingsGroup.AGENT, "settings.page.mcp"),
    SKILLS(SettingsGroup.AGENT, "settings.page.skills"),
    SUBAGENTS(SettingsGroup.AGENT, "settings.page.subagents"),
    MEMORY(SettingsGroup.AGENT, "settings.page.memory"),
    SCHEDULED(SettingsGroup.AGENT, "settings.page.scheduled"),
    REMOTE(SettingsGroup.SYSTEM, "settings.page.remote"),
    BACKUP(SettingsGroup.SYSTEM, "settings.page.backup"),
    ABOUT(SettingsGroup.SYSTEM, "settings.page.about"),
    ADVANCED(SettingsGroup.SYSTEM, "settings.page.advanced");
    companion object { fun of(g: SettingsGroup) = entries.filter { it.group == g } }
}

enum class SettingsGroup(val key: String) { GENERAL("settings.group.general"), AGENT("settings.group.agent"), SYSTEM("settings.group.system") }

private fun pageIcon(p: SettingsPage) = when (p) {
    SettingsPage.MODEL -> Lucide.Cpu
    SettingsPage.APPEARANCE -> Lucide.Palette
    SettingsPage.NETWORK -> Lucide.Globe
    SettingsPage.TOOLS -> Lucide.SquareTerminal
    SettingsPage.MCP -> Lucide.Puzzle
    SettingsPage.SKILLS -> Lucide.BookOpen
    SettingsPage.SUBAGENTS -> Lucide.Users
    SettingsPage.MEMORY -> Lucide.Brain
    SettingsPage.SCHEDULED -> Lucide.CalendarClock
    SettingsPage.REMOTE -> Lucide.Smartphone
    SettingsPage.BACKUP -> Lucide.Database
    SettingsPage.ABOUT -> Lucide.Info
    SettingsPage.ADVANCED -> Lucide.ShieldAlert
}

private fun groupIcon(g: SettingsGroup) = when (g) {
    SettingsGroup.GENERAL -> Lucide.Settings
    SettingsGroup.AGENT -> Lucide.Bot
    SettingsGroup.SYSTEM -> Lucide.Layers
}

@Composable
fun SettingsDialog(state: AppState, onClose: () -> Unit) {
    var draft by remember { mutableStateOf(state.settings) }
    var editing by remember { mutableStateOf(state.settings.activeProvider) }
    var subDraft by remember { mutableStateOf(state.subAgentConfig) }
    var mcpDraft by remember { mutableStateOf(state.mcp.servers) }
    var page by remember { mutableStateOf(state.settingsPage) }
    LaunchedEffect(page) { state.settingsPage = page }
    val save = { state.updateSettings(draft); state.updateSubAgentConfig(subDraft); if (mcpDraft != state.mcp.servers) state.updateMcpServers(mcpDraft); onClose() }
    // [immersive] undecorated + self-drawn title row; switching the mode recreates the window (AWT cannot change it live).
    val immersive = state.settings.immersiveWindow
    val chrome = rememberDialogChrome(immersive, onClose)
    key(immersive) {
    DialogWindow(
        onCloseRequest = onClose, title = tr("settings.title"), undecorated = immersive,
        state = rememberFittedDialogState(960.dp, 760.dp), resizable = true,
        // Esc closes without saving; Ctrl+S / Ctrl+Enter saves.
        onPreviewKeyEvent = { ev ->
            if (chromeKey(chrome, ev, TITLE_REGION_DIALOG)) true
            else if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
            else when {
                ev.key == androidx.compose.ui.input.key.Key.Escape -> { onClose(); true }
                ev.isCtrlPressed && (ev.key == androidx.compose.ui.input.key.Key.S || ev.key == androidx.compose.ui.input.key.Key.Enter) -> { save(); true }
                else -> false
            }
        },
    ) {
        AttachChrome(chrome, window)
        MinisTheme(state.settings) { ImmersiveFrame(chrome) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    DialogTitleBar(tr("settings.title"))
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        // Rail: groups.
                        Column(Modifier.width(64.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            SettingsGroup.entries.forEach { g ->
                                val sel = page.group == g
                                Column(
                                    Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) { if (!sel) page = SettingsPage.of(g).first() }
                                        .semantics { contentDescription = tr(g.key); selected = sel }
                                        .padding(horizontal = 6.dp, vertical = 8.dp).width(48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(groupIcon(g), null, Modifier.size(20.dp), tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(tr(g.key), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        // Section navigation within the group.
                        Column(Modifier.width(190.dp).fillMaxHeight().padding(10.dp)) {
                            Text(tr(page.group.key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
                            SettingsPage.of(page.group).forEach { p ->
                                val sel = p == page
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) { page = p }
                                        .semantics { selected = sel }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(pageIcon(p), null, Modifier.size(16.dp),
                                        tint = if (p == SettingsPage.ADVANCED) MaterialTheme.colorScheme.error else if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(10.dp))
                                    Text(tr(p.key), style = MaterialTheme.typography.bodyMedium, fontWeight = if (sel) FontWeight.SemiBold else null)
                                }
                            }
                        }
                        VerticalDivider()
                        // Detail.
                        ScrollColumn(Modifier.weight(1f).fillMaxHeight(), state = rememberScrollState().also { }) {
                            Text(tr(page.key), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            when (page) {
                                SettingsPage.MODEL -> {
                        Section(tr("settings.provider"))
                        Field(tr("settings.active_provider")) {
                            Dropdown(draft.activeProvider, ProviderKind.entries, { it.label }, { draft = draft.copy(activeProvider = it); editing = it })
                        }
                        Field(tr("settings.edit_for")) {
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
                                    Icon(if (showKey) Lucide.EyeOff else Lucide.Eye, tr("settings.show_hide"))
                                }
                            },
                        )
                        // P3-1: a stored key this Windows account cannot decrypt — say so, never show it as "no key".
                        val keyId = com.openminis.desktop.core.SettingsStore.providerKeyId(editing)
                        if (ps.apiKey.isEmpty() && state.unreadableSecrets.any { it.id == keyId })
                            Text(tr("secrets.unreadable_item", editing.label), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
                        OutlinedTextField(
                            value = ps.baseUrl, onValueChange = { draft = draft.withProvider(editing, ps.copy(baseUrl = it)) },
                            label = { Text(tr("settings.base_url", editing.defaultBaseUrl)) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                        )
                        OutlinedTextField(
                            value = ps.model, onValueChange = { draft = draft.withProvider(editing, ps.copy(model = it)) },
                            label = { Text(tr("settings.model_id", editing.defaultModel)) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus(),
                            supportingText = { Text(tr("settings.suggestions", DesktopProviderFactory.suggestedModels(editing).take(6).joinToString(", "))) },
                        )
                        Field(tr("settings.image_input")) {
                            Dropdown(
                                ps.imageInput, listOf(null, true, false),
                                { when (it) { null -> tr("settings.image.auto"); true -> tr("settings.image.on"); false -> tr("settings.image.off") } },
                                { draft = draft.withProvider(editing, ps.copy(imageInput = it)) },
                            )
                        }
                        if (state.secretsProtected) Hint(tr("settings.keys_dpapi", state.paths.secretsFile.absolutePath))
                        else Hint(tr("settings.keys_plain", state.paths.settingsFile.absolutePath))
                        if (state.unreadableSecrets.isNotEmpty()) {
                            Hint(tr("secrets.unreadable", state.unreadableSecrets.joinToString(tr("list_sep")) { state.secretLabel(it) }))
                            OutlinedButton(onClick = { state.forgetUnreadableSecrets() }) { Text(tr("secrets.forget")) }
                        }

                        Section(tr("settings.fallback"))
                        Hint(tr("settings.fallback.hint"))
                        val chain = draft.fallbackModels
                        Text(tr("settings.fallback.active", draft.primaryModel().label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        chain.forEachIndexed { i, ref ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}.  ${ref.label}" + if (draft.provider(ref.provider).apiKey.isBlank() && ref.provider != ProviderKind.OPENAI_COMPATIBLE) tr("settings.fallback.no_key") else "",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.toMutableList().also { l -> java.util.Collections.swap(l, i, i - 1) }) }, enabled = i > 0) { Icon(Lucide.ArrowUp, tr("common.move_up")) }
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.toMutableList().also { l -> java.util.Collections.swap(l, i, i + 1) }) }, enabled = i < chain.lastIndex) { Icon(Lucide.ArrowDown, tr("common.move_down")) }
                                IconButton(onClick = { draft = draft.copy(fallbackModels = chain.filterIndexed { j, _ -> j != i }) }) { Icon(Lucide.Trash, tr("common.remove")) }
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

                        Section(tr("settings.context"))
                        Toggle(tr("settings.auto_compact"), draft.autoCompact) { draft = draft.copy(autoCompact = it) }
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
                                }
                                SettingsPage.APPEARANCE -> {
                        Section(tr("settings.language"))
                        Field(tr("settings.language.label")) {
                            Dropdown(AppLanguage.of(draft.language), AppLanguage.entries,
                                { if (it == AppLanguage.SYSTEM) tr("settings.language.system", AppLanguage.of(I18n.resolve("system")).nativeName) else it.nativeName },
                                { draft = draft.copy(language = it.code) })
                        }
                        Hint(tr("settings.language.hint"))

                        AppearanceSection(draft) { draft = it }
                                }
                                SettingsPage.NETWORK -> {
                        NetworkSection(draft.network) { draft = draft.copy(network = it) }
                                }
                                SettingsPage.TOOLS -> {
                        Toggle(tr("settings.tools_enable"), draft.toolsEnabled) { draft = draft.copy(toolsEnabled = it) }
                        Field(tr("settings.shell")) {
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

                                }
                                SettingsPage.MCP -> McpSection(state, mcpDraft) { mcpDraft = it }
                                SettingsPage.SKILLS -> {
                        Toggle(tr("settings.skills_enable"), draft.skillsEnabled) { draft = draft.copy(skillsEnabled = it) }
                        SkillsSection(state, draft.disabledSkills) { draft = draft.copy(disabledSkills = it) }

                                }
                                SettingsPage.SUBAGENTS -> SubAgentSection(subDraft, draft, state.toolNames()) { subDraft = it }
                                SettingsPage.MEMORY -> {
                        Toggle(tr("settings.memory_enable"), draft.memoryEnabled) { draft = draft.copy(memoryEnabled = it) }
                        Row {
                            OutlinedButton(onClick = { state.openFile(state.memory.globalFile, "# GLOBAL memory\n\nFacts and preferences the agent should always know.\n") }) { Text(tr("settings.edit_global")) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { state.openInExplorer(state.memory.dir) }) { Text(tr("settings.open_memory")) }
                        }

                                }
                                SettingsPage.SCHEDULED -> {
                        Hint(tr("settings.scheduled.hint"))
                        OutlinedButton(onClick = { state.showScheduled = true }) {
                            Icon(Lucide.CalendarClock, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("settings.scheduled.open"))
                        }
                        HeadlessSchedulerSection(state, draft) { draft = it }
                                }
                                SettingsPage.REMOTE -> RemoteSection(state, draft.remoteMcp) { draft = draft.copy(remoteMcp = it) }
                                SettingsPage.BACKUP -> BackupSection(state, onRestored = onClose)
                                SettingsPage.ABOUT -> {
                        AboutPage()
                        UpdateCheckSection(state)
                        Section(tr("settings.data"))
                        Hint(tr("settings.app_data", state.paths.root.absolutePath))
                        OutlinedButton(onClick = { state.openInExplorer(state.paths.root) }) { Text(tr("menu.open_data")) }
                        OldBackupsSection(state)
                                }
                                SettingsPage.ADVANCED -> {
                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Lucide.TriangleAlert, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(tr("settings.advanced.warning"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        Section(tr("settings.advanced.approvals"))
                        Toggle(tr("settings.confirm_shell"), draft.confirmShellCommands) { draft = draft.copy(confirmShellCommands = it) }
                        Toggle(tr("settings.readonly_auto"), draft.autoApproveReadOnlyShell) { draft = draft.copy(autoApproveReadOnlyShell = it) }
                        Hint(tr("settings.security_hint"))
                        Section(tr("settings.advanced.context"))
                        NumberField(tr("settings.ctx_override"), draft.contextWindowOverride, Modifier.fillMaxWidth()) {
                            draft = draft.copy(contextWindowOverride = it.coerceIn(0, 10_000_000))
                        }
                        NumberField(tr("settings.inline_chars"), draft.maxInlineTextChars, Modifier.fillMaxWidth()) {
                            draft = draft.copy(maxInlineTextChars = it.coerceIn(1000, 2_000_000))
                        }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text(tr("common.cancel_esc")) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { save() }) { Text(tr("settings.save")) }
                    }
                }
            }
        } }
    }
    }
}

/** Settings › About: version, license, bundled third-party assets (UI-REFRESH §4). */
@Composable private fun AboutPage() {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("OpenMinis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(tr("about.version", com.openminis.app.BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Hint(tr("about.license"))
    Section(tr("about.third_party"))
    listOf(
        "Lucide icons — ISC License",
        "Inter — SIL Open Font License 1.1",
        "JetBrains Mono — SIL Open Font License 1.1",
        "multiplatform-markdown-renderer — Apache-2.0",
        "JLaTeXMath — GPL-2.0-or-later with classpath exception",
        "OkHttp, kotlinx — Apache-2.0 · org.json — JSON License (public domain) · jieba-analysis — Apache-2.0",
    ).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp)) }
    Hint(tr("about.third_party.hint"))
}
/** Settings › Appearance: theme mode, accent, text size, UI font, code font, live preview. */
@Composable private fun AppearanceSection(d: com.openminis.desktop.core.AppSettings, onChange: (com.openminis.desktop.core.AppSettings) -> Unit) {
    val installed = remember {
        runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList() }.getOrDefault(emptyList())
    }
    val uiLabel: (String) -> String = { when (it) { "" -> tr("appearance.font_bundled_ui"); Appearance.SYSTEM_FONT -> tr("appearance.font_system"); else -> it } }
    val monoLabel: (String) -> String = { when (it) { "" -> tr("appearance.font_bundled_mono"); Appearance.SYSTEM_FONT -> tr("appearance.font_system"); else -> it } }
    Section(tr("appearance.section.window"))
    Toggle(tr("appearance.immersive"), d.immersiveWindow) { onChange(d.copy(immersiveWindow = it)) }
    Hint(tr("appearance.immersive_hint"))
    Section(tr("appearance.section.content"))
    Toggle(tr("appearance.render_math"), d.renderMath) { onChange(d.copy(renderMath = it)) }
    Hint(tr("appearance.render_math_hint"))
    Section(tr("appearance.section.theme"))
    Field(tr("appearance.theme")) {
        Dropdown(Appearance.ThemeMode.of(d.darkTheme), Appearance.ThemeMode.entries, { tr("appearance.theme.${it.name.lowercase()}") },
            { onChange(d.copy(darkTheme = it.dark)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(tr("appearance.accent"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(110.dp))
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
    Field(tr("appearance.size")) {
        val cur = Appearance.SCALES.minByOrNull { kotlin.math.abs(it - Appearance.clampScale(d.fontScale)) } ?: 1f
        Dropdown(cur, Appearance.SCALES, { "${Appearance.percent(it)} %" + if (it == 1f) tr("appearance.size_default") else "" },
            { onChange(d.copy(fontScale = it)) })
    }
    Field(tr("appearance.ui_font")) {
        Dropdown(d.uiFont, Appearance.fontChoices(installed, Appearance.UI_RECOMMENDED, d.uiFont), uiLabel, { onChange(d.copy(uiFont = it)) })
    }
    Field(tr("appearance.mono_font")) {
        Dropdown(d.monoFont, Appearance.fontChoices(installed, Appearance.MONO_RECOMMENDED, d.monoFont), monoLabel, { onChange(d.copy(monoFont = it)) })
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

/** Vertical form field (UI-REFRESH §3.4): label → control → grey hint. */
@Composable internal fun Field(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        content()
        if (hint != null) Hint(hint)
    }
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
    Field(tr("net.proxy")) {
        Dropdown(n.proxyMode, com.openminis.desktop.net.ProxyMode.entries, { I18n.trOr("net.mode.${it.name}", it.label) }, { onChange(n.copy(proxyMode = it)) })
    }
    when (n.proxyMode) {
        com.openminis.desktop.net.ProxyMode.SYSTEM -> Hint(tr("net.hint.system"))
        com.openminis.desktop.net.ProxyMode.NONE -> Hint(tr("net.hint.none"))
        com.openminis.desktop.net.ProxyMode.MANUAL -> {
            Field(tr("net.type")) {
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
    Field(tr("net.http")) {
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
