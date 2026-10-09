// Hotfix 1 regressions: UTF-8 everywhere even when the JVM default charset is GBK (the whole
// :desktop:test task runs with -Dfile.encoding=GBK, like a zh-CN Windows JDK 17), proxy
// selection, connect/TLS watchdog, window placement.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.desktop.agent.AgentEngine
import com.openminis.desktop.core.*
import com.openminis.desktop.net.DesktopHttp
import com.openminis.desktop.net.NetworkSettings
import com.openminis.desktop.net.ProxyMode
import com.openminis.desktop.net.ProxyType
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import java.awt.Rectangle
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.test.*

class HotfixEncodingTest {
    private val sample = "2026年10月9日 你好，世界 🎉🙂 Ünïcødé"

    @Test fun testJvmReallyRunsWithGbkDefault() {
        // Guards the regression setup itself (build.gradle.kts: tasks.test jvmArgs -Dfile.encoding=GBK).
        assertEquals("GBK", Charset.defaultCharset().name())
    }

    /** Body as 1-byte chunks so every multi-byte character is split across reads. */
    private fun chunkedSse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setChunkedBody(Buffer().writeUtf8(body), 1)

    private fun run(server: MockWebServer, kind: ProviderKind, model: String): ChatMessage = runBlocking {
        val ws = Files.createTempDirectory("om-ws").toFile()
        val base = server.url("/").toString().trimEnd('/') + when (kind) { ProviderKind.GEMINI -> "/v1beta"; ProviderKind.ANTHROPIC -> ""; else -> "/v1" }
        val settings = AppSettings(activeProvider = kind, workspaceDir = ws.absolutePath, toolsEnabled = false)
            .withProvider(kind, ProviderSettings(apiKey = "k", baseUrl = base, model = model))
        val engine = AgentEngine({ s, ref -> DesktopProviderFactory.create(s, ref) }, ToolRegistry({ settings }, MemoryStore(Files.createTempDirectory("m").toFile())) { true })
        engine.runTurn(listOf(ChatMessage(role = ChatRole.USER, text = "日期？")), settings, "test") {}
    }

    @Test fun openAiStreamIsUtf8UnderGbkDefault() {
        val server = MockWebServer()
        val half = sample.length / 2
        server.enqueue(chunkedSse(
            "data: " + """{"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":${JSONObject.quote(sample.substring(0, half))}}}]}""" + "\n\n" +
            "data: " + """{"id":"x","choices":[{"index":0,"delta":{"content":${JSONObject.quote(sample.substring(half))}},"finish_reason":"stop"}]}""" + "\n\n" +
            "data: [DONE]\n\n"))
        server.start()
        val m = run(server, ProviderKind.OPENAI_COMPATIBLE, "glm-5.2")
        assertNull(m.error, m.error)
        assertEquals(sample, m.displayText)
        server.shutdown()
    }

