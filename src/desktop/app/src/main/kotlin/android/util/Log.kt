// Desktop shim for an Android framework API so ported OpenMinis code compiles unchanged on the JVM.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package android.util

object Log {
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable? = null): Int = emit("V", tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable? = null): Int = emit("D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable? = null): Int = emit("I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable? = null): Int = emit("W", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = emit("W", tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable? = null): Int = emit("E", tag, msg, tr)
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""

    private fun emit(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        com.openminis.app.logging.AppLogger.log(level, tag ?: "?", msg ?: "", tr)
        return 0
    }
}
