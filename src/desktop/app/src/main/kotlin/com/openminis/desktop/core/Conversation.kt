// OpenMinis Desktop — conversation model + JSON-file persistence (replaces the Android Room chat DB).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class ToolStatus { PENDING, RUNNING, DONE, FAILED, DENIED }

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** JSON object string of the arguments as the model sent them. */
    val argsJson: String = "{}",
    val result: String? = null,
    val isError: Boolean = false,
    val status: ToolStatus = ToolStatus.PENDING,
    /** Gemini 3 thought signature — must be echoed back with the function call. */
    val thoughtSignature: String? = null,
    val durationMs: Long? = null,
)

/** One model response inside an assistant turn: text + optional tool calls it issued. */
@Serializable
data class Round(
    val text: String = "",
    val thinking: String = "",
    /** Provider-side `reasoning_content` (DeepSeek/Kimi…) echoed back on the next request. */
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
)

@Serializable
enum class ChatRole { USER, ASSISTANT }

@Serializable
enum class AttachmentKind { IMAGE, TEXT, FILE }

/**
 * A file the user attached to a message (desktop counterpart of upstream ui/chat/InputAttachment +
 * MediaRef). The bytes live under `<data root>/attachments/<conversationId>/`; [relativePath] is
 * relative to the data root so the whole profile folder can be moved.
 */
@Serializable
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val relativePath: String,
    val sizeBytes: Long = 0,
    /** Where the user's original came from (drag-and-drop source path), informational only. */
    val sourcePath: String? = null,
)

/**
 * Compaction marker — desktop port of upstream db/CompactMarkerEntity v2 (id-only anchor model):
 * everything from the conversation start (or the previous marker's anchor + 1) up to and including
 * [lastCompactedMessageId] is folded into [summary].
 *
 * Anchor ids are the `dbMessageId`s HistoryMapper stamps on provider messages: a user message's id,
 * or `<assistantMessageId>#<roundIndex>` for one model round, so a compaction can land inside a
 * long tool-calling turn and still resolve after a restart.
 */
@Serializable
data class CompactMarker(
    val id: String = UUID.randomUUID().toString(),
    val summary: String,
    val lastCompactedMessageId: String,
    val compactedCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val version: Int = 2,
    /** Model that wrote the summary (for the UI). */
    val model: String? = null,
    /** True when the compaction ran automatically near the context limit. */
    val automatic: Boolean = false,
)

/** A provider + model id pair: one link of the fallback chain. */
@Serializable
data class ModelRef(val provider: ProviderKind, val model: String) {
    val label: String get() = "${provider.label} · $model"
}

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    /** User text (USER messages only). */
    val text: String = "",
    /** Model rounds (ASSISTANT messages only). */
    val rounds: List<Round> = emptyList(),
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    /** Files attached to a USER message. */
    val attachments: List<Attachment> = emptyList(),
    /** Fallback / compaction notices shown above an assistant reply ("⚠️ A: Rate limited → switched to B"). */
    val notices: List<String> = emptyList(),
    /** Model that produced (the end of) an assistant reply, e.g. "OpenAI · gpt-4o-mini". */
    val modelLabel: String? = null,
) {
    /** All visible assistant text, concatenated. */
    val displayText: String get() = if (role == ChatRole.USER) text else rounds.joinToString("\n\n") { it.text }.trim()
}

/** Persisted title of a chat that has not been titled yet. Stored in English for settings/JSON
 * compatibility; the UI renders it via [com.openminis.desktop.ui.displayTitle] (key chat.untitled). */
const val DEFAULT_CONVERSATION_TITLE = "New chat"

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_CONVERSATION_TITLE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val provider: ProviderKind? = null,
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** Compaction markers, oldest first; the last one is in effect. */
    val compactMarkers: List<CompactMarker> = emptyList(),
    /** Per-model token calibration ratios (upstream ContextSizeMeter): provider-reported ÷ estimated. */
    val contextRatios: Map<String, Double> = emptyMap(),
    /** Shown in the sidebar's Pinned group, above the recent chats. */
    val pinned: Boolean = false,
    /** Set by a manual rename: the first message no longer auto-titles the chat. */
    val titleLocked: Boolean = false,
    /** MCP servers switched off for this chat only (by name); every server enabled in Settings is on otherwise. */
    val mcpOff: List<String> = emptyList(),
) {
    val latestMarker: CompactMarker? get() = compactMarkers.lastOrNull()
}

class ConversationStore(private val dir: File) {
    // encodeDefaults must stay true: timestamps/ids default to "now"/random and would otherwise be regenerated on load.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true; explicitNulls = false }

    init { dir.mkdirs() }

    private fun fileFor(id: String) = File(dir, "${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun loadAll(): List<Conversation> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f ->
                try { json.decodeFromString(Conversation.serializer(), f.readText()) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.error("ConversationStore", "skip unreadable ${f.name}", e)
                    null
                }
            }
            .sortedByDescending { it.updatedAt }

    fun save(c: Conversation) = atomicWrite(fileFor(c.id), json.encodeToString(Conversation.serializer(), c))

    fun delete(id: String) { fileFor(id).delete() }
}
