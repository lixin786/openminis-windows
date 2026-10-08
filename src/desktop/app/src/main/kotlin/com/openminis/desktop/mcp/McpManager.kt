// OpenMinis Desktop — MCP server manager (P1-1): persisted server list, lazy start behind the user's
// confirmation, tool discovery, tool calls, crash restart, shutdown. The servers' tools are offered to
// the agent as native tools named `mcp__<server>__<tool>` (desktop deviation from upstream, which hands
// the model a `minis-mcp-cli` command line inside its Linux guest — there is no guest on Windows).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.mcp

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import com.openminis.desktop.agent.Toolbox
import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.core.atomicWrite
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One discovered MCP tool, as offered to the model. */
data class McpTool(val server: String, val name: String, val exposedName: String, val description: String, val inputSchema: JSONObject)

/** What the settings list shows per server. */
data class McpStatus(val state: String, val toolCount: Int = 0, val error: String? = null)

/** Persisted list (`mcp_servers.json`, upstream file name and `{ "mcpServers": … }` shape) + trusted launches. */
class McpStore(private val file: File, private val trustFile: File) {
    fun load(): List<McpServerConfig> = try {
        if (file.exists()) McpConfigJson.parseImport(file.readText(Charsets.UTF_8)) else emptyList()
    } catch (e: Exception) {
        AppLogger.error("MCP", "mcp_servers.json unreadable", e); emptyList()
    }
    fun save(list: List<McpServerConfig>) = atomicWrite(file, McpConfigJson.export(list))

    /** server name → launch fingerprint the user allowed permanently. */
    fun loadTrust(): Map<String, String> = try {
        if (!trustFile.exists()) emptyMap() else JSONObject(trustFile.readText(Charsets.UTF_8)).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    } catch (_: Exception) { emptyMap() }
    fun saveTrust(m: Map<String, String>) = atomicWrite(trustFile, JSONObject(m).toString(2))
}

