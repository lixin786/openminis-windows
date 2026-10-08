// End-to-end agent loop against mock OpenAI-compatible / Anthropic / Gemini streaming servers,
// exercising the ported upstream provider classes unchanged.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.desktop.agent.AgentEngine
import com.openminis.desktop.core.*
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentEngineTest {
    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun runAgainst(server: MockWebServer, kind: ProviderKind, model: String): Pair<ChatMessage, File> = runBlocking {
        val ws = Files.createTempDirectory("om-ws").toFile()
        val base = server.url("/").toString().trimEnd('/') + if (kind == ProviderKind.GEMINI) "/v1beta" else if (kind == ProviderKind.ANTHROPIC) "" else "/v1"
        val settings = AppSettings(activeProvider = kind, workspaceDir = ws.absolutePath, confirmShellCommands = false)
            .withProvider(kind, ProviderSettings(apiKey = "test-key", baseUrl = base, model = model))
        val reg = ToolRegistry({ settings }, MemoryStore(Files.createTempDirectory("om-mem").toFile())) { true }
        val engine = AgentEngine({ s, ref -> DesktopProviderFactory.create(s, ref) }, reg)
        val updates = mutableListOf<ChatMessage>()
        val msg = engine.runTurn(listOf(ChatMessage(role = ChatRole.USER, text = "write hello.txt")), settings, "You are a test.") { updates += it }
        assertTrue(updates.isNotEmpty())
        msg to ws
    }

    @Test fun openAiCompatibleToolLoop() {
        val server = MockWebServer()
        server.enqueue(sse(
            """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"file_write","arguments":""}}]}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"hello.txt\",\"content\":\"hi from agent\"}"}}]}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ))
        server.enqueue(sse(
            """{"id":"2","choices":[{"index":0,"delta":{"role":"assistant","content":"File **written**."}}]}""",
            """{"id":"2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}""",
            "[DONE]",
        ))
        server.start()
        val (msg, ws) = runAgainst(server, ProviderKind.OPENAI_COMPATIBLE, "test-model")
        assertNull(msg.error, msg.error)
        assertEquals("hi from agent", File(ws, "hello.txt").readText())
        assertEquals(2, msg.rounds.size)
        assertEquals(ToolStatus.DONE, msg.rounds[0].toolCalls.single().status)
        assertEquals("File **written**.", msg.rounds[1].text)
        val first = server.takeRequest()
        assertEquals("/v1/chat/completions", first.path)
        val body = JSONObject(first.body.readUtf8())
        assertTrue(body.getJSONArray("tools").length() >= 5)
        val second = JSONObject(server.takeRequest().body.readUtf8())
        val roles = (0 until second.getJSONArray("messages").length()).map { second.getJSONArray("messages").getJSONObject(it).getString("role") }
        assertTrue(roles.containsAll(listOf("assistant", "tool")), roles.toString())
        server.shutdown()
    }

    @Test fun anthropicToolLoop() {
        val server = MockWebServer()
        fun ev(type: String, json: String) = "event: $type\ndata: $json\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ev("message_start", """{"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","content":[],"model":"claude-haiku-4-5","usage":{"input_tokens":12,"output_tokens":1}}}""") +
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"file_write","input":{}}}""") +
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"hello.txt\",\"content\":\"claude\"}"}}""") +
            ev("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}""") +
            ev("message_stop", """{"type":"message_stop"}""")
        ))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ev("message_start", """{"type":"message_start","message":{"id":"m2","type":"message","role":"assistant","content":[],"model":"claude-haiku-4-5","usage":{"input_tokens":30,"output_tokens":1}}}""") +
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done."}}""") +
            ev("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""") +
            ev("message_stop", """{"type":"message_stop"}""")
        ))
        server.start()
        val (msg, ws) = runAgainst(server, ProviderKind.ANTHROPIC, "claude-haiku-4-5")
        assertNull(msg.error, msg.error)
        assertEquals("claude", File(ws, "hello.txt").readText())
        assertEquals("Done.", msg.rounds.last().text)
        val first = server.takeRequest()
        assertTrue(first.path!!.endsWith("/v1/messages"), first.path)
        // Upstream sends x-api-key to api.anthropic.com and Bearer auth to custom (relay) endpoints like this mock.
        assertTrue(first.getHeader("x-api-key") == "test-key" || first.getHeader("Authorization") == "Bearer test-key")
        server.shutdown()
    }

    @Test fun geminiToolLoop() {
        val server = MockWebServer()
        server.enqueue(sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"file_write","args":{"path":"hello.txt","content":"gemini"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":5}}""",
        ))
        server.enqueue(sse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Saved."}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2}}""",
        ))
        server.start()
        val (msg, ws) = runAgainst(server, ProviderKind.GEMINI, "gemini-2.5-flash")
        assertNull(msg.error, msg.error)
        assertEquals("gemini", File(ws, "hello.txt").readText())
        assertEquals("Saved.", msg.rounds.last().text)
        assertTrue(server.takeRequest().path!!.contains(":streamGenerateContent"))
        server.shutdown()
    }

    @Test fun missingKeyBecomesErrorMessage() = runBlocking {
        val settings = AppSettings(activeProvider = ProviderKind.OPENAI)
        val engine = AgentEngine({ s, ref -> DesktopProviderFactory.create(s, ref) }, ToolRegistry({ settings }, MemoryStore(Files.createTempDirectory("m").toFile())) { false })
        val msg = engine.runTurn(listOf(ChatMessage(role = ChatRole.USER, text = "hi")), settings, "x") {}
        assertNotNull(msg.error); assertTrue(msg.error!!.contains("API key"))
    }
}
