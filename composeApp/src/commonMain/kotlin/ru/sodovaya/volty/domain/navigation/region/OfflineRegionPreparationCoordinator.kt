package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackKey
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackManager
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackPolicy
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant

/** Durable completion marker; implementations may persist it across process restarts. */
interface OfflineRegionPreparationStore {
    fun completed(regionId: String, style: OfflineMapStyleVariant, revision: String?): Boolean
    fun markCompleted(regionId: String, style: OfflineMapStyleVariant, revision: String?)
    fun lastCompletedStyle(regionId: String): OfflineMapStyleVariant? = null
}

class InMemoryOfflineRegionPreparationStore : OfflineRegionPreparationStore {
    private val completed = mutableSetOf<String>()
    private val styles = mutableMapOf<String, OfflineMapStyleVariant>()
    override fun completed(regionId: String, style: OfflineMapStyleVariant, revision: String?): Boolean =
        key(regionId, style, revision) in completed
    override fun markCompleted(regionId: String, style: OfflineMapStyleVariant, revision: String?) {
        completed += key(regionId, style, revision)
        styles[regionId] = style
    }
    override fun lastCompletedStyle(regionId: String): OfflineMapStyleVariant? = styles[regionId]
    private fun key(regionId: String, style: OfflineMapStyleVariant, revision: String?) = "$regionId|${style.name}|${revision.orEmpty()}"
}

