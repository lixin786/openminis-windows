// OpenMinis Desktop — hard path confinement for file_read / file_write / file_edit / list_dir and the
// shell working directory. Security hardening batch (README "安全加固", layer 4).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a tool path to its real location and refuses it — no dialog, no override — unless it
 * lies inside one of the allowed roots:
 *
 *  1. lexical checks first (no file-system access): NUL, NTFS alternate data streams (`a.txt:s`),
 *     drive-relative `C:foo`, root-relative `\foo` / `/foo` on Windows, `\\.\` device paths,
 *     `\\?\GLOBALROOT`, UNC `\\server\share` outside a UNC root (resolving one would make Windows
 *     authenticate to that server), reserved device names (CON, NUL, COM1 …), components ending in
 *     a dot or a space (Win32 strips them, so they alias other names);
 *  2. `~` → the user's home, relative → the workspace, then `..` collapsed;
 *  3. the nearest *existing* ancestor (checked without following links) is resolved with
 *     `toRealPath()` — this follows symlinks and junctions and canonicalises case and 8.3 short
 *     names on Windows — and the not-yet-existing rest is appended; a dangling link fails closed;
 *  4. the real path must start with the real path of an allowed root (component-wise; Windows paths
 *     compare case-insensitively).
 *
 * Callers must use the returned [Result.Allowed.file] (the resolved real path), not the raw input.
 * Known limits: hard links cannot be detected; a directory swapped for a link between this check and
 * the write (TOCTOU) is not prevented.
 */
object PathGuard {

    sealed class Result {
        data class Allowed(val file: File) : Result()
        data class Refused(val reason: String) : Result()
    }

    private val RESERVED = Regex("(?i)^(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9¹²³]|LPT[0-9¹²³])(\\..*)?$")

    /** Pure lexical screening. Returns the refusal reason, or null when the string may be resolved. */
    fun lexicalProblem(raw: String, windows: Boolean): String? {
        if (raw.isEmpty()) return "empty path"
        if (raw.indexOf('\u0000') >= 0) return "the path contains a NUL character"
        if (windows) {
            val p = raw.replace('/', '\\')
            if (p.startsWith("\\\\.\\") || p.startsWith("\\\\?\\GLOBALROOT", ignoreCase = true) || p.startsWith("\\??\\"))
                return "device / NT-namespace paths are not allowed"
            if (Regex("^[A-Za-z]:(?![\\\\])").containsMatchIn(p)) return "drive-relative paths like C:foo are not allowed — use a full path (C:\\…) or a path relative to the workspace"
            if (p.startsWith("\\") && !p.startsWith("\\\\")) return "root-relative paths (\\foo or /foo) are not allowed on Windows — use a full path (C:\\…) or a path relative to the workspace"
            val body = stripLongPrefix(p).let { if (Regex("^[A-Za-z]:").containsMatchIn(it)) it.drop(2) else it }
            if (body.contains(':')) return "NTFS alternate data streams (file:stream) are not allowed"
            for (c in body.split('\\').filter { it.isNotEmpty() && it != "." && it != ".." }) {
                if (c.endsWith('.') || c.endsWith(' ')) return "path components ending in a dot or space are not allowed (\"$c\")"
                if (RESERVED.matches(c)) return "reserved device name \"$c\" is not allowed"
            }
        } else {
            // Same rule on every OS so the tests (and WSL-mounted NTFS) behave alike.
            val name = raw.substringAfterLast('/')
            if (name.contains(':') && !raw.startsWith("~")) return "NTFS alternate data streams (file:stream) are not allowed"
        }
        return null
    }

    private fun stripLongPrefix(p: String): String = when {
        p.startsWith("\\\\?\\UNC\\", ignoreCase = true) -> "\\\\" + p.drop(8)
        p.startsWith("\\\\?\\") -> p.drop(4)
        else -> p
    }

    /** Real path of a root, or null when it does not exist / cannot be resolved. */
    fun realRoot(root: File): Path? = runCatching { root.toPath().toAbsolutePath().normalize().toRealPath() }.getOrNull()

    /** A workspace that is a file-system / drive root would make confinement meaningless. */
    fun isFileSystemRoot(p: Path): Boolean = p.nameCount == 0 || p.parent == null

    /**
     * Resolves [raw] (absolute, `~/…`, or relative to [workspace]) and checks it against [roots]
     * (real paths are computed here; roots that do not exist are ignored).
     */
    fun resolve(raw: String, workspace: File, roots: List<File>, windows: Boolean = isWindowsHost): Result {
        val trimmed = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        lexicalProblem(trimmed, windows)?.let { return Result.Refused(it) }
        var s = trimmed
        if (windows) s = stripLongPrefix(s.replace('/', '\\'))
        val isUnc = windows && s.startsWith("\\\\")
        val realRoots = roots.mapNotNull { realRoot(it) }
        if (realRoots.isEmpty()) return Result.Refused("no allowed folder exists (workspace missing)")
        if (isUnc && realRoots.none { it.toString().startsWith("\\\\") }) return Result.Refused("network (UNC) paths are outside the workspace")
        if (s == "~" || s.startsWith("~/") || s.startsWith("~\\")) s = System.getProperty("user.home") + s.drop(1)
        val path: Path = try {
            val p = Paths.get(s)
            (if (p.isAbsolute) p else workspace.toPath().resolve(p)).toAbsolutePath().normalize()
        } catch (e: Exception) {
            return Result.Refused("invalid path (${e.message})")
        }
        val real = try { realOf(path) } catch (e: Exception) {
            return Result.Refused("the path could not be resolved safely (${e.javaClass.simpleName}: ${e.message}) — refused (fail-closed)")
        }
        val inside = realRoots.any { root -> isUnder(real, root, windows) }
        return if (inside) Result.Allowed(real.toFile())
        else Result.Refused("$real is outside the workspace")
    }

    /** Nearest existing ancestor (no link following) → toRealPath(), plus the missing rest. */
    internal fun realOf(path: Path): Path {
        var existing: Path? = path
        val rest = ArrayDeque<String>()
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing.fileName?.let { rest.addFirst(it.toString()) }
            existing = existing.parent
        }
        if (existing == null) throw java.nio.file.NoSuchFileException(path.toString(), null, "no existing ancestor")
        // toRealPath() follows every link (symlink, junction); a dangling one throws NoSuchFileException.
        var real = existing.toRealPath()
        for (part in rest) real = real.resolve(part)
        return real
    }

    internal fun isUnder(p: Path, root: Path, windows: Boolean): Boolean {
        if (!p.startsWith(root)) {
            if (!windows) return false
            val a = p.toString().lowercase().trimEnd('\\')
            val b = root.toString().lowercase().trimEnd('\\')
            return a == b || a.startsWith("$b\\")
        }
        return true
    }

    private val isWindowsHost: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
