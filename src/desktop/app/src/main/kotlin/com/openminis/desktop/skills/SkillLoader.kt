// OpenMinis Desktop — SKILL.md discovery. The frontmatter parser below is copied verbatim from
// the Android SkillRepository.parseSkillMd (OpenMinis); storage is a plain folder instead of SQLite.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.skills

import java.io.File

data class ParsedSkill(val name: String, val description: String, val version: String = "1.0.0", val body: String)

data class Skill(val name: String, val description: String, val version: String, val file: File, val body: String)

object SkillMd {
    fun parseSkillMd(content: String): ParsedSkill? {
        // CRLF / CR endings are already handled by `lines()` and `trim()`,
        // but a leading UTF-8 BOM (Windows Notepad) is not whitespace to
        // `trimStart()` and hid the opening fence, so the file was rejected.
        val trimmed = content.removePrefix("﻿").trimStart()
        if (!trimmed.startsWith("---")) return null

        // Find the closing `---` on a line by itself (matches iOS — and avoids
        // false matches against horizontal rules later in the document).
        val lines = trimmed.lines()
        var frontmatterEndLine = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                frontmatterEndLine = i
                break
            }
        }
        if (frontmatterEndLine < 0) return null

        var name = ""
        var description = ""
        var version = "1.0.0"

        var i = 1
        while (i < frontmatterEndLine) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) { i++; continue }
            val key = line.substring(0, colonIdx).trim().lowercase()
            val rawValue = line.substring(colonIdx + 1).trim()

            // YAML block scalar: `|` keeps newlines, `>` folds them into spaces.
            // Body lines continue while they are indented (or empty); a
            // dedented line ends the block.
            //
            // T151: also accept the YAML chomping indicators `>-`, `>+`, `|-`,
            // `|+` (and any explicit indentation digit suffix). Real-world
            // skill frontmatter — qbt-hub, for one — opens its description
            // with `description: >-` so trailing newlines are stripped, but
            // the previous strict `==` match treated that as a plain string
            // literal, leaving the renderer to display the raw `>-` marker.
            val isBlockScalar = rawValue.startsWith("|") || rawValue.startsWith(">")
            val resolved: String
            if (isBlockScalar && i + 1 < frontmatterEndLine) {
                val fold = rawValue.startsWith(">")
                val blockLines = mutableListOf<String>()
                var j = i + 1
                while (j < frontmatterEndLine) {
                    val next = lines[j]
                    if (next.isEmpty() || next[0].isWhitespace()) {
                        blockLines.add(next.trim())
                    } else break
                    j++
                }
                resolved = if (fold) {
                    blockLines.joinToString(" ").trim()
                } else {
                    blockLines.joinToString("\n").trim('\n')
                }
                i = j
            } else {
                resolved = rawValue
                i++
            }

            when (key) {
                "name" -> name = resolved
                "description" -> description = resolved
                "version" -> version = resolved
            }
        }

        if (name.isBlank()) return null

        val bodyStartLine = frontmatterEndLine + 1
        val body = if (bodyStartLine < lines.size) {
            lines.subList(bodyStartLine, lines.size).joinToString("\n").trim('\n')
        } else ""

        return ParsedSkill(name, description, version, body)
    }
}

/**
 * Skills live in folders: <root>/<skill-id>/SKILL.md (plus any sibling files the skill references).
 * Roots: %APPDATA%\OpenMinis\skills and <workspace>\.skills (project-local).
 */
class SkillLoader(private val roots: () -> List<File>) {
    fun load(): List<Skill> = roots().flatMap { root ->
        (root.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { dir ->
            val md = File(dir, "SKILL.md").takeIf { it.isFile } ?: File(dir, "skill.md").takeIf { it.isFile } ?: return@mapNotNull null
            val parsed = runCatching { SkillMd.parseSkillMd(md.readText()) }.getOrNull() ?: return@mapNotNull null
            Skill(parsed.name, parsed.description, parsed.version, md, parsed.body)
        }
    }.distinctBy { it.name }.sortedBy { it.name.lowercase() }

    /** System-prompt fragment, modelled on SkillRepository.skillPromptFragment (name + description + path). */
    fun promptFragment(skills: List<Skill> = load(), max: Int = 20): String? {
        if (skills.isEmpty()) return null
        return buildString {
            appendLine("<available_skills>")
            appendLine("Skills are folders of instructions. When a task matches a skill, FIRST call file_read on its SKILL.md and follow it; relative paths in a skill are relative to its folder.")
            skills.take(max).forEach { s ->
                appendLine("- ${s.name}: ${s.description.take(200)} (path: ${s.file.absolutePath})")
            }
            append("</available_skills>")
        }
    }
}
