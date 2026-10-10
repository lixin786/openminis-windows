// OpenMinis Desktop — UI state holder (desktop counterpart of the Android ChatViewModel/SessionList VM).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.core.DEFAULT_CONVERSATION_TITLE
import com.openminis.desktop.mcp.McpChatScope
import com.openminis.desktop.i18n.tr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import com.openminis.desktop.agent.AgentEngine
import com.openminis.desktop.agent.CompactOutcome
import com.openminis.desktop.agent.ContextReading
import com.openminis.desktop.agent.ConversationContextState
import com.openminis.desktop.agent.TurnListener
import com.openminis.desktop.core.Attachment
import com.openminis.desktop.core.AttachmentShaper
import com.openminis.desktop.core.AttachmentStore
import com.openminis.desktop.core.CompactMarker
import com.openminis.desktop.agent.SystemPrompt
import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ChatMessage
import com.openminis.desktop.core.ChatRole
import com.openminis.desktop.core.Conversation
import com.openminis.desktop.core.ConversationStore
import com.openminis.desktop.core.DesktopProviderFactory
import com.openminis.desktop.core.SettingsStore
import com.openminis.desktop.skills.SkillLoader
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

class PendingApproval(val request: ApprovalRequest, val answer: CompletableDeferred<Boolean>)

class AppState(val paths: AppPaths, private val scope: CoroutineScope) {
    private val settingsStore = SettingsStore(paths.settingsFile)
    private val store = ConversationStore(paths.conversationsDir)
    val memory = MemoryStore(paths.memoryDir)
    val skills = SkillLoader { skillRoots() }
    fun skillRoots(): List<File> = listOf(paths.skillsDir, File(settings.workspace(), ".skills"))
    /** [P1-3] Imports go to %APPDATA%\OpenMinis\skills; downloads use the app's proxy settings. */
    val skillImporter = com.openminis.desktop.skills.SkillImporter({ paths.skillsDir.also { it.mkdirs() } }, { com.openminis.desktop.net.DesktopHttp.newClient(30) })

    var settings by mutableStateOf(settingsStore.load())
        private set

    /** [batch13] Phone link: read-only MCP server on private LAN addresses (MCP-SERVER.md). */
    val remote = com.openminis.desktop.remote.RemoteMcpController(paths.root, com.openminis.app.BuildConfig.VERSION_NAME) { remoteTools }
    /** 13.2: the five read-only tools the phone may call (workspace only, sensitive files denied by default). */
    private val remoteTools: com.openminis.desktop.remote.RemoteToolSet by lazy {
        com.openminis.desktop.remote.RemoteReadTools(
            workspace = { settings.workspace() },
            memoryEnabled = { settings.memoryEnabled },
            memory = { memory },
            denySensitive = { settings.remoteMcp.denySensitive },
            appVersion = com.openminis.app.BuildConfig.VERSION_NAME,
            shells = {
                buildList {
                    if (com.openminis.desktop.core.AppPaths.isWindows) { add("PowerShell"); add("cmd") } else add("bash")
                    if (com.openminis.desktop.tools.ShellRunner.wslAvailable) add("WSL")
                }
            },
            // Names, state and tool counts only: command lines, args and env may hold secrets.
            mcpServers = { mcp.servers.map { com.openminis.desktop.remote.McpServerSummary(it.name, mcp.status(it.name).state, mcp.status(it.name).toolCount) } },
        )
    }
    var remoteTick by mutableStateOf(0)
        private set

    val conversations = mutableStateListOf<Conversation>()
    var selectedId by mutableStateOf<String?>(null)
        private set
    var runningConversationId by mutableStateOf<String?>(null)
        private set
    /** The assistant message currently being streamed (not yet in the conversation). */
    var streaming by mutableStateOf<ChatMessage?>(null)
        private set
    var pendingApproval by mutableStateOf<PendingApproval?>(null)
        private set
    var showSettings by mutableStateOf(false)
    var composerFocusTick by mutableStateOf(0)
        private set
    /** Text to put into the composer once (e.g. "ask again" on a declined tool card); the composer clears it. */
    var composerPrefill by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    /** Transient line under the chat ("Compacting context…", "Rate limited — retrying (1/3)…"). */
    var statusText by mutableStateOf<String?>(null)
        private set
    var compactingConversationId by mutableStateOf<String?>(null)
        private set
    /** Files waiting in the composer, per conversation. */
    val pendingAttachments = mutableStateMapOf<String, List<Attachment>>()
    /** Latest context measurement per conversation (not persisted). */
    val contextReadings = mutableStateMapOf<String, ContextReading>()
    /** True while files are dragged over the window. */
    var dragHover by mutableStateOf(false)

