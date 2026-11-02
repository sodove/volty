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
    /** The theme the rider actually sees; Settings has no theme of its own and used to force BRIGHT. */
    @Volatile
    private var observedStyle: OfflineMapStyleVariant? = null
    private val _coverage = MutableStateFlow<OfflineCurrentCoverage>(OfflineCurrentCoverage.Unknown)
    override val currentCoverage: StateFlow<OfflineCurrentCoverage> = _coverage.asStateFlow()

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
        observedStyle = style
        val states = packages.states.value
        _coverage.value = OfflineCurrentCoveragePolicy.resolve(coordinate, states)
        val state = OfflineCurrentCoveragePolicy.cellFor(coordinate, states) ?: return
        // Retraction: an earlier revision prepared WHATEVER cell held the fix, which
        // asked the repository to build/download empty on-demand neighbours (silently
        // over Wi-Fi, with a prompt over mobile data).  Opening the map is not consent
        // to a 100-200 MB download; only an installed region gets its map pack here.
        if (!state.isInstalled) return
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
        // Only the theme in use is prepared: a Settings tap after the map showed DARK
        // must not also fetch a BRIGHT pack.
        val effectiveStyle = observedStyle ?: style
        packages.states.value.firstOrNull { it.region.regionId == regionId }
            ?.let { prepare(it, effectiveStyle, OfflinePreparationTrigger.SETTINGS, force = force, meteredConfirmed = meteredConfirmed) }
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
        // Retraction: the network gate used to run FIRST and, when it blocked, called
        // requestDownload unconditionally - even for an installed region - which
        // overwrote READY with AWAITING_METERED_APPROVAL and raised the "download the
        // map" dialog on mobile data.  "Is this region already here and done" is
        // decided before any network question, and routing/search readiness follows
        // the installed package, never the map theme.
        // Second retraction: gating on isInstalled alone made an explicit resume / retry /
        // update of an installed region with a FAILED, PAUSED or newer release do nothing.
        // The package request is skipped only when the installed release IS the published
        // one (nothing to fetch), or when the trigger is automatic (opening the map never
        // updates or repairs a package; that stays an explicit action).
        val installed = packageState.isInstalled
        val packageWork = !OfflineRegionRequestPolicy.isAlreadyCurrent(
            packageState.installedReleaseVersion,
            packageState.latestRelease?.releaseVersion,
        ) && trigger != OfflinePreparationTrigger.FIRST_MAP_OPEN
        if (!installed && trigger == OfflinePreparationTrigger.FIRST_MAP_OPEN) return@supervisorScope
        val metadata = packageState.region.mapPack
        val revision = metadata?.ofmStyleRevision
        if (installed && !packageWork && !force && store.completed(packageState.region.regionId, style, revision)) {
            stateMutex.withLock { pending.remove(key) }
            return@supervisorScope
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
                    // state for a region that is NOT on the device, but never
                    // activate a native map transfer first.  An installed region
                    // only waits for a usable network to fetch its map pack: no
                    // package request, no prompt.
                    if (packageWork) {
                        packages.requestDownload(packageState.region.regionId, downloadTrigger, meteredConfirmed)
                    }
                    return@supervisorScope
                }
            }
        }
        stateMutex.withLock { pending.remove(key) }
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
            if (packageWork) {
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
