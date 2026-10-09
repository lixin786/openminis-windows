// P1-4 scheduled tasks: timer semantics (calendar once / daily, after, interval + count, on-completion with
// {{result}}), run records, catch-up, persistence (upstream JSON shape), the scheduled_task tool, editor rules.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.desktop.agent.RegistryToolbox
import com.openminis.desktop.scheduled.*
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolRegistry
import com.openminis.desktop.ui.ScheduledEditorRules
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import kotlin.test.*

class ScheduledTest {
    private val dir = TestSupport.tmp("om-sched")
    private var now = at(2026, 10, 9, 8, 0)
    private val fired = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun done() { scope.cancel() }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun scheduler(result: (ScheduledTask, String) -> ScheduledOutcome = { t, p -> ScheduledOutcome("conv-${t.label}", "done: $p", ok = true) }) =
        DesktopScheduler(ScheduledStore(File(dir, "scheduled_tasks.json")), scope, runner = { t, p -> fired += t.label to p; result(t, p) }, clock = { now })

    private fun settle(s: DesktopScheduler) = runBlocking { withTimeout(5_000) { while (s.list().any { s.isRunning(it.id) }) delay(10) }; delay(30) }

    private fun daily(label: String, h: Int, m: Int, repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY) =
        ScheduledTask(label = label, timeOfDayHour = h, timeOfDayMinute = m, repeatMode = repeat, prompt = "p-$label")

    @Test fun calendarDailyFiresOncePerDayAndRecordsRuns() {
        val s = scheduler()
        val t = s.save(daily("morning", 9, 0))
        assertEquals(at(2026, 10, 9, 9, 0), s.nextFire(t))
        s.tick(); assertTrue(fired.isEmpty())
        now = at(2026, 10, 9, 9, 0); s.tick(); settle(s)
        now += 30_000; s.tick(); settle(s)
        assertEquals(listOf("morning" to "p-morning"), fired, "one fire for the 09:00 slot")
        val after = s.store.get(t.id)!!
        assertEquals(1, after.firesSoFar)
        assertEquals("conv-morning", after.runHistory.single().sessionId)
        assertTrue(after.enabled)
        assertEquals(at(2026, 10, 10, 9, 0), s.nextFire(after), "re-armed for tomorrow")
        now = at(2026, 10, 10, 9, 0); s.tick(); settle(s)
        assertEquals(2, fired.size)
        assertEquals(2, s.store.get(t.id)!!.runHistory.size, "newest first, both kept")
    }

    @Test fun onceDisablesItselfAndMissedCalendarSlotIsSkipped() {
        val s = scheduler()
        val t = s.save(daily("once", 8, 30, ScheduledRepeatMode.ONCE))
        now = at(2026, 10, 9, 8, 30); s.tick(); settle(s)
        assertFalse(s.store.get(t.id)!!.enabled)
        now = at(2026, 10, 10, 8, 30); s.tick(); settle(s)
        assertEquals(1, fired.size)
        // App was closed over a 07:00 slot: a fresh scheduler arms the next slot, it does not fire late.
        val d = s.save(daily("seven", 7, 0))
        val restarted = scheduler()
        restarted.tick(); settle(restarted)
        assertEquals(1, fired.size)
        assertEquals(at(2026, 10, 11, 7, 0), restarted.nextFire(restarted.store.get(d.id)!!))
    }

    @Test fun afterAndIntervalWithCountAndCatchUp() {
        val s = scheduler()
        val a = s.save(ScheduledTask(label = "after", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "x",
            triggerKind = ScheduledTriggerKind.AFTER, delaySec = 600))
        val i = s.save(ScheduledTask(label = "every", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "y",
            triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 300, maxFires = 2))
        assertEquals(now + 600_000, s.nextFire(a))
        now += 300_000; s.tick(); settle(s)
        assertEquals(listOf("every"), fired.map { it.first })
        now += 300_000; s.tick(); settle(s)
        assertEquals(2, fired.count { it.first == "every" }); assertEquals(1, fired.count { it.first == "after" })
        assertFalse(s.store.get(a.id)!!.enabled, "--after fires once")
        assertFalse(s.store.get(i.id)!!.enabled, "--count 2 reached")
        assertEquals(2, s.store.get(i.id)!!.triggeredCount)
        // Overdue relative trigger after a restart fires once, right away (upstream catch-up).
        val late = s.save(ScheduledTask(label = "late", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "z",
            triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 60))
        now += 3_600_000
        val restarted = scheduler(); restarted.tick(); settle(restarted)
        assertEquals(1, fired.count { it.first == "late" })
        assertEquals(now + 60_000, restarted.nextFire(restarted.store.get(late.id)!!))
    }

    @Test fun runNowDoesNotCountAndOnCompletionGetsTheResult() {
        val s = scheduler { t, p -> ScheduledOutcome("c", "answer of ${t.label}", ok = true, result = "RESULT-42") }
        val first = s.save(daily("first", 23, 0))
        s.save(ScheduledTask(label = "second", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "summarise: ${ScheduledTask.RESULT_PLACEHOLDER}", triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = first.id))
        assertTrue(s.runNow(first.id)); settle(s)
        assertEquals(listOf("first" to "p-first", "second" to "summarise: RESULT-42"), fired.toList())
        assertEquals(at(2026, 10, 9, 23, 0), s.nextFire(s.store.get(first.id)!!), "Run now leaves the schedule alone")
        assertFalse(s.store.get(s.list().first { it.label == "second" }.id)!!.enabled, "on-completion runs once")
    }

