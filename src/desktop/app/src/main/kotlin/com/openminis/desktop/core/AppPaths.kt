// OpenMinis Desktop — replaces Android Context.filesDir / SharedPreferences locations.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import java.io.File

/**
 * All persistent state lives under one data root:
 *  - Windows: %APPDATA%\OpenMinis
 *  - elsewhere (dev/CI): ~/.openminis
 *  - override with env OPENMINIS_HOME or -Dopenminis.home=...
 *
 * The agent's default working directory (the "sandbox" replacement) is
 * %USERPROFILE%\OpenMinis\workspace, changeable in Settings.
 */
class AppPaths(val root: File) {
    val settingsFile: File get() = File(root, "settings.json")
    val conversationsDir: File get() = File(root, "conversations").apply { mkdirs() }
    val skillsDir: File get() = File(root, "skills").apply { mkdirs() }
    val memoryDir: File get() = File(root, "memory").apply { mkdirs() }
    val logsDir: File get() = File(root, "logs").apply { mkdirs() }
    val attachmentsDir: File get() = File(root, "attachments").apply { mkdirs() }

    init { root.mkdirs() }

    companion object {
        val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        fun defaultRoot(): File {
            System.getProperty("openminis.home")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            System.getenv("OPENMINIS_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            if (isWindows) {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
                return File(appData, "OpenMinis")
            }
            return File(System.getProperty("user.home"), ".openminis")
        }

        fun defaultWorkspace(): File {
            val home = System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
            return File(File(home, "OpenMinis"), "workspace")
        }
    }
}