    @Test fun anthropicStreamIsUtf8UnderGbkDefault() {
        val server = MockWebServer()
        fun ev(t: String, j: String) = "event: $t\ndata: $j\n\n"
        server.enqueue(chunkedSse(
            ev("message_start", """{"type":"message_start","message":{"id":"m","type":"message","role":"assistant","content":[],"model":"claude-haiku-4-5","usage":{"input_tokens":1,"output_tokens":1}}}""") +
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${JSONObject.quote(sample)}}}""") +
            ev("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""") +
            ev("message_stop", """{"type":"message_stop"}""")))
        server.start()
        val m = run(server, ProviderKind.ANTHROPIC, "claude-haiku-4-5")
        assertNull(m.error, m.error)
        assertEquals(sample, m.displayText)
        server.shutdown()
    }

    @Test fun geminiStreamIsUtf8UnderGbkDefault() {
        val server = MockWebServer()
        server.enqueue(chunkedSse("data: " + """{"candidates":[{"content":{"role":"model","parts":[{"text":${JSONObject.quote(sample)}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}}""" + "\n\n"))
        server.start()
        val m = run(server, ProviderKind.GEMINI, "gemini-2.5-flash")
        assertNull(m.error, m.error)
        assertEquals(sample, m.displayText)
        server.shutdown()
    }

    @Test fun processOutputDecoding() {
        assertEquals(sample, TextDecoding.decodeProcessOutput(sample.toByteArray(Charsets.UTF_8)))
        // Legacy code page (cmd's dir / ipconfig on zh-CN) → GBK.
        assertEquals("目录 测试", TextDecoding.decodeProcessOutput("目录 测试".toByteArray(Charset.forName("GBK")), Charset.forName("GBK")))
        // wsl.exe messages are UTF-16LE.
        assertEquals("适用于 Linux 的 Windows 子系统", TextDecoding.decodeProcessOutput("适用于 Linux 的 Windows 子系统".toByteArray(Charsets.UTF_16LE)))
        // Mixed: a UTF-8 line from PowerShell + a GBK line from a native tool.
        val mixed = "你好 utf8\n".toByteArray(Charsets.UTF_8) + "驱动器 C 中的卷\n".toByteArray(Charset.forName("GBK"))
        assertEquals("你好 utf8\n驱动器 C 中的卷\n", TextDecoding.decodeProcessOutput(mixed, Charset.forName("GBK")))
        // A UTF-8 sequence cut by the size cap is dropped, not turned into mojibake.
        val cut = "你好🎉".toByteArray(Charsets.UTF_8).let { it.copyOf(it.size - 2) }
        assertEquals("你好", TextDecoding.decodeProcessOutput(cut))
    }

    @Test fun shellOutputIsDecodedFromBytes() = runBlocking {
        if (AppPaths.isWindows) return@runBlocking
        val ws = Files.createTempDirectory("om-sh").toFile()
        // ASCII-only command (octal escapes) so the test does not depend on how the OS encodes argv;
        // the program writes raw UTF-8 bytes that must be decoded as UTF-8, not the GBK default.
        val r = com.openminis.desktop.tools.ShellRunner.run(ShellKind.POWERSHELL, "printf '\\344\\270\\255\\346\\226\\207\\350\\276\\223\\345\\207\\272 \\360\\237\\216\\211\\n'", ws, 20)
        assertEquals("中文输出 🎉", r.output.trim())
    }

    @Test fun fileToolsKeepChinesePathsAndContent() = runBlocking {
        val ws = Files.createTempDirectory("om-fs").toFile()
        val settings = AppSettings(workspaceDir = ws.absolutePath)
        val reg = ToolRegistry({ settings }, MemoryStore(Files.createTempDirectory("m").toFile())) { true }
        val w = reg.execute("file_write", JSONObject().put("path", "笔记/测试 文件.md").put("content", sample))
        assertFalse(w.isError, w.content)
        assertEquals(sample, java.io.File(ws, "笔记/测试 文件.md").readText(Charsets.UTF_8))
        val r = reg.execute("file_read", JSONObject().put("path", "笔记/测试 文件.md"))
        assertTrue(r.content.contains(sample), r.content)
        java.io.File(ws, "gbk.txt").writeBytes("旧文件".toByteArray(Charset.forName("GBK")))
        TextDecoding.override = Charset.forName("GBK")   // what native.encoding is on zh-CN Windows
        val g = try { reg.execute("file_read", JSONObject().put("path", "gbk.txt")) } finally { TextDecoding.override = null }
        assertTrue(g.content.contains("旧文件") && g.content.contains("GBK"), g.content)
    }

    @Test fun conversationAndSettingsJsonRoundTripChinese() {
        val dir = Files.createTempDirectory("om-cs").toFile()
        val store = ConversationStore(dir)
        store.save(Conversation(id = "c1", title = "标题🎉", messages = listOf(ChatMessage(role = ChatRole.USER, text = sample))))
        val back = store.loadAll().single()
        assertEquals("标题🎉", back.title); assertEquals(sample, back.messages.single().text)
        assertTrue(java.io.File(dir, "c1.json").readBytes().toString(Charsets.UTF_8).contains("标题"))
        val ss = SettingsStore(java.io.File(dir, "settings.json"))
        ss.save(AppSettings(systemPrompt = "用中文回答 🙂"))
        assertEquals("用中文回答 🙂", ss.load().systemPrompt)
    }
}

class HotfixNetworkTest {
    @AfterTest fun reset() { DesktopHttp.update(NetworkSettings()); DesktopHttp.env = System.getenv() }

    @Test fun manualHttpProxyAndBypass() {
        DesktopHttp.update(NetworkSettings(ProxyMode.MANUAL, ProxyType.HTTP, "127.0.0.1", 7900))
        val p = DesktopHttp.select(URI("https://api.example.com/v1")).single()
        assertEquals(Proxy.Type.HTTP, p.type()); assertTrue(p.address().toString().contains("7900"))
        assertEquals(Proxy.NO_PROXY, DesktopHttp.select(URI("http://localhost:11434/v1")).single())
        assertEquals(Proxy.NO_PROXY, DesktopHttp.select(URI("http://127.0.0.1:1234")).single())
        DesktopHttp.update(NetworkSettings(ProxyMode.MANUAL, ProxyType.SOCKS, "10.0.0.2", 1080, bypass = "*.corp.local"))
        assertEquals(Proxy.Type.SOCKS, DesktopHttp.select(URI("https://api.openai.com")).single().type())
        assertEquals(Proxy.NO_PROXY, DesktopHttp.select(URI("https://git.corp.local")).single())
    }

