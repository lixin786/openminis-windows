// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.desktop.agent.HistoryMapper
import com.openminis.desktop.core.*
import com.openminis.desktop.skills.SkillLoader
import com.openminis.desktop.skills.SkillMd
import com.openminis.desktop.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class CoreTest {
    private fun tmp(): File = Files.createTempDirectory("om-test").toFile()

    @Test fun historyMapperProducesToolUseAndResultPairs() {
        val history = listOf(
            ChatMessage(role = ChatRole.USER, text = "make a file"),
            ChatMessage(role = ChatRole.ASSISTANT, rounds = listOf(
                Round(text = "Sure.", toolCalls = listOf(ToolCall("t1", "file_write", """{"path":"a.txt","content":"x"}""", result = "Wrote", status = ToolStatus.DONE))),
                Round(text = "Done!"),
            )),
            ChatMessage(role = ChatRole.USER, text = "thanks"),
        )
        val msgs = HistoryMapper.toLLMMessages(history)
        assertEquals(listOf(LLMMessage.Role.USER, LLMMessage.Role.ASSISTANT, LLMMessage.Role.USER, LLMMessage.Role.ASSISTANT, LLMMessage.Role.USER), msgs.map { it.role })
        val toolUse = msgs[1].contentParts.filterIsInstance<AgentContentPart.ToolUse>().single()
        assertEquals("file_write", toolUse.name)
        assertEquals("a.txt", toolUse.input.getString("path"))
        val result = msgs[2].contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
        assertEquals("t1", result.id)
        assertEquals("Done!", msgs[3].content)
    }

    @Test fun historyMapperMergesConsecutiveUserTurns() {
        val msgs = HistoryMapper.toLLMMessages(listOf(
            ChatMessage(role = ChatRole.USER, text = "a"),
            ChatMessage(role = ChatRole.ASSISTANT, error = "boom"),
            ChatMessage(role = ChatRole.USER, text = "b"),
        ))
        assertEquals(1, msgs.size)
        assertEquals("a\n\nb", msgs[0].content)
    }

    @Test fun fileToolsReadWriteEditInsideWorkspace() = runBlocking {
        val ws = tmp(); val mem = tmp()
        val settings = AppSettings(workspaceDir = ws.absolutePath, confirmShellCommands = false)
        val reg = ToolRegistry({ settings }, MemoryStore(mem)) { false }
        assertFalse(reg.execute("file_write", JSONObject().put("path", "sub/hello.txt").put("content", "hello world")).isError)
        assertEquals("hello world", File(ws, "sub/hello.txt").readText())
        val read = reg.execute("file_read", JSONObject().put("path", "sub/hello.txt"))
        assertTrue(read.content.contains("hello world"), read.content)
        assertFalse(reg.execute("file_edit", JSONObject().put("path", "sub/hello.txt").put("old_string", "world").put("new_string", "Windows")).isError)
        assertEquals("hello Windows", File(ws, "sub/hello.txt").readText())
        assertTrue(reg.execute("list_dir", JSONObject()).content.contains("sub/"))
        assertTrue(reg.execute("nope", JSONObject()).isError)
    }

    @Test fun writesOutsideWorkspaceAreRefusedEvenWhenTheUserWouldAllow() = runBlocking {
        // [security] was "need approval"; now a hard refusal — no dialog is even shown.
        val ws = tmp(); val outside = File(tmp(), "x.txt")
        val settings = AppSettings(workspaceDir = ws.absolutePath)
        var asked = false
        val r = ToolRegistry({ settings }, MemoryStore(tmp())) { asked = true; true }
            .execute("file_write", JSONObject().put("path", outside.absolutePath).put("content", "x"))
        assertTrue(r.denied && r.isError, r.content); assertFalse(outside.exists()); assertFalse(asked)
        assertTrue(r.content.contains("outside the workspace"), r.content)
    }

    @Test fun shellToolRespectsConfirmation() = runBlocking {
        val ws = tmp()
        var asked: ApprovalRequest? = null
        val settings = AppSettings(workspaceDir = ws.absolutePath, confirmShellCommands = true)
        val reg = ToolRegistry({ settings }, MemoryStore(tmp())) { asked = it; false }
        val r = reg.execute("shell", JSONObject().put("command", "mkdir hi"))
        assertTrue(r.denied); assertEquals("mkdir hi", asked?.detail)
        if (!AppPaths.isWindows) {
            // `&&` is a metacharacter: it asks even with confirmation off (here: the user allows).
            val run = ToolRegistry({ settings.copy(confirmShellCommands = false) }, MemoryStore(tmp())) { true }
                .execute("shell", JSONObject().put("command", "echo hi && pwd"))
            assertFalse(run.isError, run.content)
            assertTrue(run.content.contains("hi")); assertTrue(run.content.contains("Exit code: 0"))
            val timeout = ToolRegistry({ settings.copy(confirmShellCommands = false) }, MemoryStore(tmp())) { false }
                .execute("shell", JSONObject().put("command", "sleep 5").put("timeout_seconds", 1))
            assertTrue(timeout.isError); assertTrue(timeout.content.contains("Timed out"))
        }
    }

    @Test fun powershellArgvUsesUtf16EncodedCommand() {
        val argv = ShellRunner.buildArgv(ShellKind.POWERSHELL, "Get-ChildItem \"C:\\Program Files\"", File("C:\\ws"), windows = true)
        assertTrue(argv.first().endsWith(".exe"))
        val idx = argv.indexOf("-EncodedCommand")
        val decoded = String(Base64.getDecoder().decode(argv[idx + 1]), Charsets.UTF_16LE)
        assertTrue(decoded.endsWith("Get-ChildItem \"C:\\Program Files\""))
        val wsl = ShellRunner.buildArgv(ShellKind.WSL, "ls -la", File("C:\\ws"), windows = true)
        assertEquals(listOf("wsl.exe", "--cd"), wsl.take(2)); assertEquals("ls -la", wsl.last())
    }

    @Test fun memoryWriteAndSearch() {
        val m = MemoryStore(tmp())
        m.write("## Prefs\nUser likes Kotlin and dark mode")
        assertTrue(m.get("daily", "kotlin").contains("Kotlin"))
        assertTrue(m.get("all", "python").startsWith("No matches found"))
        assertNotNull(m.promptContext())
    }

    @Test fun skillMdParsingAndDiscovery() {
        val root = tmp()
        File(root, "pdf-tools").mkdirs()
        File(root, "pdf-tools/SKILL.md").writeText("\uFEFF---\nname: pdf-tools\ndescription: >-\n  Work with PDF\n  files\n---\n# Body\nUse qpdf.")
        val parsed = SkillMd.parseSkillMd(File(root, "pdf-tools/SKILL.md").readText())!!
        assertEquals("Work with PDF files", parsed.description)
        val loader = SkillLoader { listOf(root) }
        assertEquals(listOf("pdf-tools"), loader.load().map { it.name })
        assertTrue(loader.promptFragment()!!.contains("pdf-tools"))
    }

    @Test fun storesRoundTrip() {
        val dir = tmp()
        val ss = SettingsStore(File(dir, "settings.json"))
        // securityRevision current: a round trip of already-migrated settings is lossless.
        val s = AppSettings(activeProvider = ProviderKind.GEMINI, securityRevision = SettingsStore.SECURITY_REVISION)
            .withProvider(ProviderKind.GEMINI, ProviderSettings(apiKey = "k", model = "gemini-2.5-pro"))
        ss.save(s)
        assertEquals(s, ss.load())
        val cs = ConversationStore(File(dir, "conv"))
        val c = Conversation(title = "Hi", messages = listOf(ChatMessage(role = ChatRole.USER, text = "你好")))
        cs.save(c)
        assertEquals(c, cs.loadAll().single())
        cs.delete(c.id)
        assertTrue(cs.loadAll().isEmpty())
    }

    @Test fun providerFactoryBuildsUpstreamProviders() {
        for (k in ProviderKind.entries) {
            val p = DesktopProviderFactory.create(AppSettings(activeProvider = k).withProvider(k, ProviderSettings(apiKey = "x")))
            assertEquals(ProviderSettings().effectiveModel(k), p.model.id)
        }
        assertFailsWith<IllegalStateException> { DesktopProviderFactory.create(AppSettings(activeProvider = ProviderKind.ANTHROPIC)) }
    }
}
