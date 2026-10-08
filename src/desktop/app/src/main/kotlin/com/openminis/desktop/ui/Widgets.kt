// OpenMinis Desktop — small shared widgets.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed

@Composable
fun <T> Dropdown(
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String = "",
    enabled: (T) -> Boolean = { true },
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(prefix + label(value), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.ArrowDropDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(label(opt)) },
                    enabled = enabled(opt),
                    onClick = { open = false; onSelect(opt) },
                )
            }
        }
    }
}

/**
 * Tab / Shift+Tab leave a text field and move focus (Compose text fields would otherwise insert a
 * tab or swallow it), so every screen can be driven from the keyboard.
 */
fun Modifier.tabMovesFocus(): Modifier = composed {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    onPreviewKeyEvent { ev ->
        if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.Tab && !ev.isCtrlPressed) {
            fm.moveFocus(if (ev.isShiftPressed) androidx.compose.ui.focus.FocusDirection.Previous else androidx.compose.ui.focus.FocusDirection.Next); true
        } else false
    }
}
