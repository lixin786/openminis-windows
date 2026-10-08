// OpenMinis Desktop — the agent tool loop. A compact re-implementation of the streaming/tool
// orchestration in the Android ChatViewModel (17k lines, Android-bound), reusing the upstream
// LLMProvider stream contract, AgentContentPart history shape and ToolLoopDetector, plus:
//  - context measurement + compaction driven by upstream data/ContextPolicy.kt (ContextPolicy,
//    ContextSizeMeter calibration, inLoopStep decision table) and agent/Compaction.kt,
//  - the context-overflow self-heal trigger of upstream data/ContextOverflowGuard.kt,
//  - the model fallback chain (agent/FallbackChain.kt),
//  - user attachments shaped through the upstream ImageBudget (core/Attachments.kt).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.agent

import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.data.ContextOverflowGuard
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.inferContextWindowTokens
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.AttachmentShaper
import com.openminis.desktop.core.ChatMessage
import com.openminis.desktop.core.ChatRole
import com.openminis.desktop.core.CompactMarker
import com.openminis.desktop.core.ModelRef
import com.openminis.desktop.core.Round
import com.openminis.desktop.core.ToolCall
import com.openminis.desktop.core.ToolStatus
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Per-conversation state the engine reads and updates (persisted by the caller). */
class ConversationContextState(
    markers: List<CompactMarker> = emptyList(),
    ratios: Map<String, Double> = emptyMap(),
) {
    val markers: MutableList<CompactMarker> = markers.toMutableList()
    val ratios: MutableMap<String, Double> = ratios.toMutableMap()
    /** Newest learned ratio, borrowed (with margin) by a model that has none yet. */
    var lastLearnedRatio: Double? = ratios.values.lastOrNull()
    val latestMarker: CompactMarker? get() = markers.lastOrNull()
}

/** One measurement of the outgoing request (upstream ContextSizeMeter, calibrated). */
data class ContextReading(val measuredTokens: Int, val window: Int, val compactThreshold: Int, val modelId: String)

/** Callbacks from a running turn. All have no-op defaults. */
interface TurnListener {
    fun onUpdate(message: ChatMessage) {}
    fun onCompacted(marker: CompactMarker) {}
    fun onContext(reading: ContextReading) {}
    /** Transient status line ("Compacting context…", "retrying 1/3…"); null clears it. */
    fun onStatus(text: String?) {}
}

sealed class CompactOutcome {
    data class Done(val marker: CompactMarker) : CompactOutcome()
    data class Skipped(val reason: String) : CompactOutcome()
    data class Failed(val reason: String) : CompactOutcome()
}

