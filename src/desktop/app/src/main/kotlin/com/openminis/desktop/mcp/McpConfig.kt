// OpenMinis Desktop — MCP server configuration (P1-1).
// Config shape follows upstream data/repository/MCPRepository.kt (Claude-Desktop-compatible
// `{ "mcpServers": { name: { command, args, env, … } } }`, `disabled: true` → enabled=false,
// startupTimeoutSeconds + its aliases, 4 import variants, last-write-wins by name) and the
// `$VAR / ${VAR} / $$VAR / $${VAR}` env expansion of upstream minis-mcp-cli transport/http.py.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.mcp

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * One configured MCP server. [name] is the key in `mcpServers`. Desktop supports the STDIO transport;
 * an entry with only a [url] (HTTP/SSE, upstream's second transport) is kept so import/export round-trips,
 * but it is never launched (P2).
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** Working directory; blank = the agent workspace. Desktop addition (upstream runs in the guest cwd). */
    val cwd: String = "",
    val enabled: Boolean = true,
    /** Upstream `note`: shown to the model next to the server's tools. */
    val note: String = "",
    /** Upstream [T-mcp-startup-timeout]: initialize handshake timeout; null = 60 s (upstream daemon default). */
    val startupTimeoutSeconds: Int? = null,
    /** Per tools/call timeout; null = 300 s (upstream stdio TIMEOUT_SECONDS). Desktop addition. */
    val callTimeoutSeconds: Int? = null,
    /** Ask before every tools/call of this server (desktop addition; off by default, the launch is always confirmed). */
    val confirmToolCalls: Boolean = false,
    /** HTTP/SSE transport URL (not supported on desktop yet — round-tripped only). */
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isStdio: Boolean get() = command.isNotBlank()
    val startupTimeoutMs: Long get() = (startupTimeoutSeconds?.takeIf { it > 0 } ?: McpDefaults.STARTUP_TIMEOUT_S).toLong() * 1000
    val callTimeoutMs: Long get() = (callTimeoutSeconds?.takeIf { it > 0 } ?: McpDefaults.CALL_TIMEOUT_S).toLong() * 1000

    /** Upstream `transportSummary`: the command line or the URL. */
    val transportSummary: String get() = if (isStdio) (listOf(command) + args).joinToString(" ") else url

    /**
     * Fingerprint of everything that decides WHAT process gets started. A user's "Always allow" is
     * bound to it, so editing the command / args / env / cwd asks again.
     */
    fun launchFingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun feed(s: String) { md.update(s.toByteArray(Charsets.UTF_8)); md.update(0) }
        feed(command); args.forEach(::feed); feed("|env")
        env.toSortedMap().forEach { (k, v) -> feed(k); feed(v) }
        feed("|cwd"); feed(cwd)
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}

object McpDefaults {
    /** Upstream minis-mcp-cli: initialize must finish within 60 s by default. */
    const val STARTUP_TIMEOUT_S = 60
    /** Upstream transport/stdio.py TIMEOUT_SECONDS. */
    const val CALL_TIMEOUT_S = 300
    /** Upstream daemon.py TTL_SECONDS: an idle server is stopped after 10 minutes. */
    const val IDLE_TTL_MS = 10L * 60 * 1000
    /** Upstream MCPRepository MAX_MCPS_IN_PROMPT / MAX_NOTE_LENGTH. */
    const val MAX_SERVERS = 20
    const val MAX_NOTE_LENGTH = 200
    /** protocolVersion sent in `initialize` (upstream minis-mcp-cli). */
    const val PROTOCOL_VERSION = "2025-06-18"
}

object McpConfigJson {
    /** Upstream MCPRepository.parseEntry. */
    fun parseEntry(name: String, entry: JSONObject): McpServerConfig {
        val enabled = when {
            entry.has("enabled") -> entry.optBoolean("enabled", true)
            entry.has("disabled") -> !entry.optBoolean("disabled", false)
            else -> true
        }
        val args = entry.optJSONArray("args")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
        return McpServerConfig(
            name = name.trim(),
            command = entry.optString("command", "").trim(),
            args = args,
            env = entry.optJSONObject("env")?.let(::stringMap) ?: emptyMap(),
            cwd = entry.optString("cwd", "").ifBlank { entry.optString("workingDirectory", "") },
            enabled = enabled,
            note = entry.optString("note", ""),
            startupTimeoutSeconds = parseStartupTimeout(entry),
            callTimeoutSeconds = entry.optInt("callTimeoutSeconds", 0).takeIf { it > 0 },
            confirmToolCalls = entry.optBoolean("confirmToolCalls", false),
            url = entry.optString("url", ""),
            headers = entry.optJSONObject("headers")?.let(::stringMap) ?: emptyMap(),
            createdAt = entry.optLong("createdAt", System.currentTimeMillis()),
        )
    }

