// OpenMinis Desktop — network settings for every OkHttp client (providers, MCP HTTP, skill import):
// proxy (System / None / Manual HTTP or SOCKS with optional auth + bypass list; env HTTPS_PROXY /
// HTTP_PROXY / ALL_PROXY / NO_PROXY honoured in System mode) and a connect+TLS watchdog so a stalled
// handshake becomes an error (→ retry / fallback) instead of an endless "Thinking…".
// Hotfix 2: HTTP protocol choice (Auto = HTTP/2 with per-host automatic HTTP/1.1 fallback after an
// HTTP/2 stream reset, or HTTP/1.1 only) and a stream-reset diagnostic that says whether the reset
// came from our own call.cancel() or from the peer.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.net

import com.openminis.app.logging.AppLogger
import kotlinx.serialization.Serializable
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
enum class ProxyMode(val label: String) {
    SYSTEM("System (Windows proxy settings / HTTPS_PROXY env)"),
    NONE("No proxy (direct)"),
    MANUAL("Manual"),
}

@Serializable
enum class ProxyType { HTTP, SOCKS }

@Serializable
enum class HttpProtocolMode(val label: String) {
    AUTO("Auto (HTTP/2, fall back to HTTP/1.1 on stream reset)"),
    HTTP1_ONLY("HTTP/1.1 only"),
}

@Serializable
data class NetworkSettings(
    val proxyMode: ProxyMode = ProxyMode.SYSTEM,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    /** In memory only on Windows (P3-1): settings.json keeps "" and [proxyPasswordStore] = "dpapi". */
    val proxyPassword: String = "",
    val proxyPasswordStore: String = "",
    /** Hosts that bypass the manual proxy: `;` or `,` separated, `*.corp.example`, `10.*`, `<local>`. */
    val bypass: String = "localhost;127.*;::1;<local>",
    /** TCP connect + proxy tunnel + TLS handshake must finish within this many seconds. */
    val connectTimeoutSeconds: Int = 20,
    /** HTTP/2 vs HTTP/1.1 for every client built through [DesktopHttp.harden]. */
    val httpProtocol: HttpProtocolMode = HttpProtocolMode.AUTO,
)

object DesktopHttp {
    @Volatile var settings: NetworkSettings = NetworkSettings()
        private set
    @Volatile var env: Map<String, String> = System.getenv()
    /** Last connect/TLS stall, for the error shown to the user. */
    @Volatile var lastStall: Pair<Long, String>? = null
        private set

    /** The OS proxy selector (java.net.useSystemProxies=true must be set before first use — done in main()). */
    private val systemSelector: ProxySelector? by lazy { ProxySelector.getDefault() }

    fun update(s: NetworkSettings) {
        settings = s
        AppLogger.info("Network", "proxy mode=${s.proxyMode}" + if (s.proxyMode == ProxyMode.MANUAL) " ${s.proxyType} ${s.proxyHost}:${s.proxyPort} auth=${s.proxyUser.isNotBlank()} bypass='${s.bypass}'" else "")
        installSocksAuthenticator(s)
    }

    /**
     * Apply proxy + watchdog (+ protocol choice) to a client a provider (or any desktop component)
     * built. [baseUrl] lets a host that already reset an HTTP/2 stream this session get HTTP/1.1.
     */
    fun harden(client: OkHttpClient, baseUrl: String? = null): OkHttpClient {
        val upstream = client.eventListenerFactory
        val b = client.newBuilder()
            .proxySelector(selector)
            .proxyAuthenticator(proxyAuth)
            .eventListenerFactory { call -> WatchdogListener(upstream.create(call)) }
        if (useHttp1(baseUrl)) b.protocols(HTTP1_PROTOCOLS)
        return b.build()
    }

    // ------------------------------------------------------------------ HTTP/2 → HTTP/1.1

    val HTTP1_PROTOCOLS: List<Protocol> = listOf(Protocol.HTTP_1_1)