class AgentEngine(
    private val providerFactory: (AppSettings, ModelRef) -> LLMProvider,
    private val tools: ToolRegistry,
    private val shaper: AttachmentShaper? = null,
    private val retryDelaysMs: List<Long> = FallbackPolicy.DEFAULT_RETRY_DELAYS_MS,
    private val compactIdleTimeoutMs: Long = Compaction.COMPACT_IDLE_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "AgentEngine"
        /** Automatic compactions allowed inside one turn before the loop stops trying. */
        const val MAX_COMPACTIONS_PER_TURN = 3

        /** Context window used for decisions: Settings override, else upstream catalog/heuristic. */
        fun windowFor(settings: AppSettings, model: LLMModel): Int =
            settings.contextWindowOverride.takeIf { it > 0 } ?: inferContextWindowTokens(model)

        fun policyFor(settings: AppSettings, window: Int): ContextPolicy =
            if (settings.contextWindowOverride > 0) ContextPolicy.forUserCap(window) else ContextPolicy.forContextWindow(window)
    }

    private val mapper = HistoryMapper(shaper)

    /** Back-compat entry (tests, simple callers). */
    suspend fun runTurn(
        history: List<ChatMessage>,
        settings: AppSettings,
        systemPrompt: String,
        onUpdate: (ChatMessage) -> Unit,
    ): ChatMessage = runTurn(history, settings, systemPrompt, ConversationContextState(), object : TurnListener {
        override fun onUpdate(message: ChatMessage) = onUpdate(message)
    })

    /**
     * Runs one assistant turn for [history] (which ends with the new user message).
     * [listener] receives the in-progress assistant message after every streamed change.
     * Returns the final assistant message; on cancellation the partial message is delivered
     * through [TurnListener.onUpdate] before the CancellationException propagates.
     */
    suspend fun runTurn(
        history: List<ChatMessage>,
        settings: AppSettings,
        systemPrompt: String,
        ctx: ConversationContextState,
        listener: TurnListener,
    ): ChatMessage {
        val assistantId = java.util.UUID.randomUUID().toString()
        val turnStartedAt = System.currentTimeMillis()
        val rounds = mutableListOf<Round>()
        var current = Round()
        var inTok = 0
        var outTok = 0
        val notices = mutableListOf<String>()
        var modelLabel: String? = null
        fun snapshot(error: String? = null) = ChatMessage(
            id = assistantId, role = ChatRole.ASSISTANT,
            rounds = rounds + listOfNotNull(current.takeIf { it != Round() }),
            error = error, inputTokens = inTok.takeIf { it > 0 }, outputTokens = outTok.takeIf { it > 0 },
            notices = notices.toList(), modelLabel = modelLabel,
        )
        var lastEmit = 0L
        fun emit(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || now - lastEmit > 33) { lastEmit = now; listener.onUpdate(snapshot()) }
        }

        val detector = ToolLoopDetector()
        val chain = FallbackChain(
            settings, providerFactory, retryDelaysMs,
            onNotice = { notices += it; emit(true) },
            onRetry = { a, max, why -> listener.onStatus("$why — retrying ($a/$max)…") },
        )
        try {
            val toolDefs = if (settings.toolsEnabled) tools.definitions() else emptyList()
            val thinking = runCatching { ThinkingLevel.valueOf(settings.thinkingLevel) }.getOrDefault(ThinkingLevel.OFF)
            val fixedTokens = ContextSizeMeter.estimateFixedTokens(systemPrompt, toolDefs)
            val base = mapper.toLLMMessages(history)
            var compactionsThisTurn = 0
            var lastCompactMadeProgress = true
            var uncalibratedSendUsed = false
            var overflowHealUsed = false

            for (roundIndex in 0 until settings.maxToolRounds.coerceAtLeast(1)) {
                current = Round()
                request@ while (true) {
                    val provider = try { chain.current() } catch (e: Exception) {
                        if (chain.onFailure(e)) continue@request else throw e
                    }
                    modelLabel = chain.currentRef.label
                    val modelId = provider.model.id
                    val window = windowFor(settings, provider.model)
                    val policy = policyFor(settings, window)
                    val full = base + mapper.roundsToLLM(rounds, assistantId)
                    val ratio = ContextSizeMeter.ratioFor(modelId, ctx.ratios, ctx.lastLearnedRatio)
                    val warmBudget = Compaction.warmUpBudget(window, policy.compactThreshold, ratio, fixedTokens)
                    val messages = applyRequestImageBudget(Compaction.effectiveHistory(full, ctx.latestMarker, warmBudget))
                    val raw = ContextSizeMeter.estimateTokens(messages) + fixedTokens
                    val measured = ContextSizeMeter.calibrated(raw, ratio)
                    listener.onContext(ContextReading(measured, window, policy.compactThreshold, modelId))
                    val verdict = policy.check(measured, window)
                    val canCompact = settings.autoCompact && policy.manualCompactAllowed &&
                        compactionsThisTurn < MAX_COMPACTIONS_PER_TURN && lastCompactMadeProgress
                    when (ContextPolicy.inLoopStep(verdict, measured, raw, window, canCompact, ratio, uncalibratedSendUsed)) {
                        ContextPolicy.InLoopStep.COMPACT -> {
                            compactionsThisTurn++
                            // Turn start: compact everything BEFORE the new user message (upstream's
                            // pre-send compaction must not fold the just-sent message into the summary).
                            // Mid-turn: compact up to the last finished round.
                            val anchorOverride = if (rounds.isEmpty()) lastUserTextIndex(full).let { if (it > 0) it - 1 else null } else null
                            val outcome = compactInternal(full, anchorOverride, settings, ctx, chain, listener, automatic = true)
                            if (outcome is CompactOutcome.Done) {
                                notices += "Context compacted automatically (~${measured / 1000}k of ${window / 1000}k tokens)."
                                val after = ContextSizeMeter.calibrated(
                                    ContextSizeMeter.estimateTokens(Compaction.effectiveHistory(full, ctx.latestMarker, warmBudget)) + fixedTokens, ratio,
                                )
                                lastCompactMadeProgress = after < measured
                            } else {
                                lastCompactMadeProgress = false
                                val why = (outcome as? CompactOutcome.Failed)?.reason ?: (outcome as CompactOutcome.Skipped).reason
                                notices += "⚠️ Automatic compaction did not run: $why"
                            }
                            emit(true)
                            continue@request
                        }
                        ContextPolicy.InLoopStep.STOP -> {
                            listener.onStatus(null)
                            return snapshot(
                                error = "The conversation no longer fits the model's context window (~$measured of $window tokens). " +
                                    "Use Compact (toolbar or /compact) or start a new chat.",
                            ).also(listener::onUpdate)
                        }
                        ContextPolicy.InLoopStep.SEND_UNCALIBRATED_ONCE -> uncalibratedSendUsed = true
                        else -> {}
                    }

                    try {
                        var reported = 0
                        provider.streamMessage(
                            messages = messages,
                            systemPrompt = systemPrompt,
                            maxTokens = settings.maxOutputTokens,
                            tools = toolDefs,
                            thinkingLevel = thinking,
                        ).collect { chunk ->
                            when (chunk) {
                                is LLMStreamChunk.Text -> current = current.copy(text = current.text + chunk.text)
                                is LLMStreamChunk.ThinkingDelta -> current = current.copy(thinking = current.thinking + chunk.text)
                                is LLMStreamChunk.ReasoningContent ->
                                    current = current.copy(reasoningContent = (current.reasoningContent ?: "") + chunk.content)
                                is LLMStreamChunk.ToolUseStart -> if (current.toolCalls.none { it.id == chunk.id }) {
                                    current = current.copy(toolCalls = current.toolCalls + ToolCall(chunk.id, chunk.name))
                                }
                                is LLMStreamChunk.ToolInputDelta -> current = current.copy(toolCalls = current.toolCalls.map {
                                    if (it.id == chunk.id) it.copy(argsJson = chunk.accumulated) else it
                                })
                                is LLMStreamChunk.ToolCallComplete -> {
                                    val tc = ToolCall(chunk.id, chunk.name, chunk.args.toString(), thoughtSignature = chunk.thoughtSignature)
                                    current = if (current.toolCalls.any { it.id == chunk.id })
                                        current.copy(toolCalls = current.toolCalls.map { if (it.id == chunk.id) tc else it })
                                    else current.copy(toolCalls = current.toolCalls + tc)
                                }
                                is LLMStreamChunk.Usage -> {
                                    inTok += chunk.usage.inputTokens; outTok += chunk.usage.outputTokens
                                    val u = chunk.usage
                                    val ctxTokens = u.latestContextTokens.takeIf { it > 0 }
                                        ?: (u.inputTokens + (u.cacheReadInputTokens ?: 0) + (u.cacheCreationInputTokens ?: 0))
                                    reported = maxOf(reported, ctxTokens)
                                }
                                else -> {}
                            }
                            listener.onStatus(null)
                            emit()
                        }
                        // [T-ctx-measure-outbound] calibration: provider count ÷ our estimate of that same request.
                        ContextSizeMeter.calibrationRatio(reported, raw)?.let { sample ->
                            ctx.ratios[modelId] = ContextSizeMeter.smoothed(ctx.ratios[modelId], sample)
                            ctx.lastLearnedRatio = sample
                        }
                        break@request
                    } catch (e: Exception) {
                        // Upstream: `if (e is CancellationException && e.cause == null) throw e` — a provider
                        // cancels its callbackFlow with CancellationException("Stream error", LLMError) on a
                        // dropped stream; only a cause-less one (or a cancelled job) is the user's Stop.
                        if (e is CancellationException && (e.cause == null || !currentCoroutineContext().isActive)) throw e
                        val actual = FallbackPolicy.unwrap(e)
                        if (actual is LLMError.Cancelled && !currentCoroutineContext().isActive) throw CancellationException("stopped")
                        val pe = actual as? LLMError.ProviderError
                        if (!overflowHealUsed && ContextOverflowGuard.isContextOverflow(pe?.httpStatus, pe?.detail)) {
                            // Ground truth that we under-read this request: raise the ratio so the
                            // re-check sees the real size and compacts (upstream noteContextOverflow).
                            overflowHealUsed = true
                            ctx.ratios[modelId] = ContextSizeMeter.ratioAfterOverflow(
                                ratio, raw, ContextSizeMeter.requestedTokens(pe?.detail ?: ""), window,
                            )
                            AppLogger.warning(TAG, "[Overflow] ${pe?.detail?.take(200)} — ratio now ${ctx.ratios[modelId]}")
                            if (settings.autoCompact && policy.manualCompactAllowed) {
                                current = Round(); emit(true); continue@request
                            }
                        }
                        if (chain.onFailure(actual)) { current = Round(); emit(true); continue@request }
                        throw actual
                    }
                }
                emit(force = true)

                if (current.toolCalls.isEmpty()) {
                    rounds += current
                    current = Round()
                    listener.onStatus(null)
                    return snapshot().also(listener::onUpdate)
                }

                // Execute tool calls sequentially (shell approvals are serialized for the user anyway).
                val executed = current.toolCalls.toMutableList()
                for (i in executed.indices) {
                    val call = executed[i]
                    val args = runCatching { JSONObject(call.argsJson) }.getOrElse { JSONObject() }
                    val params = jsonToMap(args)
                    val check = detector.check(call.name, params)
                    if (check.level == Level.CRITICAL) {
                        executed[i] = call.copy(status = ToolStatus.FAILED, isError = true, result = check.message ?: "Loop blocked")
                        current = current.copy(toolCalls = executed.toList()); emit(true)
                        continue
                    }
                    executed[i] = call.copy(status = ToolStatus.RUNNING)
                    current = current.copy(toolCalls = executed.toList()); emit(true)
                    val started = System.currentTimeMillis()
                    val outcome = tools.execute(call.name, args)
                    var content = outcome.content
                    val rec = detector.record(call.name, params, if (outcome.isError) null else content, if (outcome.isError) content else null, call.id)
                    if (rec.level != Level.NONE && rec.message != null) content += "\n\n${rec.message}"
                    else if (check.level == Level.WARNING && check.message != null) content += "\n\n${check.message}"
                    executed[i] = executed[i].copy(
                        result = content, isError = outcome.isError,
                        status = when { outcome.denied -> ToolStatus.DENIED; outcome.isError -> ToolStatus.FAILED; else -> ToolStatus.DONE },
                        durationMs = System.currentTimeMillis() - started,
                    )
                    current = current.copy(toolCalls = executed.toList()); emit(true)
                }
                rounds += current
                current = Round()
            }
            listener.onStatus(null)
            return snapshot(error = "Stopped after ${settings.maxToolRounds} tool rounds (limit set in Settings).").also(listener::onUpdate)
        } catch (e: CancellationException) {
            current = current.copy(toolCalls = current.toolCalls.map {
                if (it.status == ToolStatus.PENDING || it.status == ToolStatus.RUNNING) it.copy(status = ToolStatus.FAILED, isError = true, result = "Cancelled by user") else it
            })
            listener.onStatus(null)
            listener.onUpdate(snapshot(error = "Stopped."))
            throw e
        } catch (e: Exception) {
            AppLogger.error(TAG, "turn failed", e)
            listener.onStatus(null)
            val detail = e.message ?: e.javaClass.simpleName
            val stall = com.openminis.desktop.net.DesktopHttp.lastStall
                ?.takeIf { it.first >= turnStartedAt }?.second?.let { "\n\n$it" } ?: ""
            val extra = stall + (chain.unavailableSummary()?.let { "\n\nFallback models skipped:\n$it" } ?: "")
            return snapshot(error = detail + extra).also(listener::onUpdate)
        }
    }

    /**
     * Manual "Compact" (toolbar / Chat menu / `/compact`): fold the whole conversation so far into
     * a summary. The next user message gets the summary inlined (upstream v2 anchor model).
     */
    suspend fun compact(
        history: List<ChatMessage>,
        settings: AppSettings,
        ctx: ConversationContextState,
        listener: TurnListener = object : TurnListener {},
    ): CompactOutcome {
        val full = mapper.toLLMMessages(history)
        val chain = FallbackChain(settings, providerFactory, retryDelaysMs,
            onRetry = { a, max, why -> listener.onStatus("$why — retrying ($a/$max)…") })
        return try {
            compactInternal(full, null, settings, ctx, chain, listener, automatic = false)
        } finally {
            listener.onStatus(null)
        }
    }

    /** Current calibrated size of what the next request would carry (for the toolbar indicator). */
    fun measure(history: List<ChatMessage>, settings: AppSettings, systemPrompt: String, ctx: ConversationContextState): ContextReading? {
        val ref = settings.primaryModel()
        val provider = runCatching { providerFactory(settings, ref) }.getOrNull() ?: return null
        val window = windowFor(settings, provider.model)
        val policy = policyFor(settings, window)
        val fixed = ContextSizeMeter.estimateFixedTokens(systemPrompt, if (settings.toolsEnabled) tools.definitions() else emptyList())
        val ratio = ContextSizeMeter.ratioFor(provider.model.id, ctx.ratios, ctx.lastLearnedRatio)
        val msgs = Compaction.effectiveHistory(mapper.toLLMMessages(history), ctx.latestMarker,
            Compaction.warmUpBudget(window, policy.compactThreshold, ratio, fixed))
        val measured = ContextSizeMeter.calibrated(ContextSizeMeter.estimateTokens(msgs) + fixed, ratio)
        return ContextReading(measured, window, policy.compactThreshold, provider.model.id)
    }

    private suspend fun compactInternal(
        full: List<LLMMessage>,
        anchorOverride: Int?,
        settings: AppSettings,
        ctx: ConversationContextState,
        chain: FallbackChain,
        listener: TurnListener,
        automatic: Boolean,
    ): CompactOutcome {
        val prev = ctx.latestMarker
        val plan = Compaction.plan(full, prev, anchorOverride)
            ?: return CompactOutcome.Skipped(if (prev != null) "Already compacted up to this point." else "Nothing to compact yet.")
        listener.onStatus("Compacting context (${plan.messages.size} messages)…")
        AppLogger.info(TAG, "[Compact] starting: ${plan.messages.size} entries [${plan.startIdx}..${plan.anchorIdx}] automatic=$automatic")
        var summary: String
        while (true) {
            val provider = try { chain.current() } catch (e: Exception) {
                if (chain.onFailure(e)) continue
                return CompactOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            try {
                summary = withTimeout(Compaction.COMPACT_MAX_TOTAL_MS) {
                    Compaction.summarizeWithSplitting(
                        provider, windowFor(settings, provider.model), plan.messages, prev?.summary,
                        idleTimeoutMs = compactIdleTimeoutMs,
                    )
                }.trim()
                break
            } catch (t: TimeoutCancellationException) {
                return CompactOutcome.Failed("compaction timed out after ${Compaction.COMPACT_MAX_TOTAL_MS / 1000}s")
            } catch (e: Exception) {
                if (e is CancellationException && (e.cause == null || !currentCoroutineContext().isActive)) throw e
                val actual = FallbackPolicy.unwrap(e)
                // Same classification the turn uses (upstream [T-android-compact-fallback]).
                if (chain.onFailure(actual)) continue
                AppLogger.error(TAG, "[Compact] failed", e)
                return CompactOutcome.Failed(actual.message ?: actual.javaClass.simpleName)
            }
        }
        if (summary.isBlank()) return CompactOutcome.Failed("the model returned an empty summary")
        val marker = CompactMarker(
            summary = summary,
            lastCompactedMessageId = plan.anchorId,
            compactedCount = (prev?.compactedCount ?: 0) + Compaction.chatMessageCount(plan),
            model = chain.currentRef.label,
            automatic = automatic,
        )
        ctx.markers += marker
        listener.onCompacted(marker)
        AppLogger.info(TAG, "[Compact] done: anchor=${plan.anchorId.take(12)} summaryChars=${summary.length}")
        return CompactOutcome.Done(marker)
    }

    /** Index of the last user message that starts a turn (has text, not a tool result). */
    private fun lastUserTextIndex(full: List<LLMMessage>): Int =
        full.indexOfLast { m -> m.role == LLMMessage.Role.USER && m.contentParts.none { it is AgentContentPart.ToolResult } }

    /**
     * Upstream request-level image budget (ImageBudget.planRequestBudget): newest images win the
     * 25 MB request cap, older ones are replaced by upstream's elision placeholder text.
     */
    private fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
        val imgs = messages.flatMap { m -> m.contentParts.filterIsInstance<AgentContentPart.ImageData>() }
        if (imgs.isEmpty()) return messages
        val plan = ImageBudget.planRequestBudget(imgs.map { ImageBudget.BudgetImage(it.data, it.linuxPath, it.mimeType) })
        if (!plan.mutated) return messages
        return messages.map { m ->
            if (m.contentParts.none { it is AgentContentPart.ImageData }) m else m.copy(contentParts = m.contentParts.map { p ->
                if (p is AgentContentPart.ImageData && ImageBudget.ImagePartId.of(p.data) in plan.droppedIds)
                    AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(p.linuxPath)) else p
            })
        }
    }

    private fun jsonToMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k ->
        when (val v = o.opt(k)) {
            is JSONObject -> jsonToMap(v)
            is JSONArray -> (0 until v.length()).map { v.opt(it) }
            JSONObject.NULL -> null
            else -> v
        }
    }
}

