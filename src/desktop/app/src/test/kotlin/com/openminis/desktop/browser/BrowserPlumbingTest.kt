// P2-1: CDP id matching, the filtering proxy (resolve → judge IP → connect), the tool's switch and approvals.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import com.openminis.desktop.agent.Toolbox
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserPlumbingTest {

    // ---------------------------------------------------------------- CDP router

    @Test fun responsesAreMatchedByIdAndEventsAreSeparated() = runBlocking {
        val r = CdpRouter()
        val (id1, d1) = r.register("Page.navigate")
        val (id2, d2) = r.register("Runtime.evaluate")
        // Page.navigate pushes events before its response (probe §6.4); the reply for id2 may come first.
        val e1 = r.onMessage("""{"method":"Page.frameNavigated","params":{"frame":{"id":"F"}},"sessionId":"S1"}""")
        val e2 = r.onMessage("""{"method":"Page.frameStartedLoading","params":{"frameId":"F"}}""")
        assertNull(r.onMessage("""{"id":$id2,"result":{"result":{"value":"Example Domain"}}}"""))
        assertNull(r.onMessage("""{"id":$id1,"result":{"frameId":"F","loaderId":"L"}}"""))
        assertEquals("Page.frameNavigated", e1!!.method); assertEquals("S1", e1.sessionId)
        assertEquals("Page.frameStartedLoading", e2!!.method); assertNull(e2.sessionId)
        assertEquals("F", d1.await().getString("frameId"))
        assertEquals("Example Domain", d2.await().getJSONObject("result").getString("value"))
        assertEquals(0, r.pendingCount)
    }

    @Test fun errorsAndMethodNotFound() = runBlocking {
        val r = CdpRouter()
        val (id, d) = r.register("Fetch.enable")
        r.onMessage("""{"id":$id,"error":{"code":-32601,"message":"'Fetch.enable' wasn't found"}}""")
        val e = runCatching { d.await() }.exceptionOrNull() as CdpException
        assertTrue(e.methodNotFound); assertEquals("Fetch.enable", e.method)
        assertNull(r.onMessage("""{"id":999,"result":{}}""")) // unknown id ignored
        assertNull(r.onMessage("not json"))
    }

    // ---------------------------------------------------------------- filtering proxy

    private fun echoServer(): ServerSocket {
        val ss = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { c ->
                        val head = FilterProxy.readHead(c.getInputStream()) ?: return@use
                        val body = "seen:" + head.substringBefore("\r\n") + "|host:" + head.lines().firstOrNull { it.startsWith("Host:", true) }?.substringAfter(':')?.trim()
                        c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    }
                }
            }
        }
        return ss
    }

    private fun request(proxyPort: Int, raw: String): String = Socket(InetAddress.getLoopbackAddress(), proxyPort).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().write(raw.toByteArray()); s.getOutputStream().flush()
        s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    @Test fun proxyRefusesNamesThatResolveToBlockedAddresses() {
        var resolved = 0
        val p = FilterProxy(BrowserPolicy.ByteBudget(1 shl 20), resolver = { resolved++; listOf(InetAddress.getByName("127.0.0.1")) })
        val port = p.start()
        try {
            val r = request(port, "GET http://rebind.example/ HTTP/1.1\r\nHost: rebind.example\r\n\r\n")
            assertTrue(r.startsWith("HTTP/1.1 403"), r)
            val c = request(port, "CONNECT rebind.example:443 HTTP/1.1\r\nHost: rebind.example:443\r\n\r\n")
            assertTrue(c.startsWith("HTTP/1.1 403"), c)
            assertEquals(2, resolved, "the proxy resolves the name itself")
            assertEquals(0, p.served.get())
            assertTrue(p.drainBlocks().all { it.host == "rebind.example" && "loopback" in it.reason })
        } finally { p.close() }
    }

    @Test fun oneBlockedAddressAmongPublicOnesRefusesTheName() {
        val p = FilterProxy(BrowserPolicy.ByteBudget(1 shl 20), resolver = { listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.7")) })
        val port = p.start()
        try {
            assertTrue(request(port, "CONNECT mixed.example:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
            assertEquals(0, p.served.get())
        } finally { p.close() }
    }

    @Test fun literalPrivateAddressesNeverReachAResolver() {
        val p = FilterProxy(BrowserPolicy.ByteBudget(1 shl 20), resolver = { error("must not resolve literals") })
        val port = p.start()
        try {
            for (t in listOf("127.0.0.1:80", "[::1]:443", "169.254.169.254:80", "192.168.1.1:443", "[fd00::1]:443", "0.0.0.0:80"))
                assertTrue(request(port, "CONNECT $t HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"), t)
        } finally { p.close() }
    }

    @Test fun allowedRequestGoesToTheResolvedAddressInOriginForm() {
        val srv = echoServer()
        // Test-only policy: loopback allowed so a local server can stand in for a public site.
        val p = FilterProxy(BrowserPolicy.ByteBudget(1 shl 20), resolver = { listOf(InetAddress.getLoopbackAddress()) }, isBlocked = { null })
        val port = p.start()
        try {
            val r = request(port, "GET http://site.example:${srv.localPort}/a?b=1 HTTP/1.1\r\nHost: site.example:${srv.localPort}\r\nProxy-Connection: keep-alive\r\n\r\n")
            assertTrue(r.startsWith("HTTP/1.1 200"), r)
            assertTrue(r.contains("seen:GET /a?b=1 HTTP/1.1"), r)
            assertTrue(r.contains("|host:site.example:${srv.localPort}"), r)
            assertEquals(1, p.served.get())
        } finally { p.close(); srv.close() }
    }

    @Test fun pageByteBudgetCutsTheTransfer() {
        val big = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            big.accept().use { c ->
                FilterProxy.readHead(c.getInputStream())
                val chunk = ByteArray(64 * 1024)
                runCatching {
                    c.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
                    repeat(64) { c.getOutputStream().write(chunk) } // 4 MB
                }
            }
        }
        val budget = BrowserPolicy.ByteBudget(1L * 1024 * 1024)
        val p = FilterProxy(budget, resolver = { listOf(InetAddress.getLoopbackAddress()) }, isBlocked = { null })
        val port = p.start()
        try {
            val r = request(port, "GET http://big.example:${big.localPort}/ HTTP/1.1\r\nHost: x\r\n\r\n")
            assertTrue(r.length < 2 * 1024 * 1024, "cut near 1 MB, got ${r.length}")
            assertTrue(budget.exceeded)
            // Until the next navigation resets the budget, new connections are refused.
            assertTrue(request(port, "CONNECT other.example:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        } finally { p.close(); big.close() }
    }

    // ---------------------------------------------------------------- tool: switch + approvals (no browser started)

    private class Inner : Toolbox {
        override fun definitions() = emptyList<com.openminis.app.data.model.AgentToolDefinition>()
        override suspend fun execute(name: String, args: JSONObject) = ToolOutcome("inner")
    }

    private fun tmp(): File = Files.createTempDirectory("om-br").toFile()

    private fun service(started: MutableList<String> = mutableListOf()) = BrowserService(
        profileDir = File(tmp(), "profile"), executable = { started += "start"; null }, screenshotDir = { tmp() },
    )

    @Test fun switchOffRemovesTheToolAndRefusesCalls() = runBlocking {
        var s = AppSettings(browserEnabled = false)
        val tb = BrowserToolbox(Inner(), service(), { s }, ToolApprover { true }, { "c1" }, { 1 }, {})
        assertTrue(tb.definitions().none { it.name == "browser" })
        assertTrue(tb.execute("browser", JSONObject().put("action", "text")).denied)
        s = AppSettings(browserEnabled = true)
        assertTrue(tb.definitions().any { it.name == "browser" })
        s = AppSettings(browserEnabled = true, toolsEnabled = false)
        assertTrue(tb.definitions().none { it.name == "browser" }, "all tools off = no browser either")
    }

    @Test fun refusedSchemeIsNotAQuestion() = runBlocking {
        val asked = mutableListOf<ApprovalRequest>()
        val tb = BrowserToolbox(Inner(), service(), { AppSettings() }, ToolApprover { asked += it; true }, { "c1" }, { 1 }, {})
        for (u in listOf("file:///C:/x", "javascript:alert(1)", "http://169.254.169.254/", "http://127.0.0.1:47821/mcp")) {
            val r = tb.execute("browser", JSONObject().put("action", "open").put("url", u))
            assertTrue(r.denied, u)
        }
        assertTrue(asked.isEmpty())
    }

    @Test fun askedOncePerConversationPerRegistrableDomain() = runBlocking {
        val asked = mutableListOf<ApprovalRequest>()
        val starts = mutableListOf<String>()
        var conv = "c1"
        val svc = service(starts)
        val tb = BrowserToolbox(Inner(), svc, { AppSettings() }, ToolApprover { asked += it; true }, { conv }, { 1 }, {})
        tb.execute("browser", JSONObject().put("action", "open").put("url", "https://www.example.com/a"))
        tb.execute("browser", JSONObject().put("action", "open").put("url", "https://docs.example.com/b"))
        assertEquals(1, asked.size, "same eTLD+1 in the same chat: asked once")
        assertEquals("example.com", svc.grantsOf("c1").single())
        conv = "c2"
        tb.execute("browser", JSONObject().put("action", "open").put("url", "https://example.com/"))
        assertEquals(2, asked.size, "a new conversation asks again")
        assertEquals(com.openminis.desktop.security.ApprovalKind.BROWSER, asked[0].kind)
        assertTrue(asked[0].onAlwaysAllow != null)
    }

    @Test fun declineIsFinalAndAllowlistSkipsTheQuestion() = runBlocking {
        val asked = mutableListOf<ApprovalRequest>()
        val svc = service()
        val tb = BrowserToolbox(Inner(), svc, { AppSettings(browserAllowlist = listOf("example.org")) }, ToolApprover { asked += it; false }, { "c1" }, { 1 }, {})
        val no = tb.execute("browser", JSONObject().put("action", "open").put("url", "https://example.com/"))
        assertTrue(no.denied); assertTrue(svc.grantsOf("c1").isEmpty())
        val yes = tb.execute("browser", JSONObject().put("action", "open").put("url", "https://sub.example.org/"))
        assertFalse(yes.denied)
        assertTrue(yes.content.contains("not found"), "allowlisted: no question, straight to the (missing) browser: ${yes.content}")
        assertEquals(1, asked.size)
    }

    @Test fun alwaysAllowWritesTheDomain() = runBlocking {
        val added = mutableListOf<String>()
        val tb = BrowserToolbox(Inner(), service(), { AppSettings() }, ToolApprover { it.onAlwaysAllow?.invoke(); true }, { "c1" }, { 1 }, { added += it })
        tb.execute("browser", JSONObject().put("action", "open").put("url", "https://news.bbc.co.uk/x"))
        assertEquals(listOf("bbc.co.uk"), added)
    }

    @Test fun locatorPrefersEdgeThenChrome() {
        val env = mapOf("ProgramFiles(x86)" to "C:\\PF86", "ProgramFiles" to "C:\\PF", "LOCALAPPDATA" to "C:\\L")
        val c = BrowserLocator.candidates(env, windows = true).map { it.path }
        assertTrue(c.first().endsWith("msedge.exe")); assertTrue(c.indexOfFirst { it.endsWith("chrome.exe") } > c.indexOfLast { it.endsWith("msedge.exe") })
    }

    @Test fun scriptsQuoteSelectorsAsLiterals() {
        val s = Scripts.selectText("a'); fetch('//evil'); ('")
        assertTrue(s.contains("\"a'); fetch('//evil'); ('\""), s) // inside a JSON string literal, never code
    }
}
