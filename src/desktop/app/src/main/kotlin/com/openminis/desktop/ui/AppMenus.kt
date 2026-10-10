// OpenMinis Desktop — the app menu as data, rendered either as the Swing MenuBar (system title bar mode)
// or as the title-bar menu button (immersive mode), so the two can never drift apart. IMMERSIVE-WINDOW.md.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import com.openminis.desktop.i18n.tr

sealed interface MenuEntry {
    /** [shortcutText] is shown; [key] is also bound as an accelerator by the Swing MenuBar. */
    data class Action(val label: String, val shortcutText: String?, val key: KeyShortcut?, val enabled: Boolean, val onClick: () -> Unit) : MenuEntry
    data object Separator : MenuEntry
}

data class MenuGroup(val title: String, val mnemonic: Char, val entries: List<MenuEntry>)

/** File / View / Chat. [attach] opens the file chooser (needs the window), [exit] saves the placement and quits. */
fun appMenus(state: AppState, attach: () -> Unit, exit: () -> Unit): List<MenuGroup> {
    fun a(label: String, shortcut: String? = null, key: KeyShortcut? = null, enabled: Boolean = true, onClick: () -> Unit) =
        MenuEntry.Action(label, shortcut, key, enabled, onClick)
    return listOf(
        MenuGroup(tr("menu.file"), 'F', listOf(
            a(tr("menu.new_chat"), "Ctrl+N", KeyShortcut(Key.N, ctrl = true)) { state.newChat() },
            a(tr("menu.settings"), "Ctrl+,", KeyShortcut(Key.Comma, ctrl = true)) { state.showSettings = true },
            a(tr("menu.scheduled")) { state.showScheduled = true },
            MenuEntry.Separator,
            a(tr("menu.open_workspace")) { state.openInExplorer(state.settings.workspace()) },
            a(tr("menu.open_data")) { state.openInExplorer(state.paths.root) },
            MenuEntry.Separator,
            a(tr("menu.exit"), onClick = exit),
        )),
        MenuGroup(tr("menu.view"), 'V', listOf(
            a(tr("menu.zoom_in"), "Ctrl+=") { state.zoomText(+1) },
            a(tr("menu.zoom_out"), "Ctrl+-") { state.zoomText(-1) },
            a(tr("menu.zoom_reset"), "Ctrl+0") { state.zoomText(0) },
        )),
        MenuGroup(tr("menu.chat"), 'C', listOf(
            a(tr("menu.stop"), enabled = state.isRunning) { state.stop() },
            a(tr("menu.regenerate"), enabled = !state.isRunning) { state.regenerate() },
            a(tr("menu.compact"), "Ctrl+Shift+K", KeyShortcut(Key.K, ctrl = true, shift = true), enabled = !state.isRunning) { state.compactNow() },
            a(tr("menu.attach"), onClick = attach),
            a(tr("menu.rename_chat"), "F2", KeyShortcut(Key.F2), enabled = state.selectedId != null) { state.renamingId = state.selectedId },
            a(tr(if (state.selected?.pinned == true) "menu.unpin_chat" else "menu.pin_chat"), enabled = state.selectedId != null) {
                state.selectedId?.let(state::togglePin)
            },
            a(tr("menu.search_chats"), "Ctrl+F", KeyShortcut(Key.F, ctrl = true)) { state.searchFocusTick++ },
            a(tr("menu.delete_chat")) { state.selectedId?.let(state::delete) },
        )),
    )
}