/**
 * Maps persisted chat messages to the upstream provider message shape. Every message carries a
 * `dbMessageId` (upstream AgentMessage.dbMessageId) so compaction markers can anchor on it: the
 * chat message id for user turns, `<assistantId>#<round>` for each model round.
 */
class HistoryMapper(private val shaper: AttachmentShaper? = null) {
    fun roundsToLLM(rounds: List<Round>, messageId: String? = null): List<LLMMessage> = buildList {
        for ((idx, r) in rounds.withIndex()) {
            val id = messageId?.let { "$it#$idx" }
            if (r.toolCalls.isEmpty()) {
                if (r.text.isNotBlank()) add(LLMMessage(LLMMessage.Role.ASSISTANT, r.text, reasoningContent = r.reasoningContent, dbMessageId = id))
                continue
            }
            val parts = buildList<AgentContentPart> {
                if (r.text.isNotBlank()) add(AgentContentPart.Text(r.text))
                r.toolCalls.forEach { tc ->
                    add(AgentContentPart.ToolUse(tc.id, tc.name, runCatching { JSONObject(tc.argsJson) }.getOrElse { JSONObject() }, tc.thoughtSignature))
                }
            }
            add(LLMMessage(LLMMessage.Role.ASSISTANT, r.text, contentParts = parts, reasoningContent = r.reasoningContent, dbMessageId = id))
            val results = r.toolCalls.map { tc ->
                AgentContentPart.ToolResult(tc.id, tc.name, tc.result ?: "Tool call was interrupted before it produced a result.", isError = tc.isError || tc.result == null)
            }
            add(LLMMessage(LLMMessage.Role.USER, "", contentParts = results, dbMessageId = id))
        }
    }

