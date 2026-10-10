// OpenMinis Desktop — immersive window chrome: self-drawn title bar pieces (drag region, double-click
// maximize, right-click menu, min/max/close), 8-way edge resizing and the 1 px frame. IMMERSIVE-WINDOW.md.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.openminis.desktop.core.PlacementMachine
import com.openminis.desktop.core.ResizeEdge
import com.openminis.desktop.core.WinRect
import com.openminis.desktop.core.WindowPlacements
import com.openminis.desktop.i18n.tr
import com.openminis.desktop.ui.icons.Lucide
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

/**
 * One immersive window's chrome. [machine] owns the geometry; this class mirrors what the UI needs as
 * Compose state and talks to AWT. [canMaximize] is false for Settings / Scheduled tasks (close only).
 */
class WindowChrome(
    val machine: PlacementMachine,
    val canMaximize: Boolean,
    private val onClose: () -> Unit,
) {
    var maximized by mutableStateOf(machine.maximized); private set
    var active by mutableStateOf(true)
    /** Alt+Space or a right-click opens the title-bar menu at this offset (px, inside the drag region). */
    var menuAt by mutableStateOf<Offset?>(null)
    /** Which drag region owns the open menu (the main window has two: sidebar header and top bar). */
    var menuRegion by mutableStateOf(TITLE_REGION_TOP)
    /** Alt+Space: the window menu at the top-left of the main title region. */
    fun openMenu(region: String = TITLE_REGION_TOP) { menuRegion = region; menuAt = Offset(8f, 8f) }
    private var window: java.awt.Window? = null

    private fun sync() { maximized = machine.maximized }

    fun maximize() { if (canMaximize) { machine.maximize(); sync() } }
    fun restore() { machine.restore(); sync() }
    fun toggleMaximize() { if (canMaximize) { machine.toggleMaximize(); sync() } }
    fun minimize() { (window as? Frame)?.let { it.extendedState = it.extendedState or Frame.ICONIFIED } }
    fun close() = onClose()
    fun beginGesture() = machine.beginGesture()
    fun endGesture() = machine.endGesture()
    fun moveBy(dx: Int, dy: Int) = machine.moveBy(dx, dy)
    fun resizeBy(edge: ResizeEdge, dx: Int, dy: Int) = machine.resizeBy(edge, dx, dy)

    /** Binds to the real window (called once it exists); tracks focus for the dimmed inactive state. */
    fun attach(w: java.awt.Window) {
        window = w
        machine.ops = AwtWindowOps(w)
        active = w.isActive || w.isFocused
        w.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowActivated(e: java.awt.event.WindowEvent?) { active = true }
            override fun windowDeactivated(e: java.awt.event.WindowEvent?) { active = false }
        })
        sync()
    }

    /** For off-screen rendering (screenshots, tests). */
    fun attachForPreview(ops: PlacementMachine.Ops) { machine.ops = ops; sync() }
}

/** The AWT window behind [PlacementMachine.Ops]. Bounds are logical px (what AWT uses on HiDPI Windows). */
class AwtWindowOps(private val w: java.awt.Window) : PlacementMachine.Ops {
    override fun bounds() = WinRect.of(w.bounds)
    override fun minimized() = (w as? Frame)?.let { it.extendedState and Frame.ICONIFIED != 0 } ?: false
    override fun setBounds(r: WinRect) { w.setBounds(r.x, r.y, r.width, r.height); w.validate() }
    override fun workAreas(): List<WinRect> = runCatching {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val own = w.graphicsConfiguration
        val all = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
        (listOf(own) + all.filter { it != own }).map { gc ->
            val b = gc.bounds; val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
            WinRect(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
        }.distinct()
    }.getOrDefault(emptyList()).ifEmpty { WindowPlacements.screens().map(WinRect::of) }
}

/** Set by the window hosting the content; null = system title bar (nothing self-drawn). */
val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

private fun PointerEvent.screenPoint(): java.awt.Point? = awtEventOrNull?.locationOnScreen

/**
 * Title-bar drag region: drag moves the window (ignored while maximized), double-click toggles
 * maximize, right-click opens the window menu. Put it only on blank title-bar space, never on controls.
 */
fun Modifier.titleBarDrag(chrome: WindowChrome?, region: String = TITLE_REGION_TOP): Modifier = if (chrome == null) this else this.pointerInput(chrome, region) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            if (ev.type != PointerEventType.Press || ev.changes.any { it.isConsumed }) continue
            val pos = ev.changes.firstOrNull()?.position ?: Offset.Zero
            if (ev.buttons.isSecondaryPressed) { chrome.menuRegion = region; chrome.menuAt = pos; ev.changes.forEach { it.consume() }; continue }
            if (!ev.buttons.isPrimaryPressed) continue
            if ((ev.awtEventOrNull?.clickCount ?: 1) >= 2) { chrome.toggleMaximize(); ev.changes.forEach { it.consume() }; continue }
            val start = ev.screenPoint() ?: continue
            chrome.beginGesture()
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == PointerEventType.Release || !e.buttons.isPrimaryPressed) break
                if (e.type == PointerEventType.Move) e.screenPoint()?.let { p -> chrome.moveBy(p.x - start.x, p.y - start.y) }
            }
            chrome.endGesture()
        }
    }
}

