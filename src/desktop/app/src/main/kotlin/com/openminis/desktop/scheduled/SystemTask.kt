// OpenMinis Desktop — HEADLESS-SCHEDULER.md §5: the one Windows scheduled task that wakes the headless runner.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.scheduled

import java.io.File
import java.util.concurrent.TimeUnit

/** Every `schtasks` call goes through this, so tests can assert the command lines without touching the system. */
fun interface CommandExecutor {
    data class Result(val code: Int, val output: String)
    fun run(command: List<String>): Result

    companion object {
        val SYSTEM = CommandExecutor { cmd ->
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val cs = runCatching { java.nio.charset.Charset.forName(System.getProperty("native.encoding") ?: System.getProperty("sun.jnu.encoding")) }
                    .getOrDefault(Charsets.UTF_8)
                val out = p.inputStream.readBytes().toString(cs)
                if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); Result(-1, "timed out") } else Result(p.exitValue(), out.trim())
            } catch (e: Exception) { Result(-1, e.message ?: e.javaClass.simpleName) }
        }
    }
}

/** How Windows should start us headless: the program and its arguments (kept apart — no command-line quoting). */
data class LaunchCommand(val program: String, val arguments: List<String>) {
    /** As Task Scheduler shows "Task To Run". */
    fun display(): String = (listOf(program) + arguments).joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }

    companion object {
        const val FLAG = "--run-due-tasks"

        /**
         * - Installed (jpackage sets `jpackage.app-path`): `OpenMinis.exe --run-due-tasks` — a windowless GUI launcher.
         * - The fat jar: `<java.home>\bin\javaw.exe -jar <jar> --run-due-tasks` (javaw: no console window).
         * - Anything else (classes folder in a dev run): null — cannot be registered.
         */
        fun resolve(
            appPath: String? = System.getProperty("jpackage.app-path"),
            javaHome: String? = System.getProperty("java.home"),
            codeSource: File? = runCatching { File(LaunchCommand::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull(),
        ): LaunchCommand? {
            if (!appPath.isNullOrBlank()) return LaunchCommand(appPath, listOf(FLAG))
            if (javaHome.isNullOrBlank() || codeSource == null || !codeSource.isFile || !codeSource.name.lowercase().endsWith(".jar")) return null
            val javaw = File(File(javaHome, "bin"), "javaw.exe").path
            return LaunchCommand(javaw, listOf("-jar", codeSource.absolutePath, FLAG))
        }
    }
}

/**
 * Registers / queries / removes `OpenMinis\Scheduled tasks`. Registration writes a task XML and runs
 * `schtasks /Create /F /TN … /XML …` — the same task as the spec's `/SC MINUTE /MO n /IT /TR …`, but the
 * program and arguments stay separate fields (a /TR value with quoted paths does not survive Java's
 * Windows argument quoting, and /TR is capped at 261 characters). LogonType InteractiveToken = `/IT`:
 * runs only while the user is signed in, needs no admin rights and stores no password.
 */
class SystemTask(private val exec: CommandExecutor = CommandExecutor.SYSTEM, private val tempDir: File = File(System.getProperty("java.io.tmpdir"))) {
    data class Status(val registered: Boolean, val nextRun: String?, val error: String?)

    fun createCommand(xml: File) = listOf("schtasks", "/Create", "/F", "/TN", TASK_NAME, "/XML", xml.absolutePath)
    fun queryCommand() = listOf("schtasks", "/Query", "/TN", TASK_NAME, "/FO", "CSV", "/NH")
    fun deleteCommand() = listOf("schtasks", "/Delete", "/TN", TASK_NAME, "/F")

    /** Idempotent (/F replaces). Returns null on success, else schtasks' own message. */
    fun register(cmd: LaunchCommand, intervalMinutes: Int, start: java.time.LocalDateTime = java.time.LocalDateTime.now().withSecond(0).withNano(0)): String? {
        val xml = File(tempDir, "openminis-schtask-${System.nanoTime()}.xml")
        return try {
            // Task Scheduler reads UTF-16 with a BOM most reliably.
            xml.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + taskXml(cmd, intervalMinutes, start).toByteArray(Charsets.UTF_16LE))
            val r = exec.run(createCommand(xml))
            if (r.code == 0) null else r.output.ifBlank { "schtasks exit ${r.code}" }
        } finally { xml.delete() }
    }

    fun query(): Status {
        val r = exec.run(queryCommand())
        if (r.code != 0) return Status(false, null, null)
        // CSV, no header: "TaskName","Next Run Time","Status" — positional, so independent of the Windows language.
        val fields = Regex("\"([^\"]*)\"").findAll(r.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()).map { it.groupValues[1] }.toList()
        return Status(true, fields.getOrNull(1)?.takeIf { it.isNotBlank() }, null)
    }

    /** Returns null when the task is gone afterwards (also when it never existed). */
    fun unregister(): String? {
        val r = exec.run(deleteCommand())
        if (r.code == 0) return null
        return if (!query().registered) null else r.output.ifBlank { "schtasks exit ${r.code}" }
    }

    companion object {
        const val TASK_NAME = "OpenMinis\\Scheduled tasks"
        val INTERVALS = listOf(1, 5, 10, 15)

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        private fun arg(a: String) = if (a.isEmpty() || a.any { it == ' ' || it == '\t' }) "\"$a\"" else a

        fun taskXml(cmd: LaunchCommand, intervalMinutes: Int, start: java.time.LocalDateTime): String {
            val iv = intervalMinutes.coerceIn(1, 60)
            return """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>OpenMinis: runs scheduled tasks that are due while the app is closed.</Description>
  </RegistrationInfo>
  <Triggers>
    <TimeTrigger>
      <Repetition>
        <Interval>PT${iv}M</Interval>
        <StopAtDurationEnd>false</StopAtDurationEnd>
      </Repetition>
      <StartBoundary>${start}</StartBoundary>
      <Enabled>true</Enabled>
    </TimeTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>false</StartWhenAvailable>
    <ExecutionTimeLimit>PT15M</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>${esc(cmd.program)}</Command>
      <Arguments>${esc(cmd.arguments.joinToString(" ") { arg(it) })}</Arguments>
    </Exec>
  </Actions>
</Task>
"""
        }
    }
}