    private fun userMessage(m: ChatMessage): LLMMessage {
        if (m.attachments.isEmpty()) return LLMMessage(LLMMessage.Role.USER, m.text, dbMessageId = m.id)
        val shaped = shaper?.shape(m.attachments)
        val suffix = shaped?.textSuffix ?: m.attachments.joinToString("", prefix = "\n\n") { "[attached: ${it.fileName}]\n" }
        val text = (m.text + suffix).trim()
        val images = shaped?.imageParts.orEmpty()
        return if (images.isEmpty()) LLMMessage(LLMMessage.Role.USER, text, dbMessageId = m.id)
        else LLMMessage(LLMMessage.Role.USER, text, contentParts = images + AgentContentPart.Text(text.ifBlank { "(see attached image)" }), dbMessageId = m.id)
    }

    fun toLLMMessages(history: List<ChatMessage>): List<LLMMessage> {
        val out = mutableListOf<LLMMessage>()
        for (m in history) {
            when (m.role) {
                ChatRole.USER -> {
                    val next = userMessage(m)
                    val last = out.lastOrNull()
                    if (last != null && last.role == LLMMessage.Role.USER) {
                        if (last.contentParts.any { it is AgentContentPart.ToolResult }) {
                            // Previous turn ended on tool results (interrupted): close it.
                            out += LLMMessage(LLMMessage.Role.ASSISTANT, "(The previous response was interrupted.)")
                        } else {
                            // Two user turns in a row (previous turn failed): merge them.
                            val merged = if (last.contentParts.isEmpty() && next.contentParts.isEmpty()) {
                                last.copy(content = last.content + "\n\n" + next.content, dbMessageId = next.dbMessageId)
                            } else {
                                val lp = last.contentParts.ifEmpty { listOf(AgentContentPart.Text(last.content)) }
                                val np = next.contentParts.ifEmpty { listOf(AgentContentPart.Text(next.content)) }
                                LLMMessage(LLMMessage.Role.USER, last.content + "\n\n" + next.content, contentParts = lp + np, dbMessageId = next.dbMessageId)
                            }
                            out[out.lastIndex] = merged
                            continue
                        }
                    }
                    out += next
                }
                ChatRole.ASSISTANT -> out += roundsToLLM(m.rounds, m.id)
            }
        }
        return out
    }

    companion object {
        private val plain = HistoryMapper()
        /** Static helpers kept for existing callers/tests (no attachment shaping). */
        fun toLLMMessages(history: List<ChatMessage>): List<LLMMessage> = plain.toLLMMessages(history)
        fun roundsToLLM(rounds: List<Round>): List<LLMMessage> = plain.roundsToLLM(rounds)
    }
}
