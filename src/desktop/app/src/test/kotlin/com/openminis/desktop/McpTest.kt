// P1-1 MCP stdio: real child process (FakeMcpServer in its own JVM) — handshake, paged tools/list,
// calls, errors, timeout, crash restart, UTF-8, env expansion, launch confirmation, agent turn.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.JsonSchemaCompat
import com.openminis.desktop.TestSupport.base
import com.openminis.desktop.TestSupport.openAiText
import com.openminis.desktop.TestSupport.user
import com.openminis.desktop.agent.*
import com.openminis.desktop.core.*
import com.openminis.desktop.mcp.*
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.io.File
import java.util.Collections
import kotlin.test.*

class McpTest {
    private val dir = TestSupport.tmp("om-mcp")
    private val counter = File(dir, "launches.txt")
    private val java = ProcessHandle.current().info().command().orElse("java")
    private val cp = System.getProperty("java.class.path")
    private val managers = mutableListOf<McpManager>()

    @AfterTest fun cleanup() { managers.forEach { it.shutdown() } }

    private fun fake(name: String = "fake", vararg extra: Pair<String, String>, callTimeout: Int? = null, confirm: Boolean = false) = McpServerConfig(
        name = name, command = java, args = listOf("-Dfile.encoding=GBK", "-cp", cp, "com.openminis.desktop.FakeMcpServer", counter.absolutePath),
        env = extra.toMap(), callTimeoutSeconds = callTimeout, startupTimeoutSeconds = 60, confirmToolCalls = confirm,
    )

    private fun launches() = if (counter.exists()) counter.readText().length else 0

    private fun manager(
        servers: List<McpServerConfig>, approvals: MutableList<ApprovalRequest> = Collections.synchronizedList(mutableListOf()),
        allow: Boolean = true, storeDir: File = dir, baseEnv: Map<String, String> = System.getenv(),
    ): McpManager {
        val store = McpStore(File(storeDir, "mcp_servers.json"), File(storeDir, "mcp_trust.json"))
        val m = McpManager(store, ToolApprover { approvals += it; allow }, { dir }, isWindows = false, baseEnv = { baseEnv }, restartBackoffMs = listOf(10L, 10L))
        if (servers.isNotEmpty()) m.setServers(servers)
        managers += m
        return m
    }

    @Test fun handshakePagedToolsAndCallsWithUtf8() = runBlocking {
        val m = manager(listOf(fake()))
        assertTrue(m.prepare().isEmpty)
        assertEquals(listOf("echo", "fail", "slow", "crash", "env", "image"), m.tools("fake").map { it.name }, "tools/list followed nextCursor")
        assertEquals("mcp__fake__echo", m.tools("fake").first().exposedName)
        val sample = "你好，世界 🎉 Ünïcødé\n第二行"
        val out = m.call("mcp__fake__echo", JSONObject().put("text", sample))
        assertFalse(out.isError, out.content)
        assertEquals("echo: $sample", out.content, "UTF-8 both ways even though the child JVM runs with GBK default")
        val fail = m.call("mcp__fake__fail", JSONObject())
        assertTrue(fail.isError); assertEquals("boom", fail.content)
        val img = m.call("mcp__fake__image", JSONObject())
        assertTrue(img.content.contains("[image image/png") && img.content.contains("caption"), img.content)
        assertEquals("running", m.status("fake").state)
        assertEquals(1, launches())
    }

    @Test fun callTimeoutIsReportedAndServerKeepsWorking() = runBlocking {
        val m = manager(listOf(fake(callTimeout = 1)))
        m.prepare()
        val t0 = System.currentTimeMillis()
        val out = m.call("mcp__fake__slow", JSONObject())
        assertTrue(out.isError && out.content.contains("did not answer tools/call"), out.content)
        assertTrue(System.currentTimeMillis() - t0 < 4_000)
        // The fake handles one request at a time: once its 5 s sleep is over the same process answers again.
        kotlinx.coroutines.delay(4_500)
        assertEquals("echo: ok", m.call("mcp__fake__echo", JSONObject().put("text", "ok")).content)
        assertEquals(1, launches(), "a timeout does not restart the server")
    }

    @Test fun crashedServerIsRestartedThenErrorReported() = runBlocking {
        val m = manager(listOf(fake()))
        m.prepare()
        val out = m.call("mcp__fake__crash", JSONObject())
        assertTrue(out.isError && out.content.contains("exited"), out.content)
        assertTrue(out.content.contains("crashing"), "stderr tail is part of the error: ${out.content}")
        assertEquals(3, launches(), "1 start + 2 restarts (backoff list)")
        assertEquals("echo: back", m.call("mcp__fake__echo", JSONObject().put("text", "back")).content)
        assertEquals(4, launches())
    }

    @Test fun envValuesExpandDollarDollarVars() = runBlocking {
        val m = manager(listOf(fake(extra = arrayOf("FAKE_ENV" to "\$\$SECRET_X-\${SECRET_X}"))), baseEnv = System.getenv() + ("SECRET_X" to "s3"))
        m.prepare()
        assertEquals("FAKE_ENV=s3-s3", m.call("mcp__fake__env", JSONObject()).content)
    }

