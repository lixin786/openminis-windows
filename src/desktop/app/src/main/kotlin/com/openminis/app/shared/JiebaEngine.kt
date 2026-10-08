// OpenMinis Desktop — desktop adapter for upstream shared/JiebaEngine.kt.
// Upstream binds CppJieba through JNI (cpp/jieba_jni.cpp) and extracts the dictionaries from APK
// assets. On the JVM the same dictionary family is available as the pure-Java port
// `com.huaban:jieba-analysis` (Apache-2.0), which ships dict.txt + the HMM model on its classpath,
// so this class keeps the upstream constructor/method surface and swaps only the backend.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.app.shared

import android.content.Context
import com.huaban.analysis.jieba.JiebaSegmenter

internal class JiebaEngine(@Suppress("UNUSED_PARAMETER") context: Context) {

    @Volatile
    private var segmenter: JiebaSegmenter? = null

    @Volatile
    private var failed = false

    /** True once the dictionaries have loaded successfully. */
    val isReady: Boolean get() = segmenter != null

    /** Load the dictionary (first call only, ~1 s). Returns true when Jieba is ready. */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (segmenter != null) return true
        if (failed) return false
        return try {
            // WordDictionary prints load timings to stdout; harmless.
            segmenter = JiebaSegmenter()
            true
        } catch (t: Throwable) {
            failed = true
            android.util.Log.e(TAG, "Jieba init failed", t)
            false
        }
    }

    /** Exact-mode segmentation (HMM on). Empty list if init failed. */
    fun segment(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return segmenter!!.sentenceProcess(text).filter { it.isNotBlank() }
    }

    /** Search-engine-mode segmentation (jieba `cut_for_search` = SegMode.INDEX). Empty list if init failed. */
    fun segmentForSearch(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return segmenter!!.process(text, JiebaSegmenter.SegMode.INDEX).map { it.word }.filter { it.isNotBlank() }
    }

    /**
     * Part-of-speech tagging. jieba-analysis has no POS tagger, so this returns an EMPTY list —
     * the documented "no POS signal" answer upstream callers already handle
     * (see TextSegmenter.posTag).
     */
    fun posTag(@Suppress("UNUSED_PARAMETER") text: String): List<Pair<String, String>> = emptyList()

    companion object {
        private const val TAG = "JiebaEngine"
    }
}
