package ru.sodovaya.volty.domain.navigation.region

import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate

/** A validated request passed to the regional FTS4 geocoder. */
data class OfflineGeocoderRequest(
    val query: OfflineAutocompleteQuery,
    val near: GeoCoordinate?,
    val languageTag: String,
) {
    init {
        require(languageTag.isNotBlank()) { "languageTag must not be blank" }
    }
}

/** Platform adapter for a single installed regional search database. */
interface OfflineGeocoder {
    suspend fun search(request: OfflineGeocoderRequest): NavigationResult<List<PlaceCandidate>>
}

/** Keeps raw-query handling identical for online and offline autocomplete. */
object OfflineGeocoderRequestPolicy {
    fun create(
        rawQuery: String,
        near: GeoCoordinate?,
        languageTag: String,
        limit: Int = OfflineAutocompleteQueryPolicy.DEFAULT_LIMIT,
    ): OfflineGeocoderRequest? {
        require(languageTag.isNotBlank()) { "languageTag must not be blank" }
        val query = OfflineAutocompleteQueryPolicy.parse(rawQuery, limit) ?: return null
        return OfflineGeocoderRequest(
            query = query,
            near = near,
            languageTag = languageTag,
        )
    }
}
