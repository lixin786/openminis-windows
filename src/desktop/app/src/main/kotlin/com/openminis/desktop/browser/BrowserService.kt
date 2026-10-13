// OpenMinis Desktop — P2-1: browser lifecycle (start on first use, idle exit after 5 min = kill the
// process tree, clean up on app exit), per-conversation domain grants, and the fixed actions the
// `browser` tool offers. The model never sends JavaScript: the scripts below are constants of this
// file (selectors are passed in as JSON string literals).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object BrowserLocator {
    /** Where Edge (first) and Chrome (fallback) are looked for, in order. */
    fun candidates(env: Map<String, String>, windows: Boolean): List<File> {
        if (!windows) {
            val path = env["PATH"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
            val names = listOf("microsoft-edge", "microsoft-edge-stable", "google-chrome", "google-chrome-stable", "chromium", "chromium-browser")
            return names.flatMap { n -> path.map { File(it, n) } }
        }
        val pf86 = env["ProgramFiles(x86)"] ?: "C:\\Program Files (x86)"
        val pf = env["ProgramFiles"] ?: "C:\\Program Files"
        val local = env["LOCALAPPDATA"]
        return buildList {
            add(File(pf86, "Microsoft\\Edge\\Application\\msedge.exe"))
            add(File(pf, "Microsoft\\Edge\\Application\\msedge.exe"))
            local?.let { add(File(it, "Microsoft\\Edge\\Application\\msedge.exe")) }
            add(File(pf, "Google\\Chrome\\Application\\chrome.exe"))
            add(File(pf86, "Google\\Chrome\\Application\\chrome.exe"))
            local?.let { add(File(it, "Google\\Chrome\\Application\\chrome.exe")) }
        }
    }

    /** [override] (Settings) when it exists, else the first candidate that exists. */
    fun find(override: String, env: Map<String, String> = System.getenv(), windows: Boolean = com.openminis.desktop.core.AppPaths.isWindows): File? {
        override.trim().takeIf { it.isNotEmpty() }?.let { File(it) }?.let { return it.takeIf { f -> f.isFile } }
        env["OPENMINIS_BROWSER"]?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        return candidates(env, windows).firstOrNull { it.isFile }
    }
}

