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

        val normalized = buildString {
            var pendingSpace = false
            rawQuery.trim().lowercase().forEach { character ->
                if (character.isLetterOrDigit()) {
                    if (pendingSpace && isNotEmpty()) append(' ')
                    append(character)
                    pendingSpace = false
                } else if (isNotEmpty()) {
                    pendingSpace = true
                }
            }
        }
        if (normalized.length < MIN_SEARCHABLE_CHARACTERS) return null

        val tokens = normalized.split(' ')
        return OfflineAutocompleteQuery(
            normalized = normalized,
            tokens = tokens,
            ftsMatchExpression = tokens.joinToString(" AND ") { "$it*" },
            limit = boundedLimit,
        )
    }
}
