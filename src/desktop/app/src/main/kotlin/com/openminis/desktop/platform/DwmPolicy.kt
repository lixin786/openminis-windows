// OpenMinis Desktop — immersive batch 2: the platform-neutral decisions behind WindowsDwm (unit-tested).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.platform

/** Win32 / DWM constants used by [WindowsDwm] (values from winuser.h / dwmapi.h). */
object Win32 {
    const val GWL_STYLE = -16
    const val GWLP_WNDPROC = -4
    const val WS_THICKFRAME = 0x00040000
    const val WS_CAPTION = 0x00C00000
    const val WS_SYSMENU = 0x00080000
    const val WS_MINIMIZEBOX = 0x00020000
    const val WS_MAXIMIZEBOX = 0x00010000
    const val WM_NCCALCSIZE = 0x0083
    const val WM_NCACTIVATE = 0x0086
    const val WM_SYSCOMMAND = 0x0112
    const val WM_SIZE = 0x0005
    const val WM_NCDESTROY = 0x0082
    const val WM_GETMINMAXINFO = 0x0024
    const val WM_WINDOWPOSCHANGED = 0x0047
    const val WM_WINDOWPOSCHANGING = 0x0046
    const val SM_CXVIRTUALSCREEN = 78; const val SM_CYVIRTUALSCREEN = 79
    const val SW_MAXIMIZE = 3; const val SW_RESTORE = 9
    const val SWP_ASYNCWINDOWPOS = 0x4000
    const val SIZE_MAXIMIZED = 2
    const val SC_MAXIMIZE = 0xF030
    const val SC_RESTORE = 0xF120
    const val SC_KEYMENU = 0xF100
    const val SWP_NOSIZE = 0x0001; const val SWP_NOMOVE = 0x0002; const val SWP_NOZORDER = 0x0004
    const val SWP_NOACTIVATE = 0x0010; const val SWP_FRAMECHANGED = 0x0020
    const val SM_CXFRAME = 32; const val SM_CYFRAME = 33; const val SM_CXPADDEDBORDER = 92
    const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    const val DWMWCP_DONOTROUND = 1
    const val DWMWCP_ROUND = 2
}

/**
 * What the native layer does, decided without touching Windows:
 * - **Shadow**: DWM only draws the drop shadow for a window with a frame style, so the "aero borderless"
 *   bits are added and the non-client area is removed again in WM_NCCALCSIZE (client = whole window, so AWT
 *   keeps zero insets and the batch-1 layout is unchanged). The frame is extended 1 px into the client area.
 * - **Corners**: DWMWA_WINDOW_CORNER_PREFERENCE = ROUND while floating, DONOTROUND while maximized (Win11
 *   22000+; Windows 10 rejects the attribute and keeps square corners, the shadow still works there).
 * - **Maximize is the OS's, sized by us**: WM_GETMINMAXINFO pins the maximized window rect to the host monitor's
 *   work area ([maxRect]) and lifts the max track size, so a native zoom (title-bar button, double-click,
 *   Win+Up, snap → maximize) covers exactly the work area.
 * - **Size limits are ours** ([limits] / [clampSize]): WM_GETMINMAXINFO and WM_WINDOWPOSCHANGING both apply the
 *   app minimum (Window.minimumSize × scale) and a ceiling of the virtual screen + slack, never above the D3D
 *   surface limit. Whatever DefWindowProc / AWT made of a requested size is replaced by the clamped request, so
 *   another program's SetWindowPos lands as asked. (2026-10-12: a bogus track size turned SetWindowPos 1800x1200
 *   into 1800x1630 and, with the max track at 32000, into 1800x32000 — Skiko then crashed creating the surface.)
 *   SC_MAXIMIZE / SC_RESTORE pass through (dialogs swallow SC_MAXIMIZE). SC_KEYMENU is swallowed: the frame style
 *   would otherwise bring back the native system menu on Alt+Space next to the app's own title-bar menu.
 *   (Until 2026-10 the app un-zoomed an OS maximize and re-did it with setBounds; at 200 % that went through
 *   AWT's normal-position bookkeeping and left a half-size window, and snapped windows ignored Win+Up / Win+Down.)
 */
object DwmPolicy {
    /** Frame bits OR-ed into the undecorated window's style. Dialogs get no min / max / system menu. */
    fun extraStyle(canMaximize: Boolean): Int =
        Win32.WS_THICKFRAME or Win32.WS_CAPTION or
            (if (canMaximize) Win32.WS_SYSMENU or Win32.WS_MINIMIZEBOX or Win32.WS_MAXIMIZEBOX else 0)

    fun corner(maximized: Boolean): Int = if (maximized) Win32.DWMWCP_DONOTROUND else Win32.DWMWCP_ROUND

    /** DwmExtendFrameIntoClientArea margins (l, r, t, b): 1 px while floating (shadow), none while maximized. */
    fun margins(maximized: Boolean): IntArray = if (maximized) intArrayOf(0, 0, 0, 0) else intArrayOf(1, 1, 1, 1)

