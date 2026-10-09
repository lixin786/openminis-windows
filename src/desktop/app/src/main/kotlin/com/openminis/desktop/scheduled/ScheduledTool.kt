// OpenMinis Desktop — `scheduled_task` tool (P1-4): the agent-facing surface of upstream's
// `minis-scheduled` CLI (sandbox/offload/ScheduledTaskOffloadHandler), as a native tool because the
// desktop has no Linux guest. Same subcommands (list / create / delete / enable / disable / run) and
// option names. Desktop difference: creating a task asks the user first (it will run on its own later).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.scheduled

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.desktop.agent.Toolbox
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

object ScheduledToolSpec {
    const val NAME = "scheduled_task"

    val definition = AgentToolDefinition(
        name = NAME,
        description = "Create and manage scheduled tasks: a prompt the app runs on its own later — at a time of day (once / daily / weekdays / chosen days), after a delay, every N minutes, or when another task finishes — in a new chat or as a follow-up in this chat. Tasks fire while the OpenMinis app is running. " +
            "action=list shows tasks with ids; create needs `prompt` plus one trigger (`time`, `after`, `interval`, or `of`); delete / enable / disable / run need `id`. Write `prompt` as a complete instruction (the future run does not see this conversation unless target=follow-up). The user is asked to confirm every new task.",
        parameters = mapOf(
            "action" to AgentToolParam("string", "list (default) | create | delete | enable | disable | run", enumValues = listOf("list", "create", "delete", "enable", "disable", "run")),
            "prompt" to AgentToolParam("string", "create: what the agent should do when the task fires. With `of`, {{result}} is replaced by that task's answer."),
            "label" to AgentToolParam("string", "create: short name shown in the list."),
            "time" to AgentToolParam("string", "create: time of day HH:MM (24h, local time)."),
            "repeat" to AgentToolParam("string", "create with time: once (default) | daily | weekdays | custom", enumValues = listOf("once", "daily", "weekdays", "custom")),
            "days" to AgentToolParam("string", "create with repeat=custom: comma-separated mon,tue,wed,thu,fri,sat,sun."),
            "start" to AgentToolParam("string", "create with time: first day YYYY-MM-DD (optional)."),
            "end" to AgentToolParam("string", "create with time: last day YYYY-MM-DD (optional)."),
            "after" to AgentToolParam("string", "create: run once after this delay, e.g. 30m, 2h, 1d."),
            "interval" to AgentToolParam("string", "create: run every N, e.g. 10m (at least 1m)."),
            "count" to AgentToolParam("integer", "create with interval: number of runs (omit = until deleted)."),
            "of" to AgentToolParam("string", "create: run once when the task with this id or label finishes."),
            "target" to AgentToolParam("string", "create: new (default, a new chat each time) | follow-up (append to `session`, default this chat)", enumValues = listOf("new", "follow-up")),
            "session" to AgentToolParam("string", "create with target=follow-up: chat id, or \"current\" (default)."),
            "model" to AgentToolParam("string", "create: PROVIDER/model to run on (default: the active model)."),
            "thinking" to AgentToolParam("string", "create: reasoning effort off|low|medium|high|xhigh|max (default: as in Settings)."),
            "disabled" to AgentToolParam("boolean", "create: store it switched off."),
            "id" to AgentToolParam("string", "delete / enable / disable / run: the task id (a unique prefix or the exact label also works)."),
        ),
        required = emptyList(),
    )

