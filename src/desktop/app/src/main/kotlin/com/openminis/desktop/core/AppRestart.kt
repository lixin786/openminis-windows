// OpenMinis Desktop — P2-6: "Restart now" after a restore.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

object AppRestart {
    /**
     * The command that started this process. The installed app is its own launcher (OpenMinis.exe), which needs
     * no arguments. A bare `java` without visible arguments cannot be re-run faithfully → null (restart by hand).
     */
    fun command(cmd: String? = ProcessHandle.current().info().command().orElse(null),
                args: List<String>? = ProcessHandle.current().info().arguments().map { it.toList() }.orElse(null)): List<String>? {
        if (cmd.isNullOrBlank()) return null
        val exe = cmd.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val isJava = exe == "java" || exe == "java.exe" || exe == "javaw" || exe == "javaw.exe"
        if (isJava && args.isNullOrEmpty()) return null
        return listOf(cmd) + (args ?: emptyList())
    }

    /** Starts a new instance; the caller then quits this one. False = could not (ask for a manual restart). */
    fun relaunch(): Boolean = runCatching { ProcessBuilder(command() ?: return false).start(); true }.getOrDefault(false)
}
