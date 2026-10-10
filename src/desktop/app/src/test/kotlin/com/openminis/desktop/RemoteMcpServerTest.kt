// batch13.1 — remote MCP server skeleton (MCP-SERVER.md): check order, auth, lockout, sessions, headers,
// self-heal bodies, LAN-only binding, token never logged.
package com.openminis.desktop

import com.openminis.app.logging.AppLogger
import com.openminis.desktop.remote.*
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

class RemoteMcpServerTest {
    private val token = RemoteTokenStore.generate()
    private var clock = 1_000_000L
    private val lan: InetAddress = InetAddress.getByName("192.168.1.50")
    private val lan2: InetAddress = InetAddress.getByName("10.0.0.7")

    private fun core(tok: () -> String = { token }) = McpHttpCore(token = tok, now = { clock }, serverVersion = "test")

    private val goodAccept = "application/json, text/event-stream"

    private fun post(
        c: McpHttpCore, body: String, session: String? = null, auth: String? = "Bearer $token",
        accept: String? = goodAccept, from: InetAddress = lan, extra: Map<String, String> = emptyMap(), path: String = "/mcp",
    ): McpResponse {
        val h = buildMap {
            put("Content-Type", "application/json")
            accept?.let { put("Accept", it) }
            auth?.let { put("Authorization", it) }
            session?.let { put("Mcp-Session-Id", it) }
            putAll(extra)
        }
        return c.handle(McpRequest("POST", path, h, body.toByteArray(), from))
    }

    private fun req(method: String, id: Int? = 1, params: JSONObject? = null) = JSONObject().put("jsonrpc", "2.0").put("method", method)
        .also { if (id != null) it.put("id", id); if (params != null) it.put("params", params) }.toString()

