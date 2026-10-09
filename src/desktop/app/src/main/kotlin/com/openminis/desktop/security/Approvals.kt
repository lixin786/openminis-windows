// OpenMinis Desktop — fail-closed approval wrapper and the session-only shell approval memory.
// Security hardening batch (README "安全加固", layers 5 and 7).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.security

import com.openminis.app.logging.AppLogger
import com.openminis.desktop.tools.ApprovalRequest
import com.openminis.desktop.tools.ToolApprover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Wraps an approver so every failure mode answers **deny**: no window to show the dialog on
 * ([available] false or throwing), the dialog throwing, no answer within [timeoutMs]. Only an
 * explicit `true` from the user allows. The user's Stop (coroutine cancellation) still propagates.
 */
class FailClosedApprover(
    private val inner: ToolApprover,
    private val timeoutMs: Long = Long.MAX_VALUE,
    private val available: () -> Boolean = { true },
) : ToolApprover {
    override suspend fun approve(request: ApprovalRequest): Boolean {
        val ok = try { available() } catch (e: Exception) { false }
        if (!ok) {
            log("no window available to ask — denied: ${request.summary}")
            return false
        }
        return try {
            if (timeoutMs == Long.MAX_VALUE) inner.approve(request) == true
            else withTimeout(timeoutMs) { inner.approve(request) } == true
        } catch (e: TimeoutCancellationException) {
            log("no answer within ${timeoutMs / 1000}s — denied: ${request.summary}")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("approval failed (${e.javaClass.simpleName}: ${e.message}) — denied: ${request.summary}")
            false
        }
    }

    private fun log(msg: String) { runCatching { AppLogger.warning("Approval", msg) } }

    companion object {
        /** UI dialogs: a request nobody answers within 10 minutes is denied (scheduled / sub agent runs too). */
        const val UI_TIMEOUT_MS = 10 * 60 * 1000L

        fun wrap(a: ToolApprover): ToolApprover = a as? FailClosedApprover ?: FailClosedApprover(a)
    }
}

/**
 * "Allow for this session": exact command strings the user allowed again-without-asking, kept in
 * memory only and lost when the app exits. Nothing about shell commands is ever persisted, and
 * interpreters / metacharacter / nested / denylisted commands are never accepted here.
 */
object SessionShellApprovals {
    private val allowed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun contains(command: String): Boolean = command.trim() in allowed

    /** Returns false (and remembers nothing) when the command may not be remembered. */
    fun add(command: String): Boolean {
        val c = command.trim()
        if (!CommandGuard.mayBeRememberedForSession(c)) return false
        allowed += c
        return true
    }

    fun clear() = allowed.clear()
    fun snapshot(): Set<String> = synchronized(allowed) { allowed.toSet() }
}
