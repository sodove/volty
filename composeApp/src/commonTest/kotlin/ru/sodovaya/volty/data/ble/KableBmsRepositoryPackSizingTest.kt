package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID
import ru.sodovaya.volty.domain.model.GUEST_VEHICLE_ID_PREFIX
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.bmsAddress
import ru.sodovaya.volty.domain.model.bmsType
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.PackAggregator
import ru.sodovaya.volty.presentation.common.groupPackCells
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A Begode wheel multiplexes TWO battery branches over ONE BLE link, but the
 * saved vehicle is created with a single pack. Sizing the orchestrator to the
 * stored vehicle therefore dropped every sample for branch 1 — and since the
 * branches are parallel and near-identical, the dashboard silently showed HALF
 * the wheel's current and power instead of an obviously missing pack.
 *
 * These tests pin: the orchestrator is sized to the PROTOCOL, the extra slot
 * really accepts samples, a single-pack BMS is untouched, and the discovered
 * pack list is persisted on exactly the terms
 * [KableBmsRepository.maybePersistCellCount] already established.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryPackSizingTest {

    private class RecordingVehicleRepository : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private val vehicleRepo = RecordingVehicleRepository()
    private var underTest: KableBmsRepository? = null

    @AfterTest
    fun tearDown() {
        underTest?.close()
        underTest = null
    }

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = vehicleRepo,
        serviceStart = {},
        serviceStop = {},
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
    )

    private fun vehicle(
        id: String = "v1",
        name: String = "Falcon",
        type: BmsType = BmsType.BEGODE,
    ) = singlePackVehicle(
        id = id,
        name = name,
        iconKey = "unicycle",
        bmsType = type,
        bmsAddress = ADDRESS,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    // --- Sizing the orchestrator to the protocol ---

    @Test
    fun `a one-pack Begode vehicle gets a second pack slot`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        assertEquals(1, v.packs.size, "precondition: the stored vehicle has one pack")

        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)

        assertEquals(2, packs.size, "BegodeProtocol reports packCount = 2")
        assertEquals(listOf(0, 1), packs.map { it.index })
        // A Begode multiplexes both branches over ONE BLE link, so the
        // synthesised pack genuinely shares type and address with the real one.
        assertEquals(BmsType.BEGODE, packs[1].bmsType)
        assertEquals(ADDRESS, packs[1].bmsAddress)
        // Two packs both called "Falcon" would be useless in the UI.
        assertEquals("Falcon", packs[0].label)
        assertNotEquals(packs[0].label, packs[1].label, "synthesised pack needs its own name")
        assertEquals("Pack 2", packs[1].label)
    }

    @Test
    fun `a sample for the synthesised pack is not dropped`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)

        val emitted = mutableListOf<VehicleData>()
        val orchestrator = VehicleConnection(
            packs = packs,
            topology = PackTopology.PARALLEL,
            onVehicleData = { emitted += it }
        )

        // Before the fix this hit VehicleConnection's unknown-index path:
        // no state change, no emission, sample silently gone.
        val snap = orchestrator.submit(1, BmsData(voltage = 148.4f, current = 8.85f, isConnected = true))

        assertEquals(2, snap.packs.size)
        assertTrue(snap.packs[1].isOnline, "branch 1 must come online")
        assertTrue(emitted.isNotEmpty(), "submit(1) must emit a vehicle snapshot")
    }

    @Test
    fun `two parallel branches aggregate to the wheel's real current`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)
        val orchestrator = VehicleConnection(
            packs = packs,
            topology = PackTopology.PARALLEL,
            onVehicleData = {}
        )

        // Ground truth from a reference app on the same wheel: 17.70 A total,
        // 2748.8 W total — each branch carrying 8.85 A at 155.3 V.
        orchestrator.submit(0, BmsData(voltage = 155.3f, current = 8.85f, power = 155.3f * 8.85f, isConnected = true))
        val snap = orchestrator.submit(1, BmsData(voltage = 155.3f, current = 8.85f, power = 155.3f * 8.85f, isConnected = true))

        assertEquals(17.70f, snap.aggregate.current, absoluteTolerance = 0.01f)
        assertEquals(2748.8f, snap.aggregate.power, absoluteTolerance = 1.0f)
        // Parallel branches: the voltage is the mean, NOT the sum.
        assertEquals(155.3f, snap.aggregate.voltage, absoluteTolerance = 0.01f)
    }

    // --- SoC estimation through the real sample funnel ---

    @Test
    fun `estimated SoC survives the synthesised Begode branch instead of halving`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        // Stored single-pack Begode vehicle: the protocol reports two branches,
        // so slot 1 is synthesised at connect time and the estimator must be
        // able to find it. This drives the SAME funnel a live session uses
        // (via the shared buildSamplePipeline), not a hand-built aggregate.
        val v = vehicle()
        assertEquals(1, v.packs.size, "precondition: the stored vehicle has one pack")
        val funnel = repo.installSampleFunnelForTest(v, v.bmsAddress, v.bmsType)

        // A Begode reports no SoC (soc = 0 on every sample). 40 cells at
        // 3.93 V map linearly between LI_ION_NMC's emptyCellV = 3.30 and
        // defaultHighV = 4.20: (3.93 - 3.30) / 0.90 = 70 %.
        val branch = BmsData(
            voltage = 40 * 3.93f,
            current = 8.85f,
            cellVoltages = List(40) { 3.93f },
            isConnected = true
        )
        funnel(0, branch, emptyList())
        funnel(1, branch, emptyList())

        val snap = repo.activeVehicleData.value
        assertEquals(2, snap.packs.size, "precondition: both branches routed")
        assertTrue(snap.packs.all { it.isOnline }, "precondition: both branches online")
        // The regression: the estimator was handed the STORED one-pack vehicle,
        // so branch 1 kept soc = 0 and the parallel mean showed HALF (35 %).
        assertEquals(70.0f, repo.activeData.value.soc, absoluteTolerance = 0.1f)
        // Both per-pack values must carry the estimate — the aggregate being
        // right for the wrong reason (e.g. one branch at 140 %) would slip by
        // an aggregate-only assertion.
        snap.packs.forEach { p ->
            assertEquals(70.0f, p.data.soc, absoluteTolerance = 0.1f)
        }
    }

    @Test
    fun `sections travel the production funnel into the pack state`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        // The exact funnel doConnect wires into a ConnectionSession — sections
        // enter beside the sample and must come out on PackState.sections.
        val funnel = repo.installSampleFunnelForTest(v, v.bmsAddress, v.bmsType)

        val cells = List(20) { 3.705f } + List(20) { 3.71f }
        val sections = listOf(
            SectionState(index = 0, voltage = 74.1f, temperatures = listOf(28f, 25f), cellRange = 0..19),
            SectionState(index = 1, voltage = 74.2f, temperatures = listOf(28f, 25f), cellRange = 20..39)
        )
        funnel(0, BmsData(voltage = 148.3f, cellVoltages = cells, isConnected = true), sections)
        funnel(1, BmsData(voltage = 148.3f, cellVoltages = cells, isConnected = true), emptyList())

        val snap = repo.activeVehicleData.value
        assertEquals(sections, snap.packs[0].sections, "branch 0 must publish its breakdown")
        assertTrue(snap.packs[1].sections.isEmpty(), "branch 1 reported none and must show none")

        // And what came through satisfies the UI's grouping predicate — the
        // whole point of carrying the sections this far.
        val groups = groupPackCells(snap.packs[0])
        assertEquals(2, groups.size, "authoritative ranges must group")
        assertEquals(listOf(0, 20), groups.map { it.startIndex })
    }

    @Test
    fun `a single-pack BMS is left exactly as stored`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle(type = BmsType.JK_BMS)

        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)

        assertEquals(1, packs.size, "JK is a single-pack BMS — nothing to synthesise")
        assertEquals(v.packs, packs, "the stored pack must pass through untouched")
    }

    @Test
    fun `a guest connection with no vehicle still gets both Begode slots`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val packs = repo.connectionPacksForTest(null, ADDRESS, BmsType.BEGODE)
        assertEquals(2, packs.size)
        assertTrue(packs.all { it.bmsAddress == ADDRESS })
    }

    // --- Persisting the discovered packs ---

    /** Vehicle data where every pack of [packs] is online with real numbers. */
    private fun liveVehicleData(packs: List<Pack>, onlineCount: Int = packs.size): VehicleData =
        PackAggregator.build(
            packs.mapIndexed { i, p ->
                PackState(
                    pack = p,
                    data = BmsData(voltage = 148.4f, current = 8.85f, isConnected = true),
                    isOnline = i < onlineCount,
                    lastSeenAt = if (i < onlineCount) Clock.System.now() else null
                )
            },
            PackTopology.PARALLEL
        )

    @Test
    fun `the discovered pack list is persisted once the extra pack reports`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)

        repo.emitVehicleDataForTest(liveVehicleData(packs))
        runCurrent()

        assertEquals(1, vehicleRepo.upserts.size, "exactly one pack-list upsert expected")
        val saved = vehicleRepo.upserts.single()
        assertEquals(v.id, saved.id)
        assertEquals(2, saved.packs.size)
        assertEquals(listOf("Falcon", "Pack 2"), saved.packs.map { it.label })
    }

    @Test
    fun `packs are not persisted while the extra pack is still silent`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)

        // Branch 0 streaming, branch 1 never seen: the second slot is a guess
        // until it produces data, and a guess must not reach the database.
        repeat(5) {
            repo.emitVehicleDataForTest(liveVehicleData(packs, onlineCount = 1))
            runCurrent()
        }

        assertTrue(vehicleRepo.upserts.isEmpty(), "an unproven pack must not be persisted")
    }

    @Test
    fun `persistence happens once, not on every sample`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)

        repeat(20) {
            repo.emitVehicleDataForTest(liveVehicleData(packs))
            runCurrent()
        }

        assertEquals(1, vehicleRepo.upserts.size, "the pack list must be written exactly once")
    }

    @Test
    fun `a vehicle that already stores both packs is not rewritten`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        val packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType)
        val twoPackVehicle = v.copy(packs = packs)
        repo.primeConnectedForTest(
            twoPackVehicle,
            twoPackVehicle.bmsAddress,
            twoPackVehicle.bmsType,
            Clock.System.now().toEpochMilliseconds()
        )
        runCurrent()

        repeat(5) {
            repo.emitVehicleDataForTest(liveVehicleData(packs))
            runCurrent()
        }

        assertTrue(vehicleRepo.upserts.isEmpty(), "nothing new was discovered — no write")
    }

    @Test
    fun `cell count auto-fill stores one branch, not both branches merged`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()
        // Already stores both packs, so only the cell-count auto-fill can fire.
        val twoPackVehicle = v.copy(packs = repo.connectionPacksForTest(v, v.bmsAddress, v.bmsType))
        repo.primeConnectedForTest(
            twoPackVehicle,
            twoPackVehicle.bmsAddress,
            twoPackVehicle.bmsType,
            Clock.System.now().toEpochMilliseconds()
        )
        runCurrent()

        val branchCells = List(40) { 3.71f }
        val vd = PackAggregator.build(
            twoPackVehicle.packs.map {
                PackState(
                    pack = it,
                    data = BmsData(voltage = 148.4f, cellVoltages = branchCells, isConnected = true),
                    isOnline = true,
                    lastSeenAt = Clock.System.now()
                )
            },
            PackTopology.PARALLEL
        )
        assertEquals(80, vd.aggregate.cellVoltages.size, "precondition: the aggregate unions both branches")

        repeat(3) { i ->
            // Production order: the orchestrator publishes the vehicle snapshot
            // from inside submit(), then the sample reaches _activeData. The
            // voltage varies so the StateFlow does not conflate the samples.
            repo.emitVehicleDataForTest(vd)
            repo.emitActiveDataForTest(vd.aggregate.copy(voltage = 148.4f + i))
            runCurrent()
        }

        // A 40S wheel must not be stored as "80s" just because it has two
        // branches — the profile's cell count describes ONE pack.
        assertEquals(listOf(40), vehicleRepo.upserts.map { it.packs.first().cellCount })
    }

    @Test
    fun `guest vehicles are never persisted`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val guest = vehicle(id = "${GUEST_VEHICLE_ID_PREFIX}$ADDRESS")
        repo.primeConnectedForTest(guest, guest.bmsAddress, guest.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val packs = repo.connectionPacksForTest(guest, guest.bmsAddress, guest.bmsType)

        repeat(5) {
            repo.emitVehicleDataForTest(liveVehicleData(packs))
            runCurrent()
        }

        assertTrue(vehicleRepo.upserts.isEmpty(), "guests are transient — no writes")
    }

    @Test
    fun `the demo vehicle is never persisted`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val demo = vehicle(id = DEMO_VEHICLE_ID)
        repo.primeConnectedForTest(demo, demo.bmsAddress, demo.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val packs = repo.connectionPacksForTest(demo, demo.bmsAddress, demo.bmsType)

        repeat(5) {
            repo.emitVehicleDataForTest(liveVehicleData(packs))
            runCurrent()
        }

        assertTrue(vehicleRepo.upserts.isEmpty(), "demo has no real device behind it — no writes")
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