    val attachmentStore = AttachmentStore(paths.root)
    private val shaper = AttachmentShaper(attachmentStore) { settings.maxInlineTextChars }

    private var job: Job? = null

    /** Set by the main window while it is shown; approvals without a window are denied (fail-closed). */
    @Volatile var windowAvailable: Boolean = false
    private val approvalLock = kotlinx.coroutines.sync.Mutex()

    /**
     * One dialog at a time (parallel requests queue). Fail-closed (security/FailClosedApprover): no
     * window, a dialog error or no answer within 10 minutes = deny — main chat, sub agents and
     * scheduled runs alike.
     */
    private val approver: ToolApprover = com.openminis.desktop.security.FailClosedApprover(
        ToolApprover { req ->
            approvalLock.withLock {
                val d = CompletableDeferred<Boolean>()
                withContext(Dispatchers.Main) { pendingApproval = PendingApproval(req, d) }
                try { d.await() } finally { withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { pendingApproval = null } }
            }
        },
        timeoutMs = com.openminis.desktop.security.FailClosedApprover.UI_TIMEOUT_MS,
        available = { windowAvailable && !java.awt.GraphicsEnvironment.isHeadless() },
    )
    /** Read-only extra roots of the file tools: skills and attachments (the model gets those host paths). */
    private fun readRoots(): List<File> = listOf(paths.skillsDir, paths.attachmentsDir)
    private val tools = ToolRegistry({ settings }, memory, approver, ::readRoots)

    // [P1-2] Sub agents: roster in subagents.json; the main loop's toolbox gains `subagent_task`.
    private val subAgentStore = com.openminis.desktop.subagents.SubAgentStore(File(paths.root, "subagents.json"))
    var subAgentConfig by mutableStateOf(subAgentStore.load())
        private set
    /** Sub agent runs of this app session (newest last), for the chat status block. */
    val subAgentRuns = mutableStateListOf<com.openminis.desktop.subagents.SubAgentRun>()
    val subAgents = com.openminis.desktop.subagents.SubAgentManager(
        providerFactory = { s, ref -> DesktopProviderFactory.create(s, ref) },
        config = { subAgentConfig },
        // A child's shell / file approvals go through the same dialog as the parent's (labelled with the agent).
        childRegistry = { appr -> ToolRegistry({ settings }, memory, appr, ::readRoots) },
        approver = approver,
        childPromptBase = { s -> SystemPrompt.build(s, memory, skills) },
        onProgress = { r ->
            scope.launch(Dispatchers.Main) {
                val i = subAgentRuns.indexOfFirst { it.jobId == r.jobId }
                if (i >= 0) subAgentRuns[i] = r else { subAgentRuns.add(r); while (subAgentRuns.size > 50) subAgentRuns.removeAt(0) }
            }
        },
    )
    // [P1-1] MCP stdio servers (mcp_servers.json); their tools join the main loop's toolbox.
    val mcp = com.openminis.desktop.mcp.McpManager(
        com.openminis.desktop.mcp.McpStore(File(paths.root, "mcp_servers.json"), File(paths.root, "mcp_trust.json")),
        approver, { settings.workspace() },
    )
    /** A light one-line hint shown under a conversation until its next turn (e.g. MCP servers skipped). */
    val chatNotices = mutableStateMapOf<String, String?>()
    /** Bumped whenever an MCP server's status changes (settings list recomposes). */
    var mcpTick by mutableStateOf(0)
        private set
    /** MCP servers switched off for the chat the main loop is running (set at turn start; one main turn at a time). */
    @Volatile private var turnMcpOff: Set<String> = emptySet()
    // Lazy: the toolbox needs the scheduler, which is declared further down.
    private val engine by lazy {
        AgentEngine(
            providerFactory = { s, ref -> DesktopProviderFactory.create(s, ref) },
            tools = com.openminis.desktop.scheduled.ScheduledToolbox(
                subAgents.toolbox(com.openminis.desktop.mcp.McpToolbox(com.openminis.desktop.agent.RegistryToolbox(tools), mcp) { turnMcpOff }),
                scheduler, approver, currentConversation = { runningConversationId },
                describe = { t -> describeTrigger(t, lang = "en") { id -> scheduler.list().firstOrNull { it.id == id }?.label ?: id } },
                describeUi = { t -> describeTrigger(t) { id -> scheduler.list().firstOrNull { it.id == id }?.label ?: id } },
            ),
            shaper = shaper,
        )
    }

