// OpenMinis Desktop — pure geometry for the immersive (undecorated) window: edge resizing, title-bar
// moves, maximize-to-work-area and restore, and the floating geometry that is persisted. Spec: IMMERSIVE-WINDOW.md.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

/** A window rectangle in AWT logical pixels. */
data class WinRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        fun of(r: java.awt.Rectangle) = WinRect(r.x, r.y, r.width, r.height)
    }
    fun toAwt() = java.awt.Rectangle(x, y, width, height)
}

/** The 8 resize hit zones of an undecorated window. */
enum class ResizeEdge(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean, val awtCursor: Int) {
    N(false, true, false, false, java.awt.Cursor.N_RESIZE_CURSOR),
    S(false, false, false, true, java.awt.Cursor.S_RESIZE_CURSOR),
    W(true, false, false, false, java.awt.Cursor.W_RESIZE_CURSOR),
    E(false, false, true, false, java.awt.Cursor.E_RESIZE_CURSOR),
    NW(true, true, false, false, java.awt.Cursor.NW_RESIZE_CURSOR),
    NE(false, true, true, false, java.awt.Cursor.NE_RESIZE_CURSOR),
    SW(true, false, false, true, java.awt.Cursor.SW_RESIZE_CURSOR),
    SE(false, false, true, true, java.awt.Cursor.SE_RESIZE_CURSOR),
}

/**
 * One monitor as AWT reports it on HiDPI Windows: [logical] is `GraphicsConfiguration.bounds` (origin in device
 * pixels, size in logical pixels) and [scaleX]/[scaleY] its default transform. Its device rectangle keeps the origin
 * and multiplies the size.
 */
data class ScreenScale(val logical: WinRect, val scaleX: Double, val scaleY: Double) {
    val device: WinRect get() = WinRect(logical.x, logical.y,
        Math.round(logical.width * scaleX).toInt(), Math.round(logical.height * scaleY).toInt())
}

object WindowGeometry {
    /** Device (physical) rectangle → AWT logical, on the monitor holding its centre (else the one it overlaps most). */
    fun toLogical(phys: WinRect, screens: List<ScreenScale>): WinRect {
        val s = hostArea(phys, screens.map { it.device })?.let { d -> screens.first { it.device == d } } ?: return phys
        return WinRect(
            s.logical.x + Math.round((phys.x - s.logical.x) / s.scaleX).toInt(),
            s.logical.y + Math.round((phys.y - s.logical.y) / s.scaleY).toInt(),
            Math.round(phys.width / s.scaleX).toInt(), Math.round(phys.height / s.scaleY).toInt())
    }

    /** AWT logical rectangle → device (physical), the inverse of [toLogical]. */
    fun toPhysical(r: WinRect, screens: List<ScreenScale>): WinRect {
        val s = hostArea(r, screens.map { it.logical })?.let { l -> screens.first { it.logical == l } } ?: return r
        return WinRect(
            s.logical.x + Math.round((r.x - s.logical.x) * s.scaleX).toInt(),
            s.logical.y + Math.round((r.y - s.logical.y) * s.scaleY).toInt(),
            Math.round(r.width * s.scaleX).toInt(), Math.round(r.height * s.scaleY).toInt())
    }

    /**
     * New bounds while dragging [edge] by (dx, dy) from [start]. Width and height never go below
     * [minW]×[minH]; when a left/top edge hits the minimum, the opposite edge stays put (no drift).
     */
    fun resize(start: WinRect, edge: ResizeEdge, dx: Int, dy: Int, minW: Int, minH: Int): WinRect {
        var x = start.x; var y = start.y; var w = start.width; var h = start.height
        if (edge.right) w = maxOf(minW, start.width + dx)
        if (edge.bottom) h = maxOf(minH, start.height + dy)
        if (edge.left) { w = maxOf(minW, start.width - dx); x = start.x + start.width - w }
        if (edge.top) { h = maxOf(minH, start.height - dy); y = start.y + start.height - h }
        return WinRect(x, y, w, h)
    }

    /** Title-bar drag: same size, moved by (dx, dy). */
    fun move(start: WinRect, dx: Int, dy: Int) = start.copy(x = start.x + dx, y = start.y + dy)

    /** Effective minimum: the app minimum, but never more than the screen can hold. */
    fun effectiveMin(minW: Int, minH: Int, workArea: WinRect?): Pair<Int, Int> =
        if (workArea == null) minW to minH else minOf(minW, workArea.width) to minOf(minH, workArea.height)

