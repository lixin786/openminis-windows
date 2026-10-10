// OpenMinis Desktop — the Bearer token phones use for the remote MCP server.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.remote

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * 32 random bytes, base64url without padding. Kept in its own file in the app data folder (never in
 * settings.json, never logged). [rotate] replaces it; the server then drops every session.
 */
class RemoteTokenStore(private val file: File) {
    @Volatile private var cached: String? = null

    fun get(): String = cached ?: synchronized(this) {
        cached ?: (runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()?.takeIf { valid(it) } ?: write(generate())).also { cached = it }
    }

    fun rotate(): String = synchronized(this) { write(generate()).also { cached = it } }

    private fun write(t: String): String {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(t, Charsets.UTF_8)
        // Best effort owner-only permissions (POSIX semantics; on Windows %APPDATA% is already per-user).
        runCatching { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        return t
    }

    companion object {
        private val rnd = SecureRandom()
        fun generate(): String = ByteArray(32).also(rnd::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        fun valid(t: String) = t.length == 43 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