    /** Upstream parseStartupTimeout: primary field, then the aliases other MCP clients use. */
    private fun parseStartupTimeout(entry: JSONObject): Int? {
        for (key in listOf("startupTimeoutSeconds", "startup_timeout_sec", "startupTimeout", "handshakeTimeout", "initializeTimeout")) {
            if (!entry.has(key) || entry.isNull(key)) continue
            when (val raw = entry.get(key)) {
                is Number -> return raw.toInt()
                is String -> raw.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun stringMap(obj: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) out[k] = obj.optString(k, "")
        return out
    }

    /** Upstream entryToJson (+ the desktop-only cwd / callTimeoutSeconds / confirmToolCalls keys). */
    fun entryToJson(s: McpServerConfig): JSONObject {
        val o = JSONObject()
        if (s.isStdio) {
            o.put("command", s.command)
            if (s.args.isNotEmpty()) o.put("args", JSONArray(s.args))
            if (s.env.isNotEmpty()) o.put("env", JSONObject(s.env))
            if (s.cwd.isNotBlank()) o.put("cwd", s.cwd)
            s.startupTimeoutSeconds?.let { o.put("startupTimeoutSeconds", it) }
            s.callTimeoutSeconds?.let { o.put("callTimeoutSeconds", it) }
            if (s.confirmToolCalls) o.put("confirmToolCalls", true)
        } else if (s.url.isNotBlank()) {
            o.put("url", s.url)
            if (s.headers.isNotEmpty()) o.put("headers", JSONObject(s.headers))
        }
        if (s.note.isNotBlank()) o.put("note", s.note)
        o.put("enabled", s.enabled)
        o.put("createdAt", s.createdAt)
        return o
    }

    /** Upstream exportServerJSON, for one or many servers. */
    fun export(servers: List<McpServerConfig>): String {
        // Built by hand: org.json on the JVM does not keep key order, and the list order is the user's.
        if (servers.isEmpty()) return "{\n  \"mcpServers\": {}\n}"
        val body = servers.joinToString(",\n") { s ->
            "    " + JSONObject.quote(s.name) + ": " + entryToJson(s).toString(2).replace("\n", "\n    ")
        }
        return "{\n  \"mcpServers\": {\n$body\n  }\n}"
    }

    /** Key order of a JSON object text (org.json's JSONObject is unordered on the JVM). */
    private fun orderedKeys(text: String, path: String?): List<String> = try {
        var el = kotlinx.serialization.json.Json.parseToJsonElement(text)
        if (path != null) el = (el as kotlinx.serialization.json.JsonObject)[path]!!
        (el as kotlinx.serialization.json.JsonObject).keys.toList()
    } catch (_: Exception) { emptyList() }

    private fun keysOf(obj: JSONObject, text: String, path: String?): List<String> {
        val order = orderedKeys(text, path)
        val all = obj.keys().asSequence().toSet()
        return order.filter { it in all } + all.filter { it !in order }
    }

    /**
     * Upstream importJSON / parseImportRoot — accepted shapes, probed in this order:
     *  1. `{ "mcpServers": { "name": { … } } }` (Claude Desktop / Cursor)
     *  3. a single bare entry `{ "command"|"url": … }` (name from `name`, else "imported-mcp")
     *  2. a name-keyed object `{ "name": { "command"|"url": … } }`
     *  4. any of them with `disabled: true` → enabled=false.
     * @throws IllegalArgumentException when the text is not a JSON object.
     */
    fun parseImport(text: String): List<McpServerConfig> {
        val root = try { JSONObject(text.trim()) } catch (e: Exception) {
            throw IllegalArgumentException("Not a JSON object: ${e.message}")
        }
        root.optJSONObject("mcpServers")?.let { obj ->
            return keysOf(obj, text.trim(), "mcpServers").mapNotNull { n -> obj.optJSONObject(n)?.let { parseEntry(n, it) } }
        }
        if (root.has("command") || root.has("url")) {
            return listOf(parseEntry(root.optString("name", "").ifBlank { "imported-mcp" }, root))
        }
        return keysOf(root, text.trim(), null).mapNotNull { n ->
            root.optJSONObject(n)?.takeIf { it.has("command") || it.has("url") }?.let { parseEntry(n, it) }
        }
    }

    /** Merge imported servers into [current]: last-write-wins by name (upstream), keeping list order. */
    fun merge(current: List<McpServerConfig>, incoming: List<McpServerConfig>): List<McpServerConfig> {
        val names = incoming.map { it.name }.toSet()
        return current.filter { it.name !in names } + incoming
    }
}

/** How a server's command line is turned into a host process (Windows `.cmd` shims, env expansion). */
object McpLaunch {
    private val ENV_RE = Regex("""\$\$?\{?([A-Za-z_][A-Za-z0-9_]*)\}?""")

    /**
     * Upstream expand_env: `$VAR`, `${VAR}`, `$$VAR`, `$${VAR}` → value from [env] (empty if unset).
     * Upstream documents `$$VAR` as the form to use in configs.
     */
    fun expand(value: String, env: Map<String, String>): String =
        ENV_RE.replace(value) { m -> lookup(env, m.groupValues[1]) }

    /** Windows env names are case-insensitive. */
    private fun lookup(env: Map<String, String>, name: String): String =
        env[name] ?: env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: ""

    /**
     * The argv handed to ProcessBuilder. On Windows `npx`, `uvx`, `pnpm`, … are `.cmd` / `.bat` shims
     * that CreateProcess cannot start directly, so a command without an extension, or with a .cmd/.bat
     * extension, runs through `cmd.exe /d /c` (cmd resolves PATH + PATHEXT, like Claude Desktop does).
     * An explicit `.exe` / `.com` path (or anything else) is started directly. No `/s`: with it cmd strips
     * the first and last quote of the line as soon as the command is quoted, which breaks quoted args.
     */
    fun commandLine(command: String, args: List<String>, isWindows: Boolean): List<String> {
        val cmd = command.trim()
        if (!isWindows) return listOf(cmd) + args
        val ext = File(cmd).name.substringAfterLast('.', "").lowercase()
        val viaCmd = ext.isEmpty() || ext == "cmd" || ext == "bat"
        return if (viaCmd) listOf("cmd.exe", "/d", "/c", cmd) + args else listOf(cmd) + args
    }

    /** Environment for the child: the app's environment + the server's `env` with `$VAR` references expanded. */
    fun environment(base: Map<String, String>, extra: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(base)
        extra.forEach { (k, v) -> out[k] = expand(v, base) }
        return out
    }
}
