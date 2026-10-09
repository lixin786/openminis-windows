// OpenMinis Desktop — Settings › MCP servers (P1-1): list, add / edit / remove, enable, Connect (= start
// now, trust this exact launch — one start only for interpreter launches, show tool count or error), import a Claude-Desktop style `mcpServers` JSON.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

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
    Hint("Each server is a program started on this computer (stdio transport) the first time a chat needs it — you are asked before every new or changed command line. Servers started through an interpreter or package runner (python, node, npx, uvx, cmd, pwsh, scripts, .bat/.cmd shims …) are asked on EVERY start and never remembered; only a direct .exe can be trusted permanently. Its tools appear to the agent as mcp__<server>__<tool>. HTTP servers are not supported yet.")
    servers.forEachIndexed { i, sv ->
        McpServerRow(state, sv,
            onChange = { n -> onChange(servers.mapIndexed { j, o -> if (j == i) n else o }) },
            onDelete = { onChange(servers.filterIndexed { j, _ -> j != i }) })
    }
    Row {
        OutlinedButton(onClick = {
            val n = generateSequence(1) { it + 1 }.map { "server-$it" }.first { name -> servers.none { it.name == name } }
            onChange(servers + McpServerConfig(name = n, command = "npx", args = listOf("-y", "@modelcontextprotocol/server-everything")))
        }) { Text("Add MCP server") }
    }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(importText, { importText = it }, label = { Text("Import JSON ({\"mcpServers\": {…}} from Claude Desktop / Cursor, or one entry)") },
        minLines = 2, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = importText.isNotBlank(), onClick = {
            importMsg = try {
                val incoming = McpConfigJson.parseImport(importText)
                onChange(McpConfigJson.merge(servers, incoming)); importText = ""
                "Imported ${incoming.size} server(s): ${incoming.joinToString { it.name }}"
            } catch (e: Exception) { "Import failed: ${e.message}" }
        }) { Text("Import") }
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
                        "running" -> "running · ${st.toolCount} tool(s)"
                        "error" -> "error"
                        else -> st.state
                    }
                    Text("${if (open) "▾" else "▸"} ${sv.name} · $stText", Modifier.fillMaxWidth())
                }
                OutlinedButton(enabled = sv.isStdio, onClick = { state.connectMcp(sv) }) { Text("Connect") }
                IconButton(onClick = { state.mcp.stop(sv.name); onDelete() }) { Icon(Icons.Default.Delete, "Delete MCP server") }
            }
            st.error?.let { Text(it.take(600), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!sv.isStdio && sv.url.isNotBlank()) Hint("HTTP transport (${sv.url}) — kept, but not supported on desktop yet.")
            if (sv.isStdio) state.mcp.trustVerdict(sv).takeIf { !it.persistable }?.let { Hint("Asked on every start (not remembered): ${it.reason}.") }
            Text(sv.transportSummary.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) {
                OutlinedTextField(sv.name, { onChange(sv.copy(name = it.trim())) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(sv.command, { onChange(sv.copy(command = it)) }, label = { Text("Command (npx, uvx, node, C:\\path\\server.exe …)") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                var argsText by remember(sv.createdAt) { mutableStateOf(sv.args.joinToString("\n")) }
                OutlinedTextField(argsText, { argsText = it; onChange(sv.copy(args = it.lines().map(String::trim).filter(String::isNotEmpty))) },
                    label = { Text("Arguments, one per line") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                var envText by remember(sv.createdAt) { mutableStateOf(sv.env.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
                OutlinedTextField(envText, { t ->
                    envText = t
                    onChange(sv.copy(env = t.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1) } }.toMap()))
                }, label = { Text("Environment, KEY=VALUE per line ($\$VAR = take it from the system environment)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sv.cwd, { onChange(sv.copy(cwd = it)) }, label = { Text("Working directory (blank = workspace)") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(sv.note, { onChange(sv.copy(note = it.take(200))) }, label = { Text("Note shown to the agent (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().tabMovesFocus())
                NumberField("Startup timeout (seconds, 0 = 60)", sv.startupTimeoutSeconds ?: 0, Modifier.fillMaxWidth()) { onChange(sv.copy(startupTimeoutSeconds = it.takeIf { v -> v > 0 })) }
                Toggle("Ask before every tool call of this server", sv.confirmToolCalls) { onChange(sv.copy(confirmToolCalls = it)) }
                val tools = state.mcp.tools(sv.name)
                if (tools.isNotEmpty()) Hint("Tools: " + tools.joinToString(", ") { it.name })
            }
        }
    }
}
