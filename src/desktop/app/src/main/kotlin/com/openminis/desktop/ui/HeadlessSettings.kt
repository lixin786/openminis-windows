// OpenMinis Desktop — HEADLESS-SCHEDULER.md §6: Settings › Scheduled tasks › run while the app is closed.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.i18n.tr
import com.openminis.desktop.scheduled.SystemTask
import com.openminis.desktop.ui.icons.Lucide

@Composable
fun HeadlessSchedulerSection(state: AppState, draft: AppSettings, onDraft: (AppSettings) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { state.refreshHeadlessStatus() }
    val enabled = state.settings.headlessScheduler
    val interval = state.settings.headlessIntervalMinutes.takeIf { it in SystemTask.INTERVALS } ?: 5
    fun apply(on: Boolean, iv: Int) {
        busy = true; error = null
        state.applyHeadless(on, iv) { err ->
            busy = false; error = err
            if (err == null) onDraft(draft.copy(headlessScheduler = on, headlessIntervalMinutes = iv))
        }
    }

    Section(tr("headless.section"))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(tr("headless.toggle"), Modifier.weight(1f))
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Switch(checked = enabled, enabled = !busy, onCheckedChange = { apply(it, interval) })
    }
    Hint(tr("headless.difference"))
    Field(tr("headless.interval")) {
        Dropdown(interval, SystemTask.INTERVALS, { tr("headless.interval_n", it) }, { iv ->
            when {
                iv == interval -> Unit
                enabled -> apply(true, iv)   // re-register with the new interval
                else -> { state.updateSettings(state.settings.copy(headlessIntervalMinutes = iv)); onDraft(draft.copy(headlessIntervalMinutes = iv)) }
            }
        })
    }
    val st = state.headlessStatus
    val last = remember(state.scheduledTick, st) { runCatching { state.headlessState.read().lastRun }.getOrNull() }
    Text(when {
        st == null -> tr("headless.status_checking")
        st.error != null -> st.error
        st.registered -> tr("headless.status_registered", st.nextRun ?: "—")
        else -> tr("headless.status_not_registered")
    }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Text(last?.let {
        val result = when {
            it.note.isNotBlank() -> it.note
            it.ran == 0 -> tr("headless.last_none_due")
            else -> tr("headless.last_result", it.ran, it.ok, it.failed, it.needsApproval) + if (it.timedOut) " · " + tr("sched.run.timeout") else ""
        }
        tr("headless.last_run", fmtTime(it.at), result)
    } ?: tr("headless.last_never"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
    if (st?.registered == true || enabled) OutlinedButton(enabled = !busy, onClick = { apply(false, interval) }) {
        Icon(Lucide.Trash, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("headless.remove"))
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Info, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("headless.silent_hint"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
