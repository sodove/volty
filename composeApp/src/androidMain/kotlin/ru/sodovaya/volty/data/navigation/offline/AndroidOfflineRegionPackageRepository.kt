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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.navigation.region.OfflineDownloadPreferences
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkAvailability
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalog
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogCodec
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogPolicy
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogSignaturePolicy
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogEntry
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPlanFactory
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadTrigger
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageFailure
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPolicy
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPlanResult

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
    private val manifestVerifier: OfflineRegionManifestVerifier,
    private val packageStore: AndroidOfflineRegionPackageStore,
    private val preferences: () -> OfflineDownloadPreferences = { OfflineDownloadPreferences() },
    private val userAgent: String = "Volty/0.7 offline-region-repository",
) : OfflineRegionPackageRepository {
    private val applicationContext = context.applicationContext
    private val downloader = AndroidOfflineRegionArtifactDownloader(userAgent = userAgent)
    private val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _states = MutableStateFlow<List<OfflineRegionPackageState>>(emptyList())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val catalogRefreshMutex = Mutex()
    @Volatile
    private var catalog: OfflineRegionCatalog? = null

    override val states: StateFlow<List<OfflineRegionPackageState>> = _states.asStateFlow()

    init {
        _states.value = packageStore.installedRegions().map(::stateForInstalled)
    }

    override suspend fun refreshCatalog() = catalogRefreshMutex.withLock {
        val loaded = fetchCatalog()
        catalog = loaded
        publishStates()
    }

    override suspend fun requestDownload(
        regionId: String,
        trigger: OfflineRegionDownloadTrigger,
        meteredConfirmed: Boolean,
    ) {
        val currentJob = currentCoroutineContext()[Job]
        if (currentJob != null) {
            val previous = jobs.putIfAbsent(regionId, currentJob)
            if (previous != null && previous !== currentJob) return
        }
        try {
            requestDownloadLocked(regionId, trigger, meteredConfirmed)
        } finally {
            if (currentJob != null) jobs.remove(regionId, currentJob)
        }
    }

    private suspend fun requestDownloadLocked(
        regionId: String,
        trigger: OfflineRegionDownloadTrigger,
        meteredConfirmed: Boolean,
    ) {
        val entry = requireCatalogEntry(regionId)
        val release = entry.latestRelease ?: run {
            updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.FAILED, failure = OfflineRegionPackageFailure.INCOMPATIBLE) }
            return
        }
        val plan = when (val result = OfflineRegionDownloadPlanFactory.create(
            manifest = release,
            currentAppVersionCode = currentAppVersionCode,
        )) {
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
        }
    }

    override suspend fun pauseDownload(regionId: String) {
        val currentJob = currentCoroutineContext()[Job]
        jobs[regionId]
            ?.takeUnless { it === currentJob }
            ?.cancelAndJoin()
        updateState(regionId) { it.copy(status = OfflineRegionPackageStatus.PAUSED, failure = null) }
    }

    override suspend fun resumeDownload(regionId: String) {
        requestDownload(regionId, OfflineRegionDownloadTrigger.SETTINGS, meteredConfirmed = false)
    }

    override suspend fun deletePackage(regionId: String) {
        val currentJob = currentCoroutineContext()[Job]
        jobs[regionId]
            ?.takeUnless { it === currentJob }
            ?.cancelAndJoin()
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
            val unverified = OfflineRegionCatalogSignaturePolicy.unverifiedReleaseIds(
                catalog = loaded,
                verifier = manifestVerifier,
            )
            if (unverified.isNotEmpty()) throw IOException("Catalog contains unverified releases")
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
        val catalogIds = loaded.regions.mapTo(mutableSetOf()) { it.region.regionId }
        val localOnly = packageStore.installedRegions()
            .filter { it.manifest.regionId !in catalogIds }
            .map(::stateForInstalled)
        _states.value = loaded.regions.map(::stateFor) + localOnly
    }

    private fun stateForInstalled(
        installed: AndroidOfflineRegionPackageStore.InstalledOfflineRegion,
    ): OfflineRegionPackageState {
        val manifest = installed.manifest
        val bbox = manifest.coverage.bbox
        val region = OfflineRegionManifest(
            regionId = manifest.regionId,
            displayName = manifest.regionId,
            bounds = ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds(
                south = bbox[1],
                west = bbox[0],
                north = bbox[3],
                east = bbox[2],
            ),
        )
        return OfflineRegionPackageState(
            region = region,
            latestRelease = manifest,
            status = OfflineRegionPackageStatus.READY,
            installedReleaseVersion = manifest.releaseVersion,
            downloadedBytes = manifest.components.routing.downloadBytes +
                manifest.components.search.downloadBytes +
                manifest.components.map.downloadBytes,
        )
    }

    private fun stateFor(entry: OfflineRegionCatalogEntry): OfflineRegionPackageState {
        val installed = packageStore.active(entry.region.regionId)
        val latest = entry.latestRelease
        val previous = _states.value.firstOrNull { it.region.regionId == entry.region.regionId }
        val stagedBytes = latest?.let { stagedBytes(entry.region.regionId, it) } ?: 0L
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
                installed == null && stagedBytes > 0L -> OfflineRegionPackageStatus.PAUSED
                installed == null -> OfflineRegionPackageStatus.NOT_INSTALLED
                latest?.releaseVersion == installed.manifest.releaseVersion -> OfflineRegionPackageStatus.READY
                else -> OfflineRegionPackageStatus.UPDATE_AVAILABLE
            },
            installedReleaseVersion = installed?.manifest?.releaseVersion,
            downloadedBytes = stagedBytes,
        )
    }

    private fun stagedBytes(
        regionId: String,
        release: ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifest,
    ): Long {
        val plan = when (
            val result = OfflineRegionDownloadPlanFactory.create(
                manifest = release,
                currentAppVersionCode = currentAppVersionCode,
            )
        ) {
            is OfflineRegionDownloadPlanResult.Ready -> result.plan
            is OfflineRegionDownloadPlanResult.Rejected -> return 0L
        }
        val staging = packageStore.existingDownloadStaging(regionId, release.releaseVersion) ?: return 0L
        return packageStore.stagedDownloadBytes(staging, plan)
    }

    private fun updateState(
        regionId: String,
        transform: (OfflineRegionPackageState) -> OfflineRegionPackageState,
    ) {
        _states.update { states ->
            states.map { state ->
                if (state.region.regionId == regionId) transform(state) else state
            }
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
