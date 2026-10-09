package com.openminis.desktop

import com.openminis.desktop.core.ChatMessage
import com.openminis.desktop.core.ChatRole
import com.openminis.desktop.core.Conversation
import com.openminis.desktop.core.ConversationSearch
import com.openminis.desktop.core.ConversationStore
import com.openminis.desktop.core.DEFAULT_CONVERSATION_TITLE
import com.openminis.desktop.ui.AppState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationManagementTest {
    private fun chat(id: String, title: String = DEFAULT_CONVERSATION_TITLE, pinned: Boolean = false, vararg texts: String) =
        Conversation(id = id, title = title, pinned = pinned,
            messages = texts.mapIndexed { i, t -> ChatMessage(role = if (i % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT, text = t) })

    // ---- rename

    @Test fun cleanTitleTrimsJoinsLinesAndCaps() {
        assertEquals("周报 草稿", ConversationSearch.cleanTitle("  周报\n草稿 \t"))
        assertNull(ConversationSearch.cleanTitle("   \n "))
        assertEquals(ConversationSearch.MAX_TITLE, ConversationSearch.cleanTitle("x".repeat(500))!!.length)
    }

    @Test fun renamedDraftKeepsItsTitleOnTheFirstMessage() {
        val draft = Conversation()
        assertEquals("hello there", AppState.titleOnSend(draft, "hello there"))
        val renamed = draft.copy(title = "项目计划", titleLocked = true)
        assertEquals("项目计划", AppState.titleOnSend(renamed, "hello there"))
        val started = chat("a", "first", false, "first")
        assertEquals("first", AppState.titleOnSend(started, "second message"))
    }

    // ---- pin + persistence

    @Test fun pinnedChatsComeFirstAndKeepRecencyOrder() {
        val list = listOf(chat("1"), chat("2", pinned = true), chat("3"), chat("4", pinned = true))
        assertEquals(listOf("2", "4", "1", "3"), ConversationSearch.arrange(list).map { it.id })
    }

    @Test fun pinAndTitleLockSurviveTheStoreAndOldFilesLoad() {
        val dir = kotlin.io.path.createTempDirectory("conv").toFile()
        try {
            val store = ConversationStore(dir)
            store.save(chat("p", "置顶的", true, "hi").copy(titleLocked = true))
            File(dir, "old.json").writeText("""{"id":"old","title":"Legacy","createdAt":1,"updatedAt":2,"messages":[{"role":"USER","text":"x"}]}""")
            val byId = store.loadAll().associateBy { it.id }
            assertTrue(byId.getValue("p").pinned); assertTrue(byId.getValue("p").titleLocked)
            assertFalse(byId.getValue("old").pinned); assertFalse(byId.getValue("old").titleLocked)
            assertEquals("Legacy", byId.getValue("old").title)
        } finally { dir.deleteRecursively() }
    }

    // ---- search

    private val title: (Conversation) -> String = { if (it.title == DEFAULT_CONVERSATION_TITLE) "新对话" else it.title }

    @Test fun termsAreAndedAcrossTitleAndMessages() {
        val list = listOf(
            chat("a", "Gradle build", false, "jpackage fails on GBK"),
            chat("b", "Lunch", false, "gradle is slow"),
            chat("c", "Notes", false, "Gradle and jpackage tips"),
        )
        assertEquals(listOf("a", "c"), ConversationSearch.search(list, "  GRADLE   jpackage ", title).map { it.conversation.id })
        assertEquals(emptyList(), ConversationSearch.search(list, "gradle kotlin", title))
    }

    @Test fun titleMatchesRankFirstAndCarryNoSnippet() {
        val list = listOf(chat("body", "Misc", false, "talking about MCP servers"), chat("t", "MCP setup", false, "x"))
        val hits = ConversationSearch.search(list, "mcp", title)
        assertEquals(listOf("t", "body"), hits.map { it.conversation.id })
        assertNull(hits[0].snippet)
        assertEquals("talking about MCP servers", hits[1].snippet)
    }

    @Test fun localizedUntitledTitleIsSearchable() {
        val hits = ConversationSearch.search(listOf(chat("u", DEFAULT_CONVERSATION_TITLE)), "新对话", title)
        assertEquals(listOf("u"), hits.map { it.conversation.id })
    }

    @Test fun snippetCentersOnTheMatchAndCollapsesWhitespace() {
        val text = "a".repeat(200) + "\n\n  needle  here " + "b".repeat(200)
        val s = ConversationSearch.excerpt(text, listOf("needle"))
        assertTrue(s.startsWith("…") && s.endsWith("…"), s)
        assertTrue("needle here" in s, s)
        assertFalse('\n' in s)
    }

    @Test fun emptyQueryReturnsEverythingInSidebarOrder() {
        val list = listOf(chat("1"), chat("2", pinned = true))
        assertEquals(listOf("2", "1"), ConversationSearch.search(list, "   ", title).map { it.conversation.id })
    }

    @Test fun matchRangesAreCaseInsensitiveAndMerged() {
        assertEquals(listOf(0..4, 10..14), ConversationSearch.matchRanges("Hello and HELLO", listOf("hello")))
        assertEquals(listOf(0..5), ConversationSearch.matchRanges("abcdef", listOf("abcd", "cdef")))
        assertEquals(emptyList(), ConversationSearch.matchRanges("abc", emptyList()))
    }
}
