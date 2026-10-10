// OpenMinis Desktop — shell command gate: denylist hard block, metacharacter guard, read-only allowlist,
// "never remember interpreters". Pure functions: no UI, no file-system access, no process spawning,
// so every rule is unit-tested (src/test/.../security/CommandGuardTest.kt, AttackRegressionTest.kt).
// Security hardening batch (README "安全加固", layers 1, 2, 3 and 7).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import java.util.Base64

/**
 * Decides what happens to a shell command before it reaches `ShellRunner`:
 *
 *  - [Decision.BLOCK]      refused outright, never offered for approval; the tool result says why.
 *  - [Decision.CONFIRM]    the user must approve it.
 *  - [Decision.AUTO_ALLOW] read-only allowlisted command, no metacharacters, no nested payload.
 *
 * The denylist runs on the command and on every nested payload it can reach, recursively
 * (`sh -c`, `bash -c`, `eval`, `$(…)`, backticks, `cmd /c`, `powershell -Command`,
 * `powershell -EncodedCommand <base64 UTF-16LE>`, `sudo`/`env`/`xargs`/`wsl` prefixes …) after
 * `${IFS}` / `$IFS` expansion. A payload that cannot be decoded fails closed (BLOCK).
 * Windows command names are matched case-insensitively.
 */
object CommandGuard {

    enum class Decision { BLOCK, CONFIRM, AUTO_ALLOW }

    /** [rule] is a short stable id (tests, log); [reason] is shown to the model and the user. */
    data class Verdict(val decision: Decision, val reason: String = "", val rule: String = "") {
        val blocked: Boolean get() = decision == Decision.BLOCK
    }

    /** Layer 2: any of these disables every "no confirmation" fast path. Newlines count. */
    const val METACHARACTERS = "><|;&`$()\n\r"

    private const val MAX_NESTING = 8
    private const val D = '$'

    // ================================================================== public API

    /**
     * Full gate for one command line.
     * @param readOnlyAutoApprove Settings → "Run read-only commands without asking" (layer 3).
     */
    fun inspect(command: String, readOnlyAutoApprove: Boolean = true): Verdict {
        if (command.indexOf('\u0000') >= 0) return block("The command contains a NUL character — refused.", "nul")
        val payloads = try {
            collectPayloads(command, 0)
        } catch (e: Undecodable) {
            return block(e.message ?: "A nested payload could not be decoded — refused (fail-closed).", "nested-undecodable")
        }
        for (p in payloads) denylist(p)?.let { return it }
        if (readOnlyAutoApprove && payloads.size == 1 && isReadOnly(command)) {
            return Verdict(Decision.AUTO_ALLOW, "Read-only command without shell metacharacters.", "read-only")
        }
        return Verdict(Decision.CONFIRM, "Needs the user's approval.", "confirm")
    }

    /** The bare program name ([commandName] of a single token) is an interpreter (layer 7 table). */
    fun isInterpreterName(name: String): Boolean = name.lowercase() in INTERPRETERS

    fun hasMetacharacters(command: String): Boolean = command.any { it in METACHARACTERS }

