// OpenMinis Desktop — local shell execution. Replaces the Android PRoot/iSH Linux sandbox
// (see PORTING.md): commands run directly on the host via PowerShell, cmd.exe or WSL.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.tools

import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.core.ShellKind
import com.openminis.desktop.core.TextDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

data class ShellResult(val exitCode: Int?, val output: String, val timedOut: Boolean, val truncated: Boolean)

object ShellRunner {
    const val MAX_OUTPUT_CHARS = 30_000
    /** Raw bytes kept from a command's output before decoding (head; the tail is lost beyond this). */
    const val MAX_OUTPUT_BYTES = 512 * 1024

    private fun onPath(exe: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparatorChar).any { dir -> dir.isNotBlank() && File(dir, exe).canExecute() }
    }

    val wslAvailable: Boolean by lazy {
        AppPaths.isWindows && (File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\wsl.exe").exists() || onPath("wsl.exe"))
    }

    private val powershellExe: String by lazy { if (onPath("pwsh.exe")) "pwsh.exe" else "powershell.exe" }

    /** Build the argv for [command] under [kind]. On non-Windows dev hosts falls back to bash. */
    fun buildArgv(kind: ShellKind, command: String, cwd: File, windows: Boolean = AppPaths.isWindows): List<String> {
        if (!windows) return listOf("/bin/bash", "-lc", command)
        return when (kind) {
            ShellKind.POWERSHELL -> {
                // -EncodedCommand (UTF-16LE base64) avoids every quoting pitfall; force UTF-8 output.
                // UTF-8 for PowerShell's own output and for decoding native tools; wrapped in try because
                // setting the console encoding throws when the process has no console. Whatever a legacy
                // tool still prints in the ANSI/OEM code page is recovered by TextDecoding (per line).
                val script = "try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}; \$OutputEncoding=[System.Text.Encoding]::UTF8; " +
                    "\$ProgressPreference='SilentlyContinue'; " + command
                val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                listOf(powershellExe, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            }
            ShellKind.CMD -> listOf("cmd.exe", "/d", "/s", "/c", "chcp 65001>nul & $command")
            ShellKind.WSL -> listOf("wsl.exe", "--cd", cwd.absolutePath, "-e", "bash", "-lc", command)
        }
    }

    suspend fun run(kind: ShellKind, command: String, cwd: File, timeoutSeconds: Int): ShellResult =
        runInterruptible(Dispatchers.IO) {
            cwd.mkdirs()
            val pb = ProcessBuilder(buildArgv(kind, command, cwd)).directory(cwd).redirectErrorStream(true)
            pb.environment()["OPENMINIS_WORKSPACE"] = cwd.absolutePath
            val proc = pb.start()
            proc.outputStream.close()
            // Collect raw BYTES and decode at the end (TextDecoding): output may be UTF-8, the legacy
            // code page, or UTF-16LE (wsl.exe's own messages) — never assume the JVM default charset.
            val buf = HeadTailBuffer(MAX_OUTPUT_BYTES / 2)
            val reader = Thread {
                proc.inputStream.use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        synchronized(buf) { buf.write(chunk, n) }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                val finished = proc.waitFor(timeoutSeconds.toLong().coerceAtLeast(1), TimeUnit.SECONDS)
                if (!finished) killTree(proc)
                reader.join(2000)
                val raw = synchronized(buf) { buf.decode() }
                val (text, cut) = truncate(raw)
                ShellResult(if (finished) proc.exitValue() else null, text, !finished, buf.dropped > 0 || cut)
            } catch (e: InterruptedException) {
                killTree(proc)
                throw e
            }
        }

    private fun killTree(p: Process) {
        runCatching { p.toHandle().descendants().forEach { it.destroyForcibly() } }
        p.destroyForcibly()
    }

    /** Keep head and tail (errors usually sit at the end). */
    fun truncate(s: String, max: Int = MAX_OUTPUT_CHARS): Pair<String, Boolean> {
        if (s.length <= max) return s to false
        val head = s.take(max / 2)
        val tail = s.takeLast(max / 2)
        return "$head\n\n… [${s.length - max} characters omitted] …\n\n$tail" to true
    }
}

/** Keeps the first and the last [half] bytes of a stream (errors usually sit at the end). */
internal class HeadTailBuffer(private val half: Int) {
    private val head = java.io.ByteArrayOutputStream()
    private val tail = ByteArray(half)
    private var tailLen = 0
    private var tailPos = 0
    var dropped = 0L
        private set

    fun write(b: ByteArray, n: Int) {
        var i = 0
        if (head.size() < half) { val k = minOf(n, half - head.size()); head.write(b, 0, k); i = k }
        while (i < n) {
            if (tailLen == half) dropped++ else tailLen++
            tail[tailPos] = b[i]; tailPos = (tailPos + 1) % half; i++
        }
    }

    private fun tailBytes(): ByteArray {
        val out = ByteArray(tailLen)
        val start = (tailPos - tailLen + half) % half
        for (k in 0 until tailLen) out[k] = tail[(start + k) % half]
        return out
    }

    fun decode(): String {
        val h = head.toByteArray()
        if (dropped == 0L) return TextDecoding.decodeProcessOutput(h + tailBytes())
        var t = tailBytes()
        // Skip UTF-8 continuation bytes the cut left at the start of the tail.
        var k = 0
        while (k < t.size && k < 3 && (t[k].toInt() and 0xC0) == 0x80) k++
        if (k > 0) t = t.copyOfRange(k, t.size)
        return TextDecoding.decodeProcessOutput(h) + "\n\n… [$dropped bytes omitted] …\n\n" + TextDecoding.decodeProcessOutput(t)
    }
}
