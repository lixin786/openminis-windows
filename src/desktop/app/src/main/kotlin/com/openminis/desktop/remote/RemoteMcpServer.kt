// OpenMinis Desktop — the remote MCP server's sockets (JDK com.sun.net.httpserver, no extra dependency).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.remote

import com.openminis.app.logging.AppLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Settings › System › Phone link. Off by default: nothing listens until the user turns it on. */
@Serializable
data class RemoteMcpSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
) {
    companion object { const val DEFAULT_PORT = 47821 }
}

/**
 * One listening socket per selected private IPv4 address ([LanAddresses]); never the wildcard, never loopback.
 * At most 4 requests are handled at once (fixed pool).
 */
class RemoteMcpServer(
    private val core: McpHttpCore,
    /** Where to listen. Production: [LanAddresses.current]. Tests inject addresses. */
    private val addresses: () -> List<Pair<String, Inet4Address>> = LanAddresses::current,
) {
    data class Binding(val nic: String, val address: String, val port: Int)
    data class Status(val running: Boolean, val bindings: List<Binding>, val errors: List<String>)

    @Volatile var status = Status(false, emptyList(), emptyList()); private set
    private val servers = mutableListOf<HttpServer>()
    private var pool: ExecutorService? = null

    @Synchronized fun start(port: Int): Status {
        stop()
        val p = Executors.newFixedThreadPool(4) { r -> Thread(r, "remote-mcp").apply { isDaemon = true } }
        pool = p
        val bound = mutableListOf<Binding>(); val errors = mutableListOf<String>()
        for ((nic, addr) in addresses()) {
            // Defence in depth: whatever the provider returns, only private IPv4 is ever bound.
            if (!LanAddresses.isPrivateV4(addr) && !allowNonPrivateForTests) { errors += "${addr.hostAddress}: not a private IPv4 address"; continue }
            try {
                val s = HttpServer.create(InetSocketAddress(addr, port), 16)
                s.createContext("/") { ex -> serve(ex) }
                s.executor = p
                s.start()
                servers += s
                bound += Binding(nic, addr.hostAddress, port)
            } catch (e: Exception) {
                errors += "${addr.hostAddress}:$port ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
            }
        }
        if (bound.isEmpty() && errors.isEmpty()) errors += "no private IPv4 network interface"
        AppLogger.info("RemoteMcp", "listening on ${bound.joinToString { "${it.address}:${it.port}" }.ifEmpty { "nothing" }}" +
            if (errors.isEmpty()) "" else " (errors: ${errors.joinToString("; ")})")
        status = Status(bound.isNotEmpty(), bound, errors)
        return status
    }

    @Synchronized fun stop() {
        servers.forEach { runCatching { it.stop(0) } }
        servers.clear()
        pool?.shutdownNow(); pool = null
        core.dropAllSessions()
        status = Status(false, emptyList(), emptyList())
    }

    /** Only for socket-level tests on loopback; never set in the app. */
    internal var allowNonPrivateForTests = false

    private fun serve(ex: HttpExchange) {
        try {
            val body = ex.requestBody.use { it.readNBytes(McpHttpCore.MAX_BODY + 1) }
            val headers = buildMap { ex.requestHeaders.forEach { (k, v) -> if (k != null && v.isNotEmpty()) put(k, v.first()) } }
            val res = core.handle(McpRequest(ex.requestMethod, ex.requestURI.rawPath ?: "/", headers, body, ex.remoteAddress.address))
            res.headers.forEach { (k, v) -> ex.responseHeaders.set(k, v) }
            val bytes = res.body?.toByteArray(Charsets.UTF_8)
            if (bytes == null) ex.sendResponseHeaders(res.status, -1)
            else { ex.sendResponseHeaders(res.status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        } catch (e: Exception) {
            AppLogger.error("RemoteMcp", "exchange failed: ${e.javaClass.simpleName}")
        } finally { ex.close() }
    }
}

/** App-side owner: token file, core, server, and the settings they follow. */
class RemoteMcpController(appRoot: File, version: String) {
    val tokens = RemoteTokenStore(File(appRoot, "remote-mcp-token"))
    private var bindingsForOrigin: List<RemoteMcpServer.Binding> = emptyList()
    val core = McpHttpCore(
        token = { tokens.get() },
        serverVersion = version,
        allowedOrigins = { bindingsForOrigin.map { "http://${it.address}:${it.port}" }.toSet() },
    )
    val server = RemoteMcpServer(core)
    var onChange: () -> Unit = {}
    private var applied: RemoteMcpSettings? = null

    @Synchronized fun apply(s: RemoteMcpSettings, force: Boolean = false) {
        if (!force && s == applied) return
        applied = s
        if (s.enabled) server.start(s.port) else server.stop()
        bindingsForOrigin = server.status.bindings
        onChange()
    }

    /** Settings › Phone link › Rebind (network changed). */
    fun rebind() { applied?.let { apply(it, force = true) } }

    /** New token; every phone must `add` again with the new connection string. */
    fun rotateToken(): String { val t = tokens.rotate(); core.dropAllSessions(); onChange(); return t }

    fun shutdown() = server.stop()

    companion object {
        fun connectionString(address: String, port: Int, token: String) =
            "minis-mcp-cli add --name desktop --url http://$address:$port${McpHttpCore.PATH} --header \"Authorization: Bearer $token\""
    }
}
