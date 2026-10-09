// P1-2 sub agents: subagent_task → child AgentEngine loop → result back to the parent, tool subset,
// shell confirmation inside a child, depth limit, concurrency, cancellation, model resolution, persistence.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.desktop.TestSupport.base
import com.openminis.desktop.TestSupport.openAiText
import com.openminis.desktop.TestSupport.user
import com.openminis.desktop.agent.*
import com.openminis.desktop.core.*
import com.openminis.desktop.subagents.*
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolOutcome
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SubAgentTest {
    private val ws = TestSupport.tmp("om-ws")
    private val mem = MemoryStore(TestSupport.tmp("om-mem"))

    private fun settings(server: MockWebServer, confirm: Boolean = false) =
        AppSettings(activeProvider = ProviderKind.OPENAI_COMPATIBLE, workspaceDir = ws.absolutePath, toolsEnabled = true,
            confirmShellCommands = confirm, memoryEnabled = true)
            .withProvider(ProviderKind.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k", baseUrl = base(server), model = "parent-model"))

    /** One OpenAI chat-completions stream that calls [calls] (name to JSON args) and stops with tool_calls. */
    private fun toolCalls(vararg calls: Pair<String, JSONObject>): MockResponse {
        val events = mutableListOf<String>()
        calls.forEachIndexed { i, (name, args) ->
            events += """{"id":"p","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":$i,"id":"call_$i","type":"function","function":{"name":"$name","arguments":""}}]}}]}"""
            events += """{"id":"p","choices":[{"index":0,"delta":{"tool_calls":[{"index":$i,"function":{"arguments":${JSONObject.quote(args.toString())}}}]}}]}"""
        }
        events += """{"id":"p","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        events += "[DONE]"
        return TestSupport.sse(*events.toTypedArray())
    }

    private fun delegate(task: String, agent: String? = null, choice: String? = null) = JSONObject()
        .put("tool_title", "Test task").put("task", task)
        .also { o -> agent?.let { o.put("agent", it) }; choice?.let { o.put("model_choice", it) } }

    private class Rig(val engine: AgentEngine, val manager: SubAgentManager, val approvals: MutableList<ApprovalRequest>)

    private fun rig(s: () -> AppSettings, cfg: () -> SubAgentConfig, approve: Boolean = true): Rig {
        val approvals = Collections.synchronizedList(mutableListOf<ApprovalRequest>())
        val approver = ToolApprover { approvals += it; approve }
        val factory = { st: AppSettings, ref: ModelRef -> DesktopProviderFactory.create(st, ref) }
        val manager = SubAgentManager(factory, cfg, { a -> ToolRegistry(s, mem, a) }, approver, retryDelaysMs = listOf(1L))
        val engine = AgentEngine(factory, manager.toolbox(RegistryToolbox(ToolRegistry(s, mem, approver))), retryDelaysMs = listOf(1L))
        return Rig(engine, manager, approvals)
    }

    private fun run(r: Rig, s: AppSettings, prompt: String = "sys"): ChatMessage = runBlocking {
        withTimeout(30_000) { r.engine.runTurn(listOf(user("go")), s, prompt, ConversationContextState(), object : TurnListener {}) }
    }

    private fun body(r: RecordedRequest) = JSONObject(r.body.readUtf8())
    private fun toolNames(b: JSONObject): List<String> =
        b.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") } }.orEmpty()
    private fun systemText(b: JSONObject): String = b.getJSONArray("messages").getJSONObject(0).optString("content")
    private fun lastToolResult(b: JSONObject): String {
        val msgs = b.getJSONArray("messages")
        return (msgs.length() - 1 downTo 0).map { msgs.getJSONObject(it) }.first { it.optString("role") == "tool" }.optString("content")
    }

    @Test fun parentDelegatesChildRunsAndResultComesBack() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(toolCalls("subagent_task" to delegate("Count the files")))
        server.enqueue(openAiText("CHILD RESULT: 42 files"))
        server.enqueue(openAiText("The sub agent says 42."))
        val s = settings(server)
        val r = rig({ s }, { SubAgentConfig() })
        val msg = run(r, s)
        assertNull(msg.error, msg.error)
        assertEquals("The sub agent says 42.", msg.rounds.last().text)
        val parent1 = body(server.takeRequest()); val child = body(server.takeRequest()); val parent2 = body(server.takeRequest())
        assertTrue("subagent_task" in toolNames(parent1))
        assertTrue(systemText(child).contains("helper (sub-agent)"), systemText(child).take(300))
        assertEquals("Count the files", child.getJSONArray("messages").getJSONObject(1).optString("content"))
        assertFalse("subagent_task" in toolNames(child), "depth 1 = no further delegation")
        assertFalse("memory_write" in toolNames(child), "helpers never write long-term memory")
        val result = JSONObject(lastToolResult(parent2))
        assertEquals("completed", result.getString("status"))
        assertEquals("CHILD RESULT: 42 files", result.getString("result"))
        assertEquals(SubAgentDefinition.BUILT_IN_NAME, result.getString("agent"))
        assertEquals("inherited", result.getString("model_origin"))
        assertEquals("completed", r.manager.snapshot().single().state)
        server.shutdown()
    }

    @Test fun allowedToolsSubsetIsEnforced() = runBlocking {
        val inner = object : Toolbox {
            override fun definitions() = listOf("shell", "file_read", "memory_write").map {
                com.openminis.app.data.model.AgentToolDefinition(it, "d", emptyMap())
            }
            override suspend fun execute(name: String, args: JSONObject) = ToolOutcome("ran $name")
        }
        val f = FilteredToolbox(inner, listOf("file_read", "memory_write"))
        assertEquals(listOf("file_read"), f.definitions().map { it.name }, "memory_write is excluded even when listed")
        assertTrue(f.execute("shell", JSONObject()).isError)
        assertEquals("ran file_read", f.execute("file_read", JSONObject()).content)
        assertEquals(listOf("shell", "file_read"), FilteredToolbox(inner, null).definitions().map { it.name })
    }

    @Test fun childToolSubsetReachesTheWire() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(toolCalls("subagent_task" to delegate("Read it", agent = "reader")))
        server.enqueue(openAiText("read ok"))
        server.enqueue(openAiText("fine"))
        val s = settings(server)
        val reader = SubAgentDefinition(id = "r1", name = "reader", description = "Reads files")
        val cfg = SubAgentConfig(agents = listOf(SubAgentDefinition.makeBuiltIn(), reader), extras = mapOf("r1" to SubAgentExtras(allowedTools = listOf("file_read", "list_dir"))))
        val r = rig({ s }, { cfg })
        run(r, s)
        server.takeRequest()
        val child = body(server.takeRequest())
        assertEquals(setOf("file_read", "list_dir"), toolNames(child).toSet())
        assertTrue(JSONObject(lastToolResult(body(server.takeRequest()))).getString("agent") == "reader")
        server.shutdown()
    }

    @Test fun childShellCommandStillAsksForApprovalAndHonoursDeny() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(toolCalls("subagent_task" to delegate("Delete stuff")))
        server.enqueue(toolCalls("shell" to JSONObject().put("command", "mkdir SHOULD_NOT_RUN")))
        server.enqueue(openAiText("could not run it"))
        server.enqueue(openAiText("ok"))
        val s = settings(server, confirm = true)
        val r = rig({ s }, { SubAgentConfig() }, approve = false)
        run(r, s)
        val req = r.approvals.single()
        assertEquals("shell", req.toolName)
        assertTrue(req.summary.startsWith("Sub agent «${SubAgentDefinition.BUILT_IN_NAME}»"), req.summary)
        assertTrue(req.detail.contains("SHOULD_NOT_RUN"))
        server.takeRequest(); server.takeRequest()
        val childAfterTool = body(server.takeRequest())
        assertFalse(lastToolResult(childAfterTool).contains("Exit code"), "denied command must not have run")
        server.shutdown()
    }

    @Test fun depthLimitRejectsDelegationBeyondMax() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val s = settings(server)
        val r = rig({ s }, { SubAgentConfig(maxDepth = 1) })
        val out = withContext(AgentTurnContext(s, s.primaryModel(), 1)) { r.manager.handle(delegate("x"), depth = 1) }
        assertTrue(out.isError)
        assertEquals("depth_limit", JSONObject(out.content).getString("reason"))
        assertEquals(0, server.requestCount)
        // Depth 2 allowed: a depth-1 child is offered the tool.
        val deep = SubAgentConfig(maxDepth = 2)
        val r2 = rig({ s }, { deep })
        assertTrue(r2.manager.toolbox(RegistryToolbox(ToolRegistry({ s }, mem) { true }), 1).definitions().any { it.name == "subagent_task" })
        assertFalse(r2.manager.toolbox(RegistryToolbox(ToolRegistry({ s }, mem) { true }), 2).definitions().any { it.name == "subagent_task" })
        assertEquals(SubAgentConfig.MAX_DEPTH_LIMIT, SubAgentConfig(maxDepth = 99).effectiveMaxDepth, "never unbounded")
        server.shutdown()
    }

    @Test fun parallelDelegationsRespectConcurrencyLimit() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(toolCalls("subagent_task" to delegate("A"), "subagent_task" to delegate("B")))
        server.enqueue(openAiText("child done").setBodyDelay(300, TimeUnit.MILLISECONDS))
        server.enqueue(openAiText("child done").setBodyDelay(300, TimeUnit.MILLISECONDS))
        server.enqueue(openAiText("both done"))
        val s = settings(server)
        var maxSeen = 0
        lateinit var mgr: SubAgentManager
        val approver = ToolApprover { true }
        val factory = { st: AppSettings, ref: ModelRef -> DesktopProviderFactory.create(st, ref) }
        mgr = SubAgentManager(factory, { SubAgentConfig(maxConcurrent = 1) }, { a -> ToolRegistry({ s }, mem, a) }, approver,
            retryDelaysMs = listOf(1L), onProgress = { maxSeen = maxOf(maxSeen, mgr.snapshot().count { it.state == "running" }) })
        val engine = AgentEngine(factory, mgr.toolbox(RegistryToolbox(ToolRegistry({ s }, mem, approver))), retryDelaysMs = listOf(1L))
        val msg = runBlocking { withTimeout(30_000) { engine.runTurn(listOf(user("go")), s, "sys", ConversationContextState(), object : TurnListener {}) } }
        assertNull(msg.error, msg.error)
        assertEquals(1, maxSeen, "maxConcurrent=1 → never two running")
        assertEquals(2, mgr.snapshot().count { it.state == "completed" })
        assertEquals(2, msg.rounds.first().toolCalls.count { it.status == ToolStatus.DONE })
        server.shutdown()
    }

    @Test fun stoppingTheParentCancelsTheChild() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(toolCalls("subagent_task" to delegate("slow")))
        server.enqueue(openAiText("too late").setHeadersDelay(20, TimeUnit.SECONDS))
        val s = settings(server)
        val r = rig({ s }, { SubAgentConfig() })
        runBlocking {
            val job = launch(Dispatchers.IO) { r.engine.runTurn(listOf(user("go")), s, "sys", ConversationContextState(), object : TurnListener {}) }
            withTimeout(10_000) { while (r.manager.snapshot().none { it.state == "running" } || server.requestCount < 2) delay(20) }
            val t0 = System.currentTimeMillis()
            job.cancelAndJoin()
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 5_000, "cancel propagates promptly, took ${took}ms")
        }
        assertEquals("cancelled", r.manager.snapshot().single().state)
        runCatching { server.shutdown() } // its handler thread is still sleeping in the headers delay
    }

    @Test fun stopWhileWaitingForHeadersIsPrompt() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(openAiText("too late").setHeadersDelay(20, TimeUnit.SECONDS))
        val s = settings(server)
        val r = rig({ s }, { SubAgentConfig() })
        runBlocking {
            val job = launch(Dispatchers.IO) { r.engine.runTurn(listOf(user("go")), s, "sys", ConversationContextState(), object : TurnListener {}) }
            withTimeout(10_000) { while (server.requestCount < 1) delay(20) }
            delay(200)
            val t0 = System.currentTimeMillis()
            job.cancelAndJoin()
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 5_000, "Stop before response headers must cancel the HTTP call, took ${took}ms")
        }
        runCatching { server.shutdown() } // its handler thread is still sleeping in the headers delay
    }

    @Test fun modelResolutionFollowsUpstreamRules() {
        val server = MockWebServer().also { it.start() }
        val s = settings(server)
        val light = ModelRef(ProviderKind.OPENAI_COMPATIBLE, "light-model")
        val cfg = SubAgentConfig(lightModel = light)
        val r = rig({ s }, { cfg })
        val def = SubAgentDefinition.makeBuiltIn()
        val parent = s.primaryModel()
        assertEquals(light, r.manager.resolveModel(def, SubAgentModelChoice.SUB_MODEL, parent, s).ref)
        assertEquals(parent, r.manager.resolveModel(def, SubAgentModelChoice.DEFAULT_MODEL, parent, s).ref, "no default model configured → inherit")
        assertEquals(parent, r.manager.resolveModel(def, SubAgentModelChoice.parse("bogus"), parent, s).ref)
        // Pinned outranks model_choice; an unusable pin (no API key) degrades to the parent's model.
        val pinnedCfg = SubAgentConfig(extras = mapOf(def.id to SubAgentExtras(pinnedModel = ModelRef(ProviderKind.ANTHROPIC, "claude-x"))))
        val res = rig({ s }, { pinnedCfg }).manager.resolveModel(def, SubAgentModelChoice.SUB_MODEL, parent, s)
        assertEquals(parent, res.ref); assertTrue(res.pinnedUnavailable)
        // The child request really goes to the light model.
        server.enqueue(toolCalls("subagent_task" to delegate("tiny", choice = "sub_model")))
        server.enqueue(openAiText("x"))
        server.enqueue(openAiText("y"))
        run(r, s)
        server.takeRequest()
        assertEquals("light-model", body(server.takeRequest()).getString("model"))
        server.shutdown()
    }

    @Test fun configPersistsAndNormalises() {
        val f = java.io.File(TestSupport.tmp("om-sa"), "subagents.json")
        val store = SubAgentStore(f)
        assertEquals(listOf(SubAgentDefinition.BUILT_IN_ID), store.load().roster.map { it.id }, "default roster = the built-in")
        val custom = SubAgentDefinition(id = "c1", name = "研究员 researcher", description = "调研 🔍")
        store.save(SubAgentConfig(maxConcurrent = 2, agents = listOf(custom), extras = mapOf("c1" to SubAgentExtras(allowedTools = listOf("file_read")), "gone" to SubAgentExtras())))
        val back = store.load()
        assertTrue(back.roster.any { it.id == SubAgentDefinition.BUILT_IN_ID }, "built-in is always restored")
        assertEquals("调研 🔍", back.roster.first { it.id == "c1" }.description)
        assertEquals(setOf("c1"), back.extras.keys, "extras of deleted agents dropped")
        assertEquals(2, back.maxConcurrent)
    }

    @Test fun rosterSectionListsAgentsForTheMainPrompt() {
        val r = rig({ AppSettings() }, { SubAgentConfig() })
        val p = r.manager.promptSection(0)
        assertTrue(p.contains("subagent_task") && p.contains(SubAgentDefinition.BUILT_IN_NAME), p)
        assertEquals("", rig({ AppSettings() }, { SubAgentConfig(enabled = false) }).manager.promptSection(0))
    }
}
