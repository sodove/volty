package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineAutocompleteRankingPolicyTest {
    @Test
    fun exact_title_is_more_relevant_than_a_title_prefix() {
        val query = requireNotNull(OfflineAutocompleteQueryPolicy.parse("Плотинка"))

        val exact = OfflineAutocompleteRankingPolicy.score(
            query = query,
            displayName = "Плотинка",
            searchableText = "Плотинка Екатеринбург",
        )
        val prefix = OfflineAutocompleteRankingPolicy.score(
            query = query,
            displayName = "Плотинка набережная",
            searchableText = "Плотинка набережная Екатеринбург",
        )

        assertTrue(exact < prefix)
    }

    @Test
    fun title_prefix_is_more_relevant_than_a_match_only_in_search_text() {
        val query = requireNotNull(OfflineAutocompleteQueryPolicy.parse("Ленина"))

        val titlePrefix = OfflineAutocompleteRankingPolicy.score(
            query = query,
            displayName = "Ленина, 10",
            searchableText = "Ленина 10 Екатеринбург",
        )
        val addressMatch = OfflineAutocompleteRankingPolicy.score(
            query = query,
            displayName = "Дом 10",
            searchableText = "улица Ленина 10 Екатеринбург",
        )

        assertTrue(titlePrefix < addressMatch)
    }

    @Test
    fun proximity_does_not_beat_a_stronger_title_match() {
        val ordered = OfflineAutocompleteRankingPolicy.order(
            rows = listOf(
                OfflineAutocompleteRankedRow(
                    value = "near-address-match",
                    relevanceScore = 3,
                    distanceSquared = 0.000001,
                    stableId = 1,
                ),
                OfflineAutocompleteRankedRow(
                    value = "far-exact-title",
                    relevanceScore = 0,
                    distanceSquared = 1.0,
                    stableId = 2,
                ),
            ),
            preferProximity = true,
        )

        assertEquals(listOf("far-exact-title", "near-address-match"), ordered)
    }

    @Test
    fun proximity_breaks_ties_between_equally_relevant_rows() {
        val ordered = OfflineAutocompleteRankingPolicy.order(
            rows = listOf(
                OfflineAutocompleteRankedRow(
                    value = "far",
                    relevanceScore = 1,
                    distanceSquared = 0.1,
                    stableId = 1,
                ),
                OfflineAutocompleteRankedRow(
                    value = "near",
                    relevanceScore = 1,
                    distanceSquared = 0.01,
                    stableId = 2,
                ),
            ),
            preferProximity = true,
        )

        assertEquals(listOf("near", "far"), ordered)
    }
}
