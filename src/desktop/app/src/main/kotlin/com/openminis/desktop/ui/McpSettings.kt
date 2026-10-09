// OpenMinis Desktop — Settings › MCP servers (P1-1): list, add / edit / remove, enable, Connect (= start
// now, trust this exact launch — one start only for interpreter launches, show tool count or error), import a Claude-Desktop style `mcpServers` JSON.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.i18n.I18n
import com.openminis.desktop.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.desktop.mcp.McpConfigJson
import com.openminis.desktop.mcp.McpServerConfig

@Composable
internal fun McpSection(state: AppState, servers: List<McpServerConfig>, onChange: (List<McpServerConfig>) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = state.mcpTick // recompose on status changes
    Hint(tr("mcp.hint"))
    servers.forEachIndexed { i, sv ->
        McpServerRow(state, sv,
            onChange = { n -> onChange(servers.mapIndexed { j, o -> if (j == i) n else o }) },
            onDelete = { onChange(servers.filterIndexed { j, _ -> j != i }) })
    }
    Row {
        OutlinedButton(onClick = {
            val n = generateSequence(1) { it + 1 }.map { "server-$it" }.first { name -> servers.none { it.name == name } }
            onChange(servers + McpServerConfig(name = n, command = "npx", args = listOf("-y", "@modelcontextprotocol/server-everything")))
        }) { Text(tr("mcp.add")) }
    }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(importText, { importText = it }, label = { Text(tr("mcp.import_json")) },
        minLines = 2, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = importText.isNotBlank(), onClick = {
            importMsg = try {
                val incoming = McpConfigJson.parseImport(importText)
                onChange(McpConfigJson.merge(servers, incoming)); importText = ""
                tr("mcp.imported", incoming.size, incoming.joinToString { it.name })
            } catch (e: Exception) { tr("mcp.import_failed", e.message) }
        }) { Text(tr("common.import")) }
        importMsg?.let { Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun McpServerRow(state: AppState, sv: McpServerConfig, onChange: (McpServerConfig) -> Unit, onDelete: () -> Unit) {
    var open by remember(sv.createdAt) { mutableStateOf(false) }
    val st = state.mcp.status(sv.name)
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(sv.enabled, { onChange(sv.copy(enabled = it)) })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { open = !open }, modifier = Modifier.weight(1f)) {
                    val stText = when (st.state) {
                        "running" -> tr("mcp.state.running", st.toolCount)
                        else -> I18n.trOr("mcp.state.${st.state}", st.state)
                    }
                    Text("${if (open) "▾" else "▸"} ${sv.name} · $stText", Modifier.fillMaxWidth())
                }
                OutlinedButton(enabled = sv.isStdio, onClick = { state.connectMcp(sv) }) { Text(tr("mcp.connect")) }
                IconButton(onClick = { state.mcp.stop(sv.name); onDelete() }) { Icon(Icons.Default.Delete, tr("mcp.delete")) }
            }
            st.error?.let { Text(it.take(600), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!sv.isStdio && sv.url.isNotBlank()) Hint(tr("mcp.http_kept", sv.url))
            if (sv.isStdio) state.mcp.trustVerdict(sv).takeIf { !it.persistable }?.let { Hint(tr("mcp.asked_every", it.reason)) }
            Text(sv.transportSummary.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) {
                OutlinedTextField(sv.name, { onChange(sv.copy(name = it.trim())) }, label = { Text(tr("common.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(sv.command, { onChange(sv.copy(command = it)) }, label = { Text(tr("mcp.command")) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                var argsText by remember(sv.createdAt) { mutableStateOf(sv.args.joinToString("\n")) }
                OutlinedTextField(argsText, { argsText = it; onChange(sv.copy(args = it.lines().map(String::trim).filter(String::isNotEmpty))) },
                    label = { Text(tr("mcp.args")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                var envText by remember(sv.createdAt) { mutableStateOf(sv.env.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
                OutlinedTextField(envText, { t ->
                    envText = t
                    onChange(sv.copy(env = t.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1) } }.toMap()))
                }, label = { Text(tr("mcp.env")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sv.cwd, { onChange(sv.copy(cwd = it)) }, label = { Text(tr("mcp.cwd")) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(sv.note, { onChange(sv.copy(note = it.take(200))) }, label = { Text(tr("mcp.note")) }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                NumberField(tr("mcp.startup_timeout"), sv.startupTimeoutSeconds ?: 0, Modifier.fillMaxWidth()) { onChange(sv.copy(startupTimeoutSeconds = it.takeIf { v -> v > 0 })) }
                Toggle(tr("mcp.confirm_calls"), sv.confirmToolCalls) { onChange(sv.copy(confirmToolCalls = it)) }
                val tools = state.mcp.tools(sv.name)
                if (tools.isNotEmpty()) Hint(tr("mcp.tools", tools.joinToString(", ") { it.name }))
            }
        }
    }
}