/** Minimize / maximize-restore / close, flush right, Windows order and sizes (46 dp wide). */
@Composable
fun CaptionButtons(chrome: WindowChrome, modifier: Modifier = Modifier) {
    Row(modifier.alpha(if (chrome.active) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
        if (chrome.canMaximize) {
            CaptionButton(Lucide.Minus, tr("window.minimize"), danger = false) { chrome.minimize() }
            CaptionButton(if (chrome.maximized) Lucide.Copy else Lucide.Square, tr(if (chrome.maximized) "window.restore" else "window.maximize"), danger = false) {
                chrome.toggleMaximize()
            }
        }
        CaptionButton(Lucide.X, tr("window.close"), danger = true) { chrome.close() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptionButton(icon: ImageVector, label: String, danger: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val cs = MaterialTheme.colorScheme
    val bg = when {
        danger && pressed -> cs.error.copy(alpha = 0.8f)
        danger && hovered -> cs.error
        pressed -> cs.onSurface.copy(alpha = 0.16f)
        hovered -> cs.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val tint = if (danger && (hovered || pressed)) cs.onError else cs.onSurface
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        Box(
            Modifier.width(46.dp).fillMaxHeight().background(bg)
                .hoverable(src)
                .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(15.dp), tint = tint) }
    }
}

/** The window menu (right-click on the title bar, or Alt+Space): Minimize / Maximize / Restore / Close. */
@Composable
fun TitleBarMenu(chrome: WindowChrome, region: String = TITLE_REGION_TOP) {
    val at = chrome.menuAt ?: return
    if (chrome.menuRegion != region) return
    val d = LocalDensity.current
    Box(Modifier.offset(with(d) { at.x.toDp() }, with(d) { at.y.toDp() })) {
        DropdownMenu(expanded = true, onDismissRequest = { chrome.menuAt = null }, offset = DpOffset(0.dp, 0.dp)) {
            fun go(f: () -> Unit) { chrome.menuAt = null; f() }
            if (chrome.canMaximize) {
                DropdownMenuItem(text = { Text(tr("window.minimize")) }, leadingIcon = { Icon(Lucide.Minus, null, Modifier.size(15.dp)) }, onClick = { go(chrome::minimize) })
                DropdownMenuItem(text = { Text(tr("window.maximize")) }, leadingIcon = { Icon(Lucide.Square, null, Modifier.size(15.dp)) },
                    enabled = !chrome.maximized, onClick = { go(chrome::maximize) })
                DropdownMenuItem(text = { Text(tr("window.restore")) }, leadingIcon = { Icon(Lucide.Copy, null, Modifier.size(15.dp)) },
                    enabled = chrome.maximized, onClick = { go(chrome::restore) })
                HorizontalDivider()
            }
            DropdownMenuItem(text = { Text(tr("window.close")) }, leadingIcon = { Icon(Lucide.X, null, Modifier.size(15.dp)) },
                trailingIcon = { Text(ALT_F4, style = MaterialTheme.typography.labelSmall) }, onClick = { go(chrome::close) })
        }
    }
}

/**
 * Wraps a window's content: a 1 px frame while floating, and 8 resize zones (edges 5 dp, corners 10 dp)
 * on top, removed while maximized. [chrome] null = system title bar, content as is.
 */
@Composable
fun ImmersiveFrame(chrome: WindowChrome?, content: @Composable () -> Unit) {
    if (chrome == null) { content(); return }
    CompositionLocalProvider(LocalWindowChrome provides chrome) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(
                if (chrome.maximized) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (chrome.active) 1f else 0.6f))
            )) { content() }
            if (!chrome.maximized) ResizeHandles(chrome)
        }
    }
}

private const val ALT_F4 = "Alt+F4"
const val TITLE_REGION_TOP = "top"
const val TITLE_REGION_SIDE = "side"
const val TITLE_REGION_DIALOG = "dialog"

/** Immersive mode only: the File / View / Chat menu, folded into one title-bar button (no second menu row). */
val LocalAppMenus = staticCompositionLocalOf<List<MenuGroup>?> { null }

