package ru.sodovaya.volty.data.history

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
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.RideHistoryRepository
import ru.sodovaya.volty.domain.repository.RidePoint
import ru.sodovaya.volty.domain.repository.RideSummary
import ru.sodovaya.volty.domain.repository.StoredRide
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class RideTelemetryRecorderTest {
    private val vehicle = singlePackVehicle(
        id = "vehicle-1",
        name = "Test",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB",
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.parse("2026-08-09T00:00:00Z")
    )

    private class FakeHistory : RideHistoryRepository {
        val rides = linkedMapOf<String, RideSummary>()
        val points = mutableListOf<Pair<String, RidePoint>>()
        override suspend fun startRide(summary: RideSummary) { rides[summary.id] = summary }
        override suspend fun appendPoint(rideId: String, point: RidePoint) { points += rideId to point }
        override suspend fun finishRide(rideId: String, endedAt: Instant) {
            rides[rideId] = requireNotNull(rides[rideId]).copy(endedAt = endedAt)
        }
        override suspend fun listRides(vehicleId: String?): List<RideSummary> = rides.values.toList()
        override suspend fun loadRide(rideId: String): StoredRide? = null
        override suspend fun deleteRide(rideId: String) { rides.remove(rideId) }
        override suspend fun pruneOldest(keep: Int) = Unit
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `records one latest point per metric bucket and finishes on disconnect`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activeVehicle = MutableStateFlow<Vehicle?>(vehicle)
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val battery = MutableStateFlow<List<BmsData>>(emptyList())
        val motion = MutableStateFlow<List<ControllerData>>(emptyList())
        val history = FakeHistory()
        var current = Instant.parse("2026-08-09T00:00:00Z")
        val recorder = RideTelemetryRecorder(
            activeVehicle = activeVehicle,
            connectionState = state,
            batterySamples = { _: Duration -> battery },
            motionSamples = { _: Duration -> motion },
            history = history,
            context = StandardTestDispatcher(testScheduler),
            now = { current },
            bucket = 5.seconds
        )
        recorder.start()
        state.value = ConnectionState.Connected(vehicle)
        advanceUntilIdle()
        battery.value = listOf(BmsData(voltage = 80f, timestamp = current))
        advanceUntilIdle()
        current += 6.seconds
        battery.value = listOf(BmsData(voltage = 80f, timestamp = current))
        advanceUntilIdle()
        state.value = ConnectionState.Disconnected
        advanceUntilIdle()

        assertEquals(1, history.rides.size)
        assertNotNull(history.rides.values.single().endedAt)
        assertEquals(2, history.points.count { it.second.metricKey == "VOLTAGE" })
        recorder.stop()
    }
}
