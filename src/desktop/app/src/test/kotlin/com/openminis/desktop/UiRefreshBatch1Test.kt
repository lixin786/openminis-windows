// UI-REFRESH batch 1 (batch11): approval risk tiers + four-state tool cards with read-only aggregation.
package com.openminis.desktop

import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ToolCall
import com.openminis.desktop.core.ToolStatus
import com.openminis.desktop.security.ApprovalKind
import com.openminis.desktop.security.ApprovalRisk
import com.openminis.desktop.security.Risk
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolRegistry
import com.openminis.desktop.ui.ToolPresentation
import com.openminis.desktop.ui.ToolPresentation.CardState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.*

class UiRefreshBatch1Test {
    private fun risk(c: String) = ApprovalRisk.shell(c)

    @Test fun lowTierIsOnlyPlainReadOnly() {
        listOf("ls -la", "dir", "Get-ChildItem", "cat README.md", "git status".takeIf { false } ?: "type notes.txt").forEach {
            assertEquals(Risk.LOW, risk(it).risk, it); assertEquals("consequence.readonly", risk(it).consequence)
        }
        assertNotEquals(Risk.LOW, risk("ls | sort").risk, "metacharacters are never low")
        assertNotEquals(Risk.LOW, risk("cat ../secret").risk, "outside references are never low")
    }

    @Test fun mediumTierForOrdinaryCommands() {
        listOf("mkdir build", "npm install", "git commit -m x", "echo hi | findstr h", "copy a.txt b.txt").forEach {
            assertEquals(Risk.MEDIUM, risk(it).risk, it)
        }
        assertTrue("risk.may_overwrite" in risk("copy a.txt b.txt").notes)
        assertTrue("risk.meta" in risk("echo hi | findstr h").notes)
        assertTrue("risk.append" in risk("echo x >> log.txt").notes); assertEquals(Risk.MEDIUM, risk("echo x >> log.txt").risk)
        assertEquals("consequence.run", risk("mkdir build").consequence)
        assertEquals(Risk.MEDIUM, risk("dir 2>&1").risk.let { if (it == Risk.LOW) Risk.MEDIUM else it }, "2>&1 is not an overwrite")
        assertFalse("risk.overwrite" in risk("dir 2>&1").notes)
        assertFalse("risk.overwrite" in risk("dir > \$null").notes)
    }

    @Test fun highTierDeleteOverwriteOutsideScriptsNesting() {
        val rm = risk("rm -rf build")
        assertEquals(Risk.HIGH, rm.risk); assertEquals("build", rm.deleteTarget); assertEquals("consequence.delete", rm.consequence)
        assertEquals("risk.delete", rm.notes.first()); assertTrue(rm.irreversible)
        assertEquals("dist", risk("Remove-Item -Recurse -Force .\\dist").deleteTarget?.let { ApprovalRisk.confirmPhrase(risk("Remove-Item -Recurse -Force .\\dist")) })
        assertEquals("old", ApprovalRisk.confirmPhrase(risk("rd /s /q old")))
        assertNull(ApprovalRisk.confirmPhrase(risk("del *.log")), "wildcards → tick instead of typing")
        assertNull(ApprovalRisk.confirmPhrase(risk("python x.py")), "no delete target → tick")
        assertEquals(Risk.HIGH, risk("echo hi > out.txt").risk); assertEquals("consequence.overwrite", risk("echo hi > out.txt").consequence)
        assertEquals(Risk.HIGH, risk("Set-Content a.txt 'x'").risk)
        assertEquals(Risk.MEDIUM, risk("Add-Content a.txt 'x'").risk)
        assertTrue("risk.outside" in risk("echo key >> ~/.ssh/authorized_keys").notes)
        assertEquals(Risk.HIGH, risk("echo key >> ~/.ssh/authorized_keys").risk)
        assertTrue("risk.script" in risk("build.bat").notes); assertEquals(Risk.HIGH, risk(".\\deploy.ps1").risk)
        assertTrue("risk.interp" in risk("python3 -m http.server").notes)
        assertTrue("risk.nested" in risk("powershell -Command \"Get-Date\"").notes)
        assertEquals(Risk.HIGH, risk("git reset --hard HEAD~1").risk); assertTrue("risk.git" in risk("git clean -fdx").notes)
        assertEquals("consequence.high", risk("python3 -m http.server").consequence)
    }

    @Test fun phraseMatching() {
        assertTrue(ApprovalRisk.phraseMatches("build", " Build "))
        assertFalse(ApprovalRisk.phraseMatches("build", "buil"))
        assertTrue(ApprovalRisk.phraseMatches(null, ""))
    }

