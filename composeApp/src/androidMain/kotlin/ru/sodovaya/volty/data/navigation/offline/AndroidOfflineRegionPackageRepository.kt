package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.navigation.region.OfflineDownloadPreferences
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkAvailability
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalog
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogCodec
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogPolicy
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogEntry
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionComponent
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPlanFactory
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadTrigger
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailure
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionResumeDecision
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPolicy

/**
 * Catalog-backed Android implementation for automatic and Settings downloads.
 *
 * The catalog endpoint is intentionally injected. The repository contains no
 * provider key and cannot accidentally point a release build at a developer's
 * local machine.
 */
class AndroidOfflineRegionPackageRepository(
    context: Context,
    private val catalogUrl: String,
    private val currentAppVersionCode: Int,
    private val preferences: () -> OfflineDownloadPreferences = { OfflineDownloadPreferences() },
    private val userAgent: String = "Volty/0.7 offline-region-repository",
) : OfflineRegionPackageRepository {
    private val applicationContext = context.applicationContext
    private val packageStore = AndroidOfflineRegionPackageStore(applicationContext, currentAppVersionCode)
    private val downloader = AndroidOfflineRegionArtifactDownloader(userAgent = userAgent)
    private val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _states = MutableStateFlow<List<OfflineRegionPackageState>>(emptyList())
    private val jobs = ConcurrentHashMap<String, Job>()
    private var catalog: OfflineRegionCatalog? = null

    override val states: StateFlow<List<OfflineRegionPackageState>> = _states.asStateFlow()

    override suspend fun refreshCatalog() {
        val loaded = fetchCatalog()
        catalog = loaded
        publishStates()
    }

    override suspend fun requestDownload(
        regionId: String,
        trigger: OfflineRegionDownloadTrigger,
        meteredConfirmed: Boolean,
    ) {
        val entry = requireCatalogEntry(regionId)
        val release = entry.latestRelease ?: run {
            updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.FAILED, failure = OfflineRegionPackageFailure.INCOMPATIBLE) }
            return
        }
        val plan = when (val result = OfflineRegionDownloadPlanFactory.create(release, currentAppVersionCode)) {
            is ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPlanResult.Ready -> result.plan
            is ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPlanResult.Rejected -> {
                updateState(regionId) {
                    it.copy(
                        latestRelease = release,
                        status = OfflineRegionPackageStatus.FAILED,
                        downloadedBytes = 0L,
                        failure = OfflineRegionPackageFailure.INCOMPATIBLE,
                    )
                }
                return
            }
        }

        when (
            val decision = OfflineRegionDownloadPolicy.decide(
                network = networkAvailability(),
                trigger = trigger,
                preferences = preferences(),
                meteredConfirmed = meteredConfirmed,
            )
        ) {
            ru.sodovaya.volty.domain.navigation.region.OfflineDownloadDecision.Allowed -> Unit
            ru.sodovaya.volty.domain.navigation.region.OfflineDownloadDecision.RequiresMeteredConfirmation -> {
                updateState(regionId) {
                    it.copy(
                        latestRelease = release,
                        status = OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL,
                        failure = null,
                    )
                }
                return
            }
            is ru.sodovaya.volty.domain.navigation.region.OfflineDownloadDecision.Blocked -> {
                updateState(regionId) {
                    it.copy(latestRelease = release, status = OfflineRegionPackageStatus.WAITING_FOR_NETWORK, failure = null)
                }
                return
            }
        }

        val currentJob = currentCoroutineContext()[Job]
        if (currentJob != null) jobs[regionId] = currentJob
        val staging = packageStore.createDownloadStaging(regionId, release.releaseVersion)
        val stagedBytes = packageStore.stagedDownloadBytes(staging, plan)
        updateState(regionId) {
            it.copy(
                latestRelease = release,
                status = OfflineRegionPackageStatus.DOWNLOADING,
                downloadedBytes = stagedBytes,
                failure = null,
            )
        }
        try {
            val artifacts = downloader.download(plan, staging) { downloadedBytes ->
                updateState(regionId) { state ->
                    state.copy(
                        latestRelease = release,
                        status = OfflineRegionPackageStatus.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                    )
                }
            }
            updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.INSTALLING) }
            packageStore.install(release, plan, artifacts)
            updateState(regionId) {
                it.copy(
                    latestRelease = release,
                    status = OfflineRegionPackageStatus.READY,
                    installedReleaseVersion = release.releaseVersion,
                    downloadedBytes = plan.totalDownloadBytes,
                    failure = null,
                )
            }
            packageStore.discardDownloadStaging(staging)
        } catch (cancelled: CancellationException) {
            updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.PAUSED, failure = OfflineRegionPackageFailure.CANCELLED) }
            throw cancelled
        } catch (_: IOException) {
            updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.FAILED, failure = OfflineRegionPackageFailure.NETWORK) }
        } catch (_: Exception) {
            updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.FAILED, failure = OfflineRegionPackageFailure.UNKNOWN) }
        } finally {
            if (currentJob != null) jobs.remove(regionId, currentJob) else jobs.remove(regionId)
        }
    }

    override suspend fun pauseDownload(regionId: String) {
        updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.PAUSED, failure = null) }
        jobs[regionId]?.cancel()
    }

    override suspend fun resumeDownload(regionId: String) {
        requestDownload(regionId, OfflineRegionDownloadTrigger.SETTINGS)
    }

    override suspend fun deletePackage(regionId: String) {
        jobs[regionId]?.cancel()
        packageStore.delete(regionId)
        updateState(regionId) {
            it.copy(
                status = OfflineRegionPackageStatus.NOT_INSTALLED,
                installedReleaseVersion = null,
                downloadedBytes = 0L,
                failure = null,
            )
        }
    }

    private suspend fun fetchCatalog(): OfflineRegionCatalog = withContext(Dispatchers.IO) {
        val connection = (URL(catalogUrl).openConnection() as? HttpURLConnection)
            ?: throw IOException("Unsupported catalog URL")
        try {
            require(connection.url.protocol == "https") { "Catalog must use HTTPS" }
            connection.connectTimeout = CATALOG_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = CATALOG_READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            if (connection.responseCode !in 200..299) {
                throw IOException("Catalog request failed (${connection.responseCode})")
            }
            val length = connection.contentLengthLong
            if (length > MAX_CATALOG_BYTES) throw IOException("Catalog is too large")
            val body = connection.inputStream.use { input -> input.readBounded(MAX_CATALOG_BYTES) }
            val parsed = OfflineRegionCatalogCodec.parse(body)
            val loaded = (parsed as? ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogParseResult.Success)
                ?.catalog
                ?: throw IOException("Catalog is malformed")
            val errors = OfflineRegionCatalogPolicy.validate(loaded, currentAppVersionCode)
            if (errors.isNotEmpty()) throw IOException("Catalog validation failed")
            loaded
        } finally {
            connection.disconnect()
        }
    }

    private fun requireCatalogEntry(regionId: String): OfflineRegionCatalogEntry =
        requireNotNull(catalog?.regions?.firstOrNull { it.region.regionId == regionId }) {
            "Region catalog has not loaded region $regionId"
        }

    private fun publishStates() {
        val loaded = catalog ?: return
        _states.value = loaded.regions.map { entry -> stateFor(entry) }
    }

    private fun stateFor(entry: OfflineRegionCatalogEntry): OfflineRegionPackageState {
        val installed = packageStore.active(entry.region.regionId)
        val latest = entry.latestRelease
        val previous = _states.value.firstOrNull { it.region.regionId == entry.region.regionId }
        if (previous != null && previous.status.isTransient()) {
            val total = latest?.let { release ->
                release.components.routing.downloadBytes +
                    release.components.search.downloadBytes +
                    release.components.map.downloadBytes
            } ?: 0L
            return previous.copy(
                latestRelease = latest,
                downloadedBytes = previous.downloadedBytes.coerceIn(0L, total),
            )
        }
        return OfflineRegionPackageState(
            region = entry.region,
            latestRelease = latest,
            status = when {
                installed == null -> OfflineRegionPackageStatus.NOT_INSTALLED
                latest?.releaseVersion == installed.manifest.releaseVersion -> OfflineRegionPackageStatus.READY
                else -> OfflineRegionPackageStatus.UPDATE_AVAILABLE
            },
            installedReleaseVersion = installed?.manifest?.releaseVersion,
        )
    }

    private fun updateState(
        regionId: String,
        transform: (OfflineRegionPackageState) -> OfflineRegionPackageState,
    ) {
        _states.value = _states.value.map { state ->
            if (state.region.regionId == regionId) transform(state) else state
        }
    }

    private fun networkAvailability(): OfflineNetworkAvailability {
        val network = connectivity?.activeNetwork ?: return OfflineNetworkAvailability.OFFLINE
        val capabilities = connectivity?.getNetworkCapabilities(network)
            ?: return OfflineNetworkAvailability.OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return OfflineNetworkAvailability.OFFLINE
        }
        return if (connectivity?.isActiveNetworkMetered == true) {
            OfflineNetworkAvailability.METERED
        } else {
            OfflineNetworkAvailability.UNMETERED
        }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Long): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Catalog exceeds the size limit")
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun OfflineRegionPackageStatus.isTransient(): Boolean = this ==
        OfflineRegionPackageStatus.QUEUED || this == OfflineRegionPackageStatus.WAITING_FOR_NETWORK ||
        this == OfflineRegionPackageStatus.DOWNLOADING || this == OfflineRegionPackageStatus.PAUSED ||
        this == OfflineRegionPackageStatus.VERIFYING || this == OfflineRegionPackageStatus.INSTALLING

    private companion object {
        const val MAX_CATALOG_BYTES = 4L * 1024L * 1024L
        const val COPY_BUFFER_SIZE = 16 * 1024
        const val CATALOG_CONNECT_TIMEOUT_MILLIS = 10_000
        const val CATALOG_READ_TIMEOUT_MILLIS = 15_000
    }
}