class DefaultOfflineRegionPreparationCoordinator(
    private val mapPacks: OfflineMapPackManager,
    private val packages: OfflineRegionPackageRepository,
    private val scope: CoroutineScope,
    private val store: OfflineRegionPreparationStore = InMemoryOfflineRegionPreparationStore(),
    private val tileLimit: Int = 30_000,
    private val network: OfflineNetworkStatus? = null,
    private val preferences: () -> OfflineDownloadPreferences = { OfflineDownloadPreferences() },
) : OfflineRegionPreparationCoordinator {
    private val jobs = mutableMapOf<PreparationKey, Job>()
    private val requested = mutableSetOf<PreparationKey>()
    private val navigationRequested = mutableSetOf<String>()
    private val definitions = mutableMapOf<PreparationKey, OfflineMapPackDefinition>()
    private val activeStyles = mutableMapOf<String, OfflineMapStyleVariant>()
    private val _states = MutableStateFlow<List<OfflineRegionPreparationState>>(emptyList())
    override val states: StateFlow<List<OfflineRegionPreparationState>> = _states.asStateFlow()

    init {
        scope.launch {
            packages.states.collectAndPublish()
        }
        scope.launch {
            mapPacks.states.collect { publish(packages.states.value) }
        }
    }

    override suspend fun prepareCurrentRegion(coordinate: GeoCoordinate, style: OfflineMapStyleVariant) {
        val state = packages.states.value
            .filter { it.region.bounds.contains(coordinate) }
            .minByOrNull { it.region.regionId } ?: return
        prepare(state, style, OfflinePreparationTrigger.FIRST_MAP_OPEN)
    }

    override suspend fun prepareExplicitRegion(regionId: String, style: OfflineMapStyleVariant) {
        prepareExplicitRegionInternal(regionId, style, meteredConfirmed = false)
    }

    override suspend fun prepareExplicitRegionConfirmed(regionId: String, style: OfflineMapStyleVariant) {
        prepareExplicitRegionInternal(regionId, style, meteredConfirmed = true)
    }

    private suspend fun prepareExplicitRegionInternal(regionId: String, style: OfflineMapStyleVariant, meteredConfirmed: Boolean) {
        packages.states.value.firstOrNull { it.region.regionId == regionId }
            ?.let { prepare(it, style, OfflinePreparationTrigger.SETTINGS, meteredConfirmed) }
    }

    override suspend fun pause(regionId: String) {
        jobs.filterKeys { it.regionId == regionId }.values.toList().forEach { it.cancel() }
        packages.pauseDownload(regionId)
        definitions.filterKeys { it.regionId == regionId }.values.forEach { mapPacks.pause(it.key) }
    }

    override suspend fun retry(regionId: String) {
        val state = packages.states.value.firstOrNull { it.region.regionId == regionId } ?: return
        prepare(
            state,
            activeStyles[regionId] ?: OfflineMapStyleVariant.BRIGHT,
            OfflinePreparationTrigger.SETTINGS,
            force = true,
        )
    }

    private suspend fun prepare(
        packageState: OfflineRegionPackageState,
        style: OfflineMapStyleVariant,
        trigger: OfflinePreparationTrigger,
        force: Boolean = false,
        meteredConfirmed: Boolean = false,
    ) {
        val key = PreparationKey(packageState.region.regionId, style)
        activeStyles[packageState.region.regionId] = style
        val retryFailed = packageState.status == OfflineRegionPackageStatus.FAILED ||
            mapPacks.state(OfflineMapPackKey(
                packageState.region.regionId,
                style,
                "https://tiles.openfreemap.org/styles/${style.name.lowercase()}",
            )) is OfflineMapPackState.Failed
        val effectiveForce = force || retryFailed
        synchronized(jobs) {
            if (!effectiveForce && (jobs[key]?.isActive == true || key in requested)) return
            if (effectiveForce) {
                requested.remove(key)
                navigationRequested.remove(packageState.region.regionId)
            }
            requested += key
            jobs[key] = scope.launch { runPreparation(packageState, style, trigger, effectiveForce, meteredConfirmed) }
        }
        jobs[key]?.join()
    }

    private suspend fun runPreparation(
        packageState: OfflineRegionPackageState,
        style: OfflineMapStyleVariant,
        trigger: OfflinePreparationTrigger,
        force: Boolean,
        meteredConfirmed: Boolean,
    ) = supervisorScope {
        val key = PreparationKey(packageState.region.regionId, style)
        val downloadTrigger = if (trigger == OfflinePreparationTrigger.FIRST_MAP_OPEN) {
            OfflineRegionDownloadTrigger.MAP
        } else {
            OfflineRegionDownloadTrigger.SETTINGS
        }
        if (network != null) {
            when (OfflineRegionDownloadPolicy.decide(network.current(), downloadTrigger, preferences(), meteredConfirmed)) {
                OfflineDownloadDecision.Allowed -> Unit
                else -> {
                    // Let the package repository expose the waiting/approval
                    // state, but never activate a native map transfer first.
                    packages.requestDownload(packageState.region.regionId, downloadTrigger, meteredConfirmed)
                    return@supervisorScope
                }
            }
        }
        val metadata = packageState.region.mapPack
        val revision = metadata?.ofmStyleRevision
        if (!force && store.completed(packageState.region.regionId, style, revision)) return@supervisorScope
        val definition = OfflineMapPackPolicy.definition(
            packageState.region,
            style,
            tileLimit,
        ).let { base ->
            if (metadata == null) base else base.copy(
                bounds = metadata.bounds,
                minZoom = metadata.minZoom,
                maxZoom = metadata.maxZoom,
            )
        }
        definitions[key] = definition
        val mapJob = launch { mapPacks.prepare(definition) }
        val navigationJob = launch {
            if (packageState.status != OfflineRegionPackageStatus.READY &&
                packageState.status != OfflineRegionPackageStatus.UPDATE_AVAILABLE
            ) {
                val shouldRequest = synchronized(jobs) {
                    if (navigationRequested.contains(packageState.region.regionId)) false
                    else {
                        navigationRequested += packageState.region.regionId
                        true
                    }
                }
                if (shouldRequest) {
                    packages.requestDownload(
                        packageState.region.regionId,
                        downloadTrigger,
                        meteredConfirmed = meteredConfirmed,
                    )
                }
            }
        }
        mapJob.join()
        navigationJob.join()
        if (mapPacks.state(definition.key) == OfflineMapPackState.Ready &&
            packages.states.value.firstOrNull { it.region.regionId == packageState.region.regionId }?.status == OfflineRegionPackageStatus.READY
        ) store.markCompleted(packageState.region.regionId, style, revision)
        publish(packages.states.value)
    }

    private suspend fun kotlinx.coroutines.flow.Flow<List<OfflineRegionPackageState>>.collectAndPublish() =
        collect { publish(it) }

    private fun publish(packageStates: List<OfflineRegionPackageState>) {
        val next = packageStates.map { state ->
            val style = activeStyles[state.region.regionId]
                ?: store.lastCompletedStyle(state.region.regionId)
                ?: OfflineMapStyleVariant.BRIGHT
            val key = OfflineMapPackKey(state.region.regionId, style, "https://tiles.openfreemap.org/styles/${style.name.lowercase()}")
            val mapState = mapPacks.state(key) ?: state.mapPackState
            if ((state.status == OfflineRegionPackageStatus.READY || state.status == OfflineRegionPackageStatus.UPDATE_AVAILABLE) &&
                mapState == OfflineMapPackState.Ready
            ) {
                store.markCompleted(state.region.regionId, style, state.region.mapPack?.ofmStyleRevision)
            }
            OfflineRegionPreparationState(
                regionId = state.region.regionId,
                map = mapState,
                search = state.status.toNavigationComponent(state, isSearch = true),
                routing = state.status.toNavigationComponent(state, isSearch = false),
            )
        }
        _states.value = next
    }

    private fun OfflineRegionPackageStatus.toNavigationComponent(
        state: OfflineRegionPackageState,
        isSearch: Boolean,
    ): OfflineNavigationComponentState = when (this) {
        OfflineRegionPackageStatus.READY,
        OfflineRegionPackageStatus.UPDATE_AVAILABLE -> OfflineNavigationComponentState.Ready
        OfflineRegionPackageStatus.DOWNLOADING,
        OfflineRegionPackageStatus.VERIFYING,
        OfflineRegionPackageStatus.INSTALLING -> OfflineNavigationComponentState.Downloading(
            state.downloadedBytes,
            state.totalDownloadBytes,
        )
        OfflineRegionPackageStatus.PREPARING,
        OfflineRegionPackageStatus.QUEUED,
        OfflineRegionPackageStatus.WAITING_FOR_NETWORK,
        OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL -> OfflineNavigationComponentState.Preparing
        OfflineRegionPackageStatus.PAUSED -> OfflineNavigationComponentState.Paused
        OfflineRegionPackageStatus.FAILED -> OfflineNavigationComponentState.Failed(state.failure?.name ?: "unknown")
        else -> OfflineNavigationComponentState.Missing
    }

    private data class PreparationKey(val regionId: String, val style: OfflineMapStyleVariant)
}
