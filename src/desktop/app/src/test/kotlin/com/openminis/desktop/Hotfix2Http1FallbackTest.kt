// Hotfix 2 regressions: HTTP/2 stream reset (`stream was reset: CANCEL` right after a 200
// text/event-stream header) → HTTP protocol setting (Auto / HTTP/1.1 only), per-host automatic
// HTTP/1.1 downgrade + immediate retry through the fallback chain, and the reset-origin diagnostic
// (our own call.cancel() vs the peer's RST_STREAM).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.LLMProvider
import com.openminis.desktop.TestSupport.base
import com.openminis.desktop.TestSupport.openAiText
import com.openminis.desktop.TestSupport.user
import com.openminis.desktop.agent.*
import com.openminis.desktop.core.*
import com.openminis.desktop.net.DesktopHttp
import com.openminis.desktop.net.HttpProtocolMode
import com.openminis.desktop.net.NetworkSettings
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Hotfix2Http1FallbackTest {
    @BeforeTest @AfterTest fun reset() { DesktopHttp.update(NetworkSettings()); DesktopHttp.resetHttp1Downgrades(); DesktopHttp.clearFailures() }

    private val reset = StreamResetException(ErrorCode.CANCEL)

    @Test fun detectsStreamResetThroughProviderWrapping() {
        assertTrue(DesktopHttp.isHttp2StreamReset(reset))
        assertTrue(DesktopHttp.isHttp2StreamReset(LLMError.NetworkError(reset)), "providers wrap IOExceptions in NetworkError(cause)")
        assertTrue(DesktopHttp.isHttp2StreamReset(RuntimeException("Stream error", LLMError.NetworkError(reset))))
        assertFalse(DesktopHttp.isHttp2StreamReset(java.net.SocketTimeoutException("timeout")))
        assertFalse(DesktopHttp.isHttp2StreamReset(LLMError.RateLimited()))
    }

    @Test fun defaultIsAutoAndOldSettingsJsonStillLoads() {
        assertEquals(HttpProtocolMode.AUTO, NetworkSettings().httpProtocol)
        val old = """{"proxyMode":"NONE","connectTimeoutSeconds":20}""" // hotfix1 settings.json, no httpProtocol key
        val n = Json { ignoreUnknownKeys = true }.decodeFromString(NetworkSettings.serializer(), old)
        assertEquals(HttpProtocolMode.AUTO, n.httpProtocol)
        val forced = Json.decodeFromString(NetworkSettings.serializer(), Json.encodeToString(NetworkSettings.serializer(), NetworkSettings(httpProtocol = HttpProtocolMode.HTTP1_ONLY)))
        assertEquals(HttpProtocolMode.HTTP1_ONLY, forced.httpProtocol)
    }

    @Test fun http1OnlyModeForcesHttp11OnEveryHardenedClient() {
        val plain = OkHttpClient()
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), DesktopHttp.harden(plain, "https://gw.example.com/v1").protocols)
        DesktopHttp.update(NetworkSettings(httpProtocol = HttpProtocolMode.HTTP1_ONLY))
        assertEquals(listOf(Protocol.HTTP_1_1), DesktopHttp.harden(plain, "https://gw.example.com/v1").protocols)
        assertEquals(listOf(Protocol.HTTP_1_1), DesktopHttp.harden(plain).protocols, "also clients without a base URL (MCP, skill import)")
        assertEquals(listOf(Protocol.HTTP_1_1), DesktopHttp.newClient().protocols)
        assertFalse(DesktopHttp.downgradeToHttp1("https://gw.example.com/v1", "x"), "nothing to downgrade when already HTTP/1.1-only")
    }

    @Test fun autoModeDowngradesOnlyTheHostThatResetOncePerSession() {
        val plain = OkHttpClient()
        assertTrue(DesktopHttp.downgradeToHttp1("https://gw.example.com/v1", "stream was reset: CANCEL"))
        assertFalse(DesktopHttp.downgradeToHttp1("https://GW.example.com/other", "again"), "second reset on the same host: already downgraded")
        assertEquals(listOf(Protocol.HTTP_1_1), DesktopHttp.harden(plain, "https://gw.example.com/v1").protocols)
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), DesktopHttp.harden(plain, "https://api.openai.com/v1").protocols, "other hosts keep HTTP/2")
        assertTrue(DesktopHttp.isHttp1Downgraded("https://gw.example.com/v1/chat/completions"))
        assertFalse(DesktopHttp.downgradeToHttp1(null, "no host"))
    }

    /** A provider that fails like the real-Windows report: HTTP/2 stream reset before any body byte. */
    private class ResettingProvider(override var model: LLMModel) : LLMProvider {
        override val name = "resetting"
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel) = throw UnsupportedOperationException()
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel): Flow<LLMStreamChunk> = flow {
            throw LLMError.NetworkError(StreamResetException(ErrorCode.CANCEL))
        }
    }

    @Test fun agentTurnRetriesOverHttp1ImmediatelyWithoutSpendingRetriesOrFallingBack() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val second = MockWebServer().also { it.start() }
        server.enqueue(openAiText("hello over HTTP/1.1"))
        val ws = TestSupport.tmp("om-ws")
        val s = AppSettings(
            activeProvider = ProviderKind.OPENAI_COMPATIBLE, workspaceDir = ws.absolutePath, toolsEnabled = false,
            fallbackModels = listOf(ModelRef(ProviderKind.OPENAI, "fallback-1")),
        )
            .withProvider(ProviderKind.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k0", baseUrl = base(server), model = "m"))
            .withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k1", baseUrl = base(second)))
        val created = AtomicInteger()
        val http1AtCreation = mutableListOf<Boolean>()
        val engine = AgentEngine(
            { st, ref ->
                http1AtCreation += DesktopHttp.useHttp1(st.provider(ref.provider).effectiveBaseUrl(ref.provider))
                val real = DesktopProviderFactory.create(st, ref)
                if (created.getAndIncrement() == 0) ResettingProvider(real.model) else real
            },
            ToolRegistry({ s }, MemoryStore(TestSupport.tmp("om-mem"))) { true },
            // Huge same-model backoff: the HTTP/1.1 retry must not wait for it.
            retryDelaysMs = listOf(60_000L, 60_000L, 60_000L),
        )
        val msg = withTimeout(20_000) {
            engine.runTurn(listOf(user("hi")), s, "sys", ConversationContextState(), object : TurnListener {})
        }
        assertNull(msg.error, msg.error)
        assertEquals("hello over HTTP/1.1", msg.displayText)
        assertEquals(listOf(false, true), http1AtCreation, "provider rebuilt once, HTTP/1.1-only the second time")
        assertFalse(msg.modelLabel.orEmpty().contains("fallback-1"), "stayed on the primary model: ${msg.modelLabel}")
        assertTrue(msg.notices.single().contains("HTTP/1.1"), msg.notices.toString())
        assertEquals(1, server.requestCount)
        assertEquals(0, second.requestCount, "a protocol problem must not burn the fallback model")
        assertTrue(DesktopHttp.isHttp1Downgraded(base(server)))
        server.shutdown(); second.shutdown()
    }

    @Test fun secondResetOnSameHostFallsBackToNormalRetryPath() = runBlocking {
        val creations = AtomicInteger()
        val chain = FallbackChain(
            AppSettings().withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k", baseUrl = "https://gw.example.com/v1")).copy(activeProvider = ProviderKind.OPENAI),
            { _, _ -> creations.incrementAndGet(); ResettingProvider(LLMModel("m", "m", "T")) },
            retryDelaysMs = listOf(1L),
        )
        chain.current()
        val notices = mutableListOf<String>()
        assertTrue(chain.onFailure(LLMError.NetworkError(reset)))
        chain.current()
        assertEquals(2, creations.get(), "rebuilt after the downgrade")
        assertTrue(chain.onFailure(LLMError.NetworkError(reset)), "then one ordinary transient retry")
        chain.current()
        assertEquals(2, creations.get(), "ordinary retries reuse the provider")
        assertFalse(chain.onFailure(LLMError.NetworkError(reset)), "retry budget spent, no fallback configured")
        assertTrue(notices.isEmpty())
    }

    @Test fun forcedHttp1ModeDoesNotPretendToDowngrade() = runBlocking {
        DesktopHttp.update(NetworkSettings(httpProtocol = HttpProtocolMode.HTTP1_ONLY))
        val notices = mutableListOf<String>()
        val chain = FallbackChain(
            AppSettings().withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k", baseUrl = "https://gw.example.com/v1")).copy(activeProvider = ProviderKind.OPENAI),
            { _, _ -> ResettingProvider(LLMModel("m", "m", "T")) },
            retryDelaysMs = listOf(1L), onNotice = { notices += it },
        )
        chain.current()
        assertTrue(chain.onFailure(LLMError.NetworkError(reset)))
        assertTrue(notices.isEmpty(), "plain transient retry, no HTTP/1.1 notice")
    }

    // ---------------------------------------------------------------- reset-origin diagnostic (real HTTP/2)

    private fun h2Server() = MockWebServer().apply { protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE); start() }
    private fun h2Client() = DesktopHttp.harden(OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).retryOnConnectionFailure(false).readTimeout(10, TimeUnit.SECONDS).build())

    @Test fun localCancelAfterHeadersLooksExactlyLikeTheReportAndIsAttributedLocal() {
        // A local call.cancel() on an HTTP/2 stream makes OkHttp send RST_STREAM(CANCEL) and the body
        // read fails with `stream was reset: CANCEL` — the same text as a peer reset. The diagnostic
        // tells them apart.
        val server = h2Server()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: x\n\n").setBodyDelay(5, TimeUnit.SECONDS))
        val call = h2Client().newCall(Request.Builder().url(server.url("/v1/chat/completions")).build())
        val response = call.execute()
        assertEquals(200, response.code)
        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol)
        Thread { Thread.sleep(300); call.cancel() }.start()
        val e = assertFailsWith<IOException> { response.body!!.source().readUtf8() }
        assertTrue(DesktopHttp.isHttp2StreamReset(e), "${e.javaClass.name}: ${e.message}")
        assertEquals("stream was reset: CANCEL", e.message)
        val diag = DesktopHttp.lastReset?.second.orEmpty()
        assertTrue(diag.contains("reset origin: LOCAL call.cancel()"), diag)
        assertTrue(diag.contains("body bytes=0"), diag)
        response.close(); server.shutdown()
    }

    @Test fun peerResetIsAttributedToThePeer() {
        val server = h2Server()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START).setHttp2ErrorCode(ErrorCode.CANCEL.httpCode))
        val call = h2Client().newCall(Request.Builder().url(server.url("/v1/chat/completions")).build())
        val e = assertFailsWith<IOException> { call.execute().use { it.body!!.string() } }
        assertTrue(DesktopHttp.isHttp2StreamReset(e), "${e.javaClass.name}: ${e.message}")
        val diag = DesktopHttp.lastReset?.second.orEmpty()
        assertTrue(diag.contains("reset origin: PEER"), diag)
        server.shutdown()
    }

    @Test fun watchdogNeverFiresAfterTheRequestIsOnTheWireEvenWithTheMinimumTimeout() {
        // 3 s is the minimum connect/TLS watchdog. A stream that is silent for longer than that AFTER
        // headers must not be cancelled by it.
        DesktopHttp.update(NetworkSettings(connectTimeoutSeconds = 3))
        val server = h2Server()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: late\n\n").setBodyDelay(3500, TimeUnit.MILLISECONDS))
        h2Client().newCall(Request.Builder().url(server.url("/")).build()).execute().use { r ->
            assertEquals("data: late\n\n", r.body!!.string())
        }
        server.shutdown()
    }
}
