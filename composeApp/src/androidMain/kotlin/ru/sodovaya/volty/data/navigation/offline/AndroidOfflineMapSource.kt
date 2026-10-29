package ru.sodovaya.volty.data.navigation.offline

import android.content.res.AssetManager
import java.io.Closeable
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

/** Bridges the regional PMTiles store to MapLibre's ordinary vector source API. */
class AndroidOfflineMapSource(
    assetManager: AssetManager,
    private val packageStore: AndroidOfflineRegionPackageStore,
) : Closeable {
    private val tileServer = AndroidOfflinePmtilesTileServer(assetManager)

    /**
     * Returns null when no installed package covers the map center. The caller
     * should keep its existing online style in that case.
     */
    fun sourceUrl(coordinate: GeoCoordinate?): String? {
        if (coordinate == null) return null
        val installed = packageStore.installedRegionIds()
            .asSequence()
            .mapNotNull(packageStore::active)
            .firstOrNull { it.manifest.coverage.contains(coordinate) }
            ?: return null
        return tileServer.sourceUrl(installed.mapFile)
    }

    fun glyphsUrl(): String = tileServer.glyphsUrl()

    // Retraction: considerDownload() used to live here and requested a download for
    // whichever cell held the point.  Nothing called it, and re-wiring it would have
    // brought back the silent neighbouring-cell downloads; downloads are explicit now.

    override fun close() = tileServer.close()
}

private fun ru.sodovaya.volty.domain.navigation.region.OfflineRegionCoverage.contains(
    coordinate: GeoCoordinate,
): Boolean {
    if (bbox.size != 4) return false
    return coordinate.longitude in bbox[0]..bbox[2] && coordinate.latitude in bbox[1]..bbox[3]
}