    /** Tool names a sub agent can be restricted to (for the settings editor). */
    fun toolNames(): List<String> = tools.definitions().map { it.name } + com.openminis.desktop.subagents.SubAgentProtocol.TOOL_NAME

    internal fun mcpDeniedNotice(denied: List<String>): String? = when {
        denied.isEmpty() -> null
        else -> tr("notice.mcp_denied", denied.joinToString(tr("list_sep")) { tr("quote", it) })
    }

    fun updateMcpServers(list: List<com.openminis.desktop.mcp.McpServerConfig>) = scope.launch(Dispatchers.IO) { mcp.setServers(list) }
    fun connectMcp(sv: com.openminis.desktop.mcp.McpServerConfig) = scope.launch(Dispatchers.IO) { mcp.connect(sv) }
    fun shutdown() { runCatching { scheduler.stop() }; runCatching { mcp.shutdown() }; runCatching { remote.shutdown() } }

    // ------------------------------------------------------------ scheduled tasks (P1-4)

    /** Scheduled runs in progress: conversation id → task label (the chat shows it, the composer waits). */
    val scheduledRunning = mutableStateMapOf<String, String>()
    /** Partial reply of a scheduled run, per conversation (shown like a live reply when that chat is open). */
    val scheduledStreaming = mutableStateMapOf<String, ChatMessage>()
    var showScheduled by mutableStateOf(false)
    /** Settings window page (kept between openings; "open settings" buttons can jump to a page). */
    var settingsPage by mutableStateOf(SettingsPage.MODEL)
    fun openSettings(page: SettingsPage? = null) { page?.let { settingsPage = it }; showSettings = true }
    /** Toolbar layout toggle: hide / show the conversation list. */
    var sidebarCollapsed by mutableStateOf(false)

    // ---- unread (in memory): a conversation updated after it was last on screen ----
    private val appStartedAt = System.currentTimeMillis()
    private val seenAt = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    fun isUnread(c: Conversation): Boolean = c.id != selectedId && c.updatedAt > (seenAt[c.id] ?: appStartedAt)
    fun markSeen(c: Conversation) { if ((seenAt[c.id] ?: 0L) < c.updatedAt) seenAt[c.id] = c.updatedAt }

