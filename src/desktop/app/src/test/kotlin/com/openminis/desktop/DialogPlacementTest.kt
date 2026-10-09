// batch3 follow-up: secondary windows (Settings, Scheduled tasks) must fit small / high-DPI screens.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.desktop.core.WindowPlacements
import com.openminis.desktop.ui.RUNS_ONLY_WHILE_OPEN_NOTE
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogPlacementTest {
    private fun fits(p: com.openminis.desktop.core.WindowPlacement, s: Rectangle) =
        p.x >= s.x && p.y >= s.y && p.x + p.width <= s.x + s.width && p.y + p.height <= s.y + s.height

    @Test fun settingsFitsA1366x768LaptopAt150Percent() {
        // 1366×768 @150 % → 910×512 logical, minus a 40 px taskbar.
        val screen = Rectangle(0, 0, 910, 472)
        val p = WindowPlacements.dialogPlacement(720, 820, screen)
        assertTrue(fits(p, screen), p.toString())
        assertEquals(472 - 2 * WindowPlacements.DIALOG_MARGIN, p.height)
        assertEquals(720, p.width)
        assertEquals((910 - 720) / 2, p.x); assertEquals(WindowPlacements.DIALOG_MARGIN, p.y)
    }

    @Test fun at125PercentAndOnASecondMonitorWithOffset() {
        val screen = Rectangle(1920, -200, 1092, 574) // 1366×768 @125 %, left of / above the primary
        val p = WindowPlacements.dialogPlacement(820, 680, screen)
        assertTrue(fits(p, screen), p.toString())
        assertEquals(574 - 48, p.height); assertEquals(820, p.width)
    }

    @Test fun bigScreenKeepsPreferredSizeCentered() {
        val screen = Rectangle(0, 0, 2560, 1400)
        val p = WindowPlacements.dialogPlacement(720, 820, screen)
        assertEquals(720, p.width); assertEquals(820, p.height)
        assertEquals((2560 - 720) / 2, p.x); assertEquals((1400 - 820) / 2, p.y)
    }

    @Test fun tinyScreenNeverExceedsIt() {
        val screen = Rectangle(0, 0, 300, 200)
        val p = WindowPlacements.dialogPlacement(720, 820, screen)
        assertTrue(fits(p, screen), p.toString())
    }

    @Test fun noScreenInfoFallsBackToPreferredAndPlatformPosition() {
        val p = WindowPlacements.dialogPlacement(720, 820, null)
        assertEquals(-1, p.x); assertEquals(720, p.width); assertEquals(820, p.height)
    }

    @Test fun scheduledNoteSaysTasksDoNotRunWhileClosed() {
        assertTrue(RUNS_ONLY_WHILE_OPEN_NOTE.contains("only while OpenMinis is open"))
        assertTrue(RUNS_ONLY_WHILE_OPEN_NOTE.contains("skipped"))
    }
}
