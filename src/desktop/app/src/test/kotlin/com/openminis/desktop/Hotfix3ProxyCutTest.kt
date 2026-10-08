// Hotfix 3 regressions: a stream cut after its headers through a proxy (real Windows: Clash on
// 127.0.0.1:7900 cut SSE at ~3.0 s; HTTP/2 showed a PEER reset, HTTP/1.1 an EOFException, curl
// direct streamed fine). Changing protocol does not fix that, so the user is told to go direct, and
// the failure diagnostic (origin / protocol / route / timings) is part of the reportable error text.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.LLMProvider
import com.openminis.desktop.TestSupport.user
import com.openminis.desktop.agent.*
import com.openminis.desktop.core.*
import com.openminis.desktop.net.DesktopHttp
import com.openminis.desktop.net.NetworkSettings
import com.openminis.desktop.net.ProxyMode
import com.openminis.desktop.net.ProxyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.EOFException
import java.io.IOException
import kotlin.test.*

class Hotfix3ProxyCutTest {
    @BeforeTest @AfterTest fun reset() {
        DesktopHttp.update(NetworkSettings()); DesktopHttp.resetHttp1Downgrades(); DesktopHttp.clearFailures()
    }

    /** SSE headers, then the connection drops halfway through a body that carries no event yet. */
    private fun cutStream() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(": keep-alive padding\n".repeat(400))
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    private fun viaProxy(proxy: MockWebServer) =
        DesktopHttp.update(NetworkSettings(ProxyMode.MANUAL, ProxyType.HTTP, "127.0.0.1", proxy.port, bypass = ""))

    @Test fun cutThroughProxyIsRecognisedAndExplained() {
        val proxy = MockWebServer().also { it.start() }
        viaProxy(proxy)
        proxy.enqueue(cutStream())
        val client = DesktopHttp.harden(OkHttpClient())
        assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("http://gw.example.test/v1/chat/completions").build()).execute().use { it.body!!.string() }
        }
        val f = assertNotNull(DesktopHttp.lastFailureFor("http://gw.example.test/v1"))
        assertTrue(f.looksLikeProxyCut, f.text)
        assertTrue(f.text.contains("route=HTTP proxy") && f.text.contains("failure origin: PEER") && f.text.contains("protocol=http/1.1"), f.text)
        val hint = assertNotNull(DesktopHttp.proxyCutHint("http://gw.example.test/v1"))
        assertTrue(hint.contains("Proxy: Direct") && hint.contains("gw.example.test"), hint)
        proxy.shutdown()
    }

    @Test fun sameCutWithoutProxyGivesNoProxyHint() {
        val server = MockWebServer().also { it.start() }
        DesktopHttp.update(NetworkSettings(ProxyMode.NONE))
        server.enqueue(cutStream())
        val url = server.url("/v1/chat/completions")
        assertFailsWith<IOException> { DesktopHttp.harden(OkHttpClient()).newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() } }
        val f = assertNotNull(DesktopHttp.lastFailureFor(url.toString()))
        assertTrue(f.text.contains("route=DIRECT"), f.text)
        assertFalse(f.looksLikeProxyCut)
        assertNull(DesktopHttp.proxyCutHint(url.toString()))
        server.shutdown()
    }

    private class EofProvider(override var model: LLMModel) : LLMProvider {
        override val name = "eof"
        override suspend fun sendMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel) = throw UnsupportedOperationException()
        override fun streamMessageClamped(messages: List<LLMMessage>, systemPrompt: String?, maxTokens: Int, temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>, tools: List<com.openminis.app.data.model.AgentToolDefinition>,
            thinkingLevel: com.openminis.app.data.model.ThinkingLevel): Flow<LLMStreamChunk> = flow { throw LLMError.NetworkError(EOFException("\\n not found: limit=0")) }
    }

    @Test fun chainShowsProxyHintOnceThenRetriesNormally() = runBlocking {
        val notices = mutableListOf<String>()
        val cut = DesktopHttp.StreamFailure(System.currentTimeMillis() + 1, "gw.example.com", "Stream failed …", "HTTP proxy /127.0.0.1:7900",
            afterHeaders = true, bodyComplete = false, reset = false, local = false)
        val chain = FallbackChain(
            AppSettings().withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k", baseUrl = "https://gw.example.com/v1")).copy(activeProvider = ProviderKind.OPENAI),
            { _, _ -> EofProvider(LLMModel("m", "m", "T")) },
            retryDelaysMs = listOf(1L, 1L), onNotice = { notices += it },
            proxyHint = { "HINT for $it" }, failureFor = { cut },
        )
        chain.current()
        assertTrue(chain.onFailure(LLMError.NetworkError(EOFException())))
        assertTrue(chain.onFailure(LLMError.NetworkError(EOFException())))
        assertEquals(listOf("HINT for https://gw.example.com/v1"), notices, "once per chain")
        assertFalse(chain.onFailure(LLMError.NetworkError(EOFException())), "retry budget still applies")
    }

    @Test fun noHintForNonNetworkErrorsOrStaleFailures() = runBlocking {
        val notices = mutableListOf<String>()
        val stale = DesktopHttp.StreamFailure(System.currentTimeMillis() - 60_000, "gw.example.com", "old", "HTTP proxy /127.0.0.1:7900",
            afterHeaders = true, bodyComplete = false, reset = false, local = false)
        val chain = FallbackChain(
            AppSettings().withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "k", baseUrl = "https://gw.example.com/v1")).copy(activeProvider = ProviderKind.OPENAI),
            { _, _ -> EofProvider(LLMModel("m", "m", "T")) },
            retryDelaysMs = listOf(1L, 1L), onNotice = { notices += it },
            proxyHint = { "HINT" }, failureFor = { stale },
        )
        chain.current()
        chain.onFailure(LLMError.NetworkError(EOFException()))
        chain.onFailure(LLMError.RateLimited())
        assertTrue(notices.none { it == "HINT" }, notices.toString())
    }

    @Test fun agentTurnThroughCuttingProxyReportsDiagnosticAndHint() = runBlocking {
        val proxy = MockWebServer().also { it.start() }
        viaProxy(proxy)
        repeat(4) { proxy.enqueue(cutStream()) }
        val ws = TestSupport.tmp("om-ws")
        val s = AppSettings(activeProvider = ProviderKind.OPENAI_COMPATIBLE, workspaceDir = ws.absolutePath, toolsEnabled = false)
            .withProvider(ProviderKind.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k", baseUrl = "http://gw.example.test/v1", model = "m"))
        val msg = TestSupport.engine({ s }).runTurn(listOf(user("hi")), s, "sys", ConversationContextState(), object : TurnListener {})
        val err = assertNotNull(msg.error)
        assertTrue(err.contains("failure origin: PEER") && err.contains("route=HTTP proxy"), err)
        assertTrue(err.contains("Proxy: Direct"), err)
        assertEquals(1, msg.notices.count { it.contains("Proxy: Direct") }, msg.notices.toString())
        assertEquals(4, proxy.requestCount, "1 try + 3 same-model retries")
        proxy.shutdown()
    }
}
