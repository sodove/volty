package ru.sodovaya.volty.domain.navigation.region

/** A sanitized prefix expression ready to be bound to an SQLite FTS4 MATCH parameter. */
data class OfflineAutocompleteQuery(
    val normalized: String,
    val tokens: List<String>,
    val ftsMatchExpression: String,
    val limit: Int,
)

object OfflineAutocompleteQueryPolicy {
    const val MIN_SEARCHABLE_CHARACTERS = 2
    const val DEFAULT_LIMIT = 8
    const val MAX_LIMIT = 20

    fun parse(
        rawQuery: String,
        limit: Int = DEFAULT_LIMIT,
    ): OfflineAutocompleteQuery? {
        val boundedLimit = limit.coerceIn(1, MAX_LIMIT)

        val normalized = normalize(rawQuery)
        if (normalized.length < MIN_SEARCHABLE_CHARACTERS) return null

        val tokens = normalized.split(' ')
        return OfflineAutocompleteQuery(
            normalized = normalized,
            tokens = tokens,
            ftsMatchExpression = tokens.joinToString(" AND ") { "$it*" },
            limit = boundedLimit,
        )
    }

    /** Uses the same Unicode-safe normalization for indexed values and input. */
    fun normalize(rawValue: String): String = buildString {
        var pendingSpace = false
        rawValue.trim().lowercase().forEach { character ->
            if (character.isLetterOrDigit()) {
                if (pendingSpace && isNotEmpty()) append(' ')
                append(if (character == 'ё') 'е' else character)
                pendingSpace = false
            } else if (isNotEmpty()) {
                pendingSpace = true
            }
        }
    }
}

/** Stable, bounded relevance ordering for the rows returned by regional FTS. */
object OfflineAutocompleteRankingPolicy {
    /**
     * The local index returns a bounded candidate window, so ranking must be
     * deterministic and happen after FTS matching. Relevance is the primary
     * signal: GPS proximity only breaks ties between equally good matches.
     */
    fun <T> order(
        rows: Iterable<OfflineAutocompleteRankedRow<T>>,
        preferProximity: Boolean,
    ): List<T> = rows.sortedWith(
        compareBy<OfflineAutocompleteRankedRow<T>> { it.relevanceScore }
            .thenBy { row ->
                if (preferProximity) row.distanceSquared ?: Double.POSITIVE_INFINITY
                else Double.POSITIVE_INFINITY
            }
            .thenBy { it.stableId },
    ).map(OfflineAutocompleteRankedRow<T>::value)

    fun score(
        query: OfflineAutocompleteQuery,
        displayName: String,
        searchableText: String,
    ): Int {
        val title = OfflineAutocompleteQueryPolicy.normalize(displayName)
        val searchable = OfflineAutocompleteQueryPolicy.normalize(searchableText)
        val titleTokens = title.split(' ').filter(String::isNotBlank)
        return when {
            title == query.normalized -> 0
            title.startsWith(query.normalized) -> 1
            query.tokens.all { token -> titleTokens.any { it.startsWith(token) } } -> 2
            searchable.startsWith(query.normalized) -> 3
            else -> 4
        }
    }
}

/** Internal ranking row kept platform-neutral so Android does not own search semantics. */
data class OfflineAutocompleteRankedRow<T>(
    val value: T,
    val relevanceScore: Int,
    val distanceSquared: Double?,
    val stableId: Long,
)
