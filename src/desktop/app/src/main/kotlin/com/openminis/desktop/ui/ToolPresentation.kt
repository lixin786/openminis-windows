package com.openminis.desktop.ui

import com.openminis.desktop.core.ToolCall
import com.openminis.desktop.core.ToolStatus

/** What a tool card shows besides the raw call: kind (icon), readable name, duration, error line, grouping. Pure, unit-tested. */
object ToolPresentation {
    enum class Kind { SHELL, FILE, MEMORY, MCP, SUBAGENT, SCHEDULED, OTHER }

    /** Results longer than this are cut in the card (Copy still copies all of it). */
    const val RESULT_LIMIT = 20_000
    /** A round with at least this many finished calls folds them under one group header. */
    const val GROUP_THRESHOLD = 4

    fun kind(name: String): Kind = when {
        name == "shell" -> Kind.SHELL
        name.startsWith("file_") || name == "list_dir" -> Kind.FILE
        name.startsWith("memory_") -> Kind.MEMORY
        name.startsWith("mcp__") -> Kind.MCP
        name == com.openminis.desktop.subagents.SubAgentProtocol.TOOL_NAME -> Kind.SUBAGENT
        name.startsWith("scheduled_") -> Kind.SCHEDULED
        else -> Kind.OTHER
    }

    /** `mcp__github__create_issue` → `github · create_issue`; everything else unchanged. */
    fun displayName(name: String): String {
        if (!name.startsWith("mcp__")) return name
        val rest = name.removePrefix("mcp__")
        val cut = rest.indexOf("__")
        return if (cut <= 0 || cut + 2 >= rest.length) rest else rest.substring(0, cut) + " · " + rest.substring(cut + 2)
    }

    /** 850 → "850 ms", 1234 → "1.2 s", 65_000 → "1 m 5 s". */
    fun duration(ms: Long): String = when {
        ms < 1000 -> "$ms ms"
        ms < 60_000 -> String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
        else -> "${ms / 60_000} m ${(ms % 60_000) / 1000} s"
    }

    fun isFinished(tc: ToolCall) = tc.status == ToolStatus.DONE || tc.status == ToolStatus.FAILED || tc.status == ToolStatus.DENIED
    fun isFailure(tc: ToolCall) = tc.status == ToolStatus.FAILED || tc.status == ToolStatus.DENIED || (tc.status == ToolStatus.DONE && tc.isError)

    /** First non-blank line of a failed call's result, for the card header. */
    fun errorLine(tc: ToolCall): String? =
        if (!isFailure(tc)) null else tc.result?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(200)

    /** Fold only when every call has finished: a running batch stays visible card by card. */
    fun shouldGroup(calls: List<ToolCall>): Boolean = calls.size >= GROUP_THRESHOLD && calls.all(::isFinished)

    data class Clipped(val text: String, val truncated: Boolean, val total: Int)
    fun clip(result: String): Clipped =
        if (result.length <= RESULT_LIMIT) Clipped(result, false, result.length) else Clipped(result.take(RESULT_LIMIT), true, result.length)
}