class McpManager(
    private val store: McpStore,
    private val approver: ToolApprover,
    /** Working directory for servers without their own `cwd` (the agent workspace). */
    private val defaultCwd: () -> File,
    private val isWindows: Boolean = AppPaths.isWindows,
    private val baseEnv: () -> Map<String, String> = { System.getenv() },
    private val restartBackoffMs: List<Long> = listOf(500L, 2_000L),
) {
    @Volatile var servers: List<McpServerConfig> = store.load()
        private set
    private val trust = ConcurrentHashMap(store.loadTrust())
    /** Launches allowed for this app session only (dialog "Allow"). */
    private val sessionTrust = ConcurrentHashMap<String, String>()
    private val clients = ConcurrentHashMap<String, McpStdioClient>()
    private val tools = ConcurrentHashMap<String, List<McpTool>>()
    private val status = ConcurrentHashMap<String, McpStatus>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile var onChange: () -> Unit = {}

    fun status(name: String): McpStatus = status[name] ?: McpStatus(if (servers.firstOrNull { it.name == name }?.enabled == false) "disabled" else "stopped")
    fun tools(name: String): List<McpTool> = tools[name].orEmpty()
    fun isTrusted(s: McpServerConfig): Boolean = s.launchFingerprint().let { fp -> trust[s.name] == fp || sessionTrust[s.name] == fp }

    fun setServers(list: List<McpServerConfig>) {
        val byName = list.associateBy { it.name }
        // Stop servers that were removed, disabled or whose launch changed.
        servers.forEach { old ->
            val now = byName[old.name]
            if (now == null || !now.enabled || now.launchFingerprint() != old.launchFingerprint()) stop(old.name)
        }
        servers = list
        store.save(list)
        onChange()
    }

    /** The user explicitly started / tested this server in Settings: that is consent for this exact launch. */
    fun trustPermanently(s: McpServerConfig) {
        trust[s.name] = s.launchFingerprint(); store.saveTrust(HashMap(trust))
    }

    private fun lockFor(name: String) = locks.getOrPut(name) { Mutex() }

    private fun commandLineText(s: McpServerConfig): String =
        McpLaunch.commandLine(s.command, s.args.map { McpLaunch.expand(it, baseEnv()) }, isWindows).joinToString(" ")

    private suspend fun askLaunch(s: McpServerConfig): Boolean {
        if (isTrusted(s)) return true
        val detail = buildString {
            appendLine(commandLineText(s))
            appendLine("cwd: ${cwdOf(s).absolutePath}")
            if (s.env.isNotEmpty()) appendLine("env: ${s.env.keys.joinToString(", ")}")
        }.trim()
        val ok = approver.approve(ApprovalRequest("mcp", "Start MCP server «${s.name}» (runs a program on this computer)", detail,
            onAlwaysAllow = { trustPermanently(s) }))
        if (ok) sessionTrust[s.name] = s.launchFingerprint()
        return ok
    }

    private fun cwdOf(s: McpServerConfig): File = s.cwd.trim().takeIf { it.isNotEmpty() }?.let(::File) ?: defaultCwd()

    private fun setStatus(name: String, st: McpStatus) { status[name] = st; runCatching { onChange() } }

    /**
     * Start [s] if needed (asking the user unless the launch is trusted), handshake and list tools.
     * Returns the running client or throws [McpException].
     */
    suspend fun ensureStarted(s: McpServerConfig, interactive: Boolean = true): McpStdioClient = lockFor(s.name).withLock {
        clients[s.name]?.takeIf { it.isAlive }?.let { return@withLock it }
        clients.remove(s.name)?.close()
        if (!s.isStdio) throw McpException(McpException.Code.UNSUPPORTED, "MCP server '${s.name}' uses the HTTP transport, which the desktop app does not support yet.")
        if (!s.enabled) throw McpException(McpException.Code.DENIED, "MCP server '${s.name}' is disabled.")
        if (!(if (interactive) askLaunch(s) else isTrusted(s))) {
            setStatus(s.name, McpStatus("denied", error = "Start not allowed"))
            throw McpException(McpException.Code.DENIED, "The user did not allow starting MCP server '${s.name}'.")
        }
        setStatus(s.name, McpStatus("starting"))
        val env = McpLaunch.environment(baseEnv(), s.env)
        val argv = McpLaunch.commandLine(McpLaunch.expand(s.command, baseEnv()), s.args.map { McpLaunch.expand(it, env) }, isWindows)
        val c = McpStdioClient(s.name, argv, env, cwdOf(s))
        try {
            c.start()
            c.initialize(s.startupTimeoutMs)
            val listed = c.listTools(s.startupTimeoutMs)
            tools[s.name] = listed.mapNotNull { t ->
                val n = t.optString("name", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                McpTool(s.name, n, exposedName(s.name, n), t.optString("description", ""), t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"))
            }
            clients[s.name] = c
            setStatus(s.name, McpStatus("running", tools[s.name]!!.size))
            AppLogger.info("MCP", "server ${s.name}: ${tools[s.name]!!.size} tool(s)")
            c
        } catch (e: Exception) {
            c.close()
            val msg = (e as? McpException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
            setStatus(s.name, McpStatus("error", error = msg))
            throw (e as? McpException) ?: McpException(McpException.Code.START_FAILED, msg, e)
        }
    }

    /**
     * Before a turn: start every enabled server (each new / changed launch asks the user once) so the
     * model sees their tools. A server that fails or is refused is reported and skipped.
     */
    suspend fun prepare(): List<String> {
        val problems = mutableListOf<String>()
        for (s in servers.filter { it.enabled && it.isStdio }.take(McpDefaults.MAX_SERVERS)) {
            if (clients[s.name]?.isAlive == true) continue
            if (status[s.name]?.state == "denied" && !isTrusted(s)) continue // asked already this session
            try { ensureStarted(s) } catch (e: McpException) {
                if (e.code != McpException.Code.DENIED) problems += "MCP server «${s.name}»: ${e.message}"
            }
        }
        return problems
    }

    /** Settings › MCP › Connect: explicit user action → trusted launch, (re)start, tool count. */
    suspend fun connect(s: McpServerConfig): McpStatus {
        trustPermanently(s)
        stop(s.name)
        return try { ensureStarted(s.copy(enabled = true), interactive = false); status(s.name) } catch (e: McpException) { status(s.name) }
    }

    fun allTools(): List<McpTool> = servers.filter { it.enabled }.flatMap { tools[it.name].orEmpty() }

    fun definitions(): List<AgentToolDefinition> = allTools().map { t ->
        val props = t.inputSchema.optJSONObject("properties") ?: JSONObject()
        val params = LinkedHashMap<String, AgentToolParam>()
        props.keys().forEach { k ->
            val p = props.optJSONObject(k) ?: JSONObject()
            params[k] = AgentToolParam(p.optString("type", "string").ifEmpty { "string" }, p.optString("description", ""), rawSchema = p)
        }
        val req = t.inputSchema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val note = servers.firstOrNull { it.name == t.server }?.note?.take(McpDefaults.MAX_NOTE_LENGTH).orEmpty()
        AgentToolDefinition(
            name = t.exposedName,
            description = "[MCP server «${t.server}»${if (note.isNotBlank()) " — $note" else ""}] ${t.description}".trim().take(1024),
            parameters = params, required = req.filter { it in params },
        )
    }

    fun owns(exposedName: String): Boolean = allTools().any { it.exposedName == exposedName }

    suspend fun call(exposedName: String, args: JSONObject): ToolOutcome {
        val t = allTools().firstOrNull { it.exposedName == exposedName }
            ?: return ToolOutcome("Error: unknown MCP tool $exposedName", isError = true)
        val s = servers.firstOrNull { it.name == t.server } ?: return ToolOutcome("Error: MCP server ${t.server} was removed", isError = true)
        if (s.confirmToolCalls && !approver.approve(ApprovalRequest("mcp", "Call MCP tool «${t.name}» on «${s.name}»", args.toString(2).take(4000),
                onAlwaysAllow = { setServers(servers.map { if (it.name == s.name) it.copy(confirmToolCalls = false) else it }) }))) {
            return ToolOutcome("The user denied this MCP tool call.", isError = true, denied = true)
        }
        var attempt = 0
        while (true) {
            try {
                val c = ensureStarted(s)
                val res = c.callTool(t.name, args, s.callTimeoutMs)
                return mapResult(res)
            } catch (e: McpException) {
                // A crashed server is restarted (with backoff) and the call retried; anything else is the result.
                if (e.code == McpException.Code.CRASH && attempt < restartBackoffMs.size) {
                    AppLogger.warning("MCP", "server ${s.name} crashed during ${t.name}; restart ${attempt + 1}/${restartBackoffMs.size}")
                    stop(s.name, keepTools = true)
                    delay(restartBackoffMs[attempt]); attempt++
                    continue
                }
                return ToolOutcome("Error: ${e.message}", isError = true, denied = e.code == McpException.Code.DENIED)
            }
        }
    }

    fun stop(name: String, keepTools: Boolean = false) {
        clients.remove(name)?.close()
        if (!keepTools) tools.remove(name)
        status.remove(name)
        runCatching { onChange() }
    }

    fun shutdown() { clients.keys.toList().forEach { stop(it) } }

    companion object {
        private val SAFE = Regex("[^A-Za-z0-9_-]")

        /** `mcp__<server>__<tool>`, provider-safe characters, ≤ 64 chars (OpenAI / Anthropic limit). */
        fun exposedName(server: String, tool: String): String {
            val raw = "mcp__${SAFE.replace(server, "_")}__${SAFE.replace(tool, "_")}"
            if (raw.length <= 64) return raw
            val h = Integer.toHexString((server + "\u0000" + tool).hashCode()).padStart(8, '0')
            return raw.take(55) + "_" + h
        }

        /** MCP CallToolResult → tool result text (text / resource text; images and binary noted). */
        fun mapResult(res: JSONObject): ToolOutcome {
            val parts = mutableListOf<String>()
            val content = res.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> parts += c.optString("text")
                    "image", "audio" -> parts += "[${c.optString("type")} ${c.optString("mimeType")} — ${c.optString("data").length * 3 / 4} bytes, not shown]"
                    "resource" -> c.optJSONObject("resource")?.let { r ->
                        parts += if (r.has("text")) "[resource ${r.optString("uri")}]\n${r.optString("text")}" else "[resource ${r.optString("uri")} ${r.optString("mimeType")} (binary)]"
                    }
                    "resource_link" -> parts += "[resource link ${c.optString("uri")}${c.optString("name").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}]"
                    else -> parts += c.toString()
                }
            }
            if (parts.isEmpty()) res.optJSONObject("structuredContent")?.let { parts += it.toString(2) }
            val text = parts.joinToString("\n").ifEmpty { "(no content)" }
            return ToolOutcome(text, isError = res.optBoolean("isError", false))
        }
    }
}

/** Adds the MCP tools to a loop's toolbox. */
class McpToolbox(private val inner: Toolbox, private val mcp: McpManager) : Toolbox {
    override fun definitions(): List<AgentToolDefinition> {
        val own = inner.definitions()
        val names = own.map { it.name }.toSet()
        return own + mcp.definitions().filter { it.name !in names }
    }
    override suspend fun execute(name: String, args: JSONObject): ToolOutcome =
        if (name.startsWith("mcp__") && mcp.owns(name)) mcp.call(name, args) else inner.execute(name, args)
    override fun runsConcurrently(name: String) = inner.runsConcurrently(name)
}
