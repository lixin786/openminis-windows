// OpenMinis Desktop — agent tools: shell, file_read, file_write, file_edit, list_dir, memory_write, memory_get.
// Tool names/semantics follow the Android app (tools/FileReadTool, FileWriteTool, FileEditTool,
// MemoryTools); paths are host paths instead of PRoot paths.
// Security hardening batch: shell commands go through security/CommandGuard (denylist hard block,
// metacharacter guard, read-only allowlist), file paths through security/PathGuard (hard refusal
// outside the workspace), approvals through security/FailClosedApprover (any failure = deny).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ShellKind
import com.openminis.desktop.security.CommandGuard
import com.openminis.desktop.security.FailClosedApprover
import com.openminis.desktop.security.PathGuard
import com.openminis.desktop.security.SessionShellApprovals
import org.json.JSONObject
import java.io.File

/** What the UI is asked to approve before a side-effecting tool runs. */
data class ApprovalRequest(
    val toolName: String, val summary: String, val detail: String,
    /**
     * What the dialog's second "allow" button remembers; null = no such button (just Allow / Deny).
     * Shell: an exact command for this app session only (never persisted); MCP: the launch fingerprint.
     */
    val onAlwaysAllow: (() -> Unit)? = null,
    /** Label of that button ("Always allow", "Allow for this session"). */
    val rememberLabel: String? = null,
)

fun interface ToolApprover {
    suspend fun approve(request: ApprovalRequest): Boolean
}

data class ToolOutcome(val content: String, val isError: Boolean = false, val denied: Boolean = false)

