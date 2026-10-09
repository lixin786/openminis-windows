// OpenMinis Desktop — Scheduled tasks window (P1-4): list with next run, on/off, Run now, edit, delete,
// run records (open the chat a run wrote to); editor for upstream's triggers (time of day once / daily /
// weekdays / custom days with a date window, after N minutes, every N minutes [N times], when another
// task finishes) and targets (new chat, follow-up in an existing chat), model and reasoning override.
// Changes apply immediately (no Save step). Tasks fire while the app is running.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.i18n.I18n
import com.openminis.desktop.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

private val WHEN_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal fun fmtTime(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(WHEN_FMT) } ?: "—"

/** Short human description of a task's trigger (list row). */
/** [lang] "en" for what the model reads (scheduled_task list), the UI language otherwise. */
internal fun describeTrigger(t: ScheduledTask, lang: String = I18n.active, labelOf: (String) -> String): String = when (t.triggerKind) {
    ScheduledTriggerKind.AFTER -> I18n.tr("sched.trigger.after", (t.delaySec ?: 0) / 60, lang = lang)
    ScheduledTriggerKind.INTERVAL -> I18n.tr("sched.trigger.every", (t.intervalSec ?: 0) / 60, lang = lang) +
        (t.maxFires?.let { I18n.tr("sched.trigger.every_done", t.triggeredCount ?: 0, it, lang = lang) } ?: "")
    ScheduledTriggerKind.ON_COMPLETION -> I18n.tr("sched.trigger.after_task", t.onCompletionOf?.let(labelOf) ?: "?", lang = lang)
    ScheduledTriggerKind.CALENDAR -> "%02d:%02d ".format(t.timeOfDayHour, t.timeOfDayMinute) + when (t.repeatMode) {
        ScheduledRepeatMode.ONCE -> I18n.tr("sched.repeat.once_short", lang = lang)
        ScheduledRepeatMode.DAILY -> I18n.tr("sched.repeat.daily_short", lang = lang)
        ScheduledRepeatMode.WEEKDAYS -> I18n.tr("sched.repeat.weekdays_short", lang = lang)
        ScheduledRepeatMode.CUSTOM -> DAYS.filter { it.first in t.customDays }.joinToString(" ") { I18n.tr("day." + it.second, lang = lang) }
    }
}

/** Calendar day → i18n key suffix (day.MONDAY …). */
private val DAYS = listOf(Calendar.MONDAY to "MONDAY", Calendar.TUESDAY to "TUESDAY", Calendar.WEDNESDAY to "WEDNESDAY", Calendar.THURSDAY to "THURSDAY",
    Calendar.FRIDAY to "FRIDAY", Calendar.SATURDAY to "SATURDAY", Calendar.SUNDAY to "SUNDAY")

@Composable
fun ScheduledTasksWindow(state: AppState, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    DialogWindow(onCloseRequest = onClose, title = tr("sched.window"), state = rememberFittedDialogState(820.dp, 680.dp), resizable = true,
        onPreviewKeyEvent = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { if (editing != null) editing = null else onClose(); true } else false }) {
        MinisTheme(state.settings) {
            Surface(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val tick = state.scheduledTick
                val tasks = state.scheduler.list()
                ScrollColumn(Modifier.fillMaxSize()) {
                    RunsOnlyWhileOpenNote()
                    Spacer(Modifier.height(8.dp))
                    val e = editing
                    if (e != null) {
                        TaskEditor(state, e, tasks, onSave = { state.scheduler.save(it); editing = null }, onCancel = { editing = null })
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("sched.intro"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Button(onClick = { editing = ScheduledTask(label = "", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.DAILY, prompt = "") }) { Text(tr("sched.new")) }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (tasks.isEmpty()) Text(tr("sched.empty"), style = MaterialTheme.typography.bodyMedium)
                        tasks.sortedBy { state.scheduler.nextFire(it) ?: Long.MAX_VALUE }.forEach { t ->
                            TaskRow(state, t, tasks, onEdit = { editing = t }, onClose = onClose)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(state: AppState, t: ScheduledTask, all: List<ScheduledTask>, onEdit: () -> Unit, onClose: () -> Unit) {
    var open by remember(t.id) { mutableStateOf(false) }
    var confirmDelete by remember(t.id) { mutableStateOf(false) }
    val running = state.scheduler.isRunning(t.id)
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(t.enabled, { state.scheduler.setEnabled(t.id, it) })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.label.ifBlank { t.prompt.take(40) }, style = MaterialTheme.typography.titleSmall)
                    Text(describeTrigger(t) { id -> all.firstOrNull { it.id == id }?.label ?: id } + " · " +
                        (if (running) tr("sched.running_now") else tr("sched.next", fmtTime(state.scheduler.nextFire(t)))) + tr("sched.runs_count", t.firesSoFar) +
                        " · " + (if (t.targetMode is ScheduledTargetMode.NewSession) tr("sched.target.new") else tr("sched.target.follow")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { state.scheduler.runNow(t.id) }, enabled = !running) { Icon(Icons.Default.PlayArrow, tr("sched.run_now")) }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, tr("sched.edit")) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, tr("sched.delete_one")) }
                TextButton(onClick = { open = !open }) { Text(if (open) tr("sched.hide_runs") else tr("sched.runs")) }
            }
            if (confirmDelete) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("sched.delete_q", t.label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = false }) { Text(tr("common.keep")) }
                TextButton(onClick = { confirmDelete = false; state.scheduler.delete(t.id) }) { Text(tr("common.delete"), color = MaterialTheme.colorScheme.error) }
            }
            if (open) {
                if (t.runHistory.isEmpty()) Text(tr("sched.no_runs"), style = MaterialTheme.typography.bodySmall)
                t.runHistory.take(20).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (r.ok) "✓ " else "✗ ") + fmtTime(r.firedAt) + "  " + (r.preview ?: "").replace('\n', ' ').take(120),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            color = if (r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        val sid = r.sessionId
                        if (sid != null && state.conversations.any { it.id == sid }) TextButton(onClick = { state.select(sid); onClose() }) { Text(tr("sched.open_chat")) }
                    }
                }
            }
        }
    }
}

