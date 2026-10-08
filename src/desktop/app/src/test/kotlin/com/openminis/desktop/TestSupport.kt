// Shared helpers for the desktop orchestration tests (mock SSE servers, settings builders).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.desktop.agent.AgentEngine
import com.openminis.desktop.core.*
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

object TestSupport {
    fun tmp(prefix: String = "om-t"): File = Files.createTempDirectory(prefix).toFile()

    fun sse(vararg events: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    /** An OpenAI Chat Completions stream that answers [text] (with usage). */
    fun openAiText(text: String, promptTokens: Int = 10): MockResponse = sse(
        """{"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":${JSONObject.quote(text)}}}]}""",
        """{"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":3,"total_tokens":${promptTokens + 3}}}""",
        "[DONE]",
    )

    fun openAiError(code: Int, message: String, type: String = "error"): MockResponse = MockResponse()
        .setResponseCode(code).setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"message":${JSONObject.quote(message)},"type":"$type","code":"$type"}}""")

    fun base(server: MockWebServer) = server.url("/").toString().trimEnd('/') + "/v1"

    fun settingsFor(
        kind: ProviderKind, server: MockWebServer, model: String = "test-model",
        ws: File = tmp("om-ws"),
    ): AppSettings = AppSettings(activeProvider = kind, workspaceDir = ws.absolutePath, confirmShellCommands = false, toolsEnabled = false)
        .withProvider(kind, ProviderSettings(apiKey = "test-key", baseUrl = base(server), model = model))

    fun engine(
        settings: () -> AppSettings,
        shaper: AttachmentShaper? = null,
        delays: List<Long> = listOf(1, 1, 1),
    ): AgentEngine = AgentEngine(
        { s, ref -> DesktopProviderFactory.create(s, ref) },
        ToolRegistry(settings, MemoryStore(tmp("om-mem"))) { true },
        shaper, retryDelaysMs = delays, compactIdleTimeoutMs = 10_000,
    )

    fun body(r: RecordedRequest): JSONObject = JSONObject(r.body.readUtf8())

    /** Concatenated text of every message in an OpenAI chat request body. */
    fun allText(body: JSONObject): String = buildString {
        val msgs = body.getJSONArray("messages")
        for (i in 0 until msgs.length()) append(msgs.getJSONObject(i).opt("content")?.toString()).append('\n')
    }

    fun user(text: String, vararg atts: Attachment) = ChatMessage(role = ChatRole.USER, text = text, attachments = atts.toList())
    fun assistant(text: String) = ChatMessage(role = ChatRole.ASSISTANT, rounds = listOf(Round(text = text)))
}
