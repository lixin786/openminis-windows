// OpenMinis Desktop — P2-1: one headless Edge / Chrome process driven over CDP.
// Launch: BrowserPolicy.launchArgs (port 0 → DevToolsActivePort, every request via FilterProxy).
// Tabs are flat CDP sessions. Top-level navigations (the model's own, redirects, link clicks, page
// scripts) are paused with Fetch (resourceType Document) and only continue when [gate] allows them;
// sub-resources are only subject to the proxy's network rules. Popups are closed at once.
// Shutdown = kill the whole process tree (probe §5: closing tabs does not give memory back), sweep
// leftovers that carry our profile path on their command line (crashpad-handler), delete the profile.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Why a top-level navigation was stopped by the gate (reported to the model). */
data class GateRefusal(val url: String, val reason: String, val registrable: String?)

class Tab(val targetId: String, val sessionId: String) {
    @Volatile var url: String = "about:blank"
    @Volatile var loaded: CompletableDeferred<Unit> = CompletableDeferred()
    @Volatile var navStarted: CompletableDeferred<Unit> = CompletableDeferred()
    val refusals = CopyOnWriteArrayList<GateRefusal>()
}

class HeadlessBrowser(
    private val executable: File,
    private val profileDir: File,
    private val proxyPort: Int,
    /** Synchronous decision for a paused top-level document request: null = continue, else why not. */
    private val gate: (url: String, networkId: String) -> GateRefusal?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var cdp: CdpClient? = null
    val tabs = CopyOnWriteArrayList<Tab>()
    private val creating = AtomicInteger()
    private val known = ConcurrentHashMap.newKeySet<String>()
    @Volatile var product: String = ""; private set
    @Volatile var startMs: Long = 0; private set
    /** Target types seen at start (diagnostics; expected "page" only — probe §2). */
    @Volatile var startTargets: String = ""; private set

    val alive: Boolean get() = process?.isAlive == true && cdp?.closed == false

    suspend fun start(timeoutMs: Long = 15_000) {
        val t0 = System.currentTimeMillis()
        ProcessSweep.deleteTree(profileDir)
        profileDir.mkdirs()
        val args = BrowserPolicy.launchArgs(executable.absolutePath, profileDir.absolutePath, proxyPort)
        val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process = p
        val portFile = File(profileDir, "DevToolsActivePort")
        var endpoint: Pair<Int, String>? = null
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (!p.isAlive) error("the browser exited at start (exit code ${p.exitValue()})")
            endpoint = runCatching { BrowserPolicy.parseDevToolsActivePort(portFile.readText(Charsets.UTF_8)) }.getOrNull()
            if (endpoint != null) break
            delay(50)
        }
        endpoint ?: run { kill(); error("the browser did not open its DevTools port within ${timeoutMs / 1000} s") }
        val client = CdpClient("ws://127.0.0.1:${endpoint.first}${endpoint.second}", ::onEvent)
        cdp = client
        client.connect()
        product = runCatching { client.send("Browser.getVersion").optString("product") }.getOrDefault("")
        client.send("Browser.setDownloadBehavior", JSONObject().put("behavior", "deny"))
        client.send("Target.setDiscoverTargets", JSONObject().put("discover", true))
        // Use the page the browser opened (about:blank) so only ONE page target exists (probe §2). Right after
        // start that target can still be replaced; retry briefly, then fall back to creating one.
        var tab: Tab? = null
        var types = ""
        for (attempt in 0 until 10) {
            val pages = client.send("Target.getTargets").optJSONArray("targetInfos") ?: JSONArray()
            val infos = (0 until pages.length()).map { pages.getJSONObject(it) }
            infos.forEach { known += it.optString("targetId") }
            types = infos.joinToString(",") { it.optString("type") }
            val first = infos.firstOrNull { it.optString("type") == "page" }
            if (first != null) tab = runCatching { attach(first.getString("targetId")) }.getOrNull()
            if (tab != null) break
            delay(100)
        }
        if (tab == null) newTab()
        startTargets = types
        startMs = System.currentTimeMillis() - t0
        AppLogger.info("Browser", "started $product in ${startMs} ms (pid ${p.pid()}, targets: $types)")
    }

    private suspend fun attach(targetId: String): Tab {
        val c = cdp!!
        known += targetId
        val sid = c.send("Target.attachToTarget", JSONObject().put("targetId", targetId).put("flatten", true)).getString("sessionId")
        val tab = Tab(targetId, sid)
        tabs += tab
        c.send("Page.enable", sessionId = sid)
        c.send("Fetch.enable", JSONObject().put("patterns", JSONArray().put(
            JSONObject().put("urlPattern", "*").put("resourceType", "Document").put("requestStage", "Request"))), sid)
        runCatching {
            c.send("Emulation.setDeviceMetricsOverride", JSONObject().put("width", 1280).put("height", 800)
                .put("deviceScaleFactor", 1).put("mobile", false), sid)
        }
        return tab
    }

    suspend fun newTab(): Tab {
        val c = cdp!!
        creating.incrementAndGet()
        try {
            val id = c.send("Target.createTarget", JSONObject().put("url", "about:blank")).getString("targetId")
            return attach(id)
        } finally { creating.decrementAndGet() }
    }

    suspend fun send(method: String, params: JSONObject = JSONObject(), tab: Tab? = null, timeoutMs: Long = 30_000): JSONObject =
        (cdp ?: error("browser not started")).send(method, params, tab?.sessionId, timeoutMs)

    private fun tabOf(sessionId: String?) = tabs.firstOrNull { it.sessionId == sessionId }

    private fun onEvent(ev: CdpEvent) {
        when (ev.method) {
            "Target.targetCreated" -> {
                val ti = ev.params.optJSONObject("targetInfo") ?: return
                val id = ti.optString("targetId")
                if (ti.optString("type") != "page" || !known.add(id)) return
                if (creating.get() > 0 && ti.optString("openerId").isEmpty()) return // the tab we are creating
                // A popup / window.open / target=_blank: not allowed (decision: no popups). Closing a tab is fine;
                // only memory reclaim needs the process kill (probe §5).
                AppLogger.info("Browser", "closed popup ${ti.optString("url").take(200)}")
                scope.launch { runCatching { cdp?.send("Target.closeTarget", JSONObject().put("targetId", id)) } }
            }
            "Target.detachedFromTarget" -> tabs.removeAll { it.sessionId == ev.params.optString("sessionId") }
            "Fetch.requestPaused" -> {
                val tab = tabOf(ev.sessionId) ?: return
                val reqId = ev.params.optString("requestId")
                val url = ev.params.optJSONObject("request")?.optString("url").orEmpty()
                val frame = ev.params.optString("frameId")
                val mainFrame = frame == tab.targetId
                val refusal = if (mainFrame) gate(url, ev.params.optString("networkId", reqId)) else null
                scope.launch {
                    runCatching {
                        if (refusal == null) cdp?.send("Fetch.continueRequest", JSONObject().put("requestId", reqId), tab.sessionId)
                        else {
                            tab.refusals += refusal
                            AppLogger.info("Browser", "top-level navigation refused: ${refusal.url.take(200)} — ${refusal.reason}")
                            cdp?.send("Fetch.failRequest", JSONObject().put("requestId", reqId).put("errorReason", "BlockedByClient"), tab.sessionId)
                        }
                    }
                }
            }
            "Page.frameStartedLoading" -> tabOf(ev.sessionId)?.let { t -> if (ev.params.optString("frameId") == t.targetId) t.navStarted.complete(Unit) }
            "Page.frameNavigated" -> {
                val f = ev.params.optJSONObject("frame") ?: return
                tabOf(ev.sessionId)?.let { t -> if (f.optString("parentId").isEmpty()) t.url = f.optString("url") + f.optString("urlFragment") }
            }
            "Page.loadEventFired" -> tabOf(ev.sessionId)?.loaded?.complete(Unit)
            "Page.frameStoppedLoading" -> tabOf(ev.sessionId)?.let { t -> if (ev.params.optString("frameId") == t.targetId) t.loaded.complete(Unit) }
            "Page.javascriptDialogOpening" -> tabOf(ev.sessionId)?.let { t ->
                scope.launch { runCatching { cdp?.send("Page.handleJavaScriptDialog", JSONObject().put("accept", false), t.sessionId) } }
            }
        }
    }

    /** Pid of the browser and every process it started (snapshot: children are lost once the parent dies). */
    fun processTree(): List<ProcessHandle> {
        val p = process ?: return emptyList()
        val h = p.toHandle()
        return listOf(h) + runCatching { h.descendants().toList() }.getOrDefault(emptyList())
    }

    /**
     * Free everything: kill the whole process tree (never Target.closeTarget — probe §5), then sweep
     * processes still carrying our profile on their command line (crashpad-handler, probe §6.5), then
     * delete the profile (probe §6.6: 262 files / 6.75 MB even for about:blank).
     */
    fun kill() {
        runCatching { cdp?.close() }
        cdp = null
        val tree = processTree()
        tree.reversed().forEach { runCatching { it.destroyForcibly() } }
        tree.forEach { runCatching { it.onExit().get(3, TimeUnit.SECONDS) } }
        process = null
        tabs.clear()
        ProcessSweep.killByProfile(profileDir)
        if (!ProcessSweep.deleteTree(profileDir)) AppLogger.warning("Browser", "could not fully delete ${profileDir.absolutePath}")
        scope.cancel()
    }

    /** Waits for [tab]'s load (or until [timeoutMs]); true = loaded. */
    suspend fun awaitLoad(tab: Tab, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { tab.loaded.await() } != null
}

