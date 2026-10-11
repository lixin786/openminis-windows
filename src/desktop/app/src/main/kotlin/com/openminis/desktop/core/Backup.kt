// OpenMinis Desktop — P2-6 backup / restore: one zip file, no cloud. Spec: BACKUP-RESTORE.md (repo root).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Why a backup was refused; [key] is an i18n key (`backup.reject.*`), [arg] its argument. */
class BackupRejected(val key: String, val arg: String = "") : Exception("$key $arg")

/**
 * Pure file logic (no UI). Layout of a backup:
 * `manifest.json` + the app-data entries below, with `/` separators.
 * - settings.json is always written with every key value emptied (`apiKey`, `proxyPassword`); the
 *   `apiKeyStore` / `proxyPasswordStore` markers stay, so after a restore the P3-1 path lists missing keys.
 * - secrets.json / remote-mcp-token.dpapi only when [create]'s `includeSecrets` (DPAPI ciphertext, same
 *   Windows user only). The plain non-Windows token file is never packed.
 */
object Backup {
    const val FORMAT = 1
    const val APP = "OpenMinis"
    const val MANIFEST = "manifest.json"
    val DIRS = listOf("conversations", "attachments", "memory", "skills")
    val FILES = listOf("settings.json", "scheduled_tasks.json", "mcp_servers.json", "subagents.json", "remote-mcp-audit.jsonl")
    val SECRET_FILES = listOf("secrets.json", "remote-mcp-token.dpapi")
    /** Shown in the manifest so a reader knows what is deliberately missing. */
    val EXCLUDED = listOf(
        "logs/ (run logs)",
        "*.tmp, *.bak, *.part (temporary files)",
        "secrets.json, remote-mcp-token.dpapi (unless includesSecrets)",
        "window.json, mcp_trust.json (per machine: restored MCP servers must be trusted again)",
        "backups/ (earlier backups)",
    )
    private val SECRET_KEYS = setOf("apiKey", "proxyPassword")

    data class Manifest(
        val format: Int, val app: String, val version: String, val createdAt: String, val platform: String,
        val includesSecrets: Boolean, val entries: List<String>, val fileCount: Int,
    )
    data class Created(val file: File, val bytes: Long, val fileCount: Int, val manifest: Manifest)
    data class Inspection(val manifest: Manifest, val fileCount: Int, val entries: List<String>)
    data class Restored(val preRestore: File, val stamp: String, val replaced: List<String>, val fileCount: Int)

    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val stampFmtSec = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    fun defaultName(now: LocalDateTime = LocalDateTime.now()) = "openminis-backup-${now.format(stampFmt)}.zip"
    fun stamp(now: LocalDateTime = LocalDateTime.now()): String = now.format(stampFmtSec)
    fun platform(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase().let { if (it == "amd64" || it == "x86_64") "x64" else it }
        return (if (os.contains("win")) "windows" else if (os.contains("mac")) "macos" else "linux") + "-" + arch
    }

    private fun skip(f: File) = f.name.endsWith(".tmp") || f.name.endsWith(".bak") || f.name.endsWith(".part")

    /** settings.json with every secret value emptied, markers kept. Unparseable input is refused, never copied raw. */
    fun stripSecrets(settingsJson: String): String {
        fun walk(v: Any?): Any? = when (v) {
            is JSONObject -> { v.keys().asSequence().toList().forEach { k -> if (k in SECRET_KEYS && v.opt(k) is String) v.put(k, "") else walk(v.opt(k)) }; v }
            is JSONArray -> { for (i in 0 until v.length()) walk(v.opt(i)); v }
            else -> v
        }
        val o = try { JSONObject(settingsJson) } catch (e: Exception) { throw IllegalStateException("settings.json is not valid JSON; not backed up", e) }
        return (walk(o) as JSONObject).toString(2)
    }

