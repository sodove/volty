package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val lock = Any()
    private val completed = mutableSetOf<String>()
    private val styles = mutableMapOf<String, OfflineMapStyleVariant>()
    override fun completed(regionId: String, style: OfflineMapStyleVariant, revision: String?): Boolean =
        synchronized(lock) { key(regionId, style, revision) in completed }
    override fun markCompleted(regionId: String, style: OfflineMapStyleVariant, revision: String?) {
        synchronized(lock) {
            completed += key(regionId, style, revision)
            styles[regionId] = style
        }
    }
    override fun lastCompletedStyle(regionId: String): OfflineMapStyleVariant? = synchronized(lock) { styles[regionId] }
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
    private val stateMutex = Mutex()
    private val jobs = mutableMapOf<PreparationKey, Job>()
    private val requested = mutableSetOf<PreparationKey>()
    private val pending = mutableMapOf<PreparationKey, PendingPreparation>()
    private val navigationRequested = mutableSetOf<String>()
    private val pausedRegions = mutableSetOf<String>()
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
        scope.launch {
            network?.changes?.collect { availability ->
                if (availability != OfflineNetworkAvailability.OFFLINE) retryPending()
            }
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
        // A first attempt blocked by the metered-data policy is already held
        // in `requested`/`pending`.  Confirmation must explicitly replay that
        // keyed request; a normal idempotent prepare would be coalesced and
        // leave the approval dialog stuck forever.
        prepareExplicitRegionInternal(regionId, style, meteredConfirmed = true, force = true)
    }

    private suspend fun prepareExplicitRegionInternal(
        regionId: String,
        style: OfflineMapStyleVariant,
        meteredConfirmed: Boolean,
        force: Boolean = false,
    ) {
        packages.states.value.firstOrNull { it.region.regionId == regionId }
            ?.let { prepare(it, style, OfflinePreparationTrigger.SETTINGS, force = force, meteredConfirmed = meteredConfirmed) }
    }

    override suspend fun pause(regionId: String) {
        val (activeJobs, definitionsToPause) = stateMutex.withLock {
            pausedRegions += regionId
            val active = jobs.filterKeys { it.regionId == regionId }.values.toList()
            requested.removeAll { it.regionId == regionId }
            pending.keys.removeAll { it.regionId == regionId }
            navigationRequested.remove(regionId)
            jobs.keys.filter { it.regionId == regionId }.forEach(jobs::remove)
            val definitionsToPause = definitions.filterKeys { it.regionId == regionId }.values.toList()
            definitions.keys.filter { it.regionId == regionId }
                .forEach(this@DefaultOfflineRegionPreparationCoordinator.definitions::remove)
            active to definitionsToPause
        }
        activeJobs.forEach { it.cancelAndJoin() }
        packages.pauseDownload(regionId)
        definitionsToPause.forEach { mapPacks.pause(it.key) }
    }

    override suspend fun retry(regionId: String) {
        val state = packages.states.value.firstOrNull { it.region.regionId == regionId } ?: return
        val style = stateMutex.withLock {
            pausedRegions.remove(regionId)
            activeStyles[regionId] ?: OfflineMapStyleVariant.BRIGHT
        }
        prepare(
            state,
            style,
            OfflinePreparationTrigger.SETTINGS,
            force = true,
        )
    }

    override suspend fun retryPending() {
        val requests = stateMutex.withLock { pending.values.toList() }
        requests.forEach { request ->
            // A connectivity callback can arrive while the first-open job is
            // still waiting for the repository to record its waiting state.
            // Do not consume that callback by trying to start a second job:
            // wait for the keyed attempt to finish, then replay the pending
            // request against the now-current network state.
            val activeJob = stateMutex.withLock {
                if (pending[request.key] != request) null
                else jobs[request.key]?.takeUnless { it.isCompleted }
            }
            activeJob?.join()
            val stillPending = stateMutex.withLock { pending[request.key] == request }
            if (!stillPending) return@forEach
            packages.states.value.firstOrNull { it.region.regionId == request.key.regionId }?.let { state ->
                prepare(state, request.style, request.trigger, force = true, meteredConfirmed = request.meteredConfirmed)
            }
        }
    }

    private suspend fun prepare(
        packageState: OfflineRegionPackageState,
        style: OfflineMapStyleVariant,
        trigger: OfflinePreparationTrigger,
        force: Boolean = false,
        meteredConfirmed: Boolean = false,
    ) {
        val key = PreparationKey(packageState.region.regionId, style)
        val retryFailed = packageState.status == OfflineRegionPackageStatus.FAILED ||
            mapPacks.state(mapKey(packageState.region.regionId, style)) is OfflineMapPackState.Failed
        val effectiveForce = stateMutex.withLock {
            if (packageState.region.regionId in pausedRegions && !force) return@withLock null
            activeStyles[packageState.region.regionId] = style
            // Being pending is the idempotent representation of an already
            // requested offline attempt. Only an explicit retry/recovery may
            // force a new keyed job; ordinary first-open effects must coalesce.
            force || retryFailed
        } ?: return
        val job = stateMutex.withLock {
            if (jobs[key]?.isCompleted == false) null
            else if (!effectiveForce && key in requested) null
            else {
                if (effectiveForce) {
                    requested.remove(key)
                    navigationRequested.remove(packageState.region.regionId)
                }
                requested += key
                scope.launch(start = CoroutineStart.LAZY) {
                    runPreparation(packageState, style, trigger, effectiveForce, meteredConfirmed)
                }.also { jobs[key] = it }
            }
        } ?: return
        job.start()
        job.join()
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
            val initialDecision = OfflineRegionDownloadPolicy.decide(
                network.current(),
                downloadTrigger,
                preferences(),
                meteredConfirmed,
            )
            if (initialDecision != OfflineDownloadDecision.Allowed) {
                // Publish the waiting request and re-check while holding the
                // same mutex used by the connectivity collector. If the
                // network became usable after the first decision but before
                // this publication, the second check consumes that transition
                // locally instead of relying on a callback that may already
                // have observed an empty pending set.
                val stillBlocked = stateMutex.withLock {
                    pending[key] = PendingPreparation(key, style, trigger, meteredConfirmed)
                    val currentDecision = OfflineRegionDownloadPolicy.decide(
                        network.current(),
                        downloadTrigger,
                        preferences(),
                        meteredConfirmed,
                    )
                    if (currentDecision == OfflineDownloadDecision.Allowed) {
                        pending.remove(key)
                        false
                    } else {
                        true
                    }
                }
                if (stillBlocked) {
                    // Let the package repository expose the waiting/approval
                    // state, but never activate a native map transfer first.
                    packages.requestDownload(packageState.region.regionId, downloadTrigger, meteredConfirmed)
                    return@supervisorScope
                }
            }
        }
        stateMutex.withLock { pending.remove(key) }
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
        stateMutex.withLock { definitions[key] = definition }
        val mapJob = launch { mapPacks.prepare(definition) }
        val navigationJob = launch {
            if (packageState.status != OfflineRegionPackageStatus.READY &&
                packageState.status != OfflineRegionPackageStatus.UPDATE_AVAILABLE
            ) {
                val shouldRequest = stateMutex.withLock {
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

    private suspend fun publish(packageStates: List<OfflineRegionPackageState>) {
        val next = packageStates.map { state ->
            val style = stateMutex.withLock { activeStyles[state.region.regionId] }
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

    private data class PendingPreparation(
        val key: PreparationKey,
        val style: OfflineMapStyleVariant,
        val trigger: OfflinePreparationTrigger,
        val meteredConfirmed: Boolean,
    )

    private fun mapKey(regionId: String, style: OfflineMapStyleVariant) = OfflineMapPackKey(
        regionId,
        style,
        "https://tiles.openfreemap.org/styles/${style.name.lowercase()}",
    )
}
