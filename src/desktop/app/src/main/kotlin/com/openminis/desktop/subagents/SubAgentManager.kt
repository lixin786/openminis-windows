// OpenMinis Desktop — sub agents (P1-2). Desktop port of upstream agent/jobs (HelperRunner,
// ModelTierResolver, AgentJobRegistry) and the `subagent_task` tool of tools/AgentTools.kt:
// the main agent delegates a self-contained task to a named sub agent, which runs its own
// AgentEngine loop with its own system prompt, tool subset and model, and the sub agent's final
// answer comes back to the parent as the tool result.
//
// Desktop differences (see PORTING.md, P1-2): sub agents run in the FOREGROUND of the parent's
// tool call (upstream default is background + a later [Background task finished …] message), so
// `wait` is always true and steer / cancel / resume are not offered; the user's Stop cancels them.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.subagents

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.desktop.agent.AgentEngine
import com.openminis.desktop.agent.AgentTurnContext
import com.openminis.desktop.agent.ConversationContextState
import com.openminis.desktop.agent.FallbackPolicy
import com.openminis.desktop.agent.RegistryToolbox
import com.openminis.desktop.agent.Toolbox
import com.openminis.desktop.agent.TurnListener
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ChatMessage
import com.openminis.desktop.core.ChatRole
import com.openminis.desktop.core.ModelRef
import com.openminis.desktop.core.ToolStatus
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolOutcome
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Upstream SubAgentModelChoice (ModelTierResolver.kt); wire values identical. */
enum class SubAgentModelChoice(val wire: String) {
    SAME_AS_PARENT("same_as_me"), DEFAULT_MODEL("default_model"), SUB_MODEL("sub_model");
    companion object {
        /** Unknown / absent → the parent's model (upstream: the safe direction). */
        fun parse(raw: String?): SubAgentModelChoice {
            val v = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == v } ?: SAME_AS_PARENT
        }
    }
}

/** Upstream HelperModelOrigin wire values. */
enum class HelperModelOrigin(val wire: String) { PINNED("pinned"), INHERITED("inherited"), DEFAULT_GROUP("default_group"), SUB_GROUP("sub_group") }

data class ModelResolution(val ref: ModelRef, val origin: HelperModelOrigin, val tierUsed: String, val pinnedUnavailable: Boolean = false)

/** Parsed `subagent_task` arguments (upstream HelperRunner.parseArgs). */
data class DelegateArgs(
    val action: String,
    val title: String,
    val task: String,
    val context: String,
    val agentName: String?,
    val modelChoice: SubAgentModelChoice,
    val minutes: Int,
)

/** Live / finished sub agent run, shown in the chat status block and returned by action=status. */
data class SubAgentRun(
    val jobId: String,
    val agent: String,
    val title: String,
    /** queued / running / completed / no_deliverable / timeout / failed / cancelled */
    val state: String,
    val depth: Int,
    val model: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val currentTool: String? = null,
    val latestText: String = "",
    val turns: Int = 0,
) {
    val isActive: Boolean get() = state == "queued" || state == "running"
}

object SubAgentProtocol {
    const val TOOL_NAME = SubAgentDefinition.TOOL_NAME
    /** Upstream HelperRunner constants. */
    const val DEFAULT_MINUTES = 10
    const val MAX_MINUTES = 60
    const val NO_DELIVERABLE = "no_deliverable"
    /** Tools a sub agent never gets (upstream: helpers have no long-term memory writes). */
    val HELPER_EXCLUDED = setOf("memory_write", "scheduled_task")

    fun parseArgs(o: JSONObject): DelegateArgs {
        val task = o.optString("task", "").trim()
        val minutesRaw = if (o.has("max_minutes")) o.optDouble("max_minutes", DEFAULT_MINUTES.toDouble()).toInt() else DEFAULT_MINUTES
        return DelegateArgs(
            action = o.optString("action", "delegate").trim().lowercase().ifEmpty { "delegate" },
            title = o.optString("tool_title", "").trim().ifEmpty { task.take(40) },
            task = task,
            context = o.optString("context", "").trim(),
            agentName = o.optString("agent", "").trim().ifEmpty { null },
            modelChoice = SubAgentModelChoice.parse(o.optString("model_choice", "")),
            minutes = minutesRaw.coerceIn(1, MAX_MINUTES),
        )
    }

