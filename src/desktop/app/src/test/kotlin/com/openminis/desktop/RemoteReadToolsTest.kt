// batch13.2 — the five read-only phone tools (MCP-SERVER.md "工具"): workspace confinement through PathGuard,
// the sensitive-file denylist (incl. all of .git/), binary refusal, truncation, search budget, memory gating,
// system_info without MCP command lines, per-call timeout and the audit log.
package com.openminis.desktop

import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.mcp.McpServerConfig
import com.openminis.desktop.remote.*
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.ui.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.*

class RemoteReadToolsTest {
    private val ws: File = TestSupport.tmp("rws").apply { mkdirs() }
    private val outside: File = TestSupport.tmp("routside").apply { mkdirs() }
    private val memDir: File = TestSupport.tmp("rmem").apply { mkdirs() }
    private var memoryOn = true
    private var deny = true
    private var clock = 0L
    private var tick = 0L

    private fun tools(now: () -> Long = { clock }) = RemoteReadTools(
        workspace = { ws }, memoryEnabled = { memoryOn }, memory = { MemoryStore(memDir) }, denySensitive = { deny },
        appVersion = "9.9.9", shells = { listOf("PowerShell", "cmd") },
        mcpServers = { listOf(McpServerSummary("files", "running", 3)) }, windows = false, now = now,
    )

