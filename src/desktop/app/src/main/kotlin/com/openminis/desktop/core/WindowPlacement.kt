// OpenMinis Desktop — initial window size/position: clamp to the usable screen area (HiDPI-aware:
// AWT/Compose work in scaled logical pixels, which is what GraphicsEnvironment reports), center
// on first launch, remember the last placement and only restore it when it still fits a monitor.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File

@Serializable
data class WindowPlacement(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false)

object WindowPlacements {
    const val DEFAULT_W = 1200
    const val DEFAULT_H = 820
    const val MIN_W = 760
    const val MIN_H = 520
    private val json = Json { ignoreUnknownKeys = true }

    fun load(f: File): WindowPlacement? = runCatching { json.decodeFromString(WindowPlacement.serializer(), f.readText()) }.getOrNull()
    fun save(f: File, p: WindowPlacement) = runCatching { atomicWrite(f, json.encodeToString(WindowPlacement.serializer(), p)) }

    /** Usable areas (minus taskbar) of all monitors, in logical pixels; first = primary. */
    fun screens(): List<Rectangle> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = ge.maximumWindowBounds
        val others = ge.screenDevices.map { d ->
            val gc = d.defaultConfiguration
            val b = Rectangle(gc.bounds)
            val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
        }
        listOf(primary) + others.filter { it != primary }
    }.getOrDefault(emptyList())

    /**
     * Where the window goes. [saved] is used when at least 60% of it lies on a current monitor
     * (it is then clamped to that monitor); otherwise the default size, shrunk to 92% of the
     * primary usable area if needed, centered there.
     */
    fun resolve(saved: WindowPlacement?, screens: List<Rectangle>): WindowPlacement {
        val primary = screens.firstOrNull() ?: return saved ?: WindowPlacement(-1, -1, DEFAULT_W, DEFAULT_H)
        if (saved != null) {
            val r = Rectangle(saved.x, saved.y, saved.width, saved.height)
            val host = screens.maxByOrNull { s -> r.intersection(s).let { if (it.isEmpty) 0 else it.width * it.height } }
            val area = host?.let { r.intersection(it) }?.takeIf { !it.isEmpty }?.let { it.width.toLong() * it.height } ?: 0
            // Files written before the immersive-window batch stored the *maximized* size as the floating one.
            // A maximized entry that fills ~the whole monitor gets the default floating size (still maximized).
            if (host != null && saved.maximized && saved.width >= host.width * 0.95 && saved.height >= host.height * 0.95) {
                val w = minOf(DEFAULT_W, (host.width * 0.92).toInt()).coerceAtLeast(minOf(MIN_W, host.width))
                val h = minOf(DEFAULT_H, (host.height * 0.92).toInt()).coerceAtLeast(minOf(MIN_H, host.height))
                return WindowPlacement(host.x + (host.width - w) / 2, host.y + (host.height - h) / 2, w, h, maximized = true)
            }
            if (host != null && area >= 0.6 * saved.width.toLong() * saved.height) {
                val w = saved.width.coerceIn(minOf(MIN_W, host.width), host.width)
                val h = saved.height.coerceIn(minOf(MIN_H, host.height), host.height)
                val x = saved.x.coerceIn(host.x, host.x + host.width - w)
                val y = saved.y.coerceIn(host.y, host.y + host.height - h)
                return WindowPlacement(x, y, w, h, saved.maximized)
            }
        }
        val w = minOf(DEFAULT_W, (primary.width * 0.92).toInt()).coerceAtLeast(minOf(MIN_W, primary.width))
        val h = minOf(DEFAULT_H, (primary.height * 0.92).toInt()).coerceAtLeast(minOf(MIN_H, primary.height))
        return WindowPlacement(primary.x + (primary.width - w) / 2, primary.y + (primary.height - h) / 2, w, h)
    }

    /** Margin kept free around a dialog inside the usable screen area (logical px). */
    const val DIALOG_MARGIN = 24

    /**
     * [batch3 follow-up] Initial placement of a secondary window (Settings, Scheduled tasks):
     * the preferred size shrunk to the usable area of [screen] minus [margin] on every side, never
     * below [minW]×[minH] unless the screen itself is smaller, centered on that screen. A
     * 1366×768 laptop at 150 % is ~910×472 logical px after the taskbar, so an 820 px tall window
     * must come out ≤ 424 px tall — the content then scrolls. Pure function (unit-tested).
     */
    fun dialogPlacement(prefW: Int, prefH: Int, screen: Rectangle?, margin: Int = DIALOG_MARGIN, minW: Int = 420, minH: Int = 300): WindowPlacement {
        if (screen == null || screen.width <= 0 || screen.height <= 0) return WindowPlacement(-1, -1, prefW, prefH)
        val m = margin.coerceAtLeast(0)
        val availW = (screen.width - 2 * m).coerceAtLeast(1)
        val availH = (screen.height - 2 * m).coerceAtLeast(1)
        val w = prefW.coerceAtMost(availW).coerceAtLeast(minOf(minW, availW))
        val h = prefH.coerceAtMost(availH).coerceAtLeast(minOf(minH, availH))
        return WindowPlacement(screen.x + (screen.width - w) / 2, screen.y + (screen.height - h) / 2, w, h)
    }

    /** Usable area of the monitor the active window is on (else the primary one), logical px. */
    fun activeScreen(): Rectangle? = runCatching {
        if (GraphicsEnvironment.isHeadless()) return null
        val gc = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.graphicsConfiguration
            ?: return screens().firstOrNull()
        val b = Rectangle(gc.bounds)
        val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
    }.getOrNull() ?: screens().firstOrNull()
}
