// OpenMinis Desktop — skill import (P1-3). Port of upstream SkillRepository's import paths:
// importFromContent (paste / single SKILL.md, replace-by-slug), importFromArchive (.zip / .skill with
// SKILL.md at the root or one directory deep, siblings extracted, zip-slip guarded), importFromGitHub
// (raw SKILL.md + sibling files through the contents API, depth ≤ 5). Desktop addition: import a local
// folder. Storage is a plain folder per skill (<skillsDir>/<slug>/SKILL.md) instead of SQLite.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.skills

import com.openminis.app.logging.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/** Result of one import: the installed skill, plus a note when sibling files were incomplete. */
data class SkillImportResult(val skill: Skill, val warning: String? = null)

class SkillImportException(message: String) : Exception(message)

class SkillImporter(
    /** Where imported skills go (%APPDATA%\OpenMinis\skills). */
    private val skillsDir: () -> File,
    private val http: () -> OkHttpClient,
) {
    companion object {
        private const val TAG = "SkillImport"
        private const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024
        private const val MAX_GITHUB_FILES = 200

        /** Upstream SkillRepository.slugify. */
        fun slugify(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        /** Folder id for a parsed skill; a name with no ASCII letters/digits (e.g. all Chinese) still gets a stable id. */
        fun folderId(name: String): String = slugify(name).ifEmpty {
            "skill-" + Integer.toHexString(name.trim().hashCode()).padStart(8, '0')
        }

        /** A relative path inside a skill folder is safe to write (no traversal, no absolute / drive paths, no dot-files). */
        fun safeRelative(rel: String): String? {
            val r = rel.replace('\\', '/').trimStart('/')
            if (r.isEmpty() || r.startsWith(".")) return null
            val parts = r.split('/')
            if (parts.any { it == ".." || it.isEmpty() && parts.size > 1 && it != parts.last() }) return null
            if (parts.first().contains(':')) return null
            return r
        }

        data class GitHubInfo(val user: String, val repo: String, val branch: String, val dirPath: String)

        private fun normalizeURL(u: String): String = u.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }

        private fun segments(u: String): Pair<String, List<String>>? = runCatching {
            val uri = URI(normalizeURL(u))
            (uri.host ?: return null) to uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        }.getOrNull()

        /** Upstream parseGitHubURL. */
        fun parseGitHubURL(url: String): GitHubInfo? {
            val (host, parts) = segments(url) ?: return null
            fun dir(rest: List<String>) = rest.toMutableList().also { if (it.lastOrNull()?.equals("SKILL.md", true) == true) it.removeAt(it.lastIndex) }.joinToString("/")
            return when (host) {
                "raw.githubusercontent.com" -> if (parts.size < 3) null else GitHubInfo(parts[0], parts[1], parts[2], dir(parts.drop(3)))
                "github.com" -> if (parts.size < 4) null else GitHubInfo(parts[0], parts[1], parts[3], dir(parts.drop(4)))
                else -> null
            }
        }

        /** Upstream githubToRawURL; any other https URL is taken as a direct link to a SKILL.md. */
        fun rawSkillUrl(url: String): String? {
            val normalized = normalizeURL(url)
            val (host, parts) = segments(url) ?: return null
            return when (host) {
                "raw.githubusercontent.com" -> if (parts.lastOrNull()?.equals("SKILL.md", true) == true) normalized else normalized.trimEnd('/') + "/SKILL.md"
                "github.com" -> {
                    if (parts.size < 4) return null
                    var path = parts.drop(4).joinToString("/")
                    if (!path.endsWith("SKILL.md", true)) path = if (path.isEmpty()) "SKILL.md" else "$path/SKILL.md"
                    "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${parts[3]}/$path"
                }
                else -> normalized
            }
        }
    }

    private fun loadInstalled(id: String): Skill {
        val md = File(File(skillsDir(), id), "SKILL.md")
        val p = SkillMd.parseSkillMd(md.readText(Charsets.UTF_8)) ?: throw SkillImportException("SKILL.md could not be read back")
        return Skill(p.name, p.description, p.version, md, p.body)
    }

    /** Paste / single file: write <slug>/SKILL.md (replacing a skill of the same name, like upstream). */
    fun importFromContent(content: String): SkillImportResult {
        val parsed = SkillMd.parseSkillMd(content)
            ?: throw SkillImportException("Not a skill: SKILL.md needs a front matter block (--- name: … description: … ---).")
        val id = folderId(parsed.name)
        val dir = File(skillsDir(), id).also { it.mkdirs() }
        File(dir, "SKILL.md").writeText(content.removePrefix("\uFEFF"), Charsets.UTF_8)
        AppLogger.info(TAG, "installed '${parsed.name}' → ${dir.absolutePath}")
        return SkillImportResult(loadInstalled(id))
    }

    /** A .zip / .skill archive with SKILL.md at the root or one directory deep (upstream importFromArchive). */
    fun importFromArchive(input: InputStream): SkillImportResult {
        val entries = readZip(input)
        val md = entries.firstOrNull { (n, _) ->
            val name = n.replace('\\', '/')
            name.equals("SKILL.md", true) || (name.endsWith("/SKILL.md", true) && name.count { it == '/' } == 1)
        } ?: throw SkillImportException("No SKILL.md at the top of the archive (or one folder deep).")
        val content = String(md.second, Charsets.UTF_8)
        val installed = importFromContent(content)
        val prefix = md.first.replace('\\', '/').let { if (it.equals("SKILL.md", true)) "" else it.dropLast("SKILL.md".length) }
        var skipped = 0
        for ((name, data) in entries) {
            var rel = name.replace('\\', '/')
            if (prefix.isNotEmpty()) { if (!rel.startsWith(prefix)) continue; rel = rel.drop(prefix.length) }
            if (rel.equals("SKILL.md", true)) continue
            val safe = safeRelative(rel)
            if (safe == null) {
                // Dot-files are dropped quietly (upstream); traversal / absolute paths are reported.
                if (rel.split('/').any { it == ".." } || rel.contains(':') || rel.startsWith("/")) skipped++
                continue
            }
            val dest = File(installed.skill.dir, safe)
            if (!dest.canonicalPath.startsWith(installed.skill.dir.canonicalPath + File.separator)) { skipped++; continue }
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
        }
        return installed.copy(warning = if (skipped > 0) "$skipped unsafe path(s) in the archive were skipped." else null)
    }

    fun importFromArchive(file: File): SkillImportResult = file.inputStream().use { importFromArchive(it) }

    private fun readZip(input: InputStream): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var total = 0L
        try {
            ZipInputStream(input, Charsets.UTF_8).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory) {
                        val buf = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                        while (true) {
                            val n = z.read(chunk); if (n <= 0) break
                            total += n
                            if (total > MAX_ARCHIVE_BYTES) throw SkillImportException("Archive is larger than ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB uncompressed.")
                            buf.write(chunk, 0, n)
                        }
                        out += e.name to buf.toByteArray()
                    }
                    z.closeEntry()
                }
            }
        } catch (e: SkillImportException) { throw e } catch (e: Exception) {
            throw SkillImportException("Not a readable zip archive: ${e.message}")
        }
        if (out.isEmpty()) throw SkillImportException("The archive is empty.")
        return out
    }

    /** Desktop: copy a local folder that contains SKILL.md (scripts/, references/, … come along). */
    fun importFromFolder(src: File): SkillImportResult {
        val md = File(src, "SKILL.md").takeIf { it.isFile } ?: File(src, "skill.md").takeIf { it.isFile }
            ?: throw SkillImportException("${src.absolutePath} has no SKILL.md.")
        val installed = importFromContent(md.readText(Charsets.UTF_8))
        val dest = installed.skill.dir
        if (src.canonicalFile == dest.canonicalFile) return installed
        src.walkTopDown().onEnter { it == src || !it.name.startsWith(".") }.filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).invariantSeparatorsPath
            if (rel.equals("SKILL.md", true) || rel.equals("skill.md", true)) return@forEach
            val target = File(dest, rel); target.parentFile?.mkdirs(); f.copyTo(target, overwrite = true)
        }
        return installed
    }

    /** GitHub (or any direct SKILL.md URL): SKILL.md first, then sibling files through the contents API. */
    fun importFromUrl(url: String): SkillImportResult {
        val raw = rawSkillUrl(url) ?: throw SkillImportException("Not a URL: $url")
        val content = get(raw)?.toString(Charsets.UTF_8) ?: throw SkillImportException("Could not download $raw")
        val installed = importFromContent(content)
        val gh = parseGitHubURL(url) ?: return installed
        val failures = mutableListOf<String>()
        val written = intArrayOf(0)
        downloadDir(gh, gh.dirPath, installed.skill.dir, "", 0, failures, written)
        val warning = if (failures.isEmpty()) null else "Skill installed, but ${failures.size} sibling file(s) could not be fetched: " + failures.take(3).joinToString("; ")
        return installed.copy(warning = warning)
    }

    private fun downloadDir(gh: GitHubInfo, remote: String, local: File, rel: String, depth: Int, failures: MutableList<String>, written: IntArray) {
        if (depth > 5) { failures += "max depth at $remote"; return }
        if (written[0] >= MAX_GITHUB_FILES) { failures += "more than $MAX_GITHUB_FILES files — stopped"; return }
        val enc = URLEncoder.encode(remote, "UTF-8").replace("+", "%20").replace("%2F", "/")
        val api = "https://api.github.com/repos/${gh.user}/${gh.repo}/contents/$enc?ref=${gh.branch}"
        val body = get(api, accept = "application/vnd.github+json")?.toString(Charsets.UTF_8) ?: run { failures += "listing $remote failed (rate limit?)"; return }
        val items = runCatching { JSONArray(body) }.getOrElse { failures += "listing $remote: ${body.take(120)}"; return }
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val name = it.optString("name"); if (name.isEmpty() || name.startsWith(".")) continue
            val relPath = if (rel.isEmpty()) name else "$rel/$name"
            when (it.optString("type")) {
                "dir" -> downloadDir(gh, if (remote.isEmpty()) name else "$remote/$name", local, relPath, depth + 1, failures, written)
                "file" -> {
                    if (rel.isEmpty() && name.equals("SKILL.md", true)) continue
                    val safe = safeRelative(relPath) ?: continue
                    val bytes = it.optString("download_url").takeIf { u -> u.isNotEmpty() }?.let { u -> get(u) }
                    if (bytes == null) { failures += relPath; continue }
                    File(local, safe).also { f -> f.parentFile?.mkdirs() }.writeBytes(bytes)
                    written[0]++
                }
            }
        }
    }

    private fun get(url: String, accept: String? = null): ByteArray? {
        repeat(2) { attempt ->
            try {
                val rb = Request.Builder().url(url).header("User-Agent", "OpenMinis-Desktop")
                accept?.let { rb.header("Accept", it) }
                http().newCall(rb.build()).execute().use { r ->
                    if (r.isSuccessful) return r.body?.bytes()
                    if (r.code !in setOf(403, 429) && r.code < 500) return null
                }
            } catch (e: Exception) {
                AppLogger.warning(TAG, "GET $url failed: ${e.message}")
            }
            if (attempt == 0) Thread.sleep(1500)
        }
        return null
    }

    /** Remove an installed skill folder (only folders directly under a known skills root). */
    fun delete(skill: Skill, roots: List<File>) {
        val dir = skill.dir.canonicalFile
        require(roots.any { dir.parentFile == it.canonicalFile }) { "Refusing to delete ${dir.absolutePath}: not in a skills folder" }
        dir.deleteRecursively()
    }
}
