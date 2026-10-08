// Context compaction: the ported upstream algorithms + the AgentEngine orchestration end to end.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.LLMProvider
import com.openminis.desktop.TestSupport.allText
import com.openminis.desktop.TestSupport.assistant
import com.openminis.desktop.TestSupport.body
import com.openminis.desktop.TestSupport.openAiError
import com.openminis.desktop.TestSupport.openAiText
import com.openminis.desktop.TestSupport.settingsFor
import com.openminis.desktop.TestSupport.user
import com.openminis.desktop.agent.*
import com.openminis.desktop.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CompactionTest {
    private fun big(tag: String, chars: Int = 40_000) = "$tag " + "lorem ipsum ".repeat(chars / 12)

    private fun um(text: String, id: String) = LLMMessage(LLMMessage.Role.USER, text, dbMessageId = id)
    private fun am(text: String, id: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text, dbMessageId = id)

    @Test fun planStartsAfterPreviousAnchor() {
        val h = listOf(um("a", "u1"), am("b", "a1#0"), um("c", "u2"), am("d", "a2#0"))
        val first = Compaction.plan(h, null)!!
        assertEquals(0, first.startIdx); assertEquals("a2#0", first.anchorId); assertEquals(4, first.messages.size)
        val prev = CompactMarker(summary = "s", lastCompactedMessageId = "a1#0", compactedCount = 2)
        val second = Compaction.plan(h, prev)!!
        assertEquals(2, second.startIdx); assertEquals(listOf("u2", "a2#0"), second.messages.map { it.dbMessageId })
        assertNull(Compaction.plan(h, prev.copy(lastCompactedMessageId = "a2#0")), "nothing new to compact")
        // An explicit anchor (pre-send compaction) stops before the new user message.
        assertEquals("u2", Compaction.plan(h, null, anchorIdxOverride = 2)!!.anchorId)
    }

    @Test fun effectiveHistoryInlinesSummaryIntoNextUserTurn() {
        val h = listOf(um("q1", "u1"), am("r1", "a1#0"), um("q2", "u2"), am("r2", "a2#0"), um("NEW", "u3"))
        val marker = CompactMarker(summary = "SUMMARY-TEXT", lastCompactedMessageId = "a2#0", compactedCount = 4)
        val eff = Compaction.effectiveHistory(h, marker)
        // warm-up keeps the recent user-text turns before the anchor, then the injected new turn.
        assertEquals(listOf("u1", "a1#0", "u2", "a2#0", "u3"), eff.map { it.dbMessageId })
        assertTrue(eff.last().content.startsWith("<context-summary>"))
        assertTrue(eff.last().content.contains("SUMMARY-TEXT") && eff.last().content.endsWith("NEW"))
        // No user message after the anchor: the summary becomes its own trailing user turn.
        val tail = Compaction.effectiveHistory(h.dropLast(1), marker)
        assertEquals(LLMMessage.Role.USER, tail.last().role); assertTrue(tail.last().content.contains("SUMMARY-TEXT"))
        // Unresolvable anchor → full history (upstream: over-inform beats summary-only).
        assertEquals(h, Compaction.effectiveHistory(h, marker.copy(lastCompactedMessageId = "gone")))
    }

    @Test fun warmUpKeepsThreeUserTurnsAndPrunesBigToolResults() {
        val bigResult = "x".repeat(5000)
        val h = mutableListOf<LLMMessage>()
        for (i in 1..5) { h += um("q$i", "u$i"); h += am("r$i", "a$i#0") }
        h += LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("t1", "shell", JSONObject())), dbMessageId = "a5#1")
        h += LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("t1", "shell", bigResult)), dbMessageId = "a5#1")
        val eff = Compaction.effectiveHistory(h, CompactMarker(summary = "S", lastCompactedMessageId = "a5#1", compactedCount = 10))
        val ids = eff.mapNotNull { it.dbMessageId }
        assertEquals("u3", ids.first(), "last ${Compaction.KEEP_RECENT_USER_TURNS} user-text turns kept")
        assertTrue(eff.none { m -> m.contentParts.any { it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse } },
            "a >1000-char tool result and its paired tool_use are pruned from the warm-up")
    }

    @Test fun warmUpTrimmedToBudget() {
        val h = listOf(um(big("A"), "u1"), am("r", "a1#0"), um(big("B"), "u2"), am("r", "a2#0"), um("NEW", "u3"))
        val m = CompactMarker(summary = "S", lastCompactedMessageId = "a2#0", compactedCount = 4)
        val untrimmed = Compaction.effectiveHistory(h, m, warmUpBudget = null)
        val trimmed = Compaction.effectiveHistory(h, m, warmUpBudget = 12_000)
        assertTrue(trimmed.size < untrimmed.size)
        assertEquals("u2", trimmed.first().dbMessageId, "oldest warm-up turn dropped first, whole turns only")
    }

    private class FakeProvider(val fail: (String) -> Throwable?, val answer: String = "PART") : LLMProvider {
        val calls = AtomicInteger()
        override val name = "fake"
        override var model = LLMModel("fake", "fake", "Test", contextWindow = 100_000)
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel) = throw UnsupportedOperationException()
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel): Flow<LLMStreamChunk> = flow {
            calls.incrementAndGet()
            fail(messages.single().content)?.let { throw it }
            emit(LLMStreamChunk.Text(answer + calls.get()))
        }
    }

    @Test fun summarySplitsOnSizeErrorAndJoinsHalves() = runBlocking {
        // The whole transcript is "too long"; each half fits.
        val p = FakeProvider({ t -> if (t.contains("m0") && t.contains("m3")) LLMError.ProviderError("[400] prompt is too long", 400) else null })
        val msgs = (0..3).map { um("m$it", "u$it") }
        val s = Compaction.summarizeWithSplitting(p, 100_000, msgs, null)
        assertEquals(3, p.calls.get())
        assertEquals("PART2\n\nPART3", s)
    }

    @Test fun summaryDoesNotSplitOnRateLimit() = runBlocking {
        val p = FakeProvider({ LLMError.RateLimited() })
        assertFailsWith<LLMError.RateLimited> { Compaction.summarizeWithSplitting(p, 100_000, (0..3).map { um("m$it", "u$it") }, null) }
        assertEquals(1, p.calls.get(), "a 429 is not a size problem — no halved retries")
    }

    @Test fun autoCompactionBeforeSendNearTheWindow() = runBlocking {
        val server = MockWebServer()
        server.enqueue(openAiText("User sent lorem ipsum blocks A–D and the assistant acknowledged."))
        server.enqueue(openAiText("final answer"))
        server.start()
        // 40k user cap → ContextPolicy.forUserCap: compact at 34k.
        val s = settingsFor(ProviderKind.OPENAI_COMPATIBLE, server).copy(contextWindowOverride = 40_000)
        val history = mutableListOf<ChatMessage>()
        for (t in listOf("A", "B", "C", "D")) { history += user(big(t)); history += assistant("ack $t") }
        val lastAssistantId = history.last().id
        history += user("NEW QUESTION")
        val ctx = ConversationContextState()
        val compacted = mutableListOf<CompactMarker>()
        val readings = mutableListOf<ContextReading>()
        val msg = TestSupport.engine({ s }).runTurn(history, s, "sys", ctx, object : TurnListener {
            override fun onCompacted(marker: CompactMarker) { compacted += marker }
            override fun onContext(reading: ContextReading) { readings += reading }
        })
        assertNull(msg.error, msg.error)
        assertEquals("final answer", msg.displayText)
        assertEquals(1, compacted.size)
        assertEquals("$lastAssistantId#0", compacted.single().lastCompactedMessageId, "pre-send compaction stops before the new user message")
        assertTrue(compacted.single().automatic)
        assertTrue(msg.notices.any { it.contains("compacted automatically") }, msg.notices.toString())
        val summaryReq = body(server.takeRequest())
        assertFalse(summaryReq.has("tools"))
        assertTrue(allText(summaryReq).contains("Compact this conversation into a context summary"))
        val turnReq = allText(body(server.takeRequest()))
        assertTrue(turnReq.contains("<context-summary>") && turnReq.contains("blocks A–D"), "summary sent with the next request")
        assertTrue(turnReq.contains("NEW QUESTION"))
        assertFalse(turnReq.contains("A lorem ipsum"), "oldest compacted turn no longer sent verbatim")
        assertTrue(readings.first().measuredTokens > readings.last().measuredTokens)
        server.shutdown()
    }

    @Test fun midTurnCompactionAfterAHugeToolResult() = runBlocking {
        val server = MockWebServer()
        server.enqueue(TestSupport.sse(
            """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"file_read","arguments":"{\"path\":\"big.txt\"}"}}]}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ))
        server.enqueue(openAiText("SUMMARY: read big.txt"))
        server.enqueue(openAiText("big.txt is lorem ipsum"))
        server.start()
        val ws = TestSupport.tmp("om-ws")
        java.io.File(ws, "big.txt").writeText((1..1500).joinToString("\n") { "line $it lorem ipsum dolor sit amet consectetur adipiscing" })
        val s = settingsFor(ProviderKind.OPENAI_COMPATIBLE, server, ws = ws).copy(toolsEnabled = true, contextWindowOverride = 20_000)
        val ctx = ConversationContextState()
        val msg = TestSupport.engine({ s }).runTurn(listOf(user("summarise big.txt")), s, "sys", ctx, object : TurnListener {})
        assertNull(msg.error, msg.error)
        assertEquals(3, server.requestCount)
        assertEquals("${msg.id}#0", ctx.markers.single().lastCompactedMessageId, "anchored on the finished tool round")
        assertEquals(ToolStatus.DONE, msg.rounds[0].toolCalls.single().status)
        assertEquals("big.txt is lorem ipsum", msg.rounds.last().text)
        server.takeRequest(); server.takeRequest()
        val last = allText(body(server.takeRequest()))
        assertTrue(last.contains("<context-summary>") && !last.contains("line 1499 lorem"), "the huge tool result was folded into the summary")
        server.shutdown()
    }

    @Test fun manualCompactThenNextTurnCarriesSummary() = runBlocking {
        val server = MockWebServer()
        server.enqueue(openAiText("SUMMARY: talked about cats"))
        server.enqueue(openAiText("next reply"))
        server.start()
        val s = settingsFor(ProviderKind.OPENAI_COMPATIBLE, server)
        val history = mutableListOf(user("tell me about cats"), assistant("cats are great"))
        val ctx = ConversationContextState()
        val engine = TestSupport.engine({ s })
        val out = engine.compact(history, s, ctx)
        assertTrue(out is CompactOutcome.Done, out.toString())
        assertEquals(2, (out as CompactOutcome.Done).marker.compactedCount)
        assertTrue(engine.compact(history, s, ctx) is CompactOutcome.Skipped, "second compact has nothing new")
        history += user("and dogs?")
        val msg = engine.runTurn(history, s, "sys", ctx, object : TurnListener {})
        assertEquals("next reply", msg.displayText)
        server.takeRequest()
        val sent = body(server.takeRequest()).getJSONArray("messages")
        val last = sent.getJSONObject(sent.length() - 1).getString("content")
        assertTrue(last.startsWith("<context-summary>") && last.contains("talked about cats") && last.endsWith("and dogs?"), last)
        server.shutdown()
    }

    @Test fun contextOverflowRejectionRaisesRatioCompactsAndRetries() = runBlocking {
        val server = MockWebServer()
        server.enqueue(openAiError(400, "This model's maximum context length is 20000 tokens. However, you requested 20500 tokens.", "context_length_exceeded"))
        server.enqueue(openAiText("SUMMARY of old turns"))
        server.enqueue(openAiText("recovered"))
        server.start()
        val s = settingsFor(ProviderKind.OPENAI_COMPATIBLE, server).copy(contextWindowOverride = 20_000)
        val history = mutableListOf<ChatMessage>()
        for (t in listOf("A", "B")) { history += user(big(t, 14_000)); history += assistant("ok $t") }
        history += user("go on")
        val ctx = ConversationContextState()
        val msg = TestSupport.engine({ s }).runTurn(history, s, "sys", ctx, object : TurnListener {})
        assertNull(msg.error, msg.error)
        assertEquals("recovered", msg.displayText)
        assertEquals(3, server.requestCount)
        assertEquals(1, ctx.markers.size)
        assertTrue(ctx.ratios.values.single() > 1.0, "overflow taught the meter this request was bigger than estimated")
        server.shutdown()
    }

    @Test fun calibrationLearnsFromReportedUsage() = runBlocking {
        val server = MockWebServer()
        server.enqueue(openAiText("hi", promptTokens = 400))
        server.start()
        val s = settingsFor(ProviderKind.OPENAI_COMPATIBLE, server)
        val ctx = ConversationContextState()
        TestSupport.engine({ s }).runTurn(listOf(user("hello there")), s, "sys", ctx, object : TurnListener {})
        assertEquals(setOf("test-model"), ctx.ratios.keys)
        assertTrue(ctx.ratios["test-model"]!! > 1.0)
        server.shutdown()
    }

    @Test fun markersAndRatiosPersistWithConversation() {
        val c = Conversation(
            messages = listOf(user("x"), assistant("y")),
            compactMarkers = listOf(CompactMarker(summary = "S", lastCompactedMessageId = "m#0", compactedCount = 2, automatic = true)),
            contextRatios = mapOf("gpt" to 1.3),
        )
        val dir = TestSupport.tmp()
        ConversationStore(dir).save(c)
        val back = ConversationStore(dir).loadAll().single()
        assertEquals(c.compactMarkers, back.compactMarkers)
        assertEquals(1.3, back.contextRatios["gpt"])
        // Old files (no new fields) still load.
        val legacy = """{"id":"z","title":"t","messages":[{"id":"1","role":"USER","text":"hi"}]}"""
        val j = Json { ignoreUnknownKeys = true }
        assertTrue(j.decodeFromString(Conversation.serializer(), legacy).compactMarkers.isEmpty())
    }

    @Test fun historyMapperStampsAnchorIds() {
        val a = ChatMessage(role = ChatRole.ASSISTANT, rounds = listOf(
            Round(text = "t", toolCalls = listOf(ToolCall("c1", "shell", "{}", result = "ok", status = ToolStatus.DONE))), Round(text = "done")))
        val u = user("q")
        val msgs = HistoryMapper.toLLMMessages(listOf(u, a))
        assertEquals(listOf(u.id, "${a.id}#0", "${a.id}#0", "${a.id}#1"), msgs.map { it.dbMessageId })
    }
}
