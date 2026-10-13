// OpenMinis Desktop — P2-1 built-in browser: every security decision as a pure function.
// URL check for top-level navigations, IP classification (used by the filtering proxy on the
// addresses IT resolved), registrable-domain (eTLD+1) grouping, the approval decision, the limits
// and their counters. No I/O here: BrowserPolicyTest covers all of it.
// Design: docs/p2/P2-1-BROWSER-OPTIONS.md; real-machine constraints: P2-1-EDGE-PROBE-WINDOWS.md (main 8b5eab4).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** The decided limits (user's decision 2026-10-13 = the report's defaults). Not editable in the UI in v1. */
data class BrowserLimits(
    val navigationTimeoutMs: Long = 30_000,
    val toolCallTimeoutMs: Long = 60_000,
    val maxTextChars: Int = 100_000,
    val maxPageBytes: Long = 20L * 1024 * 1024,
    val maxNavigationsPerTurn: Int = 20,
    val maxTabs: Int = 3,
    val idleExitMs: Long = 5 * 60_000,
)

object BrowserPolicy {

    // ------------------------------------------------------------------ top-level URL

    sealed interface UrlCheck {
        data class Ok(val url: HttpUrl, val host: String, val registrable: String) : UrlCheck
        data class Refused(val reason: String) : UrlCheck
    }

    /** Schemes named in the decision, plus their close relatives; anything not http/https is refused anyway. */
    val REFUSED_SCHEMES = setOf(
        "file", "chrome", "edge", "devtools", "javascript", "data",
        "about", "blob", "filesystem", "view-source", "chrome-extension", "extension", "chrome-untrusted", "ws", "wss", "ftp",
    )

    /**
     * Checks a URL the model wants to open as a top-level page (or a top-level navigation the page
     * started: redirect, link click). Only http / https; host must not be localhost or an address in a
     * blocked range. A host NAME passes here — its addresses are judged by the proxy after it resolves
     * them itself (DNS rebinding: the URL layer cannot know what the name will resolve to later).
     */
    fun checkTopLevelUrl(raw: String): UrlCheck {
        val s = raw.trim()
        if (s.isEmpty()) return UrlCheck.Refused("empty URL")
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(s)?.groupValues?.get(1)?.lowercase()
            ?: return UrlCheck.Refused("not an absolute http(s) URL (add https://)")
        if (scheme in REFUSED_SCHEMES) return UrlCheck.Refused("the $scheme: scheme is not allowed (only http and https)")
        if (scheme != "http" && scheme != "https") return UrlCheck.Refused("the $scheme: scheme is not allowed (only http and https)")
        val url = s.toHttpUrlOrNull() ?: return UrlCheck.Refused("malformed URL")
        val host = url.host.lowercase().trimEnd('.')
        if (host.isEmpty()) return UrlCheck.Refused("URL has no host")
        if (host == "localhost" || host.endsWith(".localhost")) return UrlCheck.Refused("localhost is not reachable from the browser tool")
        literalAddress(host)?.let { addr -> blockedRange(addr)?.let { return UrlCheck.Refused("$host is in a blocked address range ($it)") } }
        return UrlCheck.Ok(url, host, registrableDomain(host))
    }

    // ------------------------------------------------------------------ IP ranges

    private data class Cidr(val name: String, val bytes: ByteArray, val prefix: Int)

    private fun cidr(name: String, base: String, prefix: Int) = Cidr(name, InetAddress.getByName(base).address, prefix)

    /** The decided block list (+ 0.0.0.0/8, 240/4 reserved and broadcast, which no public site uses). */
    private val V4 = listOf(
        cidr("\"this\" network 0.0.0.0/8", "0.0.0.0", 8),
        cidr("loopback 127.0.0.0/8", "127.0.0.0", 8),
        cidr("private 10.0.0.0/8", "10.0.0.0", 8),
        cidr("private 172.16.0.0/12", "172.16.0.0", 12),
        cidr("private 192.168.0.0/16", "192.168.0.0", 16),
        cidr("carrier-grade NAT 100.64.0.0/10", "100.64.0.0", 10),
        cidr("link-local 169.254.0.0/16 (incl. cloud metadata 169.254.169.254)", "169.254.0.0", 16),
        cidr("multicast 224.0.0.0/4", "224.0.0.0", 4),
        cidr("reserved 240.0.0.0/4 (incl. broadcast)", "240.0.0.0", 4),
    )
    private val V6 = listOf(
        cidr("unspecified ::", "::", 128),
        cidr("loopback ::1", "::1", 128),
        cidr("link-local fe80::/10", "fe80::", 10),
        cidr("site-local fec0::/10", "fec0::", 10),
        cidr("unique local fc00::/7", "fc00::", 7),
        cidr("multicast ff00::/8", "ff00::", 8),
    )

