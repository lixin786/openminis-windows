// OpenMinis Desktop — explicit text decoding. The desktop JVM's default charset is the Windows
// ANSI code page (GBK on zh-CN Windows) under JDK 17, so NOTHING may rely on it: every byte→text
// conversion goes through UTF-8 explicitly, or through [decodeProcessOutput] for bytes produced by
// other programs (console tools may print UTF-8, the OEM/ANSI code page, or — wsl.exe — UTF-16LE).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextDecoding {
    /**
     * The machine's legacy code page (what non-Unicode Windows programs print): JDK 17's
     * `native.encoding` (e.g. GBK, windows-1252), falling back to `sun.jnu.encoding`, then UTF-8.
     */
    val legacyCharset: Charset get() = override ?: detected
    /** Tests pin the legacy code page (a Linux CI box has no GBK ANSI code page). */
    @Volatile var override: Charset? = null
    private val detected: Charset by lazy {
        listOf("native.encoding", "sun.jnu.encoding").firstNotNullOfOrNull { k ->
            System.getProperty(k)?.let { runCatching { Charset.forName(it) }.getOrNull() }
        } ?: Charsets.UTF_8
    }

    /** Strict UTF-8 decode; null if [bytes] are not valid UTF-8. */
    fun strictUtf8(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, off, len)).toString()
    } catch (_: CharacterCodingException) { null }

    /** Looks like UTF-16LE text (wsl.exe's own messages): many NUL bytes at odd offsets. */
    fun looksUtf16le(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return true
        val n = minOf(bytes.size, 512) / 2
        var zeros = 0
        for (i in 0 until n) if (bytes[2 * i + 1] == 0.toByte() && bytes[2 * i] != 0.toByte()) zeros++
        return zeros >= n * 0.6
    }

    /**
     * Decode another program's output: BOM-aware; UTF-16LE when it looks like it; UTF-8 when the
     * bytes are valid UTF-8 (tolerating a multi-byte sequence cut off at the very end by a size
     * cap); otherwise [fallback] (the legacy code page).
     */
    fun decodeProcessOutput(bytes: ByteArray, fallback: Charset = legacyCharset): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (looksUtf16le(bytes)) {
            val start = if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) 2 else 0
            return String(bytes, start, (bytes.size - start) and 1.inv(), Charsets.UTF_16LE)
        }
        strictUtf8(bytes)?.let { return it }
        for (cut in 1..3) {
            if (bytes.size - cut <= 0) break
            strictUtf8(bytes, 0, bytes.size - cut)?.let { return it }
        }
        // Mixed output (PowerShell writes UTF-8, a legacy native tool writes GBK): decode per line.
        val sb = StringBuilder()
        var start = 0
        for (i in 0..bytes.size) {
            if (i == bytes.size || bytes[i] == '\n'.code.toByte()) {
                val end = if (i < bytes.size) i + 1 else i
                if (end > start) sb.append(strictUtf8(bytes, start, end - start) ?: String(bytes, start, end - start, fallback))
                start = end
            }
        }
        return sb.toString()
    }

    /** Text files: UTF-8 (BOM stripped) when valid, else the legacy code page; second value = charset used. */
    fun decodeTextFile(bytes: ByteArray): Pair<String, Charset> {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to Charsets.UTF_16BE
        val noBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        strictUtf8(noBom)?.let { return it to Charsets.UTF_8 }
        return String(noBom, legacyCharset) to legacyCharset
    }
}
