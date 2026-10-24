package ru.sodovaya.volty.data.energy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.energy.CapacitySource
import ru.sodovaya.volty.domain.energy.EnergyState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class BmsRangeSourceTest {
    @Test
    fun range_is_recomputed_at_most_once_a_second() = runTest {
        val time = Instant.fromEpochSeconds(1_700_000_000L)
        val fake = FakeBmsRepository()
        val vehicle = testVehicle("range-vehicle", time)
        fake.activeVehicle.value = vehicle
        fake.connectionState.value = ConnectionState.Connected(vehicle)
        fake.activeVehicleData.value = vehicleWithEnergy(time)
        fake.motionSamples.value = motionSamples(time)
        fake.activeMotion.value = motion(time, consumedWh = 500f) // 50 Wh/km -> 900 Wh = 18 km

        // The sampler's ticker never goes idle: bounded advances only, and the scope is cancelled.
        var computations = 0
        val job = Job()
        val source = BmsRangeSource(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)) { computations++ }
        try {
            assertNull(source.range.value)
            assertEquals(0, computations) // nothing computed before the first tick
            advanceTimeBy(1_001L)
            assertEquals(1, computations)
            assertEquals(18f, assertNotNull(source.range.value).km, 0.01f)

            // Four motion updates inside one second: none may reach the published range yet.
            listOf(250f, 300f, 400f, 450f).forEach { wh ->
                fake.activeMotion.value = motion(time, consumedWh = wh)
                advanceTimeBy(100L)
                assertEquals(18f, assertNotNull(source.range.value).km, 0.01f)
                assertEquals(1, computations) // updates inside the second do no work at all
            }
            // The next tick publishes only the latest (450 Wh / 10 km = 45 Wh/km -> 20 km).
            advanceTimeBy(1_000L)
            assertEquals(20f, assertNotNull(source.range.value).km, 0.01f)
            assertEquals(2, computations)
        } finally {
            job.cancel()
        }
    }

    private fun motion(time: Instant, consumedWh: Float) = ControllerData(
        consumedWh = consumedWh,
        tripKm = 10f,
        hasEnergyCounters = true,
        isConnected = true,
        timestamp = time + 300.seconds,
    )

    private fun motionSamples(time: Instant): List<ControllerData> =
        List(20) { index ->
            ControllerData(
                hasEnergyCounters = true,
                tripKm = index * 10f / 19f,
                timestamp = time + (index.toLong() * 16L).seconds,
            )
        }

    private fun vehicleWithEnergy(time: Instant): VehicleData = VehicleData(
        energy = EnergyState(
            socPercent = 50f, usableAh = 25f, nominalPackV = 72f, remainingWh = 900f,
            capacitySource = CapacitySource.ENTERED, learned = null, resistanceOhm = null, modelled = true,
        ),
    )

    private fun testVehicle(id: String, time: Instant): Vehicle = Vehicle(
        id = id,
        name = id,
        iconKey = "scooter",
        packs = listOf(Pack(0, "P0", BmsType.JK_BMS, "AA:00")),
        topology = PackTopology.PARALLEL,
        chemistry = ru.sodovaya.volty.domain.model.Chemistry.LI_ION_NMC,
        createdAt = time,
    )

    private class FakeBmsRepository : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val motionSamples = MutableStateFlow<List<ControllerData>>(emptyList())

        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(profile: ru.sodovaya.volty.domain.model.DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun disconnectLink(address: String) = Unit
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = motionSamples.asStateFlow()
        override fun movingAverage(window: Duration): Flow<ru.sodovaya.volty.domain.stats.MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() = Unit
        override suspend fun onAppPaused() = Unit
    }
}
