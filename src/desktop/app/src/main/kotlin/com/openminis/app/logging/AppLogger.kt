// Desktop rewrite of OpenMinis' AppLogger: daily-rotating log files under %APPDATA%\OpenMinis\logs.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.app.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    @Volatile var traceEnabled: Boolean = false
    @Volatile var verbose: Boolean = System.getenv("OPENMINIS_VERBOSE") == "1"
    @Volatile var logDir: File? = null
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(dir: File) { dir.mkdirs(); logDir = dir }

    fun debug(tag: String, message: String) { if (verbose) log("D", tag, message, null) }
    fun info(tag: String, message: String) = log("I", tag, message, null)
    fun warning(tag: String, message: String) = log("W", tag, message, null)
    fun warn(tag: String, message: String) = log("W", tag, message, null)
    fun error(tag: String, message: String, throwable: Throwable? = null) = log("E", tag, message, throwable)
    fun trace(tag: String, message: String) { if (traceEnabled) log("T", tag, message, null) }
    inline fun trace(tag: String, message: () -> String) { if (traceEnabled) log("T", tag, message(), null) }

    fun log(level: String, tag: String, message: String, tr: Throwable?) {
        if (!verbose && (level == "V" || level == "D")) return
        val now = Date()
        val line = buildString {
            append(ts.format(now)).append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (tr != null) append('\n').append(tr.stackTraceToString())
        }
        if (level == "E" || verbose) System.err.println(line)
        val dir = logDir ?: return
        synchronized(lock) {
            runCatching { File(dir, "${day.format(now)}.log").appendText(line + "\n") }
        }
    }
}
