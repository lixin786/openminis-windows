// OpenMinis Desktop — Scheduled tasks window (P1-4): list with next run, on/off, Run now, edit, delete,
// run records (open the chat a run wrote to); editor for upstream's triggers (time of day once / daily /
// weekdays / custom days with a date window, after N minutes, every N minutes [N times], when another
// task finishes) and targets (new chat, follow-up in an existing chat), model and reasoning override.
// Changes apply immediately (no Save step). Tasks fire while the app is running.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

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
internal fun describeTrigger(t: ScheduledTask, labelOf: (String) -> String): String = when (t.triggerKind) {
    ScheduledTriggerKind.AFTER -> "once, ${(t.delaySec ?: 0) / 60} min after it was saved"
    ScheduledTriggerKind.INTERVAL -> "every ${(t.intervalSec ?: 0) / 60} min" + (t.maxFires?.let { ", ${t.triggeredCount ?: 0}/$it done" } ?: "")
    ScheduledTriggerKind.ON_COMPLETION -> "when «${t.onCompletionOf?.let(labelOf) ?: "?"}» finishes"
    ScheduledTriggerKind.CALENDAR -> "%02d:%02d ".format(t.timeOfDayHour, t.timeOfDayMinute) + when (t.repeatMode) {
        ScheduledRepeatMode.ONCE -> "once"
        ScheduledRepeatMode.DAILY -> "every day"
        ScheduledRepeatMode.WEEKDAYS -> "Mon–Fri"
        ScheduledRepeatMode.CUSTOM -> DAYS.filter { it.first in t.customDays }.joinToString(" ") { it.second }
    }
}

