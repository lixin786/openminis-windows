// batch13.3 — connections list, disconnect (kick + 10-minute refusal), audit log view helpers, i18n of the new page.
package com.openminis.desktop

import com.openminis.desktop.i18n.I18n
import com.openminis.desktop.remote.*
import com.openminis.desktop.ui.AUDIT_SHOWN
import com.openminis.desktop.ui.humanBytes
import com.openminis.desktop.ui.outcomeKey
import org.json.JSONObject
import java.net.InetAddress
import kotlin.test.*

class RemoteSessionsAuditTest {
    private val token = RemoteTokenStore.generate()
    private var clock = 5_000_000L
    private val phone: InetAddress = InetAddress.getByName("192.168.1.50")
    private val other: InetAddress = InetAddress.getByName("192.168.1.51")
    private val accept = "application/json, text/event-stream"

    private fun core(tok: () -> String = { token }) = McpHttpCore(token = tok, now = { clock }, serverVersion = "test")

    private fun post(c: McpHttpCore, body: String, session: String? = null, from: InetAddress = phone, tok: String = token): McpResponse {
        val h = buildMap {
            put("Content-Type", "application/json"); put("Accept", accept); put("Authorization", "Bearer $tok")
            session?.let { put("Mcp-Session-Id", it) }
        }
        return c.handle(McpRequest("POST", "/mcp", h, body.toByteArray(), from))
    }

    private fun req(method: String, id: Int? = 1, params: JSONObject? = null) = JSONObject().put("jsonrpc", "2.0").put("method", method)
        .also { if (id != null) it.put("id", id); if (params != null) it.put("params", params) }.toString()

    private fun open(c: McpHttpCore, from: InetAddress = phone, name: String = "minis", tok: String = token): String {
        val r = post(c, req("initialize", 1, JSONObject().put("protocolVersion", "2025-06-18")
            .put("clientInfo", JSONObject().put("name", name).put("version", "1"))), from = from, tok = tok)
        assertEquals(200, r.status, r.toString())
        val sid = r.headers["Mcp-Session-Id"]!!
        assertEquals(202, post(c, req("notifications/initialized", null), session = sid, from = from, tok = tok).status)
        return sid
    }

    @Test fun sessionInfosShowClientAddressTimesAndCalls() {
        val c = core()
        val a = open(c, name = "minis-phone")
        clock += 1_000
        val b = open(c, from = other, name = "")
        clock += 1_000
        post(c, req("tools/call", 2, JSONObject().put("name", "nope")), session = a)
        post(c, req("tools/call", 3, JSONObject().put("name", "nope")), session = a)
        val infos = c.sessionInfos()
        assertEquals(listOf(a, b), infos.map { it.id }, "most recently used first")
        val ia = infos[0]
        assertEquals("minis-phone", ia.clientName); assertEquals("192.168.1.50", ia.remote)
        assertEquals(2, ia.calls); assertTrue(ia.initialized)
        assertEquals(5_000_000L, ia.createdAt); assertEquals(5_002_000L, ia.lastUsed)
        assertEquals("", infos[1].clientName)
    }

    @Test fun kickEndsAllSessionsOfThatAddressAndRefusesItWithoutTheSessionWord() {
        val c = core()
        val a1 = open(c); val a2 = open(c); val b = open(c, from = other)
        assertTrue(c.kick(a1))
        assertEquals(listOf(b), c.sessionInfos().map { it.id })
        // The old session and a fresh initialize are both refused: 403, Retry-After, and no "session" in the
        // body, so the phone stops instead of re-initializing in a loop.
        for (r in listOf(post(c, req("ping", 1), session = a2),
                         post(c, req("initialize", 1, JSONObject().put("protocolVersion", "2025-06-18"))))) {
            assertEquals(403, r.status, r.toString())
            assertEquals("600", r.headers["Retry-After"])
            assertFalse(r.body.orEmpty().take(200).lowercase().contains("session"), r.toString())
        }
        assertEquals(200, post(c, req("ping", 1), session = b, from = other).status, "other devices unaffected")
        assertFalse(c.kick(a1), "already gone")
    }

    @Test fun kickedAddressStillNeedsTheTokenFirst() {
        val c = core()
        c.kick(open(c))
        assertEquals(401, post(c, req("ping", 1), tok = "wrong").status, "auth runs before the kick check")
    }

