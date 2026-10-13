// OpenMinis Desktop — P2-1: Settings › Tools › Browser (switch, one-line risk note, browser path, allowlist).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.desktop.browser.BrowserLocator
import com.openminis.desktop.browser.BrowserPolicy
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.i18n.tr
import com.openminis.desktop.ui.icons.Lucide

@Composable internal fun BrowserSection(d: AppSettings, onChange: (AppSettings) -> Unit) {
    Section(tr("browser.section"))
    Toggle(tr("browser.enable"), d.browserEnabled) { onChange(d.copy(browserEnabled = it)) }
    if (!d.toolsEnabled) { Hint(tr("browser.needs_tools")); return }
    if (!d.browserEnabled) { Hint(tr("browser.off_hint")); return }
    // The decided one-sentence risk statement, shown while the switch is on.
    Hint(tr("browser.risk"))
    val found = remember(d.browserExecutable) { BrowserLocator.find(d.browserExecutable) }
    OutlinedTextField(
        value = d.browserExecutable, onValueChange = { onChange(d.copy(browserExecutable = it)) },
        label = { Text(tr("browser.path")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Hint(if (found != null) tr("browser.found", found.absolutePath) else tr("browser.not_found"))
    Field(tr("browser.allowlist"), tr("browser.allowlist_hint")) {
        if (d.browserAllowlist.isEmpty()) Hint(tr("browser.allowlist_empty"))
        d.browserAllowlist.forEach { dom ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(dom, Modifier.weight(1f))
                IconButton(onClick = { onChange(d.copy(browserAllowlist = d.browserAllowlist - dom)) }) { Icon(Lucide.Trash, tr("common.remove")) }
            }
        }
        var entry by remember { mutableStateOf("") }
        val normalized = BrowserPolicy.normalizeAllowlistEntry(entry)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(entry, { entry = it }, label = { Text(tr("browser.allowlist_add_label")) }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = normalized != null, onClick = {
                normalized?.let { if (it !in d.browserAllowlist) onChange(d.copy(browserAllowlist = d.browserAllowlist + it)) }
                entry = ""
            }) { Text(tr("browser.allowlist_add")) }
        }
        if (entry.isNotBlank() && normalized != null) Hint(tr("browser.allowlist_will_add", normalized))
    }
}
