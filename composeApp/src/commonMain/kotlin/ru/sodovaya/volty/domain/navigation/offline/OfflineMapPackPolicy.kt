package ru.sodovaya.volty.domain.navigation.offline

import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.tan
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest

object OfflineMapPackPolicy {
    const val EKB_MIN_ZOOM: Int = 5
    const val EKB_MAX_ZOOM: Int = 13

    fun definition(
        region: OfflineRegionManifest,
        style: OfflineMapStyleVariant,
        tileLimit: Int,
    ): OfflineMapPackDefinition {
        require(region.regionId.isNotBlank()) { "regionId must not be blank" }
        require(tileLimit > 0) { "tileLimit must be positive" }
        require(EKB_MIN_ZOOM in 0..MAX_SUPPORTED_ZOOM && EKB_MAX_ZOOM in EKB_MIN_ZOOM..MAX_SUPPORTED_ZOOM) {
            "invalid map zoom range"
        }
        val styleUrl = style.url
        require(styleUrl.startsWith(OFM_STYLE_BASE_URL + "/")) { "style URL must be an OFM style URL" }
        return OfflineMapPackDefinition(
            key = OfflineMapPackKey(region.regionId, style, styleUrl),
            bounds = region.bounds,
            minZoom = EKB_MIN_ZOOM,
            maxZoom = EKB_MAX_ZOOM,
            tileLimit = tileLimit,
        )
    }

    /** Counts the XYZ tiles intersecting the bounds over the inclusive zoom range. */
    fun estimatedTileCount(bounds: OfflineRegionBounds, minZoom: Int, maxZoom: Int): Long {
        require(minZoom in 0..MAX_SUPPORTED_ZOOM) { "minZoom is outside XYZ range" }
        require(maxZoom in minZoom..MAX_SUPPORTED_ZOOM) { "maxZoom must not precede minZoom" }
        return (minZoom..maxZoom).sumOf { zoom ->
            val size = 1L shl zoom
            val west = xTile(bounds.west, size)
            val east = xTile(bounds.east, size)
            val north = yTile(bounds.north, size)
            val south = yTile(bounds.south, size)
            (east - west + 1L) * (south - north + 1L)
        }
    }

    private val OfflineMapStyleVariant.url: String
        get() = "$OFM_STYLE_BASE_URL/${name.lowercase()}"

    private fun xTile(longitude: Double, size: Long): Long =
        floor(((longitude + 180.0) / 360.0) * size).toLong().coerceIn(0L, size - 1L)

    private fun yTile(latitude: Double, size: Long): Long {
        val clamped = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        val normalized = (1.0 - asinh(tan(clamped * PI / 180.0)) / PI) / 2.0
        return floor(normalized * size).toLong().coerceIn(0L, size - 1L)
    }

    private const val OFM_STYLE_BASE_URL = "https://tiles.openfreemap.org/styles"
    private const val MAX_SUPPORTED_ZOOM = 22
    private const val MAX_MERCATOR_LATITUDE = 85.05112878
}