    private fun init(c: McpHttpCore, auth: String? = "Bearer $token"): McpResponse =
        post(c, req("initialize", 1, JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "minis").put("version", "1"))), auth = auth)

    /** initialize + notifications/initialized; returns the session id. */
    private fun open(c: McpHttpCore): String {
        val r = init(c)
        assertEquals(200, r.status, r.toString())
        val sid = r.headers["Mcp-Session-Id"]!!
        assertEquals(202, post(c, req("notifications/initialized", null), session = sid).status)
        return sid
    }

    private fun json(r: McpResponse) = JSONObject(r.body!!)
    private fun sessionWordIn(r: McpResponse) = r.body.orEmpty().take(200).lowercase().contains("session")

    // ------------------------------------------------------------- handshake

    @Test fun handshakeAndPing() {
        val c = core()
        val r = init(c)
        assertEquals(200, r.status)
        val res = json(r).getJSONObject("result")
        assertEquals("2025-06-18", res.getString("protocolVersion"))
        assertFalse(res.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"))
        val sid = r.headers["Mcp-Session-Id"]!!
        assertTrue(sid.length >= 22)
        assertEquals(202, post(c, req("notifications/initialized", null), session = sid).status)
        assertEquals(200, post(c, req("ping", 7), session = sid).status)
        val list = post(c, req("tools/list", 8), session = sid)
        assertEquals(0, json(list).getJSONObject("result").getJSONArray("tools").length())
        assertEquals(-32601, json(post(c, req("resources/list", 9), session = sid)).getJSONObject("error").getInt("code"))
        assertEquals(-32602, json(post(c, req("tools/call", 10, JSONObject().put("name", "x")), session = sid)).getJSONObject("error").getInt("code"))
    }

    @Test fun initializeOncePerSessionAndToolsWaitForInitialized() {
        val c = core()
        val sid = init(c).headers["Mcp-Session-Id"]!!
        // tools before notifications/initialized
        assertEquals(-32600, json(post(c, req("tools/list", 2), session = sid)).getJSONObject("error").getInt("code"))
        // second initialize on the same live session
        val again = post(c, req("initialize", 3, JSONObject().put("protocolVersion", "2025-06-18")), session = sid)
        assertEquals(-32600, json(again).getJSONObject("error").getInt("code"))
        assertNull(again.headers["Mcp-Session-Id"])
        assertFalse(sessionWordIn(again))
        assertEquals(1, c.sessionCount())
        // a stale/unknown header on initialize = the phone re-initializing after a 404: accepted, new session
        val fresh = post(c, req("initialize", 4, JSONObject()), session = "stale-id")
        assertEquals(200, fresh.status)
        assertNotEquals(sid, fresh.headers["Mcp-Session-Id"])
    }

    // ------------------------------------------------------------- check order: auth → session → method

    @Test fun initializeNeedsBearer() {
        val c = core()
        val r = init(c, auth = null)
        assertEquals(401, r.status)
        assertEquals("Bearer", r.headers["WWW-Authenticate"])
        assertEquals(0, c.sessionCount())
        assertEquals(401, init(c, auth = "Bearer wrong").status)
        assertEquals(401, init(c, auth = "Basic $token").status)
    }

    @Test fun authIsCheckedBeforeSessionAndMethod() {
        val c = core()
        val sid = open(c)
        // wrong token + unknown session + unknown method: auth wins → 401, body without "session"
        val r = post(c, req("nope", 1), session = "unknown", auth = "Bearer bad")
        assertEquals(401, r.status); assertFalse(sessionWordIn(r))
        // wrong token + GET: still 401, not 405
        val g = c.handle(McpRequest("GET", "/mcp", mapOf("Authorization" to "Bearer bad"), ByteArray(0), lan))
        assertEquals(401, g.status)
        // right token, missing session, unknown method: session wins → 400 with "session"
        val m = post(c, req("nope", 1))
        assertEquals(400, m.status); assertTrue(sessionWordIn(m))
        // right token, live session, unknown method → JSON-RPC -32601
        assertEquals(-32601, json(post(c, req("nope", 1), session = sid)).getJSONObject("error").getInt("code"))
    }

    @Test fun rotatedTokenGives401WithoutSessionWord() {
        var t = token
        val c = core { t }
        val sid = open(c)
        t = RemoteTokenStore.generate(); c.dropAllSessions()
        val r = post(c, req("ping", 1), session = sid)  // phone still holds the old token
        assertEquals(401, r.status); assertFalse(sessionWordIn(r))
        assertEquals(0, c.sessionCount())
        assertEquals(200, post(c, req("initialize", 1, JSONObject()), auth = "Bearer $t").status)
    }

    // ------------------------------------------------------------- lockout → 429 + Retry-After

    @Test fun lockoutAfterFiveFailuresIs429WithRetryAfter() {
        val c = core()
        repeat(5) { assertEquals(401, init(c, auth = "Bearer bad$it").status) }
        val locked = init(c)  // even the right token
        assertEquals(429, locked.status)
        assertEquals("60", locked.headers["Retry-After"])
        assertFalse(sessionWordIn(locked))
        // another address is unaffected
        assertEquals(200, post(c, req("initialize", 1, JSONObject()), from = lan2).status)
        clock += 30_000
        assertEquals("30", init(c).headers["Retry-After"])
        clock += 30_001
        assertEquals(200, init(c).status)
    }

    @Test fun failuresOutsideTheWindowDoNotLock() {
        val c = core()
        repeat(4) { init(c, auth = "Bearer bad") }
        clock += 61_000
        repeat(4) { init(c, auth = "Bearer bad") }
        assertEquals(200, init(c).status)
    }

    // ------------------------------------------------------------- sessions

    @Test fun sessionErrorsCarryTheSessionWordEarly() {
        val c = core()
        val nf = post(c, req("ping", 1), session = "does-not-exist")
        assertEquals(404, nf.status); assertTrue(sessionWordIn(nf))
        assertEquals(McpHttpCore.BODY_SESSION_NOT_FOUND, nf.body)
        val missing = post(c, req("ping", 1))
        assertEquals(400, missing.status); assertTrue(sessionWordIn(missing))
        assertEquals(McpHttpCore.BODY_SESSION_REQUIRED, missing.body)
    }

    @Test fun otherErrorsNeverMentionSession() {
        val c = core()
        val sid = open(c)
        val cases = listOf(
            post(c, req("ping", 1), session = sid, auth = null),                                     // 401
            post(c, req("ping", 1), session = sid, accept = "application/json"),                    // 406
            post(c, req("ping", 1), session = sid, extra = mapOf("MCP-Protocol-Version" to "2024-11-05")), // 400 version
            post(c, "[${req("ping", 1)}]", session = sid),                                          // 400 batch
            post(c, "{not json", session = sid),                                                    // 400 parse
            post(c, req("ping", 1), session = sid, extra = mapOf("Origin" to "http://evil.example")), // 403
            post(c, req("ping", 1), session = sid, from = InetAddress.getByName("8.8.8.8")),         // 403
            post(c, req("ping", 1), session = sid, path = "/other"),                                 // 404 path
            post(c, req("session-ish/method", 2), session = sid),                                    // -32601, input not echoed
            post(c, req("tools/call", 3, JSONObject().put("name", "session")), session = sid),       // -32602, input not echoed
        )
        assertEquals(listOf(401, 406, 400, 400, 400, 403, 403, 404, 200, 200), cases.map { it.status })
        cases.forEach { assertFalse(sessionWordIn(it), it.toString()) }
    }

    @Test fun idleSessionsExpireAfter30Minutes() {
        val c = core()
        val sid = open(c)
        clock += 29 * 60_000
        assertEquals(200, post(c, req("ping", 1), session = sid).status)  // use refreshes it
        clock += 31 * 60_000
        assertEquals(404, post(c, req("ping", 1), session = sid).status)
    }

    @Test fun fullTableEvictsLeastRecentlyUsed() {
        val c = core()
        val ids = (1..McpHttpCore.MAX_SESSIONS).map { clock += 1; open(c) }
        clock += 1
        assertEquals(200, post(c, req("ping", 1), session = ids[0]).status)  // ids[0] is now the most recent
        val extra = open(c)                                                   // 33rd: evicts ids[1], never refused
        assertEquals(McpHttpCore.MAX_SESSIONS, c.sessionCount())
        assertEquals(404, post(c, req("ping", 1), session = ids[1]).status)
        assertEquals(200, post(c, req("ping", 1), session = ids[0]).status)
        assertEquals(200, post(c, req("ping", 1), session = extra).status)
    }

    @Test fun deleteEndsTheSession() {
        val c = core()
        val sid = open(c)
        val h = mapOf("Authorization" to "Bearer $token", "Mcp-Session-Id" to sid)
        assertEquals(204, c.handle(McpRequest("DELETE", "/mcp", h, ByteArray(0), lan)).status)
        assertEquals(404, c.handle(McpRequest("DELETE", "/mcp", h, ByteArray(0), lan)).status)
        assertEquals(400, c.handle(McpRequest("DELETE", "/mcp", mapOf("Authorization" to "Bearer $token"), ByteArray(0), lan)).status)
    }

    // ------------------------------------------------------------- headers

    @Test fun getIs405WithAllow() {
        val r = core().handle(McpRequest("GET", "/mcp", mapOf("Authorization" to "Bearer $token", "Accept" to "text/event-stream"), ByteArray(0), lan))
        assertEquals(405, r.status)
        assertEquals("POST, DELETE", r.headers["Allow"])
    }

    @Test fun acceptMustListBothTypes() {
        val c = core()
        assertEquals(406, post(c, req("initialize", 1, JSONObject()), accept = "application/json").status)
        assertEquals(406, post(c, req("initialize", 1, JSONObject()), accept = "text/event-stream").status)
        assertEquals(406, post(c, req("initialize", 1, JSONObject()), accept = null).status)
        assertEquals(200, post(c, req("initialize", 1, JSONObject()), accept = "text/event-stream;q=1, application/json;q=0.9").status)
    }

    @Test fun protocolVersionHeaderAbsentOkPresentMustMatch() {
        val c = core()
        val sid = open(c)
        assertEquals(200, post(c, req("ping", 1), session = sid).status)                                                   // absent
        assertEquals(200, post(c, req("ping", 1), session = sid, extra = mapOf("MCP-Protocol-Version" to "2025-06-18")).status) // equal
        assertEquals(400, post(c, req("ping", 1), session = sid, extra = mapOf("mcp-protocol-version" to "2025-03-26")).status) // different
    }

    @Test fun networkChecks() {
        val c = core()
        assertEquals(403, post(c, req("initialize", 1, JSONObject()), from = InetAddress.getByName("127.0.0.1")).status)
        assertEquals(403, post(c, req("initialize", 1, JSONObject()), from = InetAddress.getByName("100.64.1.2")).status)
        assertEquals(403, post(c, req("initialize", 1, JSONObject()), extra = mapOf("Origin" to "http://192.168.1.50.evil.example")).status)
        val big = "x".repeat(McpHttpCore.MAX_BODY + 1)
        assertEquals(413, post(c, big).status)
    }

    // ------------------------------------------------------------- binding: private IPv4 only

    @Test fun selectsOnlyPrivateIpv4OnUpNonLoopbackInterfaces() {
        fun a(s: String) = InetAddress.getByName(s)
        val nics = listOf(
            LanAddresses.Nic("lo", up = true, loopback = true, addresses = listOf(a("127.0.0.1"), a("::1"))),
            LanAddresses.Nic("wlan0", up = true, loopback = false, addresses = listOf(a("192.168.1.23"), a("fe80::1"), a("2001:db8::5"))),
            LanAddresses.Nic("eth0", up = true, loopback = false, addresses = listOf(a("10.1.2.3"), a("172.20.0.4"), a("172.32.0.1"), a("8.8.4.4"))),
            LanAddresses.Nic("tailscale", up = true, loopback = false, addresses = listOf(a("100.101.102.103"))),
            LanAddresses.Nic("apipa", up = true, loopback = false, addresses = listOf(a("169.254.10.10"))),
            LanAddresses.Nic("down", up = false, loopback = false, addresses = listOf(a("192.168.9.9"))),
            LanAddresses.Nic("any", up = true, loopback = false, addresses = listOf(a("0.0.0.0"))),
        )
        assertEquals(listOf("192.168.1.23", "10.1.2.3", "172.20.0.4"), LanAddresses.select(nics).map { it.second.hostAddress })
        LanAddresses.current().forEach { (_, ip) -> assertTrue(LanAddresses.isPrivateV4(ip), ip.hostAddress) }
    }

    @Test fun serverRefusesToBindNonPrivateAddresses() {
        val srv = RemoteMcpServer(core()) { listOf("lo" to InetAddress.getByName("127.0.0.1") as Inet4Address, "pub" to InetAddress.getByName("8.8.8.8") as Inet4Address) }
        val st = srv.start(0)
        try {
            assertFalse(st.running)
            assertTrue(st.bindings.isEmpty())
            assertEquals(2, st.errors.size)
        } finally { srv.stop() }
    }

    @Test fun realSocketRoundTrip() {
        // Loopback stands in for a LAN address here; the core's source check is widened only in this test.
        val c = McpHttpCore(token = { token }, remoteAllowed = { true })
        val srv = RemoteMcpServer(c) { listOf("lo" to InetAddress.getByName("127.0.0.1") as Inet4Address) }
        srv.allowNonPrivateForTests = true
        val port = java.net.ServerSocket(0).use { it.localPort }
        val st = srv.start(port)
        try {
            assertTrue(st.running, st.errors.toString())
            val http = HttpClient.newHttpClient()
            fun send(b: HttpRequest.Builder) = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            val base = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).header("Accept", goodAccept).header("Content-Type", "application/json")
            val r = send(base.copy().header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.ofString(req("initialize", 1, JSONObject()))))
            assertEquals(200, r.statusCode())
            val sid = r.headers().firstValue("Mcp-Session-Id").get()
            val n = send(base.copy().header("Authorization", "Bearer $token").header("Mcp-Session-Id", sid).POST(HttpRequest.BodyPublishers.ofString(req("notifications/initialized", null))))
            assertEquals(202, n.statusCode())
            assertEquals(401, send(base.copy().POST(HttpRequest.BodyPublishers.ofString(req("ping", 1)))).statusCode())
            val g = send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).header("Authorization", "Bearer $token").GET())
            assertEquals(405, g.statusCode()); assertEquals("POST, DELETE", g.headers().firstValue("Allow").get())
        } finally { srv.stop() }
    }

    // ------------------------------------------------------------- token: storage, rotation, never logged

    @Test fun tokenStoreGeneratesPersistsAndRotates() {
        val dir = kotlin.io.path.createTempDirectory("tok").toFile()
        try {
            val f = java.io.File(dir, "remote-mcp-token")
            val t1 = RemoteTokenStore(f).get()
            assertTrue(RemoteTokenStore.valid(t1)); assertEquals(43, t1.length)
            assertEquals(t1, RemoteTokenStore(f).get())
            val t2 = RemoteTokenStore(f).rotate()
            assertNotEquals(t1, t2); assertEquals(t2, RemoteTokenStore(f).get())
        } finally { dir.deleteRecursively() }
    }

    @Test fun controllerRotationDropsSessions() {
        val dir = kotlin.io.path.createTempDirectory("ctl").toFile()
        try {
            val ctl = RemoteMcpController(dir, "t")
            val old = ctl.tokens.get()
            val h = mapOf("Authorization" to "Bearer $old", "Accept" to goodAccept, "Content-Type" to "application/json")
            assertEquals(200, ctl.core.handle(McpRequest("POST", "/mcp", h, req("initialize", 1, JSONObject()).toByteArray(), lan)).status)
            assertEquals(1, ctl.core.sessionCount())
            ctl.rotateToken()
            assertEquals(0, ctl.core.sessionCount())
            assertEquals(401, ctl.core.handle(McpRequest("POST", "/mcp", h, req("ping", 1).toByteArray(), lan)).status)
            assertTrue(RemoteMcpController.connectionString("192.168.1.23", 47821, "TOK")
                .startsWith("minis-mcp-cli add --name desktop --url http://192.168.1.23:47821/mcp --header \"Authorization: Bearer TOK\""))
        } finally { dir.deleteRecursively() }
    }

    @Test fun tokenNeverReachesTheLogsOrResponses() {
        val dir = kotlin.io.path.createTempDirectory("logs").toFile()
        val prevDir = AppLogger.logDir; val prevVerbose = AppLogger.verbose
        val err = System.err
        val captured = java.io.ByteArrayOutputStream()
        try {
            AppLogger.logDir = dir; AppLogger.verbose = true
            System.setErr(java.io.PrintStream(captured, true, "UTF-8"))
            val wrong = RemoteTokenStore.generate()
            val c = core()
            val bodies = mutableListOf<String>()
            fun keep(r: McpResponse) { bodies += r.toString() }
            val sid = open(c)
            keep(post(c, req("ping", 1), session = sid))
            keep(post(c, req("tools/call", 2, JSONObject().put("name", token)), session = sid))
            keep(post(c, req("ping", 1), session = sid, auth = "Bearer $wrong"))
            repeat(6) { keep(post(c, req("ping", 1), auth = "Bearer $wrong", from = lan2)) }
            keep(post(c, "{broken", session = sid))
            keep(c.handle(McpRequest("POST", "/mcp?token=$token", mapOf("Authorization" to "Bearer $token"), ByteArray(0), lan)))
            val logs = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() } + captured.toString("UTF-8")
            assertTrue(logs.contains("RemoteMcp"), "server activity is logged")
            for (secret in listOf(token, wrong)) {
                assertFalse(logs.contains(secret), "token in logs")
                bodies.forEach { assertFalse(it.contains(secret), "token echoed: $it") }
            }
            assertFalse(logs.contains("Authorization"), "Authorization header in logs")
        } finally {
            System.setErr(err); AppLogger.logDir = prevDir; AppLogger.verbose = prevVerbose; dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------- packaging and settings

    @Test fun packagedRuntimeShipsJdkHttpserverAndServerIsOffByDefault() {
        val gradle = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "build.gradle.kts") }
            .first { it.isFile && "nativeDistributions" in it.readText() }
        assertTrue("\"jdk.httpserver\"" in gradle.readText())
        val s = com.openminis.desktop.core.AppSettings()
        assertFalse(s.remoteMcp.enabled)
        assertEquals(47821, s.remoteMcp.port)
        assertTrue(com.openminis.desktop.ui.SettingsPage.REMOTE in com.openminis.desktop.ui.SettingsPage.of(com.openminis.desktop.ui.SettingsGroup.SYSTEM))
        assertEquals("abcd••••••••wxyz", com.openminis.desktop.ui.maskToken("abcdefghijklmnopqrstuvwxyz".let { it.take(4) + "x".repeat(10) + "wxyz" }))
    }
}
