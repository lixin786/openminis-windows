// OpenMinis Desktop — context compaction. Port of the compaction half of the Android
// ui/chat/ChatViewModel.kt (compactAllImpl, generateCompactSummaryWithSplitting,
// generateCompactSummary, buildConversationTextForSummary, effectiveAgentHistoryUncounted v2,
// walkBackUserTurnsBounded, trimWarmUpToFit, shouldSplitOnError, compactSummarySystemPrompt).
// The ViewModel versions read/write Android state (StateFlows, Room markers); these are the same
// algorithms as pure functions over the provider-message list so the desktop AgentEngine and tests
// can drive them. Thresholds come from the verbatim upstream data/ContextPolicy.kt.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.agent

import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.withIdleTimeout
import com.openminis.desktop.core.CompactMarker
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

object Compaction {
    private const val TAG = "Compaction"

    /** Upstream ChatViewModel.COMPACT_KEEP_RECENT_USER_TURNS. */
    const val KEEP_RECENT_USER_TURNS = 3
    /** Upstream preAnchorCap. */
    const val PRE_ANCHOR_CAP = 100
    /** Upstream MAX_COMPACT_LLM_CALLS. */
    const val MAX_COMPACT_LLM_CALLS = 6
    /** Upstream COMPACT_IDLE_TIMEOUT_MS. */
    const val COMPACT_IDLE_TIMEOUT_MS = 120_000L
    /** Upstream COMPACT_MAX_TOTAL_MS. */
    const val COMPACT_MAX_TOTAL_MS = 900_000L

    /** Upstream ChatViewModel.CompactIdleTimeoutException — a plain Exception on purpose (see upstream doc). */
    class CompactIdleTimeoutException(message: String) : Exception(message)

    /** Verbatim upstream `compactSummarySystemPrompt` (iOS wording). */
    val SUMMARY_SYSTEM_PROMPT: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()

    /** Verbatim upstream wrapper text placed around the summary when it is replayed to the model. */
    fun summaryWrappedText(summary: String): String = "<context-summary>\n" +
        "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
        "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
        summary +
        "\n</context-summary>"

    // ───────────────────────────────────────────── transcript for the summariser

