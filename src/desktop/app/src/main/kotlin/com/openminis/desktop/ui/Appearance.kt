// OpenMinis Desktop — appearance model: theme mode, accent palettes, text size steps, font choices. Pure, unit-tested.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

object Appearance {
    /** Settings.darkTheme is tri-state: null = follow Windows. */
    enum class ThemeMode(val dark: Boolean?) {
        SYSTEM(null), LIGHT(false), DARK(true);
        companion object { fun of(dark: Boolean?) = entries.first { it.dark == dark } }
    }

    /** Primary / secondary for light and dark schemes (ARGB). The first entry is the default. */
    enum class Accent(val id: String, val light: Long, val lightSecondary: Long, val dark: Long, val darkSecondary: Long) {
        INDIGO("indigo", 0xFF5B6CFF, 0xFF4A55C8, 0xFF9AA5FF, 0xFFB8C0FF),
        BLUE("blue", 0xFF1F6FEB, 0xFF1A5BC4, 0xFF79B0FF, 0xFFA8CBFF),
        TEAL("teal", 0xFF00897B, 0xFF00695C, 0xFF5FD4C4, 0xFF94E2D7),
        GREEN("green", 0xFF2E7D32, 0xFF1B5E20, 0xFF81C784, 0xFFA5D6A7),
        ORANGE("orange", 0xFFE8590C, 0xFFC2410C, 0xFFFFA36B, 0xFFFFC299),
        ROSE("rose", 0xFFD6336C, 0xFFAD1457, 0xFFFF8FB1, 0xFFFFB8CE),
        PURPLE("purple", 0xFF7B3FE4, 0xFF5E2DB3, 0xFFC2A3FF, 0xFFD8C4FF),
        GRAPHITE("graphite", 0xFF4B5563, 0xFF374151, 0xFFB8C0CC, 0xFFD1D6DE);
        companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: INDIGO }
    }

    /** Text size steps (Settings.fontScale); Ctrl+= / Ctrl+- move one step, Ctrl+0 resets. */
    val SCALES = listOf(0.85f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.6f)
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.6f

    /** A scale read from settings.json: NaN / out of range snaps back into [MIN_SCALE, MAX_SCALE]. */
    fun clampScale(f: Float): Float = if (f.isNaN()) 1f else f.coerceIn(MIN_SCALE, MAX_SCALE)

    /** One step up (+1), down (-1) or back to 100 % (0), starting from the nearest step. */
    fun step(current: Float, dir: Int): Float {
        if (dir == 0) return 1f
        val c = clampScale(current)
        return if (dir > 0) SCALES.firstOrNull { it > c + 0.001f } ?: SCALES.last()
        else SCALES.lastOrNull { it < c - 0.001f } ?: SCALES.first()
    }

    /** Code-font preview sample (command line + look-alike glyphs); not translated. */
    const val MONO_PREVIEW = "Get-ChildItem -Path . | Select-Object Name  # 0O 1lI {}"

    fun percent(f: Float): Int = Math.round(clampScale(f) * 100)

    /** Fonts that render Chinese and Latin well on Windows, best first (only installed ones are offered first). */
    val UI_RECOMMENDED = listOf("Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "DengXian", "Microsoft JhengHei UI",
        "Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC", "HarmonyOS Sans SC", "MiSans", "PingFang SC")
    val MONO_RECOMMENDED = listOf("Cascadia Mono", "Cascadia Code", "Consolas", "JetBrains Mono", "Sarasa Mono SC",
        "Sarasa Term SC", "Fira Code", "Source Code Pro", "Courier New")

    /**
     * Dropdown options: "" (built-in default) first, then installed recommended fonts, then the current choice
     * (kept even if no longer installed so the user sees what is set), then every other installed family A→Z.
     * Families starting with '@' (vertical CJK variants) are hidden.
     */
    /** Settings value meaning "the platform default font" ('@' names are never real choices: they are hidden). */
    const val SYSTEM_FONT = "@system"

    fun fontChoices(installed: Collection<String>, recommended: List<String>, current: String): List<String> {
        val inst = installed.filter { it.isNotBlank() && !it.startsWith("@") }
        val byLower = inst.associateBy { it.lowercase() }
        val rec = recommended.mapNotNull { byLower[it.lowercase()] }
        val out = LinkedHashSet<String>()
        out += ""
        out += SYSTEM_FONT
        out += rec
        if (current.isNotBlank() && current != SYSTEM_FONT) out += (byLower[current.lowercase()] ?: current)
        out += inst.filter { it !in out }.sortedBy { it.lowercase() }
        return out.toList()
    }
}
