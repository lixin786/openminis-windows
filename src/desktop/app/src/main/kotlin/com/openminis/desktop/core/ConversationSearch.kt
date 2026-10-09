package com.openminis.desktop.core

/**
 * Sidebar ordering and search. Pure functions (no Compose, no i18n) so they are unit-tested directly;
 * the caller passes [titleOf] so the localized untitled title is what gets matched.
 */
object ConversationSearch {
    const val MAX_TITLE = 120
    private const val SNIPPET_BEFORE = 24
    private const val SNIPPET_LEN = 90

    /** Pinned chats first, each group keeping the list's own (most-recent-first) order. */
    fun arrange(list: List<Conversation>): List<Conversation> = list.filter { it.pinned } + list.filterNot { it.pinned }

    /** Whitespace-separated, case-insensitive terms; duplicates dropped. */
    fun terms(query: String): List<String> =
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }.distinct()

    data class Hit(
        val conversation: Conversation,
        /** Every term is in the title. */
        val titleMatch: Boolean,
        /** A one-line excerpt of the message that matched best, null when the title alone matched. */
        val snippet: String?,
    )

    /**
     * Every term must occur somewhere in the chat (title or any message, AND across terms, any order).
     * Chats whose title holds every term come first; otherwise the [arrange] order is kept.
     */
    fun search(list: List<Conversation>, query: String, titleOf: (Conversation) -> String): List<Hit> {
        val ts = terms(query)
        if (ts.isEmpty()) return arrange(list).map { Hit(it, titleMatch = false, snippet = null) }
        val hits = arrange(list).mapNotNull { c ->
            val title = titleOf(c).lowercase()
            val texts = c.messages.map { it.displayText }.filter { it.isNotBlank() }
            val lower = texts.map { it.lowercase() }
            if (!ts.all { t -> t in title || lower.any { t in it } }) return@mapNotNull null
            val titleMatch = ts.all { it in title }
            val snippet = if (titleMatch) null else {
                val best = lower.indices.maxByOrNull { i -> ts.count { it in lower[i] } }
                best?.let { i -> excerpt(texts[i], ts.filter { it in lower[i] }) }
            }
            Hit(c, titleMatch, snippet)
        }
        return hits.filter { it.titleMatch } + hits.filterNot { it.titleMatch }
    }

    /** One line around the earliest match, whitespace collapsed, with ellipses where cut. */
    fun excerpt(text: String, terms: List<String>): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val low = flat.lowercase()
        val at = terms.mapNotNull { t -> low.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - SNIPPET_BEFORE).coerceAtLeast(0)
        val end = (start + SNIPPET_LEN).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }

    /** Case-insensitive [start, end) ranges of every term in [text], merged, for highlighting. */
    fun matchRanges(text: String, terms: List<String>): List<IntRange> {
        val low = text.lowercase()
        val raw = terms.filter { it.isNotEmpty() }.flatMap { t ->
            generateSequence(low.indexOf(t).takeIf { it >= 0 }) { p -> low.indexOf(t, p + 1).takeIf { it >= 0 } }
                .map { it until it + t.length }.toList()
        }.sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (r in raw) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    /** Rename input → stored title: one line, trimmed, capped; null when nothing is left. */
    fun cleanTitle(input: String): String? =
        input.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_TITLE).trim().ifEmpty { null }
}