    @Test fun failedRunIsRecordedAndOverlappingFireSkipped() {
        val gate = CompletableDeferred<Unit>()
        val s = DesktopScheduler(ScheduledStore(File(dir, "s2.json")), scope, runner = { t, _ ->
            fired += t.label to ""; gate.await(); throw IllegalStateException("provider down") }, clock = { now })
        val t = s.save(ScheduledTask(label = "slow", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "x",
            triggerKind = ScheduledTriggerKind.INTERVAL, intervalSec = 60))
        now += 60_000; s.tick(); runBlocking { delay(100) }
        now += 60_000; s.tick(); runBlocking { delay(100) }
        assertEquals(1, fired.size, "a fire while the previous run is still going is skipped")
        gate.complete(Unit); settle(s)
        val run = s.store.get(t.id)!!.runHistory.first()
        assertFalse(run.ok); assertTrue(run.preview!!.contains("provider down"))
    }

    @Test fun persistsInUpstreamJsonShape() {
        val s = scheduler()
        val t = s.save(daily("周报", 17, 30, ScheduledRepeatMode.CUSTOM).copy(customDays = setOf(java.util.Calendar.FRIDAY),
            targetMode = ScheduledTargetMode.AppendToSession("abc"), prompt = "写本周周报 📋"))
        val raw = JSONArray(File(dir, "scheduled_tasks.json").readText(Charsets.UTF_8)).getJSONObject(0)
        assertEquals("APPEND_TO:abc", raw.getString("targetMode"))
        assertEquals("CUSTOM", raw.getString("repeatMode"))
        assertEquals("写本周周报 📋", ScheduledStore(File(dir, "scheduled_tasks.json")).get(t.id)!!.prompt)
        // An upstream (Android) row loads as-is.
        val f = File(dir, "up.json")
        f.writeText("""[{"id":"u1","label":"Weather","hour":7,"minute":5,"repeatMode":"WEEKDAYS","customDays":"","prompt":"weather?","targetMode":"NEW_SESSION","enabled":true,"createdAt":1}]""")
        val up = ScheduledStore(f).get("u1")!!
        assertEquals(ScheduledRepeatMode.WEEKDAYS, up.repeatMode); assertEquals(7, up.timeOfDayHour)
    }

    @Test fun toolCreatesAfterApprovalAndManages() = runBlocking {
        val s = scheduler()
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        var allow = false
        val inner = RegistryToolbox(ToolRegistry({ com.openminis.desktop.core.AppSettings(workspaceDir = dir.absolutePath) }, MemoryStore(TestSupport.tmp("m"))) { true })
        val tb = ScheduledToolbox(inner, s, ToolApprover { approvals += it; allow }, currentConversation = { "conv-1" })
        assertTrue(tb.definitions().any { it.name == "scheduled_task" })
        val args = JSONObject().put("action", "create").put("prompt", "check disk").put("time", "09:15").put("repeat", "weekdays").put("target", "follow-up")
        val denied = tb.execute("scheduled_task", args)
        assertTrue(denied.denied); assertTrue(s.list().isEmpty())
        allow = true
        val ok = JSONObject(tb.execute("scheduled_task", args).content)
        val t = s.store.get(ok.getString("id"))!!
        assertEquals(ScheduledRepeatMode.WEEKDAYS, t.repeatMode); assertEquals(9, t.timeOfDayHour); assertEquals(15, t.timeOfDayMinute)
        assertEquals(ScheduledTargetMode.AppendToSession("conv-1"), t.targetMode)
        assertTrue(approvals.last().detail.contains("check disk"))
        assertTrue(tb.execute("scheduled_task", JSONObject().put("action", "list")).content.contains("check disk"))
        assertTrue(tb.execute("scheduled_task", JSONObject().put("action", "create").put("prompt", "x").put("time", "9:00").put("after", "5m")).isError, "one trigger only")
        assertTrue(tb.execute("scheduled_task", JSONObject().put("action", "create").put("prompt", "x").put("interval", "30s")).isError, "interval ≥ 1m")
        tb.execute("scheduled_task", JSONObject().put("action", "disable").put("id", t.id.take(8)))
        assertFalse(s.store.get(t.id)!!.enabled)
        tb.execute("scheduled_task", JSONObject().put("action", "delete").put("id", "check disk"))
        assertTrue(s.list().isEmpty())
        assertEquals(7200, ScheduledToolSpec.parseDuration("2h")); assertEquals(1800, ScheduledToolSpec.parseDuration("30"))
    }

    @Test fun editorRules() {
        val base = daily("", 9, 0).copy(prompt = "Morning briefing\nmore")
        val t = ScheduledEditorRules.build(base, "TIME", "7：05", "2026-10-01", "2026-10-31", 0, 0).getOrThrow()
        assertEquals(7, t.timeOfDayHour); assertEquals(5, t.timeOfDayMinute); assertEquals("Morning briefing", t.label)
        assertTrue(ScheduledEditorRules.build(base, "TIME", "25:00", "", "", 0, 0).isFailure)
        assertTrue(ScheduledEditorRules.build(base, "TIME", "09:00", "2026-10-31", "2026-10-01", 0, 0).isFailure)
        assertTrue(ScheduledEditorRules.build(base.copy(repeatMode = ScheduledRepeatMode.CUSTOM), "TIME", "09:00", "", "", 0, 0).isFailure)
        assertTrue(ScheduledEditorRules.build(base.copy(prompt = " "), "AFTER", "", "", "", 5, 0).isFailure)
        val iv = ScheduledEditorRules.build(base, "INTERVAL", "", "", "", 15, 3).getOrThrow()
        assertEquals(900, iv.intervalSec); assertEquals(3, iv.maxFires); assertEquals(ScheduledTriggerKind.INTERVAL, iv.triggerKind)
        assertTrue(ScheduledEditorRules.build(base, "AFTER_TASK", "", "", "", 0, 0).isFailure)
    }
}
