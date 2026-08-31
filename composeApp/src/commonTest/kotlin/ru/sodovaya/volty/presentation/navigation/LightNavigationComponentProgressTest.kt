package ru.sodovaya.volty.presentation.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.start
import com.arkivanov.essenty.lifecycle.stop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.location.RideLocationStatus
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest

@OptIn(ExperimentalCoroutinesApi::class)
class LightNavigationComponentProgressTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `fresh fix publishes guidance and stale or poor fix clears it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val now = MutableStateFlow(10_000L)
        val location = FakeLocationRepository(
            RideLocationState(RideLocationStatus.Available(fix(9_000L, accuracy = 10.0))),
        )
        val navigation = FakeNavigationRepository()
        val component = component(navigation, location, dispatcher, now)
        startRoute(component)
        advanceUntilIdle()
        component.onStartNavigation()
        advanceUntilIdle()

        location.state.value = location.state.value.copy(
            status = RideLocationStatus.Available(fix(9_500L, accuracy = 10.0)),
        )
        advanceUntilIdle()
        val navigating = assertIs<NavigationPhase.Navigating>(component.state.value.phase)
        assertNotNull(navigating.guidance)

        now.value = 20_000L
        location.state.value = location.state.value.copy(
            status = RideLocationStatus.Available(fix(9_400L, accuracy = 10.0)),
        )
        advanceUntilIdle()
        assertNull(assertIs<NavigationPhase.Navigating>(component.state.value.phase).guidance)
        assertEquals(LocationUiStatus.STALE, component.state.value.locationStatus)

        now.value = 10_000L
        location.state.value = location.state.value.copy(
            status = RideLocationStatus.Available(fix(9_900L, accuracy = 80.0)),
        )
        advanceUntilIdle()
        assertNull(assertIs<NavigationPhase.Navigating>(component.state.value.phase).guidance)
        assertEquals(LocationUiStatus.POOR_ACCURACY, component.state.value.locationStatus)
        component.close()
    }

    @Test
    fun `starting a ready route accepts the last known position when stale`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val now = MutableStateFlow(10_000L)
        val location = FakeLocationRepository(
            RideLocationState(RideLocationStatus.Available(fix(9_000L, accuracy = 10.0))),
        )
        val navigation = FakeNavigationRepository()
        val component = component(navigation, location, dispatcher, now)
        startRoute(component)
        advanceUntilIdle()

        now.value = 20_000L
        component.onStartNavigation()
        assertIs<NavigationPhase.Navigating>(component.state.value.phase)
        component.close()
    }

    @Test
    fun `starting a ready route does not wait for a second location fix`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val now = MutableStateFlow(10_000L)
        val location = FakeLocationRepository(
            RideLocationState(RideLocationStatus.Available(fix(9_000L, accuracy = 10.0))),
        )
        val navigation = FakeNavigationRepository()
        val component = component(navigation, location, dispatcher, now)
        startRoute(component)
        advanceUntilIdle()

        location.state.value = location.state.value.copy(status = RideLocationStatus.Searching)
        advanceUntilIdle()
        component.onStartNavigation()

        assertIs<NavigationPhase.Navigating>(component.state.value.phase)
        component.close()
    }

    @Test
    fun `two arrival fixes transition to arrived without losing the destination`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val now = MutableStateFlow(10_000L)
        val location = FakeLocationRepository(
            RideLocationState(RideLocationStatus.Available(fix(9_900L, accuracy = 10.0))),
        )
        val navigation = FakeNavigationRepository()
        val component = component(navigation, location, dispatcher, now)
        startRoute(component)
        advanceUntilIdle()
        component.onStartNavigation()
        advanceUntilIdle()

        val destinationFix = fix(9_950L, accuracy = 10.0, atDestination = true)
        location.state.value = location.state.value.copy(
            status = RideLocationStatus.Available(destinationFix),
        )
        advanceUntilIdle()
        assertIs<NavigationPhase.Navigating>(component.state.value.phase)

        location.state.value = location.state.value.copy(
            status = RideLocationStatus.Available(destinationFix.copy(capturedAtEpochMillis = 9_960L)),
        )
        advanceUntilIdle()
        val arrived = assertIs<NavigationPhase.Arrived>(component.state.value.phase)
        assertEquals(place, arrived.plan.destination)
        component.close()
    }

    @Test
    fun `one confirmed off-route episode issues one reroute and atomically replaces the route`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val now = MutableStateFlow(10_000L)
            val location = FakeLocationRepository(
                RideLocationState(RideLocationStatus.Available(fix(9_000L, accuracy = 5.0))),
            )
            val replacement = routePlan(routeId = "replacement")
            val navigation = FakeNavigationRepository(
                routeResponses = ArrayDeque(
                    listOf(
                        NavigationResult.Success(routePlan(routeId = "initial")),
                        NavigationResult.Success(replacement),
                    ),
                ),
            )
            val component = component(navigation, location, dispatcher, now)
            startRoute(component)
            advanceUntilIdle()
            component.onStartNavigation()
            advanceUntilIdle()

            listOf(8_000L, 9_000L, 10_000L).forEach { capturedAt ->
                location.state.value = location.state.value.copy(
                    status = RideLocationStatus.Available(offRouteFix(capturedAt)),
                )
                advanceUntilIdle()
            }

            assertEquals(2, navigation.routeRequests.size)
            val navigating = assertIs<NavigationPhase.Navigating>(component.state.value.phase)
            assertEquals("replacement", navigating.plan.alternatives.single().id)
            assertNull(navigating.guidance)
            component.close()
        }

    @Test
    fun `reroute retries are bounded to two delays and honor retry after`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val now = MutableStateFlow(10_000L)
        val location = FakeLocationRepository(
            RideLocationState(RideLocationStatus.Available(fix(9_000L, accuracy = 5.0))),
        )
        val navigation = FakeNavigationRepository(
            routeResponses = ArrayDeque(
                listOf(
                    NavigationResult.Success(routePlan(routeId = "initial")),
                    NavigationResult.Failure(NavigationFailure.RateLimited(10L)),
                    NavigationResult.Failure(NavigationFailure.Offline),
                    NavigationResult.Success(routePlan(routeId = "replacement")),
                ),
            ),
        )
        val component = component(navigation, location, dispatcher, now)
        startRoute(component)
        advanceUntilIdle()
        component.onStartNavigation()
        advanceUntilIdle()
        listOf(8_000L, 9_000L, 10_000L).forEach { capturedAt ->
            location.state.value = location.state.value.copy(
                status = RideLocationStatus.Available(offRouteFix(capturedAt)),
            )
            runCurrent()
        }
        assertEquals(2, navigation.routeRequests.size)

        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(2, navigation.routeRequests.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, navigation.routeRequests.size)

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(3, navigation.routeRequests.size)
        advanceTimeBy(1L)
        advanceUntilIdle()
        assertEquals(4, navigation.routeRequests.size)
        assertIs<NavigationPhase.Navigating>(component.state.value.phase)
        component.close()
    }

    @Test
    fun `stopping during reroute fences the late provider response`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val now = MutableStateFlow(10_000L)
        val location = FakeLocationRepository(
            RideLocationState(RideLocationStatus.Available(fix(9_000L, accuracy = 5.0))),
        )
        val lateResponse = CompletableDeferred<NavigationResult<RoutePlan>>()
        val navigation = FakeNavigationRepository(
            routeResponses = ArrayDeque(listOf(NavigationResult.Success(routePlan("initial")))),
            deferredRouteResponses = ArrayDeque(listOf(lateResponse)),
        )
        val component = component(navigation, location, dispatcher, now)
        startRoute(component)
        advanceUntilIdle()
        component.onStartNavigation()
        advanceUntilIdle()

        listOf(8_000L, 9_000L, 10_000L).forEach { capturedAt ->
            location.state.value = location.state.value.copy(
                status = RideLocationStatus.Available(offRouteFix(capturedAt)),
            )
            runCurrent()
        }
        assertIs<NavigationPhase.Rerouting>(component.state.value.phase)
        assertEquals(2, navigation.routeRequests.size)

        component.onStopNavigation()
        lateResponse.complete(NavigationResult.Success(routePlan("late")))
        advanceUntilIdle()

        assertIs<NavigationPhase.Idle>(component.state.value.phase)
        assertEquals(2, navigation.routeRequests.size)
        component.close()
    }

    @Test
    fun `lifecycle stop releases location demands but preserves route intent and start reacquires`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val now = MutableStateFlow(10_000L)
            val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
            val location = FakeLocationRepository(
                RideLocationState(RideLocationStatus.Available(fix(9_900L, accuracy = 10.0))),
            )
            val navigation = FakeNavigationRepository()
            val component = component(navigation, location, dispatcher, now, lifecycle)
            component.onMapVisibilityChanged(true)
            startRoute(component)
            advanceUntilIdle()
            component.onStartNavigation()
            advanceUntilIdle()
            assertTrue(location.demands.contains(LocationConsumer.MAP))
            assertTrue(location.demands.contains(LocationConsumer.NAVIGATION))

            lifecycle.stop()
            advanceUntilIdle()
            assertTrue(location.demands.isEmpty())
            assertEquals(LocationUiStatus.NOT_REQUESTED, component.state.value.locationStatus)
            assertIs<NavigationPhase.Navigating>(component.state.value.phase)
            assertNull(assertIs<NavigationPhase.Navigating>(component.state.value.phase).guidance)

            lifecycle.start()
            advanceUntilIdle()
            assertTrue(location.demands.contains(LocationConsumer.MAP))
            assertTrue(location.demands.contains(LocationConsumer.NAVIGATION))
            assertIs<NavigationPhase.Navigating>(component.state.value.phase)
            component.close()
        }

    private fun startRoute(component: DefaultLightNavigationComponent) {
        component.onPlannerRequested()
        component.onPlaceSelected(place)
        component.onRetry()
    }

    private fun component(
        navigation: FakeNavigationRepository,
        location: FakeLocationRepository,
        dispatcher: TestDispatcher,
        now: MutableStateFlow<Long>,
        lifecycle: LifecycleRegistry = LifecycleRegistry(Lifecycle.State.RESUMED),
    ) = DefaultLightNavigationComponent(
        componentContext = DefaultComponentContext(lifecycle),
        navigationRepository = navigation,
        locationRepository = location,
        dispatcher = dispatcher,
        nowEpochMillis = { now.value },
    )

    private class FakeNavigationRepository(
        private val routeResponses: ArrayDeque<NavigationResult<RoutePlan>> = ArrayDeque(),
        private val deferredRouteResponses: ArrayDeque<CompletableDeferred<NavigationResult<RoutePlan>>> = ArrayDeque(),
    ) : NavigationRepository {
        val routeRequests = mutableListOf<RouteRequest>()

        override suspend fun search(
            query: String,
            near: GeoCoordinate?,
            languageTag: String,
        ): NavigationResult<List<PlaceCandidate>> = NavigationResult.Success(emptyList())

        override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> {
            routeRequests += request
            return if (routeResponses.isNotEmpty()) {
                routeResponses.removeFirst()
            } else if (deferredRouteResponses.isNotEmpty()) {
                deferredRouteResponses.removeFirst().await()
            } else {
                NavigationResult.Success(routePlan("default"))
            }
        }
    }

    private class FakeLocationRepository(
        initial: RideLocationState,
    ) : RideLocationRepository {
        override val requiredPermissions: List<String> = emptyList()
        override val state = MutableStateFlow(initial)
        val demands = mutableSetOf<LocationConsumer>()

        override suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean) {
            if (enabled) demands += consumer else demands -= consumer
            state.value = state.value.copy(demands = demands.toSet())
        }

        override suspend fun refreshPermissionAndProviders() = Unit
    }

    private companion object {
        val place = PlaceCandidate(
            id = "place-1",
            title = "Набережная",
            subtitle = "Екатеринбург",
            coordinate = GeoCoordinate(56.83, 60.61),
        )

        fun fix(capturedAtEpochMillis: Long, accuracy: Double, atDestination: Boolean = false) =
            RideLocationFix(
                coordinate = if (atDestination) place.coordinate else GeoCoordinate(56.83, 60.60),
                accuracyMeters = accuracy,
                speedMetersPerSecond = null,
                bearingDegrees = null,
                capturedAtEpochMillis = capturedAtEpochMillis,
                elapsedRealtimeMillis = capturedAtEpochMillis,
                source = LocationSource.GPS,
            )

        fun offRouteFix(capturedAtEpochMillis: Long) = RideLocationFix(
            coordinate = GeoCoordinate(56.832, 60.60),
            accuracyMeters = 5.0,
            speedMetersPerSecond = null,
            bearingDegrees = null,
            capturedAtEpochMillis = capturedAtEpochMillis,
            elapsedRealtimeMillis = capturedAtEpochMillis,
            source = LocationSource.GPS,
        )

        fun routePlan(routeId: String): RoutePlan {
            val geometry = listOf(
                GeoCoordinate(56.83, 60.60),
                GeoCoordinate(56.83, 60.61),
            )
            return RoutePlan(
                destination = place,
                alternatives = listOf(
                    RouteAlternative(
                        id = routeId,
                        distanceMeters = 1_000.0,
                        durationSeconds = 120L,
                        geometry = geometry,
                        maneuvers = listOf(
                            RouteManeuver(
                                id = "$routeId-straight",
                                kind = ManeuverKind.STRAIGHT,
                                instruction = "Ехать прямо",
                                streetName = null,
                                shapeIndex = 1,
                                distanceMeters = 1_000.0,
                            ),
                            RouteManeuver(
                                id = "$routeId-arrive",
                                kind = ManeuverKind.ARRIVE,
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
}
