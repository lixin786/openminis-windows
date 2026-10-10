// OpenMinis Desktop — the five read-only tools the phone may call (batch 13.2, MCP-SERVER.md "工具").
// Every path goes through PathGuard with the workspace as the only root, then the sensitive-file denylist.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.remote

import com.openminis.desktop.security.PathGuard
import com.openminis.desktop.tools.MemorySearch
import com.openminis.desktop.tools.MemoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/** Default-deny list for remote reads (MCP-SERVER.md): `.env*`, `*.pem`, `*.key`, `id_rsa*`, and all of `.git/`. */
object SensitivePaths {
    /** [rel] is relative to the workspace, any separator. Case-insensitive (Windows file names are). */
    fun isDenied(rel: String): Boolean {
        val parts = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it.equals(".git", ignoreCase = true) }) return true
        val name = parts.lastOrNull()?.lowercase() ?: return false
        return name.startsWith(".env") || name.endsWith(".pem") || name.endsWith(".key") || name.startsWith("id_rsa")
    }
}

/** The MCP servers the desktop has configured, reduced to what may leave the machine. */
data class McpServerSummary(val name: String, val state: String, val toolCount: Int)

class RemoteReadTools(
    private val workspace: () -> File,
    private val memoryEnabled: () -> Boolean,
    private val memory: () -> MemoryStore,
    private val denySensitive: () -> Boolean,
    private val appVersion: String,
    /** Available shells, e.g. ["PowerShell", "cmd", "WSL"]. */
    private val shells: () -> List<String>,
    /** Name / state / tool count only — command lines, args and env may hold secrets and are never passed here. */
    private val mcpServers: () -> List<McpServerSummary>,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win"),
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteToolSet {
    companion object {
        const val LIST_MAX = 1000
        const val READ_DEFAULT_LINES = 2000
        const val READ_MAX_BYTES = 256 * 1024
        const val SEARCH_DEFAULT = 100
        const val SEARCH_MAX = 500
        const val SEARCH_FILE_MAX = 2L * 1024 * 1024
        const val SEARCH_BUDGET_MS = 5_000L
        const val SEARCH_LINE_MAX = 500
        const val MEMORY_MAX_CHARS = 20_000
        val SEARCH_SKIP_DIRS = setOf(".git", "node_modules", "build")
        private const val SNIFF = 8192
    }

    private class ToolError(message: String) : Exception(message)

    // ------------------------------------------------------------------ schemas

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }

    private fun t(type: String, desc: String? = null) = JSONObject().put("type", type).apply { desc?.let { put("description", it) } }
    private fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)

    private fun tool(name: String, desc: String, input: JSONObject, output: JSONObject) = JSONObject()
        .put("name", name).put("description", desc).put("inputSchema", input).put("outputSchema", output)
        .put("annotations", JSONObject().put("readOnlyHint", true).put("openWorldHint", false))

    override fun list(): JSONArray = JSONArray().apply {
        put(tool("list_dir", "List a directory in the desktop workspace (default: the workspace root). Up to $LIST_MAX entries.",
            obj("path" to t("string", "Directory, relative to the workspace (optional).")),
            obj("path" to t("string"), "entries" to arr(obj("name" to t("string"), "type" to t("string"), "size" to t("integer"), "modified" to t("string"))),
                "truncated" to t("boolean"), "hidden" to t("integer"), required = listOf("path", "entries", "truncated"))))
        put(tool("read_file", "Read a UTF-8 text file from the desktop workspace, with line numbers. Default $READ_DEFAULT_LINES lines, at most 256 KB; page with offset.",
            obj("path" to t("string", "File, relative to the workspace."), "offset" to t("integer", "First line, 1-based (optional)."),
                "limit" to t("integer", "Number of lines (optional, default $READ_DEFAULT_LINES)."), required = listOf("path")),
            obj("path" to t("string"), "offset" to t("integer"), "lines" to t("integer"), "content" to t("string"),
                "truncated" to t("boolean"), "next_offset" to t("integer"), required = listOf("path", "offset", "lines", "content", "truncated"))))
        put(tool("search_files", "Literal (not regex) text search in the desktop workspace. Skips binary files, files over 2 MB, and .git / node_modules / build.",
            obj("query" to t("string", "Text to find."), "path" to t("string", "Directory to search (optional)."),
                "glob" to t("string", "File name filter, e.g. *.kt or src/**/*.md (optional)."),
                "case_sensitive" to t("boolean", "Default false."), "max_results" to t("integer", "Default $SEARCH_DEFAULT, at most $SEARCH_MAX."),
                required = listOf("query")),
            obj("matches" to arr(obj("path" to t("string"), "line" to t("integer"), "text" to t("string"))),
                "truncated" to t("boolean"), "timed_out" to t("boolean"), required = listOf("matches", "truncated", "timed_out"))))
        if (memoryEnabled()) put(tool("memory_search", "Search the desktop's memory (daily logs; scope all adds GLOBAL.md). Empty keywords return the newest entries.",
            obj("keywords" to t("string", "Keywords or a short phrase (optional)."),
                "scope" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("daily", "all"))),
                "limit" to t("integer", "Default 8, at most 30.")),
            obj("scope" to t("string"), "results" to arr(obj("file" to t("string"), "line" to t("integer"), "text" to t("string"))),
                required = listOf("scope", "results"))))
        put(tool("system_info", "Desktop app version, OS, workspace path, available shells, and configured MCP servers (names and status only).",
            obj(),
            obj("app_version" to t("string"), "os" to t("string"), "workspace" to t("string"), "shells" to arr(t("string")),
                "mcp_servers" to arr(obj("name" to t("string"), "state" to t("string"), "tool_count" to t("integer"))),
                required = listOf("app_version", "os", "workspace", "shells", "mcp_servers"))))
    }

    override fun call(name: String, arguments: JSONObject): JSONObject? {
        val known = setOf("list_dir", "read_file", "search_files", "system_info") + (if (memoryEnabled()) setOf("memory_search") else emptySet())
        if (name !in known) return null
        return try {
            when (name) {
                "list_dir" -> listDir(arguments)
                "read_file" -> readFile(arguments)
                "search_files" -> searchFiles(arguments)
                "memory_search" -> memorySearch(arguments)
                else -> systemInfo()
            }
        } catch (e: ToolError) {
            error(e.message ?: "error")
        } catch (e: Exception) {
            // Class name only: messages can carry absolute paths outside the workspace.
            error("internal error (${e.javaClass.simpleName})")
        }
    }

    // ------------------------------------------------------------------ results

    private fun ok(structured: JSONObject, text: String = structured.toString()) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("structuredContent", structured)
        .put("isError", false)

    private fun error(message: String) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", true)

    // ------------------------------------------------------------------ paths

    private class Resolved(val file: File, val rel: String)

    private fun root(): Path = PathGuard.realRoot(workspace()) ?: throw ToolError("the workspace does not exist")

    private fun rel(root: Path, p: Path): String = root.relativize(p).toString().replace('\\', '/').ifEmpty { "." }

    private fun resolve(raw: String?): Resolved {
        val input = raw?.takeIf { it.isNotBlank() } ?: "."
        val ws = workspace()
        val root = root()
        when (val r = PathGuard.resolve(input, ws, listOf(ws), windows)) {
            is PathGuard.Result.Refused ->
                // Never echo a resolved location outside the workspace (a symlink target, say).
                throw ToolError(if (r.reason.endsWith("is outside the workspace")) "refused: the path is outside the workspace" else "refused: ${r.reason}")
            is PathGuard.Result.Allowed -> {
                val p = r.file.toPath()
                val rel = rel(root, p)
                if (denySensitive() && SensitivePaths.isDenied(rel)) throw ToolError("refused: sensitive file (denied by default for remote reads)")
                return Resolved(r.file, rel)
            }
        }
    }

    // ------------------------------------------------------------------ list_dir

    private fun listDir(a: JSONObject): JSONObject {
        val r = resolve(a.optString("path", "."))
        val dir = r.file.toPath()
        if (!Files.isDirectory(dir)) throw ToolError("not a directory")
        val entries = JSONArray()
        var count = 0; var hidden = 0; var truncated = false
        val names = Files.newDirectoryStream(dir).use { ds -> ds.map { it }.sortedWith(compareBy({ !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }, { it.fileName.toString().lowercase() })) }
        for (p in names) {
            val childRel = if (r.rel == ".") p.fileName.toString() else r.rel + "/" + p.fileName
            if (denySensitive() && SensitivePaths.isDenied(childRel)) { hidden++; continue }
            if (count >= LIST_MAX) { truncated = true; break }
            val attrs = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            val type = when {
                attrs == null -> "unknown"
                attrs.isSymbolicLink -> "link"
                attrs.isDirectory -> "dir"
                attrs.isRegularFile -> "file"
                else -> "other"
            }
            entries.put(JSONObject().put("name", p.fileName.toString()).put("type", type)
                .put("size", if (attrs?.isRegularFile == true) attrs.size() else 0)
                .put("modified", attrs?.lastModifiedTime()?.toInstant()?.toString() ?: ""))
            count++
        }
        return ok(JSONObject().put("path", r.rel).put("entries", entries).put("truncated", truncated).put("hidden", hidden))
    }

    // ------------------------------------------------------------------ read_file

    private fun strictUtf8() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

    /** NUL in the first 8 KB, or bytes that are not UTF-8 → binary. */
    internal fun looksBinary(f: File): Boolean {
        val head = f.inputStream().use { it.readNBytes(SNIFF) }
        if (head.any { it == 0.toByte() }) return true
        // Cut back to a character boundary so a multi-byte sequence split at 8 KB is not counted as malformed.
        var end = head.size
        if (end == SNIFF) { var i = end - 1; var back = 0; while (i >= 0 && back < 4 && (head[i].toInt() and 0xC0) == 0x80) { i--; back++ }; if (i >= 0 && (head[i].toInt() and 0x80) != 0) end = i }
        return try { strictUtf8().decode(ByteBuffer.wrap(head, 0, end)); false } catch (_: CharacterCodingException) { true }
    }

    private fun readFile(a: JSONObject): JSONObject {
        val r = resolve(a.optString("path").takeIf { it.isNotBlank() } ?: throw ToolError("path is required"))
        val f = r.file
        if (!Files.isRegularFile(f.toPath())) throw ToolError(if (Files.isDirectory(f.toPath())) "is a directory (use list_dir)" else "no such file")
        if (looksBinary(f)) throw ToolError("binary file: only UTF-8 text can be read")
        val offset = a.optInt("offset", 1).coerceAtLeast(1)
        val limit = a.optInt("limit", READ_DEFAULT_LINES).coerceIn(1, READ_DEFAULT_LINES * 10)
        val sb = StringBuilder()
        var lineNo = 0; var taken = 0; var truncated = false; var bytes = 0
        try {
            InputStreamReader(f.inputStream(), strictUtf8()).buffered().use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    lineNo++
                    if (lineNo < offset) continue
                    if (taken >= limit) { truncated = true; break }
                    val text = if (lineNo == 1) line.removePrefix("\uFEFF") else line
                    val out = "%6d\t%s\n".format(lineNo, text)
                    val b = out.toByteArray(Charsets.UTF_8).size
                    if (bytes + b > READ_MAX_BYTES) { truncated = true; break }
                    sb.append(out); bytes += b; taken++
                }
            }
        } catch (_: CharacterCodingException) {
            throw ToolError("binary file: only UTF-8 text can be read")
        }
        if (lineNo < offset && taken == 0 && offset > 1) throw ToolError("offset is past the end of the file")
        val s = JSONObject().put("path", r.rel).put("offset", offset).put("lines", taken).put("content", sb.toString()).put("truncated", truncated)
        if (truncated) s.put("next_offset", offset + taken)
        val text = sb.toString() + if (truncated) "… truncated; continue with offset=${offset + taken}" else ""
        return ok(s, text.ifEmpty { "(empty)" })
    }

    // ------------------------------------------------------------------ search_files

    private fun searchFiles(a: JSONObject): JSONObject {
        val query = a.optString("query")
        if (query.isEmpty()) throw ToolError("query is required")
        if (query.length > 1000) throw ToolError("query is too long")
        val r = resolve(a.optString("path", "."))
        val start = r.file.toPath()
        if (!Files.isDirectory(start)) throw ToolError("not a directory")
        val caseSensitive = a.optBoolean("case_sensitive", false)
        val max = a.optInt("max_results", SEARCH_DEFAULT).coerceIn(1, SEARCH_MAX)
        val globRaw = a.optString("glob", "").trim()
        if (globRaw.length > 200) throw ToolError("glob is too long")
        val matcher = globRaw.takeIf { it.isNotEmpty() }?.let {
            try { FileSystems.getDefault().getPathMatcher("glob:$it") } catch (_: Exception) { throw ToolError("invalid glob") }
        }
        val root = root()
        val needle = if (caseSensitive) query else query.lowercase()
        val deadline = now() + SEARCH_BUDGET_MS
        val matches = JSONArray()
        var truncated = false; var timedOut = false

        Files.walkFileTree(start, emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (now() > deadline) { timedOut = true; return FileVisitResult.TERMINATE }
                if (dir != start) {
                    val n = dir.fileName?.toString()?.lowercase()
                    if (n in SEARCH_SKIP_DIRS) return FileVisitResult.SKIP_SUBTREE
                    if (attrs.isSymbolicLink || attrs.isOther) return FileVisitResult.SKIP_SUBTREE
                    // A junction looks like a directory: confine by its real path.
                    val real = runCatching { dir.toRealPath() }.getOrNull() ?: return FileVisitResult.SKIP_SUBTREE
                    if (!PathGuard.isUnder(real, root, windows)) return FileVisitResult.SKIP_SUBTREE
                    if (denySensitive() && SensitivePaths.isDenied(rel(root, real))) return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (now() > deadline) { timedOut = true; return FileVisitResult.TERMINATE }
                if (!attrs.isRegularFile || attrs.size() > SEARCH_FILE_MAX) return FileVisitResult.CONTINUE
                val rel = rel(root, file)
                if (denySensitive() && SensitivePaths.isDenied(rel)) return FileVisitResult.CONTINUE
                if (matcher != null && !matcher.matches(file.fileName) && !matcher.matches(Paths.get(rel.replace('/', File.separatorChar))))
                    return FileVisitResult.CONTINUE
                val f = file.toFile()
                if (runCatching { looksBinary(f) }.getOrDefault(true)) return FileVisitResult.CONTINUE
                try {
                    InputStreamReader(f.inputStream(), strictUtf8()).buffered().use { br ->
                        var n = 0
                        while (true) {
                            val line = br.readLine() ?: break
                            n++
                            val hay = if (caseSensitive) line else line.lowercase()
                            if (hay.contains(needle)) {
                                if (matches.length() >= max) { truncated = true; return FileVisitResult.TERMINATE }
                                matches.put(JSONObject().put("path", rel).put("line", n).put("text", line.removePrefix("\uFEFF").trim().take(SEARCH_LINE_MAX)))
                            }
                            if ((n and 1023) == 0 && now() > deadline) { timedOut = true; return FileVisitResult.TERMINATE }
                        }
                    }
                } catch (_: CharacterCodingException) { /* not UTF-8 after the sniffed head: skip */ }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        val s = JSONObject().put("matches", matches).put("truncated", truncated).put("timed_out", timedOut)
        return ok(s)
    }

    // ------------------------------------------------------------------ memory_search

    private fun memorySearch(a: JSONObject): JSONObject {
        val scope = a.optString("scope", "all").takeIf { it == "daily" || it == "all" } ?: "all"
        val keywords = a.optString("keywords", "").trim()
        val limit = a.optInt("limit", 8).coerceIn(1, 30)
        val store = memory()
        val results = JSONArray()
        if (keywords.isNotEmpty()) {
            MemorySearch(store.dir).search(keywords, includeGlobal = scope == "all", limit = limit).forEach { h ->
                results.put(JSONObject().put("file", h.chunk.file).put("line", h.chunk.startLine).put("text", h.chunk.text.take(4000)))
            }
        } else {
            var chars = 0
            for (c in MemorySearch(store.dir).chunks(includeGlobal = scope == "all").sortedByDescending { it.date?.toString() ?: "9999" }) {
                if (results.length() >= limit || chars > MEMORY_MAX_CHARS) break
                results.put(JSONObject().put("file", c.file).put("line", c.startLine).put("text", c.text.take(4000))); chars += c.text.length
            }
        }
        return ok(JSONObject().put("scope", scope).put("results", results))
    }

    // ------------------------------------------------------------------ system_info

    private fun systemInfo(): JSONObject {
        val servers = JSONArray()
        mcpServers().forEach { servers.put(JSONObject().put("name", it.name).put("state", it.state).put("tool_count", it.toolCount)) }
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        return ok(JSONObject().put("app_version", appVersion).put("os", os)
            .put("workspace", workspace().absolutePath).put("shells", JSONArray(shells()))
            .put("mcp_servers", servers).put("time", Instant.ofEpochMilli(now()).toString()))
    }
}
