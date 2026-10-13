// OpenMinis Desktop — P2-1 minimal Chrome DevTools Protocol client over OkHttp's WebSocket
// (already a dependency: no new library). One socket to the browser endpoint; tabs are flat
// sessions (Target.attachToTarget flatten=true), every message carries its sessionId.
// Probe §6.4: Page.navigate pushes events before its response, so responses are matched by id and
// events are routed separately — never "send one, read one".
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CdpException(val code: Int, message: String, val method: String) : Exception(message) {
    /** -32601: the installed Edge / Chrome does not know this method (version drift, probe §7). */
    val methodNotFound: Boolean get() = code == -32601 || message?.contains("wasn't found", ignoreCase = true) == true
}

/** A CDP event: method, params, and the flat session it belongs to (null = browser level). */
data class CdpEvent(val method: String, val params: JSONObject, val sessionId: String?)

/**
 * Id ↔ response bookkeeping, independent of the transport (unit-tested): [register] before sending,
 * [onMessage] for every frame received. A frame with an `id` completes its waiter; a frame with a
 * `method` and no `id` is an event and is returned to the caller; anything else is ignored.
 */
class CdpRouter {
    private val next = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pair<String, CompletableDeferred<JSONObject>>>()

    fun register(method: String): Pair<Int, CompletableDeferred<JSONObject>> {
        val id = next.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = method to d
        return id to d
    }

    fun forget(id: Int) { pending.remove(id) }

    val pendingCount: Int get() = pending.size

    fun onMessage(text: String): CdpEvent? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.has("id")) {
            val id = o.optInt("id", -1)
            val (method, d) = pending.remove(id) ?: return null
            if (o.has("error")) {
                val e = o.getJSONObject("error")
                d.completeExceptionally(CdpException(e.optInt("code"), e.optString("message", "CDP error"), method))
            } else d.complete(o.optJSONObject("result") ?: JSONObject())
            return null
        }
        val method = o.optString("method", "")
        if (method.isEmpty()) return null
        return CdpEvent(method, o.optJSONObject("params") ?: JSONObject(), o.optString("sessionId", "").ifEmpty { null })
    }

    fun failAll(e: Throwable) {
        pending.values.forEach { it.second.completeExceptionally(e) }
        pending.clear()
    }
}

class CdpClient(private val wsUrl: String, private val onEvent: (CdpEvent) -> Unit) {
    private val router = CdpRouter()
    @Volatile private var ws: WebSocket? = null
    @Volatile var closed = false; private set
    @Volatile var closeReason: String? = null; private set

    /** Direct connection: the DevTools endpoint is on 127.0.0.1 and must never go through a proxy. */
    private val http = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(timeoutMs: Long = 5_000) {
        val opened = CountDownLatch(1)
        var failure: Throwable? = null
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = router.onMessage(text) ?: return
                runCatching { onEvent(ev) }.onFailure { com.openminis.app.logging.AppLogger.warning("Browser", "CDP event handler: ${it.message}") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { gone("closed $code $reason") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure = t; gone(t.message ?: t.javaClass.simpleName); opened.countDown() }
        })
        if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) { close(); error("DevTools WebSocket did not open within ${timeoutMs} ms") }
        failure?.let { throw IllegalStateException("DevTools WebSocket failed: ${it.message}", it) }
    }

    private fun gone(why: String) {
        closed = true
        if (closeReason == null) closeReason = why
        router.failAll(IllegalStateException("the browser connection closed ($why)"))
    }

    suspend fun send(method: String, params: JSONObject = JSONObject(), sessionId: String? = null, timeoutMs: Long = 30_000): JSONObject {
        val socket = ws ?: error("not connected")
        if (closed) error("the browser connection closed (${closeReason ?: "?"})")
        val (id, d) = router.register(method)
        val msg = JSONObject().put("id", id).put("method", method).put("params", params)
        if (sessionId != null) msg.put("sessionId", sessionId)
        if (!socket.send(msg.toString())) { router.forget(id); error("could not send $method (connection closing)") }
        return try {
            withTimeout(timeoutMs) { d.await() }
        } finally { router.forget(id) }
    }

    fun close() {
        closed = true
        runCatching { ws?.close(1000, "bye") }
        runCatching { ws?.cancel() }
        router.failAll(IllegalStateException("closed"))
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
