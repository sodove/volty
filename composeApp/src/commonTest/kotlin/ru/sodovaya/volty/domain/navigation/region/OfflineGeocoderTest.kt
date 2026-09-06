package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class OfflineGeocoderTest {
    @Test
    fun request_policy_builds_fts_request_for_two_character_query() {
        val request = OfflineGeocoderRequestPolicy.create(
            rawQuery = "  Плотинка, Ленина ",
            near = GeoCoordinate(56.8389, 60.6057),
            languageTag = "ru-RU",
        )

        requireNotNull(request)
        assertEquals("плотинка ленина", request.query.normalized)
        assertEquals("плотинка* AND ленина*", request.query.ftsMatchExpression)
        assertEquals("ru-RU", request.languageTag)
    }

    @Test
    fun request_policy_does_not_call_database_for_short_or_blank_query() {
        assertNull(OfflineGeocoderRequestPolicy.create("а", null, "ru-RU"))
        assertNull(OfflineGeocoderRequestPolicy.create("  !!! ", null, "ru-RU"))
    }

    @Test
    fun request_requires_a_non_blank_language_tag() {
        val error = runCatching {
            OfflineGeocoderRequestPolicy.create("ек", null, " ")
        }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class, error?.let { it::class })
    }
}
