// OpenMinis Desktop — ranked memory retrieval for memory_get.
//
// Upstream data/repository/MemoryRepository.getMemory is a substring match (ALL keywords must
// appear within a ±2-line window); upstream's word segmentation lives in shared/TextSegmenter
// (Jieba for majority-Chinese text, ICU BreakIterator otherwise) and is used by voice correction.
// This desktop upgrade reuses that segmenter (copied verbatim, Jieba backed by jieba-analysis) and
// ranks memory chunks with Okapi BM25 plus a recency boost, so a query no longer has to match
// every keyword literally and Chinese queries match by word rather than by raw substring.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.tools

import android.content.Context
import com.openminis.app.shared.TextSegmenter
import java.io.File
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

/** Tokenizer: Han runs → Jieba search mode (+ character bigrams), other runs → BreakIterator words. */
object MemoryTokenizer {
    private val segmenter by lazy { TextSegmenter.getInstance(Context.APP) }

    private val EN_STOP = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "with", "is", "are", "was", "were", "be",
        "been", "it", "this", "that", "these", "those", "as", "by", "from", "i", "you", "he", "she", "we", "they", "me",
        "my", "your", "our", "do", "does", "did", "not", "no", "so", "if", "then", "but", "what", "which", "who", "how",
    )
    private val ZH_STOP = setOf(
        "的", "了", "是", "在", "和", "与", "及", "或", "我", "你", "他", "她", "它", "我们", "你们", "他们", "这", "那",
        "这个", "那个", "就", "也", "都", "而", "并", "被", "把", "让", "给", "对", "从", "到", "吗", "呢", "吧", "啊",
        "一个", "有", "没有", "什么", "怎么", "之", "其", "以", "及其", "等", "上", "下", "中",
    )

    private fun isHan(c: Char) = c.code in 0x3400..0x9FFF || c.code in 0xF900..0xFAFF

    /** Light English normalisation so "files"/"file", "configs"/"config" meet. */
    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
        else -> w
    }

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val han = isHan(text[i])
            var j = i
            while (j < n && isHan(text[j]) == han) j++
            val run = text.substring(i, j)
            if (han) {
                // Jieba via the upstream facade (a pure-Han run is always "majority Chinese").
                segmenter.segmentForSearch(run).forEach { t -> if (t.isNotBlank() && t !in ZH_STOP) out += t }
                // Bigrams catch words the dictionary does not know (names, jargon).
                if (run.length >= 2) for (k in 0 until run.length - 1) out += run.substring(k, k + 2)
                else if (run.length == 1 && run !in ZH_STOP) out += run
            } else {
                segmenter.segmentForSearch(run).forEach { t ->
                    val w = t.lowercase().trim()
                    if (w.isNotEmpty() && w.any { it.isLetterOrDigit() } && w !in EN_STOP) out += stem(w)
                }
            }
            i = j
        }
        return out
    }
}

class MemorySearch(private val dir: File) {
    data class Chunk(val file: String, val date: LocalDate?, val startLine: Int, val text: String)
    data class Hit(val chunk: Chunk, val score: Double)

    companion object {
        const val K1 = 1.2
        const val B = 0.75
        /** Recency boost: score × (1 + RECENCY_WEIGHT · e^(−age/HALF_LIFE·ln2)). */
        const val RECENCY_WEIGHT = 0.5
        const val RECENCY_HALF_LIFE_DAYS = 30.0
        /** GLOBAL.md holds standing facts the user curated; a mild constant boost instead of recency. */
        const val GLOBAL_BOOST = 1.25
        private val DAILY = Regex("(\\d{4}-\\d{2}-\\d{2})\\.md")
        private val HEADER = Regex("^#{1,6} ")
    }

