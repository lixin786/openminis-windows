// batch10: appearance model — theme mode, accents, text size steps, font choices, settings persistence.
package com.openminis.desktop

import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.SettingsStore
import com.openminis.desktop.ui.Appearance
import java.io.File
import kotlin.test.*

class AppearanceTest {
    @Test fun themeModeIsTriState() {
        assertEquals(Appearance.ThemeMode.SYSTEM, Appearance.ThemeMode.of(null))
        assertEquals(Appearance.ThemeMode.LIGHT, Appearance.ThemeMode.of(false))
        assertEquals(Appearance.ThemeMode.DARK, Appearance.ThemeMode.of(true))
        assertNull(Appearance.ThemeMode.SYSTEM.dark, "the user can go back to following Windows")
    }

    @Test fun accentsAreDistinctAndUnknownFallsBack() {
        assertEquals(Appearance.Accent.INDIGO, Appearance.Accent.of("nope"))
        assertEquals(Appearance.Accent.TEAL, Appearance.Accent.of("teal"))
        val ids = Appearance.Accent.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(Appearance.Accent.entries.size, Appearance.Accent.entries.map { it.light }.toSet().size)
        // Default accent matches the previous hard-coded theme colour.
        assertEquals(0xFF5B6CFF, Appearance.Accent.of(AppSettings().accent).light)
    }

    @Test fun textSizeSteps() {
        assertEquals(1.1f, Appearance.step(1f, +1)); assertEquals(0.9f, Appearance.step(1f, -1))
        assertEquals(1f, Appearance.step(1.4f, 0))
        assertEquals(1.6f, Appearance.step(1.6f, +1), "stops at the top"); assertEquals(0.85f, Appearance.step(0.85f, -1), "stops at the bottom")
        assertEquals(1.2f, Appearance.step(1.15f, +1), "off-step values move to the next real step")
        assertEquals(1.1f, Appearance.step(1.15f, -1))
        assertEquals(1f, Appearance.clampScale(Float.NaN)); assertEquals(1.6f, Appearance.clampScale(9f)); assertEquals(0.85f, Appearance.clampScale(0.1f))
        assertEquals(110, Appearance.percent(1.1f))
    }

    @Test fun fontChoicesOrder() {
        val installed = listOf("Arial", "Consolas", "@Microsoft YaHei", "microsoft yahei ui", "Zeta", "Cascadia Mono", "Segoe UI")
        assertEquals(listOf("", "microsoft yahei ui", "Segoe UI", "Arial", "Cascadia Mono", "Consolas", "Zeta"),
            Appearance.fontChoices(installed, Appearance.UI_RECOMMENDED, ""), "default, installed recommended (case-insensitive), then A→Z, no @ variants")
        val mono = Appearance.fontChoices(installed, Appearance.MONO_RECOMMENDED, "Gone Mono")
        assertEquals(listOf("", "Cascadia Mono", "Consolas", "Gone Mono"), mono.take(4), "an uninstalled current choice stays visible")
        assertEquals(mono.size, mono.toSet().size)
    }

    @Test fun appearancePersistsAndOldFilesLoadDefaults() {
        val dir = TestSupport.tmp("om-appear")
        val f = File(dir, "settings.json")
        val store = SettingsStore(f)
        store.save(AppSettings(darkTheme = true, accent = "rose", fontScale = 1.2f, uiFont = "Microsoft YaHei UI", monoFont = "Cascadia Mono"))
        val back = store.load()
        assertEquals(listOf<Any?>(true, "rose", 1.2f, "Microsoft YaHei UI", "Cascadia Mono"), listOf(back.darkTheme, back.accent, back.fontScale, back.uiFont, back.monoFont))
        f.writeText("""{"darkTheme":false}""")
        val old = SettingsStore(f).load()
        assertEquals(listOf<Any?>(false, "indigo", 1f, "", ""), listOf(old.darkTheme, old.accent, old.fontScale, old.uiFont, old.monoFont))
    }
}
