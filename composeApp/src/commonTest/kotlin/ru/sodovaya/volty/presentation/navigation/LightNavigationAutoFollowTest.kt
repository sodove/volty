package ru.sodovaya.volty.presentation.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.location.RideLocationStatus
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import ru.sodovaya.volty.presentation.map.RideMapFollowMode

/**
 * The map returns to following the rider a few seconds after a pan, while moving (rider, 2026-10-28: after a finger
 * drag the Light map stayed put until Recenter or the navigator was tapped). The policy existed and was tested, but
 * nothing ever called it. The gesture and the fix carry the same elapsed-realtime clock, so the decision is made on
 * each fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightNavigationAutoFollowTest {
    private val lifecycles = mutableListOf<LifecycleRegistry>()

    @AfterTest
    fun tearDown() {
        lifecycles.forEach { it.destroy() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a pan while moving returns to following once the delay has passed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val location = FakeLocation(fixAt(elapsed = 100_000L, speedKmh = 25.0))
        val component = component(location, dispatcher)
        advanceUntilIdle()

        component.onCameraGesture(nowElapsedMillis = 101_000L)
        component.onCameraGestureEnd(nowElapsedMillis = 101_000L)
        assertEquals(RideMapFollowMode.FREE, component.state.value.followState.mode)

        location.emit(fixAt(elapsed = 105_999L, speedKmh = 25.0))
        advanceUntilIdle()
        assertEquals(RideMapFollowMode.FREE, component.state.value.followState.mode, "4.999 s: still free")

        location.emit(fixAt(elapsed = 106_000L, speedKmh = 25.0))
        advanceUntilIdle()
        assertEquals(RideMapFollowMode.FOLLOWING, component.state.value.followState.mode, "5 s while moving: follows again")
    }

    @Test
    fun `a gesture still in progress never returns and the delay runs from its end`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val location = FakeLocation(fixAt(elapsed = 100_000L, speedKmh = 25.0))
        val component = component(location, dispatcher)
        advanceUntilIdle()

        component.onCameraGesture(nowElapsedMillis = 101_000L)
        location.emit(fixAt(elapsed = 130_000L, speedKmh = 25.0))
        advanceUntilIdle()
        assertEquals(RideMapFollowMode.FREE, component.state.value.followState.mode, "29 s into a drag: the finger is still down")

        component.onCameraGestureEnd(nowElapsedMillis = 131_000L)
        location.emit(fixAt(elapsed = 135_999L, speedKmh = 25.0))
        advanceUntilIdle()
        assertEquals(RideMapFollowMode.FREE, component.state.value.followState.mode, "4.999 s after the END")
        location.emit(fixAt(elapsed = 136_000L, speedKmh = 25.0))
        advanceUntilIdle()
        assertEquals(RideMapFollowMode.FOLLOWING, component.state.value.followState.mode, "5 s after the end")
    }

    @Test
    fun `standing still keeps the map where the rider panned it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val location = FakeLocation(fixAt(elapsed = 100_000L, speedKmh = 0.0))
        val component = component(location, dispatcher)
        advanceUntilIdle()

        component.onCameraGesture(nowElapsedMillis = 101_000L)
        location.emit(fixAt(elapsed = 130_000L, speedKmh = 1.0))
        advanceUntilIdle()
        assertEquals(RideMapFollowMode.FREE, component.state.value.followState.mode)

        // A fix without a speed (network) or without an elapsed clock cannot decide either.
        location.emit(fixAt(elapsed = 131_000L, speedKmh = null))
        location.emit(fixAt(elapsed = null, speedKmh = 25.0))
        advanceUntilIdle()
        assertEquals(RideMapFollowMode.FREE, component.state.value.followState.mode)
    }

    private fun component(location: FakeLocation, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        DefaultLightNavigationComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED).also { lifecycles += it }),
            navigationRepository = NoNavigation,
            locationRepository = location,
            dispatcher = dispatcher,
            nowEpochMillis = { 1_000_000L },
        )

    private var seq = 0L

    private fun fixAt(elapsed: Long?, speedKmh: Double?) = RideLocationFix(
        coordinate = GeoCoordinate(56.84, 60.6 + (seq++) * 0.0001),
        accuracyMeters = 5.0,
        speedMetersPerSecond = speedKmh?.div(3.6),
        bearingDegrees = null,
        capturedAtEpochMillis = 999_000L + seq,
        elapsedRealtimeMillis = elapsed,
        source = LocationSource.GPS,
    )

    private class FakeLocation(initial: RideLocationFix) : RideLocationRepository {
        override val requiredPermissions: List<String> = emptyList()
        override val state = MutableStateFlow(RideLocationState(RideLocationStatus.Available(initial)))
        fun emit(fix: RideLocationFix) { state.value = state.value.copy(status = RideLocationStatus.Available(fix)) }
        override suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean) = Unit
        override suspend fun refreshPermissionAndProviders() = Unit
    }

    private object NoNavigation : NavigationRepository {
        override suspend fun search(query: String, near: GeoCoordinate?, languageTag: String):
            NavigationResult<List<PlaceCandidate>> = NavigationResult.Success(emptyList())
        override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> =
            NavigationResult.Success(error("no routes in this test"))
    }
}
