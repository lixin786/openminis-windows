// Immersive window (IMMERSIVE-WINDOW.md), batch 1: 8-way resize math, maximize to the work area and exact
// restore, floating geometry kept apart from the maximized flag (window.json), the escape switch, menus,
// and that all three windows are undecorated with a self-drawn title bar.
package com.openminis.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.openminis.desktop.core.*
import com.openminis.desktop.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Rectangle
import java.io.File
import kotlin.test.*

class ImmersiveWindowTest {
    private class FakeOps(var b: WinRect, val areas: List<WinRect>) : PlacementMachine.Ops {
        var min = false
        val history = mutableListOf<WinRect>()
        override fun bounds() = b
        override fun setBounds(r: WinRect) { b = r; history += r }
        override fun workAreas() = areas
        override fun minimized() = min
    }

    // 1366×768 @150 % ≈ 910×512 logical, taskbar ≈ 40 → work area 910×472. Second monitor 1920×1040 on the right.
    private val small = WinRect(0, 0, 910, 472)
    private val big = WinRect(910, 0, 1920, 1040)

    // ------------------------------------------------------------- resize math

    @Test fun eightEdgesResizeTheRightSides() {
        val s = WinRect(100, 100, 800, 600)
        fun r(e: ResizeEdge, dx: Int, dy: Int) = WindowGeometry.resize(s, e, dx, dy, 400, 300)
        assertEquals(WinRect(100, 100, 850, 600), r(ResizeEdge.E, 50, 999))
        assertEquals(WinRect(100, 100, 800, 640), r(ResizeEdge.S, 999, 40))
        assertEquals(WinRect(80, 100, 820, 600), r(ResizeEdge.W, -20, 999))
        assertEquals(WinRect(100, 70, 800, 630), r(ResizeEdge.N, 999, -30))
        assertEquals(WinRect(90, 90, 810, 610), r(ResizeEdge.NW, -10, -10))
        assertEquals(WinRect(100, 90, 810, 610), r(ResizeEdge.NE, 10, -10))
        assertEquals(WinRect(90, 100, 810, 610), r(ResizeEdge.SW, -10, 10))
        assertEquals(WinRect(100, 100, 810, 610), r(ResizeEdge.SE, 10, 10))
        assertEquals(8, ResizeEdge.entries.size)
        assertEquals(8, ResizeEdge.entries.map { it.awtCursor }.toSet().size, "each zone has its own cursor")
    }

    @Test fun minimumSizeHoldsAndOppositeEdgeDoesNotDrift() {
        val s = WinRect(100, 100, 800, 600)
        // Dragging the left edge far right: width stops at the minimum, the right edge stays at 900.
        val w = WindowGeometry.resize(s, ResizeEdge.W, 700, 0, WindowPlacements.MIN_W, WindowPlacements.MIN_H)
        assertEquals(WindowPlacements.MIN_W, w.width); assertEquals(900, w.x + w.width)
        val n = WindowGeometry.resize(s, ResizeEdge.N, 0, 500, WindowPlacements.MIN_W, WindowPlacements.MIN_H)
        assertEquals(WindowPlacements.MIN_H, n.height); assertEquals(700, n.y + n.height)
        val se = WindowGeometry.resize(s, ResizeEdge.SE, -900, -900, 400, 300)
        assertEquals(WinRect(100, 100, 400, 300), se)
        // A screen smaller than the app minimum lowers the minimum to the screen.
        assertEquals(472 to 300, WindowGeometry.effectiveMin(760, 300, WinRect(0, 0, 472, 900)).let { it.first to it.second })
        assertEquals(910 to 472, WindowGeometry.effectiveMin(1000, 520, small))
    }

    @Test fun hostAreaIsTheMonitorUnderTheWindowCentre() {
        assertEquals(big, WindowGeometry.hostArea(WinRect(1200, 100, 800, 600), listOf(small, big)))
        assertEquals(small, WindowGeometry.hostArea(WinRect(10, 10, 300, 200), listOf(small, big)))
        // Centre off every screen: the one it overlaps most.
        assertEquals(big, WindowGeometry.hostArea(WinRect(2500, 900, 400, 400), listOf(small, big)))
        assertNull(WindowGeometry.hostArea(WinRect(0, 0, 1, 1), emptyList()))
    }

    // ------------------------------------------------------------- maximize / restore / persistence

