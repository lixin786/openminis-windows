// OpenMinis Desktop — P3-1: the encrypted side of settings (secrets.json next to settings.json).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.secrets

import com.openminis.desktop.core.atomicWrite
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * `{"version":1,"entries":{"provider.ANTHROPIC.apiKey":{"scheme":"dpapi","blob":"<base64>"}}}`.
 * Holds only ciphertext: plaintext lives in memory (AppSettings) and nowhere on disk.
 */
class SecretStore(private val file: File, val protector: SecretProtector) {
    private val lock = Any()

    private var damaged = false

    private fun read(): JSONObject = runCatching {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)).optJSONObject("entries") ?: JSONObject() else JSONObject()
    }.getOrElse {
        damaged = true
        com.openminis.app.logging.AppLogger.error("Secrets", "secrets.json unreadable: ${it.javaClass.simpleName}")
        JSONObject()
    }

    fun ids(): Set<String> = synchronized(lock) { read().keySet().toSet() }

    /** @throws SecretUnreadableException when the entry is missing or cannot be decrypted. */
    fun get(id: String): String = synchronized(lock) {
        val e = read().optJSONObject(id) ?: throw SecretUnreadableException("no stored secret")
        if (e.optString("scheme") != protector.scheme) throw SecretUnreadableException("stored with scheme ${e.optString("scheme")}")
        val blob = try { Base64.getDecoder().decode(e.optString("blob")) } catch (x: IllegalArgumentException) {
            throw SecretUnreadableException("damaged entry", x)
        }
        String(protector.unprotect(blob), Charsets.UTF_8)
    }

    /**
     * Applies all changes in one atomic write: [put] values are encrypted, [remove] ids dropped, every other
     * entry (including ones that cannot be decrypted right now) is kept byte for byte.
     */
    fun update(put: Map<String, String>, remove: Set<String>) = synchronized(lock) {
        if (put.isEmpty() && remove.isEmpty()) return@synchronized
        val entries = read()
        if (damaged && file.exists()) {
            // Never overwrite a file we could not parse: keep it aside for the user / support.
            val aside = File(file.parentFile, file.name + ".damaged-" + System.currentTimeMillis())
            runCatching { file.copyTo(aside, overwrite = false) }
            com.openminis.app.logging.AppLogger.warning("Secrets", "kept the unreadable secrets.json as ${aside.name}")
            damaged = false
        }
        remove.forEach { entries.remove(it) }
        put.forEach { (id, plain) ->
            val blob = protector.protect(plain.toByteArray(Charsets.UTF_8))
            entries.put(id, JSONObject().put("scheme", protector.scheme).put("blob", Base64.getEncoder().encodeToString(blob)))
        }
        atomicWrite(file, JSONObject().put("version", 1).put("entries", entries).toString(2))
    }
}