private enum class TriggerChoice { TIME, AFTER, INTERVAL, AFTER_TASK;
    val label: String get() = tr("sched.choice.$name") }

@Composable
private fun TaskEditor(state: AppState, initial: ScheduledTask, all: List<ScheduledTask>, onSave: (ScheduledTask) -> Unit, onCancel: () -> Unit) {
    var t by remember(initial.id) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var timeText by remember(initial.id) { mutableStateOf("%02d:%02d".format(initial.timeOfDayHour, initial.timeOfDayMinute)) }
    var startText by remember(initial.id) { mutableStateOf(initial.startDateMs?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).toString() } ?: "") }
    var endText by remember(initial.id) { mutableStateOf(initial.endDateMs?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).toString() } ?: "") }
    var trigger by remember(initial.id) { mutableStateOf(when (initial.triggerKind) {
        ScheduledTriggerKind.CALENDAR -> TriggerChoice.TIME; ScheduledTriggerKind.AFTER -> TriggerChoice.AFTER
        ScheduledTriggerKind.INTERVAL -> TriggerChoice.INTERVAL; ScheduledTriggerKind.ON_COMPLETION -> TriggerChoice.AFTER_TASK }) }
    var minutes by remember(initial.id) { mutableStateOf(((initial.delaySec ?: initial.intervalSec ?: 3600L) / 60).toInt()) }
    var count by remember(initial.id) { mutableStateOf(initial.maxFires ?: 0) }

    Text(if (all.any { it.id == initial.id }) tr("sched.edit") else tr("sched.new"), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(t.label, { t = t.copy(label = it) }, label = { Text(tr("common.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
    OutlinedTextField(t.prompt, { t = t.copy(prompt = it) }, label = { Text(tr("sched.prompt")) }, minLines = 4, modifier = Modifier.fillMaxWidth())

    Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("sched.when"), Modifier.width(120.dp)); Dropdown(trigger, TriggerChoice.entries, { it.label }, { trigger = it }) }
    when (trigger) {
        TriggerChoice.TIME -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(timeText, { timeText = it }, label = { Text(tr("sched.time")) }, singleLine = true, modifier = Modifier.width(170.dp).tabMovesFocus())
                Spacer(Modifier.width(12.dp))
                Dropdown(t.repeatMode, ScheduledRepeatMode.entries, { tr("sched.repeat.${it.name}") },
                    { t = t.copy(repeatMode = it) })
            }
            if (t.repeatMode == ScheduledRepeatMode.CUSTOM) Row {
                DAYS.forEach { (d, name) ->
                    FilterChip(selected = d in t.customDays, onClick = { t = t.copy(customDays = if (d in t.customDays) t.customDays - d else t.customDays + d) },
                        label = { Text(tr("day.$name")) }, modifier = Modifier.padding(end = 4.dp))
                }
            }
            Row {
                OutlinedTextField(startText, { startText = it }, label = { Text(tr("sched.from")) }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(endText, { endText = it }, label = { Text(tr("sched.until")) }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
            }
        }
        TriggerChoice.AFTER -> NumberField(tr("sched.minutes_from_now"), minutes, Modifier.fillMaxWidth()) { minutes = it }
        TriggerChoice.INTERVAL -> {
            NumberField(tr("sched.every_n"), minutes, Modifier.fillMaxWidth()) { minutes = it }
            NumberField(tr("sched.count"), count, Modifier.fillMaxWidth()) { count = it }
        }
        TriggerChoice.AFTER_TASK -> {
            val others = all.filter { it.id != t.id }
            if (others.isEmpty()) Text(tr("sched.create_first"), style = MaterialTheme.typography.bodySmall)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("sched.after"), Modifier.width(120.dp))
                Dropdown(others.firstOrNull { it.id == t.onCompletionOf }, others, { it?.label?.ifBlank { it.prompt.take(30) } ?: tr("sched.choose_task") }, { t = t.copy(onCompletionOf = it?.id) })
            }
            Text(tr("sched.placeholder_hint", ScheduledTask.RESULT_PLACEHOLDER), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val chats = state.conversations.filter { it.messages.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("sched.where"), Modifier.width(120.dp))
        val current = (t.targetMode as? ScheduledTargetMode.AppendToSession)?.sessionId
        Dropdown(current, listOf<String?>(null) + chats.map { it.id }, { id -> if (id == null) tr("sched.where.new") else tr("sched.where.follow", chats.firstOrNull { it.id == id }?.displayTitle() ?: tr("sched.deleted_chat")) },
            { t = t.copy(targetMode = if (it == null) ScheduledTargetMode.NewSession else ScheduledTargetMode.AppendToSession(it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("common.model"), Modifier.width(120.dp))
        val options = listOf<String?>(null) + state.settings.modelChain().map { "${it.provider.name}/${it.model}" }
        Dropdown(t.modelId, (options + t.modelId).distinct(), { it ?: tr("sched.active_model") }, { t = t.copy(modelId = it) })
        Spacer(Modifier.width(12.dp))
        Dropdown(t.thinkingLevel, listOf<ThinkingLevel?>(null) + ThinkingLevel.entries, { it?.name?.let { n -> tr("sched.reasoning", I18n.trOr("thinking.$n", n)) } ?: tr("sched.reasoning_default") }, { t = t.copy(thinkingLevel = it) })
    }
    Toggle(tr("common.enabled"), t.enabled) { t = t.copy(enabled = it) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text(tr("common.cancel_esc")) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val r = ScheduledEditorRules.build(t, trigger.name, timeText, startText, endText, minutes, count)
            r.onSuccess(onSave).onFailure { error = it.message }
        }) { Text(tr("common.save")) }
    }
}

/** Editor → task validation (pure, tested). [trigger] is TIME / AFTER / INTERVAL / AFTER_TASK. */
object ScheduledEditorRules {
    private val TIME_RE = Regex("""^\s*(\d{1,2})\s*[:：]\s*(\d{2})\s*$""")

    fun build(t: ScheduledTask, trigger: String, timeText: String, startText: String, endText: String, minutes: Int, count: Int): Result<ScheduledTask> = runCatching {
        require(t.prompt.isNotBlank()) { tr("sched.err.prompt_empty") }
        val label = t.label.ifBlank { t.prompt.lines().first().take(40) }
        fun date(s: String, what: String): Long? = s.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrElse { throw IllegalArgumentException(tr("sched.err.date", what)) }
        }
        when (trigger) {
            "TIME" -> {
                val m = TIME_RE.matchEntire(timeText) ?: throw IllegalArgumentException(tr("sched.err.time_format"))
                val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                require(h in 0..23 && mi in 0..59) { tr("sched.err.time_range") }
                require(t.repeatMode != ScheduledRepeatMode.CUSTOM || t.customDays.isNotEmpty()) { tr("sched.err.day") }
                val start = date(startText, tr("sched.err.from")); val end = date(endText, tr("sched.err.until"))
                require(start == null || end == null || end >= start) { tr("sched.err.order") }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.CALENDAR, timeOfDayHour = h, timeOfDayMinute = mi, startDateMs = start, endDateMs = end,
                    delaySec = null, intervalSec = null, maxFires = null, onCompletionOf = null)
            }
            "AFTER" -> { require(minutes >= 1) { tr("sched.err.min1") }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.AFTER, delaySec = minutes * 60L, intervalSec = null, maxFires = null, onCompletionOf = null) }
            "INTERVAL" -> { require(minutes >= 1) { tr("sched.err.every1") }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = minutes * 60L, maxFires = count.takeIf { it > 0 }, delaySec = null, onCompletionOf = null) }
            "AFTER_TASK" -> { require(!t.onCompletionOf.isNullOrBlank()) { tr("sched.err.choose") }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.ON_COMPLETION, delaySec = null, intervalSec = null, maxFires = null) }
            else -> throw IllegalArgumentException("Unknown trigger $trigger")
        }
    }
}

/** [batch3 follow-up] Shown at the top of the task list and of the create / edit form (English text; the UI shows tr("sched.runs_only_open")). */
const val RUNS_ONLY_WHILE_OPEN_NOTE =
    "Tasks run only while OpenMinis is open. If the app is closed at the scheduled time, that run is skipped " +
        "(delayed / interval tasks catch up once on next launch)."

@Composable
private fun RunsOnlyWhileOpenNote() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text("ⓘ " + tr("sched.runs_only_open"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(10.dp))
    }
}
