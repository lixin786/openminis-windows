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
        val canMaximize: Boolean
        /** The window's position, size or zoom changed (ours, the OS's, or another program's). Called on the EDT. */
        fun placementChanged()
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
        fun GetWindowRect(hwnd: HWND, rect: com.sun.jna.platform.win32.WinDef.RECT): Boolean
        fun ShowWindowAsync(hwnd: HWND, cmd: Int): Boolean
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
    private class Hook(val proc: WndProc, val prev: Pointer?, val host: Host, val window: java.awt.Window) {
        /** Diagnostics: what AWT / DefWindowProc last put in MINMAXINFO, and how many overrides were logged. */
        @Volatile var lastTrack = ""
        val logged = java.util.concurrent.atomic.AtomicInteger(0)
        /** The EDT (install runs on it): its stack tells an AWT reshape apart from a foreign one. */
        val edt: Thread = Thread.currentThread()
        /** Device-px size NativeOps just asked for (so its own WM_WINDOWPOSCHANGING is recognised). */
        val expect = java.util.concurrent.atomic.AtomicReference<IntArray?>(null)
        /** Last foreign size change (device px) and when. */
        @Volatile var foreign: IntArray? = null
        @Volatile var foreignAt = 0L
        val diag = java.util.concurrent.atomic.AtomicInteger(0)
        /** Coalesces WM_WINDOWPOSCHANGED bursts (a drag sends dozens) into one EDT callback. */
        val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    }
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
            hooks[h.pointer] = Hook(proc, prev, host, w)
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

    /**
     * [PlacementMachine.Ops] on the real window rect (GetWindowRect / SetWindowPos in device pixels, converted with
     * each monitor's scale), so AWT's cached bounds can never go stale under us. null = no native frame on [w].
     */
    fun nativeOps(w: java.awt.Window): com.openminis.desktop.core.PlacementMachine.Ops? {
        val h = hwnd(w) ?: return null
        if (!hooks.containsKey(h.pointer)) return null
        return NativeOps(w, h)
    }

    private class NativeOps(private val w: java.awt.Window, private val h: HWND) : com.openminis.desktop.core.PlacementMachine.Ops {
        private val fallback = com.openminis.desktop.ui.AwtWindowOps(w)
        private fun screens() = screenScales()
        override fun bounds(): com.openminis.desktop.core.WinRect = runCatching {
            val r = com.sun.jna.platform.win32.WinDef.RECT()
            if (!user.GetWindowRect(h, r)) error("GetWindowRect")
            com.openminis.desktop.core.WindowGeometry.toLogical(
                com.openminis.desktop.core.WinRect(r.left, r.top, r.right - r.left, r.bottom - r.top), screens())
        }.getOrElse { fallback.bounds() }
        override fun setBounds(r: com.openminis.desktop.core.WinRect) {
            val p = com.openminis.desktop.core.WindowGeometry.toPhysical(r, screens())
            hooks[h.pointer]?.expect?.set(intArrayOf(p.width, p.height))
            // Async: posted to the window's (toolkit) thread, so the EDT never blocks on it.
            if (!runCatching { user.SetWindowPos(h, null, p.x, p.y, p.width, p.height,
                    Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE or Win32.SWP_ASYNCWINDOWPOS) }.getOrDefault(false)) fallback.setBounds(r)
        }
        override fun workAreas() = fallback.workAreas()
        override fun minimized() = runCatching { user.IsIconic(h) }.getOrElse { fallback.minimized() }
        override fun osMaximize() = runCatching { user.ShowWindowAsync(h, Win32.SW_MAXIMIZE); true }.getOrDefault(false)
        override fun osRestore() = runCatching { user.ShowWindowAsync(h, Win32.SW_RESTORE); true }.getOrDefault(false)
    }

    /** Whether the OS has [w] zoomed / iconic right now (native frame only). */
    fun zoomed(w: java.awt.Window): Boolean = hwnd(w)?.let { runCatching { user.IsZoomed(it) }.getOrNull() } ?: false
    fun iconic(w: java.awt.Window): Boolean = hwnd(w)?.let { runCatching { user.IsIconic(it) }.getOrNull() } ?: false

    private fun screenScales(): List<com.openminis.desktop.core.ScreenScale> = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { d ->
            val gc = d.defaultConfiguration; val t = gc.defaultTransform
            com.openminis.desktop.core.ScreenScale(com.openminis.desktop.core.WinRect.of(gc.bounds), t.scaleX, t.scaleY)
        }
    }.getOrDefault(emptyList())

    /** Device-px size limits of a hooked window (its AWT minimum size × its monitor scale). */
    private fun limitsOf(hook: Hook): DwmPolicy.SizeLimits {
        val w = hook.window
        val min = runCatching { w.minimumSize }.getOrNull() ?: java.awt.Dimension(1, 1)
        val t = runCatching { w.graphicsConfiguration.defaultTransform }.getOrNull()
        return DwmPolicy.limits(min.width, min.height, t?.scaleX ?: 1.0, t?.scaleY ?: 1.0,
            user.GetSystemMetrics(Win32.SM_CXVIRTUALSCREEN), user.GetSystemMetrics(Win32.SM_CYVIRTUALSCREEN))
    }

    /** Monitor and work area (device px) of the monitor nearest to [hwnd]. */
    private fun monitorOf(hwnd: HWND): Pair<com.openminis.desktop.core.WinRect, com.openminis.desktop.core.WinRect>? = runCatching {
        val u = com.sun.jna.platform.win32.User32.INSTANCE
        val mon = u.MonitorFromWindow(hwnd, com.sun.jna.platform.win32.WinUser.MONITOR_DEFAULTTONEAREST)
        val mi = com.sun.jna.platform.win32.WinUser.MONITORINFO()
        if (!u.GetMonitorInfo(mon, mi).booleanValue()) return null
        fun rr(r: com.sun.jna.platform.win32.WinDef.RECT) = com.openminis.desktop.core.WinRect(r.left, r.top, r.right - r.left, r.bottom - r.top)
        rr(mi.rcMonitor) to rr(mi.rcWork)
    }.getOrNull()

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
                    // Client = whole window. Zoomed: clipped to the work area (a no-op when MINMAXINFO took).
                    if (user.IsZoomed(hwnd)) monitorOf(hwnd)?.let { (_, work) ->
                        val p = Pointer(lParam.toLong())
                        val r = DwmPolicy.zoomedClient(p.getInt(0), p.getInt(4), p.getInt(8), p.getInt(12), work)
                        r.forEachIndexed { i, v -> p.setInt(i * 4L, v) }
                    }
                    return LRESULT(0L)
                }
                Win32.WM_GETMINMAXINFO -> {
                    // AWT / DefWindowProc first; then maximized rect = work area, track sizes = our limits.
                    val res = user.CallWindowProcW(hook.prev, hwnd, msg, wParam, lParam)
                    val p = Pointer(lParam.toLong()) // POINT ptReserved, ptMaxSize, ptMaxPosition, ptMinTrackSize, ptMaxTrackSize
                    hook.lastTrack = "minTrack ${p.getInt(24)}x${p.getInt(28)} maxTrack ${p.getInt(32)}x${p.getInt(36)}"
                    if (hook.host.canMaximize) monitorOf(hwnd)?.let { (mon, work) ->
                        val m = DwmPolicy.maxRect(mon, work)
                        p.setInt(16, m[0]); p.setInt(20, m[1]); p.setInt(8, m[2]); p.setInt(12, m[3])
                    }
                    val l = limitsOf(hook)
                    p.setInt(24, l.minW); p.setInt(28, l.minH); p.setInt(32, l.maxW); p.setInt(36, l.maxH)
                    return res
                }
                Win32.WM_WINDOWPOSCHANGING -> {
                    // WINDOWPOS: hwnd, hwndInsertAfter (pointers), then int x, y, cx, cy, flags.
                    val p = Pointer(lParam.toLong()); val o = 2 * Native.POINTER_SIZE.toLong()
                    val flags = p.getInt(o + 16)
                    val reqW = p.getInt(o + 8); val reqH = p.getInt(o + 12)
                    val res = user.CallWindowProcW(hook.prev, hwnd, msg, wParam, lParam)
                    if (flags and Win32.SWP_NOSIZE == 0 && !user.IsIconic(hwnd)) {
                        val gotW = p.getInt(o + 8); val gotH = p.getInt(o + 12)
                        val cur = com.sun.jna.platform.win32.WinDef.RECT().also { user.GetWindowRect(hwnd, it) }
                        val curW = cur.right - cur.left; val curH = cur.bottom - cur.top
                        var want = intArrayOf(reqW, reqH)
                        if (reqW != curW || reqH != curH) {
                            val exp = hook.expect.get()
                            val frames = if (exp != null && exp[0] == reqW && exp[1] == reqH) emptyList()
                                else runCatching { hook.edt.stackTrace.take(40).map { "${it.className}.${it.methodName}" } }.getOrDefault(emptyList())
                            val origin = when {
                                exp != null && exp[0] == reqW && exp[1] == reqH -> DwmPolicy.SizeOrigin.OURS
                                DwmPolicy.isAwtReshape(frames) -> DwmPolicy.SizeOrigin.AWT
                                else -> DwmPolicy.SizeOrigin.FOREIGN
                            }
                            if (origin == DwmPolicy.SizeOrigin.OURS) hook.expect.compareAndSet(exp, null)
                            val now = System.currentTimeMillis()
                            want = DwmPolicy.sizeFor(origin, reqW, reqH, hook.foreign, now - hook.foreignAt)
                            if (origin == DwmPolicy.SizeOrigin.FOREIGN) { hook.foreign = DwmPolicy.clampSize(reqW, reqH, limitsOf(hook)); hook.foreignAt = now }
                            if (origin != DwmPolicy.SizeOrigin.OURS && hook.diag.incrementAndGet() <= 8) AppLogger.info("Dwm",
                                "size change $origin ${curW}x$curH -> ${reqW}x$reqH (kept ${want[0]}x${want[1]}, flags=0x${Integer.toHexString(flags)})" +
                                    if (origin == DwmPolicy.SizeOrigin.AWT) " via " + frames.filter { !it.startsWith("sun.awt.windows.WToolkit") }.take(14).joinToString(" < ") else "")
                        }
                        val f = DwmPolicy.clampSize(want[0], want[1], limitsOf(hook))
                        if (f[0] != gotW || f[1] != gotH) {
                            p.setInt(o + 8, f[0]); p.setInt(o + 12, f[1])
                            if (hook.logged.incrementAndGet() <= 5) AppLogger.warning("Dwm",
                                "size request ${reqW}x$reqH became ${gotW}x$gotH in the default handling (${hook.lastTrack}); applied ${f[0]}x${f[1]}")
                        }
                    }
                    return res
                }
                Win32.WM_NCACTIVATE -> {
                    // Let AWT see activation, but never let DefWindowProc paint a caption: lParam -1 = no repaint.
                    return user.CallWindowProcW(hook.prev, hwnd, msg, wParam, LPARAM(-1L))
                }
                Win32.WM_SYSCOMMAND -> when (DwmPolicy.sysCommand(wParam.toLong(), hook.host.canMaximize)) {
                    DwmPolicy.SysCommand.SWALLOW -> return LRESULT(0L)
                    DwmPolicy.SysCommand.PASS -> {}
                }
                Win32.WM_WINDOWPOSCHANGED -> {
                    val res = user.CallWindowProcW(hook.prev, hwnd, msg, wParam, lParam) // AWT updates its own state first
                    if (hook.pending.compareAndSet(false, true)) javax.swing.SwingUtilities.invokeLater {
                        hook.pending.set(false); hook.host.placementChanged()
                    }
                    return res
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
