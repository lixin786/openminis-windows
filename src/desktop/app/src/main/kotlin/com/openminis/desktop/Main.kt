// OpenMinis Desktop — entry point. Windows port of the OpenMinis Android app
// (https://github.com/OpenMinis/OpenMinis), built with Compose Multiplatform for Desktop.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

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
        val state = remember { AppState(paths, scope) }
        val placementFile = remember { java.io.File(paths.root, "window.json") }
        val placement = remember {
            com.openminis.desktop.core.WindowPlacements.resolve(
                com.openminis.desktop.core.WindowPlacements.load(placementFile), com.openminis.desktop.core.WindowPlacements.screens(),
            ).also { AppLogger.info("Main", "window placement $it (screens=${com.openminis.desktop.core.WindowPlacements.screens()})") }
        }
        val windowState = rememberWindowState(
            width = placement.width.dp, height = placement.height.dp,
            position = if (placement.x == -1 && placement.y == -1) WindowPosition.PlatformDefault else WindowPosition(placement.x.dp, placement.y.dp),
            placement = if (placement.maximized) androidx.compose.ui.window.WindowPlacement.Maximized else androidx.compose.ui.window.WindowPlacement.Floating,
        )
        fun savePlacement() {
            val pos = windowState.position
            val size = windowState.size
            if (pos is WindowPosition.Absolute) com.openminis.desktop.core.WindowPlacements.save(placementFile, com.openminis.desktop.core.WindowPlacement(
                pos.x.value.toInt(), pos.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt(),
                windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized,
            ))
        }
        Window(
            onCloseRequest = { savePlacement(); state.shutdown(); exitApplication() },
            title = "OpenMinis",
            state = windowState,
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.isCtrlPressed) when (ev.key) {
                    Key.N -> { state.newChat(); true }
                    Key.Comma -> { state.showSettings = true; true }
                    else -> false
                } else false
            },
        ) {
            window.minimumSize = java.awt.Dimension(
                minOf(com.openminis.desktop.core.WindowPlacements.MIN_W, placement.width),
                minOf(com.openminis.desktop.core.WindowPlacements.MIN_H, placement.height),
            )
            // An approval request must not wait unseen behind other windows.
            androidx.compose.runtime.LaunchedEffect(state.pendingApproval) {
                if (state.pendingApproval != null) {
                    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    window.toFront(); window.requestFocus()
                }
            }
            MenuBar {
                Menu("File", mnemonic = 'F') {
                    Item("New chat", shortcut = KeyShortcut(Key.N, ctrl = true), onClick = { state.newChat() })
                    Item("Settings…", shortcut = KeyShortcut(Key.Comma, ctrl = true), onClick = { state.showSettings = true })
                    Item("Scheduled tasks…", onClick = { state.showScheduled = true })
                    Separator()
                    Item("Open workspace folder", onClick = { state.openInExplorer(state.settings.workspace()) })
                    Item("Open data folder", onClick = { state.openInExplorer(paths.root) })
                    Separator()
                    Item("Exit", onClick = { savePlacement(); state.shutdown(); exitApplication() })
                }
                Menu("Chat", mnemonic = 'C') {
                    Item("Stop generating", enabled = state.isRunning, onClick = { state.stop() })
                    Item("Regenerate last reply", enabled = !state.isRunning, onClick = { state.regenerate() })
                    Item("Compact context", shortcut = KeyShortcut(Key.K, ctrl = true, shift = true), enabled = !state.isRunning, onClick = { state.compactNow() })
                    Item("Attach files…", onClick = {
                        val chooser = javax.swing.JFileChooser().apply { isMultiSelectionEnabled = true; dialogTitle = "Attach files" }
                        if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) state.attachFiles(chooser.selectedFiles.toList())
                    })
                    Item("Delete chat", onClick = { state.selectedId?.let(state::delete) })
                }
            }
            App(state)
        }
    }
}
