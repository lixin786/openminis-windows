// Immersive batch 2 — DWM rounded corners + shadow: policy (any OS), wiring (static), and a live check on Windows.
package com.openminis.desktop

import com.openminis.desktop.platform.DwmPolicy
import com.openminis.desktop.core.WinRect
import com.openminis.desktop.platform.DwmPolicy.SysCommand
import com.openminis.desktop.platform.Win32
import com.openminis.desktop.platform.WindowsDwm
import java.io.File
import kotlin.test.*

class DwmWindowTest {
    private val src = listOf(File("src/main/kotlin"), File("app/src/main/kotlin"), File("src/desktop/app/src/main/kotlin"))
        .first { it.isDirectory }.resolve("com/openminis/desktop")

    @Test fun cornersAndShadowFollowTheMaximizedState() {
        assertEquals(Win32.DWMWCP_ROUND, DwmPolicy.corner(maximized = false))
        assertEquals(Win32.DWMWCP_DONOTROUND, DwmPolicy.corner(maximized = true))
        assertContentEquals(intArrayOf(1, 1, 1, 1), DwmPolicy.margins(false))
        assertContentEquals(intArrayOf(0, 0, 0, 0), DwmPolicy.margins(true))
    }

    @Test fun frameStyleBits() {
        val main = DwmPolicy.extraStyle(canMaximize = true)
        listOf(Win32.WS_THICKFRAME, Win32.WS_CAPTION, Win32.WS_SYSMENU, Win32.WS_MINIMIZEBOX, Win32.WS_MAXIMIZEBOX)
            .forEach { assertTrue(main and it == it) }
        val dialog = DwmPolicy.extraStyle(canMaximize = false)
        assertTrue(dialog and Win32.WS_THICKFRAME != 0 && dialog and Win32.WS_CAPTION == Win32.WS_CAPTION)
        assertEquals(0, dialog and (Win32.WS_MAXIMIZEBOX or Win32.WS_MINIMIZEBOX or Win32.WS_SYSMENU))
    }

    @Test fun systemCommandsLeaveZoomToTheOs() {
        fun sc(cmd: Int, canMax: Boolean = true) = DwmPolicy.sysCommand(cmd.toLong(), canMax)
        assertEquals(SysCommand.PASS, sc(Win32.SC_MAXIMIZE), "OS zoom, sized by WM_GETMINMAXINFO")
        assertEquals(SysCommand.PASS, sc(Win32.SC_MAXIMIZE or 0x2), "low 4 bits are system use")
        assertEquals(SysCommand.SWALLOW, sc(Win32.SC_MAXIMIZE, canMax = false))
        assertEquals(SysCommand.SWALLOW, sc(Win32.SC_MAXIMIZE or 0x2, canMax = false))
        assertEquals(SysCommand.PASS, sc(Win32.SC_RESTORE))
        assertEquals(SysCommand.SWALLOW, sc(Win32.SC_KEYMENU), "no native system menu next to the app's own")
        assertEquals(SysCommand.PASS, sc(0xF020), "minimize")
        assertEquals(SysCommand.PASS, sc(0xF060), "close")
        assertEquals(SysCommand.PASS, sc(0xF010), "move")
    }

    @Test fun zoomedClientIsClippedToTheWorkArea() {
        val work = WinRect(0, 0, 2560, 1504)
        assertContentEquals(intArrayOf(0, 0, 2560, 1504), DwmPolicy.zoomedClient(0, 0, 2560, 1504, work), "MINMAXINFO took: unchanged")
        assertContentEquals(intArrayOf(0, 0, 2560, 1504), DwmPolicy.zoomedClient(-16, -16, 2576, 1520, work), "frame overhang cut off")
    }

    @Test fun maximizedRectIsTheWorkAreaRelativeToItsMonitor() {
        // 2560x1600 @200 %, taskbar 96 px at the bottom (the user's screen): 0,0 2560x1504.
        assertContentEquals(intArrayOf(0, 0, 2560, 1504), DwmPolicy.maxRect(WinRect(0, 0, 2560, 1600), WinRect(0, 0, 2560, 1504)))
        // taskbar on top
        assertContentEquals(intArrayOf(0, 96, 2560, 1504), DwmPolicy.maxRect(WinRect(0, 0, 2560, 1600), WinRect(0, 96, 2560, 1504)))
        // second monitor to the right: position is relative to that monitor
        assertContentEquals(intArrayOf(0, 0, 1920, 1040), DwmPolicy.maxRect(WinRect(2560, 0, 1920, 1080), WinRect(2560, 0, 1920, 1040)))
    }

