package ru.sodovaya.volty.data.navigation.offline

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus

/** Bridges the regional PMTiles store to MapLibre's ordinary vector source API. */
class AndroidOfflineMapSource(
    private val packageStore: AndroidOfflineRegionPackageStore,
    private val packages: OfflineRegionPackageRepository,
    private val downloadScope: CoroutineScope,
) : Closeable {
    private val tileServer = AndroidOfflinePmtilesTileServer()
    private val scheduledDownloads = ConcurrentHashMap.newKeySet<String>()
    private val automaticRetryAfterMillis = ConcurrentHashMap<String, Long>()

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

    /**
     * Starts at most one background MAP download for the catalog region under
     * the current point. The current online map remains available while the
     * package is being fetched.
     */
    fun considerDownload(coordinate: GeoCoordinate?) {
        if (coordinate == null) return
        val state = packages.states.value.firstOrNull { it.region.bounds.contains(coordinate) }
            ?: return
        if (state.status == OfflineRegionPackageStatus.READY ||
            state.status == OfflineRegionPackageStatus.DOWNLOADING ||
            state.status == OfflineRegionPackageStatus.INSTALLING ||
            state.status == OfflineRegionPackageStatus.VERIFYING ||
            state.status == OfflineRegionPackageStatus.DELETING ||
            state.status == OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL
        ) return
        val regionId = state.region.regionId
        val now = System.currentTimeMillis()
        if (automaticRetryAfterMillis[regionId]?.let { now < it } == true) return
        if (!scheduledDownloads.add(regionId)) return
        downloadScope.launch {
            try {
                packages.requestDownload(
                    regionId = regionId,
                    trigger = ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadTrigger.MAP,
                )
            } finally {
                scheduledDownloads.remove(regionId)
                val latest = packages.states.value.firstOrNull { it.region.regionId == regionId }
                if (latest?.status != OfflineRegionPackageStatus.READY) {
                    automaticRetryAfterMillis[regionId] =
                        System.currentTimeMillis() + AUTOMATIC_RETRY_COOLDOWN_MILLIS
                } else {
                    automaticRetryAfterMillis.remove(regionId)
                }
            }
        }
    }

    override fun close() = tileServer.close()
}

private const val AUTOMATIC_RETRY_COOLDOWN_MILLIS = 30_000L

private fun ru.sodovaya.volty.domain.navigation.region.OfflineRegionCoverage.contains(
    coordinate: GeoCoordinate,
): Boolean {
    if (bbox.size != 4) return false
    return coordinate.longitude in bbox[0]..bbox[2] && coordinate.latitude in bbox[1]..bbox[3]
}
