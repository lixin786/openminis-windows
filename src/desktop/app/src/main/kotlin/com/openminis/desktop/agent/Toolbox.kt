// OpenMinis Desktop — the tool surface one agent loop sees. Lets the main conversation, a sub agent
// (restricted tool subset, depth-limited delegation) and a headless scheduled run share one
// AgentEngine while each gets its own view of the tools.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.agent

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ModelRef
import com.openminis.desktop.tools.ToolOutcome
import com.openminis.desktop.tools.ToolRegistry
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

interface Toolbox {
    fun definitions(): List<AgentToolDefinition>
    suspend fun execute(name: String, args: JSONObject): ToolOutcome
    /** Calls of this tool issued in the same round may run concurrently (sub agent delegations). */
    fun runsConcurrently(name: String): Boolean = false
}

/** The plain [ToolRegistry] as a [Toolbox]. */
class RegistryToolbox(val registry: ToolRegistry) : Toolbox {
    override fun definitions() = registry.definitions()
    override suspend fun execute(name: String, args: JSONObject) = registry.execute(name, args)
}

/**
 * Coroutine-context element the engine installs around every tool execution, so a tool can learn
 * which turn it runs in: the settings of the turn, the model the turn is on right now (upstream
 * `model_choice=same_as_me`), and how deep in the delegation tree the loop is (0 = main chat).
 */
class AgentTurnContext(
    val settings: AppSettings,
    val currentModel: ModelRef,
    val depth: Int,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentTurnContext>
}
