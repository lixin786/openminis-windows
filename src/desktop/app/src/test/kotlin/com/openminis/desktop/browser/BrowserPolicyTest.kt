// P2-1: pure security functions of the built-in browser.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.net.InetAddress

class BrowserPolicyTest {
    private fun refused(url: String) = BrowserPolicy.checkTopLevelUrl(url) is BrowserPolicy.UrlCheck.Refused
    private fun ok(url: String) = BrowserPolicy.checkTopLevelUrl(url) as BrowserPolicy.UrlCheck.Ok

    // ---------------------------------------------------------------- schemes

    @Test fun refusesNonHttpSchemesAsTopLevelNavigation() {
        for (u in listOf(
            "file:///C:/Windows/win.ini", "FILE:///etc/passwd", "chrome://settings", "edge://settings/passwords",
            "devtools://devtools/bundled/inspector.html", "javascript:alert(1)", "JavaScript:fetch('//x')",
            "data:text/html,<script>alert(1)</script>", "about:blank", "view-source:https://example.com",
            "blob:https://example.com/uuid", "chrome-extension://abc/x.html", "ftp://example.com/", "ws://example.com/",
        )) assertTrue(refused(u), u)
    }

    @Test fun refusesRelativeEmptyAndMalformed() {
        assertTrue(refused("")); assertTrue(refused("example.com")); assertTrue(refused("//example.com/")); assertTrue(refused("https://"))
    }

    @Test fun acceptsPublicHttpAndHttps() {
        assertEquals("example.com", ok("https://example.com/").registrable)
        assertEquals("example.com", ok("http://www.example.com:8080/a?b=c").registrable)
        assertEquals("93.184.216.34", ok("https://93.184.216.34/").registrable)
    }

    // ---------------------------------------------------------------- addresses

    private val blockedLiterals = listOf(
        "127.0.0.1", "127.255.255.254", "::1", "10.0.0.1", "10.255.255.255",
        "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.255",
        "100.64.0.1", "100.127.255.255", "169.254.0.1", "169.254.169.254",
        "fe80::1", "febf::1", "fc00::1", "fd12:3456::1", "0.0.0.0", "0.1.2.3",
        "224.0.0.1", "239.255.255.250", "ff02::1", "ff05::1:3", "255.255.255.255", "::",
        // IPv4 hidden in IPv6
        "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a00:1", "2002:c0a8:0101::1",
    )

    @Test fun blocksEveryDecidedRange() {
        for (a in blockedLiterals) assertNotNull(BrowserPolicy.blockedRange(InetAddress.getByName(a)), a)
    }

    @Test fun cloudMetadataIsNamed() {
        assertTrue(BrowserPolicy.blockedRange(InetAddress.getByName("169.254.169.254"))!!.contains("metadata"))
    }

    @Test fun rangeEdgesStayPublic() {
        for (a in listOf("172.15.255.255", "172.32.0.0", "100.63.255.255", "100.128.0.0", "11.0.0.0", "9.255.255.255",
            "192.167.255.255", "192.169.0.0", "169.253.255.255", "169.255.0.0", "223.255.255.255", "1.1.1.1", "8.8.8.8",
            "2606:4700:4700::1111", "2001:4860:4860::8888", "fec0::1".let { "2001:db8::1" }))
            assertNull(BrowserPolicy.blockedRange(InetAddress.getByName(a)), a)
    }

    @Test fun urlWithPrivateLiteralIsRefusedIncludingChromiumNumericForms() {
        for (u in listOf(
            "http://127.0.0.1/", "http://[::1]:8080/", "http://10.1.2.3/", "http://172.20.0.5/", "http://192.168.1.1/",
            "http://100.100.100.100/", "http://169.254.169.254/latest/meta-data/", "http://[fe80::1]/", "http://[fd00::1]/",
            "http://0.0.0.0:3000/", "http://224.0.0.251/", "http://[::ffff:127.0.0.1]/",
            // Chromium (WHATWG) parses these as 127.0.0.1 / 10.0.0.1 — the URL check must too.
            "http://2130706433/", "http://0x7f000001/", "http://0x7f.1/", "http://0177.0.0.1/", "http://127.1/", "http://10.1/",
            "http://localhost:8080/", "http://foo.localhost/",
        )) assertTrue(refused(u), u)
    }

