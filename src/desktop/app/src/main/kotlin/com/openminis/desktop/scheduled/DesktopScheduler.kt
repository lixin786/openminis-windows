// OpenMinis Desktop — scheduled tasks (P1-4). The task model is upstream's (com.openminis.app.scheduled
// .ScheduledTask, copied verbatim: calendar once / daily / weekdays / custom days with an optional date
// window, --after, --interval [--count], on-completion with {{result}}, run records). Android arms one
// AlarmManager alarm per task; the desktop keeps an in-process timer instead, so tasks fire while the app
// is running (also minimised to the tray). Missed slots follow upstream's catch-up: an overdue
// after / interval fires once on start, a calendar slot that passed while the app was closed is skipped.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.scheduled

import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledRun
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.desktop.core.atomicWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** `scheduled_tasks.json`: a JSON array of upstream ScheduledTask.toJson() rows. Thread-safe, in-memory cache. */
class ScheduledStore(private val file: File) {
    private val lock = Any()
    private var cache: List<ScheduledTask> = load()

    private fun load(): List<ScheduledTask> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o -> runCatching { ScheduledTask.fromJson(o) }.getOrNull() } }
        }
    } catch (e: Exception) {
        AppLogger.error("Scheduled", "scheduled_tasks.json unreadable", e); emptyList()
    }

    fun all(): List<ScheduledTask> = synchronized(lock) { cache }
    fun get(id: String): ScheduledTask? = all().firstOrNull { it.id == id }

    private fun write(list: List<ScheduledTask>) {
        cache = list
        atomicWrite(file, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString(2))
    }

    fun upsert(t: ScheduledTask) = synchronized(lock) {
        write(if (cache.any { it.id == t.id }) cache.map { if (it.id == t.id) t else it } else cache + t)
    }

    /** Atomic read-modify-write of one row; returns the new row (null if it is gone). */
    fun update(id: String, f: (ScheduledTask) -> ScheduledTask): ScheduledTask? = synchronized(lock) {
        val cur = cache.firstOrNull { it.id == id } ?: return null
        val n = f(cur); write(cache.map { if (it.id == id) n else it }); n
    }

    fun delete(id: String) = synchronized(lock) { write(cache.filter { it.id != id }) }
}

/** What one fire produced (the run record). */
data class ScheduledOutcome(val sessionId: String?, val preview: String?, val ok: Boolean, val result: String = preview.orEmpty())

