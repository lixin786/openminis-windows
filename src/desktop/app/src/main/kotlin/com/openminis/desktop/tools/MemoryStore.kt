// OpenMinis Desktop — file-based memory, same layout as the Android app's memory tools:
// daily logs YYYY-MM-DD.md (agent-written) + GLOBAL.md (user-maintained, read-only to the agent).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.tools

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MemoryStore(val dir: File) {
    val globalFile: File get() = File(dir, "GLOBAL.md")

    init { dir.mkdirs() }

    fun write(content: String): String {
        val f = File(dir, "${LocalDate.now()}.md")
        val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val entry = "### $ts\n${content.trim()}\n\n"
        // Newest first, like the Android implementation (entries are prepended).
        val existing = if (f.exists()) f.readText() else ""
        f.writeText(entry + existing)
        return "Saved to ${f.name}"
    }

    private val search = MemorySearch(dir)

    /**
     * memory_get. Empty [keywords] dumps the files (upstream behaviour); otherwise a ranked
     * BM25 search over memory entries (see [MemorySearch]) replaces the old all-keywords match.
     */
    fun get(scope: String, keywords: String, limit: Int = 8): String {
        val files = buildList {
            if (scope == "all" && globalFile.exists()) add(globalFile)
            addAll((dir.listFiles { x -> x.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.md")) } ?: emptyArray()).sortedByDescending { it.name })
        }
        if (files.isEmpty()) return "No memories yet."
        if (keywords.isBlank()) {
            val sb = StringBuilder()
            for (f in files) {
                sb.append("## ${f.name}\n").append(f.readText().trim()).append("\n\n")
                if (sb.length > 20_000) break
            }
            return sb.toString().ifBlank { "No memories yet." }.take(20_000)
        }
        val hits = search.search(keywords, includeGlobal = scope == "all", limit = limit.coerceIn(1, 30))
        return search.format(keywords, hits)
    }

    /** Short context injected into the system prompt: GLOBAL.md + the two most recent daily logs. */
    fun promptContext(maxChars: Int = 6000): String? {
        val parts = mutableListOf<String>()
        if (globalFile.exists()) globalFile.readText().trim().takeIf { it.isNotEmpty() }?.let { parts += "### GLOBAL.md\n$it" }
        (dir.listFiles { x -> x.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.md")) } ?: emptyArray())
            .sortedByDescending { it.name }.take(2)
            .forEach { f -> f.readText().trim().takeIf { it.isNotEmpty() }?.let { parts += "### ${f.name}\n$it" } }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n").take(maxChars)
    }
}
