package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

class DefaultOfflineRegionPreparationCoordinator(
    private val mapPacks: OfflineMapPackManager,
    private val packages: OfflineRegionPackageRepository,
    private val scope: CoroutineScope,
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
    private val mapStyle = MutableStateFlow(OfflineMapStyleVariant.BRIGHT)
    private val _states = MutableStateFlow<List<OfflineRegionPreparationState>>(emptyList())
    override val states: StateFlow<List<OfflineRegionPreparationState>> = _states.asStateFlow()
    private val _coverage = MutableStateFlow<OfflineCurrentCoverage>(OfflineCurrentCoverage.Unknown)
    override val currentCoverage: StateFlow<OfflineCurrentCoverage> = _coverage.asStateFlow()

    init {
        scope.launch {
            combine(packages.states, mapPacks.states, mapStyle) { _, _, _ -> Unit }
                .collect { publish() }
        }
        scope.launch {
            network?.changes?.collect { availability ->
                if (availability != OfflineNetworkAvailability.OFFLINE) retryPending()
            }
        }
    }

    override fun setMapStyle(style: OfflineMapStyleVariant) {
        mapStyle.value = style
    }

    override suspend fun prepareCurrentRegion(coordinate: GeoCoordinate, style: OfflineMapStyleVariant) {
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

    override suspend fun retry(regionId: String, style: OfflineMapStyleVariant) {
        val state = packages.states.value.firstOrNull { it.region.regionId == regionId } ?: return
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
        val job = stateMutex.withLock {
            if (key.regionId in pausedRegions && !force) return@withLock null
            // Being pending is the idempotent representation of an already
            // requested offline attempt. Only an explicit retry/recovery may
            // force a new keyed job; ordinary first-open effects must coalesce.
            val effectiveForce = force || retryFailed
            if (jobs[key]?.isCompleted == false || (!effectiveForce && key in requested)) return@withLock null
            if (effectiveForce) {
                pausedRegions.remove(key.regionId)
                navigationRequested.remove(key.regionId)
            }
            if (meteredConfirmed) {
                val outdated = pending.keys.filter { it.regionId == key.regionId && it != key }
                pending.keys.removeAll(outdated)
                requested.removeAll(outdated)
            }
            requested += key
            scope.launch(start = CoroutineStart.LAZY) {
                runPreparation(packageState, style, trigger, meteredConfirmed)
            }.also { jobs[key] = it }
        } ?: return
        job.start()
        job.join()
    }

    private suspend fun runPreparation(
        packageState: OfflineRegionPackageState,
        style: OfflineMapStyleVariant,
        trigger: OfflinePreparationTrigger,
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
        val packKey = mapKey(key.regionId, style)
        if (!packageWork && mapPacks.state(packKey) == OfflineMapPackState.Ready) {
            stateMutex.withLock { pending.remove(key) }
            return@supervisorScope
        }
        // An installed region already has the rider's consent. Repairing its tiles
        // may use mobile data, while a new or updated search/routing package still asks.
        val meteredAllowed = meteredConfirmed || (installed && !packageWork)
        if (network != null) {
            val initialDecision = OfflineRegionDownloadPolicy.decide(
                network.current(),
                downloadTrigger,
                preferences(),
                meteredAllowed,
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
                        meteredAllowed,
                    )
                    if (currentDecision == OfflineDownloadDecision.Allowed) {
                        pending.remove(key)
                        false
                    } else {
                        true
                    }
                }
                if (stillBlocked) {
                    // Publish package waiting/approval only when there is package work.
                    if (packageWork) {
                        packages.requestDownload(packageState.region.regionId, downloadTrigger, meteredConfirmed)
                    }
                    return@supervisorScope
                }
            }
        }
        stateMutex.withLock { pending.remove(key) }
        val metadata = packageState.region.mapPack
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
        publish()
    }

    private suspend fun publish() = stateMutex.withLock {
        val style = mapStyle.value
        // A persisted completion marker could survive a missing native pack.
        // Readiness now comes from MapLibre's status for the selected style.
        val packs = mapPacks.states.value
        _states.value = packages.states.value.map { state ->
            OfflineRegionPreparationState(
                regionId = state.region.regionId,
                mapStyle = style,
                map = packs[mapKey(state.region.regionId, style)] ?: OfflineMapPackState.Missing,
                search = state.status.toNavigationComponent(state, isSearch = true),
                routing = state.status.toNavigationComponent(state, isSearch = false),
            )
        }
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
        style.ofmStyleUrl,
    )
}
