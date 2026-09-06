package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineAutocompleteQueryTest {
    @Test
    fun query_is_trimmed_collapsed_and_lowercased_for_fts_prefix_matching() {
        val query = OfflineAutocompleteQueryPolicy.parse("  Плотинка   Ленина  ")

        requireNotNull(query)
        assertEquals("плотинка ленина", query.normalized)
        assertEquals(listOf("плотинка", "ленина"), query.tokens)
        assertEquals("плотинка* AND ленина*", query.ftsMatchExpression)
    }

    @Test
    fun punctuation_cannot_change_the_fts_boolean_expression() {
        val query = OfflineAutocompleteQueryPolicy.parse("ленина\" OR *")

        requireNotNull(query)
        assertEquals(listOf("ленина", "or"), query.tokens)
        assertEquals("ленина* AND or*", query.ftsMatchExpression)
    }

    @Test
    fun autocomplete_starts_at_two_searchable_characters_and_caps_result_limit() {
        assertEquals("ек*", OfflineAutocompleteQueryPolicy.parse("ек")?.ftsMatchExpression)
        assertNull(OfflineAutocompleteQueryPolicy.parse("а"))
        assertEquals(20, OfflineAutocompleteQueryPolicy.parse("ек", limit = 100)?.limit)
    }

    @Test
    fun russian_yo_and_e_share_the_same_search_tokens() {
        assertEquals("елка", OfflineAutocompleteQueryPolicy.normalize("Ёлка"))
        assertEquals(
            "ел*",
            OfflineAutocompleteQueryPolicy.parse("ёл")?.ftsMatchExpression,
        )
    }
}