    /** Hosts that reset an HTTP/2 stream this session; their clients are built HTTP/1.1-only. */
    private val http1Hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun hostOf(baseUrl: String?): String? =
        baseUrl?.let { runCatching { URI(it.trim()).host }.getOrNull() }?.lowercase()?.takeIf { it.isNotEmpty() }

    fun useHttp1(baseUrl: String?): Boolean =
        settings.httpProtocol == HttpProtocolMode.HTTP1_ONLY || hostOf(baseUrl)?.let { it in http1Hosts } == true

    fun isHttp1Downgraded(baseUrl: String?): Boolean = hostOf(baseUrl)?.let { it in http1Hosts } == true

    /**
     * Remember that [baseUrl]'s host cannot stream over HTTP/2. Returns true when this changed
     * anything (Auto mode, host not yet downgraded), i.e. a retry on a freshly built client is worth it.
     */
    fun downgradeToHttp1(baseUrl: String?, why: String): Boolean {
        if (settings.httpProtocol != HttpProtocolMode.AUTO) return false
        val host = hostOf(baseUrl) ?: return false
        if (!http1Hosts.add(host)) return false
        AppLogger.warning("Network", "HTTP/2 stream to $host was reset ($why) — using HTTP/1.1 for $host for the rest of this session")
        return true
    }

    fun resetHttp1Downgrades() = http1Hosts.clear()

    /** True when [e] (or a cause) is OkHttp's HTTP/2 RST_STREAM error (`stream was reset: …`). */
    fun isHttp2StreamReset(e: Throwable?): Boolean {
        var c = e
        var depth = 0
        while (c != null && depth++ < 12) {
            if (c.javaClass.name == "okhttp3.internal.http2.StreamResetException") return true
            if (c.message?.contains("stream was reset", ignoreCase = true) == true) return true
            c = c.cause
        }
        return false
    }

    /**
     * What the last HTTP/2 stream reset looked like, for the log and the error shown to the user:
     * protocol, ms since response headers / call start, and whether OUR side called cancel() first
     * (an RST_STREAM CANCEL we sent ourselves) or the peer reset the stream.
     */
    @Volatile var lastReset: Pair<Long, String>? = null
        private set

    /** One failed call, as [WatchdogListener] saw it. */
    data class StreamFailure(
        val atMs: Long, val host: String, val text: String,
        /** e.g. "HTTP proxy /127.0.0.1:7900"; null when the call went direct. */
        val viaProxy: String?,
        val afterHeaders: Boolean, val bodyComplete: Boolean, val reset: Boolean, val local: Boolean,
    ) {
        /** Response headers arrived, then the peer (not us) cut the body — through a proxy. */
        val looksLikeProxyCut: Boolean get() = viaProxy != null && afterHeaders && !bodyComplete && !local
    }

    /** Last failed call of any kind (any host). */
    @Volatile var lastFailure: StreamFailure? = null
        private set
    private val failuresByHost = ConcurrentHashMap<String, StreamFailure>()

    fun lastFailureFor(baseUrl: String?, withinMs: Long = 120_000): StreamFailure? =
        hostOf(baseUrl)?.let { failuresByHost[it] }?.takeIf { System.currentTimeMillis() - it.atMs <= withinMs }

    /**
     * User-facing hint when a stream to [baseUrl]'s host was cut after its headers while going
     * through a proxy (seen with Clash on 127.0.0.1:7900 cutting SSE at ~3 s; curl direct streams fine).
     */
    fun proxyCutHint(baseUrl: String?): String? {
        val f = lastFailureFor(baseUrl) ?: return null
        if (!f.looksLikeProxyCut) return null
        return "⚠️ The response from ${f.host} was cut after its headers while going through ${f.viaProxy}. " +
            "Some local proxies cut streaming (SSE) responses after a few seconds. If ${f.host} is reachable without the proxy, " +
            "set Settings → Network → Proxy: Direct, or add ${f.host} to the proxy bypass list."
    }

    fun clearFailures() { failuresByHost.clear(); lastFailure = null; lastReset = null }

    fun newClient(readTimeoutSeconds: Long = 60): OkHttpClient = harden(
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build(),
    )

    // ------------------------------------------------------------------ proxy selection