    /** Upstream HelperRunner.childPrompt. */
    fun childPrompt(a: DelegateArgs): String =
        if (a.context.isEmpty()) a.task else a.task + "\n\n--- Context from the delegating agent ---\n" + a.context

    fun rejectionJson(reason: String, detail: String): String =
        JSONObject().put("ok", false).put("status", "rejected").put("reason", reason).put("detail", detail).toString()

    /** Upstream HelperRunner.emptyResultNote. */
    fun emptyResultNote(status: String): String =
        "(the agent ended with status $status before writing a final answer; there is no deliverable — re-delegate with a narrower task or a larger budget rather than reading its transcript)"

    /** Upstream HelperRunner.resolvedStatus. */
    fun resolvedStatus(status: String, result: String): String =
        if (status != "completed") status else if (result.isBlank()) NO_DELIVERABLE else status

    /**
     * Upstream `subagent_task` schema (AgentTools.subAgentTaskDefinition) — same name, parameters,
     * enums and required list, so a transcript/model trained on upstream drives it unchanged.
     * The description is upstream's with the background-delivery paragraph replaced by the
     * desktop's foreground contract.
     */
    fun definition(rosterNames: List<String>, maxConcurrent: Int): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Delegate a self-contained task to a sub agent — its own isolated context and tool loop — and inspect the ones you started. `action` defaults to `delegate`.\n\n" +
            "DELEGATE work needing many rounds of exploration (reading lots of files or pages, trial-and-error), producing bulk output you only need a conclusion from, or splitting into independent sub-problems you can run in parallel (several calls in one turn). DO NOT delegate what you can finish in one or two tool calls, what needs the user's confirmation mid-way, or work depending on nuances of this conversation you cannot restate. A sub agent cannot see this conversation and has no memory: write `task` as a complete brief for a capable colleague who just walked in — goal, constraints, where things are, what exactly to return. It costs a full model run, so nothing trivial. Only $maxConcurrent run at once; extra calls in the same turn wait for a slot automatically, so delegate everything you need anyway.\n\n" +
            "On this desktop app the call blocks until the sub agent finishes and returns its final answer here as the result (status completed / no_deliverable / timeout / failed); `wait` is always true and `progress_report` is ignored. Several subagent_task calls in one turn run in parallel. Shell commands a sub agent runs still need the user's approval when confirmation is on.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user on the block in the tool bar and in the transcript (e.g. 'Survey repo test layout', 'Check on the research agent'). Use the same language as the user."),
            "action" to AgentToolParam("string", "\"delegate\" (default): start a sub agent on `task`. \"status\": report the sub agents of this app session — state, current tool, elapsed, model; `job_id` for one, omit for all. \"steer\", \"cancel\", \"resume\": not available on desktop (sub agents run in the foreground; the user's Stop cancels them).", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
            "task" to AgentToolParam("string", "action=delegate only, required. The complete, self-contained brief: goal, success criteria, relevant paths/URLs, constraints, and exactly what to return. The sub agent sees nothing else."),
            "agent" to AgentToolParam("string", "action=delegate only. Which sub agent runs this task. Pick the one whose description matches the work; omit it to use the general one.", enumValues = rosterNames.ifEmpty { listOf(SubAgentDefinition.BUILT_IN_NAME) }),
            "model_choice" to AgentToolParam("string", "action=delegate only, and only when the chosen sub agent is set to Auto — one the user pinned to a model ignores it. DEFAULT TO \"same_as_me\". The user picked the model this conversation runs on, and that choice covers the work you delegate from it. \"same_as_me\" (default): this conversation's model — anything continuing the work at hand, and every case where you are unsure. \"default_model\": the user's strongest model — only when this task clearly needs more capability than the current model. \"sub_model\": the user's light model — only when the task is clearly mechanical and well-bounded, verifiable at a glance.", enumValues = listOf("same_as_me", "default_model", "sub_model")),
            "context" to AgentToolParam("string", "action=delegate only. Optional raw material to hand over verbatim (file excerpts, error output, a list of paths). Appended to the task."),
            "max_minutes" to AgentToolParam("integer", "action=delegate only. Wall-clock budget in minutes (default $DEFAULT_MINUTES, maximum $MAX_MINUTES). The sub agent is stopped when it runs out and whatever it produced so far is returned with status=timeout."),
            "wait" to AgentToolParam("boolean", "action=delegate only. Ignored on desktop: the call always waits for the result."),
            "progress_report" to AgentToolParam("string", "action=delegate only. Ignored on desktop (progress is shown to the user directly).", enumValues = listOf("none", "frequent", "moderate")),
            "job_id" to AgentToolParam("string", "action=status. The job_id of one sub agent (a prefix is accepted); omit to list all."),
            "message" to AgentToolParam("string", "action=steer only. Not available on desktop."),
            "child_session_id" to AgentToolParam("string", "action=resume only. Not available on desktop."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "task", "agent", "model_choice", "context",
            "max_minutes", "wait", "progress_report", "job_id", "message", "child_session_id"),
    )

    /**
     * Upstream HelperRunner.identitySection, with the environment sentence rewritten for the
     * desktop (Windows host, no sandbox, no browser) and the background-delivery wording kept.
     */
    fun identitySection(title: String, maxTurns: Int, instructions: String, mayDelegate: Boolean): String =
        "You are running as a helper (sub-agent) inside an app called Minis, the OpenMinis desktop app, directly on the user's computer (there is no sandbox). This is the calling environment, not your identity — keep your own model identity unchanged.\n" +
            "A parent agent delegated ONE focused task to you: \"$title\". You cannot see the parent conversation and it cannot see yours; everything you need is in the task text. Work the task to completion using your tools, then finish with a single clear final answer — that final message is returned verbatim to the parent agent as the result, and it is the ONLY thing the parent receives. So the final message must contain the complete deliverable itself: the full report, document, list, code or answer the task asked for, in full — never a summary of what you did, a pointer to a file you wrote, or \"see above\". If you produced a file, paste its full content into the final message as well. Do not ask the parent or the user questions; make reasonable assumptions and state them. Do not restate the task or add meta-commentary. Do not create scheduled tasks" +
            (if (mayDelegate) ".\n" else " or delegate further.\n") +
            "You have at most $maxTurns tool rounds. If the task genuinely exceeds what you can do at your capability level, end your final message with a line `[ESCALATE] <one-line reason>` so the parent can rerun it on a stronger model.\n\n" +
            instructionsSection(instructions)

    /** Upstream HelperRunner.subAgentInstructionsSection (delimiter byte-identical). */
    fun instructionsSection(instructions: String): String {
        val t = instructions.trim()
        return if (t.isEmpty()) "" else "\n--- Sub agent instructions (set by the user) ---\n$t\n\n"
    }

    /** Upstream HelperRunner.subAgentRosterSection + systemPromptBullet (desktop wording for delivery). */
    fun rosterSection(cfg: SubAgentConfig): String {
        val roster = cfg.roster
        if (roster.isEmpty()) return ""
        val lines = mutableListOf(
            "- $TOOL_NAME: Delegate a self-contained task to a sub agent that runs its own tool loop in an isolated session. Full contract in the tool schema. While a sub agent runs the user sees it under the chat and can stop it.",
            "Available sub agents (pass the name as $TOOL_NAME.agent):",
        )
        for (def in roster) {
            val desc = def.description.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH).trim()
            val pinned = cfg.extrasFor(def.id).pinnedModel
            val model = if (pinned != null) "fixed — ${pinned.label}" else "Auto — you choose with model_choice"
            lines += "- ${def.name} — $desc Model: $model."
        }
        lines += "Prefer a specific sub agent when its description matches; otherwise use the general one."
        return lines.joinToString("\n")
    }

    fun finalText(m: ChatMessage): String =
        m.rounds.lastOrNull { it.toolCalls.isEmpty() && it.text.isNotBlank() }?.text?.trim()
            ?: m.rounds.lastOrNull { it.text.isNotBlank() }?.text?.trim().orEmpty()
}

