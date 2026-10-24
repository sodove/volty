package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeT4Fixture
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.domain.energy.CapacitySource
import ru.sodovaya.volty.domain.energy.EnergyMemory
import ru.sodovaya.volty.domain.energy.LearnedCapacity
import ru.sodovaya.volty.domain.energy.OcvCurve
import ru.sodovaya.volty.domain.energy.PersistedCharge
import ru.sodovaya.volty.domain.energy.VehicleEnergyStore
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.BatteryCapacity
import ru.sodovaya.volty.domain.model.CapacityUnit
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * End-to-end (minus the radio) for a Begode WITHOUT a smart BMS: real
 * protocol decode of a 0x00-only stream, routed through the production
 * sample funnel — live-voltage scaling, SoC estimation, orchestrator,
 * activeData — exactly as a session would drive it.
 *
 * The stream is SYNTHETIC: nobody has captured a dumb-BMS Begode yet. The
 * live-frame bytes are anchored to the ET Max capture (raw 5892 = 58.92 V on
 * the 67.2 V scale for a real ~147 V pack), so the numbers asserted here are
 * the ones a real wheel is known to produce on this frame type.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryDumbBegodeTest {

    private class NoopVehicleRepository(
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
    ) : VehicleRepository {
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
        // no fake gets a silent default. Nothing in this file rides a learned dial range
        // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
        // absence in that map means "has learned nothing", which is exactly the case here.
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = NoopVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    private fun vehicle(cellCount: Int?) = singlePackVehicle(
        id = "v1",
        name = "Old T4",
        iconKey = "unicycle",
        bmsType = BmsType.BEGODE,
        bmsAddress = ADDRESS,
        chemistry = Chemistry.LI_ION_NMC,
        cellCount = cellCount,
        createdAt = Instant.fromEpochSeconds(0)
    )

    /**
     * The session's observe loop in miniature: decode the notification, then
     * route every pack's gated sample through the production funnel.
     */
    private class Wire(
        repo: KableBmsRepository,
        vehicle: Vehicle?,
    ) {
        private val pipeline = repo.installProtocolPipelineForTest(vehicle, ADDRESS, BmsType.BEGODE)
        private val gate = PackSampleGate(pipeline.first.packCount)
        fun notify(bytes: ByteArray) {
            pipeline.first.onNotification(bytes)
            routePackSamples(pipeline.first, gate) { i, bms, sections ->
                pipeline.second(i, bms, sections)
            }
        }
    }

    @Test
    fun `a dumb wheel with a known cell count shows scaled voltage, SoC, current and temperature`() = repoTest { repo ->
        val wire = Wire(repo, vehicle(cellCount = 40))

        // A dumb wheel's whole vocabulary: live frames and the odometer.
        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        wire.notify(odometerFrame())
        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))

        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.packs.size, "one pack — no phantom second branch")
        assertTrue(vd.packs[0].isOnline)
        assertFalse(vd.isPartial, "a wheel with no second branch is not partial")

        val data = repo.activeData.value
        assertTrue(data.isConnected, "the wheel must be connected")
        // 58.92 V on the 67.2 V scale x (40 x 4.2 / 67.2) = x2.5 = 147.30 V.
        assertEquals(147.30f, data.voltage, 0.01f)
        assertEquals(0f, data.current, 0.001f)
        assertFalse(data.hasCurrent, "the live frame exposes phase current only")
        assertEquals(0f, data.power, 0.5f)
        assertFalse(data.hasPower, "battery power is unknown without battery current")
        // SoC from the scaled voltage: 147.3 / 40 = 3.6825 V/cell -> 36.2 % (OCV curve; linear gave 42.5 %).
        assertEquals(36.16f, data.soc, 0.1f)
        assertEquals(1, data.temperatures.size)
        assertEquals(27.5035f, data.temperatures[0], 0.01f)
        assertTrue(data.cellVoltages.isEmpty(), "a dumb wheel has no cells to show")
    }

    @Test
    fun `a picked T4 - controller plus Begode pack with a typed cell count - shows voltage and SoC`() = repoTest { repo ->
        // The shape pickedControllerVehicle builds for a Begode, with the rider's 24S typed in.
        val t4 = ru.sodovaya.volty.domain.model.wheelVehicle("v1", "T4", "unicycle", ADDRESS, Chemistry.LI_ION_NMC,
            Instant.fromEpochSeconds(0)).let { it.copy(packs = it.packs.map { p -> p.copy(cellCount = 24) }) }
        val wire = Wire(repo, t4)
        // The rider's own capture (tools/begode-emulator): live 0x00 at 0x17A5 = 60.53 V on the 67.2 V
        // scale, plus 0x04, 0x07 and an all-zero 0x01 branch frame, fragmented as the wheel sends them.
        BegodeT4Fixture.notifications.forEach { wire.notify(it) }
        val data = repo.activeData.value
        assertEquals(90.75f, data.voltage, 0.1f)
        assertTrue(data.socKnown, "24S is known: 3.78 V/cell is a charge, not a dash")
    }

    @Test
    fun `a T4 configured as a controller with its battery from the controller learns its count and shows SoC`() = repoTest { repo ->
        // The rider's own profile shape: no stored pack, the Begode controller provides the battery.
        val t4 = ru.sodovaya.volty.domain.model.wheelVehicle("v1", "T4", "unicycle", ADDRESS, Chemistry.LI_ION_NMC,
            Instant.fromEpochSeconds(0)).let { it.copy(packs = emptyList(),
                controllers = it.controllers.map { c -> c.copy(providesDerivedBattery = true) }) }
        val wire = Wire(repo, t4)
        BegodeT4Fixture.notifications.forEach { wire.notify(it) }
        val data = repo.activeData.value
        assertEquals(90.75f, data.voltage, 0.1f)
        // Retraction: the slot behind a Begode was labelled VESC_BMS, which claims its own fuel gauge,
        // so the voltage estimate never ran and the dashboard showed volts without a charge.
        assertTrue(data.socKnown, "the count proven from the wheel's own frames turns volts into a charge")
    }

    private fun t4ControllerBattery(derivedCellCount: Int?) =
        ru.sodovaya.volty.domain.model.wheelVehicle("v1", "T4", "unicycle", ADDRESS, Chemistry.LI_ION_NMC,
            Instant.fromEpochSeconds(0)).let { it.copy(packs = emptyList(),
                controllers = it.controllers.map { c ->
                    c.copy(providesDerivedBattery = true, derivedCellCount = derivedCellCount)
                }) }

    @Test
    fun `a typed count wins over the learned one and clearing it restores the learned one`() = repoTest { repo ->
        val typed = Wire(repo, t4ControllerBattery(derivedCellCount = 20))
        BegodeT4Fixture.notifications.forEach { typed.notify(it) }
        val typedData = repo.activeData.value
        assertTrue(typedData.socKnown)
        // The wheel proves 24S (90.7 V branch vs 60.53 V live); the rider typed 20S. 90.7 V over 20 cells
        // is 4.54 V a cell -- a full pack -- while the learned 24S reads 3.78 V a cell, a mid charge.
        assertTrue(typedData.soc > 99f, "typed 20S rates 4.54 V/cell as full, got ${typedData.soc}")

        val cleared = Wire(repo, t4ControllerBattery(derivedCellCount = null))
        BegodeT4Fixture.notifications.forEach { cleared.notify(it) }
        val learnedData = repo.activeData.value
        assertTrue(learnedData.socKnown)
        assertTrue(learnedData.soc in 1f..90f, "the learned 24S reads 3.78 V/cell, got ${learnedData.soc}")
    }

    @Test
    fun `a controller battery with no typed count falls back to the learned one`() = repoTest { repo ->
        val learned = Wire(repo, t4ControllerBattery(derivedCellCount = null))
        BegodeT4Fixture.notifications.forEach { learned.notify(it) }
        val data = repo.activeData.value
        assertEquals(90.75f, data.voltage, 0.1f, "the learned 24S shows the 0x01 branch voltage")
        assertTrue(data.socKnown)
        assertTrue(data.soc in 1f..99f, "a mid-range charge was estimated from the learned 24S, got ${data.soc}")
    }

    @Test
    fun `a controller-only wheel without branch frames uses the typed count`() = repoTest { repo ->
        val wire = Wire(repo, t4ControllerBattery(derivedCellCount = 24))
        // Only 0x00 live frames: nothing ever teaches the decoder a count.
        wire.notify(liveFrame(voltageRaw = 6053, currentRaw = 0, tempRaw = 0))
        wire.notify(liveFrame(voltageRaw = 6053, currentRaw = 0, tempRaw = 0))
        val data = repo.activeData.value
        assertEquals(90.8f, data.voltage, 0.1f, "60.53 V on the 67.2 V scale x 24S")
        assertTrue(data.socKnown, "the typed 24S is the only source of a count here")
    }

    @Test
    fun `a controller-only wheel without branch frames and without a typed count has no charge`() = repoTest { repo ->
        val wire = Wire(repo, t4ControllerBattery(derivedCellCount = null))
        wire.notify(liveFrame(voltageRaw = 6053, currentRaw = 0, tempRaw = 0))
        wire.notify(liveFrame(voltageRaw = 6053, currentRaw = 0, tempRaw = 0))
        assertFalse(repo.activeData.value.socKnown)
    }

    @Test
    fun `a dumb wheel with no known cell count connects but never fabricates a voltage`() = repoTest { repo ->
        // A brand-new guest profile: no user-set and no auto-filled cell count.
        val wire = Wire(repo, vehicle(cellCount = null))

        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        wire.notify(odometerFrame())

        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.packs.size)
        assertTrue(vd.packs[0].isOnline, "the wheel connects and shows what it has")

        val data = repo.activeData.value
        assertTrue(data.isConnected)
        assertEquals(0f, data.current, 0.001f)
        assertFalse(data.hasCurrent, "the live frame exposes phase current only")
        assertEquals(27.5035f, data.temperatures.single(), 0.01f)
        // The 58.92 V raw reading would render as 59 V on what may be a
        // 168 V pack. Without a cell count there is no honest scale factor:
        // voltage stays 0 ("unknown"), and so do its derivatives.
        assertEquals(0f, data.voltage, 0f, "no fabricated voltage")
        assertEquals(0f, data.power, 0f, "power is voltage-derived")
        assertEquals(0f, data.soc, 0f, "SoC cannot be estimated without a voltage")
    }

    @Test
    fun `a dumb wheel with no cell count publishes an unknown SoC end to end`() = repoTest { repo ->
        // The false-alarm regression this flag exists for: soc = 0 with
        // socKnown = false must reach activeData, so AlertEngine skips the
        // SoC alerts instead of crying "Battery low" on a full wheel.
        val wire = Wire(repo, vehicle(cellCount = null))

        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))

        val data = repo.activeData.value
        assertTrue(data.isConnected)
        assertEquals(0f, data.soc, 0f)
        assertFalse(data.socKnown, "an inestimable SoC must be flagged unknown through the aggregate")
    }

    @Test
    fun `a dumb wheel with a known cell count publishes a known estimated SoC`() = repoTest { repo ->
        val wire = Wire(repo, vehicle(cellCount = 40))

        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))

        val data = repo.activeData.value
        assertEquals(36.16f, data.soc, 0.1f)
        assertTrue(data.socKnown, "the voltage estimate is a real fuel gauge — SoC alerts must work")
    }

    @Test
    fun `the smart-BMS capture publishes a known SoC`() = repoTest { repo ->
        val wire = Wire(repo, vehicle(cellCount = 40))

        BegodeDumpFixture.chunks().forEach { wire.notify(it) }

        val data = repo.activeData.value
        assertEquals(41.24f, data.soc, 0.3f)
        assertTrue(data.socKnown, "a cell-estimated SoC on the smart path stays known")
    }

    @Test
    fun `the smart-BMS capture through the same pipeline is completely unaffected`() = repoTest { repo ->
        val wire = Wire(repo, vehicle(cellCount = 40))

        BegodeDumpFixture.chunks().forEach { wire.notify(it) }

        val vd = repo.activeVehicleData.value
        assertEquals(2, vd.packs.size, "two real branches")
        assertTrue(vd.packs.all { it.isOnline })
        assertFalse(vd.isPartial)
        vd.packs.forEachIndexed { i, p ->
            assertEquals(40, p.data.cellVoltages.size, "branch $i cells")
            // The branch voltage is its CELL SUM (~148.4 V) — not the scaled
            // live-frame value (147.30 V): the synthetic path retired the
            // moment BMS frames appeared and never overrode real data.
            assertTrue(
                p.data.voltage in 148.1f..148.7f,
                "branch $i voltage ${p.data.voltage} must be the real cell sum"
            )
        }

        val data = repo.activeData.value
        assertEquals(80, data.cellVoltages.size, "the aggregate unions both branches")
        assertTrue(data.voltage in 148.1f..148.7f, "parallel mean of the two branch sums")
        assertEquals(0f, data.current, 0.01f, "the captured wheel was stationary")
        // SoC estimated from the CELLS (avg ~3.711 V -> ~41.2 % (OCV curve; linear gave 45.6 %)), not from
        // the voltage/cellCount fallback.
        assertEquals(41.24f, data.soc, 0.3f)
    }

    @Test
    fun `a battery-only wheel decodes motion that has nowhere to go, and the link survives it`() = repoTest { repo ->
        // REGRESSION. BegodeProtocol is now a MotionSource, so `protocol as?
        // MotionSource` succeeds on the link of a vehicle configured as a
        // Begode BATTERY and nothing else — which is every existing Begode
        // profile until its owner adds a controller. That link's plan owns NO
        // controller, so translating local index 0 against ownedControllers
        // threw, inside the session's observe loop, taking the whole link down
        // on the first frame and leaving a rider's batteries reconnecting in a
        // loop. The sample has nowhere to go and is dropped instead.
        val v = vehicle(cellCount = 40)
        repo.installLinksForTest(v, ADDRESS, BmsType.BEGODE)

        val spec = repo.linkSpecsForTest().single()
        assertTrue(
            spec.ownedControllers.isEmpty(),
            "precondition: a battery-only Begode profile plans no controller"
        )
        val protocol = repo.createProtocolForTest(spec, v)
        val source = assertIs<MotionSource>(
            protocol,
            "precondition: the wheel's protocol decodes motion over the battery link"
        )

        // The session's observe loop in miniature, motion side — the real
        // production funnel, the real router, the real gate.
        val funnel = repo.linkMotionFunnelsForTest().single()
        val gate = MotionSampleGate(source.controllerCount)
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        val alive = routeControllerSamples(source, gate) { i, m -> funnel(i, m) }

        assertTrue(alive, "the wheel really did decode motion — that is the whole hazard")
        advanceUntilIdle()
        assertFalse(
            repo.activeMotion.value.isConnected,
            "a link with no planned controller must publish no motion"
        )
        // …and the same notification's BATTERY half decoded normally, so what
        // was dropped is the motion sample and not the frame.
        val battery = assertNotNull(protocol.latestData(0))
        assertEquals(0f, battery.current, 1e-3f, "phase current is not battery current")
        assertFalse(battery.hasCurrent, "the live frame has no battery-current field")
    }

    // --- Energy model through the production pipeline ---

    private class FakeEnergyStore(
        private val seeded: Map<String, EnergyMemory> = emptyMap(),
        private val failLoad: Boolean = false,
    ) : VehicleEnergyStore {
        data class Saved(val id: String, val cells: Int, val chemistry: Chemistry, val memory: EnergyMemory)
        val loads = mutableListOf<Triple<String, Int, Chemistry>>()
        val saves = mutableListOf<Saved>()
        override suspend fun load(vehicleId: String, seriesCells: Int, chemistry: Chemistry): EnergyMemory {
            loads += Triple(vehicleId, seriesCells, chemistry)
            if (failLoad) throw IllegalStateException("disk error")
            return seeded[vehicleId] ?: EnergyMemory.EMPTY
        }
        override suspend fun save(vehicleId: String, seriesCells: Int, chemistry: Chemistry, memory: EnergyMemory) {
            saves += Saved(vehicleId, seriesCells, chemistry, memory)
        }
        override suspend fun peek(vehicleId: String): ru.sodovaya.volty.domain.energy.EnergyRecord? = null
        val resets = mutableListOf<String>()
        override suspend fun reset(vehicleId: String) { resets += vehicleId }
    }

    private fun energyRepoTest(
        store: FakeEnergyStore,
        vehicles: Flow<List<Vehicle>> = flowOf(emptyList()),
        body: suspend TestScope.(KableBmsRepository) -> Unit
    ) = bleRepositoryTest(vehicleRepository = NoopVehicleRepository(vehicles), energyStore = store, body = body)

    /** A 24S dumb wheel at ~3.78 V/cell, fed one live frame every 250 ms of the repository's injected clock. */
    private inner class EnergyRide(val repo: KableBmsRepository, vehicle: Vehicle) {
        var nowMs = 50_000_000L
        val startMs = nowMs
        private var tick = 0
        private val wire: Wire
        init {
            repo.orchestratorClockForTest = { Instant.fromEpochMilliseconds(nowMs) }
            wire = Wire(repo, vehicle)
        }
        fun feed(millis: Long) {
            val until = nowMs + millis
            while (nowMs < until) {
                nowMs += 250
                // Alternate by one raw unit so every frame is NEW data to the sample gate.
                wire.notify(liveFrame(voltageRaw = 6053 + (tick++ % 2), currentRaw = 0, tempRaw = 0))
            }
        }
    }

    @Test
    fun `the published charge is the tracker's, end to end, and memory is saved on disconnect`() = FakeEnergyStore().let { store -> energyRepoTest(store) { repo ->
            val ride = EnergyRide(repo, vehicle(cellCount = 24))
            ride.feed(1_000)
            assertFalse(repo.activeData.value.socKnown, "not seeded yet: unknown, not the sample's voltage estimate")
            advanceUntilIdle() // the memory load lands
            ride.feed(8_000)

            val vd = repo.activeVehicleData.value
            val energy = assertNotNull(vd.energy)
            assertTrue(energy.modelled)
            assertTrue(repo.activeData.value.socKnown)
            // activeData is what AlertEngine reads: it must be the tracker's number.
            assertEquals(assertNotNull(energy.socPercent), repo.activeData.value.soc, 0f)
            assertEquals(energy.socPercent!!, vd.packs[0].data.soc, 0f)

            assertTrue(store.saves.isEmpty(), "nothing is written before the interval or a disconnect")
            repo.disconnect()
            advanceUntilIdle()
            val saved = store.saves.single()
            assertEquals("v1", saved.id)
            assertEquals(24, saved.cells)
            assertEquals(Chemistry.LI_ION_NMC, saved.chemistry)
            assertNotNull(saved.memory.counter, "the live counter is what is persisted")
        } }

    @Test
    fun `memory is saved at most every 30 seconds while connected`() = FakeEnergyStore().let { store -> energyRepoTest(store) { repo ->
        val ride = EnergyRide(repo, vehicle(cellCount = 24))
        ride.feed(1_000); advanceUntilIdle()
        ride.feed(28_000); advanceUntilIdle()
        assertTrue(store.saves.isEmpty(), "29 s in: still inside the interval")
        ride.feed(2_500); advanceUntilIdle()
        assertEquals(1, store.saves.size, "past 30 s: one write")
        ride.feed(20_000); advanceUntilIdle()
        assertEquals(1, store.saves.size, "20 s later: still one")
        ride.feed(12_000); advanceUntilIdle()
        assertEquals(2, store.saves.size, "past the next interval: the second")
    } }

    @Test
    fun `state is per vehicle id and a stored counter and learned capacity reach the tracker`() {
        val vSoc = VoltageSocEstimator.estimateFromPackVoltage(90.8f, 24, Chemistry.LI_ION_NMC, AlertConfig())!!
        val persisted = vSoc + 6f
        val store = FakeEnergyStore(
            mapOf("v1" to EnergyMemory(LearnedCapacity(41f, 40f, 2), null, PersistedCharge(persisted, 1L)))
        )
        energyRepoTest(store) { repo ->
            val a = EnergyRide(repo, vehicle(cellCount = 24))
            a.feed(1_000); advanceUntilIdle()
            a.feed(8_000)
            val ea = assertNotNull(repo.activeVehicleData.value.energy)
            assertEquals(persisted, ea.socPercent!!, 2f, "the stored counter (6 points above the voltage) wins inside the seed tolerance")
            assertEquals(41f, ea.learned?.usableAh)
            assertEquals(CapacitySource.LEARNED, ea.capacitySource)
            repo.disconnect(); advanceUntilIdle()

            val b = EnergyRide(repo, vehicle(cellCount = 24).copy(id = "v2"))
            b.feed(1_000); advanceUntilIdle()
            b.feed(8_000)
            val eb = assertNotNull(repo.activeVehicleData.value.energy)
            assertNull(eb.learned, "another vehicle does not inherit v1's learned capacity")
            assertEquals(CapacitySource.NONE, eb.capacitySource)
            assertEquals(vSoc, eb.socPercent!!, 0.7f, "and seeds from its own voltage")
            assertEquals(
                listOf("v1", "v2"),
                store.loads.map { it.first },
                "each vehicle's memory is loaded under its own id"
            )
            assertEquals(24, store.loads.first().second)
        }
    }

    @Test
    fun `a store whose load throws never has its row overwritten`() {
        val store = FakeEnergyStore(mapOf("v1" to EnergyMemory(LearnedCapacity(41f, 40f, 2), null, null)), failLoad = true)
        energyRepoTest(store) { repo ->
            val ride = EnergyRide(repo, vehicle(cellCount = 24))
            ride.feed(1_000); advanceUntilIdle()
            ride.feed(70_000); advanceUntilIdle() // well past two save intervals
            assertTrue(repo.activeData.value.socKnown, "the model still runs, from empty memory")
            assertEquals(1, store.loads.size, "the load was attempted")
            assertTrue(store.saves.isEmpty(), "no periodic save may replace the stored row after a failed load")
            repo.disconnect(); advanceUntilIdle()
            assertTrue(store.saves.isEmpty(), "nor the disconnect save")
        }
    }

    @Test
    fun `only the winning connection's tracker is saved when a second one was built after it`() {
        val store = FakeEnergyStore()
        energyRepoTest(store) { repo ->
            val ride = EnergyRide(repo, vehicle(cellCount = 24)) // installs the winner
            // The superseded attempt finishes building AFTER the winner (never installed).
            repo.buildOrchestratorForTest(vehicle(cellCount = 24), ADDRESS, BmsType.BEGODE)
            ride.feed(1_000); advanceUntilIdle()
            ride.feed(32_000); advanceUntilIdle()
            assertEquals(1, store.saves.size, "the winner saved on its interval")
            repo.disconnect(); advanceUntilIdle()
            assertEquals(2, store.saves.size, "and on disconnect")
        }
    }

    @Test
    fun `a reset while connected is not undone by the next periodic or disconnect save`() {
        val learned = LearnedCapacity(41f, 40f, 2)
        val store = FakeEnergyStore(mapOf("v1" to EnergyMemory(learned, 0.05f, null)))
        energyRepoTest(store) { repo ->
            val ride = EnergyRide(repo, vehicle(cellCount = 24))
            ride.feed(1_000); advanceUntilIdle()
            ride.feed(8_000)
            assertEquals(learned, repo.activeVehicleData.value.energy?.learned, "precondition: the tracker holds it")

            repo.resetLearnedEnergy("v1"); advanceUntilIdle()
            assertEquals(listOf("v1"), store.resets, "the stored row is reset too")
            ride.feed(32_000); advanceUntilIdle() // past the 30 s interval
            assertTrue(store.saves.isNotEmpty(), "a periodic save happened, so the test can see what it wrote")
            assertTrue(
                store.saves.all { it.memory.learned == null && it.memory.resistanceOhm == null },
                "the periodic save must not restore the reset values: ${store.saves}"
            )
            assertNull(repo.activeVehicleData.value.energy?.learned)
            assertEquals(CapacitySource.NONE, repo.activeVehicleData.value.energy?.capacitySource)
            repo.disconnect(); advanceUntilIdle()
            assertNull(store.saves.last().memory.learned, "nor the disconnect save")
            assertNull(store.saves.last().memory.resistanceOhm)
        }
    }

    @Test
    fun `resetting another vehicle leaves the connected trackers learning alone`() {
        val learned = LearnedCapacity(41f, 40f, 2)
        val store = FakeEnergyStore(mapOf("v1" to EnergyMemory(learned, null, null)))
        energyRepoTest(store) { repo ->
            val ride = EnergyRide(repo, vehicle(cellCount = 24))
            ride.feed(1_000); advanceUntilIdle(); ride.feed(8_000)
            repo.resetLearnedEnergy("someone-else"); advanceUntilIdle()
            ride.feed(1_000)
            assertEquals(learned, repo.activeVehicleData.value.energy?.learned)
        }
    }

    @Test
    fun `a capacity saved while connected reaches the live tracker without a reconnect`() {
        val base = vehicle(cellCount = 24)
        val vehicles = MutableStateFlow(listOf(base))
        energyRepoTest(FakeEnergyStore(), vehicles) { repo ->
            val ride = EnergyRide(repo, base)
            ride.feed(1_000); advanceUntilIdle(); ride.feed(8_000)
            assertEquals(CapacitySource.NONE, repo.activeVehicleData.value.energy?.capacitySource)

            vehicles.value = listOf(base.copy(batteryCapacity = BatteryCapacity(20f, CapacityUnit.AH)))
            advanceUntilIdle()
            ride.feed(1_000)
            val e = assertNotNull(repo.activeVehicleData.value.energy)
            val (low, high) = VoltageSocEstimator.boundsFor(Chemistry.LI_ION_NMC, AlertConfig())
            assertEquals(CapacitySource.ENTERED, e.capacitySource)
            assertEquals(20f * OcvCurve.usableFraction(Chemistry.LI_ION_NMC, low, high), assertNotNull(e.usableAh), 1e-3f)

            // Cleared again: back to nothing, still without a reconnect.
            vehicles.value = listOf(base)
            advanceUntilIdle(); ride.feed(1_000)
            assertEquals(CapacitySource.NONE, repo.activeVehicleData.value.energy?.capacitySource)
        }
    }

    @Test
    fun `cell bounds edited while connected change the usable share but keep the learned capacity`() {
        val learned = LearnedCapacity(41f, 40f, 2)
        val base = vehicle(cellCount = 24).copy(batteryCapacity = BatteryCapacity(20f, CapacityUnit.AH))
        val vehicles = MutableStateFlow(listOf(base))
        energyRepoTest(FakeEnergyStore(mapOf("v1" to EnergyMemory(learned, null, null))), vehicles) { repo ->
            val ride = EnergyRide(repo, base)
            ride.feed(1_000); advanceUntilIdle(); ride.feed(8_000)
            val before = assertNotNull(repo.activeVehicleData.value.energy?.usableAh)
            vehicles.value = listOf(base.copy(alertConfig = AlertConfig(cellHighV = 4.1f, cellLowV = 3.4f)))
            advanceUntilIdle(); ride.feed(1_000)
            val e = assertNotNull(repo.activeVehicleData.value.energy)
            assertEquals(20f * OcvCurve.usableFraction(Chemistry.LI_ION_NMC, 3.4f, 4.1f), assertNotNull(e.usableAh), 1e-3f)
            assertTrue(e.usableAh != before)
            assertEquals(learned, e.learned, "a bounds change does not unlearn a pack")
        }
    }

    @Test
    fun `a chemistry change while connected drops the engine and loads under the new key`() {
        val base = vehicle(cellCount = 24)
        val vehicles = MutableStateFlow(listOf(base))
        val store = FakeEnergyStore()
        energyRepoTest(store, vehicles) { repo ->
            val ride = EnergyRide(repo, base)
            ride.feed(1_000); advanceUntilIdle(); ride.feed(8_000)
            assertEquals(listOf(Chemistry.LI_ION_NMC), store.loads.map { it.third })
            vehicles.value = listOf(base.copy(chemistry = Chemistry.LIFEPO4))
            advanceUntilIdle(); ride.feed(1_000); advanceUntilIdle()
            assertEquals(listOf(Chemistry.LI_ION_NMC, Chemistry.LIFEPO4), store.loads.map { it.third })
            repo.disconnect(); advanceUntilIdle()
            assertEquals(Chemistry.LIFEPO4, store.saves.last().chemistry)
        }
    }

    // --- Synthetic frame builders (24 bytes, layout as BegodeNoBmsProtocolTest) ---

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    private fun liveFrame(voltageRaw: Int, currentRaw: Int = 0, tempRaw: Int = 0): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[8] = (currentRaw shr 8).toByte(); p[9] = currentRaw.toByte()
        p[10] = (tempRaw shr 8).toByte(); p[11] = tempRaw.toByte()
        return frame(0x00, 24, p)
    }

    private fun odometerFrame(): ByteArray {
        val p = ByteArray(16)
        p[0] = 0x00; p[1] = 0x82.toByte(); p[2] = 0xB2.toByte(); p[3] = 0x5D
        return frame(0x04, 24, p)
    }
}
