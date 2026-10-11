// P2-6 — backup / restore (BACKUP-RESTORE.md) and the update check.
package com.openminis.desktop

import com.openminis.desktop.core.AppRestart
import com.openminis.desktop.core.Backup
import com.openminis.desktop.core.BackupRejected
import com.openminis.desktop.core.UpdateCheck
import com.openminis.desktop.ui.restoreAllowed
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BackupRestoreTest {
    private val apiKey = "sk-ant-PLAINTEXT-KEY-123456"
    private val proxyPw = "proxy-PLAINTEXT-pw-987"
    private val token = "TOKENtokenTOKENtokenTOKENtokenTOKENtoken123"

    private fun seed(root: File) {
        File(root, "conversations").mkdirs(); File(root, "conversations/c1.json").writeText("""{"id":"c1","title":"hello"}""")
        File(root, "attachments/c1").mkdirs(); File(root, "attachments/c1/a.png").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "memory").mkdirs(); File(root, "memory/GLOBAL.md").writeText("# memory")
        File(root, "skills/s1").mkdirs(); File(root, "skills/s1/SKILL.md").writeText("skill")
        File(root, "skills/s1/x.tmp").writeText("temp")
        File(root, "logs").mkdirs(); File(root, "logs/app.log").writeText("log $apiKey")
        File(root, "scheduled_tasks.json").writeText("[]")
        File(root, "mcp_servers.json").writeText("""{"mcpServers":{}}""")
        File(root, "mcp_trust.json").writeText("{}")
        File(root, "subagents.json").writeText("[]")
        File(root, "window.json").writeText("{}")
        File(root, "remote-mcp-audit.jsonl").writeText("{}\n")
        File(root, "settings.json").writeText(JSONObject()
            .put("activeProvider", "ANTHROPIC")
            .put("providers", JSONObject().put("ANTHROPIC", JSONObject().put("apiKey", apiKey).put("apiKeyStore", "").put("model", "m"))
                .put("OPENAI", JSONObject().put("apiKey", "").put("apiKeyStore", "dpapi")))
            .put("network", JSONObject().put("proxyPassword", proxyPw).put("proxyPasswordStore", "dpapi").put("proxyUser", "u"))
            .toString())
        File(root, "secrets.json").writeText("""{"provider.OPENAI.apiKey":{"scheme":"dpapi","blob":"AAEC"}}""")
        File(root, "remote-mcp-token.dpapi").writeText("""{"version":1,"scheme":"dpapi","blob":"AQID"}""")
        File(root, "remote-mcp-token").writeText(token)   // plain (non-Windows) token: never packed
    }

    private fun tmp() = kotlin.io.path.createTempDirectory("bk").toFile()
    private fun names(zip: File) = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
    private fun allText(zip: File) = ZipFile(zip).use { z -> z.entries().toList().joinToString("\n") { z.getInputStream(it).readBytes().toString(Charsets.ISO_8859_1) } }

    @Test fun defaultBackupHasTheSpecEntriesAndNoPlaintextSecret() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            val out = File(d, "out/" + Backup.defaultName())
            val r = Backup.create(root, out, includeSecrets = false, version = "1.14-desktop")
            assertTrue(out.isFile); assertEquals(out.length(), r.bytes)
            assertTrue(Regex("openminis-backup-\\d{8}-\\d{4}\\.zip").matches(out.name))
            val n = names(out)
            assertEquals("manifest.json", n.first())
            listOf("conversations/c1.json", "attachments/c1/a.png", "memory/GLOBAL.md", "skills/s1/SKILL.md", "settings.json",
                "scheduled_tasks.json", "mcp_servers.json", "subagents.json", "remote-mcp-audit.jsonl").forEach { assertTrue(it in n, it) }
            listOf("secrets.json", "remote-mcp-token.dpapi", "remote-mcp-token", "logs/app.log", "skills/s1/x.tmp", "window.json", "mcp_trust.json")
                .forEach { assertFalse(it in n, it) }
            val text = allText(out)
            listOf(apiKey, proxyPw, token).forEach { assertFalse(text.contains(it), "plaintext secret in backup") }
            ZipFile(out).use { z ->
                val m = JSONObject(z.getInputStream(z.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8))
                assertEquals(1, m.getInt("format")); assertEquals("OpenMinis", m.getString("app")); assertEquals("1.14-desktop", m.getString("version"))
                assertFalse(m.getBoolean("includesSecrets")); assertTrue(m.getString("createdAt").endsWith("Z"))
                assertTrue(m.getJSONArray("excluded").length() > 0)
                val s = JSONObject(z.getInputStream(z.getEntry("settings.json")).readBytes().toString(Charsets.UTF_8))
                assertEquals("", s.getJSONObject("providers").getJSONObject("ANTHROPIC").getString("apiKey"))
                assertEquals("dpapi", s.getJSONObject("providers").getJSONObject("OPENAI").getString("apiKeyStore"), "markers kept")
                assertEquals("", s.getJSONObject("network").getString("proxyPassword"))
                assertEquals("u", s.getJSONObject("network").getString("proxyUser"))
            }
        } finally { d.deleteRecursively() }
    }

    @Test fun includeSecretsAddsOnlyTheEncryptedFiles() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            val out = File(d, "b.zip")
            Backup.create(root, out, includeSecrets = true, version = "v")
            val n = names(out)
            assertTrue("secrets.json" in n && "remote-mcp-token.dpapi" in n)
            assertFalse("remote-mcp-token" in n)
            val text = allText(out)
            listOf(apiKey, proxyPw, token).forEach { assertFalse(text.contains(it)) }
            assertTrue(Backup.inspect(out).manifest.includesSecrets)
        } finally { d.deleteRecursively() }
    }

    @Test fun failedBackupLeavesNoHalfFile() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            File(root, "settings.json").writeText("{ not json")
            val out = File(d, "b.zip")
            assertFails { Backup.create(root, out, false, "v") }
            assertFalse(out.exists()); assertFalse(File(d, "b.zip.part").exists())
        } finally { d.deleteRecursively() }
    }

    @Test fun restoreRoundTripWithSafetyBackupAndAsideFolders() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            val zip = File(d, "b.zip"); Backup.create(root, zip, false, "v")
            // change things after the backup
            File(root, "conversations/c1.json").writeText("""{"id":"c1","title":"CHANGED"}""")
            File(root, "conversations/c2.json").writeText("{}")
            File(root, "memory/GLOBAL.md").writeText("changed")
            val secretsBefore = File(root, "secrets.json").readText()
            var froze = false
            val r = Backup.restore(root, zip, File(root, "backups"), "v", stamp = "20261011-200000", beforeSwap = { froze = true })
            assertTrue(froze)
            assertEquals("""{"id":"c1","title":"hello"}""", File(root, "conversations/c1.json").readText())
            assertFalse(File(root, "conversations/c2.json").exists())
            assertEquals("# memory", File(root, "memory/GLOBAL.md").readText())
            assertEquals("skill", File(root, "skills/s1/SKILL.md").readText())
            assertEquals(secretsBefore, File(root, "secrets.json").readText(), "a backup without keys leaves this machine's keys")
            assertTrue(File(root, "remote-mcp-token.dpapi").exists())
            assertEquals("dpapi", JSONObject(File(root, "settings.json").readText()).getJSONObject("providers").getJSONObject("OPENAI").getString("apiKeyStore"))
            assertEquals("", JSONObject(File(root, "settings.json").readText()).getJSONObject("providers").getJSONObject("ANTHROPIC").getString("apiKey"))
            // safety backup holds the changed state, with the keys
            assertTrue(r.preRestore.isFile); assertEquals("pre-restore-20261011-200000.zip", r.preRestore.name)
            assertTrue(allText(r.preRestore).contains("CHANGED"))
            assertTrue("secrets.json" in names(r.preRestore))
            // old folders renamed, not deleted; no staging left
            assertTrue(File(root, "conversations.replaced-20261011-200000/c2.json").exists())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".restore-staging") })
            assertTrue(Backup.leftovers(root, File(root, "backups")).any { it.name.startsWith("pre-restore-") })
            assertTrue(Backup.leftovers(root, File(root, "backups")).any { it.name == "conversations.replaced-20261011-200000" })
            assertTrue(File(root, "logs/app.log").exists(), "logs untouched")
        } finally { d.deleteRecursively() }
    }

    @Test fun restoreWithSecretsReplacesThem() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            val zip = File(d, "b.zip"); Backup.create(root, zip, true, "v")
            File(root, "secrets.json").writeText("{}")
            Backup.restore(root, zip, File(root, "backups"), "v", stamp = "s1")
            assertTrue(File(root, "secrets.json").readText().contains("provider.OPENAI.apiKey"))
        } finally { d.deleteRecursively() }
    }

    private fun zipOf(f: File, vararg entries: Pair<String, String>) =
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }

    private fun assertRejected(key: String, block: () -> Unit) {
        val e = assertFailsWith<BackupRejected> { block() }; assertEquals(key, e.key)
    }

    @Test fun badZipsAreRefusedAndNothingIsTouched() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            val snapshot = root.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
            val backups = File(root, "backups")
            val junk = File(d, "junk.zip").apply { writeBytes(ByteArray(200) { it.toByte() }) }
            assertRejected("backup.reject.not_zip") { Backup.restore(root, junk, backups, "v") }
            val noManifest = File(d, "nm.zip").also { zipOf(it, "conversations/x.json" to "{}") }
            assertRejected("backup.reject.no_manifest") { Backup.restore(root, noManifest, backups, "v") }
            val other = File(d, "o.zip").also { zipOf(it, "manifest.json" to """{"format":1,"app":"Other"}""") }
            assertRejected("backup.reject.not_ours") { Backup.restore(root, other, backups, "v") }
            val newer = File(d, "n.zip").also { zipOf(it, "manifest.json" to """{"format":2,"app":"OpenMinis"}""") }
            val e = assertFailsWith<BackupRejected> { Backup.restore(root, newer, backups, "v") }
            assertEquals("backup.reject.too_new", e.key); assertEquals("2", e.arg)
            val broken = File(d, "b.zip").also { zipOf(it, "manifest.json" to "{{{") }
            assertRejected("backup.reject.bad_manifest") { Backup.restore(root, broken, backups, "v") }
            val slip = File(d, "s.zip").also { zipOf(it, "manifest.json" to """{"format":1,"app":"OpenMinis"}""", "../evil.txt" to "x") }
            assertRejected("backup.reject.unsafe") { Backup.restore(root, slip, backups, "v") }
            assertFalse(File(d, "evil.txt").exists())
            assertFalse(backups.exists(), "refused before the safety backup")
            assertEquals(snapshot, root.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
        } finally { d.deleteRecursively() }
    }

    @Test fun safetyBackupFailureStopsTheRestore() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            val zip = File(d, "b.zip"); Backup.create(root, zip, false, "v")
            File(root, "memory/GLOBAL.md").writeText("current")
            val backupsIsAFile = File(d, "backups").apply { writeText("not a folder") }
            assertRejected("backup.reject.pre_restore_failed") { Backup.restore(root, zip, backupsIsAFile, "v") }
            assertEquals("current", File(root, "memory/GLOBAL.md").readText())
            assertFalse(root.listFiles()!!.any { it.name.contains(".replaced-") || it.name.startsWith(".restore-staging") })
        } finally { d.deleteRecursively() }
    }

    @Test fun swapFailureRollsEverythingBack() {
        val d = tmp()
        try {
            val root = File(d, "data").apply { mkdirs() }; seed(root)
            val zip = File(d, "b.zip"); Backup.create(root, zip, false, "v")
            File(root, "conversations/c1.json").writeText("current")
            // an aside name for settings.json is already taken → the move fails after the folders were swapped
            File(root, "settings.json.replaced-S").mkdirs(); File(root, "settings.json.replaced-S/x").writeText("x")
            val settingsNow = File(root, "settings.json").readText()
            assertRejected("backup.reject.swap_failed") { Backup.restore(root, zip, File(root, "backups"), "v", stamp = "S") }
            assertEquals("current", File(root, "conversations/c1.json").readText())
            assertEquals(settingsNow, File(root, "settings.json").readText())
            assertFalse(File(root, "conversations.replaced-S").exists())
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".restore-staging") })
        } finally { d.deleteRecursively() }
    }

    @Test fun typedConfirmation() {
        assertTrue(restoreAllowed("restore")); assertTrue(restoreAllowed(" restore "))
        assertFalse(restoreAllowed("")); assertFalse(restoreAllowed("Restore")); assertFalse(restoreAllowed("恢复"))
    }

    @Test fun versionCompareAndParse() {
        assertTrue(UpdateCheck.compare("v1.15.0", "1.14-desktop") > 0)
        assertEquals(0, UpdateCheck.compare("v1.14", "1.14-desktop"))
        assertEquals(0, UpdateCheck.compare("1.14.0", "1.14-desktop"))
        assertTrue(UpdateCheck.compare("v1.9", "1.14") < 0)
        assertTrue(UpdateCheck.compare("desktop-v2.0", "1.14-desktop") > 0)
        val newer = UpdateCheck.parse("""{"tag_name":"v1.15.0","html_url":"https://github.com/lixin786/openminis-windows/releases/tag/v1.15.0"}""", "1.14-desktop")
        assertEquals(UpdateCheck.Outcome.Newer("v1.15.0", "https://github.com/lixin786/openminis-windows/releases/tag/v1.15.0"), newer)
        assertEquals(UpdateCheck.Outcome.UpToDate("v1.14.0"), UpdateCheck.parse("""{"tag_name":"v1.14.0"}""", "1.14-desktop"))
        val odd = UpdateCheck.parse("""{"tag_name":"v9","html_url":"javascript:alert(1)"}""", "1.14")
        assertEquals(UpdateCheck.RELEASES, (odd as UpdateCheck.Outcome.Newer).url, "only github.com links are opened")
        assertTrue(UpdateCheck.parse("<html>", "1.14") is UpdateCheck.Outcome.Failed)
        assertEquals("OpenMinis-desktop/1.14-desktop", UpdateCheck.userAgent("1.14-desktop"))
    }

    @Test fun checkOverHttpSendsTheUserAgentAndHandlesErrors() {
        val seenUa = java.util.concurrent.atomic.AtomicReference<String>()
        val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/ok") { ex -> seenUa.set(ex.requestHeaders.getFirst("User-Agent"))
            val b = """{"tag_name":"v1.20.0","html_url":"https://github.com/lixin786/openminis-windows/releases/tag/v1.20.0"}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        srv.createContext("/none") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        srv.createContext("/err") { ex -> ex.sendResponseHeaders(503, -1); ex.close() }
        srv.start()
        try {
            val c = okhttp3.OkHttpClient()
            val base = "http://127.0.0.1:${srv.address.port}"
            assertTrue(UpdateCheck.check("1.14-desktop", c, "$base/ok") is UpdateCheck.Outcome.Newer)
            assertEquals("OpenMinis-desktop/1.14-desktop", seenUa.get())
            assertEquals(UpdateCheck.Outcome.NoRelease, UpdateCheck.check("1.14", c, "$base/none"))
            assertEquals(UpdateCheck.Outcome.Failed("HTTP 503"), UpdateCheck.check("1.14", c, "$base/err"))
        } finally { srv.stop(0) }
        val dead = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(UpdateCheck.check("1.14", okhttp3.OkHttpClient(), "http://127.0.0.1:$dead/x") is UpdateCheck.Outcome.Failed)
    }

    @Test fun restartCommand() {
        assertEquals(listOf("C:\\Program Files\\OpenMinis\\OpenMinis.exe"), AppRestart.command("C:\\Program Files\\OpenMinis\\OpenMinis.exe", emptyList()))
        assertNull(AppRestart.command("C:\\jdk\\bin\\java.exe", null), "bare java cannot be re-run")
        assertEquals(listOf("/usr/bin/java", "-jar", "a.jar"), AppRestart.command("/usr/bin/java", listOf("-jar", "a.jar")))
        assertNull(AppRestart.command(null, null))
    }

    @Test fun wiringAndI18n() {
        val src = listOf(File("src/main/kotlin"), File("app/src/main/kotlin"), File("src/desktop/app/src/main/kotlin")).first { it.isDirectory }.resolve("com/openminis/desktop")
        val dialog = File(src, "ui/SettingsDialog.kt").readText()
        assertTrue("BACKUP(SettingsGroup.SYSTEM" in dialog && "BackupSection(state" in dialog && "UpdateCheckSection(state)" in dialog && "OldBackupsSection(state)" in dialog)
        // no automatic network: the only caller of UpdateCheck.check is the button
        val callers = src.walkTopDown().filter { it.isFile && it.extension == "kt" && "UpdateCheck.check(" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("BackupSettings.kt"), callers)
        assertTrue("var includeSecrets by remember { mutableStateOf(false) }" in File(src, "ui/BackupSettings.kt").readText(), "keys off by default")
        val keys = File(src, "ui/BackupSettings.kt").readText().let { t -> Regex("tr\\(\"([a-z_.]+)\"").findAll(t).map { it.groupValues[1] }.toSet() } +
            listOf("not_zip", "unreadable", "no_manifest", "bad_manifest", "not_ours", "too_new", "unsafe", "pre_restore_failed", "extract_failed", "swap_failed").map { "backup.reject.$it" } +
            "settings.page.backup"
        for (l in listOf("en", "zh-CN", "zh-TW")) keys.forEach { assertTrue(com.openminis.desktop.i18n.I18n.table(l)[it].orEmpty().isNotBlank(), "$l:$it") }
    }
}