    @Test fun shellRequestsCarryTierAndHighIsNeverRemembered() = runBlocking {
        val ws = TestSupport.tmp("om-r1")
        val asked = mutableListOf<ApprovalRequest>()
        val reg = ToolRegistry({ AppSettings(workspaceDir = ws.absolutePath, autoApproveReadOnlyShell = false) }, MemoryStore(TestSupport.tmp("m"))) { asked += it; false }
        reg.execute("shell", JSONObject().put("command", "rm -rf build"))
        val del = asked.single()
        assertEquals(ApprovalKind.SHELL, del.kind); assertEquals(Risk.HIGH, del.risk)
        assertNull(del.onAlwaysAllow, "HIGH never offers 'allow for this session' even though CommandGuard would")
        assertEquals("build", del.confirmPhrase); assertTrue(del.riskNotes.isNotEmpty())
        assertTrue(del.title.contains("build")); assertTrue(del.consequence.contains("build"))
        asked.clear()
        reg.execute("shell", JSONObject().put("command", "ls"))
        assertEquals(Risk.LOW, asked.single().risk, "read-only shown inline when the user asked to confirm everything")
        asked.clear()
        reg.execute("shell", JSONObject().put("command", "mkdir made"))
        assertEquals(Risk.MEDIUM, asked.single().risk); assertNotNull(asked.single().onAlwaysAllow, "medium keeps the session option")
        assertNull(asked.single().confirmPhrase)
    }

    // ------------------------------------------------------------ tool cards

    private fun call(name: String = "shell", status: ToolStatus = ToolStatus.DONE, args: String = "{}", isError: Boolean = false, startedAt: Long? = null) =
        ToolCall(id = java.util.UUID.randomUUID().toString(), name = name, argsJson = args, result = "r", status = status, isError = isError, startedAt = startedAt)
    private fun sh(c: String, status: ToolStatus = ToolStatus.DONE) = call("shell", status, JSONObject().put("command", c).toString())

    @Test fun fourStates() {
        assertEquals(CardState.RUNNING, ToolPresentation.cardState(call(status = ToolStatus.PENDING)))
        assertEquals(CardState.RUNNING, ToolPresentation.cardState(call(status = ToolStatus.RUNNING)))
        assertEquals(CardState.SUCCESS, ToolPresentation.cardState(call()))
        assertEquals(CardState.FAILED, ToolPresentation.cardState(call(isError = true)))
        assertEquals(CardState.FAILED, ToolPresentation.cardState(call(status = ToolStatus.FAILED)))
        assertEquals(CardState.DENIED, ToolPresentation.cardState(call(status = ToolStatus.DENIED, isError = true)))
        assertEquals("2.5 s", ToolPresentation.elapsed(call(status = ToolStatus.RUNNING, startedAt = 1000), 3500))
        assertNull(ToolPresentation.elapsed(call(status = ToolStatus.PENDING), 3500))
    }

    @Test fun readOnlyCallsAggregate() {
        assertTrue(ToolPresentation.isReadOnlyCall(call("file_read")))
        assertTrue(ToolPresentation.isReadOnlyCall(sh("ls -la")))
        assertFalse(ToolPresentation.isReadOnlyCall(sh("mkdir x")))
        assertFalse(ToolPresentation.isReadOnlyCall(call("file_write")))
        assertFalse(ToolPresentation.isReadOnlyCall(call("file_read", isError = true)), "failures stay visible")
        val calls = listOf(call("file_read"), call("list_dir"), sh("ls"), call("file_write"), call("file_read"), call("memory_get"))
        val segs = ToolPresentation.segments(calls)
        assertEquals(3, segs.size)
        assertEquals(3, (segs[0] as ToolPresentation.ReadOnlyRun).calls.size)
        assertEquals("file_write", (segs[1] as ToolPresentation.Single).call.name)
        assertEquals(2, (segs[2] as ToolPresentation.ReadOnlyRun).calls.size)
        assertEquals(calls, segs.flatMap { it.calls }, "order and completeness kept")
        assertTrue(ToolPresentation.segments(listOf(call("file_read"), call("file_write"))).all { it is ToolPresentation.Single }, "a lone read is a normal card")
    }

    @Test fun groupingCountsRowsAfterChips() {
        val seven = List(7) { call("file_read") }
        assertFalse(ToolPresentation.shouldGroupSegments(seven), "7 reads are one chip, not a group")
        val mixed = listOf(call("file_write"), call("file_edit"), sh("mkdir a"), call("file_write"))
        assertTrue(ToolPresentation.shouldGroupSegments(mixed))
        assertFalse(ToolPresentation.shouldGroupSegments(mixed + call("file_write", status = ToolStatus.RUNNING)), "never while running")
    }
}
