// OpenMinis Desktop — P3-1: encrypt secrets at rest with Windows DPAPI (CurrentUser scope).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.secrets

/** Thrown when a blob cannot be decrypted (another Windows user or machine, damaged file, DPAPI error). */
class SecretUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Turns plaintext into an opaque blob and back. Implementations must never log either side. */
interface SecretProtector {
    /** Written next to every blob in secrets.json, so a future scheme can tell old blobs apart. */
    val scheme: String
    fun protect(plain: ByteArray): ByteArray
    /** @throws SecretUnreadableException */
    fun unprotect(blob: ByteArray): ByteArray

    companion object {
        /** DPAPI on Windows; null elsewhere (dev / CI builds keep the old plain-text behaviour). */
        fun platform(): SecretProtector? =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) DpapiProtector else null
    }
}

/**
 * CryptProtectData / CryptUnprotectData without CRYPTPROTECT_LOCAL_MACHINE: only the same Windows user
 * (on the same machine, unless the profile roams with its master key) can decrypt. UI is forbidden, so a
 * failure is an exception, never a dialog. A fixed app entropy keeps other programs of the same user from
 * decrypting our blobs with a bare CryptUnprotectData call (defence in depth, not a secret).
 * JNA is touched only inside these methods, so non-Windows builds never load it.
 */
object DpapiProtector : SecretProtector {
    override val scheme = "dpapi"
    private val entropy = "OpenMinis/secrets/v1".toByteArray(Charsets.UTF_8)

    override fun protect(plain: ByteArray): ByteArray = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(
            plain, entropy, com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "OpenMinis", null)
    } catch (e: Throwable) {
        throw IllegalStateException("DPAPI encryption failed: ${e.javaClass.simpleName}")
    }

    override fun unprotect(blob: ByteArray): ByteArray = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
            blob, entropy, com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
    } catch (e: Throwable) {
        // Win32Exception text is the system message ("The parameter is incorrect", "Key not valid for use in
        // specified state"); it never contains the data.
        throw SecretUnreadableException("DPAPI could not decrypt (${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)})", e)
    }
}