    @Test fun launchNeedsApprovalOncePerCommandLine() = runBlocking {
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        val denied = manager(listOf(fake()), approvals, allow = false)
        assertEquals(listOf("fake"), denied.prepare().denied, "a denied launch is reported (chat shows a hint)")
        assertEquals(1, approvals.size)
        assertEquals("mcp", approvals[0].toolName)
        assertTrue(approvals[0].summary.contains("Start MCP server «fake»"))
        assertTrue(approvals[0].detail.contains("FakeMcpServer"), "the exact command line is shown")
        assertEquals(0, launches(), "nothing started when denied")
        assertEquals("denied", denied.status("fake").state)
        assertTrue(denied.definitions().isEmpty())
        val second = denied.prepare()
        assertEquals(1, approvals.size, "not asked again in the same session after a deny")
        assertEquals(listOf("fake"), second.denied, "still reported as skipped on later turns")
        assertTrue(second.problems.isEmpty())

        // [security] The fake server is started by `java` = an interpreter: asked on EVERY start, never remembered.
        val dir2 = TestSupport.tmp("om-mcp2")
        approvals.clear()
        val ok = manager(listOf(fake()), approvals, storeDir = dir2)
        assertFalse(ok.mayPersistTrust(fake()))
        ok.prepare(); ok.stop("fake"); ok.prepare()
        assertEquals(2, approvals.size, "interpreter launch: every start asks, no session trust")
        assertTrue(approvals.all { it.onAlwaysAllow == null && it.rememberLabel == null }, "no Always allow offered")
        assertTrue(approvals[0].summary.contains("asked on every start"), approvals[0].summary)
        ok.trustPermanently(fake())
        assertFalse(File(dir2, "mcp_trust.json").exists() && File(dir2, "mcp_trust.json").readText().contains("fake"), "nothing written to disk")
        ok.shutdown()
    }

    @Test fun directExecutableKeepsFingerprintTrust() = runBlocking {
        // Never actually started (the binary does not exist): only the approval / trust bookkeeping is checked.
        val d = TestSupport.tmp("om-mcp-direct")
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        val direct = McpServerConfig(name = "gh", command = File(d, "github-mcp-server").absolutePath, args = listOf("stdio"), startupTimeoutSeconds = 5)
        val m = manager(listOf(direct), approvals, storeDir = d)
        assertTrue(m.mayPersistTrust(direct))
        m.prepare()
        assertEquals(1, approvals.size)
        assertNotNull(approvals[0].onAlwaysAllow, "a direct executable may be remembered")
        approvals[0].onAlwaysAllow!!.invoke()
        val again = manager(emptyList(), approvals, storeDir = d)
        assertTrue(again.isTrusted(direct), "fingerprint trust persisted across restarts")
        assertFalse(again.isTrusted(direct.copy(args = listOf("stdio", "--changed"))), "a changed command line is not trusted")
    }

    @Test fun legacyTrustForInterpreterLaunchIsDropped() = runBlocking {
        val d = TestSupport.tmp("om-mcp-legacy")
        val store = McpStore(File(d, "mcp_servers.json"), File(d, "mcp_trust.json"))
        store.save(listOf(fake()))
        store.saveTrust(mapOf("fake" to fake().launchFingerprint()))   // what batch2/3 "Always allow" left behind
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        val m = manager(emptyList(), approvals, storeDir = d)
        assertFalse(m.isTrusted(fake()))
        assertFalse(File(d, "mcp_trust.json").readText().contains("fake"), "stale entry removed from disk")
        m.prepare()
        assertEquals(1, approvals.size, "asked again")
    }

    @Test fun connectOnInterpreterLaunchIsConsentForOneStartOnly() = runBlocking {
        val d = TestSupport.tmp("om-mcp-connect")
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        val m = manager(listOf(fake()), approvals, storeDir = d)
        assertEquals("running", m.connect(fake()).state)
        assertTrue(approvals.isEmpty(), "the Connect click itself is the consent")
        m.stop("fake"); m.prepare()
        assertEquals(1, approvals.size, "the next start asks again")
        assertFalse(File(d, "mcp_trust.json").exists() && File(d, "mcp_trust.json").readText().contains("fake"))
    }

    @Test fun disabledServersAreNotStarted() = runBlocking {
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        val m = manager(listOf(fake().copy(enabled = false)), approvals)
        m.prepare()
        assertEquals(0, launches()); assertTrue(approvals.isEmpty())
        assertEquals("disabled", m.status("fake").state)
    }

    @Test fun perCallConfirmation() = runBlocking {
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        val m = manager(listOf(fake(confirm = true)), approvals)
        m.prepare()
        m.call("mcp__fake__echo", JSONObject().put("text", "x"))
        assertTrue(approvals.any { it.summary.contains("Call MCP tool «echo»") })
    }

