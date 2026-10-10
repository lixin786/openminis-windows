// batch11.1: duplicate conversation ids on load; "outside the workspace" only for paths really outside.
package com.openminis.desktop

import com.openminis.desktop.core.Conversation
import com.openminis.desktop.core.ConversationStore
import com.openminis.desktop.security.ApprovalRisk
import com.openminis.desktop.security.Risk
import java.io.File
import kotlin.test.*

class Batch11FixesTest {
    @Test fun duplicateIdsKeepNewestCopyOnly() {
        val dir = TestSupport.tmp("om-dup")
        val store = ConversationStore(dir)
        store.save(Conversation(id = "abc", title = "old", updatedAt = 1000))
        // a backup restore / sync conflict: same id, different file name
        File(dir, "abc.json").copyTo(File(dir, "abc (1).json"))
        store.save(Conversation(id = "abc", title = "new", updatedAt = 2000))
        store.save(Conversation(id = "other", title = "x", updatedAt = 1500))
        val all = store.loadAll()
        assertEquals(listOf("abc", "other"), all.map { it.id })
        assertEquals("new", all.first().title)
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids unique → LazyColumn keys unique")
        assertTrue(File(dir, "abc (1).json").exists(), "the skipped copy is not deleted")
    }

    @Test fun absolutePathInsideWorkspaceIsNotOutside() {
        val ws = TestSupport.tmp("om-ws").canonicalFile
        File(ws, "build").mkdirs()
        val inside = ApprovalRisk.shell("rm -rf ${ws.absolutePath}/build", ws)
        assertEquals(Risk.HIGH, inside.risk, "still a delete")
        assertFalse("risk.outside" in inside.notes, inside.notes.toString())
        assertFalse("risk.outside" in ApprovalRisk.shell("rm -rf ./sub/../build", ws).notes)
        // really outside, or not provably a plain path → still flagged
        val outside = TestSupport.tmp("om-out").canonicalFile
        assertTrue("risk.outside" in ApprovalRisk.shell("rm -rf ${outside.absolutePath}", ws).notes)
        assertTrue("risk.outside" in ApprovalRisk.shell("rm -rf ../elsewhere", ws).notes)
        assertTrue("risk.outside" in ApprovalRisk.shell("rm -rf ~/x", ws).notes)
        assertTrue("risk.outside" in ApprovalRisk.shell("Remove-Item \$env:USERPROFILE\\x", ws).notes)
        assertTrue("risk.outside" in ApprovalRisk.shell("Remove-Item HKLM:\\Software\\x", ws).notes)
        assertTrue("risk.outside" in ApprovalRisk.shell("rm -rf ${ws.absolutePath}/build", null).notes, "no workspace known → safe side")
    }

    @Test fun symlinkInsideWorkspacePointingOutIsOutside() {
        val ws = TestSupport.tmp("om-ws2").canonicalFile
        val out = TestSupport.tmp("om-out2").canonicalFile
        val link = File(ws, "link")
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), out.toPath()) }.onFailure { return }
        assertTrue("risk.outside" in ApprovalRisk.shell("rm -rf ${link.absolutePath}/x", ws).notes)
    }
}
