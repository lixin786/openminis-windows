// P1-3 skill import: paste / file, archive (root + one folder deep, siblings, zip-slip), folder, URL
// (direct SKILL.md over HTTP), GitHub URL parsing, delete guard, disabled skills left out of the prompt.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.desktop.agent.SystemPrompt
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.skills.SkillImportException
import com.openminis.desktop.skills.SkillImporter
import com.openminis.desktop.skills.SkillLoader
import com.openminis.desktop.tools.MemoryStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class SkillImportTest {
    private val root = TestSupport.tmp("om-skills")
    private val importer = SkillImporter({ root }, { OkHttpClient() })
    private val loader = SkillLoader { listOf(root) }

    private fun md(name: String, desc: String = "does things", body: String = "Step 1.") = "---\nname: $name\ndescription: $desc\nversion: 2.0\n---\n$body\n"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, Charsets.UTF_8).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test fun pasteInstallsAndReplacesBySlug() {
        val r = importer.importFromContent("\uFEFF" + md("PDF Tools", "处理 PDF 📄"))
        assertEquals("pdf-tools", r.skill.id)
        assertEquals("处理 PDF 📄", loader.load().single().description)
        importer.importFromContent(md("pdf tools", "v2"))
        assertEquals(listOf("v2"), loader.load().map { it.description }, "same slug replaces, no duplicate")
        val zh = importer.importFromContent(md("周报助手"))
        assertTrue(zh.skill.id.startsWith("skill-"), "non-ASCII names still get a folder: ${zh.skill.id}")
        assertFailsWith<SkillImportException> { importer.importFromContent("just text, no front matter") }
    }

    @Test fun archiveOneFolderDeepWithSiblingsAndZipSlipGuard() {
        val data = zip(
            "my-skill/SKILL.md" to md("Zip Skill"),
            "my-skill/scripts/run.py" to "print('你好')",
            "my-skill/../../evil.txt" to "x",
            "my-skill/.hidden" to "x",
            "other/ignored.txt" to "x",
        )
        val r = importer.importFromArchive(data.inputStream())
        val dir = File(root, "zip-skill")
        assertEquals(dir.canonicalFile, r.skill.dir.canonicalFile)
        assertEquals("print('你好')", File(dir, "scripts/run.py").readText(Charsets.UTF_8))
        assertFalse(File(root.parentFile, "evil.txt").exists() || File(root, "evil.txt").exists())
        assertFalse(File(dir, ".hidden").exists())
        assertFalse(File(dir, "ignored.txt").exists())
        assertNotNull(r.warning, "skipped unsafe path is reported")
        val rootLevel = importer.importFromArchive(zip("SKILL.md" to md("Root Skill"), "ref/a.md" to "A").inputStream())
        assertTrue(File(rootLevel.skill.dir, "ref/a.md").isFile)
        assertFailsWith<SkillImportException> { importer.importFromArchive(zip("a/b/SKILL.md" to md("Too Deep")).inputStream()) }
        assertFailsWith<SkillImportException> { importer.importFromArchive("not a zip".byteInputStream()) }
    }

    @Test fun folderImportCopiesTree() {
        val src = TestSupport.tmp("om-src")
        File(src, "SKILL.md").writeText(md("Folder Skill"), Charsets.UTF_8)
        File(src, "references").mkdirs(); File(src, "references/指南.md").writeText("内容", Charsets.UTF_8)
        File(src, ".git").mkdirs(); File(src, ".git/config").writeText("x")
        val r = importer.importFromFolder(src)
        assertEquals("内容", File(r.skill.dir, "references/指南.md").readText(Charsets.UTF_8))
        assertFalse(File(r.skill.dir, ".git").exists())
        assertFailsWith<SkillImportException> { importer.importFromFolder(TestSupport.tmp("om-empty")) }
    }

    @Test fun directUrlImport() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse().setBody(md("Web Skill", "从网上导入")).setHeader("Content-Type", "text/markdown; charset=utf-8"))
        val r = importer.importFromUrl(server.url("/x/SKILL.md").toString())
        assertEquals("从网上导入", r.skill.description)
        assertNull(r.warning)
        server.enqueue(MockResponse().setResponseCode(404))
        assertFailsWith<SkillImportException> { importer.importFromUrl(server.url("/missing/SKILL.md").toString()) }
        server.shutdown()
    }

    @Test fun githubUrlParsing() {
        val tree = "https://github.com/anthropics/skills/tree/main/document-skills/pdf"
        assertEquals("https://raw.githubusercontent.com/anthropics/skills/main/document-skills/pdf/SKILL.md", SkillImporter.rawSkillUrl(tree))
        assertEquals(SkillImporter.Companion.GitHubInfo("anthropics", "skills", "main", "document-skills/pdf"), SkillImporter.parseGitHubURL(tree))
        val blob = "github.com/u/r/blob/dev/a/SKILL.md"
        assertEquals("https://raw.githubusercontent.com/u/r/dev/a/SKILL.md", SkillImporter.rawSkillUrl(blob))
        assertEquals("a", SkillImporter.parseGitHubURL(blob)!!.dirPath)
        assertEquals("https://raw.githubusercontent.com/u/r/main/SKILL.md", SkillImporter.rawSkillUrl("https://raw.githubusercontent.com/u/r/main"))
        assertNull(SkillImporter.parseGitHubURL("https://example.com/SKILL.md"))
        assertEquals(null, SkillImporter.safeRelative("../x")); assertEquals(null, SkillImporter.safeRelative("C:/x")); assertEquals("a/b.txt", SkillImporter.safeRelative("a\\b.txt"))
    }

    @Test fun deleteOnlyInsideSkillRoots() {
        val r = importer.importFromContent(md("Doomed"))
        importer.delete(r.skill, listOf(root))
        assertFalse(r.skill.dir.exists())
        val outside = TestSupport.tmp("om-out"); File(outside, "x").mkdirs(); File(outside, "x/SKILL.md").writeText(md("X"))
        val foreign = SkillLoader { listOf(outside) }.load().single()
        assertFailsWith<IllegalArgumentException> { importer.delete(foreign, listOf(root)) }
        assertTrue(foreign.dir.exists())
    }

    @Test fun disabledSkillsAreLeftOutOfThePrompt() {
        importer.importFromContent(md("Alpha", "alpha desc")); importer.importFromContent(md("Beta", "beta desc"))
        val mem = MemoryStore(TestSupport.tmp("om-mem"))
        val on = SystemPrompt.build(AppSettings(), mem, loader)
        assertTrue(on.contains("alpha desc") && on.contains("beta desc"))
        val off = SystemPrompt.build(AppSettings(disabledSkills = listOf("beta")), mem, loader)
        assertTrue(off.contains("alpha desc") && !off.contains("beta desc"), off)
    }
}
