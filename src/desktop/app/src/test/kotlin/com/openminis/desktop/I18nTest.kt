package com.openminis.desktop

import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.desktop.i18n.AppLanguage
import com.openminis.desktop.i18n.I18n
import com.openminis.desktop.net.HttpProtocolMode
import com.openminis.desktop.net.ProxyMode
import com.openminis.desktop.ui.RUNS_ONLY_WHILE_OPEN_NOTE
import com.openminis.desktop.ui.displayTitle
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    private val langs = listOf("en", "zh-CN", "zh-TW")
    private val placeholder = Regex("""\{\d+\}""")

    private fun srcRoot(): File = listOf(File("src/main/kotlin"), File("app/src/main/kotlin"), File("src/desktop/app/src/main/kotlin"))
        .first { it.isDirectory }

    @Test fun allTablesLoadAndHaveTheSameKeys() {
        val en = I18n.table("en")
        assertTrue(en.size > 300, "en table loaded (${en.size})")
        for (l in langs) {
            val t = I18n.table(l)
            assertEquals(en.keys.sorted(), t.keys.sorted(), "keys of $l")
            t.forEach { (k, v) -> assertTrue(v.isNotBlank(), "$l:$k is blank") }
        }
    }

    @Test fun placeholdersMatchAcrossLanguages() {
        val en = I18n.table("en")
        for (l in listOf("zh-CN", "zh-TW")) {
            val t = I18n.table(l)
            en.forEach { (k, v) ->
                assertEquals(placeholder.findAll(v).map { it.value }.toSortedSet(), placeholder.findAll(t.getValue(k)).map { it.value }.toSortedSet(), "placeholders of $l:$k")
            }
        }
    }

    @Test fun chineseTablesAreActuallyTranslated() {
        val en = I18n.table("en")
        // Strings that are the same in every language (symbols, product names, key hints).
        val same = setOf("msg.tokens", "approval.remember_button", "skills.version", "mcp.command", "settings.edit_global",
            "settings.shell", "net.http.HTTP1_ONLY", "mcp.tools", "quote", "list_sep", "chatmcp.badge", "appearance.preview_text", "settings.page.mcp", "time.date")
        for (l in listOf("zh-CN", "zh-TW")) {
            val t = I18n.table(l)
            val untranslated = en.keys.filter { it !in same && t[it] == en[it] }
            assertTrue(untranslated.isEmpty(), "$l untranslated: $untranslated")
        }
        // 简体 and 繁體 are separate translations, not one copied into the other.
        val zh = I18n.table("zh-CN"); val tw = I18n.table("zh-TW")
        assertTrue(zh.count { (k, v) -> tw[k] != v } > 200)
    }

    @Test fun systemLanguageMapping() {
        assertEquals("zh-CN", I18n.fromLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-CN", I18n.fromLocale(Locale.forLanguageTag("zh-SG")))
        assertEquals("zh-CN", I18n.fromLocale(Locale.forLanguageTag("zh")))
        assertEquals("zh-TW", I18n.fromLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-TW", I18n.fromLocale(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-TW", I18n.fromLocale(Locale.forLanguageTag("zh-MO")))
        assertEquals("zh-TW", I18n.fromLocale(Locale.forLanguageTag("zh-Hant-CN")))
        assertEquals("en", I18n.fromLocale(Locale.US))
        assertEquals("en", I18n.fromLocale(Locale.JAPAN))
        assertEquals("en", I18n.resolve("en", Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-TW", I18n.resolve("zh-TW", Locale.US))
        assertEquals("zh-CN", I18n.resolve("system", Locale.CHINA))
        assertEquals("zh-CN", I18n.resolve("garbage", Locale.CHINA), "unknown setting = follow system")
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of(null))
    }

    @Test fun formatAndFallback() {
        assertEquals("a 1 b x {2} {y}", I18n.format("a {0} b {1} {2} {y}", arrayOf(1, "x")))
        assertEquals("it's {0}", I18n.format("it's {0}", arrayOf()), "no MessageFormat quoting")
        assertEquals("删除对话？", I18n.tr("chat.delete.title", lang = "zh-CN"))
        assertEquals("刪除對話？", I18n.tr("chat.delete.title", lang = "zh-TW"))
        assertEquals("“x” will be permanently deleted.", I18n.tr("chat.delete.body", "x", lang = "en"))
        assertEquals("no.such.key", I18n.tr("no.such.key", lang = "zh-CN"))
        assertEquals("fb", I18n.trOr("no.such.key", "fb", lang = "zh-CN"))
        assertEquals("Delete", I18n.tr("common.delete", lang = "fr"), "unknown table falls back to English")
    }

    @Test fun scheduledNoticeKeepsTheAppNotRunningSentence() {
        assertEquals(RUNS_ONLY_WHILE_OPEN_NOTE, I18n.tr("sched.runs_only_open", lang = "en"))
        assertTrue(I18n.tr("sched.runs_only_open", lang = "zh-CN").contains("应用不运行时不会执行"))
        assertTrue(I18n.tr("sched.runs_only_open", lang = "zh-TW").contains("不會執行"))
    }

    @Test fun everyKeyUsedInTheSourcesExists() {
        val en = I18n.table("en")
        val used = Regex("""\btr\("([A-Za-z0-9_.]+)"""")
        val missing = mutableListOf<String>()
        srcRoot().walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            used.findAll(f.readText()).forEach { m -> if (!m.groupValues[1].endsWith(".") && m.groupValues[1] !in en) missing += "${f.name}: ${m.groupValues[1]}" }
        }
        assertTrue(missing.isEmpty(), "missing keys: $missing")
        // Keys built from enum / state names.
        val dynamic = ProxyMode.entries.map { "net.mode.${it.name}" } + HttpProtocolMode.entries.map { "net.http.${it.name}" } +
            ScheduledRepeatMode.entries.map { "sched.repeat.${it.name}" } + listOf("TIME", "AFTER", "INTERVAL", "AFTER_TASK").map { "sched.choice.$it" } +
            listOf("OFF", "LOW", "MEDIUM", "HIGH").map { "thinking.$it" } +
            listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY").map { "day.$it" } + "shell.WSL"
        assertTrue(dynamic.filter { it !in en }.isEmpty(), "missing dynamic keys: ${dynamic.filter { it !in en }}")
    }

    @Test fun noHardCodedEnglishLeftInTheUi() {
        val root = srcRoot().resolve("com/openminis/desktop")
        val files = root.resolve("ui").listFiles()!!.filter { it.extension == "kt" } + root.resolve("Main.kt")
        // A visible-text call whose first argument is a string literal with an English word in it.
        val visible = Regex("""(?:\bText|\bHint|\bToggle|\bNumberField|\bSection|\bItem|\bMenu|\bModelPicker)\(\s*"([^"]*)"""")
        val namedText = Regex("""\b(?:title|placeholder|dialogTitle|contentDescription|prefix|toast)\s*=\s*"([^"]*)"""")
        val iconDesc = Regex("""\bIcon\([^,()]+(?:\([^)]*\))?,\s*"([^"]*)"""")
        val word = Regex("""[A-Za-z]{3,}""")
        val allowed = setOf("OpenMinis")
        val hits = mutableListOf<String>()
        for (f in files) f.readLines().forEachIndexed { i, line ->
            if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
            for (re in listOf(visible, namedText, iconDesc)) re.findAll(line).forEach { m ->
                if (m.groupValues[1].startsWith("\${")) return@forEach  // "${if (open) "▾" …}" — the regex cut an interpolation
                val lit = m.groupValues[1].replace(Regex("""\$\{[^}]*\}|\$[A-Za-z_]+"""), "")
                if (word.containsMatchIn(lit) && lit.trim() !in allowed) hits += "${f.name}:${i + 1}: ${m.value}"
            }
        }
        assertTrue(hits.isEmpty(), "hard-coded UI text:\n" + hits.joinToString("\n"))
    }

    @Test fun untitledConversationTitleIsLocalizedAtDisplayTime() {
        val prev = I18n.active
        try {
            val c = com.openminis.desktop.core.Conversation()
            assertEquals(com.openminis.desktop.core.DEFAULT_CONVERSATION_TITLE, c.title)  // stored value stays English
            I18n.apply("zh-CN"); assertEquals("新对话", c.displayTitle())
            I18n.apply("zh-TW"); assertEquals("新對話", c.displayTitle())
            I18n.apply("en"); assertEquals("New chat", c.displayTitle())
            I18n.apply("zh-CN"); assertEquals("周报", c.copy(title = "周报").displayTitle())
        } finally { I18n.apply(prev) }
    }

    @Test fun conversationTitlesReachTheUiOnlyThroughDisplayTitle() {
        // Data-model defaults have no i18n key, so the hard-coded-text scan cannot see them; check the paths instead.
        val root = srcRoot().resolve("com/openminis/desktop")
        val files = root.resolve("ui").listFiles()!!.filter { it.extension == "kt" && it.name != "AppState.kt" } + root.resolve("Main.kt")
        val raw = Regex("""\b(?:c|conv|conversation|chat|it)\??\.title\b""")
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (raw.containsMatchIn(l) && !l.trimStart().startsWith("//")) "${f.name}:${i + 1}: ${l.trim()}" else null } }
        assertTrue(hits.isEmpty(), "conversation title shown without displayTitle():\n" + hits.joinToString("\n"))
        val literal = root.walkTopDown().filter { it.extension == "kt" && it.name != "Conversation.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if ("\"New chat\"" in l && !l.trimStart().startsWith("//")) "${f.name}:${i + 1}" else null } }.toList()
        assertTrue(literal.isEmpty(), "\"New chat\" literal outside Conversation.kt: $literal")
    }

    @Test fun oldSettingsFilesFollowTheSystemLanguage() {
        val dir = kotlin.io.path.createTempDirectory("i18n").toFile()
        val f = File(dir, "settings.json").apply { writeText("""{"activeProvider":"ANTHROPIC","darkTheme":true}""") }
        val s = com.openminis.desktop.core.SettingsStore(f).load()
        assertEquals("system", s.language)
        com.openminis.desktop.core.SettingsStore(f).save(s.copy(language = "zh-TW"))
        assertEquals("zh-TW", com.openminis.desktop.core.SettingsStore(f).load().language)
        dir.deleteRecursively()
    }
}