    /** Layer 7: the command starts an interpreter (also behind sudo / env / start / FOO=bar prefixes). */
    fun isInterpreter(command: String): Boolean {
        val tokens = tokenize(expandIfs(command))
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val name = commandName(t)
            when {
                Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(t) -> i++
                name in WRAPPERS -> i++
                t.startsWith("-") || (t.startsWith("/") && t.length <= 3) -> i++ // wrapper flags (sudo -u, start /b)
                else -> return name in INTERPRETERS
            }
        }
        return false
    }

    /**
     * May an approval of this exact command be remembered for the rest of the app session?
     * Never for interpreters, metacharacters, nested payloads or anything the denylist blocks.
     * Nothing about shell commands is ever written to disk (README: Shell 侧不允许出现永久放行).
     */
    fun mayBeRememberedForSession(command: String): Boolean =
        command.isNotBlank() && !isInterpreter(command) && !hasMetacharacters(command) &&
            runCatching { collectPayloads(command, 0).size == 1 }.getOrDefault(false) &&
            !inspect(command, readOnlyAutoApprove = false).blocked

    /**
     * Layer 3: an allowlisted read-only command whose arguments neither re-enable writes or execution
     * nor point outside the working directory (absolute / home / drive / UNC paths, `..`, env vars).
     */
    fun isReadOnly(command: String): Boolean {
        if (hasMetacharacters(command) || command.contains('%') || command.contains('\u0000')) return false
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return false
        val cmd = commandName(tokens.first())
        if (cmd !in READ_ONLY) return false
        if (tokens.first().contains('/') || tokens.first().contains('\\')) return false // ./ls, C:\x\dir.exe
        for (a in tokens.drop(1)) {
            val low = a.lowercase()
            if (BANNED_READ_ONLY_ARGS.any { low == it || low.startsWith("$it=") || low.startsWith("$it:") }) return false
            if (isOutsideReference(a)) return false
        }
        return true
    }

    // ================================================================== payload discovery

    private class Undecodable(message: String) : Exception(message)

    /** The command and every nested payload reachable from it (outermost first). */
    internal fun collectPayloads(command: String, depth: Int): List<String> {
        val expanded = expandIfs(command)
        if (depth >= MAX_NESTING) throw Undecodable("Commands nested more than $MAX_NESTING levels deep — refused (fail-closed).")
        val out = mutableListOf(expanded)
        for (sub in commandSubstitutions(expanded)) out += collectPayloads(sub, depth + 1)
        for (seg in segments(expanded)) for (n in nestedPayloads(seg)) out += collectPayloads(n, depth + 1)
        return out
    }

    /** `$(…)` (balanced) and `` `…` `` bodies; PowerShell's `$(…)` subexpressions too. */
    internal fun commandSubstitutions(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == D && i + 1 < s.length && s[i + 1] == '(') {
                var depth = 0
                var j = i + 1
                while (j < s.length) {
                    if (s[j] == '(') depth++ else if (s[j] == ')') { depth--; if (depth == 0) break }
                    j++
                }
                out += s.substring(i + 2, minOf(j, s.length))
                i = j + 1
                continue
            }
            if (s[i] == '`') {
                val j = s.indexOf('`', i + 1)
                // In PowerShell a backtick is an escape char; scanning its "body" is harmless.
                if (j > i) { out += s.substring(i + 1, j); i = j + 1; continue }
            }
            i++
        }
        return out.filter { it.isNotBlank() }
    }

    private fun nestedPayloads(segment: String): List<String> =
        (nestedPayloads(tokenize(segment, posix = false)) + nestedPayloads(tokenize(segment, posix = true))).distinct()

    private fun nestedPayloads(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val head = commandName(tokens.first())
        val args = tokens.drop(1)
        val out = mutableListOf<String>()
        when (head) {
            "eval", "exec", "command", "builtin", "iex", "invoke-expression" -> if (args.isNotEmpty()) out += args.joinToString(" ")
            "sh", "bash", "zsh", "dash", "ksh", "ash", "fish", "busybox" -> {
                val i = args.indexOfFirst { it.startsWith("-") && !it.startsWith("--") && it.contains('c') }
                if (i >= 0 && i + 1 < args.size) out += args[i + 1]
                if (head == "busybox" && args.isNotEmpty()) out += args.joinToString(" ")
            }
            "powershell", "pwsh", "powershell_ise" -> {
                var found = false
                var i = 0
                while (i < args.size) {
                    val f = flagName(args[i])?.lowercase()
                    when {
                        f != null && f.isNotEmpty() && ("encodedcommand".startsWith(f) || f == "ec") -> {
                            val b64 = args.getOrNull(i + 1)
                                ?: throw Undecodable("PowerShell -EncodedCommand without a payload — refused (fail-closed).")
                            out += decodeEncodedCommand(b64); found = true; i += 2; continue
                        }
                        f != null && f.isNotEmpty() && "command".startsWith(f) -> {
                            if (i + 1 < args.size) out += args.drop(i + 1).joinToString(" ")
                            found = true; i = args.size; continue
                        }
                    }
                    i++
                }
                // `powershell "Remove-Item …"` (no -Command): Windows PowerShell treats the rest as a command.
                if (!found && args.isNotEmpty()) out += args.joinToString(" ")
            }
            "cmd" -> {
                val i = args.indexOfFirst { val f = flagName(it)?.lowercase(); f == "c" || f == "k" || f == "r" }
                if (i >= 0 && i + 1 < args.size) out += args.drop(i + 1).joinToString(" ")
                else args.firstOrNull { it.lowercase().startsWith("/c") && it.length > 2 }?.let { out += it.drop(2) }
            }
            in WRAPPERS -> {
                // Skip wrapper flags and VAR=value assignments, scan the rest as a command.
                val rest = args.dropWhile { it.startsWith("-") || Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(it) || (head == "start" && it.startsWith("/")) }
                if (rest.isNotEmpty()) out += rest.joinToString(" ")
                // Wrapper options with values (timeout 5 …, sudo -u root …, nice -n 10 …): also try later starts.
                for (k in 1..minOf(3, rest.size - 1)) out += rest.drop(k).joinToString(" ")
            }
        }
        return out
    }

    private fun decodeEncodedCommand(b64: String): String {
        val clean = b64.trim().trim('"', '\'')
        val raw = runCatching { Base64.getDecoder().decode(clean) }.getOrNull()
            ?: throw Undecodable("PowerShell -EncodedCommand payload is not valid base64 — refused (fail-closed).")
        if (raw.size % 2 != 0) throw Undecodable("PowerShell -EncodedCommand payload is not UTF-16LE — refused (fail-closed).")
        val text = String(raw, Charsets.UTF_16LE)
        if (text.isBlank() || text.any { it == '\uFFFD' }) throw Undecodable("PowerShell -EncodedCommand payload did not decode — refused (fail-closed).")
        return text
    }

    // ================================================================== denylist (layer 1)

    private fun denylist(payload: String): Verdict? {
        val squashed = payload.replace(Regex("\\s+"), "")
        if (FORK_BOMB.any { it.containsMatchIn(squashed) }) return block("Fork bomb — refused.", "fork-bomb")
        REDIRECT_DEV.find(payload)?.let { return block("Writes straight to the block device ${it.groupValues[1]} — refused.", "redirect-block-device") }
        for (seg in segments(payload)) (rules(seg, posix = false) ?: rules(seg, posix = true))?.let { return it }
        return null
    }

    private fun rules(segment: String, posix: Boolean): Verdict? {
        val tokens = tokenize(segment, posix)
        if (tokens.isEmpty()) return null
        val cmd = commandName(tokens.first())
        val args = tokens.drop(1)
        val lower = segment.lowercase()

        if (cmd.startsWith("mkfs") || cmd == "mke2fs" || cmd == "newfs") return block("Formats a file system ($cmd) — refused.", "mkfs")

        when (cmd) {
            "dd" -> if (DD_OF_DEV.containsMatchIn(segment)) return block("dd writing to a device (of=/dev/…) — refused.", "dd-of-dev")
            "shred", "wipefs", "blkdiscard", "sgdisk", "fdisk", "parted" ->
                if (args.any { it.contains("/dev/") }) return block("$cmd on a block device — refused.", "disk-dev")
            "shutdown" -> if (args.none { flagName(it)?.lowercase() in setOf("a", "c") && args.size == 1 })
                return block("Shuts the computer down or restarts it — refused.", "power")
            "poweroff", "reboot", "halt", "stop-computer", "restart-computer" ->
                return block("Shuts the computer down or restarts it — refused.", "power")
            "init", "telinit" -> if (args.firstOrNull() in setOf("0", "6")) return block("Halts / reboots via runlevel — refused.", "power")
            "systemctl" -> if (args.any { it in setOf("poweroff", "reboot", "halt", "kexec") }) return block("Shuts the computer down or restarts it — refused.", "power")
            "chmod", "chown", "chgrp" -> {
                val recursive = args.any { isShortFlagCluster(it, 'R') } || args.any { it.equals("--recursive", true) }
                if (recursive && args.filterNot { it.startsWith("-") }.any { isDangerousUnixTarget(it) || isDangerousWindowsTarget(it) })
                    return block("Recursive $cmd on the file-system root or home — refused.", "chmod-root")
            }
            "mv", "move", "move-item", "mi" -> {
                val targets = args.filterNot { it.startsWith("-") || (cmd == "move" && it.startsWith("/") && it.length <= 3) }
                if (targets.any { isDangerousUnixTarget(it) || isDangerousWindowsTarget(it) })
                    return block("Moves the file-system root / home, or moves files onto it — refused.", "mv-root")
            }
            "find" -> {
                val start = args.takeWhile { !it.startsWith("-") }
                if (start.any { isDangerousUnixTarget(it) || isDangerousWindowsTarget(it) } &&
                    (args.any { it == "-delete" } || args.indexOfFirst { it == "-exec" || it == "-execdir" }.let { i -> i >= 0 && args.getOrNull(i + 1)?.let(::commandName) in DELETE_COMMANDS }))
                    return block("find deleting from the file-system root or home — refused.", "find-delete-root")
            }
            "format" -> if (args.any { Regex("(?i)^[a-z]:\\\\?$").matches(it) }) return block("Formats a drive — refused.", "format")
            "format-volume", "clear-disk", "initialize-disk", "remove-partition", "set-partition" ->
                return block("PowerShell disk management ($cmd) can wipe a volume — refused.", "disk-cmdlet")
            "diskpart" -> return block("diskpart can repartition and wipe disks — refused.", "diskpart")
            "cipher" -> if (args.any { flagName(it)?.lowercase()?.startsWith("w") == true }) return block("cipher /w overwrites all free space — refused.", "cipher-w")
            "bcdedit", "bootrec", "bcdboot" -> return block("$cmd changes the boot configuration — refused.", "boot-config")
            "vssadmin" -> if (lower.contains("delete") || lower.contains("resize")) return block("Deletes volume shadow copies — refused.", "shadow-delete")
            "wmic" -> if (lower.contains("shadowcopy") && lower.contains("delete")) return block("Deletes volume shadow copies — refused.", "shadow-delete")
            "wbadmin" -> if (lower.contains("delete")) return block("Deletes Windows backups — refused.", "shadow-delete")
            "reg" -> if (args.firstOrNull()?.equals("delete", true) == true &&
                args.drop(1).any { val k = it.uppercase(); k.startsWith("HKLM") || k.startsWith("HKEY_LOCAL_MACHINE") })
                return block("Deletes a machine-wide registry key (HKLM) — refused.", "reg-delete-hklm")
        }

        // Deletes: POSIX rm, cmd rd/rmdir/del/erase, PowerShell Remove-Item and its aliases.
        if (cmd in DELETE_COMMANDS) {
            val recursive = args.any { isShortFlagCluster(it, 'r') || isShortFlagCluster(it, 'R') } ||
                args.any { a ->
                    val f = flagName(a)?.lowercase() ?: return@any false
                    f == "recursive" || f == "s" || (f.length >= 2 && "recurse".startsWith(f))
                }
            val targets = args.filterNot { it.startsWith("-") || isWindowsSwitch(it) }
            targets.firstOrNull { isDangerousUnixTarget(it) || isDangerousWindowsTarget(it) }?.let { t ->
                val bareRoot = normalizeUnix(t) == "/" || isWindowsDriveRoot(t)
                if (recursive || bareRoot || cmd in setOf("del", "erase") || t.trim().endsWith("*"))
                    return block("Recursive delete of \"$t\" (file-system root, drive root, home or a system folder) — refused.", "delete-root")
            }
            if (recursive) targets.firstOrNull { hasExpansion(it) && rootish(it) }?.let { t ->
                return block("Recursive delete of \"$t\": a dangerous location combined with a variable expansion that cannot be checked — refused (fail-closed).", "delete-variable")
            }
        }
        return null
    }

    private fun block(reason: String, rule: String) = Verdict(Decision.BLOCK, reason, rule)

    // ================================================================== targets

    /** `${IFS}` / `$IFS` → space (a classic denylist bypass: `rm${IFS}-rf${IFS}/`). */
    internal fun expandIfs(command: String): String =
        command.replace("$D{IFS}", " ").replace(Regex("\\$D" + "IFS(?![A-Za-z0-9_])"), " ")

    /** Lexical normalisation: `/tmp/../` → `/`, `~/`, `$HOME`, `${HOME}`, `%USERPROFILE%` → `~`. */
    internal fun normalizeUnix(rawToken: String): String {
        var t = rawToken.trim().trim('"', '\'')
        if (t.isEmpty()) return t
        t = t.replace("$D{HOME}", "~").replace("${D}HOME", "~")
            .replace("${D}env:USERPROFILE", "~", ignoreCase = true).replace("${D}env:HOME", "~", ignoreCase = true)
            .replace("%USERPROFILE%", "~", ignoreCase = true).replace("%HOMEPATH%", "~", ignoreCase = true)
        t = t.replace(Regex("/+"), "/")
        val absolute = t.startsWith("/")
        val home = t == "~" || t.startsWith("~/")
        val parts = (if (home) t.drop(1) else t).split('/').filter { it.isNotEmpty() }
        val stack = ArrayDeque<String>()
        for (p in parts) when (p) {
            "." -> {}
            ".." -> if (stack.isNotEmpty() && stack.last() != "..") stack.removeLast() else if (!absolute && !home) stack.addLast("..")
            else -> stack.addLast(p)
        }
        val body = stack.joinToString("/")
        return when {
            home -> if (body.isEmpty()) "~" else "~/$body"
            absolute -> "/$body"
            else -> body.ifEmpty { "." }
        }
    }

    /** Root, root glob, home, home glob, a bare `*`, `..` escapes to them (after normalisation). */
    internal fun isDangerousUnixTarget(rawToken: String): Boolean {
        val t = rawToken.trim().trim('"', '\'')
        if (t.isEmpty()) return false
        if (t == "*" || t == "./*" || t == ".*" || t == "./.*") return true
        val n = normalizeUnix(t)
        if (n == "/" || n == "~") return true
        if (n == "/*" || n == "~/*" || n == "/.*" || n == "~/.*") return true
        // Top-level system folders: /etc, /usr, /bin, /home … (one path element below the root).
        if (Regex("^/(bin|boot|dev|etc|home|lib|lib64|opt|proc|root|sbin|srv|sys|usr|var|mnt|Users|System|Applications)/?\\*?$").matches(n)) return true
        return false
    }

    /** `C:`, `C:\`, `C:\*`, `C:\Windows`, `%SystemRoot%`, `%USERPROFILE%`, `$env:USERPROFILE`, `\\server\share`. */
    internal fun isDangerousWindowsTarget(rawToken: String): Boolean {
        val t0 = rawToken.trim().trim('"', '\'')
        if (t0.isEmpty()) return false
        var t = t0.replace('/', '\\')
        if (t.startsWith("\\\\?\\") || t.startsWith("\\\\.\\")) t = t.drop(4)
        if (t.startsWith("UNC\\", ignoreCase = true)) t = "\\\\" + t.drop(4)
        val up = collapseWindows(t).uppercase().trimEnd('\\')
        val bare = up.replace(Regex("(\\\\?\\*(\\.\\*)?)+$"), "").trimEnd('\\')
        if (bare.matches(Regex("[A-Z]:"))) return true
        val vars = setOf("%USERPROFILE%", "${D}ENV:USERPROFILE", "%SYSTEMROOT%", "${D}ENV:SYSTEMROOT", "%WINDIR%", "${D}ENV:WINDIR",
            "%SYSTEMDRIVE%", "${D}ENV:SYSTEMDRIVE", "%HOMEDRIVE%", "%PROGRAMFILES%", "${D}ENV:PROGRAMFILES", "%APPDATA%", "${D}ENV:APPDATA",
            "%LOCALAPPDATA%", "${D}ENV:LOCALAPPDATA", "~", "${D}HOME", "%PROGRAMDATA%", "${D}ENV:PROGRAMDATA")
        if (bare in vars) return true
        if (bare.matches(Regex("(%SYSTEMDRIVE%|\\${'$'}ENV:SYSTEMDRIVE|[A-Z]:)\\\\(WINDOWS|WINDOWS\\\\SYSTEM32|PROGRAM FILES|PROGRAM FILES \\(X86\\)|PROGRAMDATA|USERS|USERS\\\\[^\\\\]+)"))) return true
        if (bare.matches(Regex("(%SYSTEMROOT%|%WINDIR%|\\${'$'}ENV:SYSTEMROOT|\\${'$'}ENV:WINDIR)\\\\SYSTEM32"))) return true
        if (bare.matches(Regex("\\\\\\\\[^\\\\]+(\\\\[^\\\\]+)?"))) return true
        return false
    }

    private fun isWindowsDriveRoot(rawToken: String): Boolean =
        Regex("(?i)^[a-z]:[\\\\/]?$").matches(rawToken.trim().trim('"', '\''))

    private fun collapseWindows(path: String): String {
        val prefix = Regex("^([A-Za-z]:\\\\?|\\\\\\\\[^\\\\]+\\\\?|\\\\)").find(path)?.value ?: ""
        val stack = ArrayDeque<String>()
        for (p in path.removePrefix(prefix).split('\\').filter { it.isNotEmpty() }) when (p) {
            "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(p)
        }
        val pre = if (Regex("^[A-Za-z]:$").matches(prefix) && stack.isNotEmpty()) "$prefix\\" else prefix
        return pre + stack.joinToString("\\")
    }

    /** `$x`, `${x}`, `%VAR%`, `$env:X`, backticks — an expansion we cannot evaluate statically. */
    internal fun hasExpansion(rawToken: String): Boolean {
        val t = rawToken.trim().trim('"', '\'')
        return Regex("\\$D\\{?[A-Za-z_][A-Za-z0-9_:]*\\}?").containsMatchIn(t) ||
            Regex("%[A-Za-z_][A-Za-z0-9_()]*%").containsMatchIn(t) || t.contains('`') || t.contains("$D(")
    }

    /** Starts at a root / home / drive, or is an expansion followed by nothing but `/`, `\` or a glob. */
    private fun rootish(rawToken: String): Boolean {
        val t = rawToken.trim().trim('"', '\'')
        if (t.startsWith("/") || t.startsWith("~") || t.startsWith("\\")) return true
        if (Regex("(?i)^[a-z]:").containsMatchIn(t)) return true
        // "$STEAMROOT/"*  ·  ${x}/  ·  %X%\*  ·  $env:X\*
        return Regex("^(\\$D\\{?[A-Za-z_][A-Za-z0-9_:]*\\}?|%[A-Za-z_][A-Za-z0-9_()]*%)[/\\\\]*\\*?$").matches(t) &&
            (t.endsWith("/") || t.endsWith("\\") || t.endsWith("*"))
    }

    /** An argument that refers outside the current directory (used only to deny auto-approval). */
    internal fun isOutsideReference(a: String): Boolean {
        val t = a.trim()
        if (t.startsWith("-") && !t.contains('/') && !t.contains('\\') && !t.contains(':')) return false
        if (isWindowsSwitch(t)) return false
        val v = if (t.startsWith("-") && (t.contains('=') || t.contains(':'))) t.substringAfter('=').substringAfter(':') else t
        if (v.startsWith("/") || v.startsWith("\\") || v.startsWith("~")) return true
        if (Regex("^[A-Za-z]:").containsMatchIn(v)) return true
        if (v.contains(':')) return true // ADS, drive, provider paths (Env:, HKLM:, Cert:)
        if (v.split('/', '\\').any { it == ".." }) return true
        return false
    }

    // ================================================================== lexing

    /**
     * Splits on `;`, `&&`, `||`, `|`, `&` and newlines outside quotes. Quoting is ambiguous between
     * POSIX shells (`\"` escapes a quote) and cmd / PowerShell (a backslash is a path separator), so
     * both readings are produced and the denylist checks the union.
     */
    internal fun segments(command: String): List<String> =
        (segmentsMode(command, posix = false) + segmentsMode(command, posix = true)).distinct()

    private fun segmentsMode(command: String, posix: Boolean): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote = ' '
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                posix && c == '\\' && quote != '\'' && i + 1 < command.length -> { sb.append(c).append(command[i + 1]); i++ }
                quote != ' ' -> { if (c == quote) quote = ' '; sb.append(c) }
                c == '"' || c == '\'' -> { quote = c; sb.append(c) }
                c == ';' || c == '\n' || c == '\r' || c == '|' || c == '&' -> {
                    out += sb.toString(); sb.clear()
                    if ((c == '|' || c == '&') && i + 1 < command.length && command[i + 1] == c) i++
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Whitespace tokenizer honouring '…' and "…" (quotes removed); `(` `)` `{` `}` at word edges stripped. */
    internal fun tokenize(segment: String, posix: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote = ' '
        var quotedEmpty = false
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            i++
            when {
                // POSIX: \" and \\ inside "…" (and any \x outside quotes) escape the next char.
                posix && c == '\\' && quote != '\'' && i < segment.length && (quote == ' ' || segment[i] == '"' || segment[i] == '\\' || segment[i] == '$' || segment[i] == '`') -> { sb.append(segment[i]); i++ }
                quote != ' ' -> if (c == quote) quote = ' ' else sb.append(c)
                c == '"' || c == '\'' -> { quote = c; quotedEmpty = true }
                c.isWhitespace() -> { if (sb.isNotEmpty() || quotedEmpty) out += sb.toString(); sb.clear(); quotedEmpty = false }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty() || quotedEmpty) out += sb.toString()
        // Redirection operators glued to words are not part of the word: "rm" ">/dev/x" etc. are still words.
        return out.map { it.trim('(', ')', '{', '}') }.filter { it.isNotEmpty() }
    }

    /** `/usr/bin/rm` → `rm`, `C:\Windows\System32\cmd.exe` → `cmd`, case-folded; a leading `&`/`.` call operator removed. */
    internal fun commandName(token: String): String =
        token.trim().trim('"', '\'').substringAfterLast('/').substringAfterLast('\\').lowercase()
            .removeSuffix(".exe").removeSuffix(".com")

    /** `-flag` / `--flag` / `/flag` → "flag" (null when the token is not a flag; paths like `/dev/x` are not). */
    private fun flagName(token: String): String? {
        val t = token.trim()
        return when {
            t.startsWith("--") -> t.drop(2).substringBefore('=').substringBefore(':')
            t.startsWith("-") && t.length > 1 -> t.drop(1).substringBefore('=').substringBefore(':')
            isWindowsSwitch(t) -> t.drop(1).substringBefore(':')
            else -> null
        }
    }

    /** `/s`, `/q`, `/c`, `/f:x` — but not `/`, `/tmp`, `/dev/sda`. Windows switches are 1-2 letters. */
    private fun isWindowsSwitch(t: String): Boolean =
        Regex("^/[A-Za-z?]{1,2}(:.*)?$").matches(t.trim()) && !Regex("^/(bin|dev|etc|usr|var|tmp|opt|mnt|srv|sys|lib|home|root|boot|proc|run|sbin)$").matches(t.trim())

    private fun isShortFlagCluster(token: String, c: Char): Boolean {
        val t = token.trim()
        return t.startsWith("-") && !t.startsWith("--") && t.length in 2..5 && t.drop(1).all { it.isLetter() } && t.drop(1).contains(c)
    }

    // ================================================================== tables

    internal fun isDeleteCommand(name: String): Boolean = name.lowercase() in DELETE_COMMANDS
    internal fun isWrapper(name: String): Boolean = name.lowercase() in WRAPPERS

    private val DELETE_COMMANDS = setOf("rm", "unlink", "rd", "rmdir", "del", "erase", "remove-item", "ri", "rni")

    private val WRAPPERS = setOf(
        "sudo", "doas", "env", "nohup", "nice", "ionice", "time", "timeout", "stdbuf", "xargs", "chroot", "su",
        "start", "start-process", "saps", "wsl", "runas", "call", "setsid", "watch", "parallel", "find-exec",
    )

    private val INTERPRETERS = setOf(
        "sh", "bash", "zsh", "dash", "ksh", "ash", "fish", "csh", "tcsh", "busybox",
        "python", "python2", "python3", "py", "pythonw", "node", "nodejs", "deno", "bun", "perl", "ruby", "irb", "php",
        "lua", "tclsh", "wish", "r", "rscript", "julia", "java", "jshell", "groovy", "scala", "dotnet",
        "pwsh", "powershell", "powershell_ise", "cmd", "wscript", "cscript", "mshta", "rundll32", "regsvr32",
        "msbuild", "installutil", "osascript", "wsl", "bash.exe", "eval", "iex", "invoke-expression", "source", ".",
    )

    /** Deliberately small: nothing here can write, delete, spawn or reach the network. */
    private val READ_ONLY = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "date", "which", "pwd", "whoami", "uname",
        "file", "stat", "basename", "dirname", "id", "uptime", "nproc", "md5sum", "sha1sum", "sha256sum", "cmp", "diff",
        "tree", "du", "df", "echo",
        "dir", "type", "where", "findstr", "ver", "vol",
        "get-childitem", "gci", "get-content", "gc", "select-string", "sls", "get-location", "gl", "get-date",
        "get-item", "gi", "get-command", "gcm", "measure-object", "write-output", "get-filehash",
    )

    /** Arguments that turn an allowlisted command into one that writes or executes. */
    private val BANNED_READ_ONLY_ARGS = setOf(
        "-exec", "-execdir", "-delete", "-fprint", "-fprintf", "-ok", "-okdir", "-o", "--output", "-i", "--in-place",
        "-s", "--set", "-w", "--write", "-outfile", "-outvariable", "-ov", "-literalpath", "-stream", "-wait",
        "--files0-from", "-f", "--file", "-r", "--reference",
    )

    private val FORK_BOMB = listOf(
        Regex(""":\(\)\{:\|:&\};:"""),
        Regex("""([A-Za-z_][A-Za-z0-9_]*|:)\(\)\{\1\|\1&\};\1"""),
        Regex("""fork\(\)\s*while|while\(1\)\{?fork|forkwhilefork|whiletrue;dofork""", RegexOption.IGNORE_CASE),
        Regex("""%0\|%0"""),
    )
    private val REDIRECT_DEV = Regex(""">\s*(/dev/(?:sd[a-z]+\d*|hd[a-z]+\d*|xvd[a-z]+\d*|vd[a-z]+\d*|nvme\d+n\d+(?:p\d+)?|mmcblk\d+(?:p\d+)?|disk\d+(?:s\d+)?|rdisk\d+|mapper/\S+|md\d+|dm-\d+|loop\d+))""")
    private val DD_OF_DEV = Regex("""(?i)\bof\s*=\s*["']?/dev/(?!null\b|zero\b|stdout\b|stderr\b)""")
}