/**
 * Runs sub agents. One instance per app (or per headless run). [childRegistry] builds the child's
 * [ToolRegistry] around the approver it is given, so a child's shell commands go through the very
 * same confirmation path as the parent's (labelled with the sub agent's name, one at a time).
 */
class SubAgentManager(
    private val providerFactory: (AppSettings, ModelRef) -> LLMProvider,
    private val config: () -> SubAgentConfig,
    private val childRegistry: (ToolApprover) -> ToolRegistry,
    private val approver: ToolApprover,
    /** System-prompt body (environment, workspace, memory, skills) for a child's settings. */
    private val childPromptBase: (AppSettings) -> String = { "" },
    private val retryDelaysMs: List<Long> = FallbackPolicy.DEFAULT_RETRY_DELAYS_MS,
    private val onProgress: (SubAgentRun) -> Unit = {},
) {
    private val runs = java.util.concurrent.ConcurrentHashMap<String, SubAgentRun>()
    private val order = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val slotLock = Mutex()
    private var active = 0
    /** Child approvals are asked one at a time (parallel children share one dialog). */
    private val approvalLock = Mutex()

    fun snapshot(): List<SubAgentRun> = order.mapNotNull { runs[it] }
    fun activeCount(): Int = runs.values.count { it.isActive }

    /** Wraps a main-loop toolbox with the `subagent_task` tool. */
    fun toolbox(inner: Toolbox, depth: Int = 0): Toolbox = SubAgentToolbox(inner, this, depth)

    /** Extra system-prompt section for a loop at [depth] (empty when delegation is unavailable there). */
    fun promptSection(depth: Int = 0): String {
        val cfg = config()
        return if (cfg.enabled && depth < cfg.effectiveMaxDepth) SubAgentProtocol.rosterSection(cfg) else ""
    }

    internal fun offered(depth: Int): Boolean { val c = config(); return c.enabled && depth < c.effectiveMaxDepth }

    internal fun definition(): AgentToolDefinition {
        val c = config()
        return SubAgentProtocol.definition(c.roster.map { it.name }, c.effectiveMaxConcurrent)
    }

    /** Upstream ModelTierResolver.resolveForSubAgent: pinned outranks model_choice; unusable → inherit. */
    fun resolveModel(def: SubAgentDefinition, choice: SubAgentModelChoice, parent: ModelRef, settings: AppSettings): ModelResolution {
        val cfg = config()
        fun usable(r: ModelRef) = runCatching { providerFactory(settings, r) }.isSuccess
        cfg.extrasFor(def.id).pinnedModel?.let { pinned ->
            return if (usable(pinned)) ModelResolution(pinned, HelperModelOrigin.PINNED, "primary")
            else ModelResolution(parent, HelperModelOrigin.INHERITED, "primary", pinnedUnavailable = true)
        }
        return when (choice) {
            SubAgentModelChoice.SAME_AS_PARENT -> ModelResolution(parent, HelperModelOrigin.INHERITED, "primary")
            SubAgentModelChoice.DEFAULT_MODEL -> cfg.defaultModel?.takeIf(::usable)
                ?.let { ModelResolution(it, HelperModelOrigin.DEFAULT_GROUP, "primary") }
                ?: ModelResolution(parent, HelperModelOrigin.INHERITED, "primary")
            SubAgentModelChoice.SUB_MODEL -> cfg.lightModel?.takeIf(::usable)
                ?.let { ModelResolution(it, HelperModelOrigin.SUB_GROUP, "sub") }
                // Upstream: no Sub group → degrade to primary and say so in tier_used.
                ?: ModelResolution(parent, HelperModelOrigin.INHERITED, "primary")
        }
    }

    /** Child settings: resolved model first, then the parent's whole chain as fallback. */
    fun childSettings(parent: AppSettings, ref: ModelRef, def: SubAgentDefinition): AppSettings {
        val ps = parent.provider(ref.provider)
        var s = parent.withProvider(ref.provider, ps.copy(model = ref.model)).copy(
            activeProvider = ref.provider,
            fallbackModels = parent.modelChain().filter { it != ref },
            toolsEnabled = true,
        )
        def.thinkingLevelOverride?.let { s = s.copy(thinkingLevel = it.name) }
        return s
    }

    suspend fun handle(args: JSONObject, depth: Int): ToolOutcome {
        val a = SubAgentProtocol.parseArgs(args)
        return when (a.action) {
            "status" -> ToolOutcome(statusJson(args.optString("job_id", "").trim()))
            "delegate" -> delegate(a, depth)
            "steer", "cancel", "resume" -> ToolOutcome(SubAgentProtocol.rejectionJson("unsupported_action",
                "action=${a.action} is not available on the desktop app: sub agents run in the foreground of this call and the user's Stop cancels them."), isError = true)
            else -> ToolOutcome(SubAgentProtocol.rejectionJson("unknown_action", "Unknown action '${a.action}'. Use delegate or status."), isError = true)
        }
    }

    private fun statusJson(jobId: String): String {
        val list = snapshot().filter { jobId.isEmpty() || it.jobId.startsWith(jobId) }
        val arr = org.json.JSONArray()
        val now = System.currentTimeMillis()
        list.forEach { r ->
            arr.put(JSONObject().put("job_id", r.jobId).put("agent", r.agent).put("title", r.title).put("state", r.state)
                .put("model", r.model).put("elapsed_s", ((r.finishedAt ?: now) - r.startedAt) / 1000)
                .put("current_tool", r.currentTool ?: JSONObject.NULL).put("turns", r.turns))
        }
        return JSONObject().put("ok", true).put("agents", arr).put("count", list.size).toString()
    }

    private fun publish(r: SubAgentRun) {
        if (!runs.containsKey(r.jobId)) { order.addLast(r.jobId); while (order.size > 50) order.pollFirst()?.let(runs::remove) }
        runs[r.jobId] = r
        runCatching { onProgress(r) }
    }

    private suspend fun acquireSlot() {
        while (true) {
            coroutineContext.ensureActive()
            val ok = slotLock.withLock { if (active < config().effectiveMaxConcurrent) { active++; true } else false }
            if (ok) return
            delay(50)
        }
    }

    private suspend fun releaseSlot() = slotLock.withLock { active = (active - 1).coerceAtLeast(0) }

    private suspend fun delegate(a: DelegateArgs, depth: Int): ToolOutcome {
        val cfg = config()
        val turn = coroutineContext[AgentTurnContext]
            ?: return ToolOutcome(SubAgentProtocol.rejectionJson("no_turn", "subagent_task can only run inside an agent turn."), isError = true)
        if (!cfg.enabled) return ToolOutcome(SubAgentProtocol.rejectionJson("disabled", "Sub agents are turned off in Settings."), isError = true)
        // Depth limit: no unbounded recursion even if a model calls the tool without being offered it.
        if (depth >= cfg.effectiveMaxDepth) return ToolOutcome(SubAgentProtocol.rejectionJson("depth_limit",
            "Delegation depth limit reached (${cfg.effectiveMaxDepth}): do this task yourself."), isError = true)
        if (a.task.isBlank()) return ToolOutcome(SubAgentProtocol.rejectionJson("missing_task", "action=delegate needs a non-empty `task`."), isError = true)
        val roster = cfg.roster
        val def = SubAgentRoster.resolve(a.agentName, roster)
            ?: return ToolOutcome(SubAgentProtocol.rejectionJson("unknown_agent",
                "No sub agent named '${a.agentName}'. Available: ${roster.joinToString { it.name }}."), isError = true)
        val extras = cfg.extrasFor(def.id)
        val res = resolveModel(def, a.modelChoice, turn.currentModel, turn.settings)
        val childSettings = childSettings(turn.settings, res.ref, def)
        val childDepth = depth + 1
        val jobId = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val agentLabel = def.displayName()

        val labelled = ToolApprover { req ->
            approvalLock.withLock { approver.approve(req.copy(summary = "Sub agent «${def.name}»: ${req.summary}")) }
        }
        var toolbox: Toolbox = FilteredToolbox(RegistryToolbox(childRegistry(labelled)), extras.allowedTools)
        val mayDelegate = childDepth < cfg.effectiveMaxDepth && (extras.allowedTools == null || SubAgentProtocol.TOOL_NAME in extras.allowedTools)
        if (mayDelegate) toolbox = SubAgentToolbox(toolbox, this, childDepth)
        val engine = AgentEngine(providerFactory, toolbox, null, retryDelaysMs, depth = childDepth)
        val prompt = SubAgentProtocol.identitySection(a.title, childSettings.maxToolRounds, def.instructions, mayDelegate) +
            childPromptBase(childSettings).lines().dropWhile { it.startsWith("You are Minis") }.joinToString("\n").trim() +
            (if (mayDelegate) "\n\n" + SubAgentProtocol.rosterSection(cfg) else "")

        var run = SubAgentRun(jobId, def.name, a.title, "queued", childDepth, res.ref.label)
        publish(run)
        val latest = AtomicReference<ChatMessage?>(null)
        val started = System.currentTimeMillis()
        var status: String
        var errorDetail: String? = null
        acquireSlot()
        try {
            run = run.copy(state = "running", startedAt = System.currentTimeMillis()); publish(run)
            AppLogger.info(TAG, "[$jobId] start agent=${def.name} depth=$childDepth model=${res.ref.label} origin=${res.origin.wire}")
            val listener = object : TurnListener {
                override fun onUpdate(message: ChatMessage) {
                    latest.set(message)
                    val tool = message.rounds.lastOrNull()?.toolCalls?.lastOrNull { it.status == ToolStatus.RUNNING }?.name
                    run = run.copy(currentTool = tool, latestText = message.displayText.takeLast(800), turns = message.rounds.size)
                    publish(run)
                }
            }
            try {
                val msg = withTimeout(a.minutes * 60_000L) {
                    engine.runTurn(listOf(ChatMessage(role = ChatRole.USER, text = SubAgentProtocol.childPrompt(a))),
                        childSettings, prompt, ConversationContextState(), listener)
                }
                latest.set(msg)
                status = if (msg.error != null) "failed" else "completed"
                errorDetail = msg.error
            } catch (t: TimeoutCancellationException) {
                status = "timeout"
            }
        } catch (e: CancellationException) {
            run = run.copy(state = "cancelled", finishedAt = System.currentTimeMillis(), currentTool = null); publish(run)
            throw e
        } finally {
            releaseSlot()
        }
        val m = latest.get()
        val text = m?.let(SubAgentProtocol::finalText).orEmpty()
        val finalStatus = SubAgentProtocol.resolvedStatus(status, text)
        run = run.copy(state = finalStatus, finishedAt = System.currentTimeMillis(), currentTool = null, turns = m?.rounds?.size ?: 0)
        publish(run)
        AppLogger.info(TAG, "[$jobId] end status=$finalStatus turns=${run.turns}")
        val toolNames = m?.rounds?.flatMap { r -> r.toolCalls.map { it.name } }.orEmpty()
        val j = JSONObject()
            .put("ok", finalStatus == "completed")
            .put("status", finalStatus)
            .put("result", text.ifEmpty { SubAgentProtocol.emptyResultNote(finalStatus) })
            .put("agent", def.name)
            .put("model_used", res.ref.label)
            .put("model_origin", res.origin.wire)
            .put("tier_used", res.tierUsed)
            .put("escalation_requested", text.contains("[ESCALATE]"))
            .put("turns", m?.rounds?.size ?: 0)
            .put("tools_used", org.json.JSONArray(toolNames.distinct()))
            .put("elapsed_s", (System.currentTimeMillis() - started) / 1000)
            .put("job_id", jobId)
            .put("delivered_as", "tool_result")
        if (res.pinnedUnavailable) j.put("model_group_unavailable", true)
        errorDetail?.let { j.put("error_detail", it.take(2000)) }
        if (agentLabel != def.name) j.put("agent_display_name", agentLabel)
        return ToolOutcome(j.toString(), isError = finalStatus != "completed")
    }

    companion object { private const val TAG = "SubAgents" }
}

