package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private class NoopVehicleRepository : VehicleRepository {
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
        vehicleRepository = NoopVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
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
    fun `a dumb wheel with a known cell count shows scaled voltage, SoC, current and temperature`() = runTest {
        val repo = newRepo(this).also { underTest = it }
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
        assertEquals(-3.5f, data.current, 0.001f)
        assertEquals(147.30f * -3.5f, data.power, 0.5f)
        // SoC from the scaled voltage: 147.3 / 40 = 3.6825 V/cell -> 42.5 %.
        assertEquals(42.5f, data.soc, 0.1f)
        assertEquals(1, data.temperatures.size)
        assertEquals(27.5035f, data.temperatures[0], 0.01f)
        assertTrue(data.cellVoltages.isEmpty(), "a dumb wheel has no cells to show")
    }

    @Test
    fun `a dumb wheel with no known cell count connects but never fabricates a voltage`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        // A brand-new guest profile: no user-set and no auto-filled cell count.
        val wire = Wire(repo, vehicle(cellCount = null))

        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        wire.notify(odometerFrame())

        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.packs.size)
        assertTrue(vd.packs[0].isOnline, "the wheel connects and shows what it has")

        val data = repo.activeData.value
        assertTrue(data.isConnected)
        assertEquals(-3.5f, data.current, 0.001f)
        assertEquals(27.5035f, data.temperatures.single(), 0.01f)
        // The 58.92 V raw reading would render as 59 V on what may be a
        // 168 V pack. Without a cell count there is no honest scale factor:
        // voltage stays 0 ("unknown"), and so do its derivatives.
        assertEquals(0f, data.voltage, 0f, "no fabricated voltage")
        assertEquals(0f, data.power, 0f, "power is voltage-derived")
        assertEquals(0f, data.soc, 0f, "SoC cannot be estimated without a voltage")
    }

    @Test
    fun `a dumb wheel with no cell count publishes an unknown SoC end to end`() = runTest {
        // The false-alarm regression this flag exists for: soc = 0 with
        // socKnown = false must reach activeData, so AlertEngine skips the
        // SoC alerts instead of crying "Battery low" on a full wheel.
        val repo = newRepo(this).also { underTest = it }
        val wire = Wire(repo, vehicle(cellCount = null))

        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))

        val data = repo.activeData.value
        assertTrue(data.isConnected)
        assertEquals(0f, data.soc, 0f)
        assertFalse(data.socKnown, "an inestimable SoC must be flagged unknown through the aggregate")
    }

    @Test
    fun `a dumb wheel with a known cell count publishes a known estimated SoC`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val wire = Wire(repo, vehicle(cellCount = 40))

        wire.notify(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))

        val data = repo.activeData.value
        assertEquals(42.5f, data.soc, 0.1f)
        assertTrue(data.socKnown, "the voltage estimate is a real fuel gauge — SoC alerts must work")
    }

    @Test
    fun `the smart-BMS capture publishes a known SoC`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val wire = Wire(repo, vehicle(cellCount = 40))

        BegodeDumpFixture.chunks().forEach { wire.notify(it) }

        val data = repo.activeData.value
        assertEquals(45.6f, data.soc, 0.3f)
        assertTrue(data.socKnown, "a cell-estimated SoC on the smart path stays known")
    }

    @Test
    fun `the smart-BMS capture through the same pipeline is completely unaffected`() = runTest {
        val repo = newRepo(this).also { underTest = it }
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
        // SoC estimated from the CELLS (avg ~3.711 V -> ~45.6 %), not from
        // the voltage/cellCount fallback.
        assertEquals(45.6f, data.soc, 0.3f)
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
