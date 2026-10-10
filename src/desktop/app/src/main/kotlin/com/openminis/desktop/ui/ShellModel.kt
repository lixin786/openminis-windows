// OpenMinis Desktop — pure logic behind the shell rework (UI-REFRESH §3): sidebar date groups and relative
// times, the toolbar model pill's provider badge, Markdown export. Unit-tested.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.core.ChatRole
import com.openminis.desktop.core.Conversation
import com.openminis.desktop.core.ProviderKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ShellModel {
    enum class DateGroup(val key: String) {
        TODAY("sidebar.group.today"), YESTERDAY("sidebar.group.yesterday"), WEEK("sidebar.group.week"), EARLIER("sidebar.group.earlier")
    }

    private fun day(ts: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    /** Open WebUI–style sections: today, yesterday, previous 7 days, earlier (future timestamps count as today). */
    fun group(ts: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): DateGroup {
        val days = java.time.temporal.ChronoUnit.DAYS.between(day(ts, zone), day(now, zone))
        return when {
            days <= 0 -> DateGroup.TODAY
            days == 1L -> DateGroup.YESTERDAY
            days < 7 -> DateGroup.WEEK
            else -> DateGroup.EARLIER
        }
    }

    /** i18n key + argument for "just now / 3 minutes ago / 2 hours ago / yesterday / 4 days ago", else a date. */
    data class Rel(val key: String, val arg: String? = null)

    fun relative(ts: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Rel {
        val diff = now - ts
        val days = java.time.temporal.ChronoUnit.DAYS.between(day(ts, zone), day(now, zone))
        return when {
            diff < 60_000 -> Rel("time.just_now")
            diff < 3_600_000 -> Rel("time.minutes_ago", (diff / 60_000).toString())
            days <= 0 -> Rel("time.hours_ago", (diff / 3_600_000).toString())
            days == 1L -> Rel("time.yesterday")
            days < 7 -> Rel("time.days_ago", days.toString())
            day(ts, zone).year == day(now, zone).year -> Rel("time.date", DateTimeFormatter.ofPattern("MM-dd").format(Instant.ofEpochMilli(ts).atZone(zone)))
            else -> Rel("time.date", DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochMilli(ts).atZone(zone)))
        }
    }

    data class SidebarSection(val headerKey: String, val conversations: List<Conversation>)

    /** Pinned first, then date sections; order inside each section is kept (newest activity first). Empty sections dropped. */
    fun sections(newestFirst: List<Conversation>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<SidebarSection> {
        val out = mutableListOf<SidebarSection>()
        val pinned = newestFirst.filter { it.pinned }
        if (pinned.isNotEmpty()) out += SidebarSection("sidebar.pinned", pinned)
        val rest = newestFirst.filterNot { it.pinned }.groupBy { group(it.updatedAt, now, zone) }
        DateGroup.entries.forEach { g -> rest[g]?.takeIf { it.isNotEmpty() }?.let { out += SidebarSection(g.key, it) } }
        return out
    }

    /**
     * Small badge beside the model name (Cherry Studio's "model | provider" pill). A custom OpenAI-compatible endpoint
     * shows its host ("api.siliconflow.cn", "localhost:11434") instead of "OpenAI-compatible (custom)".
     */
    fun providerBadge(kind: ProviderKind, baseUrl: String): String {
        if (kind != ProviderKind.OPENAI_COMPATIBLE) return kind.label
        val u = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull()
        val host = u?.host?.takeIf { it.isNotBlank() } ?: return "OpenAI-compatible"
        return if (u.port > 0) "$host:${u.port}" else host
    }

    /** The conversation as Markdown (Export): user text, assistant text, tool calls as one line each. */
    fun markdown(c: Conversation, title: String, userLabel: String, assistantLabel: String): String = buildString {
        appendLine("# $title"); appendLine()
        for (m in c.messages) {
            if (m.role == ChatRole.USER) {
                appendLine("## $userLabel"); appendLine()
                appendLine(m.text.trim())
                m.attachments.forEach { appendLine("- 📎 ${it.fileName}") }
            } else {
                appendLine("## $assistantLabel"); appendLine()
                for (r in m.rounds) {
                    if (r.text.isNotBlank()) { appendLine(r.text.trim()); appendLine() }
                    r.toolCalls.forEach { tc ->
                        val st = ToolPresentation.cardState(tc).name.lowercase()
                        appendLine("> 🔧 `${ToolPresentation.displayName(tc.name)}` · ${toolSummary(tc).take(200)} · $st")
                    }
                    if (r.toolCalls.isNotEmpty()) appendLine()
                }
                m.error?.let { appendLine("> ⚠️ $it") }
            }
            appendLine()
        }
    }.trimEnd() + "\n"
}
