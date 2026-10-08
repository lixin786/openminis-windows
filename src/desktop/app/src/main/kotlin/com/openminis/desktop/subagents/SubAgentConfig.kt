// OpenMinis Desktop — sub agent roster + delegation settings, persisted as subagents.json.
// The roster itself is the upstream data model (data/model/SubAgentDefinition.kt, SubAgentRoster
// normalize/resolve), kept wire-compatible with the upstream provider-config blob; the desktop-only
// parts (pinned model, allowed tools) live next to it keyed by definition id.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.subagents

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.logging.AppLogger
import com.openminis.desktop.core.ModelRef
import com.openminis.desktop.core.atomicWrite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop additions to one [SubAgentDefinition].
 *
 * [pinnedModel] is the desktop stand-in for upstream `modelGroupId` (the desktop has no model
 * groups): non-null = the user pinned this agent to that provider/model and `model_choice` is
 * ignored, exactly like a pinned group upstream. [allowedTools] = the tool subset this agent may
 * use; null = every tool a sub agent gets by default (all but memory_write, upstream helper rule).
 */
@Serializable
data class SubAgentExtras(
    val pinnedModel: ModelRef? = null,
    val allowedTools: List<String>? = null,
)

@Serializable
data class SubAgentConfig(
    /** Upstream Settings › Agents switch (AgentToolSwitch.AGENTS), on by default. */
    val enabled: Boolean = true,
    /** Upstream "default group" — what `model_choice=default_model` runs on. null = the conversation's model. */
    val defaultModel: ModelRef? = null,
    /** Upstream "sub group" (defaultSubGroupId) — what `model_choice=sub_model` runs on. null = the conversation's model. */
    val lightModel: ModelRef? = null,
    /** Sub agents running at once (upstream: 3); extras wait for a slot (upstream status=queued). */
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    /**
     * How deep delegation may nest. Upstream is fixed at 1 (a helper never sees the delegation
     * tool); the desktop allows raising it to [MAX_DEPTH_LIMIT] but never unbounded.
     */
    val maxDepth: Int = 1,
    val agents: List<SubAgentDefinition> = listOf(SubAgentDefinition.makeBuiltIn()),
    val extras: Map<String, SubAgentExtras> = emptyMap(),
) {
    val roster: List<SubAgentDefinition> get() = SubAgentRoster.normalize(agents)
    fun extrasFor(id: String): SubAgentExtras = extras[id] ?: SubAgentExtras()
    val effectiveMaxConcurrent: Int get() = maxConcurrent.coerceIn(1, 10)
    val effectiveMaxDepth: Int get() = maxDepth.coerceIn(1, MAX_DEPTH_LIMIT)

    /** Normalised copy (upstream roster rules) with extras of deleted agents dropped. */
    fun normalized(): SubAgentConfig {
        val r = SubAgentRoster.normalize(agents) { AppLogger.info("SubAgents", it) }
        val ids = r.map { it.id }.toSet()
        return copy(agents = r, extras = extras.filterKeys { it in ids })
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 3
        const val MAX_DEPTH_LIMIT = 3
    }
}

class SubAgentStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): SubAgentConfig = try {
        if (file.exists()) json.decodeFromString(SubAgentConfig.serializer(), file.readText(Charsets.UTF_8)).normalized()
        else SubAgentConfig()
    } catch (e: Exception) {
        AppLogger.error("SubAgents", "subagents.json unreadable, using defaults", e)
        SubAgentConfig()
    }

    fun save(cfg: SubAgentConfig) = atomicWrite(file, json.encodeToString(SubAgentConfig.serializer(), cfg.normalized()))
}
