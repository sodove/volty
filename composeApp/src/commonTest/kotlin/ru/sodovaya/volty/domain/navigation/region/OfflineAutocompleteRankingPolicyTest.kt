package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
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
}
