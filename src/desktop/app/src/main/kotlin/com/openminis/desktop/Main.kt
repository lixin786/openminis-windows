// OpenMinis Desktop — entry point. Windows port of the OpenMinis Android app
// (https://github.com/OpenMinis/OpenMinis), built with Compose Multiplatform for Desktop.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.desktop.i18n.tr

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openminis.app.logging.AppLogger
import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.ui.App
import com.openminis.desktop.ui.AppState
import com.openminis.desktop.ui.AwtWindowOps
import com.openminis.desktop.ui.ImmersiveFrame
import com.openminis.desktop.ui.LocalAppMenus
import com.openminis.desktop.ui.MenuEntry
import com.openminis.desktop.ui.MinisTheme
import com.openminis.desktop.ui.WindowChrome
import com.openminis.desktop.ui.appMenus
import com.openminis.desktop.ui.chromeKey
import com.openminis.desktop.core.PlacementMachine
import com.openminis.desktop.core.WinRect
import com.openminis.desktop.core.WindowPlacement
import com.openminis.desktop.core.WindowPlacements
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.isShiftPressed
import kotlinx.coroutines.flow.collectLatest

fun main(args: Array<String>) {
    // Must be set before the JDK's default ProxySelector is first touched: lets Settings → Network
    // "System" mode read the Windows proxy configuration (WinHTTP / IE settings).
    System.setProperty("java.net.useSystemProxies", "true")
    if (args.contains("--version")) { println("OpenMinis Desktop ${com.openminis.app.BuildConfig.VERSION_NAME}"); return }
    val paths = AppPaths(AppPaths.defaultRoot())
    AppLogger.init(paths.logsDir)
    AppLogger.info("Main", "OpenMinis Desktop starting; data=${paths.root} java=${System.getProperty("java.version")} " +
        "file.encoding=${System.getProperty("file.encoding")} native.encoding=${System.getProperty("native.encoding")} " +
        "sun.jnu.encoding=${System.getProperty("sun.jnu.encoding")} default=${java.nio.charset.Charset.defaultCharset()}")
    Thread.setDefaultUncaughtExceptionHandler { t, e -> AppLogger.error("Main", "uncaught on ${t.name}", e) }

    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(paths, scope).also { com.openminis.desktop.i18n.I18n.apply(it.settings.language) } }
        val placementFile = remember { java.io.File(paths.root, "window.json") }
        val initial = remember {
            WindowPlacements.resolve(WindowPlacements.load(placementFile), WindowPlacements.screens())
                .also { AppLogger.info("Main", "window placement $it (screens=${WindowPlacements.screens()})") }
        }
        // [immersive] Floating geometry and the maximized flag live here, across window re-creations (mode switch).
        val machine = remember {
            PlacementMachine(WindowPlacements.MIN_W, WindowPlacements.MIN_H).also { m ->
                m.restoreFrom(if (initial.x == -1 && initial.y == -1) null else WinRect(initial.x, initial.y, initial.width, initial.height), initial.maximized)
            }
        }
        val closing = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        fun savePlacement() { machine.snapshot()?.let { WindowPlacements.save(placementFile, it) } }
        // Idempotent: Alt+F4 may arrive both from the OS and from our own key handler.
        fun quit() { if (closing.getAndSet(true)) return; savePlacement(); state.shutdown(); exitApplication() }
        // P2-6: "Restart now" after a restore (a manual restart is asked for when the command cannot be re-run).
        state.requestRestart = {
            if (!com.openminis.desktop.core.AppRestart.relaunch()) AppLogger.warning("Main", "relaunch unavailable; quitting for a manual restart")
            quit()
        }

        val immersive = state.settings.immersiveWindow
        // AWT cannot toggle decorations on a live window: the mode switch recreates it at the same geometry.
        key(immersive) {
            val start = remember { machine.floating?.let { WindowPlacement(it.x, it.y, it.width, it.height, machine.maximized) } ?: initial }
            val windowState = rememberWindowState(
                width = start.width.dp, height = start.height.dp,
                position = if (start.x == -1 && start.y == -1) WindowPosition.PlatformDefault else WindowPosition(start.x.dp, start.y.dp),
                // Immersive "maximize" is our own (work area only), so the window itself always starts floating.
                placement = if (!immersive && start.maximized) androidx.compose.ui.window.WindowPlacement.Maximized else androidx.compose.ui.window.WindowPlacement.Floating,
            )
            val chrome = remember { if (immersive) WindowChrome(machine, canMaximize = true) { quit() } else null }
            Window(
                onCloseRequest = { quit() },
                title = "OpenMinis",
                state = windowState,
                undecorated = immersive,
                icon = painterResource("icon.png"),
                onPreviewKeyEvent = { ev ->
                    if (chromeKey(chrome, ev)) true
                    else if (ev.type == KeyEventType.KeyDown && ev.isCtrlPressed) when (ev.key) {
                        Key.N -> { state.newChat(); true }
                        Key.Equals, Key.Plus, Key.NumPadAdd -> { state.zoomText(+1); true }
                        Key.Minus, Key.NumPadSubtract -> { state.zoomText(-1); true }
                        Key.Zero, Key.NumPad0 -> { state.zoomText(0); true }
                        Key.Comma -> { state.showSettings = true; true }
                        // Without the Swing MenuBar (immersive) its accelerators are bound here instead.
                        Key.K -> if (immersive && ev.isShiftPressed) { if (!state.isRunning) state.compactNow(); true } else false
                        Key.F -> if (immersive) { state.searchFocusTick++; true } else false
                        else -> false
                    } else if (immersive && ev.type == KeyEventType.KeyDown && ev.key == Key.F2 && state.selectedId != null) {
                        state.renamingId = state.selectedId; true
                    } else false
                },
            ) {
                window.minimumSize = java.awt.Dimension(
                    minOf(WindowPlacements.MIN_W, start.width),
                    minOf(WindowPlacements.MIN_H, start.height),
                )
                if (chrome != null) LaunchedEffect(Unit) {
                    chrome.attach(window)
                    if (start.maximized) chrome.maximize()
                } else LaunchedEffect(Unit) {
                    // System title bar: the OS maximizes; remember the floating bounds once a move/resize settles.
                    machine.ops = AwtWindowOps(window)
                    snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.collectLatest { (pl, pos, size) ->
                        machine.osPlacementChanged(pl == androidx.compose.ui.window.WindowPlacement.Maximized)
                        if (pl == androidx.compose.ui.window.WindowPlacement.Floating && !windowState.isMinimized && pos is WindowPosition.Absolute) {
                            kotlinx.coroutines.delay(300)
                            if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Floating && !windowState.isMinimized)
                                machine.observedFloating(WinRect(pos.x.value.toInt(), pos.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt()))
                        }
                    }
                }
                // [security] Approvals are only asked while this window exists; otherwise they are denied.
                androidx.compose.runtime.DisposableEffect(Unit) {
                    state.windowAvailable = true
                    onDispose { state.windowAvailable = false }
                }
                // An approval request must not wait unseen behind other windows.
                androidx.compose.runtime.LaunchedEffect(state.pendingApproval) {
                    if (state.pendingApproval != null) {
                        if (window.extendedState and java.awt.Frame.ICONIFIED != 0) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                        window.toFront(); window.requestFocus()
                    }
                }
                val attach = {
                    val chooser = javax.swing.JFileChooser().apply { isMultiSelectionEnabled = true; dialogTitle = tr("attach.dialog_title") }
                    if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) state.attachFiles(chooser.selectedFiles.toList())
                }
                val menus = appMenus(state, attach) { quit() }
                // Immersive: no Swing MenuBar (it would add a second row above our title bar); the menu is a title-bar button.
                if (!immersive) MenuBar {
                    menus.forEach { g ->
                        Menu(g.title, mnemonic = g.mnemonic) {
                            g.entries.forEach { e ->
                                when (e) {
                                    MenuEntry.Separator -> Separator()
                                    is MenuEntry.Action -> Item(
                                        if (e.key == null && e.shortcutText != null) e.label + "\t" + e.shortcutText else e.label,
                                        shortcut = e.key, enabled = e.enabled, onClick = e.onClick,
                                    )
                                }
                            }
                        }
                    }
                }
                MinisTheme(state.settings) {
                    CompositionLocalProvider(LocalAppMenus provides (if (immersive) menus else null)) {
                        ImmersiveFrame(chrome) { App(state) }
                    }
                }
            }
        }
    }
}
