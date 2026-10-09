package com.openminis.desktop.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.InputStreamReader
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * UI language (Settings › Language). The value stored in settings.json is one of [code]s; [SYSTEM]
 * follows the OS display language: any Chinese in Taiwan / Hong Kong / Macau or written in Hant → 繁體,
 * any other Chinese → 简体, everything else → English.
 */
enum class AppLanguage(val code: String, val nativeName: String) {
    SYSTEM("system", ""), EN("en", "English"), ZH_CN("zh-CN", "简体中文"), ZH_TW("zh-TW", "繁體中文");

    companion object {
        fun of(code: String?): AppLanguage = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * String tables in `resources/i18n/<code>.properties` (UTF-8). Keys missing in a translation fall back
 * to English, a key missing everywhere shows the key itself (I18nTest keeps all three tables in sync).
 * Placeholders are positional: {0}, {1} … (no MessageFormat quoting rules — an apostrophe is just text).
 *
 * Only the UI is translated. What the model sees (tool descriptions, tool results, system prompt) stays
 * English. [active] is Compose state, so every composable that called [tr] redraws when it changes.
 */
object I18n {
    const val FALLBACK = "en"
    private val tables = ConcurrentHashMap<String, Map<String, String>>()

    /** Resolved table code ("en", "zh-CN", "zh-TW"). Starts as English until [apply] runs (tests stay English). */
    var active: String by mutableStateOf(FALLBACK)
        private set

    fun resolve(setting: String?, system: Locale = Locale.getDefault(Locale.Category.DISPLAY)): String =
        when (AppLanguage.of(setting)) {
            AppLanguage.EN -> "en"
            AppLanguage.ZH_CN -> "zh-CN"
            AppLanguage.ZH_TW -> "zh-TW"
            AppLanguage.SYSTEM -> fromLocale(system)
        }

    fun fromLocale(l: Locale): String {
        if (!l.language.equals("zh", ignoreCase = true)) return "en"
        val traditional = l.script.equals("Hant", ignoreCase = true) || l.country.uppercase() in setOf("TW", "HK", "MO")
        return if (traditional) "zh-TW" else "zh-CN"
    }

    fun apply(setting: String?) {
        val code = resolve(setting)
        active = code
        // Swing dialogs (JFileChooser buttons) use the JDK's own translations for this locale.
        runCatching { javax.swing.JComponent.setDefaultLocale(Locale.forLanguageTag(code)) }
    }

    fun table(code: String): Map<String, String> = tables.getOrPut(code) { load(code) }

    private fun load(code: String): Map<String, String> {
        val stream = I18n::class.java.getResourceAsStream("/i18n/$code.properties") ?: return emptyMap()
        val p = Properties()
        InputStreamReader(stream, Charsets.UTF_8).use { p.load(it) }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }
    }

    fun tr(key: String, vararg args: Any?, lang: String = active): String {
        val raw = table(lang)[key] ?: table(FALLBACK)[key] ?: key
        return format(raw, args)
    }

    /** Translation of a key built from data (an enum or state name); [fallback] when no table has it. */
    fun trOr(key: String, fallback: String, lang: String = active): String =
        table(lang)[key] ?: table(FALLBACK)[key] ?: fallback

    internal fun format(raw: String, args: Array<out Any?>): String {
        if (args.isEmpty() || '{' !in raw) return raw
        val sb = StringBuilder(raw.length + 16)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '{') {
                val close = raw.indexOf('}', i + 1)
                val idx = if (close > i) raw.substring(i + 1, close).toIntOrNull() else null
                if (idx != null && idx in args.indices) { sb.append(args[idx].toString()); i = close + 1; continue }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}

/** Translated UI text for [key] in the current language, with {0}, {1} … replaced by [args]. */
fun tr(key: String, vararg args: Any?): String = I18n.tr(key, *args)