    @Test fun schemasReachProvidersInTheirDialect() = runBlocking {
        val m = manager(listOf(fake()))
        m.prepare()
        val echo = m.definitions().first { it.name == "mcp__fake__echo" }
        assertEquals(listOf("text"), echo.required)
        val oa = echo.toOpenAIJson().getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertEquals("string", oa.getJSONObject("tags").getJSONObject("items").getString("type"))
        val gm = echo.toGeminiJson().getJSONObject("parameters").getJSONObject("properties")
        assertEquals("ARRAY", gm.getJSONObject("tags").getString("type"))
        assertEquals("STRING", gm.getJSONObject("tags").getJSONObject("items").getString("type"))
        assertTrue(echo.description.startsWith("[MCP server «fake»]"))
    }

    @Test fun agentTurnCallsAnMcpTool() {
        val server = MockWebServer().also { it.start() }
        val args = JSONObject().put("text", "你好 MCP").toString()
        server.enqueue(TestSupport.sse(
            """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"mcp__fake__echo","arguments":""}}]}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":${JSONObject.quote(args)}}}]}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""", "[DONE]"))
        server.enqueue(openAiText("done"))
        val s = AppSettings(activeProvider = ProviderKind.OPENAI_COMPATIBLE, workspaceDir = dir.absolutePath, toolsEnabled = true)
            .withProvider(ProviderKind.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k", baseUrl = base(server), model = "m"))
        val m = manager(listOf(fake()))
        val msg = runBlocking {
            m.prepare()
            val engine = AgentEngine({ st, ref -> DesktopProviderFactory.create(st, ref) },
                McpToolbox(RegistryToolbox(ToolRegistry({ s }, MemoryStore(TestSupport.tmp("om-m"))) { true }), m), retryDelaysMs = listOf(1L))
            withTimeout(30_000) { engine.runTurn(listOf(user("go")), s, "sys", ConversationContextState(), object : TurnListener {}) }
        }
        assertNull(msg.error, msg.error)
        val first = JSONObject(server.takeRequest().body.readUtf8())
        val names = (0 until first.getJSONArray("tools").length()).map { first.getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name") }
        assertTrue("mcp__fake__echo" in names && "shell" in names, names.toString())
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("echo: 你好 MCP"), second.take(2000))
        assertEquals(ToolStatus.DONE, msg.rounds.first().toolCalls.single().status)
        server.shutdown()
    }

    @Test fun configImportExportAndWindowsLaunch() {
        val claude = """{"mcpServers":{"fs":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","C:\\Users\\张三\\文档"],"env":{"K":"${'$'}${'$'}TOKEN"}},
            "off":{"command":"uvx","args":["x"],"disabled":true},"web":{"url":"http://192.168.1.5:8080/mcp"}}}"""
        val list = McpConfigJson.parseImport(claude)
        assertEquals(listOf("fs", "off", "web"), list.map { it.name })
        assertFalse(list[1].enabled); assertFalse(list[2].isStdio)
        assertEquals("C:\\Users\\张三\\文档", list[0].args.last())
        val round = McpConfigJson.parseImport(McpConfigJson.export(list))
        assertEquals(list.map { it.copy(createdAt = 0) }, round.map { it.copy(createdAt = 0) })
        assertEquals(listOf("solo"), McpConfigJson.parseImport("""{"name":"solo","command":"node","args":["s.js"]}""").map { it.name })
        assertEquals(listOf("cmd.exe", "/d", "/c", "npx", "-y", "pkg"), McpLaunch.commandLine("npx", listOf("-y", "pkg"), isWindows = true))
        assertEquals(listOf("cmd.exe", "/d", "/c", "C:\\tools\\run.cmd"), McpLaunch.commandLine("C:\\tools\\run.cmd", emptyList(), isWindows = true))
        assertEquals(listOf("C:\\Program Files\\srv\\srv.exe", "--stdio"), McpLaunch.commandLine("C:\\Program Files\\srv\\srv.exe", listOf("--stdio"), isWindows = true))
        assertEquals(listOf("npx", "-y"), McpLaunch.commandLine("npx", listOf("-y"), isWindows = false))
        assertNotEquals(list[0].launchFingerprint(), list[0].copy(args = list[0].args + "x").launchFingerprint())
    }

    @Test fun exposedNamesAreProviderSafe() {
        assertEquals("mcp__my_server__get_file", McpManager.exposedName("my server", "get.file"))
        val long = McpManager.exposedName("s".repeat(40), "t".repeat(40))
        assertTrue(long.length <= 64 && Regex("^[A-Za-z0-9_-]+$").matches(long), long)
        assertNotEquals(long, McpManager.exposedName("s".repeat(40), "t".repeat(39) + "u"))
        val g = JsonSchemaCompat.forGemini(JSONObject("""{"anyOf":[{"type":"null"},{"type":"integer","description":"n"}]}"""))
        assertEquals("INTEGER", g.getString("type"))
        val t = JsonSchemaCompat.forGemini(JSONObject("""{"type":["string","null"]}"""))
        assertTrue(t.getBoolean("nullable"))
    }
}