    /** Split files into retrievable chunks: daily logs by `### HH:mm` entries, GLOBAL.md by heading/paragraph. */
    fun chunks(includeGlobal: Boolean): List<Chunk> {
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
            .filter { includeGlobal || it.name != "GLOBAL.md" }
            .filter { it.name == "GLOBAL.md" || DAILY.matches(it.name) }
        val out = ArrayList<Chunk>()
        for (f in files) {
            val date = DAILY.matchEntire(f.name)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull() ?: continue
            var start = 0
            val buf = StringBuilder()
            fun flush(endExclusive: Int) {
                val t = buf.toString().trim()
                if (t.isNotEmpty()) out += Chunk(f.name, date, start + 1, t)
                buf.setLength(0); start = endExclusive
            }
            for ((idx, line) in lines.withIndex()) {
                val boundary = HEADER.containsMatchIn(line) || (date == null && line.isBlank() && buf.length > 400)
                if (boundary && buf.isNotBlank()) flush(idx)
                if (buf.isEmpty()) start = idx
                buf.append(line).append('\n')
                // Very long entries are split so one log dump cannot dominate.
                if (buf.length > 2000) flush(idx + 1)
            }
            flush(lines.size)
        }
        return out
    }

    fun search(query: String, includeGlobal: Boolean, limit: Int = 8, today: LocalDate = LocalDate.now()): List<Hit> {
        val q = MemoryTokenizer.tokenize(query).distinct()
        if (q.isEmpty()) return emptyList()
        val docs = chunks(includeGlobal)
        if (docs.isEmpty()) return emptyList()
        val tokenized = docs.map { MemoryTokenizer.tokenize(it.text) }
        val avgLen = tokenized.sumOf { it.size }.toDouble() / docs.size
        val df = HashMap<String, Int>()
        for (toks in tokenized) for (t in toks.toSet()) if (t in q) df[t] = (df[t] ?: 0) + 1
        val nDocs = docs.size.toDouble()
        val rawQuery = query.trim().lowercase()
        val hits = ArrayList<Hit>()
        for ((i, toks) in tokenized.withIndex()) {
            if (toks.isEmpty()) continue
            val tf = HashMap<String, Int>()
            for (t in toks) if (t in q) tf[t] = (tf[t] ?: 0) + 1
            if (tf.isEmpty()) continue
            var score = 0.0
            for ((t, f) in tf) {
                val d = df[t] ?: continue
                val idf = ln(1 + (nDocs - d + 0.5) / (d + 0.5))
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * toks.size / avgLen))
            }
            // Coverage: reward chunks that match more of the distinct query terms.
            score *= 0.5 + 0.5 * tf.size.toDouble() / q.size
            if (rawQuery.length >= 2 && docs[i].text.lowercase().contains(rawQuery)) score *= 1.3
            val c = docs[i]
            score *= if (c.date == null) GLOBAL_BOOST else {
                val age = (today.toEpochDay() - c.date.toEpochDay()).coerceAtLeast(0).toDouble()
                1 + RECENCY_WEIGHT * exp(-age * ln(2.0) / RECENCY_HALF_LIFE_DAYS)
            }
            hits += Hit(c, score)
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.chunk.date ?: LocalDate.MAX }).take(limit)
    }

    /** memory_get output: best chunks first, labelled like upstream (`[file — …]`), capped at [maxChars]. */
    fun format(query: String, hits: List<Hit>, maxChars: Int = 20_000): String {
        if (hits.isEmpty()) return "No matches found for: $query"
        val sb = StringBuilder()
        sb.append("Top ${hits.size} memory match(es) for \"").append(query.trim()).append("\" (ranked by relevance, recent first on ties):\n\n")
        for (h in hits) {
            val entry = "[${h.chunk.file} line ${h.chunk.startLine} — score %.2f]\n%s\n\n".format(h.score, h.chunk.text)
            if (sb.length + entry.length > maxChars) { sb.append("[Truncated at ${maxChars / 1000}k chars — use more specific keywords.]"); break }
            sb.append(entry)
        }
        return sb.toString().trimEnd()
    }
}
