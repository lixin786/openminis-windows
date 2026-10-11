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
 * - **Maximize stays ours** (work area, PlacementMachine): SC_MAXIMIZE and an OS maximize (Win+↑) are turned
 *   into the app's maximize; SC_RESTORE of a maximized-by-us window into the app's restore; restoring from the
 *   taskbar (iconic) passes through. SC_KEYMENU is swallowed: the frame style would otherwise bring back the
 *   native system menu on Alt+Space next to the app's own title-bar menu.
 */
object DwmPolicy {
    /** Frame bits OR-ed into the undecorated window's style. Dialogs get no min / max / system menu. */
    fun extraStyle(canMaximize: Boolean): Int =
        Win32.WS_THICKFRAME or Win32.WS_CAPTION or
            (if (canMaximize) Win32.WS_SYSMENU or Win32.WS_MINIMIZEBOX or Win32.WS_MAXIMIZEBOX else 0)

    fun corner(maximized: Boolean): Int = if (maximized) Win32.DWMWCP_DONOTROUND else Win32.DWMWCP_ROUND

    /** DwmExtendFrameIntoClientArea margins (l, r, t, b): 1 px while floating (shadow), none while maximized. */
    fun margins(maximized: Boolean): IntArray = if (maximized) intArrayOf(0, 0, 0, 0) else intArrayOf(1, 1, 1, 1)

    enum class SysCommand { PASS, MAXIMIZE_OURS, RESTORE_OURS, SWALLOW }

    fun sysCommand(wParam: Long, iconic: Boolean, maximizedByUs: Boolean, canMaximize: Boolean): SysCommand =
        when ((wParam and 0xFFF0).toInt()) {
            Win32.SC_KEYMENU -> SysCommand.SWALLOW
            Win32.SC_MAXIMIZE -> if (canMaximize) SysCommand.MAXIMIZE_OURS else SysCommand.SWALLOW
            Win32.SC_RESTORE -> if (!iconic && maximizedByUs) SysCommand.RESTORE_OURS else SysCommand.PASS
            else -> SysCommand.PASS
        }

    /** NCCALCSIZE rect while the OS has the window zoomed (transient, until it is turned into ours): inset by the frame. */
    fun zoomedClient(l: Int, t: Int, r: Int, b: Int, frameX: Int, frameY: Int): IntArray =
        intArrayOf(l + frameX, t + frameY, r - frameX, b - frameY)

    /** `-Dopenminis.dwm=false` turns the native layer off (the immersive switch in Settings is the user-facing fallback). */
    fun enabled(osName: String = System.getProperty("os.name").orEmpty(), flag: String? = System.getProperty("openminis.dwm")): Boolean =
        osName.startsWith("Windows", ignoreCase = true) && flag?.equals("false", ignoreCase = true) != true
}