    /** "30m" / "2h" / "90s" / "1d" / "45" (minutes) → seconds. */
    fun parseDuration(raw: String): Long {
        val m = Regex("""^\s*(\d+)\s*([smhd]?)\s*$""", RegexOption.IGNORE_CASE).matchEntire(raw)
            ?: throw IllegalArgumentException("duration '$raw' — use e.g. 30m, 2h, 1d")
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].lowercase()) { "s" -> n; "h" -> n * 3600; "d" -> n * 86400; else -> n * 60 }
    }

    private val DAY = mapOf("mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY)

    private fun date(s: String) = LocalDate.parse(s.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Arguments → a new task (upstream option semantics; `time` alone = once). */
    fun buildTask(a: JSONObject, currentConversation: String?, resolveId: (String) -> String?): ScheduledTask {
        val prompt = a.optString("prompt").trim()
        require(prompt.isNotEmpty()) { "create needs a prompt" }
        val label = a.optString("label").trim().ifEmpty { prompt.lines().first().take(40) }
        val target = when (a.optString("target", "new").trim().lowercase()) {
            "", "new" -> ScheduledTargetMode.NewSession
            "follow-up", "followup", "append" -> {
                val s = a.optString("session").trim().let { if (it.isEmpty() || it == "current") currentConversation else it }
                    ?: throw IllegalArgumentException("target=follow-up needs a session (no current chat)")
                ScheduledTargetMode.AppendToSession(s)
            }
            else -> throw IllegalArgumentException("target must be new or follow-up on desktop")
        }
        val thinking = a.optString("thinking").trim().takeIf { it.isNotEmpty() }?.let { ThinkingLevel.parseOrNull(it) ?: throw IllegalArgumentException("unknown thinking level '$it'") }
        val base = ScheduledTask(label = label, timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = prompt,
            targetMode = target, modelId = a.optString("model").trim().takeIf { it.isNotEmpty() }, thinkingLevel = thinking,
            enabled = !a.optBoolean("disabled", false))
        val triggers = listOf("time", "after", "interval", "of").filter { a.optString(it).isNotBlank() }
        require(triggers.size == 1) { "create needs exactly one of time / after / interval / of (got ${triggers.ifEmpty { listOf("none") }.joinToString()})" }
        return when (triggers.single()) {
            "after" -> base.copy(triggerKind = ScheduledTriggerKind.AFTER, delaySec = parseDuration(a.optString("after")))
            "interval" -> {
                val iv = parseDuration(a.optString("interval")); require(iv >= 60) { "interval must be at least 1m" }
                base.copy(triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = iv, maxFires = a.optInt("count", 0).takeIf { it > 0 })
            }
            "of" -> base.copy(triggerKind = ScheduledTriggerKind.ON_COMPLETION,
                onCompletionOf = resolveId(a.optString("of").trim()) ?: throw IllegalArgumentException("of: no task with id or label '${a.optString("of")}'"))
            else -> {
                val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(a.optString("time").trim()) ?: throw IllegalArgumentException("time must be HH:MM")
                val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                require(h in 0..23 && mi in 0..59) { "time out of range" }
                val repeat = when (a.optString("repeat", "once").trim().lowercase()) {
                    "", "once" -> ScheduledRepeatMode.ONCE; "daily" -> ScheduledRepeatMode.DAILY
                    "weekdays" -> ScheduledRepeatMode.WEEKDAYS; "custom" -> ScheduledRepeatMode.CUSTOM
                    else -> throw IllegalArgumentException("repeat must be once / daily / weekdays / custom")
                }
                val days = a.optString("days").split(',', ' ').map { it.trim().lowercase().take(3) }.filter { it.isNotEmpty() }
                    .map { DAY[it] ?: throw IllegalArgumentException("unknown day '$it'") }.toSet()
                require(repeat != ScheduledRepeatMode.CUSTOM || days.isNotEmpty()) { "repeat=custom needs days" }
                base.copy(timeOfDayHour = h, timeOfDayMinute = mi, repeatMode = repeat, customDays = days,
                    startDateMs = a.optString("start").takeIf { it.isNotBlank() }?.let(::date), endDateMs = a.optString("end").takeIf { it.isNotBlank() }?.let(::date))
            }
        }
    }
}

/** Adds `scheduled_task` to the main loop's toolbox. */
class ScheduledToolbox(
    private val inner: Toolbox,
    private val scheduler: DesktopScheduler,
    private val approver: ToolApprover,
    private val currentConversation: () -> String?,
    private val describe: (ScheduledTask) -> String = { it.label },
) : Toolbox {
    override fun definitions(): List<AgentToolDefinition> = inner.definitions() + ScheduledToolSpec.definition
    override fun runsConcurrently(name: String) = inner.runsConcurrently(name)

    private fun resolve(raw: String): ScheduledTask? {
        val all = scheduler.list()
        return all.firstOrNull { it.id == raw } ?: all.filter { it.id.startsWith(raw) }.singleOrNull() ?: all.firstOrNull { it.label.equals(raw, true) }
    }

    override suspend fun execute(name: String, args: JSONObject): ToolOutcome {
        if (name != ScheduledToolSpec.NAME) return inner.execute(name, args)
        return try {
            when (val action = args.optString("action", "list").trim().lowercase().ifEmpty { "list" }) {
                "list" -> {
                    val arr = JSONArray()
                    scheduler.list().forEach { t ->
                        arr.put(JSONObject().put("id", t.id).put("label", t.label).put("enabled", t.enabled).put("trigger", describe(t))
                            .put("target", t.targetMode.encode()).put("next_run", scheduler.nextFire(t)?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
                            .put("runs", t.firesSoFar).put("last_result", t.lastResultPreview ?: JSONObject.NULL))
                    }
                    ToolOutcome(JSONObject().put("tasks", arr).toString(2))
                }
                "create" -> {
                    val t = ScheduledToolSpec.buildTask(args, currentConversation()) { raw -> resolve(raw)?.id }
                    val ok = approver.approve(ApprovalRequest("scheduled", "Create scheduled task «${t.label}» (${describe(t)})",
                        "Runs on its own: ${describe(t)}\nWhere: ${if (t.targetMode is ScheduledTargetMode.NewSession) "a new chat each time" else "follow-up in this chat"}\n\nPrompt:\n${t.prompt}"))
                    if (!ok) return ToolOutcome("The user declined to create this scheduled task.", isError = true, denied = true)
                    val saved = scheduler.save(t)
                    ToolOutcome(JSONObject().put("ok", true).put("id", saved.id).put("label", saved.label).put("trigger", describe(saved))
                        .put("next_run", scheduler.nextFire(saved)?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL).toString())
                }
                "delete", "enable", "disable", "run" -> {
                    val t = resolve(args.optString("id").trim()) ?: return ToolOutcome("Error: no task with id or label '${args.optString("id")}' (see action=list)", isError = true)
                    when (action) {
                        "delete" -> scheduler.delete(t.id)
                        "enable" -> scheduler.setEnabled(t.id, true)
                        "disable" -> scheduler.setEnabled(t.id, false)
                        else -> if (!scheduler.runNow(t.id)) return ToolOutcome("Error: «${t.label}» is already running", isError = true)
                    }
                    ToolOutcome(JSONObject().put("ok", true).put("id", t.id).put("action", action).toString())
                }
                else -> ToolOutcome("Error: unknown action '$action' (list / create / delete / enable / disable / run)", isError = true)
            }
        } catch (e: IllegalArgumentException) {
            ToolOutcome("Error: ${e.message}", isError = true)
        } catch (e: java.time.format.DateTimeParseException) {
            ToolOutcome("Error: dates must be YYYY-MM-DD (${e.parsedString})", isError = true)
        }
    }
}