class ToolRegistry(
    private val settings: () -> AppSettings,
    private val memory: MemoryStore,
    approver: ToolApprover,
    /**
     * Extra folders the agent may READ (never write): the app's skills folder (the model reads
     * SKILL.md files listed in the system prompt) and the attachments folder (host paths of
     * attached files are given to the model). The workspace is always readable and writable.
     */
    private val readRoots: () -> List<File>,
) {
    /** Workspace-only file access (tests, simple callers); keeps `ToolRegistry(s, m) { approve }` working. */
    constructor(settings: () -> AppSettings, memory: MemoryStore, approver: ToolApprover) :
        this(settings, memory, approver, { emptyList() })

    private val approver: ToolApprover = FailClosedApprover.wrap(approver)
    private fun ws(): File = settings().workspace().also { it.mkdirs() }

    /** Lexical resolution only (no confinement) — kept for display; tools use [confine]. */
    fun resolvePath(raw: String): File {
        val p = raw.trim().removeSurrounding("\"")
        val expanded = if (p.startsWith("~")) System.getProperty("user.home") + p.drop(1) else p
        val f = File(expanded)
        return (if (f.isAbsolute) f else File(ws(), expanded)).normalize()
    }

    /** [write] = workspace only; read = workspace + [readRoots]. */
    private fun confine(raw: String, write: Boolean): PathGuard.Result {
        val w = ws()
        val wsReal = PathGuard.realRoot(w) ?: return PathGuard.Result.Refused("the workspace folder ${w.absolutePath} does not exist")
        if (PathGuard.isFileSystemRoot(wsReal)) return PathGuard.Result.Refused("the workspace is a drive / file-system root ($wsReal); choose a folder in Settings › Tools & shell")
        return PathGuard.resolve(raw, w, if (write) listOf(w) else listOf(w) + readRoots())
    }

    private fun refused(r: PathGuard.Result.Refused, what: String) = ToolOutcome(
        "Refused: $what — ${r.reason}. File tools only work inside the workspace (${ws().absolutePath})" +
            " (reading is also allowed in the app's skills and attachments folders). This is a fixed safety rule, not a question for the user.",
        isError = true, denied = true,
    )

    fun definitions(): List<AgentToolDefinition> {
        val s = settings()
        val shellDesc = when {
            !com.openminis.desktop.core.AppPaths.isWindows -> "Run a bash command on the host (development fallback)."
            s.shell == ShellKind.WSL -> "Run a bash command inside WSL (Windows Subsystem for Linux)."
            s.shell == ShellKind.CMD -> "Run a cmd.exe command on Windows."
            else -> "Run a PowerShell command on Windows."
        }
        return buildList {
            add(AgentToolDefinition(
                name = "shell",
                description = "$shellDesc Working directory is the agent workspace (${ws().absolutePath}). " +
                    "Returns combined stdout/stderr and the exit code. Non-interactive: never run commands that wait for input. " +
                    "Commands that could destroy data or the system are refused outright; others may need the user's approval.",
                parameters = linkedMapOf(
                    "command" to AgentToolParam("string", "The command line to execute."),
                    "timeout_seconds" to AgentToolParam("integer", "Optional timeout in seconds (default ${s.shellTimeoutSeconds}, max 1800)."),
                ),
                required = listOf("command"),
            ))
            add(AgentToolDefinition(
                name = "file_read",
                description = "Read a UTF-8 text file. Relative paths resolve against the workspace. Returns numbered lines; use offset/limit for large files.",
                parameters = linkedMapOf(
                    "path" to AgentToolParam("string", "File path (absolute, or relative to the workspace)."),
                    "offset" to AgentToolParam("integer", "1-based first line to return (optional)."),
                    "limit" to AgentToolParam("integer", "Maximum number of lines (optional, default 2000)."),
                ),
                required = listOf("path"),
            ))
            add(AgentToolDefinition(
                name = "file_write",
                description = "Create or overwrite a text file (parent folders are created). Set append=true to append instead.",
                parameters = linkedMapOf(
                    "path" to AgentToolParam("string", "File path (absolute, or relative to the workspace)."),
                    "content" to AgentToolParam("string", "Full file content to write."),
                    "append" to AgentToolParam("boolean", "Append instead of overwrite (optional)."),
                ),
                required = listOf("path", "content"),
            ))
            add(AgentToolDefinition(
                name = "file_edit",
                description = "Replace an exact string in a file. old_string must match exactly once unless replace_all is true.",
                parameters = linkedMapOf(
                    "path" to AgentToolParam("string", "File path (absolute, or relative to the workspace)."),
                    "old_string" to AgentToolParam("string", "Exact text to replace."),
                    "new_string" to AgentToolParam("string", "Replacement text."),
                    "replace_all" to AgentToolParam("boolean", "Replace every occurrence (optional)."),
                ),
                required = listOf("path", "old_string", "new_string"),
            ))
            add(AgentToolDefinition(
                name = "list_dir",
                description = "List the entries of a directory (default: the workspace).",
                parameters = linkedMapOf("path" to AgentToolParam("string", "Directory path (optional).")),
            ))
            if (s.memoryEnabled) {
                add(AgentToolDefinition(
                    name = "memory_write",
                    description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. Save user preferences, recurring patterns, key facts, project conventions. Never save secrets unless the user explicitly confirms. GLOBAL.md is read-only (user-maintained).",
                    parameters = linkedMapOf("content" to AgentToolParam("string", "Concise Markdown with a short heading (## Topic).")),
                    required = listOf("content"),
                ))
                add(AgentToolDefinition(
                    name = "memory_get",
                    description = "Search memory (ranked full-text search, Chinese and English; best matching entries first, recent entries boosted). " +
                        "scope 'daily' = daily logs only, 'all' = daily logs + GLOBAL.md. Not every keyword has to match. Leave keywords empty to return full files.",
                    parameters = linkedMapOf(
                        "scope" to AgentToolParam("string", "Memory scope.", listOf("daily", "all")),
                        "keywords" to AgentToolParam("string", "Search query: keywords or a short phrase, any language (optional)."),
                        "limit" to AgentToolParam("integer", "Maximum entries to return (optional, default 8)."),
                    ),
                ))
            }
        }
    }

    suspend fun execute(name: String, args: JSONObject): ToolOutcome = try {
        when (name) {
            "shell" -> shell(args)
            "file_read" -> fileRead(args)
            "file_write" -> fileWrite(args)
            "file_edit" -> fileEdit(args)
            "list_dir" -> listDir(args)
            "memory_write" -> ToolOutcome(memory.write(args.optString("content")))
            "memory_get" -> ToolOutcome(memory.get(args.optString("scope", "all"), args.optString("keywords", ""), args.optInt("limit", 8)))
            else -> ToolOutcome("Unknown tool: $name", isError = true)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutcome("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
    }

    /** Whether [cmd] needs the dialog under [s] (after the denylist said it may run at all). */
    internal fun needsApproval(cmd: String, s: AppSettings, verdict: CommandGuard.Verdict): Boolean = when {
        verdict.decision == CommandGuard.Decision.AUTO_ALLOW -> false
        // Session memory and "confirmation off" never cover metacharacters / interpreters / nested payloads.
        !CommandGuard.mayBeRememberedForSession(cmd) -> true
        SessionShellApprovals.contains(cmd) -> false
        else -> s.confirmShellCommands
    }

    private suspend fun shell(args: JSONObject): ToolOutcome {
        val s = settings()
        val cmd = args.optString("command").trim()
        if (cmd.isEmpty()) return ToolOutcome("Error: command is empty", isError = true)
        val verdict = CommandGuard.inspect(cmd, s.autoApproveReadOnlyShell)
        if (verdict.blocked) {
            com.openminis.app.logging.AppLogger.warning("ShellGuard", "blocked [${verdict.rule}] $cmd")
            return ToolOutcome(
                "Refused by the safety denylist: ${verdict.reason}\nThe command was NOT run and cannot be approved. " +
                    "Do not try to work around this rule; if the user really needs it, they must run it themselves.",
                isError = true, denied = true,
            )
        }
        val cwd = when (val r = confine(".", write = true)) {
            is PathGuard.Result.Allowed -> r.file
            is PathGuard.Result.Refused -> return refused(r, "shell working directory")
        }
        if (needsApproval(cmd, s, verdict)) {
            val remember = CommandGuard.mayBeRememberedForSession(cmd)
            val why = when {
                CommandGuard.hasMetacharacters(cmd) -> " (contains shell operators: redirection, pipes, chaining or substitution)"
                CommandGuard.isInterpreter(cmd) -> " (starts an interpreter)"
                else -> ""
            }
            val ok = approver.approve(ApprovalRequest(
                "shell", "Run ${s.shell.label} command$why", cmd,
                onAlwaysAllow = if (remember) ({ SessionShellApprovals.add(cmd) }) else null,
                rememberLabel = if (remember) "Allow for this session" else null,
            ))
            if (!ok) return ToolOutcome("The user declined to run this command.", isError = true, denied = true)
        }
        val timeout = args.optInt("timeout_seconds", s.shellTimeoutSeconds).coerceIn(1, 1800)
        val r = ShellRunner.run(s.shell, cmd, cwd, timeout)
        val status = when {
            r.timedOut -> "Timed out after ${timeout}s (process killed)."
            else -> "Exit code: ${r.exitCode}"
        }
        return ToolOutcome("${r.output.trimEnd()}\n\n$status", isError = r.timedOut || (r.exitCode ?: 1) != 0)
    }

    private fun fileRead(args: JSONObject): ToolOutcome {
        val f = when (val r = confine(args.optString("path"), write = false)) {
            is PathGuard.Result.Allowed -> r.file
            is PathGuard.Result.Refused -> return refused(r, "file_read")
        }
        if (!f.exists()) return ToolOutcome("Error: file not found: ${f.absolutePath}", isError = true)
        if (f.isDirectory) return listDirOf(f)
        if (f.length() > 20L * 1024 * 1024) return ToolOutcome("Error: file is larger than 20 MB", isError = true)
        val bytes = f.readBytes()
        if (bytes.take(8000).any { it == 0.toByte() }) return ToolOutcome("Error: ${f.name} looks like a binary file (${bytes.size} bytes)", isError = true)
        // UTF-8 when valid, else the Windows legacy code page (GBK on zh-CN) — reported so the model knows.
        val (decoded, cs) = com.openminis.desktop.core.TextDecoding.decodeTextFile(bytes)
        val lines = decoded.removePrefix("\uFEFF").lines()
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 2000).coerceIn(1, 10_000)
        val slice = lines.drop(offset - 1).take(limit)
        val body = slice.mapIndexed { i, l -> "${(offset + i).toString().padStart(6)}\t${if (l.length > 2000) l.take(2000) + "…" else l}" }.joinToString("\n")
        val more = (if (offset - 1 + slice.size < lines.size) "\n… (${lines.size} lines total; use offset to read more)" else "") +
            (if (cs != Charsets.UTF_8) "\n(decoded as ${cs.name()} — the file is not UTF-8)" else "")
        return ToolOutcome(ShellRunner.truncate(body, 60_000).first + more)
    }

    private fun fileWrite(args: JSONObject): ToolOutcome {
        val f = when (val r = confine(args.optString("path"), write = true)) {
            is PathGuard.Result.Allowed -> r.file
            is PathGuard.Result.Refused -> return refused(r, "file_write")
        }
        val content = args.optString("content")
        val append = args.optBoolean("append", false)
        if (f.isDirectory) return ToolOutcome("Error: ${f.absolutePath} is a directory", isError = true)
        f.parentFile?.mkdirs()
        if (append) f.appendText(content, Charsets.UTF_8) else f.writeText(content, Charsets.UTF_8)
        return ToolOutcome("${if (append) "Appended" else "Wrote"} ${content.toByteArray().size} bytes to ${f.absolutePath}")
    }

    private fun fileEdit(args: JSONObject): ToolOutcome {
        val f = when (val r = confine(args.optString("path"), write = true)) {
            is PathGuard.Result.Allowed -> r.file
            is PathGuard.Result.Refused -> return refused(r, "file_edit")
        }
        if (!f.isFile) return ToolOutcome("Error: file not found: ${f.absolutePath}", isError = true)
        val old = args.optString("old_string")
        val new = args.optString("new_string")
        if (old.isEmpty()) return ToolOutcome("Error: old_string is empty", isError = true)
        val text = f.readText()
        val count = countOccurrences(text, old)
        if (count == 0) return ToolOutcome("Error: old_string not found in ${f.name}", isError = true)
        val all = args.optBoolean("replace_all", false)
        if (count > 1 && !all) return ToolOutcome("Error: old_string matches $count times; add context or set replace_all", isError = true)
        f.writeText(if (all) text.replace(old, new) else text.replaceFirst(old, new))
        return ToolOutcome("Edited ${f.absolutePath} ($count replacement${if (count > 1 && all) "s" else ""})")
    }

    private fun listDir(args: JSONObject): ToolOutcome {
        val raw = args.optString("path", "").ifBlank { "." }
        val d = when (val r = confine(raw, write = false)) {
            is PathGuard.Result.Allowed -> r.file
            is PathGuard.Result.Refused -> return refused(r, "list_dir")
        }
        return listDirOf(d)
    }

    private fun listDirOf(d: File): ToolOutcome {
        if (!d.isDirectory) return ToolOutcome("Error: not a directory: ${d.absolutePath}", isError = true)
        val entries = (d.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val body = entries.take(500).joinToString("\n") { if (it.isDirectory) "${it.name}/" else "${it.name}  (${it.length()} B)" }
        return ToolOutcome("${d.absolutePath}\n${body.ifEmpty { "(empty)" }}" + if (entries.size > 500) "\n… ${entries.size - 500} more" else "")
    }
}

internal fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = text.indexOf(needle)
    while (idx >= 0) { count++; idx = text.indexOf(needle, idx + needle.length) }
    return count
}
