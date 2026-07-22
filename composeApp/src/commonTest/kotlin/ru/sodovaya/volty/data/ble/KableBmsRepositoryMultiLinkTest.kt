package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsAddress
import ru.sodovaya.volty.domain.model.bmsType
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Multi-link orchestration (sub-project A, Task 3): one BLE link per distinct
 * pack address, one shared sample funnel, the vehicle's [ConnectionState]
 * folded over the links' individual states, and one independent reconnect
 * loop per link.
 *
 * Everything runs on fakes — no real BLE. The seams mirror the single-link
 * test seams: [KableBmsRepository.installLinksForTest] installs the exact
 * production wiring (links, orchestrator, channel, consumer) and returns each
 * link's session funnel; the drop / online / failed transitions drive the
 * REAL fold and reconnect pathways, exactly as a [ConnectionSession] would.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryMultiLinkTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private var underTest: KableBmsRepository? = null

    @AfterTest
    fun tearDown() {
        underTest?.close()
        underTest = null
    }

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = StubVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
    )

    /** Two independent BMS at two distinct addresses — the sub-project's raison d'être. */
    private fun twoLinkVehicle(): Vehicle = Vehicle(
        id = "v-multi",
        name = "Rig",
        iconKey = "battery",
        packs = listOf(
            Pack(index = 0, label = "Main", bmsType = BmsType.ANT_BMS, bmsAddress = ADDR_A),
            Pack(index = 1, label = "Aux", bmsType = BmsType.JBD_BMS, bmsAddress = ADDR_B)
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun sample(current: Float, voltage: Float = 74.0f) =
        BmsData(voltage = voltage, current = current, isConnected = true)

    // ----- Fan-out + the shared funnel -----

    @Test
    fun `two links interleaved from separate coroutines aggregate both branches`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoLinkVehicle()
        val funnels = repo.installLinksForTest(v, v.bmsAddress, v.bmsType)
        assertEquals(2, funnels.size, "two distinct addresses must raise two links")
        assertEquals(2, repo.linkCountForTest())
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkOnlineForTest(ADDR_B)

        // Two sessions feed the one channel from separate coroutines; each
        // link's funnel translates ITS local pack index 0 to the global one.
        launch { repeat(3) { i -> funnels[0](0, sample(current = 8.85f, voltage = 74.0f + i * 0.01f), emptyList()) } }
        launch { repeat(3) { i -> funnels[1](0, sample(current = 4.20f, voltage = 74.0f + i * 0.01f), emptyList()) } }
        advanceUntilIdle()

        val snap = repo.activeVehicleData.value
        assertEquals(2, snap.packs.size)
        assertTrue(snap.packs[0].isOnline, "global pack 0 must be fed by link A")
        assertTrue(snap.packs[1].isOnline, "global pack 1 must be fed by link B")
        assertEquals(8.85f, snap.packs[0].data.current, absoluteTolerance = 0.001f)
        assertEquals(4.20f, snap.packs[1].data.current, absoluteTolerance = 0.001f)
        // Parallel branches: the aggregate current is the SUM of both links.
        assertEquals(13.05f, snap.aggregate.current, absoluteTolerance = 0.001f)
        assertFalse(snap.isPartial)
        assertEquals(13.05f, repo.activeData.value.current, absoluteTolerance = 0.001f)
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)
    }

    @Test
    fun `a Begode link owns both branches through one address`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = singlePackVehicle(
            id = "v-begode", name = "Wheel", iconKey = "unicycle",
            bmsType = BmsType.BEGODE, bmsAddress = ADDR_A,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        val funnels = repo.installLinksForTest(v, v.bmsAddress, v.bmsType)
        assertEquals(1, funnels.size, "one address, packCount = 2 — still ONE link")

        // The session speaks LOCAL index 1; the link owns global [0, 1].
        funnels[0](1, BmsData(voltage = 148.4f, current = 8.85f, isConnected = true), emptyList())

        val snap = repo.activeVehicleData.value
        assertEquals(2, snap.packs.size, "branch 1 must materialise on global index 1")
        assertTrue(snap.packs[1].isOnline)
        assertEquals(8.85f, snap.packs[1].data.current, absoluteTolerance = 0.001f)
    }

    // ----- The state fold -----

    @Test
    fun `first link online is Connected and partial until the second lands`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoLinkVehicle()
        val funnels = repo.installLinksForTest(v, v.bmsAddress, v.bmsType)
        assertTrue(repo.connectionState.value is ConnectionState.Connecting)

        repo.markLinkOnlineForTest(ADDR_A)
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)

        funnels[0](0, sample(current = 8.85f), emptyList())
        val partial = repo.activeVehicleData.value
        assertTrue(partial.isPartial, "the second link has not landed — the vehicle is partial")
        assertTrue(partial.packs[0].isOnline)
        assertFalse(partial.packs[1].isOnline)
        assertEquals(8.85f, partial.aggregate.current, absoluteTolerance = 0.001f)

        repo.markLinkOnlineForTest(ADDR_B)
        funnels[1](0, sample(current = 4.20f), emptyList())
        val full = repo.activeVehicleData.value
        assertFalse(full.isPartial)
        assertEquals(13.05f, full.aggregate.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun `all links failing the initial connect folds to Failed`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoLinkVehicle()
        repo.installLinksForTest(v, v.bmsAddress, v.bmsType)

        repo.markLinkFailedForTest(ADDR_A, "Device not found")
        assertTrue(
            repo.connectionState.value is ConnectionState.Connecting,
            "one link still trying — not Failed yet"
        )
        repo.markLinkFailedForTest(ADDR_B, "Device not found")
        assertEquals(ConnectionState.Failed("Device not found"), repo.connectionState.value)
        assertNull(repo.linkReconnectJobForTest(ADDR_A))
        assertNull(repo.linkReconnectJobForTest(ADDR_B))
    }

    // ----- Per-link reconnect -----

    @Test
    fun `a dropped link leaves the vehicle Connected and only its own loop running`() = runTest {
        var nowMs = 1_000_000L
        val repo = newRepo(this).also { underTest = it }
        repo.orchestratorClockForTest = { Instant.fromEpochMilliseconds(nowMs) }
        val v = twoLinkVehicle()
        val funnels = repo.installLinksForTest(v, v.bmsAddress, v.bmsType)
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkOnlineForTest(ADDR_B)
        funnels[0](0, sample(current = 8.85f), emptyList())
        funnels[1](0, sample(current = 4.20f), emptyList())
        assertFalse(repo.activeVehicleData.value.isPartial)

        repo.simulateLinkDropForTest(ADDR_B, "Link dropped")
        runCurrent()

        // The vehicle stays Connected on the surviving link; only the dropped
        // link's reconnect loop runs.
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)
        assertNull(repo.linkReconnectJobForTest(ADDR_A), "the healthy link must not be disturbed")
        val jobB = assertNotNull(repo.linkReconnectJobForTest(ADDR_B))
        assertTrue(jobB.isActive)

        // Link A keeps sampling; past packOfflineAfterMs the staleness sweep
        // takes link B's pack offline and the vehicle turns partial.
        nowMs += BleConfig.packOfflineAfterMs + 1_000L
        funnels[0](0, sample(current = 8.85f), emptyList())
        val snap = repo.activeVehicleData.value
        assertTrue(snap.isPartial)
        assertFalse(snap.packs[1].isOnline)
        assertEquals(8.85f, snap.aggregate.current, absoluteTolerance = 0.001f)
        assertTrue(repo.connectionState.value is ConnectionState.Connected)

        repo.disconnect()
        runCurrent()
        assertFalse(jobB.isActive, "disconnect must stop the dropped link's loop")
    }

    @Test
    fun `both links down folds to Reconnecting`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoLinkVehicle()
        repo.installLinksForTest(v, v.bmsAddress, v.bmsType)
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkOnlineForTest(ADDR_B)

        repo.simulateLinkDropForTest(ADDR_A, "Link dropped")
        repo.simulateLinkDropForTest(ADDR_B, "Link dropped")
        runCurrent()

        val st = repo.connectionState.value
        assertTrue(st is ConnectionState.Reconnecting, "expected Reconnecting, was $st")
        assertEquals("Link dropped", st.reason)
        assertTrue(assertNotNull(repo.linkReconnectJobForTest(ADDR_A)).isActive)
        assertTrue(assertNotNull(repo.linkReconnectJobForTest(ADDR_B)).isActive)

        repo.disconnect()
        runCurrent()
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
    }

    @Test
    fun `initial partial connect keeps the vehicle Connected while the missing link retries`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoLinkVehicle()
        repo.installLinksForTest(v, v.bmsAddress, v.bmsType)

        // Link A answered, link B was out of radio range at connect time.
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkFailedForTest(ADDR_B, "Device not found")
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)

        // The production initial-partial tail pushes the missing link into a
        // background retry instead of failing the connect.
        repo.settleInitialPartialForTest()
        val jobB = assertNotNull(repo.linkReconnectJobForTest(ADDR_B), "the missing link must retry")
        assertTrue(jobB.isActive)
        assertNull(repo.linkReconnectJobForTest(ADDR_A))

        // Its failing retry attempts must not disturb the connected vehicle.
        runCurrent()
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)

        repo.disconnect()
        runCurrent()
        assertFalse(jobB.isActive)
    }

    // ----- Degenerate single-link vehicle -----

    @Test
    fun `a single-address vehicle raises exactly one link with identity routing`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = singlePackVehicle(
            id = "v-one", name = "Solo", iconKey = "scooter",
            bmsType = BmsType.JK_BMS, bmsAddress = ADDR_A,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        val funnels = repo.installLinksForTest(v, v.bmsAddress, v.bmsType)
        assertEquals(1, funnels.size)
        assertEquals(1, repo.linkCountForTest())

        repo.markLinkOnlineForTest(ADDR_A)
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)

        funnels[0](0, sample(current = 8.85f, voltage = 83.16f), emptyList())
        val snap = repo.activeVehicleData.value
        assertEquals(1, snap.packs.size)
        assertTrue(snap.packs[0].isOnline)
        assertFalse(snap.isPartial)
        assertEquals(8.85f, repo.activeData.value.current, absoluteTolerance = 0.001f)
        assertEquals(83.16f, repo.activeData.value.voltage, absoluteTolerance = 0.001f)
    }

    private companion object {
        const val ADDR_A = "AA:BB:CC:DD:EE:01"
        const val ADDR_B = "AA:BB:CC:DD:EE:02"
    }
}