/** Process / folder cleanup helpers (Windows: CIM query by command line; elsewhere /proc). */
object ProcessSweep {
    fun killByProfile(profile: File) {
        val needle = profile.absolutePath
        try {
            if (com.openminis.desktop.core.AppPaths.isWindows) {
                // Path passed via the environment: no quoting problems with spaces / non-ASCII user names.
                val script = "\$p=\$env:OM_BROWSER_PROFILE; Get-CimInstance Win32_Process -Filter \"Name='msedge.exe' or Name='chrome.exe'\" | " +
                    "Where-Object { \$_.CommandLine -and \$_.CommandLine.Contains(\$p) } | " +
                    "ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
                val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                pb.environment()["OM_BROWSER_PROFILE"] = needle
                val pr = pb.start()
                if (!pr.waitFor(8, TimeUnit.SECONDS)) pr.destroyForcibly()
            } else {
                File("/proc").listFiles()?.filter { it.name.all(Char::isDigit) }?.forEach { d ->
                    val cmd = runCatching { File(d, "cmdline").readBytes().toString(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                    if (cmd.contains(needle)) ProcessHandle.of(d.name.toLong()).ifPresent { it.destroyForcibly() }
                }
            }
        } catch (e: Exception) {
            AppLogger.warning("Browser", "leftover process sweep failed: ${e.message}")
        }
    }

    /** Delete [dir] recursively, retrying while Windows still holds files open. true = gone. */
    fun deleteTree(dir: File): Boolean {
        repeat(10) {
            if (!dir.exists()) return true
            dir.walkBottomUp().forEach { runCatching { it.delete() } }
            if (!dir.exists()) return true
            Thread.sleep(200)
        }
        return !dir.exists()
    }

    /** Leftover processes that still name [profile] on their command line (Linux/test use; Windows: empty). */
    fun survivors(profile: File): List<Long> {
        if (com.openminis.desktop.core.AppPaths.isWindows) return emptyList()
        val needle = profile.absolutePath
        return File("/proc").listFiles()?.filter { it.name.all(Char::isDigit) }?.mapNotNull { d ->
            val cmd = runCatching { File(d, "cmdline").readBytes().toString(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            if (cmd.contains(needle) && d.name.toLong() != ProcessHandle.current().pid()) d.name.toLong() else null
        }.orEmpty()
    }
}
