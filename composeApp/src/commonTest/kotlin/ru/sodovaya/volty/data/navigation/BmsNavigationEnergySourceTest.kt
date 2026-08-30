package ru.sodovaya.volty.data.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
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
import ru.sodovaya.volty.domain.navigation.ConsumptionProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class BmsNavigationEnergySourceTest {
    @Test
    fun measured_controller_counters_take_precedence_over_power_integral() = runTest {
        val time = Instant.fromEpochSeconds(1_700_000_000L)
        val fake = FakeBmsRepository()
        val vehicle = testVehicle("counter-vehicle", time)
        fake.activeVehicle.value = vehicle
        fake.connectionState.value = ConnectionState.Connected(vehicle)
        fake.activeVehicleData.value = completeVehicleData(time)
        fake.activeMotion.value = ControllerData(
            consumedWh = 500f,
            tripKm = 10f,
            hasEnergyCounters = true,
            isConnected = true,
            timestamp = time + 300.seconds,
        )
        fake.motionSamples.value = motionSamples(time, energyCounters = true)

        val source = BmsNavigationEnergySource(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        val evidence = assertNotNull(source.evidence.value.consumption)

        assertEquals(50.0, evidence.whPerKm, 0.01)
        assertEquals(ConsumptionProvenance.CONTROLLER_COUNTERS, evidence.provenance)
    }

    @Test
    fun absent_counters_use_only_measured_power_integral_and_keep_provenance() = runTest {
        val time = Instant.fromEpochSeconds(1_700_000_000L)
        val fake = FakeBmsRepository()
        val vehicle = testVehicle("integral-vehicle", time)
        fake.activeVehicle.value = vehicle
        fake.connectionState.value = ConnectionState.Connected(vehicle)
        fake.activeVehicleData.value = completeVehicleData(time)
        fake.activeMotion.value = ControllerData(
            hasEnergyCounters = false,
            hasPower = false,
            powerW = 4_200f,
            isConnected = true,
            timestamp = time + 300.seconds,
        )
        fake.motionSamples.value = motionSamples(time, energyCounters = false)

        val source = BmsNavigationEnergySource(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        val evidence = assertNotNull(source.evidence.value.consumption)

        assertEquals(ConsumptionProvenance.POWER_INTEGRAL, evidence.provenance)
        assertEquals(2.0, evidence.distanceKm, 0.01)
        assertEquals(20, evidence.measuredSampleCount)
        assertTrue(evidence.whPerKm > 0.0)
    }

    @Test
    fun vehicle_or_connection_change_cannot_reuse_old_session_consumption() = runTest {
        val time = Instant.fromEpochSeconds(1_700_000_000L)
        val fake = FakeBmsRepository()
        val firstVehicle = testVehicle("first", time)
        fake.activeVehicle.value = firstVehicle
        fake.connectionState.value = ConnectionState.Connected(firstVehicle)
        fake.activeVehicleData.value = completeVehicleData(time)
        fake.activeMotion.value = ControllerData(
            consumedWh = 500f,
            tripKm = 10f,
            hasEnergyCounters = true,
            isConnected = true,
            timestamp = time + 300.seconds,
        )
        fake.motionSamples.value = motionSamples(time, energyCounters = true)

        val source = BmsNavigationEnergySource(fake, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertNotNull(source.evidence.value.consumption)

        fake.connectionState.value = ConnectionState.Disconnected
        fake.activeVehicle.value = null
        fake.activeVehicleData.value = VehicleData()
        fake.activeMotion.value = ControllerData()
        advanceUntilIdle()
        assertNull(source.evidence.value.consumption)

        val secondVehicle = testVehicle("second", time + 600.seconds)
        fake.activeVehicle.value = secondVehicle
        fake.connectionState.value = ConnectionState.Connected(secondVehicle)
        fake.activeVehicleData.value = completeVehicleData(time + 600.seconds)
        fake.activeMotion.value = ControllerData(
            consumedWh = 1_000f,
            tripKm = 20f,
            hasEnergyCounters = true,
            isConnected = true,
            timestamp = time + 900.seconds,
        )
        advanceUntilIdle()
        val newSessionConsumption = assertNotNull(source.evidence.value.consumption)
        assertEquals(0, newSessionConsumption.measuredSampleCount)
    }

    private fun completeVehicleData(timestamp: Instant): VehicleData = VehicleData(
        packs = listOf(
            PackState(
                pack = Pack(0, "P0", BmsType.JK_BMS, "AA:00"),
                data = BmsData(
                    voltage = 50f,
                    capacity = 20f,
                    soc = 80f,
                    socKnown = true,
                    isConnected = true,
                    timestamp = timestamp,
                ),
                isOnline = true,
            ),
        ),
        aggregate = BmsData(
            voltage = 50f,
            capacity = 20f,
            soc = 80f,
            socKnown = true,
            isConnected = true,
            timestamp = timestamp,
        ),
        topology = PackTopology.PARALLEL,
        isPartial = false,
    )

    private fun motionSamples(time: Instant, energyCounters: Boolean): List<ControllerData> =
        List(20) { index ->
            ControllerData(
                powerW = 1_000f,
                hasPower = true,
                hasEnergyCounters = energyCounters,
                tripKm = index * 2f / 19f,
                timestamp = time + (index.toLong() * 16L).seconds,
            )
        }

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
