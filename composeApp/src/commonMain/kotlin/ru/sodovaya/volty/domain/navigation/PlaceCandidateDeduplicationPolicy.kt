package ru.sodovaya.volty.domain.navigation

import kotlin.math.cos
import kotlin.math.sqrt

/** Collapses provider aliases without merging same-named places in different branches. */
object PlaceCandidateDeduplicationPolicy {
    private const val SAME_PLACE_METERS = 50.0
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")

    fun deduplicate(
        candidates: List<PlaceCandidate>,
        limit: Int = candidates.size,
        mergeSameTitle: Boolean = true,
    ): List<PlaceCandidate> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val result = mutableListOf<PlaceCandidate>()
        candidates.forEach { candidate ->
            val duplicateIndex = result.indexOfFirst { existing ->
                distanceMeters(existing.coordinate, candidate.coordinate) <= SAME_PLACE_METERS &&
                    (sameIdentity(existing, candidate) ||
                        (mergeSameTitle && !isTransitStop(existing) && !isTransitStop(candidate) &&
                            !isNameOnlyShop(existing) && !isNameOnlyShop(candidate) &&
                            normalize(candidate.title).isNotBlank() &&
                            normalize(existing.title) == normalize(candidate.title)))
            }
            if (duplicateIndex < 0) {
                result += candidate.copy(subtitle = displaySubtitle(candidate.subtitle))
            } else {
                val existing = result[duplicateIndex]
                val incoming = candidate.copy(subtitle = displaySubtitle(candidate.subtitle))
                if (quality(incoming) > quality(existing)) result[duplicateIndex] = incoming
            }
        }
        return result.take(limit)
    }

    fun displaySubtitle(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        null, "", "feature" -> null
        "shop:mall", "mall", "торговый центр" -> "Торговый центр"
        "amenity:food_court", "food_court", "фуд-корт" -> "Фуд-корт"
        "shop:hookah", "hookah", "магазин кальянов" -> "Магазин кальянов"
        "highway:bus_stop", "bus_stop", "автобусная остановка" -> "Автобусная остановка"
        "railway:tram_stop", "tram_stop", "трамвайная остановка" -> "Трамвайная остановка"
        "railway:halt", "halt", "железнодорожная остановка" -> "Железнодорожная остановка"
        else -> raw.trim()
    }

    private fun isTransitStop(candidate: PlaceCandidate): Boolean {
        val subtitle = displaySubtitle(candidate.subtitle)?.lowercase().orEmpty()
        return subtitle.startsWith("автобусная остановка") ||
            subtitle.startsWith("трамвайная остановка") ||
            subtitle.startsWith("железнодорожная остановка")
    }

    private fun isNameOnlyShop(candidate: PlaceCandidate): Boolean =
        displaySubtitle(candidate.subtitle)?.lowercase() == "магазин кальянов"

    private fun sameIdentity(left: PlaceCandidate, right: PlaceCandidate): Boolean {
        val leftId = canonicalIdentity(left.id) ?: return false
        return leftId == canonicalIdentity(right.id)
    }

    private fun canonicalIdentity(value: String): String? {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank() || normalized.startsWith("photon:") || normalized.startsWith("rowid:")) return null
        return normalized
    }

    private fun quality(candidate: PlaceCandidate): Int {
        val subtitle = candidate.subtitle?.lowercase().orEmpty()
        val semantic = when {
            subtitle == "торговый центр" -> 30
            subtitle == "фуд-корт" -> 20
            subtitle.isNotBlank() -> 10
            else -> 0
        }
        return semantic + candidate.title.length.coerceAtMost(100) + candidate.subtitle.orEmpty().length
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace('ё', 'е')
        .replace(punctuation, " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun distanceMeters(left: GeoCoordinate, right: GeoCoordinate): Double {
        val latitudeRadians = Math.toRadians((left.latitude + right.latitude) / 2.0)
        val north = Math.toRadians(right.latitude - left.latitude) * EARTH_RADIUS_METERS
        val east = Math.toRadians(right.longitude - left.longitude) * EARTH_RADIUS_METERS * cos(latitudeRadians)
        return sqrt(north * north + east * east)
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
