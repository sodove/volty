package ru.sodovaya.volty.presentation.navigation

import com.arkivanov.decompose.ComponentContext
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
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteProfile
import ru.sodovaya.volty.domain.navigation.RouteRequest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface LightNavigationComponent {
    val state: StateFlow<LightNavigationState>

    fun onPlannerRequested()
    fun onQueryChanged(query: String)
    fun onPlaceSelected(place: PlaceCandidate)
    fun onProfileSelected(profile: RouteProfile)
    fun onProfileConfirmed()
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
    private val languageTag: String = "ru-RU",
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val nowEpochMillis: () -> Long = {
        Clock.System.now().toEpochMilliseconds()
    },
) : LightNavigationComponent, ComponentContext by componentContext {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val _state = MutableStateFlow(LightNavigationState())
    override val state: StateFlow<LightNavigationState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var mapVisible = false
    private var mapDemandOwned = false
    private var navigationDemandOwned = false
    private var permissionDeniedOverride = false
    private var permissionGrantedOverride = false
    private var suppressRepositoryLocationStatus = false
    private var closed = false

    init {
        lifecycle.doOnDestroy { close() }
        scope.launch {
            locationRepository.state.collect { locationState ->
                if (!closed && !suppressRepositoryLocationStatus) {
                    _state.update { current ->
                        current.copy(locationStatus = locationUiStatus(locationState))
                    }
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
        permissionGrantedOverride = false
        refreshLocationState()
    }

    override fun onQueryChanged(query: String) {
        if (closed) return
        searchJob?.cancel()
        routeJob?.cancel()
        reduce(NavigationAction.QueryChanged(query))
        if (query.trim().length < MIN_SEARCH_LENGTH) return
        scheduleSearch(query)
    }

    override fun onPlaceSelected(place: PlaceCandidate) {
        if (closed) return
        searchJob?.cancel()
        routeJob?.cancel()
        reduce(NavigationAction.PlaceSelected(place))
    }

    override fun onProfileSelected(profile: RouteProfile) {
        if (closed) return
        routeJob?.cancel()
        reduce(NavigationAction.ProfileSelected(profile))
    }

    override fun onProfileConfirmed() {
        if (closed) return
        reduce(NavigationAction.ProfileConfirmed)
        requestRouteIfPossible()
    }

    override fun onAlternativeSelected(routeId: String) {
        if (closed) return
        reduce(NavigationAction.AlternativeSelected(routeId))
    }

    override fun onStartNavigation() {
        if (closed) return
        val before = _state.value.phase
        reduce(NavigationAction.StartNavigation)
        if (before is NavigationPhase.RouteReady && _state.value.phase is NavigationPhase.Navigating) {
            ensureNavigationDemand()
        }
    }

    override fun onRetry() {
        if (closed) return
        val phase = _state.value.phase as? NavigationPhase.Planning ?: return
        if (phase.requestInFlight) return
        if (phase.destination != null && phase.profile != null && phase.profileConfirmed) {
            requestRouteIfPossible()
        } else if (phase.query.trim().length >= MIN_SEARCH_LENGTH) {
            searchJob?.cancel()
            scheduleSearch(phase.query)
        }
    }

    override fun onStopNavigation() {
        if (closed) return
        searchJob?.cancel()
        routeJob?.cancel()
        suppressRepositoryLocationStatus = true
        reduce(NavigationAction.StopNavigation)
        releaseLocationDemands()
    }

    override fun onMapVisibilityChanged(visible: Boolean) {
        if (closed) return
        mapVisible = visible
        if (!visible) {
            releaseMapDemand()
        } else if (canUseLocation()) {
            suppressRepositoryLocationStatus = false
            ensureMapDemand()
        } else {
            refreshLocationState()
        }
    }

    override fun onLocationPermissionResult(granted: Boolean) {
        if (closed) return
        suppressRepositoryLocationStatus = false
        permissionGrantedOverride = granted
        permissionDeniedOverride = !granted
        if (!granted) {
            reduce(NavigationAction.LocationStatusChanged(LocationUiStatus.PERMISSION_DENIED))
            releaseLocationDemands()
            return
        }
        refreshLocationState()
        if (mapVisible) ensureMapDemand()
    }

    override fun onCameraGesture(nowElapsedMillis: Long) {
        if (closed) return
        reduce(NavigationAction.CameraGesture(nowElapsedMillis))
    }

    override fun onRecenterRequested() {
        if (closed) return
        suppressRepositoryLocationStatus = false
        reduce(NavigationAction.RecenterRequested)
        refreshLocationState()
        if (mapVisible && canUseLocation()) ensureMapDemand()
    }

    override fun close() {
        if (closed) return
        closed = true
        searchJob?.cancel()
        routeJob?.cancel()
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
        val phase = _state.value.phase as? NavigationPhase.Planning ?: return
        if (!phase.profileConfirmed || phase.destination == null || phase.profile == null) return
        if (phase.requestInFlight) return
        val origin = freshOrigin() ?: return
        val requestGeneration = _state.value.requestGeneration
        reduce(NavigationAction.RouteRequestStarted(requestGeneration))
        if (!(_state.value.phase as? NavigationPhase.Planning)?.requestInFlight.orFalse()) return

        routeJob?.cancel()
        routeJob = scope.launch {
            ensureNavigationDemand()
            if (_state.value.requestGeneration != requestGeneration || closed) return@launch
            val result = try {
                navigationRepository.routes(
                    RouteRequest(
                        origin = origin.coordinate,
                        destination = phase.destination,
                        profile = phase.profile,
                        languageTag = languageTag,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                NavigationResult.Failure(NavigationFailure.Offline)
            }
            if (_state.value.requestGeneration != requestGeneration || closed) return@launch
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
        }
    }

    private fun isCurrentPlanning(requestGeneration: Long, query: String): Boolean {
        val phase = _state.value.phase as? NavigationPhase.Planning ?: return false
        return _state.value.requestGeneration == requestGeneration && phase.query == query && !closed
    }

    private fun reduce(action: NavigationAction) {
        _state.update { current -> NavigationReducer.reduce(current, action) }
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

    private fun canUseLocation(): Boolean {
        if (permissionDeniedOverride) return false
        val status = locationRepository.state.value.status
        return permissionGrantedOverride || status is RideLocationStatus.Available
    }

    private fun ensureMapDemand() {
        if (mapDemandOwned || !mapVisible || !canUseLocation()) return
        mapDemandOwned = true
        scope.launch {
            runCatching { locationRepository.setDemand(LocationConsumer.MAP, true) }
        }
    }

    private fun ensureNavigationDemand() {
        if (navigationDemandOwned) return
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
        const val MIN_SEARCH_LENGTH = 3
        const val SEARCH_DEBOUNCE_MILLIS = 350L
        const val MAX_LOCATION_AGE_MILLIS = 5_000L
        const val MAX_LOCATION_ACCURACY_METERS = 50.0
    }
}