    private fun call(name: String, args: JSONObject = JSONObject(), t: RemoteReadTools = tools()) = t.call(name, args)!!
    private fun ok(r: JSONObject): JSONObject { assertFalse(r.getBoolean("isError"), r.toString()); return r.getJSONObject("structuredContent") }
    private fun err(r: JSONObject): String { assertTrue(r.getBoolean("isError"), r.toString()); assertFalse(r.has("structuredContent")); return r.getJSONArray("content").getJSONObject(0).getString("text") }
    private fun a(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
    private fun write(rel: String, text: String) = File(ws, rel).apply { parentFile.mkdirs(); writeText(text) }

    // ------------------------------------------------------------- schema

    @Test fun fiveToolsWithOutputSchemasAndReadOnlyHints() {
        val names = tools().list().let { l -> (0 until l.length()).map { l.getJSONObject(it) } }
        assertEquals(listOf("list_dir", "read_file", "search_files", "memory_search", "system_info"), names.map { it.getString("name") })
        names.forEach { t ->
            assertEquals("object", t.getJSONObject("inputSchema").getString("type"))
            assertEquals("object", t.getJSONObject("outputSchema").getString("type"))
            assertTrue(t.getJSONObject("annotations").getBoolean("readOnlyHint"))
        }
        assertNull(tools().call("shell", JSONObject()), "no write / shell tools exist")
        assertNull(tools().call("file_write", JSONObject()))
    }

    @Test fun memoryOffHidesTheTool() {
        memoryOn = false
        val names = tools().list().let { l -> (0 until l.length()).map { l.getJSONObject(it).getString("name") } }
        assertFalse("memory_search" in names)
        assertNull(tools().call("memory_search", JSONObject()), "hidden tool is an unknown tool")
    }

    // ------------------------------------------------------------- confinement

    @Test fun traversalUncAdsAndAbsoluteOutsideAreRefused() {
        File(outside, "secret.txt").writeText("top secret")
        for (p in listOf("../" + outside.name + "/secret.txt", "../../etc/passwd", outside.absolutePath + "/secret.txt", "a.txt:secret", "/etc/passwd")) {
            val msg = err(call("read_file", a("path" to p)))
            assertTrue(msg.startsWith("refused"), "$p → $msg")
            assertFalse(outside.absolutePath in msg, "never echoes a location outside the workspace: $msg")
        }
        // Windows rules (lexical, no file-system access): UNC, device paths, ADS.
        val win = RemoteReadTools({ ws }, { true }, { MemoryStore(memDir) }, { true }, "x", { emptyList() }, { emptyList() }, windows = true)
        for (p in listOf("\\\\server\\share\\x.txt", "\\\\?\\GLOBALROOT\\Device\\x", "\\\\.\\PhysicalDrive0", "notes.txt:hidden", "C:foo"))
            assertTrue(err(win.call("read_file", a("path" to p))!!).startsWith("refused"), p)
        assertTrue(err(call("list_dir", a("path" to ".."))).startsWith("refused"))
        assertTrue(err(call("search_files", a("query" to "x", "path" to ".."))).startsWith("refused"))
    }

    @Test fun symlinksOutOfTheWorkspaceAreRefusedAndNotFollowedBySearch() {
        File(outside, "leak.txt").writeText("needle outside")
        val fileLink = File(ws, "link.txt").toPath(); val dirLink = File(ws, "linkdir").toPath()
        try {
            Files.createSymbolicLink(fileLink, File(outside, "leak.txt").toPath())
            Files.createSymbolicLink(dirLink, outside.toPath())
        } catch (_: Exception) { return }  // no symlink privilege (Windows without developer mode): PathGuardTest covers it
        val m = err(call("read_file", a("path" to "link.txt")))
        assertEquals("refused: the path is outside the workspace", m)
        assertTrue(err(call("list_dir", a("path" to "linkdir"))).startsWith("refused"))
        assertTrue(err(call("read_file", a("path" to "linkdir/leak.txt"))).startsWith("refused"))
        write("in.txt", "needle inside")
        val hits = ok(call("search_files", a("query" to "needle"))).getJSONArray("matches")
        assertEquals(listOf("in.txt"), (0 until hits.length()).map { hits.getJSONObject(it).getString("path") })
        // The listing shows the links as links (no target), without following them.
        val e = ok(call("list_dir")).getJSONArray("entries")
        assertEquals("link", (0 until e.length()).map { e.getJSONObject(it) }.first { it.getString("name") == "linkdir" }.getString("type"))
    }

    // ------------------------------------------------------------- denylist

    @Test fun sensitiveFilesAndAllOfDotGitAreDeniedByDefault() {
        write(".env", "API_KEY=needle1"); write(".env.local", "needle2"); write("certs/server.pem", "needle3")
        write("certs/server.KEY", "needle4"); write("id_rsa", "needle5"); write("id_rsa.pub", "needle6")
        write(".git/config", "[user] email = me@example.com needle7"); write(".git/logs/HEAD", "needle8")
        write("src/.GIT/x", "needle9"); write("ok.txt", "needle ok")
        for (p in listOf(".env", ".env.local", "certs/server.pem", "certs/server.KEY", "id_rsa", "id_rsa.pub", ".git/config", ".git/logs/HEAD", ".git", "src/.GIT/x"))
            assertTrue(err(call(if (p == ".git") "list_dir" else "read_file", a("path" to p))).contains("sensitive"), p)
        val hits = ok(call("search_files", a("query" to "needle"))).getJSONArray("matches")
        assertEquals(listOf("ok.txt"), (0 until hits.length()).map { hits.getJSONObject(it).getString("path") })
        val root = ok(call("list_dir"))
        val names = root.getJSONArray("entries").let { e -> (0 until e.length()).map { e.getJSONObject(it).getString("name") } }
        assertFalse(names.any { it.startsWith(".env") || it.startsWith("id_rsa") || it == ".git" }, names.toString())
        assertEquals(5, root.getInt("hidden"))
        // The switch in Settings turns the list off.
        deny = false
        assertEquals("API_KEY=needle1", ok(call("read_file", a("path" to ".env"))).getString("content").substringAfter('\t').trim())
        assertTrue(ok(call("read_file", a("path" to ".git/config"))).getString("content").contains("me@example.com"))
        assertTrue(SensitivePaths.isDenied("a\\.git\\HEAD") && SensitivePaths.isDenied("deploy/prod.pem") && !SensitivePaths.isDenied("environment.md"))
    }

    // ------------------------------------------------------------- read_file

    @Test fun readFileNumbersLinesPagesAndRefusesBinary() {
        write("a.txt", "\uFEFFfirst\nsecond\nthird\n")
        val s = ok(call("read_file", a("path" to "a.txt")))
        assertEquals("     1\tfirst\n     2\tsecond\n     3\tthird\n", s.getString("content"))
        assertFalse(s.getBoolean("truncated"))
        val p = ok(call("read_file", a("path" to "a.txt", "offset" to 2, "limit" to 1)))
        assertEquals("     2\tsecond\n", p.getString("content")); assertTrue(p.getBoolean("truncated")); assertEquals(3, p.getInt("next_offset"))
        write("中文.md", "你好，世界")
        assertTrue(ok(call("read_file", a("path" to "中文.md"))).getString("content").contains("你好，世界"))
        File(ws, "img.png").writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13))
        assertTrue(err(call("read_file", a("path" to "img.png"))).startsWith("binary"))
        File(ws, "gbk.txt").writeBytes("中文".toByteArray(charset("GBK")))
        assertTrue(err(call("read_file", a("path" to "gbk.txt"))).startsWith("binary"), "not UTF-8 → refused")
        assertTrue(err(call("read_file", a("path" to "nope.txt"))).contains("no such file"))
        assertTrue(err(call("read_file", JSONObject())).contains("required"))
        assertTrue(err(call("read_file", a("path" to "."))).contains("directory"))
    }

    @Test fun readFileTruncatesAt2000LinesAnd256KB() {
        write("long.txt", (1..5000).joinToString("\n") { "line $it" })
        val s = ok(call("read_file", a("path" to "long.txt")))
        assertEquals(2000, s.getInt("lines")); assertTrue(s.getBoolean("truncated")); assertEquals(2001, s.getInt("next_offset"))
        write("wide.txt", (1..100).joinToString("\n") { "x".repeat(10_000) })
        val w = ok(call("read_file", a("path" to "wide.txt")))
        assertTrue(w.getBoolean("truncated"))
        assertTrue(w.getString("content").toByteArray().size <= RemoteReadTools.READ_MAX_BYTES)
        assertEquals(1 + w.getInt("lines"), w.getInt("next_offset"))
    }

    // ------------------------------------------------------------- list_dir

    @Test fun listDirCapsAt1000Entries() {
        val d = File(ws, "many").apply { mkdirs() }
        repeat(1005) { File(d, "f%04d.txt".format(it)).writeText("x") }
        File(d, "sub").mkdirs()
        val s = ok(call("list_dir", a("path" to "many")))
        val e = s.getJSONArray("entries")
        assertEquals(1000, e.length()); assertTrue(s.getBoolean("truncated"))
        assertEquals("dir", e.getJSONObject(0).getString("type"), "directories first")
        assertEquals(1L, e.getJSONObject(1).getLong("size"))
        assertTrue(e.getJSONObject(1).getString("modified").endsWith("Z"))
        assertTrue(err(call("list_dir", a("path" to "many/f0001.txt"))).contains("not a directory"))
    }

    // ------------------------------------------------------------- search_files

    @Test fun searchIsLiteralCaseOptionalGlobbedAndCapped() {
        write("src/A.kt", "val x = a.*b\nfun Main() {}\n")
        write("src/b.md", "MAIN entry\n")
        write("node_modules/x.js", "main"); write("build/out.txt", "main"); write(".git/HEAD", "main")
        File(ws, "bin.dat").writeBytes(byteArrayOf(0, 1, 2) + "main".toByteArray())
        File(ws, "big.txt").writeText("main\n" + "y".repeat(2 * 1024 * 1024 + 10))
        fun paths(r: JSONObject) = r.getJSONArray("matches").let { m -> (0 until m.length()).map { m.getJSONObject(it).getString("path") + ":" + m.getJSONObject(it).getInt("line") } }
        assertEquals(listOf("src/A.kt:2", "src/b.md:1"), paths(ok(call("search_files", a("query" to "main")))).sorted())
        assertEquals(listOf("src/A.kt:2"), paths(ok(call("search_files", a("query" to "Main", "case_sensitive" to true)))))
        assertEquals(listOf("src/A.kt:1"), paths(ok(call("search_files", a("query" to "a.*b")))), "regex characters are literal")
        assertEquals(listOf("src/b.md:1"), paths(ok(call("search_files", a("query" to "main", "glob" to "*.md")))))
        assertEquals(listOf("src/A.kt:2"), paths(ok(call("search_files", a("query" to "main", "path" to "src", "glob" to "*.kt")))))
        repeat(30) { write("many/m$it.txt", (1..30).joinToString("\n") { "hit" }) }
        val capped = ok(call("search_files", a("query" to "hit", "max_results" to 5000)))
        assertEquals(RemoteReadTools.SEARCH_MAX, capped.getJSONArray("matches").length()); assertTrue(capped.getBoolean("truncated"))
        assertEquals(RemoteReadTools.SEARCH_DEFAULT, ok(call("search_files", a("query" to "hit"))).getJSONArray("matches").length())
        assertTrue(err(call("search_files", JSONObject())).contains("required"))
    }

    @Test fun searchStopsAtItsTimeBudgetAndReturnsWhatItFound() {
        repeat(50) { write("t/f%02d.txt".format(it), "needle\n") }
        // Each clock read advances 200 ms: the 5 s budget runs out part-way through the tree.
        val t = tools(now = { tick += 200; tick })
        val s = ok(call("search_files", a("query" to "needle"), t))
        assertTrue(s.getBoolean("timed_out"))
        val n = s.getJSONArray("matches").length()
        assertTrue(n in 1 until 50, "partial results: $n")
    }

    // ------------------------------------------------------------- memory, system_info

    @Test fun memorySearchUsesTheMemoryStore() {
        File(memDir, "2026-10-01.md").writeText("### 09:00\n喜欢用 PowerShell 7\n")
        File(memDir, "GLOBAL.md").writeText("# Facts\nWorkspace on D drive\n")
        val r = ok(call("memory_search", a("keywords" to "PowerShell", "scope" to "daily"))).getJSONArray("results")
        assertEquals(1, r.length()); assertEquals("2026-10-01.md", r.getJSONObject(0).getString("file"))
        val g = ok(call("memory_search", a("keywords" to "drive", "scope" to "all"))).getJSONArray("results")
        assertEquals("GLOBAL.md", g.getJSONObject(0).getString("file"))
        assertEquals(0, ok(call("memory_search", a("keywords" to "drive", "scope" to "daily"))).getJSONArray("results").length())
        assertTrue(ok(call("memory_search")).getJSONArray("results").length() >= 1, "empty keywords = newest entries")
    }

    @Test fun systemInfoThroughTheAppNeverCarriesMcpCommandLines() {
        val root = TestSupport.tmp("rsys")
        val state = AppState(AppPaths(root), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            state.updateMcpServers(listOf(McpServerConfig(name = "github", command = "npx-SECRETCMD", args = listOf("--token", "ghp_SECRETARG"),
                env = mapOf("GITHUB_TOKEN" to "ghp_SECRETENV"), enabled = false))).let { kotlinx.coroutines.runBlocking { it.join() } }
            assertEquals("github", state.mcp.servers.single().name)
            val token = state.remote.tokens.get()
            val c = state.remote.core
            val from = InetAddress.getByName("192.168.1.9")
            fun post(body: JSONObject, sid: String?) = c.handle(McpRequest("POST", "/mcp", buildMap {
                put("Content-Type", "application/json"); put("Accept", "application/json, text/event-stream"); put("Authorization", "Bearer $token")
                sid?.let { put("Mcp-Session-Id", it) } }, body.toString().toByteArray(), from))
            val init = post(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize").put("params", JSONObject().put("protocolVersion", "2025-06-18")), null)
            val sid = init.headers["Mcp-Session-Id"]!!
            post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), sid)
            val r = post(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call").put("params", JSONObject().put("name", "system_info")), sid)
            val body = r.body!!
            for (secret in listOf("SECRETCMD", "SECRETARG", "SECRETENV", "GITHUB_TOKEN", "--token", token)) assertFalse(secret in body, secret)
            val sc = JSONObject(body).getJSONObject("result").getJSONObject("structuredContent")
            val srv = sc.getJSONArray("mcp_servers").getJSONObject(0)
            assertEquals(setOf("name", "state", "tool_count"), srv.keySet())
            assertEquals("github", srv.getString("name")); assertEquals("disabled", srv.getString("state"))
            assertEquals(state.settings.workspace().absolutePath, sc.getString("workspace"))
            // ... and the call went to the audit log, with the IP and without the token.
            val e = state.remote.audit.entries().first()
            assertEquals("system_info", e.tool); assertEquals("192.168.1.9", e.ip); assertEquals("ok", e.outcome); assertTrue(e.bytes > 0)
            assertFalse(token in File(root, "remote-mcp-audit.jsonl").readText())
        } finally { runCatching { state.shutdown() } }
    }

    // ------------------------------------------------------------- core: timeout + audit

    private fun coreWith(set: RemoteToolSet, audit: RemoteAudit, timeoutMs: Long = McpHttpCore.CALL_TIMEOUT_MS): Pair<McpHttpCore, String> {
        val token = RemoteTokenStore.generate()
        val c = McpHttpCore(token = { token }, tools = { set }, audit = audit, callTimeoutMs = timeoutMs)
        val from = InetAddress.getByName("10.0.0.5")
        val tok = token
        val sid = c.handle(McpRequest("POST", "/mcp", mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream",
            "Authorization" to "Bearer $tok"), """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""".toByteArray(), from)).headers["Mcp-Session-Id"]!!
        c.handle(McpRequest("POST", "/mcp", mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream",
            "Authorization" to "Bearer $tok", "Mcp-Session-Id" to sid), """{"jsonrpc":"2.0","method":"notifications/initialized"}""".toByteArray(), from))
        lastToken = tok
        return c to sid
    }
    private var lastToken = ""
    private fun toolCall(c: McpHttpCore, sid: String, name: String, args: JSONObject) = JSONObject(c.handle(McpRequest("POST", "/mcp",
        mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream", "Authorization" to "Bearer $lastToken", "Mcp-Session-Id" to sid),
        JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call").put("params", JSONObject().put("name", name).put("arguments", args)).toString().toByteArray(),
        InetAddress.getByName("10.0.0.5"))).body!!)

    @Test fun everyCallIsAuditedWithPathBytesAndOutcome() {
        val auditFile = File(TestSupport.tmp("raudit"), "audit.jsonl")
        val audit = RemoteAudit(auditFile)
        write("notes/a.txt", "hello")
        val (c, sid) = coreWith(tools(), audit)
        toolCall(c, sid, "read_file", a("path" to "notes/a.txt"))
        toolCall(c, sid, "read_file", a("path" to ".env"))
        toolCall(c, sid, "list_dir", JSONObject())
        val unknown = toolCall(c, sid, "rm_rf_session_everything", JSONObject())
        assertEquals("unknown tool", unknown.getJSONObject("error").getString("message"), "never echoes the tool name")
        val e = audit.entries()
        assertEquals(listOf("(unknown)", "list_dir", "read_file", "read_file"), e.map { it.tool })
        assertEquals(listOf("unknown_tool", "ok", "error", "ok"), e.map { it.outcome })
        assertEquals(listOf("", ".", ".env", "notes/a.txt"), e.map { it.path })
        assertTrue(e.last().bytes >= "     1\thello\n".length)
        assertEquals("10.0.0.5", e.last().ip)
        assertFalse(lastToken in auditFile.readText()); assertFalse("hello" in auditFile.readText(), "contents are never audited")
        audit.clear(); assertTrue(audit.entries().isEmpty())
    }

    @Test fun aSlowCallTimesOutAsToolError() {
        val slow = object : RemoteToolSet {
            override fun list() = JSONArray()
            override fun call(name: String, arguments: JSONObject): JSONObject? { Thread.sleep(2_000); return JSONObject() }
        }
        val audit = RemoteAudit(File(TestSupport.tmp("rslow"), "audit.jsonl"))
        val (c, sid) = coreWith(slow, audit, timeoutMs = 200)
        val t0 = System.currentTimeMillis()
        val r = toolCall(c, sid, "read_file", a("path" to "x"))
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
        val res = r.getJSONObject("result")
        assertTrue(res.getBoolean("isError")); assertTrue(res.getJSONArray("content").getJSONObject(0).getString("text").startsWith("timed out"))
        assertEquals("timeout", audit.entries().first().outcome)
        assertEquals(10_000L, McpHttpCore.CALL_TIMEOUT_MS); assertEquals(4, McpHttpCore.MAX_CONCURRENT_CALLS)
    }

    @Test fun denylistDefaultsOnAndOldSettingsKeepIt() {
        assertTrue(RemoteMcpSettings().denySensitive)
        val f = File(TestSupport.tmp("rset"), "settings.json").apply { writeText("""{"remoteMcp":{"enabled":true,"port":47821}}""") }
        assertTrue(com.openminis.desktop.core.SettingsStore(f).load().remoteMcp.denySensitive)
    }
}
