// OpenMinis Desktop — P2-1 filtering proxy: the ONLY way out for the headless browser.
// Chromium is started with --proxy-server=http://127.0.0.1:<port> and --proxy-bypass-list=<-loopback>,
// so every request (pages, sub-resources, XHR, redirects, WebSocket, the browser's own background
// traffic) arrives here (probe §4: --proxy-server is really honoured). This proxy resolves the host
// name ITSELF, judges every resolved address with BrowserPolicy.blockedRange and then connects to that
// exact address — the name is never resolved a second time, so DNS rebinding cannot swap in a private
// address between the check and the connection. The app's own upstream proxy settings are applied
// (direct / HTTP CONNECT / SOCKS), always with the already-resolved IP as the destination.
// It also counts the bytes delivered per page (BrowserLimits.maxPageBytes) and cuts everything when
// a page goes over.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import com.openminis.app.logging.AppLogger
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class FilterProxy(
    private val budget: BrowserPolicy.ByteBudget,
    /** DNS lookup (injectable for tests). Must return every address of the name. */
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** Range name when an address must not be reached; null = allowed. */
    private val isBlocked: (InetAddress) -> String? = BrowserPolicy::blockedRange,
    /** Upstream proxy for (host, https) under the app's network settings; Proxy.NO_PROXY = direct. */
    private val upstream: (String, Boolean) -> Proxy = { _, _ -> Proxy.NO_PROXY },
    /** Basic credentials for an HTTP upstream proxy, if configured. */
    private val upstreamCredentials: (Proxy) -> Pair<String, String>? = { null },
    private val connectTimeoutMs: Int = 15_000,
) : Closeable {
    data class Block(val host: String, val reason: String, val at: Long = System.currentTimeMillis())

    private val counter = AtomicInteger()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "browser-proxy-${counter.incrementAndGet()}").apply { isDaemon = true }
    })
    private val open: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private var server: ServerSocket? = null
    @Volatile private var stopped = false

    /** Hosts refused since the last [drainBlocks] (the tool reports them to the model). */
    private val blocks = java.util.concurrent.ConcurrentLinkedQueue<Block>()
    /** Requests served (for diagnostics / tests). */
    val served = AtomicInteger()

    val port: Int get() = server?.localPort ?: 0

    /** Bind 127.0.0.1 on a system-chosen port; returns it. */
    @Synchronized fun start(): Int {
        server?.let { return it.localPort }
        val ss = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        server = ss
        pool.execute {
            while (!stopped) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                open += c
                pool.execute { handle(c) }
            }
        }
        return ss.localPort
    }

    fun drainBlocks(): List<Block> = buildList { while (true) add(blocks.poll() ?: break) }

    /** Cut every open connection (page went over its byte budget, or the browser is shutting down). */
    fun closeAll() { open.toList().forEach { runCatching { it.close() } }; open.clear() }

    override fun close() {
        stopped = true
        runCatching { server?.close() }
        closeAll()
        pool.shutdownNow()
    }

    private fun block(host: String, reason: String) {
        blocks += Block(host, reason)
        while (blocks.size > 50) blocks.poll()
        AppLogger.warning("Browser", "proxy refused $host: $reason")
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 30_000
            val cin = BufferedInputStream(client.getInputStream())
            val cout = client.getOutputStream()
            val head = readHead(cin) ?: return
            val lines = head.split("\r\n")
            val target = BrowserPolicy.parseProxyRequestLine(lines.first())
                ?: return reply(cout, 400, "Bad Request", "not a proxy request")
            if (budget.exceeded) {
                block(target.host, "page transfer limit reached")
                return reply(cout, 403, "Forbidden", "OpenMinis: page transfer limit reached")
            }
            // 1. resolve ourselves  2. judge EVERY address  3. connect to the judged address only.
            val addrs = try {
                BrowserPolicy.literalAddress(target.host)?.let { listOf(it) } ?: resolver(target.host)
            } catch (e: Exception) {
                return reply(cout, 502, "Bad Gateway", "OpenMinis: cannot resolve ${target.host}")
            }
            if (addrs.isEmpty()) return reply(cout, 502, "Bad Gateway", "OpenMinis: cannot resolve ${target.host}")
            for (a in addrs) isBlocked(a)?.let { why ->
                block(target.host, "${a.hostAddress} is in a blocked range ($why)")
                return reply(cout, 403, "Forbidden", "OpenMinis: ${target.host} resolves to a blocked address ($why)")
            }
            val https = target.connect
            val via = upstream(target.host, https)
            val (sock, viaHttpProxy) = connect(addrs, target.port, via, tunnel = target.connect)
                ?: return reply(cout, 502, "Bad Gateway", "OpenMinis: cannot connect to ${target.host}")
            remote = sock
            open += sock
            served.incrementAndGet()
            client.soTimeout = 0
            sock.soTimeout = 0
            val rin = sock.getInputStream()
            val rout = sock.getOutputStream()
            if (target.connect) {
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); cout.flush()
            } else {
                val ip = addrs.first().let { if (it is Inet6Address) "[${it.hostAddress}]" else it.hostAddress }
                rout.write(rewriteHead(lines, target, if (viaHttpProxy) "http://$ip:${target.port}" else null, via).toByteArray(Charsets.ISO_8859_1))
                rout.flush()
            }
            val up = pool.submit { pipe(cin, rout, count = false); runCatching { sock.shutdownOutput() } }
            pipe(rin, cout, count = true)
            up.cancel(true)
        } catch (_: Exception) {
            // connection closed / cut: nothing to report
        } finally {
            runCatching { client.close() }; open -= client
            remote?.let { runCatching { it.close() }; open -= it }
        }
    }

    /** Socket to one of [addrs] (already judged), directly or through the upstream proxy [via]. */
    private fun connect(addrs: List<InetAddress>, port: Int, via: Proxy, tunnel: Boolean): Pair<Socket, Boolean>? {
        for (a in addrs) {
            val dest = InetSocketAddress(a, port)
            val s = runCatching {
                when (via.type()) {
                    Proxy.Type.DIRECT, null -> Socket().apply { connect(dest, connectTimeoutMs) } to false
                    // SOCKS with a resolved InetSocketAddress sends the IP, never the name.
                    Proxy.Type.SOCKS -> Socket(via).apply { connect(dest, connectTimeoutMs) } to false
                    Proxy.Type.HTTP -> httpProxySocket(via, dest, tunnel)
                }
            }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    /**
     * HTTP upstream: for a tunnel we CONNECT <ip>:<port> ourselves and hand the raw socket on; for plain
     * HTTP we return the socket to the proxy and the request is rewritten to the absolute form with the IP
     * (plain HTTP is not tunnelled: many proxies only allow CONNECT to 443).
     */
    private fun httpProxySocket(via: Proxy, dest: InetSocketAddress, tunnel: Boolean): Pair<Socket, Boolean> {
        val pa = via.address() as InetSocketAddress
        val s = Socket()
        s.connect(if (pa.isUnresolved) InetSocketAddress(pa.hostString, pa.port) else pa, connectTimeoutMs)
        if (!tunnel) return s to true
        val ip = dest.address.let { if (it is Inet6Address) "[${it.hostAddress}]" else it.hostAddress }
        val auth = upstreamCredentials(via)?.let { "Proxy-Authorization: ${okhttp3.Credentials.basic(it.first, it.second)}\r\n" } ?: ""
        s.getOutputStream().write("CONNECT $ip:${dest.port} HTTP/1.1\r\nHost: $ip:${dest.port}\r\n$auth\r\n".toByteArray(Charsets.ISO_8859_1))
        s.getOutputStream().flush()
        s.soTimeout = connectTimeoutMs
        val resp = readHead(BufferedInputStreamNoReadAhead(s.getInputStream())) ?: run { s.close(); error("upstream proxy closed") }
        val code = resp.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull()
        if (code != 200) { s.close(); error("upstream proxy answered $code") }
        return s to false
    }

    private fun rewriteHead(lines: List<String>, t: BrowserPolicy.ProxyTarget, absoluteBase: String?, via: Proxy): String {
        val first = if (absoluteBase != null) {
            val p = t.originForm.split(' ')
            "${p[0]} $absoluteBase${p[1]} ${p[2]}"
        } else t.originForm
        val upgrade = lines.any { it.startsWith("Upgrade:", ignoreCase = true) }
        val kept = lines.drop(1).filter { it.isNotEmpty() }.filterNot {
            it.startsWith("Proxy-Connection:", true) || it.startsWith("Proxy-Authorization:", true) ||
                (!upgrade && it.startsWith("Connection:", true)) || it.startsWith("Keep-Alive:", true)
        }
        val auth = if (absoluteBase != null) upstreamCredentials(via)?.let { listOf("Proxy-Authorization: ${okhttp3.Credentials.basic(it.first, it.second)}") } ?: emptyList() else emptyList()
        // One request per connection: the response ends when the server closes, so bytes are counted per request.
        val conn = if (upgrade) emptyList() else listOf("Connection: close")
        return (listOf(first) + kept + auth + conn).joinToString("\r\n") + "\r\n\r\n"
    }

    private fun pipe(input: InputStream, out: OutputStream, count: Boolean) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try { input.read(buf) } catch (_: Exception) { -1 }
            if (n < 0) break
            if (count && !budget.add(n.toLong())) {
                block("(page)", "page transfer limit reached")
                closeAll()
                break
            }
            try { out.write(buf, 0, n); out.flush() } catch (_: Exception) { break }
        }
    }

    private fun reply(out: OutputStream, code: Int, text: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        runCatching {
            out.write("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\nX-OpenMinis-Blocked: 1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(b); out.flush()
        }
    }

    companion object {
        /** Request / response head up to the blank line (≤ 64 KB). */
        internal fun readHead(input: InputStream): String? {
            val bo = ByteArrayOutputStream()
            var state = 0
            while (bo.size() < 64 * 1024) {
                val c = input.read()
                if (c < 0) return null
                bo.write(c)
                state = when {
                    c == '\r'.code && (state == 0 || state == 2) -> state + 1
                    c == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
                if (state == 4) return String(bo.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
            }
            return null
        }
    }
}

/** Reads byte by byte from the raw stream (no read-ahead), so nothing after the upstream proxy's 200 is swallowed. */
private class BufferedInputStreamNoReadAhead(private val inner: InputStream) : InputStream() {
    override fun read(): Int = inner.read()
}
