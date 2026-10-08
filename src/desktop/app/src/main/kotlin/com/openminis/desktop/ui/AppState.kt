// OpenMinis Desktop — UI state holder (desktop counterpart of the Android ChatViewModel/SessionList VM).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

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
import java.io.File

class PendingApproval(val request: ApprovalRequest, val answer: CompletableDeferred<Boolean>)

class AppState(val paths: AppPaths, private val scope: CoroutineScope) {
    private val settingsStore = SettingsStore(paths.settingsFile)
    private val store = ConversationStore(paths.conversationsDir)
    val memory = MemoryStore(paths.memoryDir)
    val skills = SkillLoader { listOf(paths.skillsDir, File(settings.workspace(), ".skills")) }

    var settings by mutableStateOf(settingsStore.load())
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

    private val approver = ToolApprover { req ->
        val d = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { pendingApproval = PendingApproval(req, d) }
        try { d.await() } finally { withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { pendingApproval = null } }
    }
    private val tools = ToolRegistry({ settings }, memory, approver)
    private val engine = AgentEngine({ s, ref -> DesktopProviderFactory.create(s, ref) }, tools, shaper)

    init {
        com.openminis.desktop.net.DesktopHttp.update(settings.network)
        conversations.addAll(store.loadAll())
        newChat()
        // Warm the Chinese segmenter (dictionary load ~1 s) off the UI thread.
        scope.launch(Dispatchers.IO) { runCatching { com.openminis.desktop.tools.MemoryTokenizer.tokenize("预热 warmup") } }
    }

    val selected: Conversation? get() = conversations.firstOrNull { it.id == selectedId }
    val isRunning: Boolean get() = runningConversationId != null

    fun updateSettings(s: AppSettings) {
        if (s.network != settings.network) com.openminis.desktop.net.DesktopHttp.update(s.network)
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
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx >= 0) conversations.removeAt(idx)
        pendingAttachments.remove(id)
        scope.launch(Dispatchers.IO) { store.delete(id); attachmentStore.deleteConversation(id) }
        if (selectedId == id) {
            selectedId = conversations.firstOrNull()?.id
            if (selectedId == null) newChat()
        }
    }

    fun rename(id: String, title: String) = mutate(id, persist = true) { it.copy(title = title.ifBlank { it.title }) }

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
                    f.isDirectory -> errors += "${f.name}: folders can't be attached (attach the files inside, or give the agent the path)"
                    else -> runCatching { attachmentStore.importFile(conv.id, f) }.onSuccess { added += it }.onFailure { errors += "${f.name}: ${it.message}" }
                }
            }
            withContext(Dispatchers.Main) {
                if (added.isNotEmpty()) pendingAttachments[conv.id] = pending(conv.id) + added
                if (errors.isNotEmpty()) toast = "Some files were not attached:\n" + errors.joinToString("\n")
                composerFocusTick++
            }
        }
    }

    fun attachImage(image: java.awt.Image) {
        val conv = selected ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { attachmentStore.importImage(conv.id, image) }
                .onSuccess { a -> withContext(Dispatchers.Main) { pendingAttachments[conv.id] = pending(conv.id) + a } }
                .onFailure { e -> withContext(Dispatchers.Main) { toast = "Could not paste image: ${e.message}" } }
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
        val s = settings
        val user = ChatMessage(role = ChatRole.USER, text = trimmed, attachments = atts)
        val title = if (conv.messages.isEmpty()) titleFrom(trimmed.ifEmpty { atts.first().fileName }) else conv.title
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
                val prompt = withContext(Dispatchers.IO) { SystemPrompt.build(s, memory, skills) }
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
        if (isRunning || compactingConversationId != null) { toast = "Wait for the current response to finish before compacting."; return }
        if (conv.messages.isEmpty()) { toast = "Nothing to compact — the chat is empty."; return }
        val s = settings
        val ctx = contextStateFor(conv)
        compactingConversationId = conv.id
        statusText = "Compacting context…"
        job = scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    engine.compact(conv.messages, s, ctx, object : TurnListener {
                        override fun onStatus(text: String?) { scope.launch(Dispatchers.Main) { statusText = text ?: "Compacting context…" } }
                    })
                }
                when (outcome) {
                    is CompactOutcome.Done -> mutate(conv.id, persist = true) { it.copy(compactMarkers = ctx.markers.toList()) }
                    is CompactOutcome.Skipped -> toast = outcome.reason
                    is CompactOutcome.Failed -> toast = "Compaction failed: ${outcome.reason}"
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
            val r = runCatching { engine.measure(conv.messages, s, SystemPrompt.build(s, memory, skills), contextStateFor(conv)) }.getOrNull()
            withContext(Dispatchers.Main) { if (r != null) contextReadings[convId] = r else contextReadings.remove(convId) }
        }
    }

    fun stop() {
        job?.cancel()
        if (compactingConversationId != null) statusText = null
        pendingApproval?.answer?.complete(false)
    }

    fun answerApproval(allow: Boolean, dontAskAgain: Boolean = false) {
        val p = pendingApproval ?: return
        if (allow && dontAskAgain) {
            updateSettings(
                if (p.request.toolName == "shell") settings.copy(confirmShellCommands = false)
                else settings.copy(confirmWritesOutsideWorkspace = false)
            )
        }
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

    fun skillCount(): Int = runCatching { skills.load().size }.getOrDefault(0)

    fun openInExplorer(f: File) {
        scope.launch(Dispatchers.IO) {
            f.mkdirs()
            runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure {
                withContext(Dispatchers.Main) { toast = "Cannot open ${f.absolutePath}: ${it.message}" }
            }
        }
    }

    fun openFile(f: File, template: String = "") {
        scope.launch(Dispatchers.IO) {
            if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(template) }
            runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure {
                withContext(Dispatchers.Main) { toast = "Cannot open ${f.absolutePath}: ${it.message}" }
            }
        }
    }

    companion object {
        fun titleFrom(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "New chat"
            return if (line.length > 48) line.take(47) + "…" else line
        }
    }
}