    @Test fun maximizeFillsOnlyTheWorkAreaAndRestoreReturnsExactly() {
        val m = PlacementMachine(760, 520)
        val ops = FakeOps(WinRect(1000, 50, 900, 700), listOf(small, big)); m.ops = ops
        m.maximize()
        assertTrue(m.maximized)
        assertEquals(big, ops.b, "maximized = work area of the host monitor (taskbar stays visible)")
        assertEquals(WindowPlacement(1000, 50, 900, 700, true), m.snapshot(), "window.json keeps the floating geometry")
        m.restore()
        assertFalse(m.maximized)
        assertEquals(WinRect(1000, 50, 900, 700), ops.b)
        m.toggleMaximize(); assertTrue(m.maximized); m.toggleMaximize(); assertEquals(WinRect(1000, 50, 900, 700), ops.b)
    }

    @Test fun movesAndResizesAreIgnoredWhileMaximized() {
        val m = PlacementMachine(760, 520)
        val ops = FakeOps(WinRect(10, 10, 800, 600), listOf(small)); m.ops = ops
        m.maximize()
        val before = ops.history.size
        m.beginGesture(); m.moveBy(200, 200); m.resizeBy(ResizeEdge.SE, 50, 50); m.endGesture()
        assertEquals(before, ops.history.size)
        assertEquals(small, ops.b)
        m.restore(); assertEquals(WinRect(10, 10, 800, 600), ops.b)
    }

    @Test fun dragAndResizeUpdateTheFloatingGeometry() {
        val m = PlacementMachine(400, 300)
        val ops = FakeOps(WinRect(10, 10, 800, 600), listOf(big)); m.ops = ops
        m.beginGesture(); m.moveBy(30, 40); m.moveBy(100, 5); m.endGesture()
        assertEquals(WinRect(110, 15, 800, 600), ops.b, "moves are relative to the gesture start, not cumulative")
        m.beginGesture(); m.resizeBy(ResizeEdge.W, -10, 0); m.endGesture()
        assertEquals(WinRect(100, 15, 810, 600), ops.b)
        m.maximize(); m.restore()
        assertEquals(WinRect(100, 15, 810, 600), ops.b)
        // Minimized: bounds are meaningless (-32000), window.json gets the floating geometry.
        ops.b = WinRect(-32000, -32000, 160, 28); ops.min = true
        assertEquals(WindowPlacement(100, 15, 810, 600, false), m.snapshot())
    }

    @Test fun startupMaximizedRestoresToTheSavedFloatingSize() {
        val m = PlacementMachine(760, 520)
        m.restoreFrom(WinRect(1100, 80, 1000, 700), maximized = true)
        val ops = FakeOps(WinRect(1100, 80, 1000, 700), listOf(small, big)); m.ops = ops
        m.maximize()   // what Main does once the window exists
        assertEquals(big, ops.b)
        m.restore()
        assertEquals(WinRect(1100, 80, 1000, 700), ops.b)
    }

    @Test fun systemTitleBarModeIgnoresMaximizedSizesForFloating() {
        val m = PlacementMachine(760, 520)
        val ops = FakeOps(WinRect(10, 10, 800, 600), listOf(small)); m.ops = ops
        m.observedFloating(WinRect(20, 20, 820, 620))
        m.osPlacementChanged(true)
        m.observedFloating(WinRect(0, 0, 910, 472))  // a late resize event of the OS maximize
        assertEquals(WindowPlacement(20, 20, 820, 620, true), m.snapshot())
        m.osPlacementChanged(false)
        assertFalse(m.snapshot()!!.maximized)
    }

    @Test fun legacyWindowJsonWithMaximizedSizeGetsADefaultFloatingSize() {
        val screen = Rectangle(0, 0, 1920, 1040)
        val legacy = WindowPlacements.resolve(WindowPlacement(0, 0, 1920, 1040, maximized = true), listOf(screen))
        assertTrue(legacy.maximized)
        assertTrue(legacy.width <= WindowPlacements.DEFAULT_W && legacy.height <= WindowPlacements.DEFAULT_H, legacy.toString())
        val fine = WindowPlacements.resolve(WindowPlacement(100, 100, 1000, 700, maximized = true), listOf(screen))
        assertEquals(WindowPlacement(100, 100, 1000, 700, true), fine)
        // Round trip through window.json.
        val dir = TestSupport.tmp("wj"); val f = File(dir, "window.json")
        WindowPlacements.save(f, WindowPlacement(5, 6, 1001, 702, true))
        assertEquals(WindowPlacement(5, 6, 1001, 702, true), WindowPlacements.load(f))
    }

