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

    @Test
    fun `nearby transit stops in different directions remain separate`() {
        val result = PlaceCandidateDeduplicationPolicy.deduplicate(
            listOf(
                candidate("stop-a", "Алатырь", "Автобусная остановка", 60.6000),
                candidate("stop-b", "Алатырь", "Автобусная остановка", 60.6002),
            ),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `same name hookah shops without addresses remain separate`() {
        val result = PlaceCandidateDeduplicationPolicy.deduplicate(
            listOf(
                candidate("shop-a", "Cosmoshop", "Магазин кальянов", 60.6000),
                candidate("shop-b", "Cosmoshop", "Магазин кальянов", 60.6002),
            ),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `nearby generic aliases collapse with wider place radius`() {
        val result = PlaceCandidateDeduplicationPolicy.deduplicate(
            listOf(
                candidate("feature-a", "ТЦ «Алатырь»", "feature", 60.6000),
                candidate("feature-b", "ТЦ «Алатырь»", "feature", 60.6010),
            ),
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `raw place kinds become human readable subtitles`() {
        assertEquals("Магазин кальянов", PlaceCandidateDeduplicationPolicy.displaySubtitle("shop:hookah"))
        assertEquals("Автобусная остановка", PlaceCandidateDeduplicationPolicy.displaySubtitle("highway:bus_stop"))
        assertEquals("Трамвайная остановка", PlaceCandidateDeduplicationPolicy.displaySubtitle("railway:tram_stop"))
    }

    private fun candidate(id: String, title: String, subtitle: String, longitude: Double) = PlaceCandidate(
        id = id,
        title = title,
        subtitle = subtitle,
        coordinate = GeoCoordinate(56.83, longitude),
    )
}