    @Test fun systemModeHonoursEnvVars() {
        DesktopHttp.update(NetworkSettings(ProxyMode.SYSTEM))
        DesktopHttp.env = mapOf("HTTPS_PROXY" to "http://user:pw@127.0.0.1:7900", "NO_PROXY" to "internal.example,localhost")
        val p = DesktopHttp.select(URI("https://gateway.example.com")).single()
        assertTrue(p.address().toString().contains("7900"), p.toString())
        assertEquals(Proxy.NO_PROXY, DesktopHttp.select(URI("https://api.internal.example")).single())
        assertEquals(Proxy.NO_PROXY, DesktopHttp.select(URI("http://localhost:8080")).single())
        DesktopHttp.update(NetworkSettings(ProxyMode.NONE))
        assertEquals(Proxy.NO_PROXY, DesktopHttp.select(URI("https://gateway.example.com")).single())
    }

    @Test fun requestsGoThroughTheManualProxy() {
        // MockWebServer acting as an HTTP proxy for a plain-http target.
        val proxy = MockWebServer()
        proxy.enqueue(MockResponse().setBody("via proxy"))
        proxy.start()
        DesktopHttp.update(NetworkSettings(ProxyMode.MANUAL, ProxyType.HTTP, proxy.hostName, proxy.port, bypass = ""))
        val client = DesktopHttp.newClient()
        client.newCall(okhttp3.Request.Builder().url("http://upstream.invalid/x").build()).execute().use { assertEquals("via proxy", it.body!!.string()) }
        assertEquals("http://upstream.invalid/x", proxy.takeRequest().requestLine.split(' ')[1])
        proxy.shutdown()
    }

    @Test fun stalledTlsHandshakeIsAbortedQuickly() {
        // A server that accepts TCP and never answers the TLS ClientHello (the reported hang).
        val ss = ServerSocket(0)
        val t = Thread { runCatching { while (true) { val s = ss.accept(); Thread { runCatching { s.getInputStream().read(ByteArray(4096)); Thread.sleep(60_000) } }.start() } } }.apply { isDaemon = true; start() }
        DesktopHttp.update(NetworkSettings(ProxyMode.NONE, connectTimeoutSeconds = 3))
        val started = System.currentTimeMillis()
        val e = assertFails {
            DesktopHttp.newClient(readTimeoutSeconds = 600).newCall(okhttp3.Request.Builder().url("https://127.0.0.1:${ss.localPort}/").build()).execute()
        }
        val took = System.currentTimeMillis() - started
        assertTrue(took < 15_000, "took $took ms ($e)")
        assertTrue(DesktopHttp.lastStall!!.second.contains("TLS handshake"), DesktopHttp.lastStall!!.second)
        ss.close(); t.interrupt()
    }
}

class HotfixWindowTest {
    @Test fun defaultIsClampedAndCenteredOnSmallScreen() {
        val p = WindowPlacements.resolve(null, listOf(Rectangle(0, 0, 1280, 760)))
        assertTrue(p.width <= 1280 * 0.92 + 1 && p.height <= 760 * 0.92 + 1, p.toString())
        assertEquals((1280 - p.width) / 2, p.x); assertEquals((760 - p.height) / 2, p.y)
        val big = WindowPlacements.resolve(null, listOf(Rectangle(0, 0, 2560, 1400)))
        assertEquals(WindowPlacements.DEFAULT_W, big.width); assertEquals(WindowPlacements.DEFAULT_H, big.height)
    }

    @Test fun savedPlacementRestoredClampedOrDroppedWhenMonitorGone() {
        val screens = listOf(Rectangle(0, 0, 1920, 1040))
        assertEquals(WindowPlacement(100, 50, 1000, 700), WindowPlacements.resolve(WindowPlacement(100, 50, 1000, 700), screens))
        // Too big for the screen: clamped.
        val c = WindowPlacements.resolve(WindowPlacement(0, 0, 2304, 1534), screens)
        assertTrue(c.width <= 1920 && c.height <= 1040, c.toString())
        // Was on a second monitor that is gone: back to the centered default.
        val gone = WindowPlacements.resolve(WindowPlacement(2500, 100, 1000, 700), screens)
        assertTrue(gone.x in 0..1920 && gone.x + gone.width <= 1920, gone.toString())
    }
}