class BrowserService(
    private val profileDir: File,
    val limits: BrowserLimits = BrowserLimits(),
    private val executable: () -> File?,
    upstream: (String, Boolean) -> Proxy = { _, _ -> Proxy.NO_PROXY },
    upstreamCredentials: (Proxy) -> Pair<String, String>? = { null },
    private val screenshotDir: () -> File,
    /** Address policy (tests may relax loopback to reach a local test server). */
    isBlocked: (java.net.InetAddress) -> String? = BrowserPolicy::blockedRange,
    resolver: (String) -> List<java.net.InetAddress> = { java.net.InetAddress.getAllByName(it).toList() },
    /** URL-level check of top-level navigations (tests relax localhost the same way). */
    private val urlCheck: (String) -> BrowserPolicy.UrlCheck = BrowserPolicy::checkTopLevelUrl,
) {
    val bytes = BrowserPolicy.ByteBudget(limits.maxPageBytes)
    val proxy = FilterProxy(bytes, resolver, isBlocked, upstream, upstreamCredentials)
    private val navigations = BrowserPolicy.NavigationBudget(limits.maxNavigationsPerTurn)
    private val lock = Mutex()
    @Volatile private var browser: HeadlessBrowser? = null
    @Volatile private var lastUsed = 0L
    @Volatile private var busy = false
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "browser-idle").apply { isDaemon = true } }
    private var idleTask: ScheduledFuture<*>? = null

    /** conversation id → registrable domains the user allowed there (memory only; ends with the app). */
    private val grants = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var conversation: String = ""
    @Volatile private var turn: Long = 0
    @Volatile private var allowlist: List<String> = emptyList()
    /** Navigation ids already counted (redirect hops share one). */
    private val countedNav = ConcurrentHashMap.newKeySet<String>()

    val running: Boolean get() = browser?.alive == true

    fun grantsOf(conv: String): Set<String> = grants[conv].orEmpty().toSet()
    fun grant(conv: String, registrable: String) { grants.getOrPut(conv) { ConcurrentHashMap.newKeySet() } += registrable }

    /** Called by the tool before each action. */
    fun context(conv: String, turnKey: Long, allow: List<String>) { conversation = conv; turn = turnKey; allowlist = allow }

    fun navigationsLeft(turnKey: Long): Int = limits.maxNavigationsPerTurn - navigations.used(turnKey)

    /** Top-level gate (redirects, clicks, page scripts, the model's own navigations). Never asks: refuses. */
    private fun gate(url: String, networkId: String): GateRefusal? {
        val c = urlCheck(url)
        if (c is BrowserPolicy.UrlCheck.Refused) return GateRefusal(url, c.reason, null)
        c as BrowserPolicy.UrlCheck.Ok
        if (BrowserPolicy.decide(c.registrable, grantsOf(conversation), allowlist) == BrowserPolicy.Grant.ASK)
            return GateRefusal(url, "${c.registrable} is not approved in this conversation", c.registrable)
        if (countedNav.add(networkId) && !navigations.tryUse(turn))
            return GateRefusal(url, "navigation limit reached (${limits.maxNavigationsPerTurn} per turn)", c.registrable)
        bytes.reset()
        return null
    }

    private suspend fun ensure(): HeadlessBrowser {
        browser?.takeIf { it.alive }?.let { return it }
        browser?.let { runCatching { it.kill() } }
        val exe = executable() ?: throw BrowserUnavailable("Microsoft Edge / Google Chrome was not found on this computer")
        val port = proxy.start()
        val b = HeadlessBrowser(exe, profileDir, port, ::gate)
        try { b.start() } catch (e: Exception) { runCatching { b.kill() }; throw e }
        browser = b
        scheduleIdle()
        return b
    }

    private fun scheduleIdle() {
        idleTask?.cancel(false)
        idleTask = timer.scheduleWithFixedDelay({
            val b = browser ?: return@scheduleWithFixedDelay
            if (!busy && System.currentTimeMillis() - lastUsed >= limits.idleExitMs) {
                AppLogger.info("Browser", "idle for ${limits.idleExitMs / 1000} s: killing the browser process tree")
                stopBrowser()
            } else if (!b.alive && !busy) stopBrowser()
        }, 15, 15, TimeUnit.SECONDS)
    }

    /** Kill the browser (tree) and clean the profile. The proxy stays for the next start. */
    @Synchronized fun stopBrowser() {
        val b = browser ?: return
        browser = null
        idleTask?.cancel(false)
        proxy.closeAll()
        runCatching { b.kill() }
    }

    /** App exit. */
    fun shutdown() {
        stopBrowser()
        runCatching { proxy.close() }
        timer.shutdownNow()
        if (profileDir.exists()) ProcessSweep.deleteTree(profileDir)
    }

    /** Runs one action under the call timeout with the browser started; keeps the idle clock. */
    suspend fun <T> use(block: suspend (HeadlessBrowser) -> T): T = lock.withLock {
        busy = true
        try {
            val b = ensure()
            withTimeoutOrNull(limits.toolCallTimeoutMs) { block(b) } ?: throw BrowserTimeout(limits.toolCallTimeoutMs)
        } finally {
            lastUsed = System.currentTimeMillis()
            busy = false
        }
    }

    // ------------------------------------------------------------------ actions

    data class Page(val tab: Int, val tabs: Int, val url: String, val title: String)

    fun tabAt(b: HeadlessBrowser, index: Int?): Tab {
        val i = (index ?: b.tabs.size) - 1
        return b.tabs.getOrNull(i) ?: throw BrowserActionError("there is no tab ${index ?: 0} (open tabs: ${b.tabs.size})")
    }

    suspend fun page(b: HeadlessBrowser, tab: Tab): Page {
        val title = runCatching { eval(b, tab, "document.title").toString() }.getOrDefault("")
        return Page(b.tabs.indexOf(tab) + 1, b.tabs.size, tab.url, title)
    }

    /** Navigate [tab] (the model's `open`). The domain must already be granted (the tool asks first). */
    suspend fun navigate(b: HeadlessBrowser, tab: Tab, url: String): String? {
        tab.refusals.clear()
        proxy.drainBlocks()
        tab.loaded = kotlinx.coroutines.CompletableDeferred()
        val r = b.send("Page.navigate", JSONObject().put("url", url), tab, limits.navigationTimeoutMs)
        val err = r.optString("errorText").takeIf { it.isNotEmpty() }
        val loaded = if (err == null) b.awaitLoad(tab, limits.navigationTimeoutMs) else true
        if (!loaded) runCatching { b.send("Page.stopLoading", tab = tab) }
        return describeFailure(tab, err, loaded)
    }

    private fun describeFailure(tab: Tab, err: String?, loaded: Boolean): String? {
        val notes = mutableListOf<String>()
        tab.refusals.forEach { notes += "Navigation to ${it.url} was blocked: ${it.reason}." + if (it.registrable != null && "not approved" in it.reason) " Call browser open with that URL to ask the user." else "" }
        proxy.drainBlocks().distinctBy { it.host to it.reason }.forEach { notes += "The network filter refused ${it.host}: ${it.reason}." }
        if (bytes.exceeded) notes += "The page went over the ${limits.maxPageBytes / (1024 * 1024)} MB transfer limit and was cut off."
        if (err != null && notes.isEmpty()) notes += "Navigation failed: $err."
        if (!loaded) notes += "The page was still loading after ${limits.navigationTimeoutMs / 1000} s; content may be partial."
        return notes.joinToString(" ").ifEmpty { null }
    }

    suspend fun eval(b: HeadlessBrowser, tab: Tab, expression: String): Any? {
        val r = b.send("Runtime.evaluate", JSONObject().put("expression", expression).put("returnByValue", true), tab)
        r.optJSONObject("exceptionDetails")?.let { ex ->
            throw BrowserActionError(ex.optJSONObject("exception")?.optString("description")?.substringBefore('\n') ?: ex.optString("text", "script error"))
        }
        return r.optJSONObject("result")?.opt("value")
    }

    suspend fun text(b: HeadlessBrowser, tab: Tab): String =
        (eval(b, tab, Scripts.TEXT) as? String).orEmpty()

    suspend fun links(b: HeadlessBrowser, tab: Tab): String {
        val arr = eval(b, tab, Scripts.LINKS) as? JSONArray ?: JSONArray()
        return (0 until arr.length()).joinToString("\n") { i -> val l = arr.getJSONArray(i); "${i + 1}. ${l.optString(0).ifBlank { "(no text)" }} — ${l.optString(1)}" }
    }

    suspend fun selectText(b: HeadlessBrowser, tab: Tab, selector: String): String {
        val arr = eval(b, tab, Scripts.selectText(selector)) as? JSONArray ?: throw BrowserActionError("invalid selector")
        if (arr.length() == 0) return "(no element matches $selector)"
        return (0 until arr.length()).joinToString("\n---\n") { arr.optString(it) }
    }

    /** Real mouse click at the element's centre; waits for a navigation it causes. */
    suspend fun click(b: HeadlessBrowser, tab: Tab, selector: String): String? {
        val pt = eval(b, tab, Scripts.locate(selector)) as? JSONArray ?: throw BrowserActionError("no visible element matches $selector")
        val x = pt.getDouble(0); val y = pt.getDouble(1)
        tab.refusals.clear(); proxy.drainBlocks()
        tab.navStarted = kotlinx.coroutines.CompletableDeferred()
        tab.loaded = kotlinx.coroutines.CompletableDeferred()
        for (type in listOf("mouseMoved", "mousePressed", "mouseReleased")) {
            val p = JSONObject().put("type", type).put("x", x).put("y", y)
            if (type != "mouseMoved") p.put("button", "left").put("clickCount", 1)
            b.send("Input.dispatchMouseEvent", p, tab)
        }
        return settle(b, tab)
    }

    suspend fun type(b: HeadlessBrowser, tab: Tab, selector: String, text: String, clear: Boolean, enter: Boolean): String? {
        val ok = eval(b, tab, Scripts.focus(selector, clear)) as? Boolean ?: false
        if (!ok) throw BrowserActionError("no element matches $selector")
        b.send("Input.insertText", JSONObject().put("text", text), tab)
        if (!enter) return null
        tab.refusals.clear(); proxy.drainBlocks()
        tab.navStarted = kotlinx.coroutines.CompletableDeferred()
        tab.loaded = kotlinx.coroutines.CompletableDeferred()
        for (type in listOf("rawKeyDown", "char", "keyUp")) {
            val p = JSONObject().put("type", type).put("key", "Enter").put("code", "Enter").put("windowsVirtualKeyCode", 13)
            if (type == "char") p.put("text", "\r")
            b.send("Input.dispatchKeyEvent", p, tab)
        }
        return settle(b, tab)
    }

    /** After an input: if a navigation starts within 1.5 s, wait for it to load (navigation timeout). */
    private suspend fun settle(b: HeadlessBrowser, tab: Tab): String? {
        val started = withTimeoutOrNull(1_500) { tab.navStarted.await() } != null
        if (!started) { delay(200); return describeFailure(tab, null, true) }
        val loaded = b.awaitLoad(tab, limits.navigationTimeoutMs)
        if (!loaded) runCatching { b.send("Page.stopLoading", tab = tab) }
        return describeFailure(tab, null, loaded)
    }

    suspend fun screenshot(b: HeadlessBrowser, tab: Tab): File {
        val r = b.send("Page.captureScreenshot", JSONObject().put("format", "png"), tab)
        val bytes = java.util.Base64.getDecoder().decode(r.getString("data"))
        val dir = screenshotDir().also { it.mkdirs() }
        val f = File(dir, "browser-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".png")
        f.writeBytes(bytes)
        return f
    }
}