    enum class SysCommand { PASS, SWALLOW }

    fun sysCommand(wParam: Long, canMaximize: Boolean): SysCommand =
        when ((wParam and 0xFFF0).toInt()) {
            Win32.SC_KEYMENU -> SysCommand.SWALLOW
            Win32.SC_MAXIMIZE -> if (canMaximize) SysCommand.PASS else SysCommand.SWALLOW
            else -> SysCommand.PASS
        }

    /**
     * MINMAXINFO ptMaxPosition / ptMaxSize (device px) for a window on a monitor: the work area, with the position
     * relative to the monitor's top-left as Windows expects. Returns (x, y, width, height).
     */
    fun maxRect(monitor: com.openminis.desktop.core.WinRect, work: com.openminis.desktop.core.WinRect): IntArray =
        intArrayOf(work.x - monitor.x, work.y - monitor.y, work.width, work.height)

    /** Largest window edge Skiko's Direct3D surface accepts (D3D11 max texture size). */
    const val SURFACE_MAX = 16_384
    /** Room above the virtual screen for frames / invisible borders. */
    const val SLACK = 128

    data class SizeLimits(val minW: Int, val minH: Int, val maxW: Int, val maxH: Int)

    /** Device-px size limits: the logical minimum × scale; the virtual screen + [SLACK], capped at [SURFACE_MAX]. */
    fun limits(minLogicalW: Int, minLogicalH: Int, scaleX: Double, scaleY: Double, virtualW: Int, virtualH: Int): SizeLimits {
        val minW = Math.ceil(minLogicalW * scaleX).toInt().coerceIn(1, SURFACE_MAX)
        val minH = Math.ceil(minLogicalH * scaleY).toInt().coerceIn(1, SURFACE_MAX)
        val maxW = (virtualW + SLACK).coerceIn(minW, SURFACE_MAX)
        val maxH = (virtualH + SLACK).coerceIn(minH, SURFACE_MAX)
        return SizeLimits(minW, minH, maxW, maxH)
    }

    /** Who asked for a size change (WM_WINDOWPOSCHANGING). */
    enum class SizeOrigin {
        /** PlacementMachine via NativeOps (edge / corner drag, restore). */
        OURS,
        /** AWT's own reshape (the EDT is blocked in WComponentPeer.reshape / setBounds): Compose or AWT re-applying its sizes. */
        AWT,
        /** Anyone else: the shell (snap, Win+arrows), another program's SetWindowPos, the OS zoom. */
        FOREIGN,
    }

    /** How long after a foreign size change an AWT re-apply of a different size is treated as stale. */
    const val STALE_MS = 1500L

    /**
     * The size to keep for a request: a foreign change (snap, SetWindowPos from outside) wins over an AWT re-apply
     * of a different size that follows within [STALE_MS] — that echo is AWT / Compose writing back the bounds it had
     * cached before the foreign change. Everything else gets the request.
     */
    fun sizeFor(origin: SizeOrigin, reqW: Int, reqH: Int, foreign: IntArray?, sinceForeignMs: Long): IntArray =
        if (origin == SizeOrigin.AWT && foreign != null && sinceForeignMs in 0..STALE_MS && (reqW != foreign[0] || reqH != foreign[1]))
            intArrayOf(foreign[0], foreign[1])
        else intArrayOf(reqW, reqH)

    /** EDT stack frames that mean AWT itself is reshaping the window. */
    fun isAwtReshape(frames: List<String>): Boolean =
        frames.any { it.startsWith("sun.awt.windows.WComponentPeer.reshape") || it.startsWith("sun.awt.windows.WComponentPeer.setBounds") ||
            it.startsWith("sun.awt.windows.WWindowPeer.setBounds") || it.startsWith("sun.awt.windows.WWindowPeer.reshape") }

    /** The size a WM_WINDOWPOSCHANGING request ends up with: the request, clamped to [l]; nothing else. */
    fun clampSize(reqW: Int, reqH: Int, l: SizeLimits): IntArray =
        intArrayOf(reqW.coerceIn(l.minW, l.maxW), reqH.coerceIn(l.minH, l.maxH))

    /**
     * NCCALCSIZE client rect (l, t, r, b) while zoomed: the proposed rect clipped to the work area. With [maxRect] in
     * place that changes nothing; if MINMAXINFO was not applied, the off-screen frame overhang is cut off.
     */
    fun zoomedClient(l: Int, t: Int, r: Int, b: Int, work: com.openminis.desktop.core.WinRect): IntArray =
        intArrayOf(maxOf(l, work.x), maxOf(t, work.y), minOf(r, work.x + work.width), minOf(b, work.y + work.height))

    /** `-Dopenminis.dwm=false` turns the native layer off (the immersive switch in Settings is the user-facing fallback). */
    fun enabled(osName: String = System.getProperty("os.name").orEmpty(), flag: String? = System.getProperty("openminis.dwm")): Boolean =
        osName.startsWith("Windows", ignoreCase = true) && flag?.equals("false", ignoreCase = true) != true
}