    /** Files that go into a backup of [root], as (zip name → source). */
    fun collect(root: File, includeSecrets: Boolean): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        for (d in DIRS) {
            val dir = File(root, d)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && !skip(it) }.sortedBy { it.path }.forEach { f ->
                out += (d + "/" + f.relativeTo(dir).invariantSeparatorsPath) to f
            }
        }
        for (n in FILES + (if (includeSecrets) SECRET_FILES else emptyList())) File(root, n).takeIf { it.isFile }?.let { out += n to it }
        return out
    }

    /**
     * Writes the zip next to [target] under a temporary name, then moves it into place atomically: a failure
     * leaves no half file. [progress] gets (done, total) files.
     */
    fun create(
        root: File, target: File, includeSecrets: Boolean, version: String,
        now: Instant = Instant.now(), progress: (Int, Int) -> Unit = { _, _ -> },
    ): Created {
        val files = collect(root, includeSecrets)
        val entries = files.map { it.first.substringBefore('/') }.distinct()
        val manifest = Manifest(FORMAT, APP, version, now.toString(), platform(), includeSecrets, entries, files.size)
        target.absoluteFile.parentFile?.mkdirs()
        val tmp = File(target.absoluteFile.parentFile, target.name + ".part")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry(MANIFEST)); z.write(manifestJson(manifest).toByteArray(Charsets.UTF_8)); z.closeEntry()
                files.forEachIndexed { i, (name, f) ->
                    z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                    if (name == "settings.json") z.write(stripSecrets(f.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                    else f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    progress(i + 1, files.size)
                }
            }
            try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: Exception) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: Throwable) { tmp.delete(); throw e }
        return Created(target, target.length(), files.size, manifest)
    }

    fun manifestJson(m: Manifest): String = JSONObject()
        .put("format", m.format).put("app", m.app).put("version", m.version).put("createdAt", m.createdAt)
        .put("platform", m.platform).put("includesSecrets", m.includesSecrets)
        .put("entries", JSONArray(m.entries)).put("fileCount", m.fileCount).put("excluded", JSONArray(EXCLUDED))
        .toString(2)

    /** A zip name that would land outside the target folder, or any absolute / drive path. */
    fun unsafeName(n: String): Boolean {
        val s = n.replace('\\', '/')
        return s.startsWith("/") || s.split('/').any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(s) || s.contains('\u0000')
    }

    private fun allowedTop(includesSecrets: Boolean) = DIRS + FILES + (if (includesSecrets) SECRET_FILES else emptyList())

    /** Validates without touching anything. @throws BackupRejected */
    fun inspect(zip: File): Inspection {
        val zf = try { ZipFile(zip) } catch (e: ZipException) { throw BackupRejected("backup.reject.not_zip") }
            catch (e: java.io.IOException) { throw BackupRejected("backup.reject.unreadable", e.message ?: "") }
        zf.use {
            val me = zf.getEntry(MANIFEST) ?: throw BackupRejected("backup.reject.no_manifest")
            val o = try { JSONObject(zf.getInputStream(me).readBytes().toString(Charsets.UTF_8)) } catch (e: Exception) { throw BackupRejected("backup.reject.bad_manifest") }
            if (o.optString("app") != APP) throw BackupRejected("backup.reject.not_ours")
            val format = o.optInt("format", -1)
            if (format > FORMAT) throw BackupRejected("backup.reject.too_new", format.toString())
            if (format < 1) throw BackupRejected("backup.reject.bad_manifest")
            val m = Manifest(format, APP, o.optString("version"), o.optString("createdAt"), o.optString("platform"),
                o.optBoolean("includesSecrets"), o.optJSONArray("entries")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                o.optInt("fileCount"))
            val allowed = allowedTop(m.includesSecrets)
            var count = 0
            val tops = LinkedHashSet<String>()
            for (e in zf.entries()) {
                if (unsafeName(e.name)) throw BackupRejected("backup.reject.unsafe", e.name)
                if (e.isDirectory || e.name == MANIFEST) continue
                val top = e.name.substringBefore('/')
                if (top in allowed) { count++; tops += top }
            }
            return Inspection(m, count, tops.toList())
        }
    }

    /**
     * Destructive. Order (spec §5): validate → pre-restore backup into [backupsDir] (secrets included: it stays
     * on this machine; failure aborts) → extract into a staging folder → for every managed name, rename the
     * current one to `<name>.replaced-<stamp>` and move the staged one in. Any failure after the first rename
     * puts every renamed item back. Secret files are managed only when the backup carries them; otherwise this
     * machine's keys are left as they are.
     * @throws BackupRejected when the zip is refused or the pre-restore backup fails (nothing touched).
     */
    fun restore(
        root: File, zip: File, backupsDir: File, version: String,
        stamp: String = stamp(), beforeSwap: () -> Unit = {},
    ): Restored {
        val insp = inspect(zip)
        val pre = File(backupsDir, "pre-restore-$stamp.zip")
        try { create(root, pre, includeSecrets = true, version = version) }
        catch (e: Exception) { throw BackupRejected("backup.reject.pre_restore_failed", e.message ?: e.javaClass.simpleName) }

        val staging = File(root, ".restore-staging-$stamp")
        staging.deleteRecursively()
        val managed = allowedTop(insp.manifest.includesSecrets)
        try {
            ZipFile(zip).use { zf ->
                for (e in zf.entries()) {
                    if (e.isDirectory || e.name == MANIFEST || unsafeName(e.name)) continue
                    if (e.name.substringBefore('/') !in managed) continue
                    val out = File(staging, e.name)
                    if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw BackupRejected("backup.reject.unsafe", e.name)
                    out.parentFile.mkdirs()
                    zf.getInputStream(e).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                }
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw if (e is BackupRejected) e else BackupRejected("backup.reject.extract_failed", e.message ?: e.javaClass.simpleName)
        }

        beforeSwap()
        val renamed = ArrayList<Pair<File, File>>()   // current → aside
        val placed = ArrayList<File>()
        try {
            for (n in managed) {
                val cur = File(root, n)
                if (cur.exists()) {
                    val aside = File(root, "$n.replaced-$stamp")
                    Files.move(cur.toPath(), aside.toPath())
                    renamed += cur to aside
                }
                val staged = File(staging, n)
                if (staged.exists()) { Files.move(staged.toPath(), cur.toPath()); placed += cur }
            }
        } catch (e: Exception) {
            placed.forEach { it.deleteRecursively() }
            renamed.asReversed().forEach { (cur, aside) -> runCatching { Files.move(aside.toPath(), cur.toPath()) } }
            staging.deleteRecursively()
            throw BackupRejected("backup.reject.swap_failed", e.message ?: e.javaClass.simpleName)
        }
        staging.deleteRecursively()
        return Restored(pre, stamp, renamed.map { it.second.name }, insp.fileCount)
    }

    /** Old data kept by restores and pre-restore zips, for the About page (newest first). */
    fun leftovers(root: File, backupsDir: File): List<File> =
        ((backupsDir.listFiles { f -> f.isFile && f.name.startsWith("pre-restore-") && f.name.endsWith(".zip") } ?: emptyArray()).toList() +
            (root.listFiles { f -> f.name.contains(".replaced-") } ?: emptyArray()).toList())
            .sortedByDescending { it.lastModified() }

    fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    fun localTime(iso: String): String = runCatching {
        LocalDateTime.ofInstant(Instant.parse(iso), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(iso)
}
