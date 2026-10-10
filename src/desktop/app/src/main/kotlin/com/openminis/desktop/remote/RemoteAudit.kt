// OpenMinis Desktop — audit log of remote (phone) tool calls: time, source IP, tool, path, result bytes,
// outcome. Never the token, the Authorization header, or file contents. MCP-SERVER.md "审计".
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.remote

import org.json.JSONObject
import java.io.File

class RemoteAudit(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Entry(val time: Long, val ip: String, val tool: String, val path: String, val bytes: Int, val outcome: String)

    companion object {
        /** When the file passes this size, the oldest half is dropped. */
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_PATH = 300
        const val OK = "ok"
        const val ERROR = "error"
        const val TIMEOUT = "timeout"
        const val UNKNOWN_TOOL = "unknown_tool"
    }

    private val lock = Any()

    fun record(ip: String, tool: String, path: String, bytes: Int, outcome: String) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val line = JSONObject()
                .put("time", now()).put("ip", ip).put("tool", tool.take(64))
                .put("path", path.replace('\n', ' ').replace('\r', ' ').take(MAX_PATH))
                .put("bytes", bytes).put("outcome", outcome).toString()
            file.appendText(line + "\n", Charsets.UTF_8)
            if (file.length() > MAX_BYTES) {
                val lines = file.readLines(Charsets.UTF_8)
                file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
        }
        Unit
    }

    /** Newest first. */
    fun entries(limit: Int = 500): List<Entry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines(Charsets.UTF_8).asReversed().asSequence().mapNotNull { l ->
            runCatching {
                val o = JSONObject(l)
                Entry(o.getLong("time"), o.getString("ip"), o.getString("tool"), o.optString("path"), o.optInt("bytes"), o.optString("outcome"))
            }.getOrNull()
        }.take(limit).toList()
    }

    fun clear() = synchronized(lock) { runCatching { file.delete() }; Unit }
}
