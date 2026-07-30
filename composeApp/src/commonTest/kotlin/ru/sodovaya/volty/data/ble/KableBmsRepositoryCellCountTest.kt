package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.GUEST_VEHICLE_ID_PREFIX
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.cellCountOrNull
import ru.sodovaya.volty.domain.model.wheelVehicle
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The profile's cell count is an auto-filled cache of live telemetry (see
 * [KableBmsRepository.maybePersistCellCount]): once a candidate is CONFIRMED,
 * the repo writes it back into the saved vehicle. Guests/demo are transient
 * and must never be persisted.
 *
 * **Two routes to "confirmed", by protocol (Task 3, post-review redesign).**
 * The first version of this gate tried to recompute a completeness ratio
 * from published [BmsData] (`cellVoltages.sum()` against `voltage`) — review
 * caught that this is unsound: [BegodeProtocol]'s own `branchVoltage`
 * substitutes the cell sum into the published `voltage` once the sum clears
 * its LOOSE 90 % cutoff, so a ratio recomputed from the PUBLISHED sample
 * reduces to that same loose cutoff no matter what threshold is used — a
 * stuck 36-of-40 branch (ratio 0.9076) sails through. So this file's Begode
 * tests below drive a REAL [BegodeProtocol] with real notification bytes
 * (mirroring `BegodeProtocolTest.aThirtySixOfFortyRunMustNeverLatchACellCount`)
 * and prove the repo asks [BegodeProtocol.derivedCellCount] rather than
 * recomputing anything from the sample it publishes — a hand-built sample
 * cannot exercise this distinction, only a real decode can.
 *
 * Every OTHER BMS type keeps the ORIGINAL rule, unconditionally: three
 * consecutive samples with an identical cell-list size, no ratio at all — the
 * `singlePackVehicle`/JK_BMS tests below never install a real protocol, so
 * they exercise exactly that original path.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryCellCountTest {

    private class RecordingVehicleRepository : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        // Explicit, because VehicleRepository.updateGaugePeaks is abstract: no fake gets a
        // silent default. Nothing in this file rides a learned dial range (G §9.2).
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    private val vehicleRepo = RecordingVehicleRepository()
    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = vehicleRepo,
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    // -----------------------------------------------------------------------
    // Every other BMS type: stability only, exactly as before Task 3
    // -----------------------------------------------------------------------

    private fun vehicle(id: String = "v1", cellCount: Int? = null) = singlePackVehicle(
        id = id,
        name = "Test",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        cellCount = cellCount,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun sample(cells: Int, voltage: Float) = BmsData(
        voltage = voltage,
        cellVoltages = List(cells) { 3.3f },
        isConnected = true
    )

    /** Emit [count] distinct samples, draining the dispatcher after each. */
    private fun TestScope.emitSamples(repo: KableBmsRepository, cells: Int, count: Int) {
        repeat(count) { i ->
            repo.emitActiveDataForTest(sample(cells, voltage = 13f + i))
            runCurrent()
        }
    }

    @Test
    fun `stable cell count is persisted into the saved vehicle`() = repoTest { repo ->
        val v = vehicle(cellCount = null)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        emitSamples(repo, cells = 4, count = 3)

        assertEquals(1, vehicleRepo.upserts.size, "exactly one auto-fill upsert expected")
        assertEquals(4, vehicleRepo.upserts.single().cellCountOrNull)
        assertEquals(v.id, vehicleRepo.upserts.single().id)
    }

    @Test
    fun `unstable cell count is not persisted`() = repoTest { repo ->
        val v = vehicle(cellCount = null)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        // Daly-style mid-cycle partial lists: 3, 6, 9 — never the same twice.
        repo.emitActiveDataForTest(sample(3, 13f)); runCurrent()
        repo.emitActiveDataForTest(sample(6, 14f)); runCurrent()
        repo.emitActiveDataForTest(sample(9, 15f)); runCurrent()

        assertTrue(vehicleRepo.upserts.isEmpty(), "partial counts must not be persisted")
    }

    @Test
    fun `matching profile count is not re-persisted`() = repoTest { repo ->
        val v = vehicle(cellCount = 4)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        emitSamples(repo, cells = 4, count = 5)

        assertTrue(vehicleRepo.upserts.isEmpty(), "no upsert when the profile already matches")
    }

    @Test
    fun `guest vehicles are never persisted`() = repoTest { repo ->
        val guest = vehicle(id = "${GUEST_VEHICLE_ID_PREFIX}AA:BB", cellCount = null)
        repo.primeConnectedForTest(guest, guest.primaryAddress, guest.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        emitSamples(repo, cells = 4, count = 5)

        assertTrue(vehicleRepo.upserts.isEmpty(), "guests are transient — no auto-fill writes")
    }

    // -----------------------------------------------------------------------
    // Begode: ask the REAL decoder, driven by real notification bytes
    // -----------------------------------------------------------------------

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:00"
    }

    private fun wheel(id: String) = wheelVehicle(
        id = id,
        name = "ET Max",
        iconKey = "unicycle",
        address = ADDRESS,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    /**
     * The session's observe loop in miniature, matching
     * `KableBmsRepositoryDumbBegodeTest.Wire` and driven by the exact byte
     * layout `BegodeProtocolTest`'s own fixtures use: decode the raw
     * notification through a REAL [BegodeProtocol], then route its gated pack
     * samples into the repo's production funnel — the only way this file can
     * prove the repo asks the decoder rather than recomputing a judgement
     * from a hand-built sample.
     */
    private class Wire(repo: KableBmsRepository, vehicle: Vehicle?) {
        private val pipeline = repo.installProtocolPipelineForTest(vehicle, ADDRESS, BmsType.BEGODE)
        val protocol = pipeline.first as BegodeProtocol
        private val gate = PackSampleGate(protocol.packCount)
        fun notify(bytes: ByteArray) {
            protocol.onNotification(bytes)
            routePackSamples(protocol, gate) { i, bms, sections ->
                pipeline.second(i, bms, sections)
            }
        }
    }

    // --- Synthetic frame builders, byte-for-byte the same layout
    // BegodeProtocolTest uses (24 bytes: 55 AA + 16 payload + type + subtype + 5A x4) ---

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    private fun liveFrame(voltageRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        return frame(0x00, 24, p)
    }

    /** 0x01 telemetry frame: pack voltage at payload bytes 4..5 (BE). */
    private fun telemetryFrame(bmsnum: Int, packVoltageRaw: Int, t1: Int, t2: Int, sectionVoltageRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[4] = (packVoltageRaw shr 8).toByte(); p[5] = packVoltageRaw.toByte()
        p[8] = (t1 shr 8).toByte(); p[9] = t1.toByte()
        p[10] = (t2 shr 8).toByte(); p[11] = t2.toByte()
        p[12] = (sectionVoltageRaw shr 8).toByte(); p[13] = sectionVoltageRaw.toByte()
        return frame(0x01, bmsnum, p)
    }

    /** Cell frame (0x02/0x03): 8 cells at frame bytes 2..17, BE millivolts, one value per cell. */
    private fun cellFrameOf(type: Int, packetIndex: Int, mv: IntArray): ByteArray {
        require(mv.size == 8) { "a cell frame carries exactly 8 cells" }
        val p = ByteArray(16)
        for (i in 0 until 8) {
            p[i * 2] = (mv[i] shr 8).toByte()
            p[i * 2 + 1] = mv[i].toByte()
        }
        return frame(type, packetIndex, p)
    }

    /**
     * Fires the cell-count collector explicitly, once, after the protocol has
     * already been driven to its final state.
     *
     * Feeding `_activeData` purely through [Wire.notify]'s production funnel
     * is NOT reliable here: `_activeData` is a `StateFlow`, which conflates
     * consecutive EQUAL values, and most of a real capture's intermediate
     * per-branch samples land equal (or close enough that the collector never
     * observably advances) until the very last one. That is an artifact of
     * how a `StateFlow` collector schedules, not a statement about
     * [KableBmsRepository.maybePersistCellCount] itself — which for a Begode
     * protocol never reads anything OFF the sample besides `isConnected`
     * (see [confirmedCellCount]'s KDoc): the confirmation comes from
     * [BegodeProtocol.derivedCellCount] on the LIVE protocol instance,
     * regardless of what this trivial sample carries. So this helper is not
     * a shortcut around "asking the decoder" — it is the most direct way to
     * ask the exact question this test is about, once the decode itself
     * (driven by real notification bytes) has already run its course.
     */
    private fun TestScope.evaluateCellCountAutoFill(repo: KableBmsRepository) {
        repo.emitActiveDataForTest(BmsData(isConnected = true))
        runCurrent()
    }

    /**
     * The pre-flight correction's own numbers: a branch stuck at 36 of 40
     * cells (a half-filled last packet, cells 36..39 never arriving) reports
     * the SAME truncated list for three consecutive samples — clearing the
     * stability streak just as cleanly as a genuine reading would. Only
     * [BegodeProtocol.derivedCellCount] — which still holds the RAW 147.2 V
     * frame field, never substituted — can tell the difference; a ratio
     * recomputed from the PUBLISHED sample cannot, because `branchVoltage`
     * already folded the (0.9076, comfortably "complete" by its own loose
     * 90 % cutoff) cell sum into that sample's own `voltage` field.
     */
    @Test
    fun aStuckThirtySixOfFortyStreamDoesNotOverwriteTheStoredCount() = repoTest { repo ->
        val v = wheel("v2").withCellCount(40)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val wire = Wire(repo, v)

        repeat(3) {
            wire.notify(liveFrame(voltageRaw = 5888))
            wire.notify(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
            for (packet in 0 until 4) {
                wire.notify(cellFrameOf(type = 0x02, packetIndex = packet, mv = IntArray(8) { 3711 }))
            }
            wire.notify(cellFrameOf(type = 0x02, packetIndex = 4, mv = intArrayOf(3711, 3711, 3711, 3711, 0, 0, 0, 0)))
        }
        assertNull(wire.protocol.derivedCellCount(), "precondition: the decoder itself refuses this run")

        evaluateCellCountAutoFill(repo)

        assertTrue(
            vehicleRepo.upserts.isEmpty(),
            "a stuck 36-of-40 stream must not overwrite the stored count"
        )
    }

    /**
     * The positive case the gate must not have broken: a genuinely complete
     * capture (the real ET Max dump) confirms 40S and IS persisted, with no
     * profile cell count assisting it at all.
     */
    @Test
    fun `the decoder's confirmed count is persisted for a Begode wheel`() = repoTest { repo ->
        val v = wheel("v3") // cellCount left null: nothing known yet
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val wire = Wire(repo, v)

        BegodeDumpFixture.chunks().forEach { wire.notify(it) }
        assertEquals(40, wire.protocol.derivedCellCount(), "precondition: the real capture confirms 40S")

        evaluateCellCountAutoFill(repo)

        // Two DIFFERENT auto-fills legitimately both fire here: the wheel's
        // real second branch is discovered (maybePersistPacks, one stored
        // pack growing to two) AND the confirmed count is persisted
        // (maybePersistCellCount) — this test is about the second one only.
        assertTrue(
            vehicleRepo.upserts.any { it.packs.first().cellCount == 40 },
            "the decoder's confirmed count must reach some upsert"
        )
    }

    /**
     * The seam above ([Wire], via [installProtocolPipelineForTest]'s
     * single-arg [createProtocol]) is not the one PRODUCTION uses to build a
     * link's protocol — [connectLinkAttempt] calls the SPEC-based overload
     * (`createProtocol(spec, vehicle)`), which is the one `[primaryPackProtocol]`'s
     * capture must actually run inside for a real connection to ever confirm
     * anything. No test in this repo can drive a real [ConnectionSession]
     * (`ConnectionSession` needs a real Kable peripheral), so
     * [createProtocolForTest] — "the protocol connectLinkAttempt would build
     * for one link, through the production controller-aware factory" per its
     * own KDoc — is the closest this suite can get to that call site, and it
     * goes through the exact same `createProtocol(spec, vehicle)` this test
     * pins.
     */
    @Test
    fun `the spec-based factory also captures the primary pack protocol`() = repoTest { repo ->
        val v = wheel("v4")
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()
        val spec = planLinks(v.packs, v.controllers).single()

        // The production factory connectLinkAttempt calls for this link —
        // feeding it directly (no funnel) isolates the capture itself from
        // everything Wire/installProtocolPipelineForTest already covers.
        val protocol = repo.createProtocolForTest(spec, v) as BegodeProtocol
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        assertEquals(40, protocol.derivedCellCount(), "precondition: the real capture confirms 40S")

        evaluateCellCountAutoFill(repo)

        assertTrue(
            vehicleRepo.upserts.any { it.packs.first().cellCount == 40 },
            "the spec-based factory's protocol must be the one the gate asks"
        )
    }
}