/** Restricts a toolbox to a sub agent's tool subset (and never memory_write). Enforced on execute too. */
class FilteredToolbox(private val inner: Toolbox, private val allowed: List<String>?) : Toolbox {
    fun isAllowed(name: String): Boolean =
        name !in SubAgentProtocol.HELPER_EXCLUDED && (allowed == null || name in allowed)
    override fun definitions() = inner.definitions().filter { isAllowed(it.name) }
    override suspend fun execute(name: String, args: JSONObject): ToolOutcome =
        if (!isAllowed(name)) ToolOutcome("Error: tool '$name' is not available to this sub agent.", isError = true)
        else inner.execute(name, args)
    override fun runsConcurrently(name: String) = inner.runsConcurrently(name)
}

/** Adds `subagent_task` to a loop at [depth] (when delegation is enabled and the depth allows it). */
class SubAgentToolbox(private val inner: Toolbox, private val manager: SubAgentManager, private val depth: Int) : Toolbox {
    override fun definitions(): List<AgentToolDefinition> =
        inner.definitions() + if (manager.offered(depth)) listOf(manager.definition()) else emptyList()
    override suspend fun execute(name: String, args: JSONObject): ToolOutcome =
        if (name == SubAgentProtocol.TOOL_NAME) manager.handle(args, depth) else inner.execute(name, args)
    override fun runsConcurrently(name: String) = name == SubAgentProtocol.TOOL_NAME || inner.runsConcurrently(name)
}
