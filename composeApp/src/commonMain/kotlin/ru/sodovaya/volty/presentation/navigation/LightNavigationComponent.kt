package ru.sodovaya.volty.presentation.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.location.RideLocationStatus
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ArrivalEnergyEstimator
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationEnergySource
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteProgressEngine
import ru.sodovaya.volty.domain.navigation.RouteProgressUpdate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface LightNavigationComponent {
    val state: StateFlow<LightNavigationState>
    val locationState: StateFlow<RideLocationState>
    val locationPermissions: List<String>

    fun onPlannerRequested()
    fun onQueryChanged(query: String)
    fun onPlaceSelected(place: PlaceCandidate)
    fun onAlternativeSelected(routeId: String)
    fun onStartNavigation()
    fun onRetry()
    fun onStopNavigation()
    fun onMapVisibilityChanged(visible: Boolean)
    fun onLocationPermissionResult(granted: Boolean)
    fun onCameraGesture(nowElapsedMillis: Long)
    fun onRecenterRequested()
    fun close()
}

@OptIn(ExperimentalTime::class)
class DefaultLightNavigationComponent(
    componentContext: ComponentContext,
    private val navigationRepository: NavigationRepository,
    private val locationRepository: RideLocationRepository,
    private val energySource: NavigationEnergySource? = null,
    private val languageTag: String = "ru-RU",
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val nowEpochMillis: () -> Long = {
        Clock.System.now().toEpochMilliseconds()
    },
) : LightNavigationComponent, ComponentContext by componentContext {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val _state = MutableStateFlow(LightNavigationState())
    override val state: StateFlow<LightNavigationState> = _state.asStateFlow()
    override val locationState: StateFlow<RideLocationState> = locationRepository.state
    override val locationPermissions: List<String> = locationRepository.requiredPermissions

    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var mapVisible = false
    private var mapDemandOwned = false
    private var navigationDemandOwned = false
    private var permissionDeniedOverride = false
    private var suppressRepositoryLocationStatus = false
    private var lifecycleStopped = false
    private var awaitingRouteOrigin = false
    private val progressEngine = RouteProgressEngine()
    private var rerouteJob: Job? = null
    private var lastRerouteEpisodeId: Long? = null
    private var closed = false

    init {
        lifecycle.doOnStart { onLifecycleStarted() }
        lifecycle.doOnStop { onLifecycleStopped() }
        lifecycle.doOnDestroy { close() }
        scope.launch {
            locationRepository.state.collect { locationState ->
                if (!closed && !suppressRepositoryLocationStatus) {
                    _state.update { current ->
                        current.copy(locationStatus = locationUiStatus(locationState))
                    }
                }
                if (!closed && !lifecycleStopped) {
                    processNavigationLocation(locationState)
                    processPendingRouteOrigin()
                }
            }
        }
        energySource?.let { source ->
            scope.launch {
                source.evidence.collect { evidence ->
                    refreshArrivalSoc(evidence)
                }
            }
        }
    }

    override fun onPlannerRequested() {
        if (closed) return
        suppressRepositoryLocationStatus = false
        if (_state.value.phase is NavigationPhase.Idle) {
            reduce(NavigationAction.PlannerRequested)
        }
        permissionDeniedOverride = false
        refreshLocationState()
    }

    override fun onQueryChanged(query: String) {
        if (closed) return
        searchJob?.cancel()
        routeJob?.cancel()
        rerouteJob?.cancel()
        progressEngine.reset(null)
        lastRerouteEpisodeId = null
        awaitingRouteOrigin = false
        releaseNavigationDemand()
        reduce(NavigationAction.QueryChanged(query))
        if (query.trim().length < LightNavigationSearchPolicy.MIN_QUERY_LENGTH) return
        scheduleSearch(query)
    }

    override fun onPlaceSelected(place: PlaceCandidate) {
        if (closed) return
        searchJob?.cancel()
        routeJob?.cancel()
        rerouteJob?.cancel()
        progressEngine.reset(null)
        lastRerouteEpisodeId = null
        awaitingRouteOrigin = false
        releaseNavigationDemand()
        reduce(NavigationAction.PlaceSelected(place))
    }

    override fun onAlternativeSelected(routeId: String) {
        if (closed) return
        val before = _state.value.phase
        reduce(NavigationAction.AlternativeSelected(routeId))
        val after = _state.value.phase
        if (before is NavigationPhase.Navigating &&
            after is NavigationPhase.Navigating &&
            before.selectedRouteId != after.selectedRouteId
        ) {
            progressEngine.reset(after.selectedRouteId)
            lastRerouteEpisodeId = null
        }
    }

    override fun onStartNavigation() {
        if (closed || lifecycleStopped) return
        val before = _state.value.phase
        reduce(NavigationAction.StartNavigation)
        if (before is NavigationPhase.RouteReady && _state.value.phase is NavigationPhase.Navigating) {
            progressEngine.reset(before.selectedRouteId)
            lastRerouteEpisodeId = null
            ensureNavigationDemand()
        }
    }

    override fun onRetry() {
        if (closed) return
        if (lifecycleStopped) return
        when (val phase = _state.value.phase) {
            is NavigationPhase.Rerouting -> retryRerouteManually(phase)
            is NavigationPhase.Planning -> {
                if (phase.requestInFlight) return
                if (phase.destination != null) {
                    requestRouteIfPossible()
                } else if (phase.query.trim().length >= LightNavigationSearchPolicy.MIN_QUERY_LENGTH) {
                    searchJob?.cancel()
                    scheduleSearch(phase.query)
                }
            }
            else -> Unit
        }
    }

    override fun onStopNavigation() {
        if (closed) return
        searchJob?.cancel()
        routeJob?.cancel()
        rerouteJob?.cancel()
        progressEngine.reset(null)
        lastRerouteEpisodeId = null
        awaitingRouteOrigin = false
        suppressRepositoryLocationStatus = true
        reduce(NavigationAction.StopNavigation)
        releaseLocationDemands()
    }

    override fun onMapVisibilityChanged(visible: Boolean) {
        if (closed) return
        mapVisible = visible
        if (!visible) {
            releaseMapDemand()
        } else {
            if (lifecycleStopped) return
            suppressRepositoryLocationStatus = false
            ensureMapDemand()
        }
    }

    override fun onLocationPermissionResult(granted: Boolean) {
        if (closed) return
        suppressRepositoryLocationStatus = false
        permissionDeniedOverride = !granted
        if (!granted) {
            reduce(NavigationAction.LocationStatusChanged(LocationUiStatus.PERMISSION_DENIED))
            releaseLocationDemands()
            return
        }
        refreshLocationState()
        if (mapVisible && !lifecycleStopped) ensureMapDemand()
    }

    override fun onCameraGesture(nowElapsedMillis: Long) {
        if (closed) return
        reduce(NavigationAction.CameraGesture(nowElapsedMillis))
    }

    override fun onRecenterRequested() {
        if (closed || lifecycleStopped) return
        suppressRepositoryLocationStatus = false
        reduce(NavigationAction.RecenterRequested)
        refreshLocationState()
        if (mapVisible) ensureMapDemand()
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycleStopped = true
        searchJob?.cancel()
        routeJob?.cancel()
        rerouteJob?.cancel()
        progressEngine.reset(null)
        lastRerouteEpisodeId = null
        awaitingRouteOrigin = false
        reduce(NavigationAction.StopNavigation)

        val releaseMap = mapDemandOwned
        val releaseNavigation = navigationDemandOwned
        mapDemandOwned = false
        navigationDemandOwned = false
        scope.launch {
            if (releaseNavigation) runCatching { locationRepository.setDemand(LocationConsumer.NAVIGATION, false) }
            if (releaseMap) runCatching { locationRepository.setDemand(LocationConsumer.MAP, false) }
            scope.cancel()
        }
    }

    private fun scheduleSearch(query: String) {
        val requestGeneration = _state.value.requestGeneration
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            if (!isCurrentPlanning(requestGeneration, query)) return@launch
            reduce(NavigationAction.SearchStarted(requestGeneration))
            val result = try {
                navigationRepository.search(
                    query = query,
                    near = currentCoordinate(),
                    languageTag = languageTag,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                NavigationResult.Failure(NavigationFailure.Offline)
            }
            if (!isCurrentPlanning(requestGeneration, query)) return@launch
            when (result) {
                is NavigationResult.Success -> reduce(
                    NavigationAction.SearchLoaded(requestGeneration, result.value),
                )
                is NavigationResult.Failure -> reduce(
                    NavigationAction.SearchFailed(requestGeneration, result.reason),
                )
            }
        }
    }

    private fun requestRouteIfPossible() {
        if (closed || lifecycleStopped) return
        val phase = _state.value.phase as? NavigationPhase.Planning ?: return
        val destination = phase.destination ?: return
        if (phase.requestInFlight) return
        val origin = routeOrigin()
        if (origin == null) {
            awaitingRouteOrigin = true
            reduce(NavigationAction.LocationStatusChanged(locationUiStatus(locationRepository.state.value)))
            ensureNavigationDemand()
            refreshLocationState()
            return
        }
        awaitingRouteOrigin = false
        val requestGeneration = _state.value.requestGeneration
        reduce(NavigationAction.RouteRequestStarted(requestGeneration))
        if (!(_state.value.phase as? NavigationPhase.Planning)?.requestInFlight.orFalse()) return

        routeJob?.cancel()
        routeJob = scope.launch {
            ensureNavigationDemand()
            if (_state.value.requestGeneration != requestGeneration || closed || lifecycleStopped) return@launch
            val result = try {
                navigationRepository.routes(
                    RouteRequest(
                        origin = origin.coordinate,
                        destination = destination,
                        languageTag = languageTag,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                NavigationResult.Failure(NavigationFailure.Offline)
            }
            if (_state.value.requestGeneration != requestGeneration || closed || lifecycleStopped) return@launch
            when (result) {
                is NavigationResult.Success -> reduce(
                    NavigationAction.RouteLoaded(
                        plan = result.value,
                        requestGeneration = requestGeneration,
                    ),
                )
                is NavigationResult.Failure -> {
                    reduce(NavigationAction.RouteFailed(result.reason, requestGeneration))
                    releaseNavigationDemand()
                }
            }
            if (result is NavigationResult.Success) {
                progressEngine.reset(null)
                lastRerouteEpisodeId = null
            }
        }
    }

    /**
     * A tap on Build route may legitimately happen before Android delivers its
     * first fix. Retry that one pending request when a usable fix arrives; a
     * provider failure remains an explicit retry so a flaky public service does
     * not get hammered by every location callback.
     */
    private fun processPendingRouteOrigin() {
        if (!awaitingRouteOrigin) return
        val phase = _state.value.phase as? NavigationPhase.Planning ?: return
        if (phase.destination == null || phase.requestInFlight || routeOrigin() == null) return
        awaitingRouteOrigin = false
        requestRouteIfPossible()
    }

    private fun processNavigationLocation(locationState: RideLocationState) {
        val phase = _state.value.phase as? NavigationPhase.Navigating ?: return
        val route = phase.plan.alternatives.firstOrNull { it.id == phase.selectedRouteId } ?: return
        val fix = (locationState.status as? RideLocationStatus.Available)?.fix
        when (val update = progressEngine.update(route, fix, nowEpochMillis())) {
            is RouteProgressUpdate.Unavailable -> reduce(NavigationAction.GuidanceCleared)
            is RouteProgressUpdate.OnRoute -> {
                reduce(NavigationAction.GuidanceUpdated(update.guidance))
                refreshArrivalSoc()
            }
            is RouteProgressUpdate.OffRouteCandidate -> Unit
            is RouteProgressUpdate.OffRouteConfirmed -> startAutomaticReroute(
                plan = phase.plan,
                fix = fix,
                episodeId = update.episodeId,
            )
            RouteProgressUpdate.Arrived -> {
                reduce(NavigationAction.Arrived)
                refreshArrivalSoc()
                releaseNavigationDemand()
            }
        }
    }

    private fun startAutomaticReroute(
        plan: RoutePlan,
        fix: RideLocationFix?,
        episodeId: Long,
    ) {
        if (closed || lifecycleStopped || fix == null) return
        if (lastRerouteEpisodeId == episodeId || rerouteJob?.isActive == true) return
        val phase = _state.value.phase as? NavigationPhase.Navigating ?: return
        if (phase.plan != plan) return

        lastRerouteEpisodeId = episodeId
        rerouteJob?.cancel()
        reduce(NavigationAction.BeginRerouting(attempt = 1))
        val requestGeneration = _state.value.requestGeneration
        rerouteJob = scope.launch {
            runReroute(
                plan = plan,
                requestGeneration = requestGeneration,
                initialOrigin = fix.coordinate,
                automaticRetries = true,
            )
        }
    }

    private fun retryRerouteManually(phase: NavigationPhase.Rerouting) {
        val origin = freshOrigin() ?: return
        rerouteJob?.cancel()
        val requestGeneration = _state.value.requestGeneration
        reduce(
            NavigationAction.RerouteStarted(
                requestGeneration = requestGeneration,
                attempt = phase.attempt + 1,
            ),
        )
        rerouteJob = scope.launch {
            runReroute(
                plan = phase.plan,
                requestGeneration = requestGeneration,
                initialOrigin = origin.coordinate,
                automaticRetries = false,
            )
        }
    }

    private suspend fun runReroute(
        plan: RoutePlan,
        requestGeneration: Long,
        initialOrigin: GeoCoordinate,
        automaticRetries: Boolean,
    ) {
        var retryNumber = 0
        var retryAfterMillis = 0L
        while (true) {
            if (!isCurrentRerouting(requestGeneration)) return
            if (retryNumber > 0) {
                val baseDelayMillis = if (retryNumber == 1) FIRST_REROUTE_RETRY_MILLIS else SECOND_REROUTE_RETRY_MILLIS
                delay(max(baseDelayMillis, retryAfterMillis))
                if (!isCurrentRerouting(requestGeneration)) return
            }

            val origin = if (retryNumber == 0) {
                initialOrigin
            } else {
                freshOrigin()?.coordinate ?: return
            }
            if (retryNumber > 0) {
                reduce(
                    NavigationAction.RerouteStarted(
                        requestGeneration = requestGeneration,
                        attempt = retryNumber + 1,
                    ),
                )
            }
            val result = try {
                navigationRepository.routes(
                    RouteRequest(
                        origin = origin,
                        destination = plan.destination,
                        languageTag = languageTag,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                NavigationResult.Failure(NavigationFailure.Offline)
            }
            if (!isCurrentRerouting(requestGeneration)) return
            when (result) {
                is NavigationResult.Success -> {
                    reduce(NavigationAction.RerouteLoaded(result.value, requestGeneration))
                    progressEngine.reset(selectedRouteIdOrNull())
                    lastRerouteEpisodeId = null
                    return
                }
                is NavigationResult.Failure -> {
                    reduce(NavigationAction.RerouteFailed(result.reason, requestGeneration))
                    if (!automaticRetries || retryNumber >= MAX_AUTOMATIC_REROUTE_RETRIES) return
                    retryNumber += 1
                    retryAfterMillis = retryAfterMillis(result.reason)
                }
            }
        }
    }

    private fun isCurrentRerouting(requestGeneration: Long): Boolean =
        !closed &&
            !lifecycleStopped &&
            _state.value.requestGeneration == requestGeneration &&
            _state.value.phase is NavigationPhase.Rerouting

    private fun selectedRouteIdOrNull(): String? = when (val phase = _state.value.phase) {
        is NavigationPhase.Navigating -> phase.selectedRouteId
        is NavigationPhase.Arrived -> phase.selectedRouteId
        is NavigationPhase.RouteReady -> phase.selectedRouteId
        is NavigationPhase.Rerouting -> phase.selectedRouteId
        NavigationPhase.Idle -> null
        is NavigationPhase.Planning -> null
    }

    private fun retryAfterMillis(failure: NavigationFailure): Long = when (failure) {
        is NavigationFailure.RateLimited -> failure.retryAfterSeconds
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / MILLIS_PER_SECOND) * MILLIS_PER_SECOND
        else -> 0L
    }

    private fun isCurrentPlanning(requestGeneration: Long, query: String): Boolean {
        val phase = _state.value.phase as? NavigationPhase.Planning ?: return false
        return _state.value.requestGeneration == requestGeneration && phase.query == query && !closed
    }

    private fun reduce(action: NavigationAction) {
        _state.update { current -> NavigationReducer.reduce(current, action) }
    }

    private fun refreshArrivalSoc(evidence: ru.sodovaya.volty.domain.navigation.NavigationEnergyEvidence? = energySource?.evidence?.value) {
        if (evidence == null) return
        val remainingDistanceMeters = when (val phase = _state.value.phase) {
            is NavigationPhase.Navigating -> phase.guidance?.remainingDistanceMeters
                ?: phase.plan.alternatives.firstOrNull { it.id == phase.selectedRouteId }?.distanceMeters
            is NavigationPhase.Arrived -> 0.0
            else -> null
        }
        val estimate = ArrivalEnergyEstimator.estimate(
            evidence = evidence,
            remainingDistanceMeters = remainingDistanceMeters,
            nowEpochMillis = nowEpochMillis(),
        )
        reduce(NavigationAction.ArrivalSocChanged(estimate))
    }

    private fun refreshLocationState() {
        scope.launch {
            runCatching { locationRepository.refreshPermissionAndProviders() }
        }
    }

    private fun currentCoordinate(): GeoCoordinate? =
        (locationRepository.state.value.status as? RideLocationStatus.Available)?.fix?.coordinate

    private fun freshOrigin(): RideLocationFix? {
        val fix = (locationRepository.state.value.status as? RideLocationStatus.Available)?.fix ?: return null
        val ageMillis = nowEpochMillis() - fix.capturedAtEpochMillis
        if (ageMillis !in 0L..MAX_LOCATION_AGE_MILLIS) return null
        if (fix.accuracyMeters > MAX_LOCATION_ACCURACY_METERS) return null
        return fix
    }

    private fun routeOrigin(): RideLocationFix? {
        val fix = (locationRepository.state.value.status as? RideLocationStatus.Available)?.fix ?: return null
        if (fix.accuracyMeters > MAX_LOCATION_ACCURACY_METERS) return null
        return fix
    }

    private fun ensureMapDemand() {
        if (closed || lifecycleStopped || mapDemandOwned || !mapVisible || permissionDeniedOverride) return
        mapDemandOwned = true
        scope.launch {
            runCatching { locationRepository.setDemand(LocationConsumer.MAP, true) }
        }
    }

    private fun ensureNavigationDemand() {
        if (closed || lifecycleStopped || navigationDemandOwned) return
        navigationDemandOwned = true
        scope.launch {
            runCatching { locationRepository.setDemand(LocationConsumer.NAVIGATION, true) }
        }
    }

    private fun releaseMapDemand() {
        if (!mapDemandOwned) return
        mapDemandOwned = false
        scope.launch {
            runCatching { locationRepository.setDemand(LocationConsumer.MAP, false) }
        }
    }

    private fun releaseNavigationDemand() {
        if (!navigationDemandOwned) return
        navigationDemandOwned = false
        scope.launch {
            runCatching { locationRepository.setDemand(LocationConsumer.NAVIGATION, false) }
        }
    }

    private fun releaseLocationDemands() {
        releaseMapDemand()
        releaseNavigationDemand()
    }

    private fun onLifecycleStopped() {
        if (closed || lifecycleStopped) return
        lifecycleStopped = true
        suppressRepositoryLocationStatus = true
        searchJob?.cancel()
        routeJob?.cancel()
        rerouteJob?.cancel()
        progressEngine.reset(selectedRouteIdOrNull())
        lastRerouteEpisodeId = null
        reduce(NavigationAction.RequestsCancelled)
        reduce(NavigationAction.LifecycleLocationUnavailable)
        releaseLocationDemands()
    }

    private fun onLifecycleStarted() {
        if (closed) return
        lifecycleStopped = false
        suppressRepositoryLocationStatus = false
        refreshLocationState()
        if (mapVisible) ensureMapDemand()
        when (_state.value.phase) {
            is NavigationPhase.Navigating,
            is NavigationPhase.Rerouting -> ensureNavigationDemand()
            else -> Unit
        }
    }

    private fun locationUiStatus(state: RideLocationState): LocationUiStatus {
        if (permissionDeniedOverride) return LocationUiStatus.PERMISSION_DENIED
        return when (val status = state.status) {
            RideLocationStatus.NotRequested -> LocationUiStatus.NOT_REQUESTED
            RideLocationStatus.PermissionRequired -> LocationUiStatus.PERMISSION_REQUIRED
            RideLocationStatus.PermissionDenied -> LocationUiStatus.PERMISSION_DENIED
            RideLocationStatus.ProviderDisabled -> LocationUiStatus.PROVIDER_DISABLED
            RideLocationStatus.Searching -> LocationUiStatus.SEARCHING
            is RideLocationStatus.Available -> {
                val ageMillis = nowEpochMillis() - status.fix.capturedAtEpochMillis
                when {
                    ageMillis !in 0L..MAX_LOCATION_AGE_MILLIS -> LocationUiStatus.STALE
                    status.fix.accuracyMeters > MAX_LOCATION_ACCURACY_METERS -> LocationUiStatus.POOR_ACCURACY
                    else -> LocationUiStatus.FRESH
                }
            }
        }
    }

    private fun Boolean?.orFalse(): Boolean = this == true

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 350L
        const val MAX_LOCATION_AGE_MILLIS = 5_000L
        const val MAX_LOCATION_ACCURACY_METERS = 50.0
        const val FIRST_REROUTE_RETRY_MILLIS = 2_000L
        const val SECOND_REROUTE_RETRY_MILLIS = 5_000L
        const val MAX_AUTOMATIC_REROUTE_RETRIES = 2
        const val MILLIS_PER_SECOND = 1_000L
    }
}