private val DAYS = listOf(Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed", Calendar.THURSDAY to "Thu",
    Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat", Calendar.SUNDAY to "Sun")

@Composable
fun ScheduledTasksWindow(state: AppState, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    DialogWindow(onCloseRequest = onClose, title = "Scheduled tasks", state = rememberDialogState(size = DpSize(820.dp, 680.dp)),
        onPreviewKeyEvent = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { if (editing != null) editing = null else onClose(); true } else false }) {
        MinisTheme(state.settings.darkTheme) {
            Surface(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val tick = state.scheduledTick
                val tasks = state.scheduler.list()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    val e = editing
                    if (e != null) {
                        TaskEditor(state, e, tasks, onSave = { state.scheduler.save(it); editing = null }, onCancel = { editing = null })
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tasks run a prompt on a schedule while OpenMinis is running. Shell commands they run still ask for approval when confirmation is on.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Button(onClick = { editing = ScheduledTask(label = "", timeOfDayHour = 9, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.DAILY, prompt = "") }) { Text("New task") }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (tasks.isEmpty()) Text("No scheduled tasks yet.", style = MaterialTheme.typography.bodyMedium)
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
                        (if (running) "running now" else "next: " + fmtTime(state.scheduler.nextFire(t))) + " · runs: ${t.firesSoFar}" +
                        " · " + (if (t.targetMode is ScheduledTargetMode.NewSession) "new chat" else "follow-up in a chat"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { state.scheduler.runNow(t.id) }, enabled = !running) { Icon(Icons.Default.PlayArrow, "Run now") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit task") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete task") }
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide runs" else "Runs") }
            }
            if (confirmDelete) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Delete «${t.label}»? Its chats are kept.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                TextButton(onClick = { confirmDelete = false; state.scheduler.delete(t.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            if (open) {
                if (t.runHistory.isEmpty()) Text("No runs yet.", style = MaterialTheme.typography.bodySmall)
                t.runHistory.take(20).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (r.ok) "✓ " else "✗ ") + fmtTime(r.firedAt) + "  " + (r.preview ?: "").replace('\n', ' ').take(120),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            color = if (r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        val sid = r.sessionId
                        if (sid != null && state.conversations.any { it.id == sid }) TextButton(onClick = { state.select(sid); onClose() }) { Text("Open chat") }
                    }
                }
            }
        }
    }
}

private enum class TriggerChoice(val label: String) { TIME("At a time of day"), AFTER("Once, after a delay"), INTERVAL("Every N minutes"), AFTER_TASK("When another task finishes") }

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

    Text(if (all.any { it.id == initial.id }) "Edit task" else "New task", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(t.label, { t = t.copy(label = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
    OutlinedTextField(t.prompt, { t = t.copy(prompt = it) }, label = { Text("Prompt (what the agent should do each time)") }, minLines = 4, modifier = Modifier.fillMaxWidth())

    Row(verticalAlignment = Alignment.CenterVertically) { Text("When:", Modifier.width(120.dp)); Dropdown(trigger, TriggerChoice.entries, { it.label }, { trigger = it }) }
    when (trigger) {
        TriggerChoice.TIME -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(timeText, { timeText = it }, label = { Text("Time (HH:MM, 24h)") }, singleLine = true, modifier = Modifier.width(170.dp).tabMovesFocus())
                Spacer(Modifier.width(12.dp))
                Dropdown(t.repeatMode, ScheduledRepeatMode.entries, { when (it) { ScheduledRepeatMode.ONCE -> "Once"; ScheduledRepeatMode.DAILY -> "Every day"; ScheduledRepeatMode.WEEKDAYS -> "Weekdays (Mon–Fri)"; ScheduledRepeatMode.CUSTOM -> "Chosen days" } },
                    { t = t.copy(repeatMode = it) })
            }
            if (t.repeatMode == ScheduledRepeatMode.CUSTOM) Row {
                DAYS.forEach { (d, name) ->
                    FilterChip(selected = d in t.customDays, onClick = { t = t.copy(customDays = if (d in t.customDays) t.customDays - d else t.customDays + d) },
                        label = { Text(name) }, modifier = Modifier.padding(end = 4.dp))
                }
            }
            Row {
                OutlinedTextField(startText, { startText = it }, label = { Text("From date (YYYY-MM-DD, optional)") }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(endText, { endText = it }, label = { Text("Until date (optional)") }, singleLine = true, modifier = Modifier.weight(1f).tabMovesFocus())
            }
        }
        TriggerChoice.AFTER -> NumberField("Minutes from now", minutes, Modifier.fillMaxWidth()) { minutes = it }
        TriggerChoice.INTERVAL -> {
            NumberField("Every N minutes (at least 1)", minutes, Modifier.fillMaxWidth()) { minutes = it }
            NumberField("Number of runs (0 = until deleted)", count, Modifier.fillMaxWidth()) { count = it }
        }
        TriggerChoice.AFTER_TASK -> {
            val others = all.filter { it.id != t.id }
            if (others.isEmpty()) Text("Create another task first.", style = MaterialTheme.typography.bodySmall)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text("After:", Modifier.width(120.dp))
                Dropdown(others.firstOrNull { it.id == t.onCompletionOf }, others, { it?.label?.ifBlank { it.prompt.take(30) } ?: "choose a task" }, { t = t.copy(onCompletionOf = it?.id) })
            }
            Text("Use ${ScheduledTask.RESULT_PLACEHOLDER} in the prompt to insert that task's answer. Runs once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val chats = state.conversations.filter { it.messages.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Where:", Modifier.width(120.dp))
        val current = (t.targetMode as? ScheduledTargetMode.AppendToSession)?.sessionId
        Dropdown(current, listOf<String?>(null) + chats.map { it.id }, { id -> if (id == null) "A new chat each time" else "Follow-up in «" + (chats.firstOrNull { it.id == id }?.title ?: "deleted chat") + "»" },
            { t = t.copy(targetMode = if (it == null) ScheduledTargetMode.NewSession else ScheduledTargetMode.AppendToSession(it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Model:", Modifier.width(120.dp))
        val options = listOf<String?>(null) + state.settings.modelChain().map { "${it.provider.name}/${it.model}" }
        Dropdown(t.modelId, (options + t.modelId).distinct(), { it ?: "Active model" }, { t = t.copy(modelId = it) })
        Spacer(Modifier.width(12.dp))
        Dropdown(t.thinkingLevel, listOf<ThinkingLevel?>(null) + ThinkingLevel.entries, { it?.name?.let { n -> "Reasoning: $n" } ?: "Reasoning: as in Settings" }, { t = t.copy(thinkingLevel = it) })
    }
    Toggle("Enabled", t.enabled) { t = t.copy(enabled = it) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel (Esc)") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val r = ScheduledEditorRules.build(t, trigger.name, timeText, startText, endText, minutes, count)
            r.onSuccess(onSave).onFailure { error = it.message }
        }) { Text("Save") }
    }
}

/** Editor → task validation (pure, tested). [trigger] is TIME / AFTER / INTERVAL / AFTER_TASK. */
object ScheduledEditorRules {
    private val TIME_RE = Regex("""^\s*(\d{1,2})\s*[:：]\s*(\d{2})\s*$""")

    fun build(t: ScheduledTask, trigger: String, timeText: String, startText: String, endText: String, minutes: Int, count: Int): Result<ScheduledTask> = runCatching {
        require(t.prompt.isNotBlank()) { "The prompt is empty." }
        val label = t.label.ifBlank { t.prompt.lines().first().take(40) }
        fun date(s: String, what: String): Long? = s.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrElse { throw IllegalArgumentException("$what must look like 2026-10-31.") }
        }
        when (trigger) {
            "TIME" -> {
                val m = TIME_RE.matchEntire(timeText) ?: throw IllegalArgumentException("Time must be HH:MM, e.g. 09:00.")
                val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                require(h in 0..23 && mi in 0..59) { "Time must be between 00:00 and 23:59." }
                require(t.repeatMode != ScheduledRepeatMode.CUSTOM || t.customDays.isNotEmpty()) { "Choose at least one day." }
                val start = date(startText, "From date"); val end = date(endText, "Until date")
                require(start == null || end == null || end >= start) { "Until date is before From date." }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.CALENDAR, timeOfDayHour = h, timeOfDayMinute = mi, startDateMs = start, endDateMs = end,
                    delaySec = null, intervalSec = null, maxFires = null, onCompletionOf = null)
            }
            "AFTER" -> { require(minutes >= 1) { "At least 1 minute." }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.AFTER, delaySec = minutes * 60L, intervalSec = null, maxFires = null, onCompletionOf = null) }
            "INTERVAL" -> { require(minutes >= 1) { "At least every 1 minute." }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = minutes * 60L, maxFires = count.takeIf { it > 0 }, delaySec = null, onCompletionOf = null) }
            "AFTER_TASK" -> { require(!t.onCompletionOf.isNullOrBlank()) { "Choose the task to wait for." }
                t.copy(label = label, triggerKind = ScheduledTriggerKind.ON_COMPLETION, delaySec = null, intervalSec = null, maxFires = null) }
            else -> throw IllegalArgumentException("Unknown trigger $trigger")
        }
    }
}