class BrowserUnavailable(message: String) : Exception(message)
class BrowserTimeout(ms: Long) : Exception("the browser action did not finish within ${ms / 1000} s")
class BrowserActionError(message: String) : Exception(message)

/** The only JavaScript ever evaluated: fixed, read-only extraction / focus helpers. */
object Scripts {
    const val TEXT = "(document.body ? document.body.innerText : (document.documentElement ? document.documentElement.innerText : ''))"
    const val LINKS = "Array.from(document.links).slice(0, 500).map(a => [(a.innerText || a.title || '').trim().replace(/\\s+/g, ' ').slice(0, 200), a.href])"
    fun lit(s: String): String = JSONObject.quote(s)
    fun selectText(sel: String) = "(() => { try { return Array.from(document.querySelectorAll(${lit(sel)})).slice(0, 200).map(e => (e.innerText || e.value || e.textContent || '').trim()); } catch (e) { return null; } })()"
    fun locate(sel: String) = "(() => { let e; try { e = document.querySelector(${lit(sel)}); } catch (x) { return null; } if (!e) return null; " +
        "e.scrollIntoView({block: 'center', inline: 'center'}); const r = e.getBoundingClientRect(); if (r.width === 0 && r.height === 0) return null; " +
        "return [r.left + r.width / 2, r.top + r.height / 2]; })()"
    fun focus(sel: String, clear: Boolean) = "(() => { let e; try { e = document.querySelector(${lit(sel)}); } catch (x) { return false; } if (!e) return false; " +
        "e.scrollIntoView({block: 'center'}); e.focus(); " +
        (if (clear) "if ('value' in e) { e.value = ''; e.dispatchEvent(new Event('input', {bubbles: true})); } else if (e.isContentEditable) { e.textContent = ''; } " else "") +
        "return document.activeElement === e || e.contains(document.activeElement); })()"
}
