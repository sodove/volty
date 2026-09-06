package ru.sodovaya.volty.presentation.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle
import ru.sodovaya.volty.domain.navigation.routing.NavigationPreferencesStore

@OptIn(ExperimentalCoroutinesApi::class)
class LightNavigationComponentPlanningTest {
    private val dispatcher = StandardTestDispatcher()

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `two character query is debounced once`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository()
        val component = component(navigation)

        component.onPlannerRequested()
        component.onQueryChanged("ab")
        advanceTimeBy(349L)
        assertTrue(navigation.searches.isEmpty())

        advanceTimeBy(1L)
        advanceUntilIdle()

        assertEquals(listOf("ab"), navigation.searches)
        assertEquals(listOf(place), assertIs<NavigationPhase.Planning>(component.state.value.phase).searchResults)
        component.close()
    }

    @Test
    fun `newer query fences an older response`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository()
        val oldResponse = CompletableDeferred<NavigationResult<List<PlaceCandidate>>>()
        val newResponse = CompletableDeferred<NavigationResult<List<PlaceCandidate>>>()
        navigation.searchResponses.addLast(oldResponse)
        navigation.searchResponses.addLast(newResponse)
        val component = component(navigation)

        component.onPlannerRequested()
        component.onQueryChanged("old")
        advanceTimeBy(350L)
        runCurrent()
        assertEquals(listOf("old"), navigation.searches)

        component.onQueryChanged("new")
        advanceTimeBy(350L)
        runCurrent()
        oldResponse.complete(NavigationResult.Success(listOf(place)))
        newResponse.complete(NavigationResult.Success(listOf(secondPlace)))
        advanceUntilIdle()

        val planning = assertIs<NavigationPhase.Planning>(component.state.value.phase)
        assertEquals("new", planning.query)
        assertEquals(listOf(secondPlace), planning.searchResults)
        component.close()
    }

    @Test
    fun `route request uses the last known origin when it is stale`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository()
        val location = FakeLocationRepository(
            RideLocationState(
                status = RideLocationStatus.Available(fix(capturedAt = 1_000L, accuracy = 10.0)),
            ),
        )
        val component = component(navigation, location, now = 10_000L)

        component.onPlannerRequested()
        component.onPlaceSelected(place)
        component.onRetry()
        advanceUntilIdle()
        assertEquals(1, navigation.routeRequests.size)
        assertEquals(GeoCoordinate(56.83, 60.60), navigation.routeRequests.single().origin)
        assertEquals(LocationUiStatus.STALE, component.state.value.locationStatus)
        assertEquals(listOf(LocationConsumer.NAVIGATION), location.demands)
        component.onStopNavigation()
        advanceUntilIdle()
        assertTrue(location.demands.isEmpty())
        assertEquals(LocationUiStatus.NOT_REQUESTED, component.state.value.locationStatus)
        component.close()
    }

    @Test
    fun `route request carries the selected profile and speed`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository()
        val location = FakeLocationRepository(
            RideLocationState(
                status = RideLocationStatus.Available(fix(capturedAt = 9_000L, accuracy = 10.0)),
            ),
        )
        val component = component(navigation, location)

        component.onPlannerRequested()
        component.onRouteStyleChanged(RouteStyle.CURVY)
        component.onTopSpeedChanged(120)
        component.onPlaceSelected(place)
        component.onRetry()
        advanceUntilIdle()

        val request = navigation.routeRequests.single()
        assertEquals(RouteStyle.CURVY, request.style)
        assertEquals(120, request.preferences.declaredTopSpeedKph)
        component.close()
    }

    @Test
    fun `navigation restores the last route style and speed from persistent preferences`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val navigation = FakeNavigationRepository()
            val preferences = FakeNavigationPreferencesStore(
                initial = mapOf(
                    "vehicle-a" to NavigationPreferenceValues(
                        style = RouteStyle.CURVY,
                        speedKph = 80,
                    ),
                ),
            )
            val component = component(
                navigation,
                appPreferences = preferences,
                activeVehicleId = MutableStateFlow("vehicle-a"),
            )

            advanceUntilIdle()

            assertEquals(RouteStyle.CURVY, component.state.value.routeStyle)
            assertEquals(80, component.state.value.routingPreferences.declaredTopSpeedKph)
            component.close()
        }

    @Test
    fun `navigation persists route style and speed changes`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository()
        val preferences = FakeNavigationPreferencesStore()
        val component = component(
            navigation,
            appPreferences = preferences,
            activeVehicleId = MutableStateFlow("vehicle-a"),
        )

        component.onPlannerRequested()
        component.onRouteStyleChanged(RouteStyle.MAX_CURVY_TOURING)
        component.onTopSpeedChanged(35)
        advanceUntilIdle()

        assertEquals(RouteStyle.MAX_CURVY_TOURING, preferences.savedStyle("vehicle-a"))
        assertEquals(35, preferences.savedSpeed("vehicle-a"))
        component.close()
    }

    @Test
    fun `switching the active vehicle restores that vehicle's navigation options`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val navigation = FakeNavigationRepository()
            val activeVehicleId = MutableStateFlow<String?>("vehicle-a")
            val preferences = FakeNavigationPreferencesStore(
                initial = mapOf(
                    "vehicle-a" to NavigationPreferenceValues(RouteStyle.CURVY, 80),
                    "vehicle-b" to NavigationPreferenceValues(RouteStyle.FAST_WITHOUT_HIGHWAYS, 30),
                ),
            )
            val component = component(
                navigation,
                appPreferences = preferences,
                activeVehicleId = activeVehicleId,
            )

            advanceUntilIdle()
            assertEquals(RouteStyle.CURVY, component.state.value.routeStyle)
            assertEquals(80, component.state.value.routingPreferences.declaredTopSpeedKph)

            activeVehicleId.value = "vehicle-b"
            advanceUntilIdle()

            assertEquals(RouteStyle.FAST_WITHOUT_HIGHWAYS, component.state.value.routeStyle)
            assertEquals(30, component.state.value.routingPreferences.declaredTopSpeedKph)
            component.close()
        }

    @Test
    fun `route request started before the first fix retries once when a fix appears`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository()
        val location = FakeLocationRepository()
        val component = component(navigation, location)

        component.onPlannerRequested()
        component.onPlaceSelected(place)
        component.onRetry()
        advanceUntilIdle()
        assertTrue(navigation.routeRequests.isEmpty())

        location.state.value = RideLocationState(
            status = RideLocationStatus.Available(fix(capturedAt = 9_900L, accuracy = 10.0)),
        )
        advanceUntilIdle()

        assertEquals(1, navigation.routeRequests.size)
        assertIs<NavigationPhase.RouteReady>(component.state.value.phase)
        component.close()
    }

    @Test
    fun `map visibility starts location demand and denied permission keeps search usable`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            val navigation = FakeNavigationRepository()
            val location = FakeLocationRepository()
            val component = component(navigation, location)

            component.onPlannerRequested()
            component.onMapVisibilityChanged(true)
            advanceUntilIdle()
            assertEquals(listOf(LocationConsumer.MAP), location.demands)

            component.onLocationPermissionResult(false)
            assertEquals(LocationUiStatus.PERMISSION_DENIED, component.state.value.locationStatus)
            advanceUntilIdle()
            assertTrue(location.demands.isEmpty())
            component.onQueryChanged("дом")
            advanceTimeBy(350L)
            advanceUntilIdle()
            assertEquals(listOf("дом"), navigation.searches)

            component.onLocationPermissionResult(true)
            advanceUntilIdle()
            assertEquals(listOf(LocationConsumer.MAP), location.demands)
            component.close()
        }

    @Test
    fun `stop ignores an in-flight response from the old generation`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository()
        val response = CompletableDeferred<NavigationResult<List<PlaceCandidate>>>()
        navigation.searchResponses.addLast(response)
        val component = component(navigation)

        component.onPlannerRequested()
        component.onQueryChanged("old")
        advanceTimeBy(350L)
        runCurrent()
        component.onStopNavigation()
        response.complete(NavigationResult.Success(listOf(place)))
        advanceUntilIdle()

        assertIs<NavigationPhase.Idle>(component.state.value.phase)
        assertTrue(assertIs<NavigationPhase.Idle>(component.state.value.phase) == NavigationPhase.Idle)
        component.close()
    }

    @Test
    fun `rate limit is surfaced and retry is explicit`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val navigation = FakeNavigationRepository(
            routeResponses = ArrayDeque(
                listOf(
                    NavigationResult.Failure(NavigationFailure.RateLimited(12L)),
                    NavigationResult.Success(fakePlan()),
                ),
            ),
        )
        val location = FakeLocationRepository(
            RideLocationState(
                status = RideLocationStatus.Available(fix(capturedAt = 9_000L, accuracy = 10.0)),
            ),
        )
        val component = component(navigation, location, now = 10_000L)
        component.onPlannerRequested()
        component.onPlaceSelected(place)
        component.onRetry()
        advanceUntilIdle()

        val failed = assertIs<NavigationPhase.Planning>(component.state.value.phase)
        assertEquals(NavigationFailure.RateLimited(12L), failed.failure)
        assertEquals(1, navigation.routeRequests.size)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, navigation.routeRequests.size)
        component.onRetry()
        advanceUntilIdle()
        assertEquals(2, navigation.routeRequests.size)
        assertIs<NavigationPhase.RouteReady>(component.state.value.phase)
        component.close()
    }

    private fun component(
        navigation: FakeNavigationRepository,
        location: FakeLocationRepository = FakeLocationRepository(),
        now: Long = 10_000L,
        appPreferences: NavigationPreferencesStore? = null,
        activeVehicleId: MutableStateFlow<String?>? = null,
    ) = DefaultLightNavigationComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        navigationRepository = navigation,
        locationRepository = location,
        navigationPreferences = appPreferences,
        activeVehicleId = activeVehicleId,
        dispatcher = dispatcher,
        nowEpochMillis = { now },
    )

    private class FakeNavigationPreferencesStore(
        initial: Map<String?, NavigationPreferenceValues> = emptyMap(),
    ) : NavigationPreferencesStore {
        private val styles = initial.mapValuesTo(mutableMapOf()) { MutableStateFlow(it.value.style) }
        private val speeds = initial.mapValuesTo(mutableMapOf()) { MutableStateFlow(it.value.speedKph) }

        override fun routeStyleFor(vehicleId: String?): Flow<RouteStyle> =
            styles.getOrPut(vehicleId) { MutableStateFlow(RouteStyle.FAST_WITH_HIGHWAYS) }

        override fun topSpeedKphFor(vehicleId: String?): Flow<Int> =
            speeds.getOrPut(vehicleId) { MutableStateFlow(50) }

        override suspend fun setRouteStyle(vehicleId: String?, style: RouteStyle) {
            (routeStyleFor(vehicleId) as MutableStateFlow).value = style
        }

        override suspend fun setTopSpeedKph(vehicleId: String?, speedKph: Int) {
            (topSpeedKphFor(vehicleId) as MutableStateFlow).value = speedKph
        }

        fun savedStyle(vehicleId: String?): RouteStyle =
            (routeStyleFor(vehicleId) as StateFlow<RouteStyle>).value

        fun savedSpeed(vehicleId: String?): Int =
            (topSpeedKphFor(vehicleId) as StateFlow<Int>).value
    }

    private data class NavigationPreferenceValues(
        val style: RouteStyle,
        val speedKph: Int,
    )

    private class FakeNavigationRepository(
        private val routeResponses: ArrayDeque<NavigationResult<RoutePlan>> = ArrayDeque(),
    ) : NavigationRepository {
        val searches = mutableListOf<String>()
        val routeRequests = mutableListOf<ru.sodovaya.volty.domain.navigation.RouteRequest>()
        val searchResponses = ArrayDeque<CompletableDeferred<NavigationResult<List<PlaceCandidate>>>>()

        override suspend fun search(
            query: String,
            near: GeoCoordinate?,
            languageTag: String,
        ): NavigationResult<List<PlaceCandidate>> {
            searches += query
            return if (searchResponses.isEmpty()) {
                NavigationResult.Success(listOf(place))
            } else {
                searchResponses.removeFirst().await()
            }
        }

        override suspend fun routes(
            request: ru.sodovaya.volty.domain.navigation.RouteRequest,
        ): NavigationResult<RoutePlan> {
            routeRequests += request
            return if (routeResponses.isEmpty()) {
                NavigationResult.Success(fakePlan())
            } else {
                routeResponses.removeFirst()
            }
        }
    }

    private class FakeLocationRepository(
        initial: RideLocationState = RideLocationState(),
    ) : RideLocationRepository {
        override val requiredPermissions: List<String> = emptyList()
        override val state = MutableStateFlow(initial)
        val demands = mutableListOf<LocationConsumer>()
        var refreshCount = 0

        override suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean) {
            if (enabled) {
                if (consumer !in demands) demands += consumer
            } else {
                demands.remove(consumer)
            }
            state.value = state.value.copy(demands = demands.toSet())
        }

        override suspend fun refreshPermissionAndProviders() {
            refreshCount += 1
        }
    }

    private companion object {
        val place = PlaceCandidate(
            id = "place-1",
            title = "Набережная",
            subtitle = "Екатеринбург",
            coordinate = GeoCoordinate(56.8389, 60.6057),
        )
        val secondPlace = place.copy(id = "place-2", title = "Парк")

        fun fix(capturedAt: Long, accuracy: Double) = RideLocationFix(
            coordinate = GeoCoordinate(56.83, 60.60),
            accuracyMeters = accuracy,
            speedMetersPerSecond = null,
            bearingDegrees = null,
            capturedAtEpochMillis = capturedAt,
            elapsedRealtimeMillis = capturedAt,
            source = ru.sodovaya.volty.domain.location.LocationSource.GPS,
        )

        fun fakePlan(): RoutePlan = ru.sodovaya.volty.domain.navigation.RoutePlan(
            destination = place,
            alternatives = listOf(
                ru.sodovaya.volty.domain.navigation.RouteAlternative(
                    id = "route-1",
                    distanceMeters = 1_000.0,
                    durationSeconds = 120L,
                    geometry = listOf(
                        GeoCoordinate(56.83, 60.60),
                        GeoCoordinate(56.8389, 60.6057),
                    ),
                    maneuvers = listOf(
                        ru.sodovaya.volty.domain.navigation.RouteManeuver(
                            id = "arrive",
                            kind = ru.sodovaya.volty.domain.navigation.ManeuverKind.ARRIVE,
                            instruction = "Вы прибыли",
                            streetName = null,
                            shapeIndex = 1,
                            distanceMeters = 0.0,
                        ),
                    ),
                ),
            ),
        )
    }
}