    @Test fun smallScreenStillFitsTheMainWindowAndDialogs() {
        // 1366×768 @150 %: resolved main window and both dialogs stay on screen.
        val s = Rectangle(0, 0, 910, 472)
        val main = WindowPlacements.resolve(null, listOf(s))
        assertTrue(main.x >= 0 && main.y >= 0 && main.x + main.width <= 910 && main.y + main.height <= 472, main.toString())
        for ((w, h) in listOf(960 to 760, 820 to 680)) {
            val d = WindowPlacements.dialogPlacement(w, h, s)
            assertTrue(d.x >= 0 && d.y >= 0 && d.x + d.width <= 910 && d.y + d.height <= 472, d.toString())
        }
        // Maximize on that screen = exactly the work area.
        val m = PlacementMachine(WindowPlacements.MIN_W, WindowPlacements.MIN_H)
        val ops = FakeOps(WinRect(main.x, main.y, main.width, main.height), listOf(WinRect(0, 0, 910, 472))); m.ops = ops
        m.maximize(); assertEquals(WinRect(0, 0, 910, 472), ops.b)
    }

    // ------------------------------------------------------------- switch, menus, wiring

    @Test fun escapeSwitchDefaultsOnAndOldSettingsFilesGetIt() {
        assertTrue(AppSettings().immersiveWindow)
        val dir = TestSupport.tmp("imm")
        val f = File(dir, "settings.json").apply { writeText("""{"activeProvider":"ANTHROPIC"}""") }
        assertTrue(SettingsStore(f).load().immersiveWindow)
        SettingsStore(f).save(AppSettings(immersiveWindow = false))
        assertFalse(SettingsStore(f).load().immersiveWindow)
    }

    @Test fun appMenusKeepEveryFormerMenuBarItem() {
        val state = AppState(AppPaths(TestSupport.tmp("menus")), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            val groups = appMenus(state, attach = {}, exit = {})
            assertEquals(3, groups.size)
            val labels = groups.flatMap { g -> g.entries.filterIsInstance<MenuEntry.Action>().map { it.label } }
            val expected = listOf("menu.new_chat", "menu.settings", "menu.scheduled", "menu.open_workspace", "menu.open_data", "menu.exit",
                "menu.zoom_in", "menu.zoom_out", "menu.zoom_reset", "menu.stop", "menu.regenerate", "menu.compact", "menu.attach",
                "menu.rename_chat", "menu.pin_chat", "menu.search_chats", "menu.delete_chat").map { com.openminis.desktop.i18n.tr(it) }
            assertEquals(expected, labels)
            val compact = groups[2].entries.filterIsInstance<MenuEntry.Action>().first { it.label == com.openminis.desktop.i18n.tr("menu.compact") }
            assertEquals("Ctrl+Shift+K", compact.shortcutText)
        } finally { runCatching { state.shutdown() } }
    }

    private fun src(rel: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "src/main/kotlin/com/openminis/desktop/$rel") }.first { it.isFile }.readText()

    @Test fun allThreeWindowsAreUndecoratedWithASelfDrawnTitleBar() {
        val main = src("Main.kt")
        assertTrue("undecorated = immersive" in main)
        assertTrue("if (!immersive) MenuBar" in main, "no second menu row in immersive mode")
        assertTrue("key(immersive)" in main, "mode switch recreates the window")
        for (f in listOf("ui/SettingsDialog.kt", "ui/ScheduledTasksWindow.kt")) {
            val s = src(f)
            assertTrue("undecorated = immersive" in s, f)
            assertTrue("DialogTitleBar(" in s, f)
            assertTrue("ImmersiveFrame(chrome)" in s, f)
            assertTrue("chromeKey(chrome, ev" in s, "$f: Alt+F4 / Alt+Space")
            assertTrue("Key.Escape" in s, "$f: Esc still closes")
        }
        val app = src("ui/App.kt")
        assertTrue("CaptionButtons(chrome" in app && "titleBarDrag(chrome, TITLE_REGION_TOP)" in app && "titleBarDrag(chrome, TITLE_REGION_SIDE)" in app)
    }

    @Test fun immersiveMainWindowRendersAtSmallAndLargeSizes() {
        val root = TestSupport.tmp("imm-render")
        val state = AppState(AppPaths(root), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            for ((w, h) in listOf(910 to 472, 1280 to 800)) for (maximized in listOf(false, true)) {
                val m = PlacementMachine(WindowPlacements.MIN_W, WindowPlacements.MIN_H)
                val chrome = WindowChrome(m, canMaximize = true) {}
                chrome.attachForPreview(FakeOps(WinRect(0, 0, w, h), listOf(WinRect(0, 0, w, h))))
                if (maximized) chrome.maximize()
                assertEquals(maximized, chrome.maximized)
                val scene = ImageComposeScene(w, h, Density(1f)) {
                    MinisTheme(state.settings) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalAppMenus provides appMenus(state, {}, {})) {
                            ImmersiveFrame(chrome) { App(state) }
                        }
                    }
                }
                repeat(2) { scene.render(it * 16_000_000L) }
                scene.close()
            }
        } finally { runCatching { state.shutdown() } }
    }
}