    @Test fun whatwgIpv4Parsing() {
        fun ip(h: String) = BrowserPolicy.whatwgIpv4(h)?.let { InetAddress.getByAddress(it).hostAddress }
        assertEquals("127.0.0.1", ip("2130706433"))
        assertEquals("127.0.0.1", ip("0x7f.0.0.1"))
        assertEquals("127.0.0.1", ip("0177.0.0.01"))
        assertEquals("127.0.0.1", ip("127.1"))
        assertEquals("10.0.0.1", ip("10.0.1"))
        assertNull(ip("example.com")); assertNull(ip("256.1.1.1")); assertNull(ip("1.2.3.4.5")); assertNull(ip("4294967296"))
    }

    // ---------------------------------------------------------------- eTLD+1

    @Test fun registrableDomainGrouping() {
        assertEquals("example.com", BrowserPolicy.registrableDomain("a.b.example.com"))
        assertEquals("bbc.co.uk", BrowserPolicy.registrableDomain("news.bbc.co.uk"))
        assertEquals("example.com.cn", BrowserPolicy.registrableDomain("www.example.com.cn"))
        assertEquals("github.io".let { "user.github.io" }, BrowserPolicy.registrableDomain("user.github.io")) // private suffix: each user is a site
        assertEquals("xn--fiqs8s.com".let { BrowserPolicy.registrableDomain("www.中国.com") }, BrowserPolicy.registrableDomain("中国.com"))
        assertEquals("1.1.1.1", BrowserPolicy.registrableDomain("1.1.1.1"))
        assertEquals("co.uk", BrowserPolicy.registrableDomain("co.uk"))
        assertEquals("example.com", BrowserPolicy.registrableDomain("EXAMPLE.com."))
    }

    @Test fun allowlistEntriesNormalize() {
        assertEquals("example.com", BrowserPolicy.normalizeAllowlistEntry("https://www.Example.com/path?q=1"))
        assertEquals("example.com", BrowserPolicy.normalizeAllowlistEntry("*.example.com"))
        assertEquals("example.com", BrowserPolicy.normalizeAllowlistEntry("docs.example.com:443"))
        assertNull(BrowserPolicy.normalizeAllowlistEntry("   "))
    }

    // ---------------------------------------------------------------- approval decision

    @Test fun allowlistHitNeedsNoQuestion() {
        assertEquals(BrowserPolicy.Grant.ALLOWLIST, BrowserPolicy.decide("example.com", emptySet(), listOf("example.com")))
        assertEquals(BrowserPolicy.Grant.ALLOWLIST, BrowserPolicy.decide("example.com", emptySet(), listOf("https://docs.example.com/")))
        assertEquals(BrowserPolicy.Grant.CONVERSATION, BrowserPolicy.decide("example.com", setOf("example.com"), emptyList()))
        assertEquals(BrowserPolicy.Grant.ASK, BrowserPolicy.decide("example.org", setOf("example.com"), listOf("example.com")))
        // Same eTLD+1 = one question: a subdomain of an allowed site is allowed, a look-alike is not.
        assertEquals(BrowserPolicy.Grant.ASK, BrowserPolicy.decide("example.com.evil.net".let { BrowserPolicy.registrableDomain(it) }, setOf("example.com"), emptyList()))
    }

    // ---------------------------------------------------------------- limits

    @Test fun textIsTruncatedWithANote() {
        val t = "x".repeat(100_005)
        val r = BrowserPolicy.truncate(t, 100_000)
        assertTrue(r.startsWith("x".repeat(100_000)))
        assertTrue(r.contains("truncated") && r.contains("100000") && r.contains("100005"))
        assertEquals("short", BrowserPolicy.truncate("short", 100_000))
    }

