// Memory retrieval: Jieba/BreakIterator tokenisation + BM25 ranking with recency boost.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.desktop.tools.MemorySearch
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.MemoryTokenizer
import com.openminis.desktop.tools.ToolRegistry
import com.openminis.desktop.core.AppSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.test.*

class MemorySearchTest {
    private val dir = TestSupport.tmp("om-memory")
    private val today = LocalDate.of(2026, 10, 8)

    private fun day(d: LocalDate, vararg entries: String) =
        File(dir, "$d.md").writeText(entries.joinToString("") { "### 10:00\n$it\n\n" })

    @Test fun chineseIsSegmentedIntoWords() {
        val t = MemoryTokenizer.tokenize("我喜欢喝手冲咖啡，不加糖")
        assertTrue("咖啡" in t, t.toString())
        assertTrue("喜欢" in t, t.toString())
        assertFalse("的" in MemoryTokenizer.tokenize("我的电脑"), "stop words dropped")
    }

    @Test fun englishIsLowercasedAndStemmed() {
        val t = MemoryTokenizer.tokenize("The Config FILES are in C:\\Users")
        assertTrue("config" in t && "file" in t, t.toString())
        assertFalse("the" in t)
    }

    @Test fun mixedScriptTokens() {
        val t = MemoryTokenizer.tokenize("用户的GitHub账号是octocat")
        assertTrue("github" in t && "octocat" in t && "账号" in t, t.toString())
    }

    @Test fun bm25RanksTheRelevantChineseEntryFirst() {
        day(today.minusDays(3),
            "## 饮食偏好\n用户喜欢手冲咖啡，不加糖不加奶。",
            "## 项目\nOpenMinis 桌面端移植，使用 Kotlin 和 Compose。")
        day(today.minusDays(1), "## 天气\n今天上海下雨。")
        val hits = MemorySearch(dir).search("咖啡 偏好", includeGlobal = true, today = today)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().chunk.text.contains("手冲咖啡"), hits.toString())
        assertTrue(hits.none { it.chunk.text.contains("下雨") }, "unrelated entries are not returned")
    }

    @Test fun notEveryKeywordHasToMatch() {
        day(today, "## Tools\nUser prefers PowerShell over cmd for scripts.")
        // Old behaviour required ALL keywords; "bash" never appears.
        val hits = MemorySearch(dir).search("powershell bash scripts", includeGlobal = false, today = today)
        assertEquals(1, hits.size)
    }

    @Test fun recencyBreaksTiesTowardNewerLogs() {
        day(today.minusDays(200), "## Car\nThe user's car is a blue Toyota.")
        day(today.minusDays(2), "## Car\nThe user's car is a blue Toyota.")
        val hits = MemorySearch(dir).search("car toyota", includeGlobal = false, today = today)
        assertEquals(2, hits.size)
        assertEquals("${today.minusDays(2)}.md", hits.first().chunk.file)
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test fun rarerTermsWeighMore() {
        day(today, "## A\nmeeting notes meeting notes meeting", "## B\nmeeting about the kubernetes migration")
        val hits = MemorySearch(dir).search("kubernetes meeting", includeGlobal = false, today = today)
        assertTrue(hits.first().chunk.text.contains("kubernetes"))
    }

    @Test fun globalOnlyInScopeAll() {
        File(dir, "GLOBAL.md").writeText("# GLOBAL\n\n## 称呼\n请称呼用户为“老王”。\n")
        day(today, "## misc\nnothing")
        val s = MemorySearch(dir)
        assertTrue(s.search("称呼 老王", includeGlobal = true, today = today).first().chunk.file == "GLOBAL.md")
        assertTrue(s.search("称呼 老王", includeGlobal = false, today = today).isEmpty())
    }

    @Test fun memoryGetToolReturnsRankedOutput() = runBlocking {
        val store = MemoryStore(dir)
        store.write("## 偏好\n用户喜欢深色主题")
        store.write("## Shell\nUse PowerShell 7")
        val reg = ToolRegistry({ AppSettings(workspaceDir = TestSupport.tmp().absolutePath) }, store) { true }
        val out = reg.execute("memory_get", JSONObject().put("scope", "all").put("keywords", "深色主题")).content
        assertTrue(out.startsWith("Top 1 memory match"), out)
        assertTrue(out.contains("深色主题"))
        val dump = reg.execute("memory_get", JSONObject().put("scope", "daily")).content
        assertTrue(dump.contains("PowerShell 7") && dump.contains("深色主题"), "empty keywords still dumps the logs")
    }

    @Test fun longLogsAreChunked() {
        File(dir, "$today.md").writeText("### 09:00\n" + "filler line\n".repeat(400) + "needle-word here\n")
        val chunks = MemorySearch(dir).chunks(includeGlobal = false)
        assertTrue(chunks.size > 1)
        val hit = MemorySearch(dir).search("needle-word", includeGlobal = false, today = today).first()
        assertTrue(hit.chunk.text.contains("needle-word") && hit.chunk.text.length <= 2100)
    }
}
