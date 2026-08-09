package ru.sodovaya.volty.presentation.graph

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.RideHistoryRepository
import ru.sodovaya.volty.domain.repository.RidePoint
import ru.sodovaya.volty.domain.repository.RideSummary
import ru.sodovaya.volty.domain.repository.StoredRide
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class GraphComponentTelemetryTest {
    private class FakeRepo : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val batteries = MutableStateFlow<List<BmsData>>(emptyList())
        val motion = MutableStateFlow<List<ControllerData>>(emptyList())

        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun disconnectLink(address: String) = Unit
        override fun samples(window: Duration): Flow<List<BmsData>> = batteries
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = motion
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() = Unit
    }

    private class FakeHistory(
        private val stored: StoredRide
    ) : RideHistoryRepository {
        override suspend fun startRide(summary: RideSummary) = Unit
        override suspend fun appendPoint(rideId: String, point: RidePoint) = Unit
        override suspend fun finishRide(rideId: String, endedAt: Instant) = Unit
        override suspend fun listRides(vehicleId: String?): List<RideSummary> = listOf(stored.summary)
        override suspend fun loadRide(rideId: String): StoredRide? = stored.takeIf { it.summary.id == rideId }
        override suspend fun deleteRide(rideId: String) = Unit
        override suspend fun pruneOldest(keep: Int) = Unit
    }

    private fun component(repo: FakeRepo) = DefaultGraphComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        bmsRepository = repo,
        onBackRequested = {}
    )

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `motion metric comes from motion samples and keeps its timestamp`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val component = component(repo)
        component.onMetricSelected(GraphMetric.SPEED)
        repo.motion.value = listOf(
            ControllerData(speedKmh = 31f, speedSource = SpeedSource.REPORTED, timestamp = Instant.fromEpochSeconds(10))
        )
        advanceUntilIdle()

        assertEquals(listOf(31f), component.state.value.values)
        assertEquals(Instant.fromEpochSeconds(10), component.state.value.series[GraphMetric.SPEED]?.points?.single()?.timestamp)
    }

    @Test
    fun `selection projects nearest point to every visible metric`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val component = component(repo)
        component.onMetricAdded(GraphMetric.VOLTAGE)
        repo.batteries.value = listOf(
            BmsData(voltage = 80f, timestamp = Instant.fromEpochSeconds(0)),
            BmsData(voltage = 81f, timestamp = Instant.fromEpochSeconds(2))
        )
        advanceUntilIdle()

        component.onTimestampSelected(Instant.fromEpochSeconds(1))
        val selected = component.state.value.selectedPoints[GraphMetric.VOLTAGE]
        assertNotNull(selected)
        assertEquals(Instant.fromEpochSeconds(0), selected.timestamp)
        assertTrue(component.state.value.selectedTimestamp != null)
    }

    @Test
    fun `completed ride replaces live series with stored points`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val started = Instant.fromEpochSeconds(100)
        val summary = RideSummary("ride-1", "vehicle-1", started, started + 30.seconds)
        val history = FakeHistory(
            StoredRide(summary, listOf(RidePoint(GraphMetric.SPEED.name, started, 24f)))
        )
        val component = DefaultGraphComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            onBackRequested = {},
            rideHistoryRepository = history
        )
        component.onMetricSelected(GraphMetric.SPEED)
        component.onRideSelected("ride-1")
        advanceUntilIdle()

        assertEquals("ride-1", component.state.value.selectedRideId)
        assertEquals(listOf(24f), component.state.value.values)
        assertEquals(1, component.state.value.history.size)
    }
}
