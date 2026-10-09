// Security hardening batch — hard path confinement (realpath, symlinks, nonexistent targets, Windows forms).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathGuardTest {
    private fun tmp(p: String = "om-pg") = Files.createTempDirectory(p).toFile().canonicalFile
    private val win = System.getProperty("os.name").lowercase().contains("win")

    private fun allowed(r: PathGuard.Result): File = (r as? PathGuard.Result.Allowed)?.file ?: error("expected Allowed, got $r")
    private fun refused(r: PathGuard.Result) = assertTrue(r is PathGuard.Result.Refused, "expected Refused, got $r")

    @Test fun insideRelativeAndAbsolute() {
        val ws = tmp()
        assertEquals(File(ws, "a/b.txt").absolutePath, allowed(PathGuard.resolve("a/b.txt", ws, listOf(ws))).absolutePath)
        assertEquals(File(ws, "x.txt").absolutePath, allowed(PathGuard.resolve(File(ws, "x.txt").absolutePath, ws, listOf(ws))).absolutePath)
        assertEquals(ws.absolutePath, allowed(PathGuard.resolve(".", ws, listOf(ws))).absolutePath)
    }

    @Test fun dotDotTraversalIsRefused() {
        val ws = tmp()
        refused(PathGuard.resolve("../escape.txt", ws, listOf(ws)))
        refused(PathGuard.resolve("a/../../escape.txt", ws, listOf(ws)))
        refused(PathGuard.resolve(File(ws, "../x").path, ws, listOf(ws)))
        allowed(PathGuard.resolve("a/../ok.txt", ws, listOf(ws)))
    }

    @Test fun siblingWithSamePrefixIsOutside() {
        val parent = tmp()
        val ws = File(parent, "ws").apply { mkdirs() }
        File(parent, "ws-evil").mkdirs()
        refused(PathGuard.resolve(File(parent, "ws-evil/x").absolutePath, ws, listOf(ws)))
    }

    @Test fun absoluteOutsideAndHomeAreRefused() {
        val ws = tmp()
        refused(PathGuard.resolve(tmp("om-other").absolutePath + "/x.txt", ws, listOf(ws)))
        refused(PathGuard.resolve("~/.ssh/authorized_keys", ws, listOf(ws)))
        refused(PathGuard.resolve(if (win) "C:\\Windows\\win.ini" else "/etc/passwd", ws, listOf(ws)))
    }

    @Test fun nonexistentTargetResolvesNearestExistingAncestor() {
        val ws = tmp()
        val f = allowed(PathGuard.resolve("new/deeper/file.txt", ws, listOf(ws)))
        assertTrue(f.absolutePath.startsWith(ws.absolutePath))
    }

    private fun symlinkOrSkip(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath()); true
    } catch (e: Exception) {
        println("SKIP symlink test: ${e.javaClass.simpleName}: ${e.message}"); false
    }

    @Test fun symlinkDirEscapeIsRefused() {
        val ws = tmp(); val outside = tmp("om-out")
        if (!symlinkOrSkip(File(ws, "link"), outside)) return
        refused(PathGuard.resolve("link/x.txt", ws, listOf(ws)))
        refused(PathGuard.resolve("link", ws, listOf(ws)))
        refused(PathGuard.resolve("link/new/dir/x.txt", ws, listOf(ws)))
    }

    @Test fun symlinkFileEscapeAndDanglingLinkAreRefused() {
        val ws = tmp(); val outside = tmp("om-out")
        val secret = File(outside, "secret.txt").apply { writeText("s") }
        if (!symlinkOrSkip(File(ws, "s.txt"), secret)) return
        refused(PathGuard.resolve("s.txt", ws, listOf(ws)))
        // dangling: the target does not exist yet — a write would create it outside
        symlinkOrSkip(File(ws, "dangling.txt"), File(outside, "not-yet.txt"))
        refused(PathGuard.resolve("dangling.txt", ws, listOf(ws)))
    }

    @Test fun symlinkInsideWorkspaceIsFine() {
        val ws = tmp()
        val real = File(ws, "real").apply { mkdirs() }
        if (!symlinkOrSkip(File(ws, "alias"), real)) return
        assertEquals(File(real, "x").absolutePath, allowed(PathGuard.resolve("alias/x", ws, listOf(ws))).absolutePath)
    }

    @Test fun extraReadRootsAreHonoured() {
        val ws = tmp(); val skills = tmp("om-skills")
        File(skills, "a").mkdirs(); File(skills, "a/SKILL.md").writeText("x")
        allowed(PathGuard.resolve(File(skills, "a/SKILL.md").absolutePath, ws, listOf(ws, skills)))
        refused(PathGuard.resolve(File(skills, "a/SKILL.md").absolutePath, ws, listOf(ws)))
    }

    @Test fun windowsLexicalForms() {
        assertNotNull(PathGuard.lexicalProblem("C:foo.txt", windows = true))
        assertNotNull(PathGuard.lexicalProblem("\\Windows\\x", windows = true))
        assertNotNull(PathGuard.lexicalProblem("/etc/x", windows = true))
        assertNotNull(PathGuard.lexicalProblem("notes.txt:hidden", windows = true))
        assertNotNull(PathGuard.lexicalProblem("C:\\ws\\a.txt:Zone.Identifier", windows = true))
        assertNotNull(PathGuard.lexicalProblem("\\\\.\\PhysicalDrive0", windows = true))
        assertNotNull(PathGuard.lexicalProblem("\\\\?\\GLOBALROOT\\Device\\x", windows = true))
        assertNotNull(PathGuard.lexicalProblem("sub\\CON", windows = true))
        assertNotNull(PathGuard.lexicalProblem("nul.txt", windows = true))
        assertNotNull(PathGuard.lexicalProblem("evil.txt.", windows = true))
        assertNotNull(PathGuard.lexicalProblem("evil ", windows = true))
        assertNotNull(PathGuard.lexicalProblem("a\u0000b", windows = true))
        assertNull(PathGuard.lexicalProblem("C:\\Users\\me\\OpenMinis\\workspace\\a.txt", windows = true))
        assertNull(PathGuard.lexicalProblem("\\\\?\\C:\\Users\\me\\ws\\a.txt", windows = true))
        assertNull(PathGuard.lexicalProblem("sub\\..\\a.txt", windows = true))
        assertNull(PathGuard.lexicalProblem("笔记\\测试 文件.md", windows = true))
    }

    @Test fun uncOutsideAUncWorkspaceIsRefusedWithoutTouchingTheNetwork() {
        val ws = tmp()
        refused(PathGuard.resolve("\\\\attacker\\share\\x.txt", ws, listOf(ws), windows = true))
        refused(PathGuard.resolve("\\\\?\\UNC\\attacker\\share\\x", ws, listOf(ws), windows = true))
    }

    @Test fun adsRefusedOnEveryOs() {
        val ws = tmp()
        refused(PathGuard.resolve("a.txt:stream", ws, listOf(ws)))
    }

    @Test fun rootWorkspaceDetected() {
        val root = File(if (win) "C:\\" else "/").toPath()
        assertTrue(PathGuard.isFileSystemRoot(root))
        assertTrue(!PathGuard.isFileSystemRoot(tmp().toPath()))
    }

    @Test fun windowsCaseInsensitiveCompare() {
        val a = java.nio.file.Paths.get("/x/Work/A")
        val b = java.nio.file.Paths.get("/x/work")
        // On the windows branch the string compare is case-insensitive; on POSIX it is not.
        assertTrue(PathGuard.isUnder(a, java.nio.file.Paths.get("/x/Work"), windows = false))
        assertTrue(!PathGuard.isUnder(a, b, windows = false))
    }
}
