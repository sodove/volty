package ru.sodovaya.volty.presentation.vehicle.info

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.energy.RangeSource
import ru.sodovaya.volty.domain.energy.EnergyMemory
import ru.sodovaya.volty.domain.energy.EnergyRecord
import ru.sodovaya.volty.domain.energy.VehicleEnergyStore
import ru.sodovaya.volty.domain.energy.RangeDetail
import ru.sodovaya.volty.domain.energy.RangeEstimate
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.repository.BleDiagnosticsRepository
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import com.arkivanov.essenty.lifecycle.start
import com.arkivanov.essenty.lifecycle.stop
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VehicleInfoComponentTest {
    private val wheel = Vehicle(
        id = "wheel", name = "T4", iconKey = "wheel",
        packs = listOf(Pack(0, "Wheel pack", BmsType.BEGODE, "WW")),
        controllers = listOf(Controller(0, "Wheel", ControllerType.BEGODE, "WW")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0),
    )

    private class FakeBms : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class Vehicles(list: List<Vehicle>) : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = MutableStateFlow(list)
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    private class Diagnostics : BleDiagnosticsRepository {
        override val state = MutableStateFlow(BleDiagnosticsState())
        override fun startCapture() {}
        override fun stopCapture() {}
        override fun clearCapture() {}
    }

    /** Counts `peek` and refuses `load`: the screen must never call the destructive one. */
    private class Store(var record: EnergyRecord?) : VehicleEnergyStore {
        var peeks = 0
        override suspend fun load(vehicleId: String, seriesCells: Int, chemistry: Chemistry): EnergyMemory = error("load deletes on key mismatch; the UI must peek")
        override suspend fun peek(vehicleId: String): EnergyRecord? { peeks++; return record }
        override suspend fun save(vehicleId: String, seriesCells: Int, chemistry: Chemistry, memory: EnergyMemory) {}
        override suspend fun reset(vehicleId: String) {}
    }

    private class Range : RangeSource {
        override val range = MutableStateFlow<RangeEstimate?>(null)
        override val detail = MutableStateFlow<RangeDetail?>(null)
    }

    private class Rig(val bms: FakeBms, val diagnostics: Diagnostics, val store: Store, val component: VehicleInfoComponent)

    private fun TestScope.rig(record: EnergyRecord? = null, lifecycle: LifecycleRegistry = LifecycleRegistry(Lifecycle.State.RESUMED)): Rig {
        val bms = FakeBms(); val diagnostics = Diagnostics(); val store = Store(record)
        val component = DefaultVehicleInfoComponent(
            DefaultComponentContext(lifecycle), backgroundScope, "wheel",
            Vehicles(listOf(wheel)), bms, diagnostics, store, Range(), MutableStateFlow(UnitSystem.METRIC),
            onBackRequested = {}, refreshMillis = 500L, nowEpochMillis = { currentTime },
        )
        return Rig(bms, diagnostics, store, component)
    }

    private val record = EnergyRecord(
        EnergyMemory(null, resistanceOhm = 0.09f, counter = null), seriesCells = 20, chemistry = Chemistry.LI_ION_NMC,
    )

    private fun connect(r: Rig) {
        r.bms.activeVehicle.value = wheel
        r.bms.connectionState.value = ConnectionState.Connected(wheel)
    }

    private fun InfoRow.vu() = value to unit

    private fun VehicleInfo?.row(kind: InfoSectionKind, label: InfoLabel) =
        checkNotNull(this).sections.first { it.kind == kind }.rows.first { it.label == label }

    @Test fun opensWithThePersistedRecordAndPeeksOnceNeverLoads() = runTest {
        val r = rig(record); runCurrent()
        assertEquals(1, r.store.peeks)
        assertEquals("T4", r.component.vehicleName.value)
        val resistance = r.component.info.value.row(InfoSectionKind.ENERGY, InfoLabel.RESISTANCE)
        assertEquals("90.0" to InfoUnit.MILLIOHM, resistance.vu()); assertTrue(resistance.persisted)
        assertEquals(InfoAbsence.NOT_CONNECTED, r.component.info.value.row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).reason)
        advanceTimeBy(5_000)
        assertEquals(1, r.store.peeks, "ticking does not re-read the store")
    }

    @Test fun rereadsTheRecordWhenTheConnectionDropsAndOnlyThen() = runTest {
        val r = rig(null); runCurrent()
        assertEquals(1, r.store.peeks)
        assertEquals(InfoAbsence.NOT_EARNED, r.component.info.value.row(InfoSectionKind.ENERGY, InfoLabel.RESISTANCE).reason)
        connect(r); advanceTimeBy(600)
        assertEquals(1, r.store.peeks, "connecting does not peek")
        // The tracker saved while connected; the drop picks it up.
        r.store.record = record
        r.bms.connectionState.value = ConnectionState.Disconnected
        advanceTimeBy(600)
        assertEquals(2, r.store.peeks)
        assertEquals("90.0" to InfoUnit.MILLIOHM, r.component.info.value.row(InfoSectionKind.ENERGY, InfoLabel.RESISTANCE).vu())
    }

    @Test fun aBurstOfSamplesIsFoldedIntoAtMostTwoUpdatesASecond() = runTest {
        val r = rig(); connect(r); runCurrent()
        val seen = mutableListOf<VehicleInfo?>()
        backgroundScope.launch { r.component.info.collect { seen += it } }
        runCurrent()
        seen.clear()
        // Twenty distinct speeds in 200 ms of virtual time.
        repeat(20) { n ->
            val motion = ControllerData(speedKmh = 10f + n, speedSource = SpeedSource.REPORTED, isConnected = true)
            r.bms.activeVehicleData.value = VehicleData(
                controllers = listOf(ControllerState(wheel.controllers.single(), motion, isOnline = true)), motion = motion)
            r.bms.activeMotion.value = motion
            advanceTimeBy(10)
        }
        advanceTimeBy(800)
        assertTrue(seen.size in 1..2, "20 changes in 1 s must publish at most 2 updates, got ${seen.size}")
        assertEquals("29" to InfoUnit.SPEED, seen.last().row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).vu(), "the last value wins")
    }

    @Test fun aStalledButOnlineLinkGoesStaleWithoutAnyEmission() = runTest {
        val r = rig(); connect(r)
        r.diagnostics.state.value = BleDiagnosticsState(activeLinks = listOf(
            BleDiagnosticLink("WW", null, "BEGODE", null, null, null, null, framesPerSecond = 10f, statsAtEpochMillis = 0L)))
        runCurrent()
        assertEquals("10.0", r.component.info.value.row(InfoSectionKind.CONNECTIONS, InfoLabel.FRAMES_PER_SECOND).value)
        // Nothing upstream changes; only the clock does.
        advanceTimeBy(3_600)
        assertEquals(InfoAbsence.STALE, r.component.info.value.row(InfoSectionKind.CONNECTIONS, InfoLabel.FRAMES_PER_SECOND).reason)
    }

    @Test fun noRebuildHappensWhileTheScreenIsStoppedAndItResumesOnStart() = runTest {
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        val r = rig(lifecycle = lifecycle); connect(r); runCurrent()
        fun speed(v: Float) {
            val motion = ControllerData(speedKmh = v, speedSource = SpeedSource.REPORTED, isConnected = true)
            r.bms.activeVehicleData.value = VehicleData(controllers = listOf(ControllerState(wheel.controllers.single(), motion, isOnline = true)), motion = motion)
            r.bms.activeMotion.value = motion
        }
        speed(10f); advanceTimeBy(600)
        assertEquals("10" to InfoUnit.SPEED, r.component.info.value.row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).vu())
        lifecycle.stop()
        speed(50f); advanceTimeBy(5_000)
        assertEquals("10", r.component.info.value.row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).value, "stopped: no rebuild")
        lifecycle.start(); advanceTimeBy(100)
        assertEquals("50", r.component.info.value.row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).value, "started again: rebuilt at once")
    }

    @Test fun anUnknownVehicleHasNoInfo() = runTest {
        val c = DefaultVehicleInfoComponent(
            DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, "missing",
            Vehicles(listOf(wheel)), FakeBms(), Diagnostics(), Store(null), Range(), MutableStateFlow(UnitSystem.METRIC), {},
        )
        advanceTimeBy(1_000)
        assertNull(c.info.value); assertNull(c.vehicleName.value)
    }
}