    @Test fun navigationBudgetPerTurn() {
        val b = BrowserPolicy.NavigationBudget(20)
        repeat(20) { assertTrue(b.tryUse(1)) }
        assertFalse(b.tryUse(1)); assertEquals(20, b.used(1))
        assertTrue(b.tryUse(2)); assertEquals(1, b.used(2))
    }

    @Test fun byteBudgetTrips() {
        val b = BrowserPolicy.ByteBudget(20L * 1024 * 1024)
        assertTrue(b.add(20L * 1024 * 1024)); assertFalse(b.exceeded)
        assertFalse(b.add(1)); assertTrue(b.exceeded)
        b.reset(); assertFalse(b.exceeded); assertEquals(0, b.used)
    }

    @Test fun tabLimit() {
        val l = BrowserLimits()
        assertTrue(BrowserPolicy.mayOpenTab(2, l)); assertFalse(BrowserPolicy.mayOpenTab(3, l))
    }

    @Test fun decidedDefaults() {
        val l = BrowserLimits()
        assertEquals(30_000, l.navigationTimeoutMs); assertEquals(60_000, l.toolCallTimeoutMs); assertEquals(100_000, l.maxTextChars)
        assertEquals(20L * 1024 * 1024, l.maxPageBytes); assertEquals(20, l.maxNavigationsPerTurn); assertEquals(3, l.maxTabs); assertEquals(300_000, l.idleExitMs)
        assertTrue(com.openminis.desktop.core.AppSettings().browserEnabled, "decision 1: the switch defaults to ON")
    }

    // ---------------------------------------------------------------- launch + DevToolsActivePort + proxy line

    @Test fun launchArgsFollowTheProbe() {
        val a = BrowserPolicy.launchArgs("msedge.exe", "C:\\p", 41234)
        assertTrue("--disable-component-extensions-with-background-pages" in a)
        assertTrue("--remote-debugging-port=0" in a)
        assertTrue(a.none { it.startsWith("--remote-debugging-port=") && it != "--remote-debugging-port=0" })
        assertTrue("--proxy-server=http://127.0.0.1:41234" in a)
        assertTrue("--proxy-bypass-list=<-loopback>" in a, "loopback must go through the proxy too")
        assertTrue("--headless=new" in a && "--user-data-dir=C:\\p" in a)
    }

    @Test fun devToolsActivePort() {
        assertEquals(51234 to "/devtools/browser/abc-123", BrowserPolicy.parseDevToolsActivePort("51234\n/devtools/browser/abc-123\n"))
        assertEquals(51234 to "/devtools/browser/x", BrowserPolicy.parseDevToolsActivePort("51234\r\n/devtools/browser/x"))
        assertNull(BrowserPolicy.parseDevToolsActivePort("51234\n")) // half-written
        assertNull(BrowserPolicy.parseDevToolsActivePort(""))
        assertNull(BrowserPolicy.parseDevToolsActivePort("0\n/devtools/browser/x"))
    }

    @Test fun proxyRequestLines() {
        assertEquals(BrowserPolicy.ProxyTarget(true, "example.com", 443, ""), BrowserPolicy.parseProxyRequestLine("CONNECT example.com:443 HTTP/1.1"))
        assertEquals(BrowserPolicy.ProxyTarget(true, "::1", 443, ""), BrowserPolicy.parseProxyRequestLine("CONNECT [::1]:443 HTTP/1.1"))
        val g = BrowserPolicy.parseProxyRequestLine("GET http://Example.com:8080/a/b?c=d HTTP/1.1")!!
        assertEquals("example.com", g.host); assertEquals(8080, g.port); assertEquals("GET /a/b?c=d HTTP/1.1", g.originForm)
        assertNull(BrowserPolicy.parseProxyRequestLine("GET /relative HTTP/1.1"))
        assertNull(BrowserPolicy.parseProxyRequestLine("CONNECT example.com HTTP/1.1"))
        assertNull(BrowserPolicy.parseProxyRequestLine("GET https://example.com/ HTTP/1.1"))
    }
}
