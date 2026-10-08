// OpenMinis Desktop — model fallback chain. Port of the retry/fallback decision in the Android
// ui/chat/ChatViewModel.kt agent loop (AUTO_RETRY_DELAYS_SEC same-model retries,
// [T-android-fallback-providererror] isFallbackable, [T-fallback-5xx-status] fresh retry budget per
// member, [T-android-fallback-chain-refill] tried-set, buildFallbackProviders' skip of members
// without credentials, FallbackStrategy.always). Upstream builds the chain from a ModelGroup;
// the desktop chain is Settings → "Fallback models" behind the active provider/model.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.agent

import com.openminis.app.data.model.LLMError
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.core.ModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object FallbackPolicy {
    /** Upstream AUTO_RETRY_DELAYS_SEC = {1, 2, 4}. */
    val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L)

    /** Upstream `unwrapFlowException`: the first LLMError in the cause chain, else [e]. */
    fun unwrap(e: Throwable): Throwable {
        var c: Throwable? = e
        while (c != null) {
            if (c is LLMError) return c
            c = c.cause
        }
        return e
    }

    /** Worth retrying on the SAME model first (upstream: NetworkError || TransientError). */
    fun isTransient(e: Throwable): Boolean =
        e is LLMError.NetworkError || e is LLMError.TransientError || (e !is LLMError && e is java.io.IOException)

    /**
     * Should the turn move to the next model? Upstream's rule is
     * `isRateLimit || is5xx || isFallbackable || strategy == always`.
     *
     * Desktop addition (user requirement): a network/transient failure that survived the
     * same-model retries also falls back. Upstream deliberately does not, reasoning that a dead
     * local network cannot be fixed by switching; on the desktop the chain often mixes endpoints
     * (a local Ollama/LM Studio + a cloud API, or a relay + the vendor), where switching does help.
     */
    fun shouldFallback(e: Throwable, always: Boolean): Boolean {
        if (e is CancellationException || e is LLMError.Cancelled) return false
        if (always) return true
        val err = e as? LLMError
        return e is LLMError.RateLimited ||
            err?.isHttpServerError == true ||
            err?.isFallbackable == true ||
            isTransient(e)
    }

    fun reason(e: Throwable): String = when (e) {
        is LLMError.RateLimited -> "Rate limited (429)"
        is LLMError.ProviderError -> e.detail.take(160)
        is LLMError.TransientError -> (e.httpStatus?.let { "[$it] " } ?: "") + e.detail.take(160)
        is LLMError -> e.message ?: e.fallbackReason
        else -> e.message ?: e.javaClass.simpleName
    }
}

/**
 * One turn's walk down the chain. The model the turn switched to stays in use for the rest of
 * that turn (upstream: the turn-local `currentProvider` moves to `next`). Not thread-safe; one
 * instance per turn / per compaction.
 */
class FallbackChain(
    private val settings: AppSettings,
    private val factory: (AppSettings, ModelRef) -> LLMProvider,
    private val retryDelaysMs: List<Long> = FallbackPolicy.DEFAULT_RETRY_DELAYS_MS,
    private val onNotice: (String) -> Unit = {},
    private val onRetry: (attempt: Int, max: Int, reason: String) -> Unit = { _, _, _ -> },
) {
    val refs: List<ModelRef> = settings.modelChain()
    var index: Int = 0
        private set
    private val tried = mutableSetOf(0)
    private val providers = HashMap<Int, LLMProvider>()
    private val unusable = HashMap<Int, String>()
    private var retryAttempt = 0

    val currentRef: ModelRef get() = refs[index]

    /** The provider for the current link; creation errors on the primary propagate (e.g. missing key). */
    fun current(): LLMProvider = providers.getOrPut(index) {
        try { factory(settings, refs[index]) } catch (e: IllegalStateException) {
            // Missing credentials: same class as upstream's "instance has no credential" skip, so a
            // configured fallback can still serve the turn. Surfaces verbatim when nothing can.
            unusable[index] = e.message ?: "not configured"
            throw LLMError.InvalidApiKey(e.message ?: "")
        }
    }

    private fun usable(i: Int): Boolean {
        if (i in providers) return true
        if (i in unusable) return false
        return try {
            providers[i] = factory(settings, refs[i]); true
        } catch (e: Exception) {
            unusable[i] = e.message ?: e.javaClass.simpleName
            AppLogger.warning("FallbackChain", "skipping ${refs[i].label}: ${unusable[i]}")
            false
        }
    }

    /**
     * Decide what to do after [error]: returns true when the caller should retry (after a
     * same-model backoff, or on the next model), false when the error should surface.
     */
    suspend fun onFailure(error: Throwable): Boolean {
        val actual = FallbackPolicy.unwrap(error)
        if (actual is CancellationException) throw actual
        if (FallbackPolicy.isTransient(actual) && retryAttempt < retryDelaysMs.size) {
            val wait = retryDelaysMs[retryAttempt]
            retryAttempt += 1
            AppLogger.warning("FallbackChain", "transient error on ${currentRef.label}, retry $retryAttempt/${retryDelaysMs.size} in ${wait}ms: ${actual.message}")
            onRetry(retryAttempt, retryDelaysMs.size, FallbackPolicy.reason(actual))
            delay(wait)
            return true
        }
        if (!FallbackPolicy.shouldFallback(actual, settings.fallbackOnAnyError)) return false
        val next = (refs.indices).firstOrNull { it !in tried && usable(it) } ?: return false
        val msg = "⚠️ ${currentRef.label}: ${FallbackPolicy.reason(actual)}\n→ Switched to ${refs[next].label}"
        AppLogger.info("FallbackChain", msg.replace('\n', ' '))
        onNotice(msg)
        tried += next
        index = next
        // Fresh same-model retry budget for the member we just switched to ([T-fallback-5xx-status]).
        retryAttempt = 0
        return true
    }

    /** Members that could not be tried, with why (upstream `unavailableGroupMembers`). */
    fun unavailableSummary(): String? = unusable.entries.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { (i, why) -> "⚠️ ${refs[i].label}: $why" }
}
