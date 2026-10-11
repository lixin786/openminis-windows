// OpenMinis Desktop — immersive batch 2: Win11 rounded corners + DWM drop shadow for undecorated windows (JNA).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.platform

import com.openminis.app.logging.AppLogger
import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.ConcurrentHashMap

/**
 * Native side of [DwmPolicy]. Every call is best effort: any failure is logged once and the window keeps the
 * batch-1 look (square, self-drawn 1 px outline). Nothing here is touched unless [DwmPolicy.enabled].
 */
object WindowsDwm {
    /** What [install] achieved for one window. */
    data class Result(val shadow: Boolean, val rounded: Boolean)

    /** Callbacks from the subclassed window procedure (run on the AWT toolkit thread → hop to the EDT). */
    interface Host {
        val maximizedByUs: Boolean
        val canMaximize: Boolean
        fun requestMaximize()
        fun requestRestore()
    }

    @Suppress("FunctionName")
    interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: HWND, attr: Int, value: IntByReference, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: HWND, margins: Margins): Int
    }

    @Suppress("FunctionName")
    interface User : StdCallLibrary {
        fun GetWindowLongW(hwnd: HWND, index: Int): Int
        fun SetWindowLongW(hwnd: HWND, index: Int, value: Int): Int
        fun SetWindowLongPtrW(hwnd: HWND, index: Int, proc: Callback): Pointer?
        fun SetWindowLongPtrW(hwnd: HWND, index: Int, proc: Pointer?): Pointer?
        fun CallWindowProcW(prev: Pointer?, hwnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
        fun SetWindowPos(hwnd: HWND, after: HWND?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun IsIconic(hwnd: HWND): Boolean
        fun IsZoomed(hwnd: HWND): Boolean
        fun GetSystemMetrics(index: Int): Int
    }

    @Structure.FieldOrder("cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight")
    class Margins @JvmOverloads constructor(
        @JvmField var cxLeftWidth: Int = 0, @JvmField var cxRightWidth: Int = 0,
        @JvmField var cyTopHeight: Int = 0, @JvmField var cyBottomHeight: Int = 0,
    ) : Structure()

    interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    }

    private val dwm by lazy { Native.load("dwmapi", Dwm::class.java) }
    private val user by lazy { Native.load("user32", User::class.java) }

    /** Keeps each window's callback reachable (a collected JNA callback = crash) and its previous procedure. */
    private class Hook(val proc: WndProc, val prev: Pointer?, val host: Host)
    private val hooks = ConcurrentHashMap<Pointer, Hook>()

    private fun hwnd(w: java.awt.Window): HWND? =
        runCatching { Native.getWindowPointer(w)?.let(::HWND) }.getOrNull()

    /** Call on the EDT once the window is displayable. Returns null when nothing was applied. */
    fun install(w: java.awt.Window, host: Host, maximized: Boolean): Result? {
        if (!DwmPolicy.enabled()) return null
        return try {
            val h = hwnd(w) ?: return null
            if (hooks.containsKey(h.pointer)) return Result(shadow = true, rounded = setMaximized(w, maximized))
            // 1) Subclass first, so the frame style never produces a visible non-client area.
            val proc = object : WndProc {
                override fun callback(hwnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT = handle(hwnd, msg, wParam, lParam)
            }
            val prev = user.SetWindowLongPtrW(h, Win32.GWLP_WNDPROC, proc)
            if (prev == null) { AppLogger.warning("Dwm", "subclassing failed; keeping the square batch-1 frame"); return null }
            hooks[h.pointer] = Hook(proc, prev, host)
            // 2) Frame bits → DWM draws the shadow; WM_NCCALCSIZE keeps the client area = whole window.
            val style = user.GetWindowLongW(h, Win32.GWL_STYLE)
            user.SetWindowLongW(h, Win32.GWL_STYLE, style or DwmPolicy.extraStyle(host.canMaximize))
            user.SetWindowPos(h, null, 0, 0, 0, 0,
                Win32.SWP_FRAMECHANGED or Win32.SWP_NOMOVE or Win32.SWP_NOSIZE or Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE)
            val rounded = setMaximized(w, maximized)
            AppLogger.info("Dwm", "native frame on (shadow, rounded=$rounded)")
            Result(shadow = true, rounded = rounded)
        } catch (e: Throwable) {
            AppLogger.warning("Dwm", "native frame unavailable: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Corners + shadow margins for the floating / maximized state. Returns whether rounding was accepted (Win11). */
    fun setMaximized(w: java.awt.Window, maximized: Boolean): Boolean = try {
        val h = hwnd(w)
        if (h == null || !hooks.containsKey(h.pointer)) false else {
            val m = DwmPolicy.margins(maximized)
            dwm.DwmExtendFrameIntoClientArea(h, Margins(m[0], m[1], m[2], m[3]))
            dwm.DwmSetWindowAttribute(h, Win32.DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(DwmPolicy.corner(maximized)), 4) == 0
        }
    } catch (e: Throwable) { false }

    private fun handle(hwnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
        val hook = hooks[hwnd.pointer] ?: return LRESULT(0L)
        try {
            when (msg) {
                Win32.WM_NCCALCSIZE -> if (wParam.toLong() != 0L) {
                    // Client = whole window. While the OS has it zoomed (before we take it over), inset by the frame.
                    if (user.IsZoomed(hwnd)) {
                        val p = Pointer(lParam.toLong())
                        val fx = user.GetSystemMetrics(Win32.SM_CXFRAME) + user.GetSystemMetrics(Win32.SM_CXPADDEDBORDER)
                        val fy = user.GetSystemMetrics(Win32.SM_CYFRAME) + user.GetSystemMetrics(Win32.SM_CXPADDEDBORDER)
                        val r = DwmPolicy.zoomedClient(p.getInt(0), p.getInt(4), p.getInt(8), p.getInt(12), fx, fy)
                        r.forEachIndexed { i, v -> p.setInt(i * 4L, v) }
                    }
                    return LRESULT(0L)
                }
                Win32.WM_NCACTIVATE -> {
                    // Let AWT see activation, but never let DefWindowProc paint a caption: lParam -1 = no repaint.
                    return user.CallWindowProcW(hook.prev, hwnd, msg, wParam, LPARAM(-1L))
                }
                Win32.WM_SYSCOMMAND -> when (DwmPolicy.sysCommand(wParam.toLong(), user.IsIconic(hwnd), hook.host.maximizedByUs, hook.host.canMaximize)) {
                    DwmPolicy.SysCommand.MAXIMIZE_OURS -> { javax.swing.SwingUtilities.invokeLater { hook.host.requestMaximize() }; return LRESULT(0L) }
                    DwmPolicy.SysCommand.RESTORE_OURS -> { javax.swing.SwingUtilities.invokeLater { hook.host.requestRestore() }; return LRESULT(0L) }
                    DwmPolicy.SysCommand.SWALLOW -> return LRESULT(0L)
                    DwmPolicy.SysCommand.PASS -> {}
                }
                Win32.WM_SIZE -> if (wParam.toInt() == Win32.SIZE_MAXIMIZED && hook.host.canMaximize) {
                    // An OS maximize (Win+↑) would cover the taskbar edge and bypass PlacementMachine: hand it to ours.
                    javax.swing.SwingUtilities.invokeLater { hook.host.requestMaximize() }
                }
                Win32.WM_NCDESTROY -> {
                    hooks.remove(hwnd.pointer)
                    user.SetWindowLongPtrW(hwnd, Win32.GWLP_WNDPROC, hook.prev)
                }
            }
        } catch (e: Throwable) {
            AppLogger.warning("Dwm", "window procedure: ${e.javaClass.simpleName}")
        }
        return user.CallWindowProcW(hook.prev, hwnd, msg, wParam, lParam)
    }
}
