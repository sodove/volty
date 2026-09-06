package ru.sodovaya.volty.domain.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceCandidateDeduplicationPolicyTest {
    @Test
    fun `same mall aliases collapse to the most meaningful category`() {
        val result = PlaceCandidateDeduplicationPolicy.deduplicate(
            listOf(
                candidate("food", "Алатырь", "amenity:food_court", 60.6000),
                candidate("mall", "Алатырь", "shop:mall", 60.6001),
                candidate("feature", "Алатырь", "feature", 60.6000),
            ),
        )

        assertEquals(1, result.size)
        assertEquals("Торговый центр", result.single().subtitle)
    }

    @Test
    fun `same name in distant branches remains separate`() {
        val result = PlaceCandidateDeduplicationPolicy.deduplicate(
            listOf(
                candidate("one", "Центральный", "shop:mall", 60.6000),
                candidate("two", "Центральный", "shop:mall", 60.6300),
            ),
        )

        assertEquals(2, result.size)
    }

    private fun candidate(id: String, title: String, subtitle: String, longitude: Double) = PlaceCandidate(
        id = id,
        title = title,
        subtitle = subtitle,
        coordinate = GeoCoordinate(56.83, longitude),
    )
}
