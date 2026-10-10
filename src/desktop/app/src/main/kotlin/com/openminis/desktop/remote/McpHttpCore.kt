// OpenMinis Desktop — transport-independent core of the remote (phone → desktop) MCP server.
// Streamable HTTP, protocol 2025-06-18, Bearer auth, read-only. Spec: MCP-SERVER.md at the repo root.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.remote

import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** One HTTP request as the core sees it. Header names are matched case-insensitively. */
class McpRequest(
    val method: String,
    val path: String,
    headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
) {
    val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }
    fun header(name: String): String? = headers[name.lowercase()]
}

class McpResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String? = null) {
    override fun toString() = "$status $headers $body"
}

/** What the server exposes. 13.1 ships none; 13.2 plugs the five read-only tools in here. */
interface RemoteToolSet {
    /** The `tools` array for tools/list. */
    fun list(): JSONArray
    /** A tools/call result object, or null when [name] is not a tool of this set. */
    fun call(name: String, arguments: JSONObject): JSONObject?

    object None : RemoteToolSet {
        override fun list() = JSONArray()
        override fun call(name: String, arguments: JSONObject): JSONObject? = null
    }
}

/**
 * Request pipeline, in a fixed order (MCP-SERVER.md "检查顺序"):
 *  1. network: path, source address, Origin, body size  → 404 / 403 / 413
 *  2. lockout                                           → 429 + Retry-After
 *  3. Bearer auth (initialize included)                 → 401
 *  4. HTTP verb and headers                             → 405 + Allow / 406 / 415 / 400 (protocol version)
 *  5. JSON body, then session                           → 400 parse/batch, 400 session required, 404 session not found
 *  6. JSON-RPC method dispatch
 *
 * Bodies the phone client should self-heal on (re-initialize) contain the word "session" in their first
 * 200 characters; every other error body must NOT contain it, or the client would reconnect in a loop.
 * The token and the Authorization header are never logged and never echoed.
 */
