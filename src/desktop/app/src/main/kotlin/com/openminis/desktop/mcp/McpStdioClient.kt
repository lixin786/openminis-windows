// OpenMinis Desktop — MCP stdio transport (P1-1): JSON-RPC 2.0 over newline-delimited UTF-8 stdin/stdout
// of a child process. Protocol flow and defaults follow upstream minis-mcp-cli transport/stdio.py
// (initialize with protocolVersion 2025-06-18 → notifications/initialized → tools/list / tools/call,
// one JSON message per line, 300 s call timeout); the desktop client is long-lived and multiplexed
// (request ids + a reader thread) instead of one process per call, keeps stderr (upstream sends it to
// /dev/null) for the log and error messages, and pages tools/list with `cursor`.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.mcp

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpException(val code: Code, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Code { START_FAILED, CRASH, TIMEOUT, PROTOCOL, RPC_ERROR, DENIED, UNSUPPORTED, NOT_FOUND }
}

class McpStdioClient(
    val serverName: String,
    private val argv: List<String>,
    private val env: Map<String, String>,
    private val cwd: File?,
    private val clientVersion: String = "1.14.0",
) {
    private val tag = "MCP/$serverName"
    @Volatile private var process: Process? = null
    private var writer: Writer? = null
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)
    private val stderrTail = ArrayDeque<String>()
    @Volatile private var closedByUs = false
    @Volatile private var eof = false

    /** Result of `initialize` (serverInfo, capabilities, instructions). */
    @Volatile var initializeResult: JSONObject? = null
        private set
    /** Set when the server sends notifications/tools/list_changed. */
    @Volatile var toolsChanged = false

    val isAlive: Boolean get() = !closedByUs && !eof && process?.isAlive == true
    val pid: Long? get() = process?.pid()

    fun stderrTail(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

    fun start() {
        val pb = ProcessBuilder(argv)
        cwd?.let { it.mkdirs(); pb.directory(it) }
        pb.environment().clear()
        pb.environment().putAll(env)
        val p = try { pb.start() } catch (e: Exception) {
            throw McpException(McpException.Code.START_FAILED, "Cannot start MCP server '$serverName' (${argv.joinToString(" ")}): ${e.message}", e)
        }
        process = p
        // Explicit UTF-8 both ways: never the platform charset (GBK on zh-CN Windows).
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        AppLogger.info(tag, "started pid=${p.pid()} argv=${argv.joinToString(" ")} cwd=${cwd?.absolutePath}")
        Thread({ readStdout(p) }, "mcp-$serverName-out").apply { isDaemon = true; start() }
        Thread({ readStderr(p) }, "mcp-$serverName-err").apply { isDaemon = true; start() }
    }

    private fun readStdout(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    val msg = try { JSONObject(t) } catch (_: Exception) {
                        AppLogger.warning(tag, "non-JSON line on stdout: ${t.take(300)}")
                        continue
                    }
                    try { dispatch(msg) } catch (e: Exception) { AppLogger.warning(tag, "dispatch failed: ${e.message}") }
                }
            }
        } catch (_: Exception) {
            // stream closed
        } finally {
            eof = true
            val code = runCatching { if (p.waitFor(2, TimeUnit.SECONDS)) p.exitValue() else null }.getOrNull()
            if (!closedByUs) AppLogger.warning(tag, "server stdout closed (exit=${code ?: "running"})")
            val why = if (closedByUs) "MCP server '$serverName' was stopped."
            else "MCP server '$serverName' exited (code ${code ?: "?"}) before replying." +
                stderrTail().takeIf { it.isNotBlank() }?.let { "\nstderr:\n${it.takeLast(1500)}" }.orEmpty()
            pending.values.forEach { it.completeExceptionally(McpException(McpException.Code.CRASH, why)) }
            pending.clear()
        }
    }

    private fun readStderr(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    AppLogger.info(tag, "stderr: ${line.take(2000)}")
                    synchronized(stderrTail) {
                        stderrTail.addLast(line.take(500))
                        while (stderrTail.size > 20) stderrTail.removeFirst()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun dispatch(msg: JSONObject) {
        val method = msg.optString("method", "")
        if (method.isNotEmpty()) {
            if (msg.has("id") && !msg.isNull("id")) {
                // Server → client request. We advertise no client capabilities; answer ping, refuse the rest.
                val reply = JSONObject().put("jsonrpc", "2.0").put("id", msg.get("id"))
                when (method) {
                    "ping" -> reply.put("result", JSONObject())
                    "roots/list" -> reply.put("result", JSONObject().put("roots", JSONArray()))
                    else -> reply.put("error", JSONObject().put("code", -32601).put("message", "Method not found: $method"))
                }
                runCatching { send(reply) }
            } else when (method) {
                "notifications/tools/list_changed" -> toolsChanged = true
                "notifications/message" -> AppLogger.info(tag, "log: ${msg.optJSONObject("params")?.toString()?.take(1000)}")
                else -> {}
            }
            return
        }
        val idRaw = msg.opt("id") ?: return
        val id = when (idRaw) {
            is Number -> idRaw.toLong()
            is String -> idRaw.toLongOrNull() ?: return
            else -> return
        }
        pending.remove(id)?.complete(msg)
    }

    private fun send(obj: JSONObject) {
        val w = writer ?: throw McpException(McpException.Code.CRASH, "MCP server '$serverName' is not running")
        synchronized(writeLock) {
            try {
                // org.json escapes control characters (\n inside strings), so one message = one line.
                w.write(obj.toString())
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                throw McpException(McpException.Code.CRASH, "MCP server '$serverName' closed its stdin: ${e.message}", e)
            }
        }
    }

    fun notify(method: String, params: JSONObject? = null) {
        val o = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) o.put("params", params)
        send(o)
    }

    /** One JSON-RPC request; returns `result` or throws [McpException]. */
    suspend fun request(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        if (!isAlive) throw McpException(McpException.Code.CRASH, "MCP server '$serverName' is not running" +
            stderrTail().takeIf { it.isNotBlank() }?.let { "\nstderr:\n${it.takeLast(1500)}" }.orEmpty())
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        val o = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) o.put("params", params)
        try {
            send(o)
        } catch (e: Exception) {
            pending.remove(id); throw e
        }
        val reply = try {
            withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            if (!d.isCompleted) pending.remove(id)
        }
        if (reply == null) {
            runCatching { notify("notifications/cancelled", JSONObject().put("requestId", id).put("reason", "timeout")) }
            throw McpException(McpException.Code.TIMEOUT, "MCP server '$serverName' did not answer $method within ${timeoutMs / 1000.0}s")
        }
        reply.optJSONObject("error")?.let { err ->
            throw McpException(McpException.Code.RPC_ERROR, "MCP error ${err.opt("code") ?: ""}: ${err.optString("message", err.toString())}".trim())
        }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    /** initialize + notifications/initialized (upstream _handshake). */
    suspend fun initialize(timeoutMs: Long): JSONObject {
        val res = request("initialize", JSONObject()
            .put("protocolVersion", McpDefaults.PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "openminis-desktop").put("version", clientVersion)), timeoutMs)
        initializeResult = res
        notify("notifications/initialized")
        return res
    }

    /** tools/list, following `nextCursor` until the server stops sending one. */
    suspend fun listTools(timeoutMs: Long, maxPages: Int = 100): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var cursor: String? = null
        val seen = HashSet<String>()
        repeat(maxPages) {
            val params = cursor?.let { JSONObject().put("cursor", it) }
            val res = request("tools/list", params, timeoutMs)
            val arr = res.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(out::add)
            val next = res.optString("nextCursor", "").takeIf { res.has("nextCursor") && !res.isNull("nextCursor") && it.isNotEmpty() }
            if (next == null || !seen.add(next)) return out
            cursor = next
        }
        AppLogger.warning(tag, "tools/list: stopped after $maxPages pages")
        return out
    }

    suspend fun callTool(name: String, arguments: JSONObject, timeoutMs: Long): JSONObject =
        request("tools/call", JSONObject().put("name", name).put("arguments", arguments), timeoutMs)

    /**
     * Stop the server: close stdin (the MCP stdio shutdown signal), give it a moment, then kill the
     * whole process tree — `cmd /c npx …` leaves node as a grandchild that destroy() alone would orphan.
     */
    fun close(graceMs: Long = 500) {
        if (closedByUs) return
        closedByUs = true
        val p = process ?: return
        val tree = runCatching { p.toHandle().descendants().toList() }.getOrDefault(emptyList())
        runCatching { synchronized(writeLock) { writer?.close() } }
        val exited = runCatching { p.waitFor(graceMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        (tree + runCatching { p.toHandle().descendants().toList() }.getOrDefault(emptyList())).distinct().forEach { h ->
            runCatching { h.destroyForcibly() }
        }
        if (!exited) runCatching { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS) }
        AppLogger.info(tag, "stopped (pid=${p.pid()}, killed ${tree.size} descendant(s))")
        pending.values.forEach { it.completeExceptionally(McpException(McpException.Code.CRASH, "MCP server '$serverName' was stopped.")) }
        pending.clear()
    }
}
