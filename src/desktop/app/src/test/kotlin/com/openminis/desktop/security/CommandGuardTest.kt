// Security hardening batch — unit tests of the pure command gate (denylist / metacharacters / allowlist).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import com.openminis.desktop.security.CommandGuard.Decision
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandGuardTest {
    private fun decision(c: String, ro: Boolean = true) = CommandGuard.inspect(c, ro).decision
    private fun assertBlocked(c: String) = assertEquals(Decision.BLOCK, decision(c), "expected BLOCK: $c (${CommandGuard.inspect(c)})")
    private fun assertConfirm(c: String) = assertEquals(Decision.CONFIRM, decision(c), "expected CONFIRM: $c (${CommandGuard.inspect(c)})")
    private fun assertAuto(c: String) = assertEquals(Decision.AUTO_ALLOW, decision(c), "expected AUTO_ALLOW: $c (${CommandGuard.inspect(c)})")
    private fun ps(script: String) = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    @Test fun normalizeCollapsesDotDot() {
        assertEquals("/", CommandGuard.normalizeUnix("/tmp/../"))
        assertEquals("/", CommandGuard.normalizeUnix("/tmp/a/../../"))
        assertEquals("/", CommandGuard.normalizeUnix("//"))
        assertEquals("~", CommandGuard.normalizeUnix("\$HOME"))
        assertEquals("~", CommandGuard.normalizeUnix("\${HOME}/"))
        assertEquals("~/x", CommandGuard.normalizeUnix("~/a/../x"))
        assertEquals("build", CommandGuard.normalizeUnix("./build"))
    }

    @Test fun ifsIsExpanded() {
        assertEquals("rm -rf /", CommandGuard.expandIfs("rm\${IFS}-rf\${IFS}/"))
        assertEquals("rm -rf /", CommandGuard.expandIfs("rm\$IFS-rf\$IFS/"))
        assertEquals("echo \$IFSX", CommandGuard.expandIfs("echo \$IFSX"))
    }

    @Test fun segmentsSplitOutsideQuotes() {
        assertEquals(listOf("a", "b", "c", "d"), CommandGuard.segments("a; b && c | d"))
        assertEquals(listOf("echo 'a;b'"), CommandGuard.segments("echo 'a;b'"))
    }

    @Test fun metacharacters() {
        for (c in listOf("a > b", "a < b", "a | b", "a; b", "a & b", "a `b`", "a \$(b)", "a (b)", "a\nb", "a\rb", "echo \$HOME"))
            assertTrue(CommandGuard.hasMetacharacters(c), c)
        assertFalse(CommandGuard.hasMetacharacters("ls -la src"))
    }

    @Test fun readOnlyAllowlist() {
        assertAuto("ls -la")
        assertAuto("ls")
        assertAuto("cat README.md")
        assertAuto("grep -n TODO src/main.kt")
        assertAuto("Get-ChildItem")
        assertAuto("get-content notes.txt")
        assertAuto("dir /b")
        assertAuto("echo hello")
        assertAuto("wc -l a.txt")
        // conservative: not on the list, or arguments that write / execute / leave the workspace
        assertConfirm("find . -name x")
        assertConfirm("sed -n 1p a")
        assertConfirm("awk 1 a")
        assertConfirm("xargs ls")
        assertConfirm("env")
        assertConfirm("git status")
        assertConfirm("sort -o out.txt in.txt")
        assertConfirm("tree -o out.txt")
        assertConfirm("cat /etc/passwd")
        assertConfirm("cat ~/.ssh/id_rsa")
        assertConfirm("type C:\\Users\\me\\secret.txt")
        assertConfirm("cat ../outside.txt")
        assertConfirm("Get-Content -Path Env:\\OPENAI_KEY")
        assertConfirm("type %USERPROFILE%\\x")
        assertConfirm("./ls")
        // switch off → everything not blocked asks
        assertEquals(Decision.CONFIRM, decision("ls -la", ro = false))
    }

    @Test fun interpreters() {
        for (c in listOf("bash x.sh", "sh", "zsh -c ls", "python3 a.py", "python -m http.server", "node s.js", "perl -e 1", "pwsh -File x.ps1",
            "powershell", "cmd /c dir", "C:\\Windows\\System32\\cmd.exe", "/usr/bin/python3 x", "sudo python3 x.py", "env FOO=1 node x",
            "ruby x.rb", "php x.php", "wscript x.vbs", "cscript x.js", "mshta x.hta", "osascript -e 1", "FOO=1 bash x"))
            assertTrue(CommandGuard.isInterpreter(c), c)
        for (c in listOf("ls", "git status", "npm test", "mkdir x")) assertFalse(CommandGuard.isInterpreter(c), c)
    }

    @Test fun sessionMemoryEligibility() {
        assertTrue(CommandGuard.mayBeRememberedForSession("npm test"))
        assertFalse(CommandGuard.mayBeRememberedForSession("python3 build.py"))
        assertFalse(CommandGuard.mayBeRememberedForSession("npm test && rm x"))
        assertFalse(CommandGuard.mayBeRememberedForSession("rm -rf /"))
        assertFalse(CommandGuard.mayBeRememberedForSession("echo x >> ~/.ssh/authorized_keys"))
        assertFalse(CommandGuard.mayBeRememberedForSession("sudo bash"))
    }

    @Test fun falsePositivesStayConfirmNotBlock() {
        assertConfirm("rm -rf ./build")
        assertConfirm("rm -rf build")
        assertConfirm("rm -rf /tmp/om-test-123")
        assertConfirm("rm -rf \"\$TMPDIR_BUILD\"")
        assertConfirm("rm file.txt")
        assertConfirm("Remove-Item -Recurse -Force .\\build")
        assertConfirm("rd /s /q build")
        assertConfirm("del /q *.tmp")
        assertConfirm("dd if=/dev/zero of=./disk.img bs=1M count=1")
        assertConfirm("dd if=/dev/urandom of=/dev/null count=1")
        assertConfirm("chmod -R 755 ./dist")
        assertConfirm("mv a.txt b.txt")
        assertConfirm("shutdown /a")
        assertConfirm("echo /dev/sda")
        assertConfirm("format-table")
        assertConfirm("reg query HKLM\\Software")
        assertConfirm("reg delete HKCU\\Software\\MyApp /f")
        assertConfirm("cipher /e secret")
        assertConfirm("npx -y @modelcontextprotocol/server-filesystem C:\\Users\\me\\docs")
    }
}