class McpHttpCore(
    private val token: () -> String,
    private val tools: () -> RemoteToolSet = { RemoteToolSet.None },
    private val serverVersion: String = "dev",
    private val now: () -> Long = System::currentTimeMillis,
    /** Origins accepted besides "no Origin header" (same-origin pages on the bound addresses). */
    private val allowedOrigins: () -> Set<String> = { emptySet() },
    /** Source-address check (private IPv4 only). Tests may widen it to loopback; production never does. */
    private val remoteAllowed: (InetAddress) -> Boolean = LanAddresses::isPrivateV4,
    /** 13.2: every tools/call is recorded here (time, IP, tool, path, bytes, outcome). */
    private val audit: RemoteAudit? = null,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val PATH = "/mcp"
        const val MAX_BODY = 1 shl 20
        const val MAX_SESSIONS = 32
        const val IDLE_MS = 30 * 60_000L
        const val FAIL_WINDOW_MS = 60_000L
        const val FAIL_LIMIT = 5
        const val LOCK_MS = 60_000L
        /** One tools/call may run this long; at most [MAX_CONCURRENT_CALLS] run at once. */
        const val CALL_TIMEOUT_MS = 10_000L
        const val MAX_CONCURRENT_CALLS = 4
        private const val TAG = "RemoteMcp"

        // Self-heal bodies (MUST contain "session" early). Fixed, short, nothing interpolated.
        const val BODY_SESSION_NOT_FOUND = """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"session not found"}}"""
        const val BODY_SESSION_REQUIRED = """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"session required: missing Mcp-Session-Id"}}"""

        private val JSON = mapOf("Content-Type" to "application/json")
        private fun err(status: Int, code: Int, message: String, extra: Map<String, String> = emptyMap()) =
            McpResponse(status, JSON + extra, JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                .put("error", JSONObject().put("code", code).put("message", message)).toString())
    }

    // ---------------------------------------------------------------- sessions (LRU, idle expiry)

    class Session(val id: String, val remote: String, var lastUsed: Long) {
        @Volatile var initialized = false
        var clientName: String = ""
    }

    private val rnd = SecureRandom()
    private val sessions = object : LinkedHashMap<String, Session>(16, 0.75f, true) {}
    private val lock = Any()

    fun sessionCount(): Int = synchronized(lock) { expire(); sessions.size }
    fun sessionsSnapshot(): List<Session> = synchronized(lock) { expire(); sessions.values.toList() }
    /** Token rotated or server stopped: every session ends at once (next request with the old token → 401). */
    fun dropAllSessions() = synchronized(lock) { sessions.clear() }

    private fun expire() {
        val t = now()
        sessions.values.removeIf { t - it.lastUsed > IDLE_MS }
    }

    private fun newSession(remote: InetAddress): Session = synchronized(lock) {
        expire()
        // Full: evict the least recently used one (the phone never sends DELETE; a daemon restart opens a new one).
        while (sessions.size >= MAX_SESSIONS) {
            val eldest = sessions.keys.first()
            sessions.remove(eldest)
            AppLogger.info(TAG, "evicted least recently used connection (limit $MAX_SESSIONS)")
        }
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(rnd::nextBytes))
        Session(id, remote.hostAddress, now()).also { sessions[id] = it }
    }

    private fun findSession(id: String): Session? = synchronized(lock) {
        expire()
        sessions[id]?.also { it.lastUsed = now() }  // get() refreshes the access order
    }

    private fun removeSession(id: String): Boolean = synchronized(lock) { sessions.remove(id) != null }

    // ---------------------------------------------------------------- failed-auth lockout per source address

    private class Failures { val times = ArrayDeque<Long>(); var lockedUntil = 0L }
    private val failures = HashMap<String, Failures>()

    /** Remaining lock in ms for this address, 0 when not locked. */
    private fun lockRemaining(ip: String): Long = synchronized(failures) {
        val f = failures[ip] ?: return 0
        (f.lockedUntil - now()).coerceAtLeast(0)
    }

    private fun recordFailure(ip: String) = synchronized(failures) {
        val t = now()
        val f = failures.getOrPut(ip) { Failures() }
        while (f.times.isNotEmpty() && t - f.times.first() > FAIL_WINDOW_MS) f.times.removeFirst()
        f.times.addLast(t)
        if (f.times.size >= FAIL_LIMIT) {
            f.lockedUntil = t + LOCK_MS
            f.times.clear()
            AppLogger.warning(TAG, "$ip locked for ${LOCK_MS / 1000}s after $FAIL_LIMIT failed authentications")
        }
        if (failures.size > 1024) failures.entries.removeIf { it.value.lockedUntil < t && it.value.times.isEmpty() }
    }

    private fun clearFailures(ip: String) = synchronized(failures) { failures.remove(ip) }

    private fun authorized(header: String?): Boolean {
        if (header == null) return false
        val parts = header.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return false
        val expected = token()
        if (expected.isEmpty()) return false
        return MessageDigest.isEqual(parts[1].trim().toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- pipeline

    fun handle(req: McpRequest): McpResponse {
        val ip = req.remote.hostAddress
        val res = try { pipeline(req, ip) } catch (e: Exception) {
            AppLogger.error(TAG, "request failed: ${e.javaClass.simpleName}")
            err(500, -32603, "internal error")
        }
        // Never log headers or bodies (the Authorization header carries the token).
        AppLogger.info(TAG, "$ip ${req.method} ${req.path.substringBefore('?').take(64)} -> ${res.status}")
        return res
    }

    private fun pipeline(req: McpRequest, ip: String): McpResponse {
        // 1. network
        if (req.path.substringBefore('?') != PATH) return McpResponse(404, JSON, """{"error":"not found"}""")
        if (!remoteAllowed(req.remote)) return McpResponse(403, JSON, """{"error":"forbidden: private network only"}""")
        req.header("Origin")?.let { o -> if (o !in allowedOrigins()) return McpResponse(403, JSON, """{"error":"forbidden origin"}""") }
        if (req.body.size > MAX_BODY) return McpResponse(413, JSON, """{"error":"request too large"}""")

        // 2. lockout
        val locked = lockRemaining(ip)
        if (locked > 0) {
            val secs = ((locked + 999) / 1000).toString()
            return McpResponse(429, JSON + ("Retry-After" to secs), """{"error":"too many failed attempts, retry later"}""")
        }

        // 3. auth — every request, initialize included
        if (!authorized(req.header("Authorization"))) {
            recordFailure(ip)
            return McpResponse(401, JSON + ("WWW-Authenticate" to "Bearer"), """{"error":"unauthorized"}""")
        }
        clearFailures(ip)

        // 4. verb and headers
        when (req.method.uppercase()) {
            "POST", "DELETE" -> Unit
            else -> return McpResponse(405, JSON + ("Allow" to "POST, DELETE"), """{"error":"method not allowed"}""")
        }
        req.header("MCP-Protocol-Version")?.let { v ->
            // Absent = 2025-06-18 (the phone client never sends it); only present-and-different is refused.
            if (v.trim() != PROTOCOL_VERSION) return err(400, -32600, "unsupported protocol version; this server speaks $PROTOCOL_VERSION")
        }
        val sid = req.header("Mcp-Session-Id")?.trim()?.takeIf { it.isNotEmpty() }

        if (req.method.equals("DELETE", ignoreCase = true)) {
            if (sid == null) return McpResponse(400, JSON, BODY_SESSION_REQUIRED)
            return if (removeSession(sid)) McpResponse(204) else McpResponse(404, JSON, BODY_SESSION_NOT_FOUND)
        }

        val accept = req.header("Accept").orEmpty().split(',').map { it.substringBefore(';').trim().lowercase() }
        if ("application/json" !in accept || "text/event-stream" !in accept)
            return err(406, -32600, "Accept must list application/json and text/event-stream")
        req.header("Content-Type")?.let { ct ->
            if (ct.substringBefore(';').trim().lowercase() != "application/json") return err(415, -32600, "Content-Type must be application/json")
        }

        // 5. body, then session
        val parsed = try { JSONTokener(String(req.body, Charsets.UTF_8)).nextValue() } catch (_: Exception) { null }
        if (parsed is JSONArray) return err(400, -32600, "batch requests are not supported in $PROTOCOL_VERSION")
        if (parsed !is JSONObject) return err(400, -32700, "parse error")
        val msg: JSONObject = parsed
        val method = msg.optString("method", "")
        val hasId = msg.has("id") && !msg.isNull("id")

        if (method == "initialize") {
            if (!hasId) return err(400, -32600, "initialize must be a request")
            if (sid != null && findSession(sid) != null) return rpcError(msg, -32600, "already initialized")
            // An unknown/stale header on initialize is ignored: that is the phone re-initializing after a 404.
            val s = newSession(req.remote)
            s.clientName = msg.optJSONObject("params")?.optJSONObject("clientInfo")?.optString("name", "").orEmpty().take(64)
            val result = JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "openminis-desktop").put("version", serverVersion))
                .put("instructions", "Read-only access to the OpenMinis desktop workspace.")
            return McpResponse(200, JSON + ("Mcp-Session-Id" to s.id), rpcResult(msg, result))
        }

        if (sid == null) return McpResponse(400, JSON, BODY_SESSION_REQUIRED)
        val session = findSession(sid) ?: return McpResponse(404, JSON, BODY_SESSION_NOT_FOUND)

        // 6. dispatch (error texts never echo client input: a "session" substring there would trigger a reconnect loop)
        if (method.isEmpty()) return McpResponse(202)  // a JSON-RPC response or error from the client
        if (!hasId) {                                    // notification
            if (method == "notifications/initialized") session.initialized = true
            return McpResponse(202)
        }
        return when (method) {
            "ping" -> McpResponse(200, JSON, rpcResult(msg, JSONObject()))
            "tools/list" -> if (!session.initialized) rpcError(msg, -32600, "not initialized: send notifications/initialized first")
                else McpResponse(200, JSON, rpcResult(msg, JSONObject().put("tools", tools().list())))
            "tools/call" -> {
                if (!session.initialized) return rpcError(msg, -32600, "not initialized: send notifications/initialized first")
                val p = msg.optJSONObject("params") ?: return rpcError(msg, -32602, "missing params")
                val name = p.optString("name", "")
                val args = p.optJSONObject("arguments") ?: JSONObject()
                val set = tools()
                val path = args.optString("path", "").ifEmpty { if (name == "list_dir" || name == "search_files") "." else "" }
                val fut = callPool.submit<JSONObject?> { set.call(name, args) }
                val result = try {
                    fut.get(callTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    fut.cancel(true)
                    audit?.record(ip, name.take(64), path, 0, RemoteAudit.TIMEOUT)
                    return McpResponse(200, JSON, rpcResult(msg, toolError("timed out after ${callTimeoutMs / 1000} s")))
                } catch (e: java.util.concurrent.ExecutionException) {
                    AppLogger.error(TAG, "tool call failed: ${e.cause?.javaClass?.simpleName}")
                    audit?.record(ip, name.take(64), path, 0, RemoteAudit.ERROR)
                    return McpResponse(200, JSON, rpcResult(msg, toolError("internal error")))
                }
                if (result == null) {
                    audit?.record(ip, "(unknown)", "", 0, RemoteAudit.UNKNOWN_TOOL)
                    return rpcError(msg, -32602, "unknown tool")
                }
                val bytes = result.optJSONArray("content")?.let { c -> (0 until c.length()).sumOf { c.optJSONObject(it)?.optString("text").orEmpty().toByteArray(Charsets.UTF_8).size } } ?: 0
                audit?.record(ip, name, path, bytes, if (result.optBoolean("isError")) RemoteAudit.ERROR else RemoteAudit.OK)
                McpResponse(200, JSON, rpcResult(msg, result))
            }
            else -> rpcError(msg, -32601, "method not found")
        }
    }

    private val callPool = java.util.concurrent.Executors.newFixedThreadPool(MAX_CONCURRENT_CALLS) { r ->
        Thread(r, "remote-mcp-tool").apply { isDaemon = true }
    }

    private fun toolError(message: String) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message))).put("isError", true)

    private fun rpcResult(req: JSONObject, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", req.get("id")).put("result", result).toString()

    private fun rpcError(req: JSONObject, code: Int, message: String) = McpResponse(200, JSON,
        JSONObject().put("jsonrpc", "2.0").put("id", req.opt("id") ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message)).toString())
}
