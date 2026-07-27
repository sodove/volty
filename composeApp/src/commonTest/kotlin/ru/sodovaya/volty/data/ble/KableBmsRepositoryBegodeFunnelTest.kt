package ru.sodovaya.volty.data.ble

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MotionAggregator
import ru.sodovaya.volty.presentation.ride.DefaultRideDashboardComponent
import ru.sodovaya.volty.presentation.root.Config
import ru.sodovaya.volty.presentation.root.homeConfigFor
import ru.sodovaya.volty.util.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part D Task 5 — **the funnel, end to end.** One BLE link carrying a
 * controller and two battery packs at once, driven by the real ET Max capture
 * at its real notification boundaries.
 *
 * Task 4 already proved the *plan* (archetype 3: one link owning
 * `controllers = [0]` + `packs = [0, 1]`) and spot-checked three motion fields
 * off the same fixture. What it did not exercise, and what this file adds:
 *
 *  1. **The interleave itself.** `D §5` asks for motion and battery arriving
 *     *from the same link*, not merely for both being present when the dust
 *     settles. Asserting the end state cannot tell a genuine interleave from a
 *     stream that decoded every battery frame first and every motion frame
 *     afterwards — and the historical seam bug (Task 2's regression) lived
 *     exactly in the routing between those two funnels. So the funnel calls are
 *     RECORDED in arrival order and the alternation is asserted, alongside the
 *     one thing that proves a single orchestrator holds both sides: a published
 *     snapshot in which the controller and both branches are simultaneously
 *     online.
 *  2. **The battery half beside the motion half, in one test.** The two halves
 *     have always been asserted in separate tests over separate wirings.
 *  3. **The whole motion sample from the real capture**, field by field —
 *     including the four that no earlier test carried through the funnel
 *     (`escTempC`, `motorCurrentA`, `odometerKm`, `faults`).
 *  4. **The Ride dashboard is reachable for a wheel** — see the two tests at
 *     the bottom, and the honesty note on them.
 *  5. **The aggregate is the identity for one controller** — proved by whole
 *     -object equality, not by re-listing fields.
 *
 * **Why the fixture rather than synthetic frames.** [BegodeDumpFixture] is a
 * real capture replayed at the real MTU-23 chunk boundaries: every notification
 * is 20 bytes and every frame is 24, so **no frame is ever delivered whole**.
 * A funnel test built from synthetic whole frames would pass against a decoder
 * that cannot reassemble, i.e. against a decoder that works on nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryBegodeFunnelTest {

    private class NoopVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = NoopVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    @AfterTest
    fun tearDown() {
        // Only the Ride-component test installs a Main dispatcher; resetting
        // unconditionally is harmless and keeps the two paths independent.
        Dispatchers.resetMain()
    }

    /**
     * An ET Max exactly as Task 4's picker gate lets a rider configure it: one
     * stored pack (the protocol reports the second branch and `expandedTo`
     * synthesises its slot) plus the wheel itself as a controller, at the SAME
     * address — a wheel multiplexes both over one BLE link.
     */
    private fun wheel() = Vehicle(
        id = "v-wheel",
        name = "ET Max",
        iconKey = "unicycle",
        packs = listOf(
            Pack(index = 0, label = "Branch 1", bmsType = BmsType.BEGODE, bmsAddress = WHEEL, cellCount = 40)
        ),
        controllers = listOf(
            Controller(index = 0, label = "Wheel", controllerType = ControllerType.BEGODE, address = WHEEL)
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    /**
     * The session's observe loop in miniature — production funnels, routers and
     * gates against the protocol the production factory built for this link —
     * with one addition over Task 4's copy: every funnel call is recorded in
     * arrival order, so the interleave is observable and not merely implied by
     * the end state.
     *
     * `P<n>` is a battery sample for branch n, `M<n>` a motion sample for
     * controller n, both LOCAL indices as the session sees them.
     */
    private class RecordingWire(repo: KableBmsRepository, vehicle: Vehicle) {
        val protocol: BegodeProtocol
        val calls: MutableList<String> = mutableListOf()
        private val packFunnel: (Int, BmsData, List<SectionState>) -> Unit
        private val motionFunnel: (Int, ControllerData) -> Unit
        private val packGate: PackSampleGate
        private val motionGate: MotionSampleGate

        init {
            packFunnel = repo.installLinksForTest(vehicle, vehicle.primaryAddress, BmsType.BEGODE).single()
            motionFunnel = repo.linkMotionFunnelsForTest().single()
            val spec = repo.linkSpecsForTest().single()
            protocol = assertIs(repo.createProtocolForTest(spec, vehicle))
            packGate = PackSampleGate(protocol.packCount)
            motionGate = MotionSampleGate(protocol.controllerCount)
        }

        fun notify(bytes: ByteArray) {
            protocol.onNotification(bytes)
            routePackSamples(protocol, packGate) { i, bms, sections ->
                calls += "P$i"
                packFunnel(i, bms, sections)
            }
            routeControllerSamples(protocol, motionGate) { i, m ->
                calls += "M$i"
                motionFunnel(i, m)
            }
        }

        fun replayTheCapture() = BegodeDumpFixture.chunks().forEach { notify(it) }
    }

    // ----- 0. Straddling: the decode owes nothing to where the chunks fall -----

    @Test
    fun `the decode is boundary-independent, which is what surviving MTU-23 straddling means`() {
        // Opens with the premise the whole file rests on — every notification
        // is 20 bytes and every frame 24, so no frame is EVER delivered whole
        // — and then makes it an assertion about the decoder rather than about
        // the fixture: the same bytes cut at a different, harsher boundary
        // must decode to the same controller sample.
        //
        // Seven bytes spreads a single frame over four notifications and puts
        // a cut inside the 0x55 0xAA header, inside the 16-byte payload and
        // inside the 5A5A5A5A tail — the three places a reassembler can lose a
        // frame. Anything that "works on whole frames" fails one side or the
        // other and the two stop agreeing.
        val chunks = BegodeDumpFixture.chunks()
        assertEquals(228, chunks.size, "the capture is 228 notifications")
        assertTrue(
            chunks.all { it.size == BEGODE_MTU_PAYLOAD },
            "every notification is $BEGODE_MTU_PAYLOAD bytes at MTU 23"
        )
        assertTrue(
            BEGODE_FRAME_BYTES % BEGODE_MTU_PAYLOAD != 0,
            "a $BEGODE_FRAME_BYTES-byte frame cannot tile a $BEGODE_MTU_PAYLOAD-byte notification"
        )

        val stream = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        assertEquals(
            0, stream.size % BEGODE_FRAME_BYTES,
            "the capture is a whole number of frames end to end, with no gaps to hide a loss in"
        )
        val framesInTheCapture = stream.size / BEGODE_FRAME_BYTES

        val atRealBoundaries = replayCountingDecodes(chunks)
        val atSevenBytes = replayCountingDecodes(stream.chunkedInto(7))

        // (1) Same VALUES. What the wheel ends up saying cannot depend on where
        // the radio split the stream. Timestamp is stamped per rebuild, so it
        // is the one field that may legitimately differ between two replays.
        val real = assertNotNull(
            atRealBoundaries.motion,
            "the capture must decode motion through its real MTU-23 boundaries"
        )
        val recut = assertNotNull(
            atSevenBytes.motion,
            "the same bytes cut into 7-byte notifications must still decode"
        )
        assertEquals(
            real.copy(timestamp = recut.timestamp),
            recut,
            "the wheel's motion must not depend on where the radio split the stream"
        )

        // (2) Same NUMBER of frames. Values alone cannot see frame LOSS: this
        // wheel repeats its frame types every cycle, so dropping one whole
        // frame leaves the terminal reading identical and the decoder silently
        // slower. Demonstrated on the very branch this test's own comment calls
        // out — `tryParseAll`'s no-header case, which keeps a trailing 0x55 in
        // case the header pair straddles. Discarding it instead loses exactly
        // one frame per split header, and every value assertion in this file
        // stays green. So count the decodes each cut produced, and require the
        // capture's own frame count from the bytes rather than a number read
        // off the implementation.
        //
        // One frame of the 190 is unobservable, and exactly one: the capture's
        // first branch-1 cell frame arrives before that branch's first 0x01
        // telemetry frame, and `BegodeProtocol.rebuild` returns early while
        // `sawTelemetry` is false — so it publishes no BmsData. Written as
        // "all but one" rather than as a bare 189 so the exemption has to stay
        // true: a second unobservable frame fails this too.
        assertEquals(
            framesInTheCapture - 1,
            atRealBoundaries.total,
            "every frame of the capture but the one that precedes its branch's telemetry " +
                "must produce exactly one decode ($framesInTheCapture frames in the stream)"
        )
        assertEquals(
            atRealBoundaries.decodes(),
            atSevenBytes.decodes(),
            "the harsher cut must reach the same live frames, not merely the same final values"
        )
    }

    /** Motion / branch-0 / branch-1 decodes produced by one replay, and the last motion sample. */
    private class DecodeTally(
        val motionDecodes: Int,
        val pack0Decodes: Int,
        val pack1Decodes: Int,
        val motion: ControllerData?
    ) {
        fun decodes(): Triple<Int, Int, Int> = Triple(motionDecodes, pack0Decodes, pack1Decodes)
        /**
         * Every frame type the wheel emits contributes exactly one decode:
         * 0x00/0x04/0x07 rebuild the controller sample, 0x01/0x02/0x03 rebuild
         * their branch's [BmsData]. So the three counters sum to the number of
         * frames the decoder actually consumed.
         */
        val total: Int get() = motionDecodes + pack0Decodes + pack1Decodes
    }

    /**
     * Replay [notifications] through a fresh decoder, counting how many times
     * each cached decode was REPLACED — instance identity, the same
     * discriminator [MotionSampleGate] and [PackSampleGate] use, which is what
     * makes this a count of frames consumed rather than of notifications fed.
     *
     * At most one frame can complete per notification for any chunk size below
     * the 24-byte frame (a leftover is always < 24, so buffer < 24 + chunk),
     * so no decode can be double-counted by two frames landing in one append.
     */
    private fun replayCountingDecodes(notifications: List<ByteArray>): DecodeTally {
        val protocol = BegodeProtocol(cellCount = 40)
        var lastMotion: ControllerData? = null
        var lastPack0: BmsData? = null
        var lastPack1: BmsData? = null
        var motionDecodes = 0
        var pack0Decodes = 0
        var pack1Decodes = 0
        notifications.forEach { n ->
            protocol.onNotification(n)
            val m = protocol.latestMotion(0)
            if (m !== lastMotion) { motionDecodes++; lastMotion = m }
            val p0 = protocol.latestData(0)
            if (p0 !== lastPack0) { pack0Decodes++; lastPack0 = p0 }
            val p1 = protocol.latestData(1)
            if (p1 !== lastPack1) { pack1Decodes++; lastPack1 = p1 }
        }
        return DecodeTally(motionDecodes, pack0Decodes, pack1Decodes, lastMotion)
    }

    private fun ByteArray.chunkedInto(size: Int): List<ByteArray> =
        indices.step(size).map { copyOfRange(it, minOf(it + size, this.size)) }

    // ----- 1. The funnel: one controller, two packs, interleaved -----

    @Test
    fun `one wheel connection yields one controller and two packs, interleaved over the one link`() = repoTest { repo ->
        val taps = mutableListOf<VehicleData>()
        repo.vehicleDataTapForTest = { taps += it }
        val wire = RecordingWire(repo, wheel())
        wire.replayTheCapture()
        advanceUntilIdle()

        // (a) Both kinds of sample really came out of the ONE link's routers.
        assertContains(wire.calls, "M0", "the link produced motion samples")
        assertContains(wire.calls, "P0", "…and branch 0 battery samples")
        assertContains(wire.calls, "P1", "…and branch 1 battery samples")
        assertTrue(
            wire.calls.none { it != "M0" && it != "P0" && it != "P1" },
            "the link owns exactly one controller and two branches, got ${wire.calls.distinct()}"
        )

        // (b) INTERLEAVED, not two blocks. Neither side is a prefix of the
        // stream, and the two alternate repeatedly across the 13 s capture — a
        // decode that batched all batteries then all motion would satisfy every
        // end-state assertion in this file and fail here.
        val kinds = wire.calls.map { it.first() }
        assertTrue(
            kinds.indexOfFirst { it == 'M' } < kinds.indexOfLast { it == 'P' },
            "motion starts before the battery stream ends"
        )
        assertTrue(
            kinds.indexOfFirst { it == 'P' } < kinds.indexOfLast { it == 'M' },
            "battery starts before the motion stream ends"
        )
        val alternations = kinds.zipWithNext().count { (a, b) -> a != b }
        assertTrue(
            alternations >= 20,
            "motion and battery must alternate throughout the capture, not cross once; got $alternations"
        )

        // (c) ONE orchestrator holds both sides: a published snapshot in which
        // the controller and BOTH branches are online at the same instant.
        assertTrue(
            taps.any { vd ->
                vd.controllers.count { it.isOnline } == 1 && vd.packs.count { it.isOnline } == 2
            },
            "no snapshot ever carried the wheel's controller and both branches online together"
        )

        // (d) The end state a rider sees.
        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.controllers.size, "one controller: the wheel itself")
        assertEquals(2, vd.packs.size, "two branches")
        assertTrue(vd.controllers.single().isOnline)
        assertTrue(vd.packs.all { it.isOnline })
        assertFalse(vd.isPartial, "both branches reported")
        assertFalse(vd.motionPartial, "the only controller reported")
    }

    @Test
    fun `the same funnel carries the wheel's whole motion sample and its whole battery reading`() = repoTest { repo ->
        val wire = RecordingWire(repo, wheel())
        wire.replayTheCapture()
        advanceUntilIdle()

        // --- motion, straight off the capture ---
        val motion = repo.activeMotion.value
        assertTrue(motion.isConnected)
        assertEquals(0f, motion.speedKmh, 0f, "the captured wheel is stationary throughout")
        assertEquals(SpeedSource.REPORTED, motion.speedSource, "a wheel reports ground speed")
        assertEquals(2f, motion.dutyPercent, 0f, "hardware PWM: an ET Max spends 2 % holding itself upright")
        assertTrue(motion.hasDuty, "the truePWM latch closed on the capture's first non-zero reading")
        // 58.88 V on Begode's 67.2 V reference x (40 x 4.2 / 67.2) = 147.20 V.
        assertEquals(147.20f, motion.inputVoltageV, 0.01f)
        assertEquals(0.67f, motion.batteryCurrentA, 1e-3f, "0x07 bytes 2..3, negated into the controller convention")
        assertEquals(3.00f, motion.motorCurrentA, 1e-3f, "the live frame's PHASE current — a different quantity")
        assertEquals(147.20f * 0.67f, motion.powerW, 0.05f, "voltage x battery current")
        assertEquals(20f, motion.motorTempC, 0f, "the 0x07 motor thermistor")
        assertTrue(motion.hasMotorTemp, "the ET Max has one, whatever D §2 assumed")
        // Mainboard MPU of the capture's last live frame: raw -2908 / 340 + 36.53.
        assertEquals(27.98f, motion.escTempC, 0.01f, "the live frame's board sensor, distinct from the motor's 20 °C")
        assertTrue(motion.hasEscTemp, "a real reading, not the -50 °C sentinel")
        assertEquals(8565.341f, motion.odometerKm, 1e-3f, "the wheel's lifetime mileage from the 0x04 frame")
        assertEquals(0f, motion.tripKm, 0f, "a wheel that never moved")
        assertEquals(emptyList(), motion.faults, "the capture's alert byte is 0x00 throughout")

        // --- battery, from the same notifications, over the same link ---
        val data = repo.activeData.value
        assertTrue(data.isConnected)
        assertEquals(80, data.cellVoltages.size, "40 cells per branch, both branches")
        assertTrue(data.voltage in 148.1f..148.7f, "parallel mean of the two branch cell sums, was ${data.voltage}")
        assertTrue(data.socKnown)
        assertEquals(45.6f, data.soc, 0.3f, "estimated from the cells")
        val vd = repo.activeVehicleData.value
        vd.packs.forEachIndexed { i, p ->
            assertEquals(40, p.data.cellVoltages.size, "branch $i cells")
            assertTrue(p.data.voltage in 148.1f..148.7f, "branch $i voltage was ${p.data.voltage}")
        }
    }

    // ----- 2. The Ride dashboard is reachable for a wheel -----

    @Test
    fun `a wheel's home destination is Ride`() {
        // The routing RULE, at the layer that holds it. `homeConfigFor` is the
        // single function every post-connect landing, every back-out target and
        // the Ride tab's visibility go through (see RootNavigationTest, which
        // explains why DefaultRootComponent itself is out of reach from
        // commonTest: it is a KoinComponent resolving an `expect class`
        // PermissionsChecker over a real android.content.Context, eagerly, in
        // its own init).
        assertEquals(Config.Ride, homeConfigFor(wheel()))
    }

    @Test
    fun `the wheel's motion reaches the Ride dashboard's state through the real repository`() = repoTest { repo ->
        // As far as "reachable" can honestly be taken in this repo: the real
        // KableBmsRepository, fed the real capture through the real funnel,
        // wired into the real DefaultRideDashboardComponent — the object that
        // holds everything RideDashboardScreen renders.
        //
        // The Composable itself is NOT covered, here or anywhere: this project
        // carries no Robolectric, no compose-ui-test and no instrumented source
        // set (composeApp/build.gradle.kts), so no test can assert that a dial
        // is drawn. What a wheel's rider actually sees on the glass remains
        // unverified without a wheel; what this pins is that the numbers are in
        // the state the screen reads.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        // The component is built FIRST, before a single byte is decoded, so
        // everything asserted below had to arrive through its live collectors
        // — the path a rider is on. Built after the replay it would be seeded
        // from `activeMotion.value` in its own constructor, and the test would
        // still pass with every collector in the class deleted.
        val component = DefaultRideDashboardComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            vehicleRepository = NoopVehicleRepository(),
            appPrefs = appPrefs(),
            onOpenGraphRequested = {},
            onOpenSettingsRequested = {},
            onAddVehicleRequested = {},
            onDisconnectRequested = {}
        )
        advanceUntilIdle()
        assertEquals(
            0f, component.state.value.motion.dutyPercent, 0f,
            "precondition: the dashboard starts empty, so what follows is the live path"
        )

        val wire = RecordingWire(repo, wheel())
        wire.replayTheCapture()
        advanceUntilIdle()

        val state = component.state.value
        assertEquals("ET Max", assertNotNull(state.vehicle, "the wheel is the active vehicle").name)
        assertEquals(2f, state.motion.dutyPercent, 0f, "the wheel's ШИМ reading reached the dashboard state")
        assertEquals(147.20f, state.motion.inputVoltageV, 0.01f)
        assertEquals(SpeedSource.REPORTED, state.motion.speedSource)
        assertFalse(state.motionPartial, "one controller, online")
        assertTrue(state.battery.voltage in 148.1f..148.7f, "…and the battery tile, from the same link")
    }

    // ----- 3. Aggregation is unaffected: one controller folds to itself -----

    @Test
    fun `folding the wheel's single controller is the identity, end to end`() = repoTest { repo ->
        // PROOF rather than a spot-check: whole-object equality between what
        // the decoder produced and what the vehicle-level aggregate published.
        // Any fold that touches any field of a one-controller vehicle — a sum
        // that doubles, a max that clamps, a flag that defaults — breaks this
        // without needing to be enumerated in advance.
        val wire = RecordingWire(repo, wheel())
        wire.replayTheCapture()
        advanceUntilIdle()

        val decoded = assertNotNull(wire.protocol.latestMotion(0), "the capture decoded motion")
        assertEquals(
            decoded,
            repo.activeMotion.value,
            "MotionAggregator must not alter a single controller's sample on its way to the vehicle"
        )
        assertFalse(repo.activeVehicleData.value.motionPartial)
    }

    @Test
    fun `MotionAggregator folds the wheel's own sample to itself`() {
        // The same claim without the radio, so a break in the fold is reported
        // by the pure test too. The sample is the real one the capture
        // produces, not a hand-written ControllerData: a fold could be the
        // identity on the defaults and not on real values.
        val protocol = BegodeProtocol(cellCount = 40)
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        val decoded = assertNotNull(protocol.latestMotion(0))

        val result = MotionAggregator.build(
            listOf(
                ControllerState(
                    controller = Controller(0, "Wheel", ControllerType.BEGODE, WHEEL),
                    data = decoded,
                    isOnline = true
                )
            )
        )
        assertEquals(decoded, result.aggregate, "one controller must fold to itself")
        assertFalse(result.partial, "a vehicle whose only controller is online is not partial")
    }

    @Test
    fun `the single-controller fold carries every field, not only the ones a parked wheel fills`() {
        // The capture above is 13 s of a STATIONARY wheel: speed, trip,
        // eRPM, the energy counters and the fault list are all zero or empty
        // in it. So the identity assertion on that sample cannot tell a fold
        // that carries `speedKmh` from one that drops it — both read 0. The
        // fixture shape hides the mutant, exactly the trap D Task 4's
        // equivalence verdict fell into.
        //
        // This is the same single-controller fold reached with every field
        // distinct and non-zero, so dropping ANY of them is caught. A moving
        // wheel produces this shape; so does a VESC, which is the other
        // topology that reaches this line.
        val everything = ControllerData(
            speedKmh = 43.5f,
            speedSource = SpeedSource.REPORTED,
            dutyPercent = 78f,
            hasDuty = true,
            motorCurrentA = 61.25f,
            batteryCurrentA = 33.75f,
            inputVoltageV = 147.2f,
            powerW = 4968.0f,
            eRpm = 12345f,
            escTempC = 52.5f,
            motorTempC = 71.5f,
            hasMotorTemp = true,
            odometerKm = 8565.341f,
            tripKm = 12.75f,
            consumedAh = 4.5f,
            consumedWh = 640.5f,
            regenAh = 0.25f,
            regenWh = 36.5f,
            faults = listOf("OVER_TEMPERATURE"),
            // Deliberately left null. `MotionAggregator.aggregate` does NOT
            // carry `batteryLevelFraction` — a real, currently-harmless gap
            // (nothing reads it off the aggregate; the two VESC decoders read
            // it from their own `latestMotion`). Setting it here would make
            // this test fail for the aggregator's bug rather than for the
            // fold under test, and pinning the drop as expected would block
            // the fix. It is reported instead: see D Task 5's report.
            batteryLevelFraction = null,
            isConnected = true
        )

        val agg = MotionAggregator.aggregate(
            listOf(
                ControllerState(
                    controller = Controller(0, "Wheel", ControllerType.BEGODE, WHEEL),
                    data = everything,
                    isOnline = true
                )
            )
        )
        assertEquals(everything, agg, "the one-controller fold must be the identity on every field")
    }

    private companion object {
        const val WHEEL = "AA:BB:CC:DD:EE:FF"
        /** ATT payload at the wheel's MTU of 23 (23 - 3 bytes of ATT header). */
        const val BEGODE_MTU_PAYLOAD = 20
        /** Every Begode frame: 0x55 0xAA, 16 payload bytes, type, subtype, 0x5A x4. */
        const val BEGODE_FRAME_BYTES = 24
    }

    /** A real [AppPrefs] over an in-memory store — same shape as RideDashboardComponentTest's. */
    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    /**
     * [AppPrefs] shares its flows eagerly over a scope of its own on
     * [Dispatchers.Default], so the constructed values need one real dispatch
     * before `.value` reflects them — awaited on the condition rather than on a
     * wall-clock guess, exactly as RideDashboardComponentTest does.
     */
    private suspend fun appPrefs(): AppPrefs {
        val prefs = AppPrefs(
            FakePreferencesDataStore(
                mutablePreferencesOf(
                    stringPreferencesKey("unit_system") to UnitSystem.METRIC.name,
                    stringPreferencesKey("dashboard_style") to DashboardStyle.CLEAN.name
                )
            )
        )
        prefs.unitSystem.first { it == UnitSystem.METRIC }
        prefs.defaultDashboardStyle.first { it == DashboardStyle.CLEAN }
        return prefs
    }
}
