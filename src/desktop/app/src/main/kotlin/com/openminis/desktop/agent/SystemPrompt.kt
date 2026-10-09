// OpenMinis Desktop — system prompt assembly (desktop counterpart of the Android prompt builder).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.agent

import com.openminis.desktop.core.AppPaths
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ShellKind
import com.openminis.desktop.skills.SkillLoader
import com.openminis.desktop.tools.MemoryStore
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object SystemPrompt {
    fun build(settings: AppSettings, memory: MemoryStore, skills: SkillLoader): String = buildString {
        appendLine("You are Minis, a capable AI agent running as the OpenMinis desktop app on the user's computer.")
        appendLine("Current time: ${ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)}")
        appendLine("Operating system: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        if (settings.toolsEnabled) {
            val shell = if (!AppPaths.isWindows) "bash" else when (settings.shell) {
                ShellKind.POWERSHELL -> "PowerShell (use PowerShell syntax, e.g. Get-ChildItem, Select-String; not bash)"
                ShellKind.CMD -> "cmd.exe"
                ShellKind.WSL -> "bash inside WSL (Windows drives are under /mnt/c, …)"
            }
            appendLine("Your workspace folder is ${settings.workspace().absolutePath}; relative paths and the shell start there.")
            appendLine("The `shell` tool runs $shell directly on the host — there is no sandbox, so be careful with destructive commands.")
            appendLine("Prefer file_read/file_write/file_edit over shell for file contents. Work step by step and verify results.")
        }
        appendLine("Format answers in Markdown.")
        if (settings.memoryEnabled) {
            memory.promptContext()?.let {
                appendLine()
                appendLine("<memory>")
                appendLine("Persistent memory (GLOBAL.md is maintained by the user; daily logs by you via memory_write):")
                appendLine(it)
                appendLine("</memory>")
            }
        }
        if (settings.skillsEnabled && settings.toolsEnabled) {
            skills.promptFragment(skills.load().filter { it.id !in settings.disabledSkills })?.let { appendLine(); appendLine(it) }
        }
        if (settings.systemPrompt.isNotBlank()) {
            appendLine()
            appendLine("<user_instructions>")
            appendLine(settings.systemPrompt.trim())
            appendLine("</user_instructions>")
        }
    }.trim()
}
