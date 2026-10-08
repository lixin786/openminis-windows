// OpenMinis Desktop — Settings › Sub agents (P1-2): delegation switch, limits, default / light model,
// and the roster editor (upstream Settings › Agents › Sub agents, desktop layout).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ModelRef
import com.openminis.desktop.subagents.SubAgentConfig
import com.openminis.desktop.subagents.SubAgentExtras

/** Models the user already configured (active + fallbacks); null = follow the conversation. */
private fun modelOptions(settings: AppSettings, extra: ModelRef?): List<ModelRef?> =
    (listOf<ModelRef?>(null) + settings.modelChain() + listOfNotNull(extra)).distinct()

@Composable
private fun ModelPicker(label: String, value: ModelRef?, settings: AppSettings, nullLabel: String, onChange: (ModelRef?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(180.dp))
        Dropdown(value, modelOptions(settings, value), { it?.label ?: nullLabel }, onChange)
    }
}

@Composable
internal fun SubAgentSection(cfg: SubAgentConfig, settings: AppSettings, toolNames: List<String>, onChange: (SubAgentConfig) -> Unit) {
    Toggle("Enable sub agents (subagent_task tool)", cfg.enabled) { onChange(cfg.copy(enabled = it)) }
    Hint("The main agent can delegate a self-contained task to a sub agent, which runs its own tool loop and returns its final answer. Shell commands a sub agent runs still ask for your approval when confirmation is on.")
    NumberField("Sub agents running at once (1–10)", cfg.maxConcurrent, Modifier.fillMaxWidth()) { onChange(cfg.copy(maxConcurrent = it)) }
    NumberField("Delegation depth (1 = sub agents cannot delegate further, max ${SubAgentConfig.MAX_DEPTH_LIMIT})", cfg.maxDepth, Modifier.fillMaxWidth()) { onChange(cfg.copy(maxDepth = it)) }
    ModelPicker("Default (strong) model:", cfg.defaultModel, settings, "Same as the conversation") { onChange(cfg.copy(defaultModel = it)) }
    ModelPicker("Light model:", cfg.lightModel, settings, "Same as the conversation") { onChange(cfg.copy(lightModel = it)) }
    Hint("model_choice=default_model / sub_model use these; models come from the active model and the fallback list above.")

    val roster = cfg.roster
    roster.forEachIndexed { i, def ->
        SubAgentEditor(
            def = def, extras = cfg.extrasFor(def.id), settings = settings, toolNames = toolNames,
            canMoveUp = i > 0, canMoveDown = i < roster.lastIndex,
            onChange = { d, x -> onChange(cfg.copy(agents = roster.map { if (it.id == def.id) d else it }, extras = cfg.extras + (def.id to x))) },
            onMove = { delta ->
                val l = roster.toMutableList(); java.util.Collections.swap(l, i, i + delta)
                onChange(cfg.copy(agents = l.mapIndexed { k, d -> d.copy(sortOrder = k) }))
            },
            onDelete = { onChange(cfg.copy(agents = roster.filter { it.id != def.id }, extras = cfg.extras - def.id)) },
        )
    }
    OutlinedButton(
        enabled = roster.size < SubAgentLimits.MAX_COUNT,
        onClick = {
            val n = generateSequence(1) { it + 1 }.map { "agent-$it" }.first { name -> roster.none { it.name.equals(name, true) } }
            onChange(cfg.copy(agents = roster + SubAgentDefinition(name = n, description = "When to use this agent…", sortOrder = roster.size)))
        },
    ) { Text("Add sub agent") }
}

@Composable
private fun SubAgentEditor(
    def: SubAgentDefinition, extras: SubAgentExtras, settings: AppSettings, toolNames: List<String>,
    canMoveUp: Boolean, canMoveDown: Boolean,
    onChange: (SubAgentDefinition, SubAgentExtras) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit,
) {
    var open by remember(def.id) { mutableStateOf(false) }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = !open }, modifier = Modifier.weight(1f)) {
                    Text("${if (open) "▾" else "▸"} ${def.displayName()}" + (extras.pinnedModel?.let { " · ${it.label}" } ?: " · Auto"),
                        Modifier.fillMaxWidth())
                }
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Icons.Default.ArrowUpward, "Move up") }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(Icons.Default.ArrowDownward, "Move down") }
                IconButton(onClick = onDelete, enabled = !def.isBuiltIn) { Icon(Icons.Default.Delete, "Delete sub agent") }
            }
            if (open) {
                OutlinedTextField(def.name, { onChange(def.copy(name = it.take(SubAgentLimits.NAME_MAX_LENGTH)), extras) },
                    label = { Text("Name (what the model passes as agent)") }, singleLine = true, enabled = !def.isBuiltIn,
                    modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(def.description, { onChange(def.copy(description = it.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH)), extras) },
                    label = { Text("Description — when to use it (max ${SubAgentLimits.DESCRIPTION_MAX_LENGTH})") },
                    modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(def.instructions, { onChange(def.copy(instructions = it.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)), extras) },
                    label = { Text("Instructions for the sub agent (optional)") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth())
                ModelPicker("Model:", extras.pinnedModel, settings, "Auto (model_choice)") { onChange(def, extras.copy(pinnedModel = it)) }
                var toolsText by remember(def.id) { mutableStateOf(extras.allowedTools?.joinToString(", ").orEmpty()) }
                OutlinedTextField(toolsText, { t ->
                    toolsText = t
                    val list = t.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    onChange(def, extras.copy(allowedTools = list.ifEmpty { null }))
                }, label = { Text("Allowed tools, comma separated (blank = all except memory_write)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tabMovesFocus())
                if (toolNames.isNotEmpty()) Hint("Available: " + toolNames.joinToString(", "))
            }
        }
    }
}
