// OpenMinis Desktop — the Bearer token phones use for the remote MCP server.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.remote

import com.openminis.app.logging.AppLogger
import com.openminis.desktop.core.atomicWrite
import com.openminis.desktop.secrets.SecretProtector
import com.openminis.desktop.secrets.SecretUnreadableException
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * 32 random bytes, base64url without padding (unchanged). Never in settings.json, never logged. [rotate] replaces
 * it; the server then drops every session.
 *
 * On disk (P3-1 follow-up): with DPAPI (Windows) the token lives encrypted in `<file>.dpapi`
 * (`{"version":1,"scheme":"dpapi","blob":"<base64>"}`); the old plain-text `<file>` is migrated once, then
 * overwritten and deleted. Without DPAPI (non-Windows dev builds) the plain file is used as before.
 * A token that cannot be decrypted (another Windows account / PC) is not usable by anyone here, so a new one is
 * made — but the unreadable file is kept aside and [notice] tells the user that phones must be added again.
 * If encryption itself fails, the token is kept in memory for this session only: plaintext is never written.
 */
class RemoteTokenStore(
    private val file: File,
    private val protector: SecretProtector? = SecretProtector.platform(),
) {
    enum class Notice { REGENERATED_UNREADABLE, NOT_SAVED }

    @Volatile private var cached: String? = null
    /** Shown in Settings › Phone link; null = nothing to tell. */
    @Volatile var notice: Notice? = null; private set

    val encryptedFile: File get() = File(file.parentFile, file.name + ".dpapi")

    fun get(): String = cached ?: synchronized(this) { cached ?: load().also { cached = it } }

    /** Called at app start so an old plain-text token file is migrated even if the phone link stays off. */
    fun migrate() { if (protector != null && file.exists()) get() }

    fun rotate(): String = synchronized(this) {
        val t = generate()
        if (protector == null) writePlain(t) else { saveEncrypted(t); wipe(file) }
        if (notice == Notice.REGENERATED_UNREADABLE) notice = null   // the user has just re-paired anyway
        cached = t
        t
    }

    private fun load(): String {
        val p = protector ?: return readPlain() ?: writePlain(generate())
        val enc = encryptedFile
        if (enc.exists()) {
            try {
                val t = decrypt(p, enc)
                wipe(file)   // a plain copy left behind by an interrupted migration
                return t
            } catch (e: SecretUnreadableException) {
                val aside = File(enc.parentFile, enc.name + ".unreadable-" + System.currentTimeMillis())
                runCatching { enc.copyTo(aside, overwrite = false) }
                AppLogger.warning("RemoteMcp", "phone-link token cannot be decrypted (${e.message}); a new one was made, old file kept as ${aside.name}")
                notice = Notice.REGENERATED_UNREADABLE
            }
        }
        val legacy = readPlain()
        val t = legacy ?: generate()
        saveEncrypted(t)
        if (legacy != null && notice != Notice.NOT_SAVED) {
            wipe(file)
            AppLogger.info("RemoteMcp", "plain-text phone-link token moved into DPAPI storage")
        }
        return t
    }

    private fun decrypt(p: SecretProtector, enc: File): String {
        val o = try { JSONObject(enc.readText(Charsets.UTF_8)) } catch (e: Exception) { throw SecretUnreadableException("damaged token file", e) }
        if (o.optString("scheme") != p.scheme) throw SecretUnreadableException("stored with scheme ${o.optString("scheme")}")
        val blob = try { Base64.getDecoder().decode(o.optString("blob")) } catch (e: IllegalArgumentException) { throw SecretUnreadableException("damaged token file", e) }
        val t = String(p.unprotect(blob), Charsets.UTF_8)
        if (!valid(t)) throw SecretUnreadableException("decrypted value is not a token")
        return t
    }

    /** Encrypt and write atomically; on failure keep the token in memory only (never plaintext) and say so. */
    private fun saveEncrypted(t: String) {
        val p = protector ?: return
        try {
            val blob = p.protect(t.toByteArray(Charsets.UTF_8))
            atomicWrite(encryptedFile, JSONObject().put("version", 1).put("scheme", p.scheme)
                .put("blob", Base64.getEncoder().encodeToString(blob)).toString())
            if (notice == Notice.NOT_SAVED) notice = null
        } catch (e: Exception) {
            AppLogger.error("RemoteMcp", "phone-link token could not be encrypted: ${e.message ?: e.javaClass.simpleName}")
            notice = Notice.NOT_SAVED
        }
    }

    private fun readPlain(): String? = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()?.takeIf { valid(it) }

    private fun writePlain(t: String): String {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(t, Charsets.UTF_8)
        // Best effort owner-only permissions (POSIX semantics; on Windows %APPDATA% is already per-user).
        runCatching { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        return t
    }

    /** Overwrite then delete (best effort: SSD wear levelling / shadow copies are beyond reach). */
    private fun wipe(f: File) {
        if (!f.exists()) return
        runCatching { f.writeBytes(ByteArray(f.length().toInt().coerceAtLeast(64))) }
        if (!f.delete()) AppLogger.warning("RemoteMcp", "could not delete the old plain-text token file")
    }

    companion object {
        private val rnd = SecureRandom()
        fun generate(): String = ByteArray(32).also(rnd::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        fun valid(t: String) = t.length == 43 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