class DesktopScheduler(
    val store: ScheduledStore,
    private val scope: CoroutineScope,
    /** Runs the task's prompt (already with {{result}} filled in) and returns the run record. */
    private val runner: suspend (task: ScheduledTask, prompt: String) -> ScheduledOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
) {
    /** taskId → the slot the timer is waiting for (computed once, like an armed alarm). */
    private val armed = ConcurrentHashMap<String, Long>()
    private val running = ConcurrentHashMap<String, Job>()
    @Volatile var onChange: () -> Unit = {}
    private var loop: Job? = null

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { if (it is CancellationException) throw it; AppLogger.error(TAG, "tick failed", it) }
                delay(tickMs)
            }
        }
    }

    fun stop() { loop?.cancel(); loop = null }

    fun isRunning(id: String): Boolean = running[id]?.isActive == true

    /** Stop a run in progress (recorded as "Stopped."). */
    fun cancelRun(id: String) { running[id]?.cancel() }

    /** When the timer will fire this task next (null = not scheduled). */
    fun nextFire(t: ScheduledTask): Long? = if (!t.enabled) null else armed[t.id] ?: t.nextTriggerMs(clock())

    // ---------------------------------------------------------------- editing

    fun list(): List<ScheduledTask> = store.all()

    fun save(t: ScheduledTask): ScheduledTask {
        val prev = store.get(t.id)
        var n = if (prev != null) t.copy(
            runHistory = prev.runHistory, fireCount = prev.fireCount, lastFiredAt = prev.lastFiredAt,
            lastResultPreview = prev.lastResultPreview, lastResultSessionId = prev.lastResultSessionId,
        ) else t
        // A relative trigger counts from the moment it is saved (or re-enabled), like upstream.
        if (n.usesAnchor && (prev == null || prev.delaySec != n.delaySec || prev.intervalSec != n.intervalSec || prev.triggerKind != n.triggerKind || (!prev.enabled && n.enabled))) {
            n = n.copy(anchorMs = clock(), triggeredCount = 0)
        }
        store.upsert(n)
        armed.remove(n.id)
        onChange()
        return n
    }

    fun setEnabled(id: String, enabled: Boolean) { store.get(id)?.let { save(it.copy(enabled = enabled)) } }

    fun delete(id: String) {
        running.remove(id)?.cancel()
        store.delete(id); armed.remove(id); onChange()
    }

    /** "Run now": fires immediately; does not count toward an interval's --count (upstream Android). */
    fun runNow(id: String): Boolean {
        val t = store.get(id) ?: return false
        if (isRunning(id)) return false
        launchRun(t, t.prompt)
        return true
    }

    // ---------------------------------------------------------------- timer

    /** One timer step: fire every enabled task whose armed slot has come. Public for tests. */
    fun tick() {
        val now = clock()
        for (t in store.all()) {
            if (!t.enabled || t.triggerKind == ScheduledTriggerKind.ON_COMPLETION) { armed.remove(t.id); continue }
            val slot = armed[t.id] ?: t.nextTriggerMs(now)?.also { armed[t.id] = it }
            if (slot == null) {
                // Window over (end date passed / count used up): disable, keep the row (upstream).
                if (t.triggerKind != ScheduledTriggerKind.CALENDAR || t.endDateMs != null) { store.update(t.id) { it.copy(enabled = false) }; onChange() }
                continue
            }
            if (now < slot) continue
            armed.remove(t.id)
            fire(t, now)
        }
    }

    private fun fire(t: ScheduledTask, now: Long) {
        // Bookkeeping first (upstream ScheduledTaskManager.afterAlarmFire / ONCE handling), so a slow run never fires twice.
        val after = when {
            t.triggerKind == ScheduledTriggerKind.AFTER -> store.update(t.id) { it.copy(enabled = false, triggeredCount = 1) }
            t.triggerKind == ScheduledTriggerKind.INTERVAL -> store.update(t.id) {
                val n = (it.triggeredCount ?: 0) + 1
                val next = it.copy(triggeredCount = n, anchorMs = now)
                if (it.maxFires != null && n >= it.maxFires!!) next.copy(enabled = false) else next
            }
            t.repeatMode == ScheduledRepeatMode.ONCE -> store.update(t.id) { it.copy(enabled = false) }
            else -> t
        } ?: return
        if (after.enabled && after.isCalendar) after.nextTriggerMs(now + 1_000)?.let { armed[t.id] = it }
        onChange()
        if (isRunning(t.id)) { AppLogger.warning(TAG, "task ${t.id} «${t.label}» still running — this fire is skipped"); return }
        launchRun(after, after.prompt)
    }

    private fun launchRun(t: ScheduledTask, prompt: String) {
        val firedAt = clock()
        AppLogger.info(TAG, "fire task=${t.id} «${t.label}» trigger=${t.triggerKind} target=${t.targetMode.encode()}")
        running[t.id] = scope.launch {
            val out = try { runner(t, prompt) } catch (e: CancellationException) {
                ScheduledOutcome(null, "Stopped.", ok = false)
            } catch (e: Exception) {
                AppLogger.error(TAG, "task ${t.id} failed", e)
                ScheduledOutcome(null, "Error: ${e.message}", ok = false)
            }
            markFired(t.id, out, firedAt)
            running.remove(t.id)
            onChange()
            completionFollowUps(t.id, out)
        }
        onChange()
    }

    /** Upstream ScheduledTaskManager.markFired: newest-first run record, capped at MAX_RUN_HISTORY. */
    private fun markFired(id: String, out: ScheduledOutcome, firedAt: Long) {
        val run = ScheduledRun(firedAt = firedAt, sessionId = out.sessionId, preview = out.preview?.take(PREVIEW_CHARS), ok = out.ok)
        store.update(id) { t ->
            t.copy(lastFiredAt = firedAt, lastResultPreview = run.preview, lastResultSessionId = out.sessionId,
                runHistory = (listOf(run) + t.runHistory).take(ScheduledTask.MAX_RUN_HISTORY), fireCount = t.firesSoFar + 1)
        }
    }

    /** Upstream ScheduledCompletionTriggers: tasks waiting on [finishedId] fire once with {{result}} filled in. */
    private fun completionFollowUps(finishedId: String, out: ScheduledOutcome) {
        store.all().filter { it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION && it.onCompletionOf == finishedId }.forEach { w ->
            store.update(w.id) { it.copy(enabled = false, triggeredCount = 1) }
            launchRun(w, w.prompt.replace(ScheduledTask.RESULT_PLACEHOLDER, out.result.take(RESULT_CHARS)))
        }
    }

    companion object {
        private const val TAG = "Scheduled"
        const val PREVIEW_CHARS = 300
        const val RESULT_CHARS = 20_000
    }
}