    private fun inCidr(a: ByteArray, c: Cidr): Boolean {
        if (a.size != c.bytes.size) return false
        var bits = c.prefix
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (c.bytes[i].toInt() and mask)) return false
            bits -= 8; i++
        }
        return true
    }

    /** IPv4 hidden inside an IPv6 address: ::ffff:a.b.c.d, ::a.b.c.d, 64:ff9b::/96 (NAT64), 2002::/16 (6to4). */
    internal fun embeddedV4(a: ByteArray): ByteArray? {
        if (a.size != 16) return null
        val z = (0 until 10).all { a[it] == 0.toByte() }
        if (z && a[10] == 0xFF.toByte() && a[11] == 0xFF.toByte()) return a.copyOfRange(12, 16)
        if (z && a[10] == 0.toByte() && a[11] == 0.toByte() && !(a[12] == 0.toByte() && a[13] == 0.toByte() && a[14] == 0.toByte())) return a.copyOfRange(12, 16)
        if (a[0] == 0.toByte() && a[1] == 0x64.toByte() && a[2] == 0xFF.toByte() && a[3] == 0x9B.toByte() && (4 until 12).all { a[it] == 0.toByte() }) return a.copyOfRange(12, 16)
        if (a[0] == 0x20.toByte() && a[1] == 0x02.toByte()) return a.copyOfRange(2, 6)
        return null
    }

    /** Name of the blocked range [addr] falls in, or null when it is a public address the browser may reach. */
    fun blockedRange(addr: InetAddress): String? {
        val b = addr.address
        return when (addr) {
            is Inet4Address -> V4.firstOrNull { inCidr(b, it) }?.name
            is Inet6Address -> V6.firstOrNull { inCidr(b, it) }?.name
                ?: embeddedV4(b)?.let { v4 -> V4.firstOrNull { inCidr(v4, it) }?.name?.let { "$it (IPv4 embedded in IPv6)" } }
            else -> "unknown address family"
        }
    }

    fun blockedRange(literal: String): String? = literalAddress(literal)?.let(::blockedRange)

    /**
     * [host] as an IP address when it is an IP literal — including the forms Chromium's URL parser
     * accepts for IPv4 (WHATWG: 1–4 parts, decimal / 0x hex / 0 octal: `2130706433`, `0x7f.1`, `0177.0.0.1`).
     * Never does a DNS lookup. null = a host name.
     */
    fun literalAddress(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.contains(':')) {
            if (!h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return null
            return runCatching { InetAddress.getByName(h) }.getOrNull()
        }
        return whatwgIpv4(h)?.let { InetAddress.getByAddress(it) }
    }

    internal fun whatwgIpv4(host: String): ByteArray? {
        val h = host.trimEnd('.')
        if (h.isEmpty()) return null
        val parts = h.split('.')
        if (parts.size > 4 || parts.any { it.isEmpty() }) return null
        // WHATWG: it is an IPv4 address only if the LAST part is numeric; otherwise a domain name.
        val nums = parts.map { parseIpv4Part(it) ?: return null }
        for (i in 0 until nums.size - 1) if (nums[i] > 255) return null
        val last = nums.last()
        val maxLast = 1L shl (8 * (5 - nums.size))
        if (last >= maxLast) return null
        var v = last
        for (i in 0 until nums.size - 1) v += nums[i] shl (8 * (3 - i))
        return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    private fun parseIpv4Part(p: String): Long? {
        val (digits, radix) = when {
            p.startsWith("0x") || p.startsWith("0X") -> p.substring(2) to 16
            p.length > 1 && p.startsWith("0") -> p.substring(1) to 8
            else -> p to 10
        }
        if (digits.isEmpty()) return if (radix == 16) 0 else null
        if (digits.length > 12) return null
        return digits.toLongOrNull(radix)?.takeIf { it >= 0 }
    }

    // ------------------------------------------------------------------ eTLD+1

    /**
     * The registrable domain (eTLD+1, public suffix list bundled in OkHttp — no new dependency):
     * `news.bbc.co.uk` → `bbc.co.uk`, `a.b.example.com` → `example.com`, IDN → punycode.
     * An IP literal is its own group; a public suffix itself (or an unknown single label) stays as is.
     */
    fun registrableDomain(host: String): String {
        val h = host.trim().lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return h
        literalAddress(h)?.let { return it.hostAddress }
        val url = HttpUrl.Builder().scheme("https").host(h).build()
        return url.topPrivateDomain() ?: url.host
    }

    /** An allowlist entry as typed in Settings (`https://www.Example.com/x`, `*.example.com`) → its registrable domain. */
    fun normalizeAllowlistEntry(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').removePrefix("*.").removePrefix(".")
        if (!s.startsWith("[")) s = s.substringBefore(':')
        if (s.isEmpty()) return null
        return runCatching { registrableDomain(s) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ approval decision

    enum class Grant { ALLOWLIST, CONVERSATION, ASK }

    /** Decision A: per registrable domain, asked once per conversation; "Always allow" = Settings allowlist. */
    fun decide(registrable: String, conversationGrants: Set<String>, allowlist: List<String>): Grant = when {
        allowlist.any { normalizeAllowlistEntry(it) == registrable } -> Grant.ALLOWLIST
        registrable in conversationGrants -> Grant.CONVERSATION
        else -> Grant.ASK
    }

    // ------------------------------------------------------------------ limits

    /** Text for the model: at most [max] chars, with a note saying how much was cut. */
    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text
        else text.take(max) + "\n\n[truncated: showing the first $max of ${text.length} characters]"

    /** Navigations of one user turn (decision: at most [max] per turn). Thread-safe. */
    class NavigationBudget(private val max: Int) {
        private var turn: Long = Long.MIN_VALUE
        private var used = 0
        /** true = this navigation may start (and is counted); false = the turn already used [max]. */
        @Synchronized fun tryUse(turnKey: Long): Boolean {
            if (turnKey != turn) { turn = turnKey; used = 0 }
            if (used >= max) return false
            used++; return true
        }
        @Synchronized fun used(turnKey: Long): Int = if (turnKey == turn) used else 0
    }

    /** Bytes the proxy delivered for the current page (reset at every navigation the tool starts). Thread-safe. */
    class ByteBudget(private val max: Long) {
        @Volatile var used: Long = 0; private set
        @Volatile var exceeded: Boolean = false; private set
        @Synchronized fun reset() { used = 0; exceeded = false }
        /** Count [n] bytes; false once the page went over [max] (the proxy then cuts every connection). */
        @Synchronized fun add(n: Long): Boolean {
            used += n
            if (used > max) exceeded = true
            return !exceeded
        }
    }

    /** Whether one more tab may be opened. */
    fun mayOpenTab(open: Int, limits: BrowserLimits): Boolean = open < limits.maxTabs

    // ------------------------------------------------------------------ launch

    /**
     * Command line of the headless browser (probe-verified set, P2-1-EDGE-PROBE-WINDOWS.md §1–2):
     * port 0 + DevToolsActivePort (never a fixed port), --disable-component-extensions-with-background-pages
     * (else 5 extra background pages), every request through our proxy — loopback too (`<-loopback>`,
     * Chromium otherwise bypasses the proxy for localhost) — and WebRTC UDP off so nothing goes around it.
     */
    fun launchArgs(executable: String, profileDir: String, proxyPort: Int): List<String> = listOf(
        executable,
        "--headless=new",
        "--user-data-dir=$profileDir",
        "--remote-debugging-port=0",
        "--remote-debugging-address=127.0.0.1",
        "--no-first-run", "--no-default-browser-check",
        "--disable-gpu",
        "--disable-component-extensions-with-background-pages",
        "--disable-background-mode", "--disable-default-apps",
        "--no-pings", "--disable-breakpad",
        "--disable-sync", "--disable-extensions",
        "--proxy-server=http://127.0.0.1:$proxyPort",
        "--proxy-bypass-list=<-loopback>",
        "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
        "--deny-permission-prompts",
        "about:blank",
    )

    /** `DevToolsActivePort`: line 1 = port, line 2 = browser WebSocket path. null while not (fully) written. */
    fun parseDevToolsActivePort(content: String): Pair<Int, String>? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val port = lines[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val path = lines[1].takeIf { it.startsWith("/devtools/browser/") } ?: return null
        return port to path
    }

    // ------------------------------------------------------------------ proxy request line

    data class ProxyTarget(val connect: Boolean, val host: String, val port: Int, val originForm: String)

    /**
     * First line of a request the browser sent to our proxy: `CONNECT host:443 HTTP/1.1` (HTTPS / wss tunnel)
     * or `GET http://host:port/path HTTP/1.1` (plain HTTP, absolute form). null = not a proxy request we serve.
     */
    fun parseProxyRequestLine(line: String): ProxyTarget? {
        val parts = line.trim().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val method = parts[0].uppercase()
        if (method == "CONNECT") {
            val hp = parts[1]
            val (host, port) = if (hp.startsWith("[")) {
                val end = hp.indexOf(']').takeIf { it > 0 } ?: return null
                hp.substring(1, end) to hp.substring(end + 1).removePrefix(":")
            } else hp.substringBeforeLast(':') to hp.substringAfterLast(':', "")
            val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (host.isEmpty()) return null
            return ProxyTarget(true, host.lowercase(), p, "")
        }
        val url = parts[1].toHttpUrlOrNull() ?: return null
        if (url.scheme != "http") return null
        val origin = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        return ProxyTarget(false, url.host.lowercase(), url.port, "$method $origin ${parts[2]}")
    }
}
