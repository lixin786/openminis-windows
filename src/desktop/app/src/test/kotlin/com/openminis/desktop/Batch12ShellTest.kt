// batch12 (UI-REFRESH §3): sidebar date groups / relative times, provider badge, Markdown export,
// bundled assets present, settings pages complete, icons generated.
package com.openminis.desktop

import com.openminis.desktop.core.*
import com.openminis.desktop.ui.SettingsGroup
import com.openminis.desktop.ui.SettingsPage
import com.openminis.desktop.ui.ShellModel
import com.openminis.desktop.ui.ShellModel.DateGroup
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

class Batch12ShellTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 10, 21, 0)

    @Test fun dateGroups() {
        assertEquals(DateGroup.TODAY, ShellModel.group(at(2026, 10, 10, 0, 5), now, zone))
        assertEquals(DateGroup.TODAY, ShellModel.group(now + 60_000, now, zone), "clock skew → today")
        assertEquals(DateGroup.YESTERDAY, ShellModel.group(at(2026, 10, 9, 23, 59), now, zone))
        assertEquals(DateGroup.WEEK, ShellModel.group(at(2026, 10, 4), now, zone))
        assertEquals(DateGroup.EARLIER, ShellModel.group(at(2026, 10, 3), now, zone))
    }

    @Test fun relativeTimes() {
        assertEquals("time.just_now", ShellModel.relative(now - 20_000, now, zone).key)
        assertEquals(ShellModel.Rel("time.minutes_ago", "3"), ShellModel.relative(now - 3 * 60_000, now, zone))
        assertEquals(ShellModel.Rel("time.hours_ago", "5"), ShellModel.relative(at(2026, 10, 10, 15, 30), now, zone))
        assertEquals("time.yesterday", ShellModel.relative(at(2026, 10, 9, 23, 0), now, zone).key)
        assertEquals(ShellModel.Rel("time.days_ago", "4"), ShellModel.relative(at(2026, 10, 6), now, zone))
        assertEquals(ShellModel.Rel("time.date", "09-01"), ShellModel.relative(at(2026, 9, 1), now, zone))
        assertEquals(ShellModel.Rel("time.date", "2025-12-31"), ShellModel.relative(at(2025, 12, 31), now, zone))
    }

    @Test fun sectionsPinnedFirstThenDates() {
        val list = listOf(
            Conversation(id = "a", updatedAt = now - 1000),
            Conversation(id = "p", updatedAt = at(2026, 1, 1), pinned = true),
            Conversation(id = "y", updatedAt = at(2026, 10, 9)),
            Conversation(id = "old", updatedAt = at(2025, 1, 1)),
        )
        val secs = ShellModel.sections(list, now, zone)
        assertEquals(listOf("sidebar.pinned", "sidebar.group.today", "sidebar.group.yesterday", "sidebar.group.earlier"), secs.map { it.headerKey })
        assertEquals(listOf("p"), secs[0].conversations.map { it.id })
        assertEquals(list.size, secs.sumOf { it.conversations.size })
        assertEquals(secs.flatMap { it.conversations }.map { it.id }.toSet().size, list.size, "no duplicate keys across sections")
    }

    @Test fun providerBadge() {
        assertEquals("DeepSeek", ShellModel.providerBadge(ProviderKind.DEEPSEEK, "https://api.deepseek.com/v1"))
        assertEquals("api.siliconflow.cn", ShellModel.providerBadge(ProviderKind.OPENAI_COMPATIBLE, "https://api.siliconflow.cn/v1"))
        assertEquals("localhost:11434", ShellModel.providerBadge(ProviderKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1"))
        assertEquals("OpenAI-compatible", ShellModel.providerBadge(ProviderKind.OPENAI_COMPATIBLE, "not a url"))
    }

    @Test fun markdownExport() {
        val c = Conversation(id = "e", messages = listOf(
            ChatMessage(role = ChatRole.USER, text = "列出文件"),
            ChatMessage(role = ChatRole.ASSISTANT, rounds = listOf(Round(text = "好的", toolCalls = listOf(
                ToolCall(id = "1", name = "shell", argsJson = """{"command":"dir"}""", status = ToolStatus.DONE, result = "x"))))),
        ))
        val md = ShellModel.markdown(c, "T", "你", "助手")
        assertTrue(md.startsWith("# T\n"))
        assertTrue("## 你\n\n列出文件" in md); assertTrue("## 助手\n\n好的" in md)
        assertTrue("`shell` · dir · success" in md, md)
    }

    @Test fun settingsPagesCoverSpecSections() {
        val required = setOf(SettingsPage.MODEL, SettingsPage.APPEARANCE, SettingsPage.TOOLS, SettingsPage.MCP, SettingsPage.SKILLS,
            SettingsPage.SUBAGENTS, SettingsPage.SCHEDULED, SettingsPage.NETWORK, SettingsPage.ABOUT, SettingsPage.ADVANCED)
        assertTrue(SettingsPage.entries.containsAll(required))
        SettingsGroup.entries.forEach { assertTrue(SettingsPage.of(it).isNotEmpty(), it.name) }
    }

    @Test fun bundledFontsAndLicensesShip() {
        listOf("Inter-Regular", "Inter-Medium", "Inter-SemiBold", "Inter-Bold", "JetBrainsMono-Regular", "JetBrainsMono-Bold").forEach {
            val r = javaClass.classLoader.getResource("fonts/$it.ttf")
            assertNotNull(r, it)
            val head = r.openStream().use { s -> s.readNBytes(4) }
            assertTrue(head.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(head) == "true", "$it is a TrueType file")
        }
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "third_party").isDirectory }
        listOf("lucide/LICENSE", "inter/OFL.txt", "jetbrains-mono/OFL.txt").forEach { assertTrue(java.io.File(root, "third_party/$it").isFile, it) }
    }

    @Test fun noMaterialIconsLeft() {
        val src = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/main/kotlin") }.first { it.isDirectory }
        val offenders = src.walkTopDown().filter { it.extension == "kt" }.filter { "Icons.Default." in it.readText() || "material.icons" in it.readText() }.toList()
        assertTrue(offenders.isEmpty(), offenders.toString())
        assertNotNull(com.openminis.desktop.ui.icons.Lucide.Trash)
    }

    // batch12.1: a settings detail page must not repeat its own headline as a section title (any locale).
    @Test fun noSectionRepeatsPageTitle() {
        val src = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/main/kotlin/com/openminis/desktop/ui") }.first { it.isDirectory }
        val keys = src.walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""Section\(tr\("([^"]+)"\)\)""").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        val res = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "src/main/resources/i18n") }.first { it.isDirectory }
        listOf("en", "zh-CN", "zh-TW").forEach { loc ->
            val props = java.util.Properties().apply { java.io.File(res, "$loc.properties").reader(Charsets.UTF_8).use { load(it) } }
            val titles = SettingsPage.entries.mapNotNull { props.getProperty(it.key) }.toSet()
            val dupes = keys.filter { props.getProperty(it) in titles }
            assertTrue(dupes.isEmpty(), "$loc: $dupes")
        }
    }
}
