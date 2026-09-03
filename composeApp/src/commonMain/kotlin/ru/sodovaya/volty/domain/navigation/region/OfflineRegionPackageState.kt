package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.flow.StateFlow

enum class OfflineRegionPackageFailure {
    NETWORK,
    STORAGE,
    CHECKSUM,
    INCOMPATIBLE,
    CANCELLED,
    UNKNOWN,
}

data class OfflineRegionPackageState(
    val region: OfflineRegionManifest,
    val latestRelease: OfflineRegionPackageManifest?,
    val status: OfflineRegionPackageStatus,
    val installedReleaseVersion: String? = null,
    val downloadedBytes: Long = 0L,
    val failure: OfflineRegionPackageFailure? = null,
) {
    init {
        require(downloadedBytes >= 0L) { "downloadedBytes must not be negative" }
        require(latestRelease != null || downloadedBytes == 0L) {
            "downloadedBytes requires a published release"
        }
        latestRelease?.let { release ->
            require(release.regionId == region.regionId) {
                "latest release must belong to the catalog region"
            }
            require(downloadedBytes <= totalDownloadBytes) {
                "downloadedBytes must not exceed the published download size"
            }
        }
    }

    val totalDownloadBytes: Long
        get() = latestRelease?.let { release ->
            release.components.routing.downloadBytes +
                release.components.search.downloadBytes +
                release.components.map.downloadBytes
        } ?: 0L

    val totalInstalledBytes: Long
        get() = latestRelease?.let { release ->
            release.components.routing.installedBytes +
                release.components.search.installedBytes +
                release.components.map.installedBytes
        } ?: 0L

    val downloadProgress: Float?
        get() = totalDownloadBytes.takeIf { it > 0L }?.let { total ->
            downloadedBytes.toFloat() / total.toFloat()
        }
}

/** The single package boundary used by Settings and automatic search/route/map requests. */
interface OfflineRegionPackageRepository {
    val states: StateFlow<List<OfflineRegionPackageState>>

    suspend fun refreshCatalog()

    suspend fun requestDownload(
        regionId: String,
        trigger: OfflineRegionDownloadTrigger,
        meteredConfirmed: Boolean = false,
    )

    suspend fun pauseDownload(regionId: String)

    suspend fun resumeDownload(regionId: String)

    suspend fun deletePackage(regionId: String)
}