    fun matchesBypass(host: String, patterns: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        return patterns.split(';', ',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { p ->
            when {
                p == "*" -> true
                p == "<local>" -> !h.contains('.') && !h.contains(':')
                p.startsWith("*.") -> h.endsWith(p.drop(1)) || h == p.drop(2)
                p.startsWith(".") -> h.endsWith(p) || h == p.drop(1)
                p.endsWith("*") -> h.startsWith(p.dropLast(1))
                else -> h == p || h.endsWith(".$p") && !p.first().isDigit()
            }
        }
    }

    fun isLoopback(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        return h == "localhost" || h.endsWith(".localhost") || h.startsWith("127.") || h == "::1" || h == "0:0:0:0:0:0:0:1"
    }

    private fun envVar(name: String): String? =
        (env[name] ?: env[name.lowercase()] ?: env.entries.firstOrNull { it.key.equals(name, true) }?.value)?.trim()?.takeIf { it.isNotEmpty() }

    /** curl-style proxy URL from the environment: http://user:pass@host:port, socks5://host:port. */
    fun parseProxyUrl(raw: String): Pair<Proxy, Pair<String, String>?>? {
        val u = runCatching { URI(if ("://" in raw) raw else "http://$raw") }.getOrNull() ?: return null
        val host = u.host ?: return null
        val socks = u.scheme?.lowercase()?.startsWith("socks") == true
        val port = if (u.port > 0) u.port else if (socks) 1080 else 80
        val creds = u.userInfo?.split(':', limit = 2)?.let { it[0] to it.getOrElse(1) { "" } }
        return Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)) to creds
    }

    /** Which proxies to try for [uri] under the current settings. */
    fun select(uri: URI): List<Proxy> {
        val host = uri.host ?: return listOf(Proxy.NO_PROXY)
        val s = settings
        return when (s.proxyMode) {
            ProxyMode.NONE -> listOf(Proxy.NO_PROXY)
            ProxyMode.MANUAL -> {
                if (s.proxyHost.isBlank() || s.proxyPort !in 1..65535 || matchesBypass(host, s.bypass)) listOf(Proxy.NO_PROXY)
                else listOf(Proxy(if (s.proxyType == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(s.proxyHost.trim(), s.proxyPort)))
            }
            ProxyMode.SYSTEM -> {
                val envUrl = if (uri.scheme.equals("https", true)) envVar("HTTPS_PROXY") ?: envVar("ALL_PROXY") else envVar("HTTP_PROXY") ?: envVar("ALL_PROXY")
                if (envUrl != null) {
                    val noProxy = envVar("NO_PROXY").orEmpty()
                    // Loopback never goes through an env proxy (local Ollama / LM Studio / MCP servers).
                    if (isLoopback(host) || matchesBypass(host, noProxy)) listOf(Proxy.NO_PROXY)
                    else parseProxyUrl(envUrl)?.let { listOf(it.first) } ?: listOf(Proxy.NO_PROXY)
                } else {
                    runCatching { systemSelector?.select(uri) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
                }
            }
        }
    }

    private fun proxyCredentials(proxy: Proxy?): Pair<String, String>? {
        val s = settings
        if (s.proxyMode == ProxyMode.MANUAL && s.proxyUser.isNotBlank()) return s.proxyUser to s.proxyPassword
        if (s.proxyMode == ProxyMode.SYSTEM) {
            val envUrl = envVar("HTTPS_PROXY") ?: envVar("HTTP_PROXY") ?: envVar("ALL_PROXY") ?: return null
            return parseProxyUrl(envUrl)?.second
        }
        return null
    }

    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = this@DesktopHttp.select(uri)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            AppLogger.warning("Network", "proxy connect failed for $uri via $sa: ${ioe?.message}")
        }
    }

    private val proxyAuth = Authenticator { route: Route?, response: Response ->
        if (response.request.header("Proxy-Authorization") != null) return@Authenticator null // already tried
        // OkHttp asks before every HTTPS tunnel with a synthetic 407 ("OkHttp-Preemptive"): no credentials = plain CONNECT
        // through the same proxy. Not a real challenge, so nothing to log; it never falls back to a direct connection.
        val preemptive = response.header("Proxy-Authenticate").equals("OkHttp-Preemptive", ignoreCase = true)
        val creds = proxyCredentials(route?.proxy) ?: run {
            if (!preemptive) AppLogger.warning("Network", "proxy ${route?.proxy} requires authentication (HTTP 407) but no proxy user is configured")
            return@Authenticator null
        }
        response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(creds.first, creds.second)).build()
    }

    /** SOCKS auth goes through java.net.Authenticator (OkHttp can't do it). */
    private fun installSocksAuthenticator(s: NetworkSettings) {
        if (s.proxyMode == ProxyMode.MANUAL && s.proxyType == ProxyType.SOCKS && s.proxyUser.isNotBlank()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestingHost.equals(settings.proxyHost, true) || requestingProtocol?.startsWith("SOCKS", true) == true)
                        PasswordAuthentication(settings.proxyUser, settings.proxyPassword.toCharArray()) else null
            })
        }
    }

    // ------------------------------------------------------------------ watchdog

    private val timer by lazy { Timer("http-watchdog", true) }

    /**
     * Cancels a call whose TCP connect / proxy CONNECT / TLS handshake has not completed within
     * [NetworkSettings.connectTimeoutSeconds] (OkHttp's connectTimeout only covers the TCP connect;
     * the TLS handshake runs under the 10-minute read timeout). Delegates every event to [inner].
     */
    private class WatchdogListener(private val inner: EventListener) : EventListener() {
        private val tasks = ConcurrentHashMap<Call, TimerTask>()
        @Volatile private var startedAt = 0L
        @Volatile private var headersAt = 0L
        @Volatile private var canceledAt = 0L
        @Volatile private var cancelledBy: String? = null
        @Volatile private var protocol: Protocol? = null
        @Volatile private var bodyBytes = -1L

        @Volatile private var proxy: Proxy? = null
        @Volatile private var failureNoted = false

        /**
         * Records any I/O failure of a call (HTTP/2 reset, HTTP/1.1 EOF, socket reset…) with what is
         * needed to tell the causes apart: protocol, route (direct / proxy), timings, whether the
         * body ever completed, and whether OUR side cancelled first.
         */
        private fun noteFailure(call: Call, ioe: IOException) {
            if (failureNoted) return
            failureNoted = true
            val now = System.nanoTime()
            fun ms(t: Long) = if (t == 0L) "n/a" else "${(now - t) / 1_000_000}ms"
            // call.isCanceled() flips at the very start of Call.cancel(), before OkHttp sends its own
            // RST_STREAM(CANCEL) — the canceled() event can arrive after the failed read it caused.
            val local = call.isCanceled() || canceledAt != 0L
            val who = if (local) "LOCAL call.cancel()${if (canceledAt != 0L) " ${ms(canceledAt)} before the failure" else ""}${cancelledBy?.let { " ($it)" } ?: ""}"
                else "PEER (no local cancel recorded)"
            val p = proxy
            val via = p?.takeIf { it.type() != Proxy.Type.DIRECT }?.let { "${it.type()} proxy ${it.address()}" }
            val reset = isHttp2StreamReset(ioe)
            val host = call.request().url.host
            val text = "${if (reset) "HTTP/2 stream reset" else "Stream failed"} on $host: ${ioe.javaClass.simpleName}: ${ioe.message}; " +
                "protocol=${protocol ?: "?"}, route=${via ?: if (p == null) "?" else "DIRECT"}, " +
                "since call start ${ms(startedAt)}, since response headers ${ms(headersAt)}, body bytes=${if (bodyBytes < 0) 0 else bodyBytes}; " +
                "${if (reset) "reset" else "failure"} origin: $who"
            val f = StreamFailure(System.currentTimeMillis(), host, text, via, headersAt != 0L, bodyBytes >= 0, reset, local)
            lastFailure = f
            failuresByHost[host.lowercase()] = f
            if (reset) lastReset = f.atMs to text
            AppLogger.warning("Network", text)
        }

        private fun arm(call: Call, phase: () -> String) {
            val secs = settings.connectTimeoutSeconds.let { if (it < 3) 20 else it.coerceAtMost(300) }
            val t = object : TimerTask() {
                override fun run() {
                    val msg = "Connection to ${call.request().url.host} stalled: ${phase()} did not complete within ${secs}s " +
                        "(proxy mode ${settings.proxyMode}). If a proxy is needed, set it in Settings → Network."
                    lastStall = System.currentTimeMillis() to msg
                    AppLogger.warning("Network", msg)
                    cancelledBy = "connect/TLS watchdog"
                    call.cancel()
                }
            }
            tasks.put(call, t)?.cancel()
            timer.schedule(t, secs * 1000L)
        }
        private fun disarm(call: Call) { tasks.remove(call)?.cancel() }

        @Volatile private var phase = "TCP connect"
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            this.proxy = proxy
            phase = "TCP connect to $inetSocketAddress${if (proxy.type() != Proxy.Type.DIRECT) " via proxy $proxy" else ""}"
            arm(call) { phase }; inner.connectStart(call, inetSocketAddress, proxy)
        }
        override fun secureConnectStart(call: Call) { phase = "TLS handshake"; inner.secureConnectStart(call) }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) = inner.secureConnectEnd(call, handshake)
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { disarm(call); this.protocol = protocol; inner.connectEnd(call, inetSocketAddress, proxy, protocol) }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            disarm(call); AppLogger.warning("Network", "connect failed to $inetSocketAddress via $proxy: ${ioe.message}"); inner.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        }
        override fun callEnd(call: Call) { disarm(call); inner.callEnd(call) }
        override fun callFailed(call: Call, ioe: IOException) { disarm(call); noteFailure(call, ioe); inner.callFailed(call, ioe) }
        override fun canceled(call: Call) {
            if (canceledAt == 0L) canceledAt = System.nanoTime()
            disarm(call); inner.canceled(call)
        }
        // Plain delegation for the rest.
        override fun callStart(call: Call) { startedAt = System.nanoTime(); inner.callStart(call) }
        override fun proxySelectStart(call: Call, url: okhttp3.HttpUrl) = inner.proxySelectStart(call, url)
        override fun proxySelectEnd(call: Call, url: okhttp3.HttpUrl, proxies: List<Proxy>) = inner.proxySelectEnd(call, url, proxies)
        override fun dnsStart(call: Call, domainName: String) = inner.dnsStart(call, domainName)
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) = inner.dnsEnd(call, domainName, inetAddressList)
        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) { protocol = connection.protocol(); proxy = connection.route().proxy; inner.connectionAcquired(call, connection) }
        override fun connectionReleased(call: Call, connection: okhttp3.Connection) = inner.connectionReleased(call, connection)
        // A request on the wire means connect + TLS are done (also covers pooled / coalesced connections).
        override fun requestHeadersStart(call: Call) { disarm(call); inner.requestHeadersStart(call) }
        override fun requestHeadersEnd(call: Call, request: Request) = inner.requestHeadersEnd(call, request)
        override fun requestBodyStart(call: Call) = inner.requestBodyStart(call)
        override fun requestBodyEnd(call: Call, byteCount: Long) = inner.requestBodyEnd(call, byteCount)
        override fun requestFailed(call: Call, ioe: IOException) = inner.requestFailed(call, ioe)
        override fun responseHeadersStart(call: Call) = inner.responseHeadersStart(call)
        override fun responseHeadersEnd(call: Call, response: Response) { headersAt = System.nanoTime(); inner.responseHeadersEnd(call, response) }
        override fun responseBodyStart(call: Call) = inner.responseBodyStart(call)
        override fun responseBodyEnd(call: Call, byteCount: Long) { bodyBytes = byteCount; inner.responseBodyEnd(call, byteCount) }
        override fun responseFailed(call: Call, ioe: IOException) { noteFailure(call, ioe); inner.responseFailed(call, ioe) }
    }
}
