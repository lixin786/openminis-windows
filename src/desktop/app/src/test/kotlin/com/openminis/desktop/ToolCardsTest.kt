package com.openminis.desktop

import com.openminis.desktop.core.Attachment
import com.openminis.desktop.core.AttachmentKind
import com.openminis.desktop.core.ChatMessage
import com.openminis.desktop.core.ChatRole
import com.openminis.desktop.core.CompactMarker
import com.openminis.desktop.core.Conversation
import com.openminis.desktop.core.ToolCall
import com.openminis.desktop.core.ToolStatus
import com.openminis.desktop.ui.AppState
import com.openminis.desktop.ui.ToolPresentation
import com.openminis.desktop.ui.ToolPresentation.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCardsTest {
    private fun call(name: String = "shell", status: ToolStatus = ToolStatus.DONE, result: String? = "ok", isError: Boolean = false) =
        ToolCall(id = java.util.UUID.randomUUID().toString(), name = name, result = result, status = status, isError = isError)

    @Test fun kindsAndMcpDisplayNames() {
        assertEquals(Kind.SHELL, ToolPresentation.kind("shell"))
        assertEquals(Kind.FILE, ToolPresentation.kind("file_edit")); assertEquals(Kind.FILE, ToolPresentation.kind("list_dir"))
        assertEquals(Kind.MEMORY, ToolPresentation.kind("memory_get"))
        assertEquals(Kind.MCP, ToolPresentation.kind("mcp__github__create_issue"))
        assertEquals(Kind.SUBAGENT, ToolPresentation.kind(com.openminis.desktop.subagents.SubAgentProtocol.TOOL_NAME))
        assertEquals(Kind.OTHER, ToolPresentation.kind("something_else"))
        assertEquals("github · create_issue", ToolPresentation.displayName("mcp__github__create_issue"))
        assertEquals("my_srv · read_file", ToolPresentation.displayName("mcp__my_srv__read_file"))
        assertEquals("shell", ToolPresentation.displayName("shell"))
        assertEquals("weird", ToolPresentation.displayName("mcp__weird"))
    }

    @Test fun durations() {
        assertEquals("850 ms", ToolPresentation.duration(850))
        assertEquals("1.2 s", ToolPresentation.duration(1234))
        assertEquals("1 m 5 s", ToolPresentation.duration(65_000))
    }

    @Test fun failuresAndErrorLine() {
        assertEquals("boom", ToolPresentation.errorLine(call(status = ToolStatus.FAILED, result = "\n  boom \nstack")))
        assertEquals("denied", ToolPresentation.errorLine(call(status = ToolStatus.DENIED, result = "denied")))
        assertEquals("exit 1", ToolPresentation.errorLine(call(status = ToolStatus.DONE, isError = true, result = "exit 1")))
        assertNull(ToolPresentation.errorLine(call()))
    }

    @Test fun groupingOnlyForFinishedBatchesOfFourOrMore() {
        assertFalse(ToolPresentation.shouldGroup(List(3) { call() }))
        assertTrue(ToolPresentation.shouldGroup(List(4) { call() }))
        assertFalse(ToolPresentation.shouldGroup(List(4) { call() } + call(status = ToolStatus.RUNNING)))
        assertTrue(ToolPresentation.shouldGroup(List(3) { call() } + call(status = ToolStatus.FAILED)))
    }

    @Test fun longResultsAreClippedButTotalKept() {
        val big = "x".repeat(ToolPresentation.RESULT_LIMIT + 5)
        val c = ToolPresentation.clip(big)
        assertTrue(c.truncated); assertEquals(ToolPresentation.RESULT_LIMIT, c.text.length); assertEquals(big.length, c.total)
        assertFalse(ToolPresentation.clip("short").truncated)
    }

    // ---- edit & resend

    private fun user(t: String, vararg atts: Attachment) = ChatMessage(role = ChatRole.USER, text = t, attachments = atts.toList())
    private fun bot(t: String) = ChatMessage(role = ChatRole.ASSISTANT, rounds = listOf(com.openminis.desktop.core.Round(text = t)))

    @Test fun editDropsLaterMessagesAndMarkers() {
        val u1 = user("first"); val b1 = bot("a"); val u2 = user("second"); val b2 = bot("b")
        val c = Conversation(title = "first", messages = listOf(u1, b1, u2, b2), compactMarkers = listOf(
            CompactMarker(lastCompactedMessageId = b1.id, summary = "s1", compactedCount = 2), CompactMarker(lastCompactedMessageId = "${b2.id}#0", summary = "s2", compactedCount = 4)))
        val e = AppState.editedConversation(c, u2.id, "  second, edited ")!!
        assertEquals(listOf(u1.id, b1.id), e.messages.take(2).map { it.id })
        assertEquals(3, e.messages.size)
        assertEquals("second, edited", e.messages.last().text)
        assertNotEquals(u2.id, e.messages.last().id)
        assertEquals(listOf(b1.id), e.compactMarkers.map { it.lastCompactedMessageId })
        assertEquals("first", e.title)
    }

    @Test fun editingTheFirstMessageRetitlesUnlessLocked() {
        val u = user("old question")
        val c = Conversation(title = "old question", messages = listOf(u, bot("x")))
        assertEquals("new question", AppState.editedConversation(c, u.id, "new question")!!.title)
        assertEquals("Mine", AppState.editedConversation(c.copy(title = "Mine", titleLocked = true), u.id, "new question")!!.title)
    }

    @Test fun editRejectsEmptyTextAndNonUserIds() {
        val a = Attachment(fileName = "a.png", mimeType = "image/png", kind = AttachmentKind.IMAGE, relativePath = "x/a.png")
        val u = user("hi"); val withAtt = user("look", a); val b = bot("x")
        val c = Conversation(messages = listOf(u, b, withAtt))
        assertNull(AppState.editedConversation(c, u.id, "   "))
        assertNull(AppState.editedConversation(c, b.id, "text"))
        assertNull(AppState.editedConversation(c, "missing", "text"))
        val kept = AppState.editedConversation(c, withAtt.id, "")!!
        assertEquals(listOf(a), kept.messages.last().attachments)
    }
}
