// Security hardening batch — MCP launches started through an interpreter are never trusted permanently
// (user decision 2026-10-09: same rule as the shell; only a direct native executable keeps its fingerprint).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpTrustPolicyTest {
    private fun tmp() = Files.createTempDirectory("om-mtp").toFile().canonicalFile
    private fun posix(cmd: String, probe: McpTrustPolicy.Probe? = null) = McpTrustPolicy.isPersistable(cmd, isWindows = false, probe = probe)
    private fun win(cmd: String, probe: McpTrustPolicy.Probe? = null) = McpTrustPolicy.isPersistable(cmd, isWindows = true, probe = probe)

    @Test fun interpretersAreNeverPersistable() {
        for (c in listOf("python", "python3", "python3.12", "/usr/bin/python3.11", "node", "node22", "nodejs", "deno", "bun",
                "sh", "bash", "zsh", "perl", "ruby", "php", "java", "pwsh", "pwsh-preview", "powershell", "cmd", "wsl"))
            assertFalse(posix(c), c)
        for (c in listOf("C:\\Python312\\python.exe", "C:\\Program Files\\nodejs\\node.exe", "pwsh.exe", "powershell.exe",
                "cmd.exe", "C:\\Windows\\System32\\wscript.exe", "cscript.exe", "mshta.exe", "java.exe"))
            assertFalse(win(c), c)
    }

    @Test fun packageRunnersAndScriptsAreNeverPersistable() {
        for (c in listOf("npx", "npm", "pnpm", "yarn", "bunx", "uvx", "uv", "pipx", "poetry", "tsx", "ts-node", "docker-compose"))
            assertFalse(posix(c), c)
        for (c in listOf("server.py", "./run.sh", "index.js", "srv.mjs", "tool.jar", "x.rb", "a.ps1"))
            assertFalse(posix(c), c)
        for (c in listOf("C:\\tools\\run.bat", "C:\\tools\\run.cmd", "x.vbs", "x.ps1", "npx.cmd", "uvx.exe", "C:\\nvm\\npx.exe"))
            assertFalse(win(c), c)
    }

    @Test fun windowsCommandWithoutExeGoesThroughCmdAndIsNotPersistable() {
        assertFalse(win("mcp-server-git"), "extensionless → cmd.exe /d /c shim")
        assertFalse(win("C:\\tools\\srv"))
        assertTrue(win("C:\\Program Files\\srv\\srv.exe"))
        assertTrue(win("srv.COM"))
    }

    @Test fun directNativeExecutablesArePersistable() {
        assertTrue(posix("/usr/local/bin/mcp-server-git"))
        assertTrue(posix("github-mcp-server"))
        assertTrue(win("C:\\Users\\张三\\bin\\github-mcp-server.exe"))
    }

    @Test fun metacharactersInTheProgramAreNeverPersistable() {
        assertFalse(posix("srv;rm"))
        assertFalse(win("srv.exe & calc"))
    }

    private fun symlinkOrSkip(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath()); true
    } catch (e: Exception) { println("SKIP symlink test: ${e.javaClass.simpleName}: ${e.message}"); false }

    @Test fun symlinkToAnInterpreterIsNotPersistable() {
        val d = tmp()
        val py = File(d, "python3").apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) }
        val link = File(d, "innocent-server")
        if (!symlinkOrSkip(link, py)) return
        val probe = McpTrustPolicy.Probe.system(mapOf("PATH" to d.path), isWindows = false)
        assertFalse(posix(link.path, probe), "absolute path resolving to python")
        assertFalse(posix("innocent-server", probe), "PATH lookup resolving to python")
    }

    @Test fun shebangScriptWithoutExtensionIsNotPersistable() {
        val d = tmp()
        val f = File(d, "my-mcp").apply { writeText("#!/usr/bin/env node\nconsole.log(1)\n"); setExecutable(true) }
        val probe = McpTrustPolicy.Probe.system(mapOf("PATH" to d.path), isWindows = false)
        assertFalse(posix(f.path, probe))
        assertFalse(posix("my-mcp", probe))
        val bin = File(d, "real-bin").apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) }
        assertTrue(posix(bin.path, probe), "a native binary stays persistable")
    }

    @Test fun windowsExeThatIsNotAPeFileIsNotPersistable() {
        val d = tmp()
        val fake = File(d, "srv.exe").apply { writeText("@echo off\r\n") }
        val pe = File(d, "ok.exe").apply { writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0)) }
        val probe = object : McpTrustPolicy.Probe {
            override fun resolve(command: String) = File(command).takeIf { it.isFile }
            override fun head(file: File, n: Int) = file.readBytes().take(n).toByteArray()
        }
        assertFalse(win(fake.path, probe))
        assertTrue(win(pe.path, probe))
    }
}
