// Immersive batch 2 — DWM rounded corners + shadow: policy (any OS), wiring (static), and a live check on Windows.
package com.openminis.desktop

import com.openminis.desktop.platform.DwmPolicy
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

    @Test fun systemCommandsKeepMaximizeOurs() {
        fun sc(cmd: Int, iconic: Boolean = false, ours: Boolean = false, canMax: Boolean = true) = DwmPolicy.sysCommand(cmd.toLong(), iconic, ours, canMax)
        assertEquals(SysCommand.MAXIMIZE_OURS, sc(Win32.SC_MAXIMIZE))
        assertEquals(SysCommand.MAXIMIZE_OURS, sc(Win32.SC_MAXIMIZE or 0x2), "low 4 bits are system use")
        assertEquals(SysCommand.SWALLOW, sc(Win32.SC_MAXIMIZE, canMax = false))
        assertEquals(SysCommand.RESTORE_OURS, sc(Win32.SC_RESTORE, ours = true))
        assertEquals(SysCommand.PASS, sc(Win32.SC_RESTORE, iconic = true, ours = true), "taskbar restore from minimized")
        assertEquals(SysCommand.PASS, sc(Win32.SC_RESTORE))
        assertEquals(SysCommand.SWALLOW, sc(Win32.SC_KEYMENU), "no native system menu next to the app's own")
        assertEquals(SysCommand.PASS, sc(0xF020), "minimize")
        assertEquals(SysCommand.PASS, sc(0xF060), "close")
        assertEquals(SysCommand.PASS, sc(0xF010), "move")
    }

    @Test fun zoomedClientIsInsetByTheFrame() {
        assertContentEquals(intArrayOf(8, 8, 1912, 1032), DwmPolicy.zoomedClient(0, 0, 1920, 1040, 8, 8))
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
                override val maximizedByUs = false; override val canMaximize = true
                override fun requestMaximize() {}; override fun requestRestore() {}
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
