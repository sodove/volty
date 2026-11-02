package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.flow.StateFlow
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState

enum class OfflineRegionPackageFailure {
    NETWORK,
    STORAGE,
    CHECKSUM,
    INCOMPATIBLE,
    CANCELLED,
    UNKNOWN,
}

/**
 * Lets the Android storage/download boundary preserve the reason for a
 * failure instead of flattening every IOException into a network error.
 */
class OfflineRegionPackageFailureException(
    val category: OfflineRegionPackageFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class OfflineRegionPackageState(
    val region: OfflineRegionManifest,
    val latestRelease: OfflineRegionPackageManifest?,
    val status: OfflineRegionPackageStatus,
    val onDemand: Boolean = false,
    val installedReleaseVersion: String? = null,
    val downloadedBytes: Long = 0L,
    val failure: OfflineRegionPackageFailure? = null,
    /** Supplied independently by the preparation coordinator from OfflineMapPackManager. */
    val mapPackState: OfflineMapPackState = OfflineMapPackState.Missing,
) {
    val navigationStatus: OfflineRegionPackageStatus get() = status
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

    /**
     * True once a release is on the device, whatever the status says right
     * now.  A transient status (an approval prompt, a failed update attempt)
     * is written over [status] but never over [installedReleaseVersion], so
     * this is the question automatic preparation must ask.  An earlier
     * revision looked at [status] alone, and a READY region that had been
     * overwritten with AWAITING_METERED_APPROVAL looked "not downloaded".
     */
    val isInstalled: Boolean
        get() = installedReleaseVersion != null ||
            status == OfflineRegionPackageStatus.READY ||
            status == OfflineRegionPackageStatus.UPDATE_AVAILABLE

    val totalDownloadBytes: Long
        get() = latestRelease?.let { release ->
            release.components.routing.downloadBytes +
                release.components.search.downloadBytes
        } ?: 0L

    val totalInstalledBytes: Long
        get() = latestRelease?.let { release ->
            release.components.routing.installedBytes +
                release.components.search.installedBytes
        } ?: 0L

    val downloadProgress: Float?
        get() = totalDownloadBytes.takeIf { it > 0L }?.let { total ->
            downloadedBytes.toFloat() / total.toFloat()
        }
}

/** The single package boundary used by Settings and automatic search/route/map requests. */
interface OfflineRegionPackageRepository {
    val states: StateFlow<List<OfflineRegionPackageState>>

    /** True after a verified catalog has been published into [states]. */
    fun isCatalogLoaded(): Boolean = false

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

/**
 * Whether a download request has anything left to do.  The repository asks
 * this before it touches the state: a region whose installed release is the
 * published one must never be re-downloaded and must never have its READY
 * status replaced by a metered-approval prompt.
 */
object OfflineRegionRequestPolicy {
    fun isAlreadyCurrent(installedReleaseVersion: String?, latestReleaseVersion: String?): Boolean =
        installedReleaseVersion != null && installedReleaseVersion == latestReleaseVersion

    /**
     * The state a request for [state] must leave behind when the installed
     * release already is [latest]: READY, with any approval/waiting/failure
     * residue cleared, or null when there is real work to do.
     */
    fun settledIfCurrent(
        state: OfflineRegionPackageState,
        installedReleaseVersion: String?,
        latest: OfflineRegionPackageManifest,
    ): OfflineRegionPackageState? {
        if (!isAlreadyCurrent(installedReleaseVersion, latest.releaseVersion)) return null
        val total = latest.components.routing.downloadBytes + latest.components.search.downloadBytes
        return state.copy(
            latestRelease = latest,
            status = OfflineRegionPackageStatus.READY,
            installedReleaseVersion = installedReleaseVersion,
            downloadedBytes = total,
            failure = null,
        )
    }
}