    /** Port of upstream `buildConversationTextForSummary` (identical truncation rules). */
    fun buildConversationTextForSummary(history: List<LLMMessage>): String = buildString {
        for (msg in history) {
            val role = msg.role.name.lowercase()
            val text = msg.content.take(500)
            // Upstream messages carry either content OR parts; the desktop mapper sets both for
            // user turns with images, so skip `content` when a Text part repeats it.
            if (text.isNotEmpty() && msg.contentParts.none { it is AgentContentPart.Text }) {
                append(role).append(": ").append(text).append('\n')
            }
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> append(role).append(": ").append(part.text.take(500)).append('\n')
                    is AgentContentPart.ToolUse -> {
                        val preview = part.input.toString().take(200)
                        append(role).append(" [tool:").append(part.name).append("]: ").append(preview).append('\n')
                    }
                    is AgentContentPart.ToolResult ->
                        append(role).append(" [result:").append(part.name).append("]: ").append(part.content.take(500)).append('\n')
                    is AgentContentPart.ImageData -> append(role).append(" [image: ").append(part.mimeType).append("]\n")
                }
            }
        }
    }

    // ───────────────────────────────────────────── split policy (upstream companion, verbatim logic)

    /** Upstream `looksLikeParameterRejection`. */
    fun looksLikeParameterRejection(error: LLMError.ProviderError): Boolean {
        val msg = error.message?.lowercase() ?: return false
        val sizeMarkers = listOf(
            "context_length", "context length", "too long", "too large",
            "maximum context", "max_tokens", "token limit", "exceeds",
            "reduce the length", "payload",
        )
        if (sizeMarkers.any { msg.contains(it) }) return false
        val paramMarkers = listOf(
            "is not supported", "unsupported parameter", "unsupported value",
            "invalid_request_error", "invalid parameter", "unrecognized request argument",
            "does not support parameter", "extra_forbidden",
        )
        return paramMarkers.any { msg.contains(it) }
    }

    /** Upstream `shouldSplitOnError`: only size-dependent failures are worth a halved retry. */
    fun shouldSplitOnError(error: Throwable): Boolean {
        if (error is CancellationException) return false
        if (error is CompactIdleTimeoutException) return false
        if (error is LLMError) {
            return when (error) {
                is LLMError.Cancelled,
                is LLMError.NetworkError,
                is LLMError.RateLimited,
                is LLMError.TransientError,
                is LLMError.InvalidApiKey,
                -> false
                is LLMError.ProviderError -> !looksLikeParameterRejection(error)
                else -> true
            }
        }
        if (error is java.io.IOException) return false
        return true
    }

    // ───────────────────────────────────────────── summary generation

    /**
     * Port of upstream `generateCompactSummaryWithSplitting`: summarise [messages]; on a
     * size-shaped failure halve by message count (depth ≤ 3, ≤ [MAX_COMPACT_LLM_CALLS] calls in
     * total) and join the partial summaries oldest-first with a plain string join.
     */
    suspend fun summarizeWithSplitting(
        provider: LLMProvider,
        contextWindow: Int,
        messages: List<LLMMessage>,
        previousSummary: String?,
        calls: AtomicInteger = AtomicInteger(0),
        depth: Int = 0,
        idleTimeoutMs: Long = COMPACT_IDLE_TIMEOUT_MS,
    ): String {
        val transcript = buildConversationTextForSummary(messages)
        val conversationText = if (previousSummary.isNullOrBlank()) transcript
        else "Previous context summary:\n$previousSummary\n\nNew conversation to merge:\n$transcript"
        val spent = calls.incrementAndGet()
        if (spent > MAX_COMPACT_LLM_CALLS) {
            throw IllegalStateException("compaction exceeded its budget of $MAX_COMPACT_LLM_CALLS model calls")
        }
        return try {
            generateSummary(provider, contextWindow, conversationText, idleTimeoutMs)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!shouldSplitOnError(e) || messages.size < 2 || depth >= 3) throw e
            if (calls.get() + 2 > MAX_COMPACT_LLM_CALLS) throw e
            val mid = messages.size / 2
            AppLogger.info(TAG, "[Compact] Splitting ${messages.size} messages into $mid + ${messages.size - mid} (depth=$depth)")
            val s1 = summarizeWithSplitting(provider, contextWindow, messages.subList(0, mid).toList(), null, calls, depth + 1, idleTimeoutMs)
            val s2 = summarizeWithSplitting(provider, contextWindow, messages.subList(mid, messages.size).toList(), null, calls, depth + 1, idleTimeoutMs)
            s1 + "\n\n" + s2
        }
    }

    /** Port of upstream `generateCompactSummary` (BEGIN/END framing, ≤8192 output, idle timer per chunk). */
    suspend fun generateSummary(
        provider: LLMProvider,
        contextWindow: Int,
        conversationText: String,
        idleTimeoutMs: Long = COMPACT_IDLE_TIMEOUT_MS,
    ): String {
        val userMessage = buildString {
            append("Compact this conversation into a context summary:\n\n")
            append(conversationText)
            append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
            append(
                "Now generate a structured context summary following the system prompt " +
                    "instructions. Do NOT continue the conversation above — summarize it. " +
                    "Write everything in past tense, framed as \"what was discussed / what " +
                    "was done\", NOT as an ongoing goal or todo list."
            )
        }
        val estimatedInput = userMessage.length / 4
        val maxOut = maxOf(1024, minOf(8192, contextWindow - estimatedInput))
        val text = StringBuilder()
        provider.streamMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = userMessage)),
            systemPrompt = SUMMARY_SYSTEM_PROMPT,
            maxTokens = maxOut,
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        ).withIdleTimeout(idleTimeoutMs) { idleMs ->
            throw CompactIdleTimeoutException("compaction stream received no data for ${idleMs / 1000}s")
        }.collect { chunk -> if (chunk is LLMStreamChunk.Text) text.append(chunk.text) }
        return text.toString()
    }

    // ───────────────────────────────────────────── what gets compacted

    /** The slice a compaction covers: [startIdx]..[anchorIdx] of the provider-message list. */
    data class Plan(val startIdx: Int, val anchorIdx: Int, val anchorId: String, val messages: List<LLMMessage>)

    /**
     * Port of the range logic in upstream `compactAllImpl` (v2 unified anchor model): the anchor is
     * the last entry with a dbMessageId at or before [anchorIdxOverride] (default: the tail); the
     * slice starts right after the previous marker's anchor. Null when there is nothing new.
     */
    fun plan(history: List<LLMMessage>, previous: CompactMarker?, anchorIdxOverride: Int? = null): Plan? {
        if (history.isEmpty()) return null
        var i = (anchorIdxOverride ?: history.lastIndex).coerceIn(0, history.lastIndex)
        while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
        if (i < 0) return null
        val start = previous?.lastCompactedMessageId?.let { id ->
            val prevIdx = history.indexOfLast { it.dbMessageId == id }
            if (prevIdx < 0) 0 else prevIdx + 1
        } ?: 0
        if (start > i) return null
        val slice = history.subList(start, i + 1).toList()
        if (slice.isEmpty()) return null
        return Plan(start, i, history[i].dbMessageId!!, slice)
    }

    // ───────────────────────────────────────────── what the model is sent afterwards

    data class WalkBackResult(val priorIdx: Int?, val userTextTurnsFound: Int, val messageCount: Int, val stopReason: String)

    /** Verbatim port of upstream `walkBackUserTurnsBounded` over an explicit history list. */
    fun walkBackUserTurnsBounded(history: List<LLMMessage>, anchorIdx: Int, maxUserTextTurns: Int, maxMessages: Int): WalkBackResult {
        if (anchorIdx < 0 || anchorIdx >= history.size) return WalkBackResult(null, 0, 0, "invalidAnchor")
        var acceptedPriorIdx: Int? = null
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0
        var i = anchorIdx
        while (i >= 0) {
            val msg = history[i]
            if (msg.role != LLMMessage.Role.USER) { i -= 1; continue }
            // A user message carrying a tool result is the second half of a round, not a boundary.
            if (msg.contentParts.any { it is AgentContentPart.ToolResult }) { i -= 1; continue }
            val candidateMessageCount = anchorIdx - i + 1
            if (candidateMessageCount > maxMessages) {
                return WalkBackResult(acceptedPriorIdx, acceptedUserTextTurns, acceptedMessageCount, "messageCapWouldExceed")
            }
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            val hasText = msg.content.isNotBlank() || msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
            if (hasText) {
                acceptedUserTextTurns += 1
                if (acceptedUserTextTurns >= maxUserTextTurns) {
                    return WalkBackResult(acceptedPriorIdx, acceptedUserTextTurns, acceptedMessageCount, "userTextTargetMet")
                }
            }
            i -= 1
        }
        return WalkBackResult(acceptedPriorIdx, acceptedUserTextTurns, acceptedMessageCount, "reachedStart")
    }

    /**
     * Port of upstream `effectiveAgentHistoryUncounted` (v2 markers): the last
     * [KEEP_RECENT_USER_TURNS] user-text turns up to the anchor as warm-up (tool results > 1000
     * chars and their calls pruned, leading non-user peeled, optionally trimmed oldest-first to
     * [warmUpBudget] raw tokens — upstream `trimWarmUpToFit`), then the summary inlined into the
     * first user message after the anchor (or appended as its own user turn), then the rest.
     * An unresolvable anchor degrades to the full history, as upstream.
     */
    fun effectiveHistory(history: List<LLMMessage>, marker: CompactMarker?, warmUpBudget: Int? = null): List<LLMMessage> {
        if (marker == null || marker.summary.isBlank()) return history
        val summaryText = summaryWrappedText(marker.summary)
        val anchorIdx = history.indexOfLast { it.dbMessageId == marker.lastCompactedMessageId }
        if (anchorIdx < 0) {
            AppLogger.warning(TAG, "[Compact] anchor ${marker.lastCompactedMessageId.take(12)} not in history(size=${history.size}) — sending full history")
            return history
        }
        val walk = walkBackUserTurnsBounded(history, anchorIdx, KEEP_RECENT_USER_TURNS, PRE_ANCHOR_CAP)
        val priorIdx = walk.priorIdx ?: (anchorIdx + 1)
        val preAnchorRaw = if (priorIdx <= anchorIdx) history.subList(priorIdx, anchorIdx + 1).toList() else emptyList()

        val droppedToolIds = mutableSetOf<String>()
        for (msg in preAnchorRaw) for (part in msg.contentParts) {
            if (part is AgentContentPart.ToolResult && part.content.length > 1000) droppedToolIds.add(part.id)
        }
        val preAnchor = ArrayList<LLMMessage>(preAnchorRaw.size)
        for (msg in preAnchorRaw) {
            if (msg.contentParts.isEmpty()) { preAnchor.add(msg); continue }
            val kept = msg.contentParts.filter { part ->
                when (part) {
                    is AgentContentPart.ToolUse -> part.id !in droppedToolIds
                    is AgentContentPart.ToolResult -> part.id !in droppedToolIds
                    else -> true
                }
            }
            if (kept.isEmpty()) continue
            preAnchor.add(msg.copy(contentParts = kept))
        }
        while (preAnchor.isNotEmpty() && preAnchor.first().role != LLMMessage.Role.USER) preAnchor.removeAt(0)

        val postAnchor = if (anchorIdx + 1 < history.size) history.subList(anchorIdx + 1, history.size) else emptyList()
        val warmUp = trimWarmUpToFit(preAnchor, postAnchor, summaryText, warmUpBudget)

        val result = ArrayList<LLMMessage>(warmUp.size + postAnchor.size + 1)
        result.addAll(warmUp)
        val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
        if (firstUserOffset >= 0) {
            if (firstUserOffset > 0) result.addAll(postAnchor.subList(0, firstUserOffset))
            val target = postAnchor[firstUserOffset]
            // Update BOTH representations so the summary survives whichever one a provider reads.
            val injectedParts = if (target.contentParts.isEmpty()) target.contentParts else {
                val firstTextIdx = target.contentParts.indexOfFirst { it is AgentContentPart.Text }
                if (firstTextIdx < 0) listOf(AgentContentPart.Text(summaryText)) + target.contentParts
                else target.contentParts.mapIndexed { i, part ->
                    if (i != firstTextIdx || part !is AgentContentPart.Text) part else AgentContentPart.Text(summaryText + "\n\n" + part.text)
                }
            }
            result.add(target.copy(content = summaryText + "\n\n" + target.content, contentParts = injectedParts))
            if (firstUserOffset + 1 < postAnchor.size) result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
        } else {
            result.addAll(postAnchor)
            result.add(LLMMessage(role = LLMMessage.Role.USER, content = summaryText))
        }
        return result
    }

    /** Port of upstream `trimWarmUpToFit` with the budget passed in (null = undecided → unchanged). */
    fun trimWarmUpToFit(warmUp: List<LLMMessage>, rest: List<LLMMessage>, summaryText: String, budget: Int?): List<LLMMessage> {
        if (warmUp.isEmpty() || budget == null) return warmUp
        val restTokens = ContextSizeMeter.estimateTokens(rest) + ContextSizeMeter.estimateTokens(summaryText)
        if (ContextSizeMeter.estimateTokens(warmUp) + restTokens < budget) return warmUp
        val drop = ContextSizeMeter.warmUpDrop(
            sizes = warmUp.map { ContextSizeMeter.estimateTokens(listOf(it)) },
            startsTurn = warmUp.map { m -> m.role == LLMMessage.Role.USER && m.contentParts.none { it is AgentContentPart.ToolResult } },
            restTokens = restTokens,
            budget = budget,
        )
        return warmUp.drop(drop)
    }

    /** Upstream top-level `warmUpBudget`. */
    fun warmUpBudget(window: Int?, compactThreshold: Int?, ratio: Double, fixedTokens: Int): Int? {
        val w = window?.takeIf { it > 0 } ?: return null
        val threshold = compactThreshold ?: return null
        if (fixedTokens <= 0) return null
        val line = if (threshold > 0) threshold else w
        return (line / ratio).toInt() - fixedTokens
    }

    /** Number of distinct chat messages (user messages / assistant replies) a plan covers. */
    fun chatMessageCount(plan: Plan): Int = plan.messages.mapNotNull { it.dbMessageId?.substringBefore('#') }.distinct().size
}
