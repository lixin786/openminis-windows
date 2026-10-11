// OpenMinis Desktop — HEADLESS-SCHEDULER.md §4: one process at a time fires scheduled tasks.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.scheduled

import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * `run-due-tasks.lock` in the app-data folder. The OS lock sits on a byte range past the text, so the
 * holder's `{pid, owner, since}` stays readable by others (Windows locks are mandatory). The OS drops the
 * lock when its process dies, so a lock we can take is stale by definition: we take it over and log whose
 * it was. The app holds it for as long as its timer may fire; a headless pass for its run.
 */
class SchedulerLock(val file: File, private val owner: String, private val pid: Long = ProcessHandle.current().pid()) {
    sealed interface Result {
        /** [staleFrom] = what the previous, now dead, holder wrote (null = the file was empty / new). */
        data class Acquired(val staleFrom: String?) : Result
        data class Busy(val holder: String) : Result
    }

    private var raf: RandomAccessFile? = null
    private var lock: FileLock? = null
    val held: Boolean @Synchronized get() = lock?.isValid == true

    @Synchronized fun tryAcquire(now: Long = System.currentTimeMillis()): Result {
        if (held) return Result.Acquired(null)
        file.parentFile?.mkdirs()
        val r = RandomAccessFile(file, "rw")
        val l = try { r.channel.tryLock(LOCK_POS, 1, false) } catch (e: OverlappingFileLockException) { null } catch (e: Exception) {
            AppLogger.warning(TAG, "lock failed: ${e.message}"); null
        }
        if (l == null) { val h = describe(read(r)); r.close(); return Result.Busy(h) }
        val previous = read(r)
        val stale = previous.takeIf { it.isNotBlank() }?.let { p ->
            val o = runCatching { JSONObject(p) }.getOrNull()
            if (o?.optLong("pid") == pid) null else describe(p)
        }
        r.setLength(0); r.seek(0)
        r.write(JSONObject().put("pid", pid).put("owner", owner).put("since", now).toString().toByteArray(Charsets.UTF_8))
        r.channel.force(false)
        raf = r; lock = l
        if (stale != null) AppLogger.warning(TAG, "took over a stale lock ($stale)")
        return Result.Acquired(stale)
    }

    @Synchronized fun release() {
        runCatching { raf?.let { it.setLength(0) } }
        runCatching { lock?.release() }; runCatching { raf?.close() }
        lock = null; raf = null
    }

    private fun read(r: RandomAccessFile): String = runCatching {
        r.seek(0); val n = minOf(r.length(), 4096L).toInt(); val b = ByteArray(n); r.readFully(b); b.toString(Charsets.UTF_8)
    }.getOrDefault("")

    private fun describe(text: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return "unknown holder"
        return "${o.optString("owner", "?")} pid=${o.optLong("pid")} since=${java.time.Instant.ofEpochMilli(o.optLong("since"))}"
    }

    companion object {
        private const val TAG = "Scheduled"
        const val FILE_NAME = "run-due-tasks.lock"
        /** Locked byte, past the JSON text (a range may lie beyond the end of the file). */
        const val LOCK_POS = 1L shl 20
    }
}

/**
 * `headless-state.json`: how far the clock was checked (shared by the app timer and headless passes), the
 * last headless pass, and the "needed your OK" notices the app shows at its next start.
 */
class HeadlessState(private val file: File) {
    data class Notice(val taskId: String, val label: String, val step: String, val at: Long)
    data class LastRun(val at: Long, val ran: Int, val ok: Int, val failed: Int, val needsApproval: Int, val timedOut: Boolean, val note: String = "")
    data class Data(val checkedUpTo: Long?, val lastRun: LastRun?, val notices: List<Notice>)

    @Synchronized fun read(): Data {
        val o = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return Data(null, null, emptyList())
        val lr = o.optJSONObject("lastRun")?.let {
            LastRun(it.optLong("at"), it.optInt("ran"), it.optInt("ok"), it.optInt("failed"), it.optInt("needsApproval"), it.optBoolean("timedOut"), it.optString("note"))
        }
        val ns = o.optJSONArray("notices")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Notice(it.optString("taskId"), it.optString("label"), it.optString("step"), it.optLong("at")) } } ?: emptyList()
        return Data(if (o.has("checkedUpTo")) o.optLong("checkedUpTo") else null, lr, ns)
    }

    @Synchronized fun update(f: (Data) -> Data) {
        val d = f(read())
        val o = JSONObject()
        d.checkedUpTo?.let { o.put("checkedUpTo", it) }
        d.lastRun?.let { o.put("lastRun", JSONObject().put("at", it.at).put("ran", it.ran).put("ok", it.ok).put("failed", it.failed)
            .put("needsApproval", it.needsApproval).put("timedOut", it.timedOut).put("note", it.note)) }
        o.put("notices", org.json.JSONArray().also { a -> d.notices.takeLast(50).forEach { n ->
            a.put(JSONObject().put("taskId", n.taskId).put("label", n.label).put("step", n.step).put("at", n.at)) } })
        com.openminis.desktop.core.atomicWrite(file, o.toString(2))
    }

    /** Notices for the app's next start; returns them and clears the list. */
    fun takeNotices(): List<Notice> { var out = emptyList<Notice>(); update { out = it.notices; it.copy(notices = emptyList()) }; return out }

    companion object { const val FILE_NAME = "headless-state.json" }
}