    @Test fun sizeLimitsAt200PercentAndClamping() {
        // 2560x1600 @200 %, app minimum 760x520 logical
        val l = DwmPolicy.limits(760, 520, 2.0, 2.0, 2560, 1600)
        assertEquals(DwmPolicy.SizeLimits(1520, 1040, 2688, 1728), l)
        assertContentEquals(intArrayOf(1800, 1200), DwmPolicy.clampSize(1800, 1200, l), "SetWindowPos 1800x1200 lands as asked (not 1630, not 32000)")
        assertContentEquals(intArrayOf(1800, 1728), DwmPolicy.clampSize(1800, 32000, l), "a runaway height is cut to the screen")
        assertContentEquals(intArrayOf(1520, 1040), DwmPolicy.clampSize(100, 100, l), "the app minimum holds")
        val huge = DwmPolicy.limits(760, 520, 1.0, 1.0, 30000, 20000)
        assertEquals(DwmPolicy.SURFACE_MAX, huge.maxW); assertEquals(DwmPolicy.SURFACE_MAX, huge.maxH)
    }

    @Test fun switchedOnOnlyOnWindowsAndCanBeTurnedOff() {
        assertTrue(DwmPolicy.enabled("Windows 11", null))
        assertFalse(DwmPolicy.enabled("Windows 11", "false"))
        assertTrue(DwmPolicy.enabled("Windows 10", "true"))
        assertFalse(DwmPolicy.enabled("Linux", null))
        assertFalse(DwmPolicy.enabled("Mac OS X", null))
    }

    @Test fun wiring() {
        val chrome = File(src, "ui/WindowChrome.kt").readText()
        assertTrue("WindowsDwm.install(w, dwmHost, machine.maximized)" in chrome, "attach installs the native frame")
        assertTrue("WindowsDwm.setMaximized(it, maximized)" in chrome, "maximize / restore update corners and shadow")
        assertTrue("chrome.maximized || chrome.dwmFrame" in chrome, "no self-drawn outline under a DWM frame")
        val native = File(src, "platform/WindowsDwm.kt").readText()
        assertTrue("WM_NCDESTROY" in native && "hooks.remove" in native, "window procedure restored, callback released")
        assertTrue("CallWindowProcW(hook.prev" in native)
        // dialogs go through the same attach (AttachChrome) — settings and scheduled tasks
        listOf("ui/SettingsDialog.kt", "ui/ScheduledTasksWindow.kt").forEach { assertTrue("AttachChrome(" in File(src, it).readText(), it) }
    }

    /** Live: an undecorated frame keeps zero insets and its bounds after the frame style is added (Windows only). */
    @Test fun liveFrameOnWindows() {
        if (!DwmPolicy.enabled() || java.awt.GraphicsEnvironment.isHeadless()) return
        val f = javax.swing.JFrame().apply { isUndecorated = true; setBounds(200, 200, 640, 400) }
        try {
            javax.swing.SwingUtilities.invokeAndWait { f.isVisible = true }
            var r: WindowsDwm.Result? = null
            val host = object : WindowsDwm.Host {
                override val canMaximize = true
                override fun placementChanged() {}
            }
            javax.swing.SwingUtilities.invokeAndWait { r = WindowsDwm.install(f, host, maximized = false) }
            Thread.sleep(300)
            assertNotNull(r, "native frame installed")
            javax.swing.SwingUtilities.invokeAndWait {
                assertEquals(java.awt.Insets(0, 0, 0, 0), f.insets, "no visible non-client area")
                assertEquals(java.awt.Rectangle(200, 200, 640, 400), f.bounds)
                WindowsDwm.setMaximized(f, true); WindowsDwm.setMaximized(f, false)
            }
        } finally { javax.swing.SwingUtilities.invokeAndWait { f.dispose() } }
    }
}
