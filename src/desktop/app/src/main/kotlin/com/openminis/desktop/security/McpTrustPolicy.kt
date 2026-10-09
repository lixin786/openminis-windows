// OpenMinis Desktop — which MCP server launches may have their "Always allow" remembered on disk.
// Security hardening batch (README "安全加固", layer 7 applied to MCP, as the user decided on 2026-10-09):
// an MCP fingerprint binds only the command line. For `python server.py`, `node index.js`, `npx pkg`,
// `cmd /c run.bat` … the line never changes while the code it runs can, so a remembered approval would
// be permanent arbitrary code execution for whoever can edit that script / package. Such launches are
// confirmed on EVERY start; only a direct native executable may keep its SHA-256 fingerprint trust.
// Pure decision logic; file probing goes through the injectable [Probe] so it is unit-testable.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import java.io.File

object McpTrustPolicy {

    /** File-system access the policy needs (real implementation: [Probe.system]). */
    interface Probe {
        /** The file that `command` starts (an existing path, or the PATH / PATHEXT lookup), or null. */
        fun resolve(command: String): File?
        /** The first bytes of [file] (up to [n]); empty when unreadable. */
        fun head(file: File, n: Int): ByteArray

        companion object {
            fun system(env: Map<String, String>, isWindows: Boolean, cwd: File? = null): Probe = object : Probe {
                override fun resolve(command: String): File? = resolveCommand(command, env, isWindows, cwd)
                override fun head(file: File, n: Int): ByteArray =
                    runCatching { file.inputStream().use { it.readNBytes(n) } }.getOrDefault(ByteArray(0))
            }
        }
    }

    /** Package runners / build tools / version managers: they run code that is not on the command line. */
    private val LAUNCHERS = setOf(
        "npx", "npm", "pnpm", "pnpx", "yarn", "yarnpkg", "bunx", "corepack", "volta", "fnm", "nvm", "nvs",
        "uvx", "uv", "pipx", "pip", "pip3", "poetry", "pdm", "hatch", "rye", "conda", "mamba", "micromamba", "pixi",
        "tsx", "ts-node", "esno", "esrun", "vite-node", "jbang", "kotlin", "kotlinc", "go", "cargo", "rustup",
        "gradle", "gradlew", "mvn", "mvnw", "make", "just", "rake", "bundle", "bundler", "gem", "composer",
        "asdf", "mise", "rtx", "proto", "scoop", "winget", "choco", "docker-compose",
    )

    /** Script / shim extensions: the file is interpreted, not executed natively. */
    private val SCRIPT_EXTENSIONS = setOf(
        "sh", "bash", "zsh", "ksh", "fish", "command", "py", "pyw", "pyc", "pyz", "js", "mjs", "cjs", "ts", "mts", "cts",
        "rb", "pl", "pm", "php", "lua", "r", "jl", "tcl", "groovy", "kts", "jar", "war", "scpt", "applescript",
        "ps1", "psm1", "psd1", "bat", "cmd", "vbs", "vbe", "jse", "wsf", "wsh", "hta", "msc", "lnk", "url", "scr", "cpl",
    )

    /** Native executable extensions on Windows that CreateProcess starts without `cmd.exe /c`. */
    private val WINDOWS_NATIVE = setOf("exe", "com")

    data class Verdict(val persistable: Boolean, val reason: String)

    /**
     * May an approval of an MCP launch whose (env-expanded) program is [command] be remembered
     * permanently? Only for a direct native executable: never for an interpreter, a package runner,
     * a script (by extension or `#!` shebang), a Windows shim that has to go through `cmd.exe /c`
     * (any command without `.exe` / `.com`), or a symlink that resolves to any of those.
     */
    fun verdict(command: String, isWindows: Boolean, probe: Probe? = null): Verdict {
        val cmd = command.trim().trim('"', '\'')
        if (cmd.isEmpty()) return Verdict(false, "no command")
        if (CommandGuard.hasMetacharacters(cmd)) return Verdict(false, "the command contains shell metacharacters")
        nameVerdict(cmd, isWindows)?.let { return it }
        val file = probe?.resolve(cmd)
        if (file != null) {
            val real = runCatching { file.toPath().toRealPath().toFile() }.getOrDefault(file)
            if (real.name != File(cmd).name) nameVerdict(real.path, isWindows)?.let { return Verdict(false, "${it.reason} (resolved to ${real.path})") }
            val head = probe.head(real, 2)
            if (head.size == 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte())
                return Verdict(false, "${real.name} is a #! script run by an interpreter")
            if (isWindows && head.size == 2 && !(head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()))
                return Verdict(false, "${real.name} is not a native Windows executable")
        }
        return Verdict(true, "direct executable")
    }

    fun isPersistable(command: String, isWindows: Boolean, probe: Probe? = null): Boolean = verdict(command, isWindows, probe).persistable

    private fun nameVerdict(cmd: String, isWindows: Boolean): Verdict? {
        val fileName = cmd.substringAfterLast('/').substringAfterLast('\\')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val base = CommandGuard.commandName(cmd)
        // python3.12, node22, pwsh-preview, python3.12.exe → python / node / pwsh
        val stem = base.replace(Regex("(-preview|-nightly|-dev)$"), "").replace(Regex("[0-9][0-9.]*$"), "").trimEnd('.', '-', '_')
        if (CommandGuard.isInterpreterName(base) || CommandGuard.isInterpreterName(stem))
            return Verdict(false, "$fileName is an interpreter")
        if (base in LAUNCHERS || stem in LAUNCHERS) return Verdict(false, "$fileName runs packages or scripts that are not on the command line")
        if (ext in SCRIPT_EXTENSIONS) return Verdict(false, "$fileName is a script (.$ext)")
        if (isWindows && ext !in WINDOWS_NATIVE) return Verdict(false, "$fileName has no .exe/.com extension, so it starts through cmd.exe /c")
        return null
    }

    /** PATH (+ PATHEXT on Windows) lookup, like the OS does for a bare program name. */
    fun resolveCommand(command: String, env: Map<String, String>, isWindows: Boolean, cwd: File? = null): File? {
        val cmd = command.trim().trim('"', '\'')
        if (cmd.isEmpty()) return null
        // Any separator or an absolute path (also a host-native one, e.g. C:\\… while checking POSIX rules) is a path, not a PATH lookup.
        val hasDir = cmd.contains('/') || cmd.contains('\\') || File(cmd).isAbsolute
        if (hasDir) {
            val f = File(cmd).let { if (it.isAbsolute || cwd == null) it else File(cwd, cmd) }
            return f.takeIf { it.isFile }
        }
        fun envOf(name: String) = env[name] ?: env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val dirs = envOf("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        val exts = if (isWindows) listOf("") + envOf("PATHEXT").orEmpty().ifBlank { ".COM;.EXE;.BAT;.CMD" }.split(';').filter { it.isNotBlank() } else listOf("")
        for (d in dirs) for (e in exts) {
            val f = File(d.trim('"'), cmd + e)
            if (f.isFile) return f
        }
        return null
    }
}
