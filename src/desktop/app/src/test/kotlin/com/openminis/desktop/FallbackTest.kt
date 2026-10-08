// Model fallback chain against fake servers: 429 / 5xx / overloaded / network / auth, ordering,
// exhaustion, compaction through the chain.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.LLMError
import com.openminis.desktop.TestSupport.base
import com.openminis.desktop.TestSupport.openAiError
import com.openminis.desktop.TestSupport.openAiText
import com.openminis.desktop.TestSupport.user
import com.openminis.desktop.agent.*
import com.openminis.desktop.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.*

class FallbackTest {
    private val ws = TestSupport.tmp("om-ws")

    /** Primary = OpenAI-compatible on [primary]; fallbacks = OpenAI on [second] (+ DeepSeek on [third]). */
    private fun chainSettings(primary: String, second: MockWebServer, third: MockWebServer? = null, always: Boolean = false): AppSettings {
        var s = AppSettings(
            activeProvider = ProviderKind.OPENAI_COMPATIBLE, workspaceDir = ws.absolutePath, toolsEnabled = false,
            fallbackModels = listOfNotNull(ModelRef(ProviderKind.OPENAI, "fallback-1"), third?.let { ModelRef(ProviderKind.DEEPSEEK, "fallback-2") }),
            fallbackOnAnyError = always,
        )
            .withProvider(ProviderKind.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k0", baseUrl = primary, model = "primary-model"))
            .withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k1", baseUrl = base(second)))
        if (third != null) s = s.withProvider(ProviderKind.DEEPSEEK, ProviderSettings(apiKey = "k2", baseUrl = base(third)))
        return s
    }

    private fun run(s: AppSettings): ChatMessage = runBlocking {
        TestSupport.engine({ s }).runTurn(listOf(user("hi")), s, "sys", ConversationContextState(), object : TurnListener {})
    }

    private fun servers(n: Int) = List(n) { MockWebServer().also { it.start() } }

    @Test fun rateLimitedPrimaryFallsBackToNextModel() {
        val (a, b) = servers(2)
        a.enqueue(openAiError(429, "slow down", "rate_limit_exceeded"))
        b.enqueue(openAiText("from fallback"))
        val msg = run(chainSettings(base(a), b))
        assertNull(msg.error, msg.error)
        assertEquals("from fallback", msg.displayText)
        assertEquals("OpenAI · fallback-1", msg.modelLabel)
        assertTrue(msg.notices.single().contains("Rate limited") && msg.notices.single().contains("Switched to OpenAI · fallback-1"), msg.notices.toString())
        assertEquals(1, a.requestCount)
        assertEquals("fallback-1", TestSupport.body(b.takeRequest()).getString("model"))
        a.shutdown(); b.shutdown()
    }

    @Test fun serverErrorRetriesSameModelThenFallsBack() {
        val (a, b) = servers(2)
        repeat(4) { a.enqueue(openAiError(503, "upstream unavailable", "server_error")) }
        b.enqueue(openAiText("ok"))
        val msg = run(chainSettings(base(a), b))
        assertEquals("ok", msg.displayText)
        assertEquals(4, a.requestCount, "1 try + 3 same-model retries (upstream AUTO_RETRY_DELAYS_SEC) before switching")
        a.shutdown(); b.shutdown()
    }

    @Test fun transient5xxRecoversOnSameModelWithoutSwitching() {
        val (a, b) = servers(2)
        a.enqueue(openAiError(502, "bad gateway", "server_error"))
        a.enqueue(openAiText("same model"))
        val msg = run(chainSettings(base(a), b))
        assertEquals("same model", msg.displayText)
        assertTrue(msg.notices.isEmpty())
        assertEquals(0, b.requestCount)
        a.shutdown(); b.shutdown()
    }

    @Test fun anthropicOverloadedFallsBack() {
        val (a, b) = servers(2)
        repeat(4) { a.enqueue(MockResponse().setResponseCode(529).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")) }
        b.enqueue(openAiText("rescued"))
        val s = AppSettings(activeProvider = ProviderKind.ANTHROPIC, workspaceDir = ws.absolutePath, toolsEnabled = false,
            fallbackModels = listOf(ModelRef(ProviderKind.OPENAI, "gpt-x")))
            .withProvider(ProviderKind.ANTHROPIC, ProviderSettings(apiKey = "ka", baseUrl = a.url("/").toString().trimEnd('/'), model = "claude-haiku-4-5"))
            .withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k1", baseUrl = base(b)))
        val msg = run(s)
        assertEquals("rescued", msg.displayText, msg.error)
        assertTrue(msg.notices.single().contains("Anthropic"))
        a.shutdown(); b.shutdown()
    }

    @Test fun networkErrorFallsBackAfterRetries() {
        val dead = MockWebServer().also { it.start() }
        val deadUrl = base(dead); dead.shutdown() // connection refused from now on
        val b = MockWebServer().also { it.start() }
        b.enqueue(openAiText("network rescue"))
        val msg = run(chainSettings(deadUrl, b))
        assertEquals("network rescue", msg.displayText, msg.error)
        assertTrue(msg.notices.single().contains("Switched"))
        b.shutdown()
    }

    @Test fun midStreamDisconnectIsRetried() {
        val (a, b) = servers(2)
        a.enqueue(TestSupport.openAiText("partial").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        a.enqueue(openAiText("whole"))
        val msg = run(chainSettings(base(a), b))
        assertEquals("whole", msg.displayText, msg.error)
        a.shutdown(); b.shutdown()
    }

    @Test fun invalidKeyOnPrimaryFallsBack() {
        val (a, b) = servers(2)
        a.enqueue(openAiError(401, "bad key", "invalid_api_key"))
        b.enqueue(openAiText("other key works"))
        assertEquals("other key works", run(chainSettings(base(a), b)).displayText)
        a.shutdown(); b.shutdown()
    }

    @Test fun chainOrderIsRespectedAcrossThreeModels() {
        val (a, b, c) = servers(3)
        a.enqueue(openAiError(429, "r", "rate_limit_exceeded"))
        b.enqueue(openAiError(429, "r", "rate_limit_exceeded"))
        c.enqueue(openAiText("third"))
        val msg = run(chainSettings(base(a), b, c))
        assertEquals("third", msg.displayText)
        assertEquals(2, msg.notices.size)
        assertEquals(listOf(1, 1, 1), listOf(a.requestCount, b.requestCount, c.requestCount))
        a.shutdown(); b.shutdown(); c.shutdown()
    }

    @Test fun exhaustedChainSurfacesTheLastError() {
        val (a, b) = servers(2)
        a.enqueue(openAiError(429, "r", "rate_limit_exceeded"))
        b.enqueue(openAiError(429, "r", "rate_limit_exceeded"))
        val msg = run(chainSettings(base(a), b))
        assertNotNull(msg.error); assertTrue(msg.error!!.contains("Rate limited"), msg.error)
        a.shutdown(); b.shutdown()
    }

    @Test fun plainBadRequestFallsBackLikeUpstream() {
        // Upstream [T-android-fallback-providererror]: ProviderError (400/404/…) is isFallbackable.
        val (a, b) = servers(2)
        a.enqueue(openAiError(400, "unknown parameter foo", "invalid_request_error"))
        b.enqueue(openAiText("served by fallback"))
        assertEquals("served by fallback", run(chainSettings(base(a), b)).displayText)
        a.shutdown(); b.shutdown()
    }

    @Test fun missingKeyPrimarySkipsToConfiguredFallback() {
        val b = MockWebServer().also { it.start() }
        b.enqueue(openAiText("fallback had a key"))
        val s = AppSettings(activeProvider = ProviderKind.ANTHROPIC, workspaceDir = ws.absolutePath, toolsEnabled = false,
            fallbackModels = listOf(ModelRef(ProviderKind.OPENAI, "gpt-x")))
            .withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k1", baseUrl = base(b)))
        assertEquals("fallback had a key", run(s).displayText)
        b.shutdown()
    }

    @Test fun fallbackWithoutKeyIsSkippedAndReported() {
        val a = MockWebServer().also { it.start() }
        a.enqueue(openAiError(429, "r", "rate_limit_exceeded"))
        val s = AppSettings(activeProvider = ProviderKind.OPENAI_COMPATIBLE, workspaceDir = ws.absolutePath, toolsEnabled = false,
            fallbackModels = listOf(ModelRef(ProviderKind.GEMINI, "gemini-2.5-flash")))
            .withProvider(ProviderKind.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k0", baseUrl = base(a), model = "m"))
        val msg = run(s)
        assertNotNull(msg.error)
        assertTrue(msg.error!!.contains("Fallback models skipped") && msg.error!!.contains("Google Gemini"), msg.error)
        a.shutdown()
    }

    @Test fun compactionUsesTheChainToo() = runBlocking {
        val (a, b) = servers(2)
        a.enqueue(openAiError(429, "r", "rate_limit_exceeded"))
        b.enqueue(openAiText("SUMMARY from fallback"))
        val s = chainSettings(base(a), b)
        val ctx = ConversationContextState()
        val out = TestSupport.engine({ s }).compact(listOf(user("q"), TestSupport.assistant("a")), s, ctx)
        assertTrue(out is CompactOutcome.Done, out.toString())
        assertEquals("OpenAI · fallback-1", (out as CompactOutcome.Done).marker.model)
        a.shutdown(); b.shutdown()
    }

    @Test fun policyClassification() {
        assertTrue(FallbackPolicy.shouldFallback(LLMError.RateLimited(), false))
        assertTrue(FallbackPolicy.shouldFallback(LLMError.TransientError("[503] x", 503), false))
        assertTrue(FallbackPolicy.shouldFallback(LLMError.TransientError("[overloaded_error] x", 529), false))
        assertTrue(FallbackPolicy.shouldFallback(LLMError.NetworkError(java.io.IOException("refused")), false))
        assertTrue(FallbackPolicy.shouldFallback(LLMError.InvalidApiKey(), false))
        assertFalse(FallbackPolicy.shouldFallback(LLMError.Cancelled(), true))
        assertFalse(FallbackPolicy.shouldFallback(LLMError.DecodingError(RuntimeException("x")), false))
        assertTrue(FallbackPolicy.shouldFallback(LLMError.DecodingError(RuntimeException("x")), true), "FallbackStrategy.always")
        assertTrue(FallbackPolicy.isTransient(LLMError.TransientError("t")))
        assertFalse(FallbackPolicy.isTransient(LLMError.RateLimited()))
        val wrapped = RuntimeException("flow", LLMError.RateLimited())
        assertTrue(FallbackPolicy.unwrap(wrapped) is LLMError.RateLimited)
    }

    @Test fun settingsChainDeduplicatesPrimary() {
        val s = AppSettings(activeProvider = ProviderKind.OPENAI, fallbackModels = listOf(ModelRef(ProviderKind.OPENAI, "gpt-4o-mini"), ModelRef(ProviderKind.XAI, "grok-4")))
        assertEquals(listOf(ModelRef(ProviderKind.OPENAI, "gpt-4o-mini"), ModelRef(ProviderKind.XAI, "grok-4")), s.modelChain())
    }
}
