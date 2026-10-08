// Desktop shim for an Android framework API so ported OpenMinis code compiles unchanged on the JVM.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String = String(encode(input, flags), Charsets.US_ASCII)

    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray {
        var enc = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) enc = enc.withoutPadding()
        val out = enc.encode(input)
        if (flags and NO_WRAP != 0) return out
        // Android DEFAULT wraps at 76 chars with '\n'.
        val s = String(out, Charsets.US_ASCII).chunked(76).joinToString(if (flags and CRLF != 0) "\r\n" else "\n", postfix = if (flags and CRLF != 0) "\r\n" else "\n")
        return s.toByteArray(Charsets.US_ASCII)
    }

    @JvmStatic fun decode(str: String, flags: Int): ByteArray = decode(str.toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray {
        val cleaned = String(input, Charsets.US_ASCII).filterNot { it.isWhitespace() }
        return if (flags and URL_SAFE != 0 || cleaned.contains('-') || cleaned.contains('_'))
            java.util.Base64.getUrlDecoder().decode(cleaned.trimEnd('='))
        else java.util.Base64.getDecoder().decode(cleaned.trimEnd('='))
    }
}
