// OpenMinis Desktop — Settings › Sub agents (P1-2): delegation switch, limits, default / light model,
// and the roster editor (upstream Settings › Agents › Sub agents, desktop layout).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.ui

import com.openminis.desktop.ui.icons.Lucide
import com.openminis.desktop.i18n.tr

import androidx.compose.foundation.layout.*
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
    Toggle(tr("sub.enable"), cfg.enabled) { onChange(cfg.copy(enabled = it)) }
    Hint(tr("sub.hint"))
    NumberField(tr("sub.concurrent"), cfg.maxConcurrent, Modifier.fillMaxWidth()) { onChange(cfg.copy(maxConcurrent = it)) }
    NumberField(tr("sub.depth", SubAgentConfig.MAX_DEPTH_LIMIT), cfg.maxDepth, Modifier.fillMaxWidth()) { onChange(cfg.copy(maxDepth = it)) }
    ModelPicker(tr("sub.default_model"), cfg.defaultModel, settings, tr("sub.same_as_conv")) { onChange(cfg.copy(defaultModel = it)) }
    ModelPicker(tr("sub.light_model"), cfg.lightModel, settings, tr("sub.same_as_conv")) { onChange(cfg.copy(lightModel = it)) }
    Hint(tr("sub.choice_hint"))

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
    ) { Text(tr("sub.add")) }
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
                    Text("${if (open) "▾" else "▸"} ${def.displayName()}" + (extras.pinnedModel?.let { " · ${it.label}" } ?: tr("sub.auto")),
                        Modifier.fillMaxWidth())
                }
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Lucide.ArrowUp, tr("common.move_up")) }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(Lucide.ArrowDown, tr("common.move_down")) }
                IconButton(onClick = onDelete, enabled = !def.isBuiltIn) { Icon(Lucide.Trash, tr("sub.delete")) }
            }
            if (open) {
                OutlinedTextField(def.name, { onChange(def.copy(name = it.take(SubAgentLimits.NAME_MAX_LENGTH)), extras) },
                    label = { Text(tr("sub.name")) }, singleLine = true, enabled = !def.isBuiltIn,
                    modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(def.description, { onChange(def.copy(description = it.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH)), extras) },
                    label = { Text(tr("sub.description", SubAgentLimits.DESCRIPTION_MAX_LENGTH)) },
                    modifier = Modifier.fillMaxWidth().tabMovesFocus())
                OutlinedTextField(def.instructions, { onChange(def.copy(instructions = it.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH)), extras) },
                    label = { Text(tr("sub.instructions")) }, minLines = 3,
                    modifier = Modifier.fillMaxWidth())
                ModelPicker(tr("common.model"), extras.pinnedModel, settings, tr("sub.auto_choice")) { onChange(def, extras.copy(pinnedModel = it)) }
                var toolsText by remember(def.id) { mutableStateOf(extras.allowedTools?.joinToString(", ").orEmpty()) }
                OutlinedTextField(toolsText, { t ->
                    toolsText = t
                    val list = t.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    onChange(def, extras.copy(allowedTools = list.ifEmpty { null }))
                }, label = { Text(tr("sub.tools")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tabMovesFocus())
                if (toolNames.isNotEmpty()) Hint(tr("sub.available", toolNames.joinToString(", ")))
            }
        }
    }
}