    @Test fun refusalExpiresAfterTenMinutes() {
        val c = core()
        c.kick(open(c))
        clock += McpHttpCore.KICK_BLOCK_MS - 1_000
        assertEquals(1_000L, c.blockedAddresses()["192.168.1.50"])
        assertEquals(403, post(c, req("initialize", 1, JSONObject())).status)
        clock += 1_000
        assertTrue(c.blockedAddresses().isEmpty())
        open(c)
    }

    @Test fun unblockAndRotationLiftTheRefusal() {
        val c = core()
        c.kick(open(c))
        c.unblock("192.168.1.50")
        open(c)
        c.kick(c.sessionInfos().first().id)
        c.dropAllSessions()  // what token rotation and server stop do
        assertTrue(c.blockedAddresses().isEmpty())
    }

    @Test fun controllerKickAndClearAuditNotifyTheSettingsPage() {
        val dir = kotlin.io.path.createTempDirectory("ctl133").toFile()
        try {
            val ctl = RemoteMcpController(dir, "t")
            var changes = 0
            ctl.onChange = { changes++ }
            val tok = ctl.tokens.get()
            val h = mapOf("Authorization" to "Bearer $tok", "Accept" to accept, "Content-Type" to "application/json")
            val sid = ctl.core.handle(McpRequest("POST", "/mcp", h, req("initialize", 1, JSONObject()).toByteArray(), phone)).headers["Mcp-Session-Id"]!!
            assertTrue(ctl.kick(sid)); assertEquals(1, changes)
            assertFalse(ctl.kick(sid)); assertEquals(1, changes)
            ctl.unblock("192.168.1.50"); assertEquals(2, changes)
            ctl.audit.record("192.168.1.50", "list_dir", ".", 10, RemoteAudit.OK)
            assertEquals(1, ctl.audit.entries().size)
            ctl.clearAudit(); assertEquals(3, changes)
            assertTrue(ctl.audit.entries().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun auditEntriesNewestFirstAndCappedForThePage() {
        val dir = kotlin.io.path.createTempDirectory("audit133").toFile()
        try {
            var t = 0L
            val a = RemoteAudit(java.io.File(dir, "a.jsonl")) { t }
            repeat(AUDIT_SHOWN + 5) { t = it.toLong(); a.record("10.0.0.2", "read_file", "f$it.txt", it, RemoteAudit.OK) }
            val e = a.entries(AUDIT_SHOWN)
            assertEquals(AUDIT_SHOWN, e.size)
            assertEquals("f${AUDIT_SHOWN + 4}.txt", e.first().path)
        } finally { dir.deleteRecursively() }
    }

    @Test fun viewHelpers() {
        assertEquals("0 B", humanBytes(0)); assertEquals("1023 B", humanBytes(1023))
        assertEquals("1.0 KB", humanBytes(1024)); assertEquals("1.5 MB", humanBytes(1536 * 1024))
        listOf(RemoteAudit.OK, RemoteAudit.ERROR, RemoteAudit.TIMEOUT, RemoteAudit.UNKNOWN_TOOL).forEach { o ->
            val k = outcomeKey(o)!!
            for (l in listOf("en", "zh-CN", "zh-TW")) assertTrue(I18n.table(l).containsKey(k), "$l:$k")
        }
        assertNull(outcomeKey("weird"))
    }

    @Test fun newPageStringsInAllThreeLanguages() {
        val keys = listOf("remote.sessions", "remote.sessions.none", "remote.sessions.kick", "remote.sessions.kick_hint",
            "remote.sessions.blocked", "remote.sessions.unblock", "remote.audit", "remote.audit.hint", "remote.audit.clear",
            "remote.audit.clear_confirm", "remote.audit.empty", "remote.audit.truncated")
        for (l in listOf("en", "zh-CN", "zh-TW")) keys.forEach { assertTrue(I18n.table(l)[it].orEmpty().isNotBlank(), "$l:$it") }
        assertEquals("断开", I18n.tr("remote.sessions.kick", lang = "zh-CN"))
        assertEquals("中斷", I18n.tr("remote.sessions.kick", lang = "zh-TW"))
        assertTrue(I18n.tr("remote.sessions.kick_hint", "10", lang = "zh-CN").contains("10 分钟"))
    }
}