    /**
     * Work area the window maximizes onto: the one holding the window's centre, else the one it overlaps
     * most, else the first (primary).
     */
    fun hostArea(window: WinRect, areas: List<WinRect>): WinRect? {
        if (areas.isEmpty()) return null
        val cx = window.x + window.width / 2; val cy = window.y + window.height / 2
        areas.firstOrNull { cx >= it.x && cx < it.x + it.width && cy >= it.y && cy < it.y + it.height }?.let { return it }
        return areas.maxByOrNull { a ->
            val w = minOf(a.x + a.width, window.x + window.width) - maxOf(a.x, window.x)
            val h = minOf(a.y + a.height, window.y + window.height) - maxOf(a.y, window.y)
            if (w > 0 && h > 0) w.toLong() * h else 0L
        } ?: areas.first()
    }
}

/**
 * The window's placement state machine, independent of AWT ([Ops] is the window).
 * - Floating geometry and the maximized flag are kept apart: maximizing never overwrites [floating],
 *   restoring returns exactly to it, and [snapshot] (what window.json stores) is always the floating one.
 * - In immersive mode "maximize" sets the bounds to the work area of the host monitor (the taskbar stays visible).
 * - While maximized, moves and edge resizes are ignored.
 * - With the native frame on Windows ([Ops.osMaximize] true) the OS owns the zoom: maximize / restore are
 *   ShowWindow calls (WM_GETMINMAXINFO pins the maximized rect to the work area), so Win+Up / Win+Down /
 *   snapping all work, and every geometry change, ours or external, comes back through [observed].
 */
class PlacementMachine(private val minW: Int, private val minH: Int) {
    interface Ops {
        fun bounds(): WinRect
        fun setBounds(r: WinRect)
        /** Usable (taskbar-free) areas of all monitors, logical px. */
        fun workAreas(): List<WinRect>
        /** Iconified: its bounds are meaningless (Windows parks it at -32000,-32000). */
        fun minimized(): Boolean = false
        /** Native frame: zoom with the OS (ShowWindow SW_MAXIMIZE). false = not available, the machine sets the bounds. */
        fun osMaximize(): Boolean = false
        /** Native frame: un-zoom with the OS (back to its own normal rect). */
        fun osRestore(): Boolean = false
    }

    var ops: Ops? = null
    var maximized: Boolean = false; private set
    /** Last floating (non-maximized) bounds; null until known. */
    var floating: WinRect? = null; private set
    private var gestureStart: WinRect? = null

    /** Startup / mode switch: where the window is (floating) and whether it should be maximized. */
    fun restoreFrom(saved: WinRect?, maximized: Boolean) { floating = saved; this.maximized = maximized }

    fun maximize() {
        val o = ops ?: return
        if (!maximized && !o.minimized()) floating = o.bounds()
        if (o.osMaximize()) { maximized = true; return }
        val base = floating ?: o.bounds()
        val area = WindowGeometry.hostArea(base, o.workAreas()) ?: return
        maximized = true
        o.setBounds(area)
    }

    fun restore() {
        val o = ops ?: return
        if (!maximized) return
        maximized = false
        if (o.osRestore()) return
        floating?.let(o::setBounds)
    }

    fun toggleMaximize() = if (maximized) restore() else maximize()

    /** Decorated mode: the OS maximized/restored the window. */
    fun osPlacementChanged(isMaximized: Boolean) { maximized = isMaximized }

    /**
     * Native frame: the window's real placement after any change (ours, the OS's, or another program's
     * SetWindowPos). Zoomed = maximized (floating kept); otherwise these bounds are the floating ones.
     */
    fun observed(r: WinRect, zoomed: Boolean, iconic: Boolean) {
        if (iconic) return
        if (zoomed) { maximized = true; return }
        maximized = false
        floating = r
    }

    /** Decorated mode (or any external move while floating): remember the floating bounds. */
    fun observedFloating(r: WinRect) { if (!maximized) floating = r }

    fun beginGesture() { gestureStart = if (maximized) null else ops?.bounds() }
    fun endGesture() { gestureStart = null; if (!maximized) ops?.bounds()?.let { floating = it } }

    fun moveBy(dx: Int, dy: Int) {
        val o = ops ?: return; val s = gestureStart ?: return
        if (maximized) return
        WindowGeometry.move(s, dx, dy).also { o.setBounds(it); floating = it }
    }

    fun resizeBy(edge: ResizeEdge, dx: Int, dy: Int) {
        val o = ops ?: return; val s = gestureStart ?: return
        if (maximized) return
        val (mw, mh) = WindowGeometry.effectiveMin(minW, minH, WindowGeometry.hostArea(s, o.workAreas()))
        WindowGeometry.resize(s, edge, dx, dy, mw, mh).also { o.setBounds(it); floating = it }
    }

    /** What window.json stores: floating bounds + maximized flag. */
    fun snapshot(): WindowPlacement? {
        val o = ops
        val f = (if (maximized || o == null || o.minimized()) floating else o.bounds()) ?: return null
        return WindowPlacement(f.x, f.y, f.width, f.height, maximized)
    }
}