@Composable
fun AppMenuButton(groups: List<MenuGroup>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(34.dp)) { Icon(Lucide.Menu, tr("window.app_menu"), Modifier.size(18.dp)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            groups.forEachIndexed { gi, g ->
                if (gi > 0) HorizontalDivider()
                Text(g.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
                g.entries.forEach { e ->
                    when (e) {
                        is MenuEntry.Separator -> HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                        is MenuEntry.Action -> DropdownMenuItem(
                            text = { Text(e.label) }, enabled = e.enabled,
                            trailingIcon = e.shortcutText?.let { sc -> { Text(sc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            onClick = { open = false; e.onClick() },
                        )
                    }
                }
            }
        }
    }
}
private const val EDGE_DP = 5
private const val CORNER_DP = 10

@Composable
private fun BoxScope.ResizeHandles(chrome: WindowChrome) {
    val e = EDGE_DP.dp; val c = CORNER_DP.dp
    Zone(chrome, ResizeEdge.N, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(e).padding(horizontal = c))
    Zone(chrome, ResizeEdge.S, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(e).padding(horizontal = c))
    Zone(chrome, ResizeEdge.W, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(e).padding(vertical = c))
    Zone(chrome, ResizeEdge.E, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(e).padding(vertical = c))
    Zone(chrome, ResizeEdge.NW, Modifier.align(Alignment.TopStart).size(c))
    Zone(chrome, ResizeEdge.NE, Modifier.align(Alignment.TopEnd).size(c))
    Zone(chrome, ResizeEdge.SW, Modifier.align(Alignment.BottomStart).size(c))
    Zone(chrome, ResizeEdge.SE, Modifier.align(Alignment.BottomEnd).size(c))
}

@Composable
private fun Zone(chrome: WindowChrome, edge: ResizeEdge, modifier: Modifier) {
    Box(modifier
        .pointerHoverIcon(PointerIcon(java.awt.Cursor(edge.awtCursor)), overrideDescendants = true)
        .pointerInput(chrome, edge) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type != PointerEventType.Press || !ev.buttons.isPrimaryPressed) continue
                    val start = ev.screenPoint() ?: continue
                    ev.changes.forEach { it.consume() }
                    chrome.beginGesture()
                    while (true) {
                        val m = awaitPointerEvent()
                        m.changes.forEach { it.consume() }
                        if (m.type == PointerEventType.Release || !m.buttons.isPrimaryPressed) break
                        if (m.type == PointerEventType.Move) m.screenPoint()?.let { p -> chrome.resizeBy(edge, p.x - start.x, p.y - start.y) }
                    }
                    chrome.endGesture()
                }
            }
        })
}

/** Title row for Settings / Scheduled tasks in immersive mode: title as the drag region, close on the right. */
@Composable
fun DialogTitleBar(title: String) {
    val chrome = LocalWindowChrome.current ?: return
    Row(Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().titleBarDrag(chrome, TITLE_REGION_DIALOG), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (chrome.active) 1f else 0.6f),
                modifier = Modifier.padding(start = 14.dp))
            TitleBarMenu(chrome, TITLE_REGION_DIALOG)
        }
        CaptionButtons(chrome, Modifier.fillMaxHeight())
    }
    HorizontalDivider()
}

/** Chrome for a Settings / Scheduled-tasks window, or null in system-title-bar mode. Attach it with [AttachChrome]. */
@Composable
fun rememberDialogChrome(immersive: Boolean, onClose: () -> Unit): WindowChrome? {
    val close by rememberUpdatedState(onClose)
    return remember(immersive) { if (immersive) WindowChrome(PlacementMachine(420, 300), canMaximize = false) { close() } else null }
}

@Composable
fun AttachChrome(chrome: WindowChrome?, window: java.awt.Window) {
    LaunchedEffect(chrome, window) { chrome?.attach(window) }
}

/**
 * Keys the OS handled for us before: Alt+F4 closes, Alt+Space opens the window menu. Only consulted in
 * immersive mode; closing is idempotent, so an OS-level Alt+F4 arriving as well is harmless.
 */
fun chromeKey(chrome: WindowChrome?, ev: androidx.compose.ui.input.key.KeyEvent, region: String = TITLE_REGION_TOP): Boolean {
    if (chrome == null || ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !ev.isAltPressed) return false
    return when (ev.key) {
        androidx.compose.ui.input.key.Key.F4 -> { chrome.close(); true }
        androidx.compose.ui.input.key.Key.Spacebar -> { chrome.openMenu(region); true }
        else -> false
    }
}
