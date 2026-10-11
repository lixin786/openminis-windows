// HEADLESS-SCHEDULER.md — Windows task registration (fake executor), lock, due detection, headless passes end to end.
package com.openminis.desktop

import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.desktop.core.*
import com.openminis.desktop.scheduled.*
import com.openminis.desktop.security.HeadlessApprover
import com.openminis.desktop.tools.ApprovalRequest
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.Calendar
import kotlin.test.*

class HeadlessSchedulerTest {
    // The end-to-end cases run the real headless path, which applies the user's language: pin English around every case.
    private var prevLang = com.openminis.desktop.i18n.I18n.active
    @org.junit.jupiter.api.BeforeEach fun pinEnglish() { prevLang = com.openminis.desktop.i18n.I18n.active; com.openminis.desktop.i18n.I18n.apply("en") }
    @org.junit.jupiter.api.AfterEach fun restoreLanguage() { com.openminis.desktop.i18n.I18n.apply(prevLang) }

    @Test fun headlessPassLeavesTheGlobalLanguageAlone() {
        val server = MockWebServer(); server.start()
        try {
            val (root, _) = seed(server, enabled = true, language = "zh-CN")
            server.enqueue(sse("""{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"}}]}""",
                """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
            assertEquals(0, HeadlessRunner.run(AppPaths(root)))
            assertEquals("en", com.openminis.desktop.i18n.I18n.active, "a headless pass must not leave the process language changed")
        } finally { server.shutdown() }
    }
    private class FakeExec(var answer: (List<String>) -> CommandExecutor.Result = { CommandExecutor.Result(0, "") }) : CommandExecutor {
        val calls = mutableListOf<List<String>>()
        var lastXml: String? = null
        override fun run(command: List<String>): CommandExecutor.Result {
            calls += command
            val i = command.indexOf("/XML")
            if (i >= 0 && i + 1 < command.size) lastXml = File(command[i + 1]).readBytes().let { b -> String(b, 2, b.size - 2, Charsets.UTF_16LE) }
            return answer(command)
        }
    }

    private val jarCmd = LaunchCommand("C:\\Program Files\\Java\\jdk-17\\bin\\javaw.exe", listOf("-jar", "C:\\Users\\Li Xin\\OpenMinis app.jar", LaunchCommand.FLAG))

    @Test fun registerWritesOneInteractiveJavawTask() {
        val tmp = TestSupport.tmp("xml")
        val ex = FakeExec()
        val task = SystemTask(ex, tmp)
        assertNull(task.register(jarCmd, 5, LocalDateTime.of(2026, 10, 12, 1, 0)))
        val c = ex.calls.single()
        assertEquals(listOf("schtasks", "/Create", "/F", "/TN", "OpenMinis\\Scheduled tasks", "/XML"), c.take(6))
        val xml = ex.lastXml!!
        assertTrue("<LogonType>InteractiveToken</LogonType>" in xml, "= /IT: only while signed in, no password")
        assertTrue("<RunLevel>LeastPrivilege</RunLevel>" in xml, "no admin")
        assertTrue("<Interval>PT5M</Interval>" in xml)
        assertTrue("<Command>C:\\Program Files\\Java\\jdk-17\\bin\\javaw.exe</Command>" in xml)
        assertTrue("<Arguments>-jar &quot;C:\\Users\\Li Xin\\OpenMinis app.jar&quot; --run-due-tasks</Arguments>" in xml, xml)
        assertTrue("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>" in xml)
        assertTrue("<StartBoundary>2026-10-12T01:00</StartBoundary>" in xml)
        assertFalse(tmp.listFiles()!!.any { it.name.endsWith(".xml") }, "temp XML removed")
        // toggling again replaces the same task (/F), never a second one
        task.register(jarCmd, 15); task.register(jarCmd, 1)
        assertEquals(1, ex.calls.map { it[it.indexOf("/TN") + 1] }.toSet().size)
        assertTrue(ex.calls.all { "/F" in it })
        assertTrue("<Interval>PT1M</Interval>" in ex.lastXml!!)
    }

    @Test fun registerFailureIsOneSentence() {
        val ex = FakeExec { CommandExecutor.Result(1, "ERROR: Access is denied.") }
        assertEquals("ERROR: Access is denied.", SystemTask(ex, TestSupport.tmp("x")).register(jarCmd, 5))
        assertEquals(1, ex.calls.size, "no retry")
    }

    @Test fun queryAndUnregister() {
        val ex = FakeExec { c -> if ("/Query" in c) CommandExecutor.Result(0, "\"\\OpenMinis\\Scheduled tasks\",\"2026/10/12 1:05:00\",\"Ready\"") else CommandExecutor.Result(0, "") }
        val t = SystemTask(ex)
        assertEquals(SystemTask.Status(true, "2026/10/12 1:05:00", null), t.query())
        assertEquals(listOf("schtasks", "/Query", "/TN", "OpenMinis\\Scheduled tasks", "/FO", "CSV", "/NH"), ex.calls.last())
        assertNull(t.unregister())
        assertEquals(listOf("schtasks", "/Delete", "/TN", "OpenMinis\\Scheduled tasks", "/F"), ex.calls.last())
        // not registered: delete fails, query fails → already gone, fine
        val gone = FakeExec { CommandExecutor.Result(1, "ERROR: The system cannot find the file specified.") }
        assertNull(SystemTask(gone).unregister())
        assertFalse(SystemTask(gone).query().registered)
        // delete fails but the task is still there → error
        val stuck = FakeExec { c -> if ("/Query" in c) CommandExecutor.Result(0, "\"x\",\"N/A\",\"Ready\"") else CommandExecutor.Result(1, "denied") }
        assertEquals("denied", SystemTask(stuck).unregister())
    }

    @Test fun launchCommand() {
        assertEquals(LaunchCommand("C:\\Program Files\\OpenMinis\\OpenMinis.exe", listOf("--run-due-tasks")),
            LaunchCommand.resolve(appPath = "C:\\Program Files\\OpenMinis\\OpenMinis.exe", javaHome = "x", codeSource = null))
        val jar = File(TestSupport.tmp("lc"), "OpenMinis-windows-x64-1.14.0.jar").apply { writeText("x") }
        val r = LaunchCommand.resolve(appPath = null, javaHome = "C:\\jdk", codeSource = jar)!!
        assertTrue(r.program.endsWith("javaw.exe"), r.program)
        assertEquals(listOf("-jar", jar.absolutePath, "--run-due-tasks"), r.arguments)
        assertNull(LaunchCommand.resolve(appPath = null, javaHome = "C:\\jdk", codeSource = TestSupport.tmp("classes")), "dev classes folder")
        assertEquals("\"C:\\Program Files\\x.exe\" --run-due-tasks", LaunchCommand("C:\\Program Files\\x.exe", listOf("--run-due-tasks")).display())
    }

    @Test fun lockIsExclusiveReadableAndStaleLocksAreTakenOver() {
        val f = File(TestSupport.tmp("lock"), SchedulerLock.FILE_NAME)
        val app = SchedulerLock(f, "app", pid = 111)
        assertEquals(SchedulerLock.Result.Acquired(null), app.tryAcquire())
        val busy = SchedulerLock(f, "headless", pid = 222).tryAcquire()
        assertTrue(busy is SchedulerLock.Result.Busy && busy.holder.startsWith("app pid=111"), busy.toString())
        app.release()
        val h = SchedulerLock(f, "headless", pid = 222)
        assertTrue(h.tryAcquire() is SchedulerLock.Result.Acquired)
        h.release()
        // a crashed holder: its text is still there, the OS lock is gone
        f.writeText("""{"pid":999999,"owner":"headless","since":1}""")
        val r = SchedulerLock(f, "app", pid = 333).tryAcquire()
        assertTrue(r is SchedulerLock.Result.Acquired && r.staleFrom!!.startsWith("headless pid=999999"), r.toString())
        assertEquals(333, JSONObject(f.readText()).getLong("pid"))
    }

    @Test fun startupReconcileRepairsMissingStaleAndLeftoverTasks() {
        val cmd = LaunchCommand("C:\\Program Files\\OpenMinis\\OpenMinis.exe", listOf("--run-due-tasks"))
        fun exported(c: LaunchCommand, iv: Int) = SystemTask.taskXml(c, iv, java.time.LocalDateTime.of(2026, 10, 12, 2, 0))
        fun run(present: String?, enabled: Boolean, iv: Int = 5, launcher: LaunchCommand? = cmd, createOk: Boolean = true): Pair<SystemTask.Reconcile, FakeExec> {
            var exists = present != null
            val fx = FakeExec { c ->
                when {
                    "/XML" in c && "/Query" in c -> if (exists) CommandExecutor.Result(0, present!!) else CommandExecutor.Result(1, "ERROR: The system cannot find the file specified.")
                    "/Create" in c -> if (createOk) { exists = true; CommandExecutor.Result(0, "SUCCESS") } else CommandExecutor.Result(1, "ERROR: Access is denied.")
                    "/Delete" in c -> { exists = false; CommandExecutor.Result(0, "SUCCESS") }
                    else -> CommandExecutor.Result(if (exists) 0 else 1, "")
                }
            }
            return SystemTask(fx, TestSupport.tmp("rx")).reconcile(enabled, iv, launcher) to fx
        }
        fun creates(fx: FakeExec) = fx.calls.count { "/Create" in it }
        fun deletes(fx: FakeExec) = fx.calls.count { "/Delete" in it }

        // on + in sync: query only, nothing rewritten (also case-insensitive path)
        run(exported(cmd, 5), true).let { (r, fx) -> assertEquals(SystemTask.Reconciled.IN_SYNC, r.action); assertEquals(1, fx.calls.size) }
        run(exported(cmd.copy(program = cmd.program.uppercase()), 5), true).let { (r, _) -> assertEquals(SystemTask.Reconciled.IN_SYNC, r.action) }
        // on + gone (cleanup tool / update): registered again
        run(null, true).let { (r, fx) -> assertEquals(SystemTask.Reconciled.REGISTERED, r.action); assertEquals(1, creates(fx)); assertTrue(fx.lastXml!!.contains("--run-due-tasks")) }
        // on + app moved / other interval: replaced, pointing at the current program
        run(exported(LaunchCommand("D:\\old\\OpenMinis.exe", listOf("--run-due-tasks")), 5), true).let { (r, fx) ->
            assertEquals(SystemTask.Reconciled.REPAIRED, r.action); assertTrue(fx.lastXml!!.contains("<Command>C:\\Program Files\\OpenMinis\\OpenMinis.exe</Command>")) }
        run(exported(cmd, 15), true, iv = 5).let { (r, fx) -> assertEquals(SystemTask.Reconciled.REPAIRED, r.action); assertTrue(fx.lastXml!!.contains("PT5M")) }
        // on + garbage export: replaced rather than trusted
        run("not xml", true).let { (r, _) -> assertEquals(SystemTask.Reconciled.REPAIRED, r.action) }
        // on + cannot be started by Windows (dev classes): leave Windows alone
        run(null, true, launcher = null).let { (r, fx) -> assertEquals(SystemTask.Reconciled.NO_LAUNCHER, r.action); assertEquals(0, creates(fx)) }
        // on + Windows refuses: reported once, no retry loop
        run(null, true, createOk = false).let { (r, fx) -> assertEquals(SystemTask.Reconciled.FAILED, r.action); assertTrue(r.error!!.contains("Access is denied")); assertEquals(1, creates(fx)) }
        // off + leftover: removed; off + nothing: nothing touched
        run(exported(cmd, 5), false).let { (r, fx) -> assertEquals(SystemTask.Reconciled.REMOVED, r.action); assertEquals(1, deletes(fx)); assertEquals(0, creates(fx)) }
        run(null, false).let { (r, fx) -> assertEquals(SystemTask.Reconciled.IN_SYNC, r.action); assertEquals(1, fx.calls.size) }
    }

    /** A real second JVM takes the lock and is killed (no release, no cleanup): the next owner must take over. */
    @Test fun lockOfAKilledProcessIsTakenOver() {
        val f = File(TestSupport.tmp("lockkill"), SchedulerLock.FILE_NAME)
        val javaExe = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").path
        val child = ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"), LockHolderMain::class.java.name, f.absolutePath)
            .redirectErrorStream(true).start()
        try {
            // skip JVM notices such as "Picked up JAVA_TOOL_OPTIONS" (the zh-CN run sets it)
            val line = child.inputStream.bufferedReader().lineSequence().first { it == "LOCKED" || it.startsWith("BUSY") }
            assertEquals("LOCKED", line)
            val busy = SchedulerLock(f, "app", pid = 4242).tryAcquire()
            assertTrue(busy is SchedulerLock.Result.Busy && busy.holder.startsWith("headless pid=${child.pid()}"), busy.toString())
            child.destroyForcibly(); assertTrue(child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
            val app = SchedulerLock(f, "app", pid = 4242)
            val r = app.tryAcquire()
            assertTrue(r is SchedulerLock.Result.Acquired && r.staleFrom!!.startsWith("headless pid=${child.pid()}"), r.toString())
            assertEquals(4242, JSONObject(f.readText()).getLong("pid"))
            app.release()
        } finally { child.destroyForcibly() }
    }

    @Test fun headlessStateRoundTrip() {
        val s = HeadlessState(File(TestSupport.tmp("hs"), HeadlessState.FILE_NAME))
        assertNull(s.read().checkedUpTo)
        s.update { it.copy(checkedUpTo = 5, lastRun = HeadlessState.LastRun(5, 2, 1, 0, 1, false), notices = listOf(HeadlessState.Notice("t", "L", "shell: x", 6))) }
        assertEquals(5L, s.read().checkedUpTo); assertEquals(1, s.read().lastRun!!.needsApproval)
        assertEquals(listOf(HeadlessState.Notice("t", "L", "shell: x", 6)), s.takeNotices())
        assertTrue(s.read().notices.isEmpty()); assertEquals(5L, s.read().checkedUpTo)
    }

    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 12, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun daily(h: Int, m: Int, last: Long? = null) =
        ScheduledTask(label = "d$h$m", timeOfDayHour = h, timeOfDayMinute = m, repeatMode = ScheduledRepeatMode.DAILY, prompt = "p", createdAt = at(0, 0), lastFiredAt = last)

    private fun scheduler(root: File, now: () -> Long) =
        DesktopScheduler(ScheduledStore(File(root, "scheduled_tasks.json")), CoroutineScope(Dispatchers.Default), { _, _ -> ScheduledOutcome(null, "x", true) }, clock = now)

    @Test fun dueDetectionUsesPersistedState() {
        val root = TestSupport.tmp("due")
        val now = at(10, 3)
        val sch = scheduler(root) { now }
        val due = daily(10, 0)
        val old = daily(8, 0)                              // 2 h ago: beyond the grace → skipped
        val fired = daily(10, 1, last = at(10, 1))         // already ran in the app
        val future = daily(10, 30)
        val after = ScheduledTask(label = "a", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "p",
            triggerKind = ScheduledTriggerKind.AFTER, delaySec = 60, anchorMs = at(9, 0))   // overdue → once
        val off = daily(10, 0).copy(id = "off", enabled = false)
        val chained = ScheduledTask(label = "c", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "p",
            triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = due.id)
        listOf(due, old, fired, future, after, off, chained).forEach { sch.store.upsert(it) }
        val ids = sch.dueTasks(checkedUpTo = at(9, 58), now = now).map { it.label }.toSet()
        assertEquals(setOf(due.label, after.label), ids)
        // nothing checked since 10:01 → the 10:00 slot belongs to an earlier check
        assertEquals(setOf(after.label), sch.dueTasks(checkedUpTo = at(10, 1), now = now).map { it.label }.toSet())
    }

    @Test fun runDueRecordsHeadlessRunsApprovalsFollowUpsAndTimeout() = runBlocking {
        val root = TestSupport.tmp("rd")
        val now = at(10, 3)
        val sch = scheduler(root) { now }
        val a = daily(10, 0).copy(label = "A")
        val b = daily(10, 1).copy(label = "B")
        val chain = ScheduledTask(label = "C", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "got {{result}}",
            triggerKind = ScheduledTriggerKind.ON_COMPLETION, onCompletionOf = a.id)
        listOf(a, b, chain).forEach { sch.store.upsert(it) }
        val prompts = mutableListOf<String>()
        val s = sch.runDue(at(9, 58), deadline = Long.MAX_VALUE) { t, p ->
            prompts += p
            if (t.label == "B") ScheduledOutcome("conv-b", "Needs your approval (not run): shell: rm", ok = false, status = DesktopScheduler.STATUS_NEEDS_APPROVAL)
            else ScheduledOutcome("conv-" + t.label, "done ${t.label}", ok = true, result = "R-${t.label}")
        }
        assertEquals(3, s.ran); assertEquals(2, s.ok); assertEquals(0, s.failed); assertFalse(s.timedOut)
        assertEquals(listOf("B"), s.needsApproval.map { it.first.label })
        assertTrue("got R-A" in prompts)
        val ra = sch.store.get(a.id)!!.runHistory.single()
        assertEquals("headless", ra.source); assertTrue(ra.ok); assertNull(ra.status)
        val rb = sch.store.get(b.id)!!.runHistory.single()
        assertEquals("needs-approval", rb.status); assertFalse(rb.ok); assertEquals("headless", rb.source)
        assertFalse(sch.store.get(chain.id)!!.enabled)
        // a second pass right after: nothing is due twice
        assertEquals(0, sch.runDue(now, Long.MAX_VALUE) { _, _ -> error("must not run") }.ran)
        // timeout: the deadline has passed → recorded as timeout, the pass stops
        val c = daily(10, 2).copy(label = "T"); sch.store.upsert(c)
        val t = sch.runDue(at(10, 1) + 1, deadline = 0) { _, _ -> error("must not run") }
        assertTrue(t.timedOut)
        assertEquals("timeout", sch.store.get(c.id)!!.runHistory.single().status)
        // a run that is too slow is cut off
        val d = ScheduledTask(label = "S", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "p",
            triggerKind = ScheduledTriggerKind.AFTER, delaySec = 1, anchorMs = 0)
        val real = scheduler(TestSupport.tmp("rd2")) { System.currentTimeMillis() }
        real.store.upsert(d)
        var started = false
        val slow = real.runDue(System.currentTimeMillis(), deadline = System.currentTimeMillis() + 300) { _, _ -> started = true; delay(10_000); ScheduledOutcome(null, "late", true) }
        assertTrue(started); assertTrue(slow.timedOut); assertEquals("timeout", real.store.get(d.id)!!.runHistory.single().status)
    }

    @Test fun inAppTimerOnlyFiresWithTheLock() {
        val root = TestSupport.tmp("gate")
        var fired = 0
        val sch = DesktopScheduler(ScheduledStore(File(root, "s.json")), CoroutineScope(Dispatchers.Default), { _, _ -> fired++; ScheduledOutcome(null, "x", true) })
        sch.store.upsert(ScheduledTask(label = "a", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "p",
            triggerKind = ScheduledTriggerKind.AFTER, delaySec = 1, anchorMs = 0))
        var beats = 0
        sch.gate = { false }; sch.heartbeat = { beats++ }
        sch.tick(); Thread.sleep(200)
        assertEquals(0, fired); assertEquals(0, beats)
        sch.gate = { true }
        sch.tick(); Thread.sleep(500)
        assertEquals(1, fired); assertEquals(1, beats)
    }

    @Test fun headlessApproverNeverSaysYesAndStopsTheRun() = runBlocking {
        val ha = HeadlessApprover()
        val job = launch { delay(10_000) }
        ha.begin(job)
        assertFalse(ha.approve(ApprovalRequest("shell", "shell: echo hi > out.txt", "detail")))
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("shell: echo hi > out.txt — detail"), ha.take())
    }

    // ------------------------------------------------------------ end to end (real AppState, mock model)

    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString("") { "data: $it\n\n" })

    private fun seed(server: MockWebServer, enabled: Boolean = true, language: String = "system"): Pair<File, ScheduledTask> {
        val root = TestSupport.tmp("hl-root")
        val ws = File(root, "ws").apply { mkdirs() }
        val s = AppSettings(activeProvider = ProviderKind.OPENAI_COMPATIBLE, workspaceDir = ws.absolutePath, headlessScheduler = enabled, confirmShellCommands = true, language = language)
            .withProvider(ProviderKind.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k", baseUrl = server.url("/v1").toString().trimEnd('/'), model = "m"))
        SettingsStore(File(root, "settings.json")).save(s)
        val t = ScheduledTask(label = "Morning", timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE, prompt = "say hi",
            triggerKind = ScheduledTriggerKind.AFTER, delaySec = 60, anchorMs = System.currentTimeMillis() - 120_000)
        ScheduledStore(File(root, "scheduled_tasks.json")).upsert(t)
        return root to t
    }

    @Test fun endToEndHeadlessPassRunsTheDueTask() {
        val server = MockWebServer()
        server.enqueue(sse("""{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello from headless"}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        server.start()
        try {
            val (root, t) = seed(server)
            assertEquals(0, HeadlessRunner.run(AppPaths(root)))
            val task = ScheduledStore(File(root, "scheduled_tasks.json")).get(t.id)!!
            val run = task.runHistory.single()
            assertEquals("headless", run.source); assertTrue(run.ok, run.preview); assertEquals("Hello from headless", run.preview)
            assertFalse(task.enabled, "an after-task fires once")
            assertTrue(File(root, "conversations").listFiles()!!.any { it.readText().contains("Hello from headless") }, "the chat got the reply")
            val st = HeadlessState(File(root, HeadlessState.FILE_NAME)).read()
            assertEquals(1, st.lastRun!!.ran); assertNotNull(st.checkedUpTo)
            assertEquals("", File(root, SchedulerLock.FILE_NAME).readText(), "lock released")
        } finally { server.shutdown() }
    }

    @Test fun endToEndApprovalIsNeverGrantedHeadless() {
        val server = MockWebServer()
        server.enqueue(sse(
            """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"shell","arguments":""}}]}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"command\":\"echo pwned > out.txt\"}"}}]}}]}""",
            """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""", "[DONE]"))
        server.enqueue(sse("""{"id":"2","choices":[{"index":0,"delta":{"role":"assistant","content":"worked around it"}}]}""",
            """{"id":"2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
        server.start()
        try {
            val (root, t) = seed(server)
            assertEquals(0, HeadlessRunner.run(AppPaths(root)))
            assertFalse(File(root, "ws/out.txt").exists(), "the command must not run")
            assertEquals(1, server.requestCount, "the run stops at the approval; the model gets no second round")
            val run = ScheduledStore(File(root, "scheduled_tasks.json")).get(t.id)!!.runHistory.single()
            assertEquals("needs-approval", run.status); assertEquals("headless", run.source); assertFalse(run.ok)
            assertTrue(run.preview!!.contains("echo pwned"), run.preview)
            val st = HeadlessState(File(root, HeadlessState.FILE_NAME)).read()
            assertEquals(1, st.lastRun!!.needsApproval)
            assertEquals("Morning", st.notices.single().label)
        } finally { server.shutdown() }
    }

    @Test fun headlessExitsWhenTheAppHoldsTheLockOrTheSwitchIsOff() {
        val server = MockWebServer(); server.start()
        try {
            val (root, t) = seed(server)
            val app = SchedulerLock(File(root, SchedulerLock.FILE_NAME), "app")
            assertTrue(app.tryAcquire() is SchedulerLock.Result.Acquired)
            assertEquals(0, HeadlessRunner.run(AppPaths(root)))
            app.release()
            assertEquals(0, server.requestCount)
            assertTrue(ScheduledStore(File(root, "scheduled_tasks.json")).get(t.id)!!.runHistory.isEmpty())
            val (root2, t2) = seed(server, enabled = false)
            assertEquals(0, HeadlessRunner.run(AppPaths(root2)))
            assertEquals(0, server.requestCount)
            assertTrue(ScheduledStore(File(root2, "scheduled_tasks.json")).get(t2.id)!!.runHistory.isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun wiringDocsAndI18n() {
        assertFalse(AppSettings().headlessScheduler, "off by default")
        assertEquals(5, AppSettings().headlessIntervalMinutes)
        val src = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/kotlin/com/openminis/desktop") }.first { it.isDirectory }
        val main = File(src, "Main.kt").readText()
        val flagAt = main.indexOf("LaunchCommand.FLAG"); val appAt = main.indexOf("application {")
        assertTrue(flagAt in 0 until appAt, "headless branch before any window")
        assertTrue(main.indexOf("\"java.awt.headless\", \"true\"") in 0 until appAt)
        assertTrue(main.contains("exitProcess(com.openminis.desktop.scheduled.HeadlessRunner.run(paths))"))
        // schtasks only behind the executor
        val appState = File(src, "ui/AppState.kt").readText()
        assertTrue(Regex("scheduler\\.start\\(\\)\\s*\\n\\s*reconcileHeadless\\(\\)").containsMatchIn(appState), "startup reconciles the Windows task (window app only)")
        val schtasks = src.walkTopDown().filter { it.isFile && it.extension == "kt" && "\"schtasks\"" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("SystemTask.kt"), schtasks)
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "HEADLESS-SCHEDULER.md").isFile }
        assertFalse(File(repo, "README.md").readText().contains("应用不运行时不会执行**"))
        assertFalse(File(repo, "src/desktop/PORTING.md").readText().contains("Tasks do not run while the app is not running"))
        val keys = listOf("section", "toggle", "difference", "interval", "interval_n", "status_checking", "status_registered", "status_not_registered",
            "last_run", "last_never", "last_none_due", "last_result", "remove", "silent_hint", "windows_only", "no_launcher", "register_failed",
            "unregister_failed", "repair_failed", "notice").map { "headless.$it" } +
            listOf("sched.last_run", "sched.run.headless", "sched.run.needs_approval", "sched.run.timeout", "sched.run.skipped_approval", "sched.runs_in_background")
        for (l in listOf("en", "zh-CN", "zh-TW")) keys.forEach { assertTrue(com.openminis.desktop.i18n.I18n.table(l)[it].orEmpty().isNotBlank(), "$l:$it") }
    }
}
