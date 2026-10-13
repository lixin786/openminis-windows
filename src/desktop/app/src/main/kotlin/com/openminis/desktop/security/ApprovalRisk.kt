// OpenMinis Desktop — risk tier of an approval request (UI-REFRESH §1.1). Pure, unit-tested.
// Presentation only: it never relaxes CommandGuard (blocked stays blocked, auto-allow rules unchanged).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

enum class ApprovalKind { SHELL, FILE, MCP_START, MCP_CALL, SCHEDULED, SUBAGENT, BROWSER, OTHER }

/** LOW = inline one-line confirm; MEDIUM = card dialog; HIGH = card + "I understand" / type-the-name. */
enum class Risk { LOW, MEDIUM, HIGH }

object ApprovalRisk {
    /** Why a shell command is rated as it is; [notes] are i18n keys (risk.*), most important first. */
    data class ShellAssessment(
        val risk: Risk,
        /** i18n key of the one-line consequence (consequence.*). */
        val consequence: String,
        val notes: List<String>,
        /** First thing a delete command removes (as written), null when not a delete. */
        val deleteTarget: String? = null,
    ) {
        val isDelete get() = deleteTarget != null
        val irreversible get() = isDelete || "risk.overwrite" in notes
    }

    private val SCRIPT_EXT = Regex("""(?i)\.(bat|cmd|ps1|psm1|vbs|vbe|js|jse|wsf|sh|py|msi|reg)$""")
    private val OVERWRITE_CMDS = setOf("set-content", "sc", "out-file", "clear-content", "clc", "truncate", "tee")
    private val MAY_OVERWRITE_CMDS = setOf("mv", "move", "move-item", "mi", "cp", "copy", "copy-item", "cpi", "xcopy", "robocopy", "ren", "rename", "rename-item")
    private val GIT_DESTRUCTIVE = listOf(Regex("""(?i)^git\s+clean\b"""), Regex("""(?i)^git\s+reset\s+.*--hard\b"""), Regex("""(?i)^git\s+checkout\s+--\s"""),
        Regex("""(?i)^git\s+push\s+.*(--force|-f)\b"""), Regex("""(?i)^git\s+branch\s+-D\b"""))
    /** `> file` that is not `>>`, `2>&1`, `>&2`, `> $null`, `>nul`, `> /dev/null`. */
    private val OVERWRITE_REDIRECT = Regex("""(?<![>0-9&])[12]?>(?![>&])\s*(?!\${'$'}null\b|nul\b|NUL\b|/dev/null\b)\S""")
    private val APPEND_REDIRECT = Regex(""">>\s*\S""")

    /**
     * @param workspace when given, an absolute / `~` / `..` path that really resolves inside it (PathGuard: realpath,
     * symlinks, Windows case rules) no longer raises "outside the workspace". Anything not plainly a path
     * (variables, `%X%`, provider/ADS colons, flag values) stays flagged — the safe side.
     */
    fun shell(command: String, workspace: java.io.File? = null): ShellAssessment {
        val cmd = command.trim()
        val notes = mutableListOf<String>()
        var risk = Risk.MEDIUM
        fun high(note: String) { risk = Risk.HIGH; if (note !in notes) notes += note }
        fun medium(note: String) { if (note !in notes) notes += note }

        val payloads = runCatching { CommandGuard.collectPayloads(cmd, 0) }.getOrNull()
        if (payloads == null || payloads.size > 1) high("risk.nested")
        if (CommandGuard.isInterpreter(cmd)) high("risk.interp")

        var deleteTarget: String? = null
        var outsideWrite = false
        for (seg in CommandGuard.segments(cmd)) {
            val tokens = CommandGuard.tokenize(seg)
            val start = tokens.indexOfFirst { !CommandGuard.isWrapper(CommandGuard.commandName(it)) && !Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(it) }
            if (start < 0) continue
            val name = CommandGuard.commandName(tokens[start])
            val args = tokens.drop(start + 1)
            if (SCRIPT_EXT.containsMatchIn(tokens[start])) high("risk.script")
            val readOnly = CommandGuard.isReadOnly(seg)
            when {
                CommandGuard.isDeleteCommand(name) -> {
                    high("risk.delete")
                    if (deleteTarget == null) deleteTarget = args.firstOrNull { !it.startsWith("-") && !Regex("^/[A-Za-z?]{1,2}$").matches(it) }
                }
                name in OVERWRITE_CMDS && args.none { it.equals("-Append", true) || it == "-a" } -> high("risk.overwrite")
                name in MAY_OVERWRITE_CMDS -> medium("risk.may_overwrite")
                GIT_DESTRUCTIVE.any { it.containsMatchIn(seg.trim()) } -> high("risk.git")
            }
            if (!readOnly && args.any { CommandGuard.isOutsideReference(it) && !provablyInside(it, workspace) }) outsideWrite = true
        }
        if (OVERWRITE_REDIRECT.containsMatchIn(cmd.replace(APPEND_REDIRECT, " "))) high("risk.overwrite")
        else if (APPEND_REDIRECT.containsMatchIn(cmd)) medium("risk.append")
        if (outsideWrite) high("risk.outside")
        if (risk != Risk.HIGH && CommandGuard.hasMetacharacters(cmd)) medium("risk.meta")

        if (risk == Risk.MEDIUM && notes.isEmpty() && CommandGuard.isReadOnly(cmd)) {
            return ShellAssessment(Risk.LOW, "consequence.readonly", emptyList())
        }
        val consequence = when {
            deleteTarget != null -> "consequence.delete"
            "risk.overwrite" in notes -> "consequence.overwrite"
            risk == Risk.HIGH -> "consequence.high"
            else -> "consequence.run"
        }
        // Most specific first: delete > overwrite > outside > git > script > interp > nested > others.
        val order = listOf("risk.delete", "risk.overwrite", "risk.outside", "risk.git", "risk.script", "risk.interp", "risk.nested",
            "risk.may_overwrite", "risk.append", "risk.meta")
        return ShellAssessment(risk, consequence, notes.sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }, deleteTarget)
    }

    private val DRIVE_ABS = Regex("""^[A-Za-z]:[\\/]""")

    internal fun provablyInside(token: String, workspace: java.io.File?): Boolean {
        if (workspace == null) return false
        val t = token.trim().removeSurrounding("\"").removeSurrounding("'")
        if (t.isEmpty() || t.startsWith("-") || t.any { it in "\$%`*?<>|" }) return false
        // A colon is only acceptable as the drive letter of an absolute Windows path (not HKLM:, Env:, a.txt:stream).
        if (t.contains(':') && !(DRIVE_ABS.containsMatchIn(t) && t.indexOf(':', 2) < 0)) return false
        if (t.startsWith("\\\\")) return false // UNC
        return PathGuard.resolve(t, workspace, listOf(workspace)) is PathGuard.Result.Allowed
    }

    /**
     * What a HIGH request makes the user type (GitHub Danger Zone): the base name of a plain delete target.
     * Null (= tick "I understand" instead) when there is no target or it holds wildcards / variables / is a root.
     */
    fun confirmPhrase(a: ShellAssessment): String? {
        val t = a.deleteTarget?.trim()?.trim('"', '\'') ?: return null
        if (t.isEmpty() || t.any { it in "*?[]{}\$%~`" }) return null
        val base = t.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        if (base.isEmpty() || base == "." || base == ".." || base.endsWith(":")) return null
        return base
    }

    /** The typed phrase matches (trimmed; case-insensitive because Windows paths are). */
    fun phraseMatches(expected: String?, typed: String): Boolean =
        expected == null || typed.trim().equals(expected, ignoreCase = true)
}
