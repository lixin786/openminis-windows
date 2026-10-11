// OpenMinis Desktop — HEADLESS-SCHEDULER.md: `--run-due-tasks`, started by the Windows task while the app is closed.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.scheduled

import com.openminis.app.logging.AppLogger
import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.ui.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Take the lock → run what is due, one task at a time, within [TOTAL_TIMEOUT_MS] → record → exit.
 * No window, no dialog (java.awt.headless is set by main before anything else); the app's own timer
 * keeps running tasks while the app is open, and holds the same lock, so nothing fires twice.
 */
object HeadlessRunner {
    private const val TAG = "Headless"
    const val TOTAL_TIMEOUT_MS = 10 * 60 * 1000L

    fun run(paths: AppPaths, clock: () -> Long = System::currentTimeMillis): Int {
        val lock = SchedulerLock(File(paths.root, SchedulerLock.FILE_NAME), "headless")
        when (val a = lock.tryAcquire()) {
            is SchedulerLock.Result.Busy -> { AppLogger.info(TAG, "skipped: ${a.holder} holds the lock (the app's timer or another pass runs the tasks)"); return 0 }
            is SchedulerLock.Result.Acquired -> Unit
        }
        val state = HeadlessState(File(paths.root, HeadlessState.FILE_NAME))
        val started = clock()
        return try {
            runBlocking { pass(paths, state, started, clock) }
            0
        } catch (e: Throwable) {
            AppLogger.error(TAG, "headless pass failed", e)
            runCatching { state.update { it.copy(lastRun = HeadlessState.LastRun(started, 0, 0, 0, 0, false, "error: ${e.message ?: e.javaClass.simpleName}")) } }
            1
        } finally { lock.release() }
    }

    private suspend fun pass(paths: AppPaths, st: HeadlessState, started: Long, clock: () -> Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = withContext(Dispatchers.Main) { AppState(paths, scope, headless = true) }
        // I18n.active is process-global: put it back afterwards (the real process exits anyway; tests share the JVM).
        val prevLang = com.openminis.desktop.i18n.I18n.active
        try {
            if (!app.settings.headlessScheduler) {
                AppLogger.info(TAG, "background runs are switched off in Settings — nothing to do (remove the Windows task there)")
                return
            }
            com.openminis.desktop.i18n.I18n.apply(app.settings.language)
            val checkedUpTo = st.read().checkedUpTo ?: (started - DesktopScheduler.CALENDAR_GRACE_MS)
            val s = app.scheduler.runDue(checkedUpTo, started + TOTAL_TIMEOUT_MS) { t, p -> app.runScheduledHeadless(t, p) }
            AppLogger.info(TAG, "pass done: ran=${s.ran} ok=${s.ok} failed=${s.failed} needsApproval=${s.needsApproval.size} timedOut=${s.timedOut}")
            st.update { d ->
                d.copy(checkedUpTo = started,
                    lastRun = HeadlessState.LastRun(started, s.ran, s.ok, s.failed, s.needsApproval.size, s.timedOut),
                    notices = d.notices + s.needsApproval.map { (t, step) -> HeadlessState.Notice(t.id, t.label, step, clock()) })
            }
        } finally {
            runCatching { app.shutdown() }
            scope.cancel()
            com.openminis.desktop.i18n.I18n.apply(prevLang)
        }
    }
}