    /** Toolbar › Export: the selected conversation as Markdown (save dialog). */
    fun exportSelected() {
        val c = selected ?: return
        if (c.messages.isEmpty()) return
        val title = c.displayTitle()
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = com.openminis.desktop.i18n.I18n.tr("export.title")
            selectedFile = java.io.File(title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".md")
        }
        if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val f = chooser.selectedFile.let { if (it.name.contains('.')) it else java.io.File(it.path + ".md") }
        val md = ShellModel.markdown(c, title, com.openminis.desktop.i18n.I18n.tr("export.user"), com.openminis.desktop.i18n.I18n.tr("export.assistant"))
        runCatching { f.writeText(md, Charsets.UTF_8) }
            .onSuccess { toast = com.openminis.desktop.i18n.I18n.tr("export.done", f.absolutePath) }
            .onFailure { toast = com.openminis.desktop.i18n.I18n.tr("export.failed", it.message ?: it.javaClass.simpleName) }
    }
    /** Bumped when a task or its run records change (the Scheduled tasks window recomposes). */
    var scheduledTick by mutableStateOf(0)
        private set
    val scheduler = com.openminis.desktop.scheduled.DesktopScheduler(
        com.openminis.desktop.scheduled.ScheduledStore(File(paths.root, "scheduled_tasks.json")), scope,
        runner = { t, prompt -> runScheduled(t, prompt) },
    )

    /** Desktop meaning of upstream `modelId`: "<PROVIDER>/<model>" (null = the app's active model). */
    internal fun applyTaskModel(base: AppSettings, t: com.openminis.app.scheduled.ScheduledTask): AppSettings {
        var s = base
        t.modelId?.let { id ->
            val kind = com.openminis.desktop.core.ProviderKind.entries.firstOrNull { id.startsWith(it.name + "/") }
            if (kind != null) s = s.withProvider(kind, s.provider(kind).copy(model = id.substringAfter('/'))).copy(activeProvider = kind)
        }
        t.thinkingLevel?.let { s = s.copy(thinkingLevel = it.name) }
        return s
    }

    /** One scheduled fire: a new chat or a follow-up in an existing chat, run without the user at the keyboard. */
    private suspend fun runScheduled(t: com.openminis.app.scheduled.ScheduledTask, prompt: String): com.openminis.desktop.scheduled.ScheduledOutcome {
        val target = t.targetMode
        val convId: String = when (target) {
            is com.openminis.app.scheduled.ScheduledTargetMode.NewSession -> withContext(Dispatchers.Main) {
                val c = Conversation(title = "⏰ " + t.label.ifBlank { titleFrom(prompt) })
                conversations.add(0, c); c.id
            }
            is com.openminis.app.scheduled.ScheduledTargetMode.AppendToSession -> target.sessionId
            else -> return com.openminis.desktop.scheduled.ScheduledOutcome(null, "This target (${target.encode().substringBefore(':')}) is not supported on desktop yet.", ok = false)
        }
        // Never write into a chat while the user's own turn (or another scheduled run) is running in it.
        while (runningConversationId == convId || compactingConversationId == convId || scheduledRunning.containsKey(convId)) kotlinx.coroutines.delay(500)
        val ok = withContext(Dispatchers.Main) {
            if (conversations.none { it.id == convId }) return@withContext false
            scheduledRunning[convId] = t.label.ifBlank { tr("scheduled.default_label") }
            scheduledTaskOfConv[convId] = t.id
            val s0 = settings
            mutate(convId, persist = true) {
                it.copy(messages = it.messages + ChatMessage(role = ChatRole.USER, text = prompt), updatedAt = System.currentTimeMillis(),
                    provider = s0.activeProvider, model = s0.provider().effectiveModel(s0.activeProvider))
            }
            conversations.indexOfFirst { it.id == convId }.takeIf { it > 0 }?.let { conversations.add(0, conversations.removeAt(it)) }
            true
        }
        if (!ok) return com.openminis.desktop.scheduled.ScheduledOutcome(null, "The target chat was deleted.", ok = false)
        val label = t.label.ifBlank { tr("scheduled.default_label") }
        val labelled = ToolApprover { req -> approver.approve(req.copy(summary = tr("scheduled.approval_prefix", label, req.summary))) }
        val runOff = withContext(Dispatchers.Main) { conversations.firstOrNull { it.id == convId }?.mcpOff.orEmpty().toSet() }
        val runEngine = AgentEngine(
            providerFactory = { st, ref -> DesktopProviderFactory.create(st, ref) },
            tools = subAgents.toolbox(com.openminis.desktop.mcp.McpToolbox(com.openminis.desktop.agent.RegistryToolbox(ToolRegistry({ settings }, memory, labelled, ::readRoots)), mcp) { runOff }),
            shaper = shaper,
        )
        val conv = withContext(Dispatchers.Main) { conversations.first { it.id == convId } }
        val ctx = contextStateFor(conv)
        val s = applyTaskModel(settings, t)
        val latest = java.util.concurrent.atomic.AtomicReference<ChatMessage?>(null)
        var final: ChatMessage? = null
        try {
            if (s.toolsEnabled && mcp.servers.any { it.enabled && it.name !in runOff }) withContext(Dispatchers.IO) { mcp.prepare(runOff) }
            val promptText = withContext(Dispatchers.IO) { mainPrompt(s, runOff) }
            final = withContext(Dispatchers.IO) {
                runEngine.runTurn(conv.messages, s, promptText, ctx, object : TurnListener {
                    override fun onUpdate(message: ChatMessage) { latest.set(message); scope.launch(Dispatchers.Main) { scheduledStreaming[convId] = message } }
                })
            }
        } finally {
            val msg = final ?: latest.get()
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                mutate(convId, persist = true) {
                    it.copy(messages = if (msg != null) it.messages + msg else it.messages, updatedAt = System.currentTimeMillis(),
                        compactMarkers = ctx.markers.toList(), contextRatios = ctx.ratios.toMap())
                }
                scheduledStreaming.remove(convId); scheduledRunning.remove(convId); scheduledTaskOfConv.remove(convId)
            }
        }
        val text = final?.let { com.openminis.desktop.subagents.SubAgentProtocol.finalText(it) }.orEmpty()
        val err = final?.error
        return com.openminis.desktop.scheduled.ScheduledOutcome(convId, err?.let { "Error: $it" } ?: text.ifBlank { "(no reply)" }, ok = err == null, result = text)
    }

    /** Stop a running scheduled task (its chat keeps what was produced). */
    fun stopScheduled(convId: String) { scheduledTaskOfConv[convId]?.let(scheduler::cancelRun) }
    private val scheduledTaskOfConv = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun updateSubAgentConfig(c: com.openminis.desktop.subagents.SubAgentConfig) {
        subAgentConfig = c.normalized()
        scope.launch(Dispatchers.IO) { subAgentStore.save(subAgentConfig) }
    }

    /** Main-chat system prompt: base + the sub agent roster when delegation is available. */
    private fun mainPrompt(s: AppSettings, off: Set<String> = emptySet()): String {
        val base = SystemPrompt.build(s, memory, skills)
        val roster = if (s.toolsEnabled) subAgents.promptSection(0) else ""
        val mcpPart = if (s.toolsEnabled) mcpPromptSection(off) else ""
        return listOf(base, roster, mcpPart).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Upstream MCPRepository.mcpPromptFragment, desktop wording: the tools are native `mcp__…` tools. */
    private fun mcpPromptSection(off: Set<String>): String {
        val running = mcp.servers.filter { it.enabled && it.name !in off && mcp.tools(it.name).isNotEmpty() }.sortedByDescending { it.createdAt }.take(com.openminis.desktop.mcp.McpDefaults.MAX_SERVERS)
        if (running.isEmpty()) return ""
        return buildString {
            appendLine("Available MCP Servers (their tools are offered to you as mcp__<server>__<tool>):")
            running.forEach { sv ->
                append("- ").append(sv.name)
                val note = sv.note.let { if (it.length > com.openminis.desktop.mcp.McpDefaults.MAX_NOTE_LENGTH) it.take(com.openminis.desktop.mcp.McpDefaults.MAX_NOTE_LENGTH) + "…" else it }
                if (note.isNotBlank()) append(": ").append(note)
                appendLine()
            }
        }.trim()
    }

    init {
        mcp.onChange = { scope.launch(Dispatchers.Main) { mcpTick++ } }
        scheduler.onChange = { scope.launch(Dispatchers.Main) { scheduledTick++ } }
        remote.onChange = { scope.launch(Dispatchers.Main) { remoteTick++ } }
        if (settings.remoteMcp.enabled) scope.launch(Dispatchers.IO) { remote.apply(settings.remoteMcp) }
        scheduler.start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { mcp.shutdown() }; runCatching { remote.shutdown() } })
        com.openminis.desktop.net.DesktopHttp.update(settings.network)
        conversations.addAll(store.loadAll())
        newChat()
        // Warm the Chinese segmenter (dictionary load ~1 s) off the UI thread.
        scope.launch(Dispatchers.IO) { runCatching { com.openminis.desktop.tools.MemoryTokenizer.tokenize("预热 warmup") } }
    }

    val selected: Conversation? get() = conversations.firstOrNull { it.id == selectedId }
    val isRunning: Boolean get() = runningConversationId != null

    /** Ctrl+= / Ctrl+- / Ctrl+0: text size one step up / down / back to 100 %. */
    fun zoomText(dir: Int) {
        val next = Appearance.step(settings.fontScale, dir)
        if (next != settings.fontScale) updateSettings(settings.copy(fontScale = next))
    }

    fun updateSettings(s: AppSettings) {
        if (s.language != settings.language) com.openminis.desktop.i18n.I18n.apply(s.language)
        if (s.network != settings.network) com.openminis.desktop.net.DesktopHttp.update(s.network)
        if (s.remoteMcp != settings.remoteMcp) scope.launch(Dispatchers.IO) { remote.apply(s.remoteMcp) }
        settings = s
        com.openminis.app.data.FastModePrefs.setEnabled(false)
        scope.launch(Dispatchers.IO) { settingsStore.save(s) }
    }

    fun newChat() {
        // Reuse an existing empty draft instead of piling up "New chat" entries.
        conversations.firstOrNull { it.messages.isEmpty() }?.let { selectedId = it.id; composerFocusTick++; return }
        val c = Conversation()
        conversations.add(0, c)
        selectedId = c.id
        composerFocusTick++
    }

    fun select(id: String) { selectedId = id; composerFocusTick++; refreshContextReading(id) }

    fun delete(id: String) {
        if (runningConversationId == id) stop()
        if (renamingId == id) renamingId = null
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx >= 0) conversations.removeAt(idx)
        pendingAttachments.remove(id)
        scope.launch(Dispatchers.IO) { store.delete(id); attachmentStore.deleteConversation(id) }
        if (selectedId == id) {
            selectedId = com.openminis.desktop.core.ConversationSearch.arrange(conversations).firstOrNull()?.id
            if (selectedId == null) newChat()
        }
    }

    /** Chat whose sidebar row shows the inline rename field (F2, double-click, or the row menu). */
    var renamingId by mutableStateOf<String?>(null)
    /** Bumped to move focus into the sidebar search field (Ctrl+F). */
    var searchFocusTick by mutableStateOf(0)

    /** Manual rename: cleaned, capped, and locked so the first message does not overwrite it. Blank input keeps the old title. */
    fun rename(id: String, title: String) {
        renamingId = null
        val clean = com.openminis.desktop.core.ConversationSearch.cleanTitle(title) ?: return
        mutate(id, persist = true) { if (it.title == clean && it.titleLocked) it else it.copy(title = clean, titleLocked = true) }
    }

    /** Switch one MCP server on/off for one chat; takes effect from the chat's next turn. */
    fun setChatMcp(convId: String, server: String, on: Boolean) = mutate(convId, persist = true) {
        it.copy(mcpOff = McpChatScope.withServer(it.mcpOff, server, on))
    }

    fun togglePin(id: String) = mutate(id, persist = true) { it.copy(pinned = !it.pinned) }

    private fun mutate(id: String, persist: Boolean, f: (Conversation) -> Conversation) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        val updated = f(conversations[idx])
        conversations[idx] = updated
        if (persist && updated.messages.isNotEmpty()) scope.launch(Dispatchers.IO) { store.save(updated) }
    }

    // ------------------------------------------------------------ attachments

    fun pending(convId: String? = selectedId): List<Attachment> = convId?.let { pendingAttachments[it] }.orEmpty()

    /** Import dropped / chosen / pasted files into the selected chat's composer. */
    fun attachFiles(files: List<File>) {
        val conv = selected ?: return
        scope.launch(Dispatchers.IO) {
            val added = mutableListOf<Attachment>()
            val errors = mutableListOf<String>()
            for (f in files) {
                when {
                    f.isDirectory -> errors += tr("attach.err.folder", f.name)
                    else -> runCatching { attachmentStore.importFile(conv.id, f) }.onSuccess { added += it }.onFailure { errors += "${f.name}: ${it.message}" }
                }
            }
            withContext(Dispatchers.Main) {
                if (added.isNotEmpty()) pendingAttachments[conv.id] = pending(conv.id) + added
                if (errors.isNotEmpty()) toast = tr("toast.not_attached", errors.joinToString("\n"))
                composerFocusTick++
            }
        }
    }

    fun attachImage(image: java.awt.Image) {
        val conv = selected ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { attachmentStore.importImage(conv.id, image) }
                .onSuccess { a -> withContext(Dispatchers.Main) { pendingAttachments[conv.id] = pending(conv.id) + a } }
                .onFailure { e -> withContext(Dispatchers.Main) { toast = tr("toast.paste_failed", e.message) } }
        }
    }

    fun removePending(a: Attachment) {
        val convId = selectedId ?: return
        pendingAttachments[convId] = pending(convId).filterNot { it.id == a.id }
        scope.launch(Dispatchers.IO) { attachmentStore.delete(a) }
    }

    fun attachmentFile(a: Attachment): File = attachmentStore.fileOf(a)

    // ------------------------------------------------------------ turns

    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val conv = selected ?: return false
        if (trimmed == "/compact") { compactNow(); return true }
        val atts = pending(conv.id)
        if ((trimmed.isEmpty() && atts.isEmpty()) || isRunning) return false
        if (scheduledRunning.containsKey(conv.id)) { toast = tr("toast.scheduled_busy"); return false }
        val s = settings
        val user = ChatMessage(role = ChatRole.USER, text = trimmed, attachments = atts)
        val title = titleOnSend(conv, trimmed.ifEmpty { atts.first().fileName })
        pendingAttachments.remove(conv.id)
        mutate(conv.id, persist = true) {
            it.copy(
                messages = it.messages + user, title = title, updatedAt = System.currentTimeMillis(),
                provider = s.activeProvider, model = s.provider().effectiveModel(s.activeProvider),
            )
        }
        // Move to top of the list.
        conversations.indexOfFirst { it.id == conv.id }.takeIf { it > 0 }?.let { conversations.add(0, conversations.removeAt(it)) }
        startTurn(conv.id)
        return true
    }

    private fun contextStateFor(c: Conversation) = ConversationContextState(c.compactMarkers, c.contextRatios)

    private fun startTurn(convId: String) {
        val s = settings
        val conv = conversations.firstOrNull { it.id == convId } ?: return
        runningConversationId = convId
        streaming = null
        val history = conv.messages
        val off = conv.mcpOff.toSet()
        turnMcpOff = off
        val ctx = contextStateFor(conv)
        val latest = java.util.concurrent.atomic.AtomicReference<ChatMessage?>(null)
        val listener = object : TurnListener {
            override fun onUpdate(message: ChatMessage) {
                latest.set(message)
                scope.launch(Dispatchers.Main) { if (runningConversationId == convId) streaming = message }
            }
            override fun onCompacted(marker: CompactMarker) {
                scope.launch(Dispatchers.Main) { mutate(convId, persist = true) { it.copy(compactMarkers = ctx.markers.toList()) } }
            }
            override fun onContext(reading: ContextReading) { scope.launch(Dispatchers.Main) { contextReadings[convId] = reading } }
            override fun onStatus(text: String?) { scope.launch(Dispatchers.Main) { if (runningConversationId == convId) statusText = text } }
        }
        job = scope.launch {
            var final: ChatMessage? = null
            try {
                if (s.toolsEnabled && mcp.servers.any { it.enabled && it.name !in off }) {
                    val r = withContext(Dispatchers.IO) { mcp.prepare(off) }
                    withContext(Dispatchers.Main) {
                        if (r.problems.isNotEmpty()) toast = r.problems.joinToString("\n")
                        chatNotices[convId] = mcpDeniedNotice(r.denied)
                    }
                }
                val prompt = withContext(Dispatchers.IO) { mainPrompt(s, off) }
                final = withContext(Dispatchers.IO) { engine.runTurn(history, s, prompt, ctx, listener) }
            } catch (e: CancellationException) {
                final = latest.get()
            } finally {
                val msg = final ?: latest.get()
                mutate(convId, persist = true) {
                    it.copy(
                        messages = if (msg != null) it.messages + msg else it.messages,
                        updatedAt = System.currentTimeMillis(),
                        compactMarkers = ctx.markers.toList(), contextRatios = ctx.ratios.toMap(),
                    )
                }
                streaming = null
                statusText = null
                runningConversationId = null
                job = null
            }
        }
    }

    /** Manual compaction of the selected chat (toolbar button, Chat menu, `/compact`). */
    fun compactNow() {
        val conv = selected ?: return
        if (isRunning || compactingConversationId != null) { toast = tr("toast.compact_wait"); return }
        if (conv.messages.isEmpty()) { toast = tr("toast.compact_empty"); return }
        val s = settings
        val ctx = contextStateFor(conv)
        compactingConversationId = conv.id
        statusText = tr("chat.compacting")
        job = scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    engine.compact(conv.messages, s, ctx, object : TurnListener {
                        override fun onStatus(text: String?) { scope.launch(Dispatchers.Main) { statusText = text ?: tr("chat.compacting") } }
                    })
                }
                when (outcome) {
                    is CompactOutcome.Done -> mutate(conv.id, persist = true) { it.copy(compactMarkers = ctx.markers.toList()) }
                    is CompactOutcome.Skipped -> toast = outcome.reason
                    is CompactOutcome.Failed -> toast = tr("toast.compact_failed", outcome.reason)
                }
            } catch (_: CancellationException) {
            } finally {
                compactingConversationId = null
                statusText = null
                job = null
                refreshContextReading(conv.id)
            }
        }
    }

    /** Undo the latest compaction (upstream revertCompact): the full history is sent again. */
    fun revertCompact(convId: String) {
        mutate(convId, persist = true) { it.copy(compactMarkers = it.compactMarkers.dropLast(1)) }
        refreshContextReading(convId)
    }

    fun refreshContextReading(convId: String) {
        val conv = conversations.firstOrNull { it.id == convId } ?: return
        val s = settings
        scope.launch(Dispatchers.IO) {
            val r = runCatching { engine.measure(conv.messages, s, mainPrompt(s, conv.mcpOff.toSet()), contextStateFor(conv)) }.getOrNull()
            withContext(Dispatchers.Main) { if (r != null) contextReadings[convId] = r else contextReadings.remove(convId) }
        }
    }

    fun stop() {
        job?.cancel()
        if (compactingConversationId != null) statusText = null
        pendingApproval?.answer?.complete(false)
    }

    /** Declined tool card › Ask again: a ready-to-send request in the composer (the user can still edit it). */
    fun requestRetry(tc: com.openminis.desktop.core.ToolCall) {
        composerPrefill = com.openminis.desktop.i18n.I18n.tr("tool.retry_prompt", ToolPresentation.displayName(tc.name), toolSummary(tc).take(300))
    }

    fun answerApproval(allow: Boolean, dontAskAgain: Boolean = false) {
        val p = pendingApproval ?: return
        // [security] Only what the request itself offers is remembered (MCP launch fingerprint, an exact
        // shell command for this session). A shell approval never switches confirmation off any more.
        if (allow && dontAskAgain) p.request.onAlwaysAllow?.let { runCatching(it) }
        p.answer.complete(allow)
    }

    /** Retry the last user message (drops the last assistant reply, keeps its attachments). */
    fun regenerate() {
        val conv = selected ?: return
        if (isRunning) return
        val lastUser = conv.messages.indexOfLast { it.role == ChatRole.USER }
        if (lastUser < 0) return
        mutate(conv.id, persist = true) { c ->
            val kept = c.messages.subList(0, lastUser + 1).toList()
            val alive = kept.map { it.id }.toSet()
            // A marker anchored inside a dropped reply can no longer resolve: drop it too.
            c.copy(messages = kept, compactMarkers = c.compactMarkers.filter { it.lastCompactedMessageId.substringBefore('#') in alive })
        }
        startTurn(conv.id)
    }

    /** Edit a user message and run the turn again from there; everything after it is dropped. */
    fun editAndResend(messageId: String, newText: String): Boolean {
        val conv = selected ?: return false
        if (isRunning || scheduledRunning.containsKey(conv.id)) return false
        val edited = editedConversation(conv, messageId, newText) ?: return false
        mutate(conv.id, persist = true) { edited.copy(updatedAt = System.currentTimeMillis()) }
        conversations.indexOfFirst { it.id == conv.id }.takeIf { it > 0 }?.let { conversations.add(0, conversations.removeAt(it)) }
        startTurn(conv.id)
        return true
    }

    fun skillCount(): Int = runCatching { skills.load().size }.getOrDefault(0)

    fun openInExplorer(f: File) {
        scope.launch(Dispatchers.IO) {
            f.mkdirs()
            runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure {
                withContext(Dispatchers.Main) { toast = tr("toast.cannot_open", f.absolutePath, it.message) }
            }
        }
    }

    fun openFile(f: File, template: String = "") {
        scope.launch(Dispatchers.IO) {
            if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(template) }
            runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure {
                withContext(Dispatchers.Main) { toast = tr("toast.cannot_open", f.absolutePath, it.message) }
            }
        }
    }

    companion object {
        /**
         * [c] with user message [messageId] replaced by [newText] (attachments kept, fresh id) and every later message
         * dropped, plus compaction markers anchored in the dropped part. Null when the id is not a user message or the
         * edit would leave an empty message.
         */
        fun editedConversation(c: Conversation, messageId: String, newText: String): Conversation? {
            val idx = c.messages.indexOfFirst { it.id == messageId && it.role == ChatRole.USER }
            if (idx < 0) return null
            val old = c.messages[idx]
            val text = newText.trim()
            if (text.isEmpty() && old.attachments.isEmpty()) return null
            val kept = c.messages.subList(0, idx).toList() + ChatMessage(role = ChatRole.USER, text = text, attachments = old.attachments)
            val alive = kept.map { it.id }.toSet()
            val title = if (idx == 0 && !c.titleLocked) titleFrom(text.ifEmpty { old.attachments.first().fileName }) else c.title
            return c.copy(messages = kept, title = title,
                compactMarkers = c.compactMarkers.filter { it.lastCompactedMessageId.substringBefore('#') in alive })
        }

        /** The first message titles a chat, unless the user already renamed it. */
        fun titleOnSend(conv: Conversation, text: String): String =
            if (conv.messages.isEmpty() && !conv.titleLocked) titleFrom(text) else conv.title

        fun titleFrom(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: DEFAULT_CONVERSATION_TITLE
            return if (line.length > 48) line.take(47) + "…" else line
        }
    }
}
