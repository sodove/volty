package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.ble.LinkSpec
import ru.sodovaya.volty.data.ble.MotionSampleGate
import ru.sodovaya.volty.data.ble.PackSampleGate
import ru.sodovaya.volty.data.ble.ProtocolKind
import ru.sodovaya.volty.data.ble.VehicleConnection
import ru.sodovaya.volty.data.ble.controllerMotionProtocol
import ru.sodovaya.volty.data.ble.planLinks
import ru.sodovaya.volty.data.ble.routeControllerSamples
import ru.sodovaya.volty.data.ble.routePackSamples
import ru.sodovaya.volty.data.bms.vesc.VescBmsValues
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `VescGatewayProtocol` — one BLE link to a VESC Express head unit carrying two
 * CAN uBoxes plus the battery the head unit hosts.
 *
 * Every frame here is assembled by hand from the pinned field tables (spec §4
 * and `VescValues`' own documented widths), so a decode that agrees with these
 * numbers agrees with the wire, not with an encoder of ours.
 *
 * The harness ([FakeGateway]) deliberately answers **asynchronously**, on its
 * own coroutine and after a link latency, because that is the only way the
 * one-request-in-flight assertion can discriminate anything: a fake that
 * replied inline from `send` would make the counter go 0→1→0 inside every send
 * and could never observe two outstanding requests, however wrong the loop was.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VescGatewayProtocolTest {

    // ------------------------------------------------------------------
    // Wire fixtures — raw integers are what goes on the wire.
    // ------------------------------------------------------------------

    private fun i16(o: MutableList<Byte>, v: Int) {
        o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
    }

    private fun i32(o: MutableList<Byte>, v: Int) {
        o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
        o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
    }

    /** `COMM_GET_VALUES` (4) — the per-unit frame. [dutyRaw] /1000, [currentInRaw] /100. */
    private fun valuesFrame(
        dutyRaw: Int = 500,
        currentInRaw: Int = 3_000,
        rpm: Int = 12_000,
        tempMosRaw: Int = 400,
        /** `v_in` /10 — the rail this unit measures. 0 is a unit that does not know it. */
        vInRaw: Int = 782
    ): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescValues.OPCODE_GET_VALUES.toByte()
        i16(o, tempMosRaw); i16(o, 680)
        i32(o, -8_250); i32(o, currentInRaw)
        i32(o, 0); i32(o, 0)                      // id, iq
        i16(o, dutyRaw)
        i32(o, rpm)
        i16(o, vInRaw)
        i32(o, 154_000); i32(o, 21_000); i32(o, 9_800_000); i32(o, 1_200_000)
        i32(o, 0); i32(o, 0)                      // tachometer counts
        o += 0                                    // fault
        return VescPacket.frame(o.toByteArray())
    }

    /**
     * `COMM_GET_VALUES_SETUP` (47) — the SETUP frame. Its currents/amp-hours are
     * the whole SETUP's (summed across CAN by `mc_interface_get_setup_values`),
     * which is why [currentInRaw] here defaults to something no single unit
     * reports: a decoder that let it through would be visible immediately.
     */
    private fun setupFrame(
        speedMsRaw: Int = 13_056,           // /1000 m/s -> 47.0 km/h
        tachAbsMRaw: Int = 1_284_600_000,   // /1000 m  -> 1284600.0 km
        currentInRaw: Int = 9_999,
        battLevelRaw: Int = 840,
        rpm: Int = 12_000
    ): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescValues.OPCODE_GET_VALUES_SETUP.toByte()
        i16(o, 520); i16(o, 680)
        i32(o, -8_250); i32(o, currentInRaw)
        i16(o, 760)
        i32(o, rpm)
        i32(o, speedMsRaw)
        i16(o, 782); i16(o, battLevelRaw)
        i32(o, 154_000); i32(o, 21_000); i32(o, 9_800_000); i32(o, 1_200_000)
        i32(o, 12_400_000); i32(o, tachAbsMRaw)
        i32(o, 0)
        o += 0                                    // fault
        return VescPacket.frame(o.toByteArray())
    }

    /** `COMM_BMS_GET_VALUES` (96) — 2 cells, 1 sensor, tail through `can_id`. */
    private fun bmsFrame(
        vTotRaw: Int = 75_500_000,          // /1e6 -> 75.5 V
        iInRaw: Int = -12_500_000,          // /1e6 -> -12.5 A (discharging)
        socRaw: Int = 812,                  // /1e3 -> 0.812
        canIdRaw: Int = 10
    ): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescBmsValues.OPCODE_BMS_GET_VALUES.toByte()
        i32(o, vTotRaw); i32(o, 0); i32(o, iInRaw); i32(o, iInRaw)
        i32(o, 18_200); i32(o, 1_374_100)
        o += 2                                    // cells
        i16(o, 4_012); i16(o, 3_998)
        o += 0; o += 1                            // balancing
        o += 1                                    // sensors
        i16(o, 2_350)
        i16(o, 3_120); i16(o, 2_880); i16(o, 0); i16(o, 2_410)
        i16(o, socRaw); i16(o, 990)
        o += canIdRaw.toByte()
        return VescPacket.frame(o.toByteArray())
    }

    // ------------------------------------------------------------------
    // The fake link
    // ------------------------------------------------------------------

    /** What the scripted device does with one request. */
    private class ScriptedReply(val afterMs: Long, val payload: ByteArray?)

    private companion object {
        const val LATENCY_MS = 20L
        const val TIMEOUT_MS = 400L
        const val GUARD_MS = 100L
        const val CYCLE_GAP_MS = 50L
        const val CAN_A = 41
        const val CAN_B = 42

        /** The CAN id of the rider's real uBox, behind the head unit. */
        const val U_BOX = 24
    }

    /**
     * A link that records every request and answers it on its own coroutine.
     *
     * [outstanding] is requests SENT minus requests RETIRED, where a request is
     * retired when its scripted reply is delivered or, for one that is never
     * answered, at the exact moment the client's own timeout expires. So the
     * counter measures precisely "how many requests has the client got in the
     * air right now", and `<= 1` is the invariant the gateway's single
     * `send_func_can_fwd` demands (spec §10.1).
     */
    private class FakeGateway(
        private val protocol: VescGatewayProtocol,
        private val script: (canId: Int?, opcode: Int) -> ScriptedReply
    ) {
        /** (canId or null for unwrapped, inner opcode), in send order. */
        val sent = mutableListOf<Pair<Int?, Int>>()
        var outstanding = 0
            private set
        var maxOutstanding = 0
            private set

        private val queue = Channel<Pair<Int?, Int>>(Channel.UNLIMITED)

        val send: suspend (ByteArray) -> Unit = { frame ->
            val request = unwrap(frame)
            val previous = sent.lastOrNull()
            sent += request
            outstanding++
            if (outstanding > maxOutstanding) maxOutstanding = outstanding
            // `fail` (an AssertionError, not an Exception) on purpose: the
            // loop's own write-failure handler catches Exception, and a
            // swallowed assertion would make this test pass vacuously.
            if (outstanding > 1) {
                fail(
                    "two requests in flight at once: $request while $previous was still " +
                        "unanswered — the gateway keeps ONE reply slot (spec §10.1)"
                )
            }
            queue.send(request)
        }

        fun runDevice(scope: CoroutineScope): Job = scope.launch {
            for ((canId, opcode) in queue) {
                val reply = script(canId, opcode)
                // Delivery and retirement are two clocks on purpose: a reply
                // later than the client's timeout is delivered late but was
                // retired on time, so scripting one cannot fake a second
                // request in flight.
                launch {
                    delay(reply.afterMs)
                    reply.payload?.let { protocol.onNotification(it) }
                }
                delay(min(reply.afterMs, TIMEOUT_MS))
                outstanding--
            }
        }

        /** Strip VESC framing, then the FORWARD_CAN wrapper if there is one. */
        private fun unwrap(frame: ByteArray): Pair<Int?, Int> {
            val len = frame[1].toInt() and 0xFF
            val payload = frame.copyOfRange(2, 2 + len)
            val first = payload[0].toInt() and 0xFF
            return if (first == VescCan.OPCODE_FORWARD_CAN) {
                (payload[1].toInt() and 0xFF) to (payload[2].toInt() and 0xFF)
            } else {
                null to first
            }
        }
    }

    /**
     * [nowMs] is wired to the scheduler's own virtual clock, which is the only
     * reason the derived battery's three age rules
     * ([VescGatewayProtocol.STALE_READING_MS] twice,
     * [VescGatewayProtocol.HOSTED_BMS_WARMUP_MS] once) can be driven at all: they
     * are measured in seconds, and a test that had to spend them in REAL time
     * could only approximate them.
     */
    private fun TestScope.protocol(
        controllers: List<GatewaySource> = listOf(
            GatewaySource(globalIndex = 0, canId = CAN_A, motor = MotorConfig(wheelDiameterMm = 254)),
            GatewaySource(globalIndex = 1, canId = CAN_B, motor = MotorConfig(wheelDiameterMm = 254))
        ),
        packs: List<GatewaySource> = listOf(GatewaySource(globalIndex = 0, canId = null))
    ) = VescGatewayProtocol(
        controllers = controllers,
        packs = packs,
        pollIntervalMs = CYCLE_GAP_MS,
        replyTimeoutMs = TIMEOUT_MS,
        lateReplyGuardMs = GUARD_MS,
        nowMs = { currentTime }
    )

    /**
     * Answers everything promptly with the frame its opcode calls for.
     *
     * **The two uBoxes answer DIFFERENT setup frames**, which is what makes the
     * per-controller overlay observable at all: a protocol that kept one shared
     * vehicle-wide overlay would publish the last answer for both, and every
     * assertion that names a speed or an odometer below would catch it.
     */
    private fun healthyScript(): (Int?, Int) -> ScriptedReply = { canId, opcode ->
        when (opcode) {
            VescValues.OPCODE_GET_VALUES_SETUP ->
                if (canId == CAN_A) {
                    ScriptedReply(LATENCY_MS, setupFrame())
                } else {
                    // 8.0 m/s -> 28.8 km/h, 500 000 m -> 500.0 km, 50 %.
                    ScriptedReply(
                        LATENCY_MS,
                        setupFrame(speedMsRaw = 8_000, tachAbsMRaw = 500_000_000, battLevelRaw = 500)
                    )
                }
            VescValues.OPCODE_GET_VALUES ->
                ScriptedReply(LATENCY_MS, valuesFrame(dutyRaw = if (canId == CAN_A) 500 else 250))
            VescBmsValues.OPCODE_BMS_GET_VALUES -> ScriptedReply(LATENCY_MS, bmsFrame())
            else -> ScriptedReply(TIMEOUT_MS, null)
        }
    }

    /**
     * One healthy cycle of the default plan: FIVE answered round-trips — a
     * SETUP and a `GET_VALUES` for each of the two uBoxes, plus the hosted
     * battery — and the inter-cycle gap.
     */
    private val healthyCycleMs = 5 * LATENCY_MS + CYCLE_GAP_MS

    /**
     * One cycle of the default plan with exactly ONE REQUEST silent: four
     * answered round-trips, one that costs the full timeout plus the quiet
     * window, and the inter-cycle gap.
     */
    private val oneSilentCycleMs = 4 * LATENCY_MS + TIMEOUT_MS + GUARD_MS + CYCLE_GAP_MS

    /**
     * One cycle with TWO of the five requests silent — which is what a whole
     * silent CONTROLLER now costs, since it is asked for both a SETUP and a
     * `GET_VALUES`. Also the shape of a cycle where both SETUPs go unanswered.
     */
    private val twoSilentCycleMs =
        3 * LATENCY_MS + 2 * (TIMEOUT_MS + GUARD_MS) + CYCLE_GAP_MS

    /**
     * Advance to just inside the end of [count] full cycles — one millisecond
     * short of the next cycle's first request, which lands exactly on the
     * boundary and would otherwise be counted here.
     */
    private fun TestScope.runCycles(count: Int, cycleMs: Long = healthyCycleMs) {
        advanceTimeBy(count * cycleMs - 1)
        runCurrent()
    }

    // ------------------------------------------------------------------
    // 1. The shape of one cycle
    // ------------------------------------------------------------------

    @Test
    fun `one cycle asks every owned source exactly once`() = runTest {
        val p = protocol()
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)

        assertEquals(
            listOf(
                CAN_A to VescValues.OPCODE_GET_VALUES_SETUP,   // speed/odometer, per controller
                CAN_B to VescValues.OPCODE_GET_VALUES_SETUP,
                CAN_A to VescValues.OPCODE_GET_VALUES,         // per-unit
                CAN_B to VescValues.OPCODE_GET_VALUES,         // per-unit
                null to VescBmsValues.OPCODE_BMS_GET_VALUES    // hosted: NOT forwarded
            ),
            link.sent,
            "one cycle = a SETUP and a GET_VALUES per controller, plus one request per battery"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * The frame that carries speed, trip, odometer and battery level goes to
     * **every** controller, not to a nominated one. It used to go to
     * `controllers.firstOrNull()`, and on the rider's scooter that is the head
     * unit — a VESC Express bridge with no motor, whose firmware falls through
     * to `default: break;` on opcode 47 and never builds a reply.
     */
    @Test
    fun `the speed request goes to every controller, once each per cycle`() = runTest {
        val p = protocol()
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(3)

        val setups = link.sent.filter { it.second == VescValues.OPCODE_GET_VALUES_SETUP }
        assertEquals(
            listOf(CAN_A, CAN_B, CAN_A, CAN_B, CAN_A, CAN_B),
            setups.map { it.first },
            "3 cycles x 2 controllers: one SETUP each, every cycle — asking only the first " +
                "controller is what pinned the rider's speed at zero"
        )
        loop.cancel(); device.cancel()
    }

    // ------------------------------------------------------------------
    // 2. THE property: one request in flight, always
    // ------------------------------------------------------------------

    /**
     * The assertion this whole class exists for. `FORWARD_CAN` replies arrive
     * bare and the gateway holds ONE reply slot, so a second forward sent before
     * the first reply lands races state on the head unit — and two replies to
     * the same inner opcode are byte-identical, so nothing could untangle them
     * afterwards.
     *
     * Fires from inside [FakeGateway.send] the instant a second request goes out
     * while one is unanswered, and again here on the recorded maximum, so it
     * cannot be defeated by a loop that merely returns before the check.
     */
    @Test
    fun `at most one request is outstanding at any instant`() = runTest {
        val p = protocol()
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(4)

        assertEquals(20, link.sent.size, "4 cycles x 5 requests — the loop really ran")
        assertEquals(1, link.maxOutstanding, "never more than one request in flight")
        assertEquals(0, link.outstanding, "and every one of them settled")
        loop.cancel(); device.cancel()
    }

    /** Same invariant when a source is silent — the timeout must not overlap the next request. */
    @Test
    fun `one request in flight still holds while a controller is silent`() = runTest {
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (canId == CAN_B) ScriptedReply(TIMEOUT_MS, null) else healthyScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(4, cycleMs = twoSilentCycleMs)

        assertEquals(
            20,
            link.sent.size,
            "4 cycles x 5 requests: a silent uBox costs its cycle two timeouts, never a skipped request"
        )
        assertEquals(1, link.maxOutstanding, "a timing-out request is still exactly one request")
        assertEquals(0, link.outstanding, "and every one of them settled")
        loop.cancel(); device.cancel()
    }

    // ------------------------------------------------------------------
    // 3. Routing by the source asked, never by arrival order
    // ------------------------------------------------------------------

    @Test
    fun `each reply routes to the global index of the source it was asked of`() = runTest {
        // Non-contiguous, out-of-order global indices: a positional guess
        // ("reply i belongs to controller i") lands on the wrong slot here.
        val p = protocol(
            controllers = listOf(
                GatewaySource(globalIndex = 3, canId = CAN_A),
                GatewaySource(globalIndex = 1, canId = CAN_B)
            ),
            packs = listOf(GatewaySource(globalIndex = 2, canId = null))
        )
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)

        // Local index -> the owned source at that position, whatever its global.
        val a = assertNotNull(p.latestMotion(0), "the front uBox answered")
        val b = assertNotNull(p.latestMotion(1), "the second uBox answered")
        assertTrue(abs(a.dutyPercent - 50f) < 0.01f, "canId $CAN_A's duty (0.500) landed on local 0")
        assertTrue(abs(b.dutyPercent - 25f) < 0.01f, "canId $CAN_B's duty (0.250) landed on local 1")

        // GLOBALITY, and asserted WITHOUT going back through
        // GatewaySource.globalIndex — which is what the two duty assertions
        // above do, so on their own they pin canId->slot and nothing more.
        // Each controller's overlay is folded in under its own GLOBAL key (3
        // and 1 here). Key the overlay by arrival position instead and 0 never
        // equals 3, so canId 41's SETUP reply silently stops reaching anyone —
        // with no wheel configured on these sources, that shows up as NONE.
        assertEquals(SpeedSource.REPORTED, a.speedSource, "the overlay found canId $CAN_A by its global index 3")
        assertEquals(SpeedSource.REPORTED, b.speedSource, "and canId $CAN_B by its own global index 1")
        // And each carries ITS OWN setup scalars: one shared vehicle-wide
        // overlay would publish the last reply for both.
        assertTrue(abs(a.speedKmh - 47.0f) < 0.05f, "canId $CAN_A's own SETUP speed")
        assertTrue(abs(b.speedKmh - 28.8f) < 0.05f, "canId $CAN_B's own SETUP speed, not its neighbour's")
        assertTrue(abs(a.odometerKm - 1284.6f) < 0.1f, "and its own odometer")
        assertTrue(abs(b.odometerKm - 500.0f) < 0.1f, "and its own odometer")

        val battery = assertNotNull(p.latestData(0))
        assertTrue(abs(battery.voltage - 75.5f) < 0.001f)
        loop.cancel(); device.cancel()
    }

    @Test
    fun `a CAN-forwarded battery is wrapped while a hosted one is not`() = runTest {
        val p = protocol(
            controllers = listOf(GatewaySource(globalIndex = 0, canId = CAN_A)),
            packs = listOf(
                GatewaySource(globalIndex = 0, canId = null),
                GatewaySource(globalIndex = 1, canId = 7)
            )
        )
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        // One controller (2 requests) + two batteries = 4 answered round-trips.
        runCycles(1, cycleMs = 4 * LATENCY_MS + CYCLE_GAP_MS)

        assertEquals(
            listOf(null to VescBmsValues.OPCODE_BMS_GET_VALUES, 7 to VescBmsValues.OPCODE_BMS_GET_VALUES),
            link.sent.filter { it.second == VescBmsValues.OPCODE_BMS_GET_VALUES }
        )
        loop.cancel(); device.cancel()
    }

    // ------------------------------------------------------------------
    // 4. The SETUP frame is an overlay, not a sample
    // ------------------------------------------------------------------

    /**
     * The trap: `COMM_GET_VALUES_SETUP` reports the whole SETUP — VESC sums
     * current, power and amp-hours across every CAN node before answering. Let
     * that decode through as the answering controller's own `ControllerData` and
     * `MotionAggregator`, which SUMS currents across controllers, doubles it.
     */
    @Test
    fun `setup contributes speed and odometer but never the summed per-unit currents`() = runTest {
        val p = protocol()
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)

        val front = assertNotNull(p.latestMotion(0))
        // From SETUP: the vehicle-level scalars.
        assertEquals(SpeedSource.REPORTED, front.speedSource)
        assertTrue(abs(front.speedKmh - 47.0f) < 0.05f, "speed came from the SETUP frame")
        assertTrue(abs(front.odometerKm - 1284.6f) < 0.1f, "odometer came from the SETUP frame")
        assertEquals(0.84f, front.batteryLevelFraction)
        // From GET_VALUES: everything per-unit. 3_000/100 = 30 A, not 9_999/100.
        assertTrue(
            abs(front.batteryCurrentA - 30f) < 0.01f,
            "per-unit current must come from GET_VALUES, not the SETUP frame's CAN-wide sum"
        )
        assertTrue(abs(front.dutyPercent - 50f) < 0.01f, "per-unit duty likewise")

        // The second uBox has its OWN overlay from its own SETUP reply — and
        // still its own per-unit numbers underneath it.
        val second = assertNotNull(p.latestMotion(1))
        assertEquals(SpeedSource.REPORTED, second.speedSource)
        assertTrue(abs(second.speedKmh - 28.8f) < 0.05f, "the rear uBox's own reported speed")
        assertTrue(abs(second.odometerKm - 500.0f) < 0.1f, "and its own odometer")
        assertEquals(0.5f, second.batteryLevelFraction)
        assertTrue(
            abs(second.batteryCurrentA - 30f) < 0.01f,
            "and its per-unit current is still GET_VALUES', not the SETUP frame's CAN-wide sum"
        )
        assertTrue(abs(second.dutyPercent - 25f) < 0.01f, "per-unit duty likewise")
        loop.cancel(); device.cancel()
    }

    /**
     * **The rider's own scooter, and the reason this frame is asked of everybody.**
     *
     * Controller 0 is the head unit: VESC Express on an ESP32-C6, a CAN bridge
     * with no motor. Its firmware handles neither opcode 4 nor opcode 47 — both
     * fall through to `default: break;` in `commands.c` and no reply is ever
     * built — while it does answer for the ANT BMS it hosts. Controller 1 is
     * the real uBox, reachable only through `FORWARD_CAN`.
     *
     * Addressing the single SETUP request to `controllers.firstOrNull()` sent
     * it to the one node on the vehicle that cannot answer it, and speed, trip,
     * odometer and battery level were all pinned at zero however well the uBox
     * answered everything else.
     *
     * Note the uBox is built with a **bare `MotorConfig()`** — `wheelDiameterMm
     * = 0`, exactly as CAN discovery creates one today (`I` Task 6). So the
     * eRPM→speed fallback is unavailable and `REPORTED` here can only have come
     * from the SETUP frame: this test cannot pass by accident on a derived speed.
     */
    @Test
    fun `setup is read from whichever controller answers it, not from controller 0`() = runTest {
        // The PREMISE, asserted rather than assumed: with a bare MotorConfig()
        // there is no wheel, so `GET_VALUES` cannot derive a speed at any rpm.
        // Whatever REPORTED this test sees came from the SETUP frame — and if
        // Task 6 ever gives CAN-discovered controllers a real wheel, this line
        // fails and tells the next reader the test needs rewriting rather than
        // silently starting to pass for the wrong reason.
        assertNull(
            VescValues.derivedSpeedKmh(eRpm = 12_000f, motor = MotorConfig()),
            "a CAN-discovered controller has wheelDiameterMm = 0, so the eRPM fallback is closed"
        )
        val p = protocol(
            controllers = listOf(
                GatewaySource(globalIndex = 0, canId = null, motor = MotorConfig()),
                GatewaySource(globalIndex = 1, canId = U_BOX, motor = MotorConfig())
            ),
            packs = listOf(GatewaySource(globalIndex = 0, canId = null))
        )
        val link = FakeGateway(p) { canId, opcode ->
            when {
                // The head unit emulates the ANT BMS and answers that, itself.
                opcode == VescBmsValues.OPCODE_BMS_GET_VALUES -> ScriptedReply(LATENCY_MS, bmsFrame())
                // 11.67 m/s -> 42.0 km/h, 812 000 m -> 812.0 km.
                canId == U_BOX && opcode == VescValues.OPCODE_GET_VALUES_SETUP ->
                    ScriptedReply(
                        LATENCY_MS,
                        setupFrame(speedMsRaw = 11_670, tachAbsMRaw = 812_000_000)
                    )
                canId == U_BOX && opcode == VescValues.OPCODE_GET_VALUES ->
                    ScriptedReply(LATENCY_MS, valuesFrame(dutyRaw = 250))
                // Everything addressed to the head unit itself: silence.
                else -> ScriptedReply(TIMEOUT_MS, null)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        // Three answered round-trips, two silent ones (the head unit's).
        runCycles(1, cycleMs = twoSilentCycleMs)

        assertTrue(
            link.sent.contains(U_BOX to VescValues.OPCODE_GET_VALUES_SETUP),
            "the uBox must be ASKED for the setup frame: ${link.sent}"
        )
        val uBox = assertNotNull(p.latestMotion(1), "the uBox answered everything it was asked")
        assertEquals(SpeedSource.REPORTED, uBox.speedSource, "and its ground speed is a measurement")
        assertEquals(42.0f, uBox.speedKmh, 0.05f)
        assertEquals(812.0f, uBox.odometerKm, 0.1f)
        assertEquals(0f, uBox.tripKm, 0.01f, "the first frame of the connection is the trip baseline")
        assertEquals(0.84f, assertNotNull(uBox.batteryLevelFraction), 0.001f)

        assertNull(p.latestMotion(0), "the head unit answers nothing, so it has no sample at all")
        assertNotNull(p.latestData(0), "while the BMS it hosts reports normally")
        loop.cancel(); device.cancel()
    }

    /**
     * `I` Task 10 — **the second place a VESC speed reaches a consumer.**
     *
     * `applySetup` copies `speedKmh` and `speedSource` out of the SETUP decode
     * into its `SetupOverlay`, and `publishController` copies them again over the
     * per-unit decode. So a magnitude taken in only one of `VescValues`' two
     * decoders would still let a signed number reach the dashboard by this road:
     * both take it, or neither does.
     *
     * The uBox here reverses on BOTH frames, which is the coherent shape (a
     * controller whose motor direction is configured the other way round signs
     * both its `rpm` and the ground speed derived from it), and the two sources
     * are made to disagree in MAGNITUDE — 42.0 reported against 38.3 derived —
     * so the number asserted below is attributable to the overlay rather than to
     * the fallback.
     */
    @Test
    fun `a reversing controller publishes its speed as a magnitude through the setup overlay`() = runTest {
        val wheel = MotorConfig(wheelDiameterMm = 254)
        val derived = assertNotNull(
            VescValues.derivedSpeedKmh(eRpm = -12_000f, motor = wheel),
            "precondition: this controller CAN derive a speed, so REPORTED has to beat it"
        )
        assertTrue(
            abs(abs(derived) - 42.0f) > 1f,
            "precondition: the two sources disagree, so 42.0 below can only be the overlay's"
        )

        val p = protocol(
            controllers = listOf(GatewaySource(globalIndex = 0, canId = U_BOX, motor = wheel)),
            packs = emptyList()
        )
        val link = FakeGateway(p) { _, opcode ->
            when (opcode) {
                // -11.67 m/s -> a magnitude of 42.0 km/h.
                VescValues.OPCODE_GET_VALUES_SETUP ->
                    ScriptedReply(LATENCY_MS, setupFrame(speedMsRaw = -11_670, rpm = -12_000))
                VescValues.OPCODE_GET_VALUES -> ScriptedReply(LATENCY_MS, valuesFrame(rpm = -12_000))
                else -> ScriptedReply(TIMEOUT_MS, null)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1, cycleMs = 2 * LATENCY_MS + CYCLE_GAP_MS)

        val uBox = assertNotNull(p.latestMotion(0), "the uBox answered both of its requests")
        assertEquals(SpeedSource.REPORTED, uBox.speedSource, "the overlay's source won")
        assertEquals(
            42.0f, uBox.speedKmh, 0.05f,
            "the overlay carries the MAGNITUDE; a signed one nulls consumption and freezes the peak"
        )
        assertEquals(
            -12_000f, uBox.eRpm,
            "and the direction survives the overlay untouched — eRpm is not a magnitude"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * Trip is distance since the link came up — and the baseline is **per
     * controller**, because two nodes' tachometers are two independent
     * counters. The two here start 784.6 km apart on purpose: one shared
     * baseline gives the second controller either a negative trip (coerced to a
     * flat 0 that never moves) or an absurd 787 km, depending which reply won.
     */
    @Test
    fun `trip starts at zero for this connection and grows with each controller's own odometer`() =
        runTest {
            var odoARaw = 1_284_600_000                       // 1284.6 km
            var odoBRaw = 500_000_000                         //  500.0 km
            val p = protocol()
            val link = FakeGateway(p) { canId, opcode ->
                if (opcode == VescValues.OPCODE_GET_VALUES_SETUP) {
                    ScriptedReply(
                        LATENCY_MS,
                        setupFrame(tachAbsMRaw = if (canId == CAN_A) odoARaw else odoBRaw)
                    )
                } else {
                    healthyScript()(canId, opcode)
                }
            }
            val device = link.runDevice(this)
            val loop = launch { p.runPollLoop(link.send) }

            runCycles(1)
            assertEquals(0f, assertNotNull(p.latestMotion(0)).tripKm, "the first frame is the baseline")
            assertEquals(0f, assertNotNull(p.latestMotion(1)).tripKm, "and so it is for the rear uBox")

            odoARaw += 2_500_000                              // +2500 m
            odoBRaw += 1_200_000                              // +1200 m
            runCycles(2)
            assertTrue(
                abs(assertNotNull(p.latestMotion(0)).tripKm - 2.5f) < 0.01f,
                "trip is distance since the link came up, not the ESC's since-boot counter"
            )
            assertTrue(
                abs(assertNotNull(p.latestMotion(1)).tripKm - 1.2f) < 0.01f,
                "and it is measured against THIS controller's own baseline, not its neighbour's"
            )
            loop.cancel(); device.cancel()
        }

    /**
     * A torn-down session must not leave its ground speed behind for the next
     * one — on this app the next session can be a DIFFERENT vehicle.
     *
     * The malformed reply is what makes the leak observable: a SETUP that times
     * out would have its overlay dropped by the silence path anyway, so it
     * cannot tell "reset cleared it" from "the timeout cleared it". A reply that
     * arrives and fails to decode goes down neither path, leaving whatever
     * [VescGatewayProtocol.reset] did (or did not do) on display.
     */
    @Test
    fun `reset forgets the setup overlay`() = runTest {
        var malformed = false
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescValues.OPCODE_GET_VALUES_SETUP && malformed) {
                // Opcode 47, body far too short: `decodeSetupValues` returns
                // null, so the request is ANSWERED but contributes nothing.
                ScriptedReply(LATENCY_MS, VescPacket.frame(byteArrayOf(47, 0, 0)))
            } else {
                healthyScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)
        assertEquals(SpeedSource.REPORTED, assertNotNull(p.latestMotion(0)).speedSource)

        p.reset()
        malformed = true
        runCycles(3)

        val front = assertNotNull(p.latestMotion(0), "the loop kept running through the reset")
        assertEquals(
            SpeedSource.DERIVED, front.speedSource,
            "a new session must not inherit the old one's reported speed"
        )
        assertFalse(abs(front.speedKmh - 47.0f) < 0.05f, "nor its number")
        assertEquals(0f, front.odometerKm, "nor its odometer")
        loop.cancel(); device.cancel()
    }

    @Test
    fun `reset forgets the trip baseline`() = runTest {
        var odoRaw = 1_284_600_000
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescValues.OPCODE_GET_VALUES_SETUP) {
                ScriptedReply(LATENCY_MS, setupFrame(tachAbsMRaw = odoRaw))
            } else {
                healthyScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)
        assertEquals(0f, assertNotNull(p.latestMotion(0)).tripKm)

        p.reset()
        odoRaw += 2_500_000                                   // the vehicle moved 2.5 km
        runCycles(3)

        assertEquals(
            0f, assertNotNull(p.latestMotion(0)).tripKm,
            "the reconnected session's trip restarts from its own first frame, not the old one's"
        )
        loop.cancel(); device.cancel()
    }

    @Test
    fun `a silent SETUP drops the reported speed instead of freezing it`() = runTest {
        var setupAnswers = true
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescValues.OPCODE_GET_VALUES_SETUP && !setupAnswers) {
                ScriptedReply(TIMEOUT_MS, null)
            } else {
                healthyScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)
        assertEquals(SpeedSource.REPORTED, assertNotNull(p.latestMotion(0)).speedSource)

        setupAnswers = false
        advanceTimeBy(3 * twoSilentCycleMs)
        runCurrent()

        val front = assertNotNull(p.latestMotion(0))
        assertEquals(
            SpeedSource.DERIVED, front.speedSource,
            "with nobody reporting a speed the dashboard must fall back, not hold 47 km/h forever"
        )
        assertFalse(abs(front.speedKmh - 47.0f) < 0.05f, "the stale reported speed is gone")
        loop.cancel(); device.cancel()
    }

    /**
     * The counterpart, and the reason the overlay is dropped **per controller**
     * rather than wholesale: a node that stops answering opcode 47 — the head
     * unit's permanent state — must not take its neighbour's reported speed
     * down with it. Clearing the whole map on any silence would restore exactly
     * the symptom this task exists to fix.
     *
     * It is the REAR uBox that goes quiet here, deliberately: its SETUP is the
     * second of the two in the cycle, so a wholesale clear runs AFTER the front
     * one's reply has been filed and destroys it. Silence the front one instead
     * and the rear's later reply would refill the map, leaving the mutant alive.
     */
    @Test
    fun `one controller's silent SETUP does not drop the other's reported speed`() = runTest {
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescValues.OPCODE_GET_VALUES_SETUP && canId == CAN_B) {
                ScriptedReply(TIMEOUT_MS, null)
            } else {
                healthyScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(3 * oneSilentCycleMs)
        runCurrent()

        val front = assertNotNull(p.latestMotion(0))
        assertEquals(SpeedSource.REPORTED, front.speedSource, "the answering one keeps its ground speed")
        assertTrue(abs(front.speedKmh - 47.0f) < 0.05f, "and it is still its own SETUP frame's speed")
        val rear = assertNotNull(p.latestMotion(1))
        assertEquals(SpeedSource.DERIVED, rear.speedSource, "the silent one falls back to its wheel")
        loop.cancel(); device.cancel()
    }

    // ------------------------------------------------------------------
    // 5. Silence never wedges the loop
    // ------------------------------------------------------------------

    @Test
    fun `a silent controller is skipped and the other sources keep reporting`() = runTest {
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (canId == CAN_B) ScriptedReply(TIMEOUT_MS, null) else healthyScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(3 * twoSilentCycleMs)
        runCurrent()

        assertNotNull(p.latestMotion(0), "the awake uBox keeps reporting")
        assertNotNull(p.latestData(0), "and so does the hosted battery")
        assertNull(p.latestMotion(1), "no reply, no sample — that is what makes it go offline")
        assertTrue(
            link.sent.count { it.second == VescBmsValues.OPCODE_BMS_GET_VALUES } >= 3,
            "the battery was still polled every cycle — a sleeping uBox cannot wedge the loop"
        )
        loop.cancel(); device.cancel()
    }

    @Test
    fun `a controller that wakes up starts reporting again`() = runTest {
        var awake = false
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (canId == CAN_B && !awake) ScriptedReply(TIMEOUT_MS, null)
            else healthyScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(2 * twoSilentCycleMs)
        runCurrent()
        assertNull(p.latestMotion(1))

        awake = true
        runCycles(3)
        assertTrue(
            abs(assertNotNull(p.latestMotion(1)).dutyPercent - 25f) < 0.01f,
            "the woken uBox's own decode lands in its own slot"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * The wire has no transaction id and two `GET_VALUES` replies are
     * byte-identical, so a reply that arrives just after we gave up would be
     * credited to whatever we asked next — here, the OTHER uBox. The quiet
     * window after a timeout is what stops that.
     */
    @Test
    fun `a reply that arrives after its timeout is not credited to the next controller`() = runTest {
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                // The front uBox's per-unit request answers just after we gave up
                // — inside the window where, WITHOUT the quiet guard, the
                // second uBox's byte-identical request would still be armed
                // (it is sent at T+timeout and answered at T+timeout+latency).
                opcode == VescValues.OPCODE_GET_VALUES && canId == CAN_A ->
                    ScriptedReply(TIMEOUT_MS + LATENCY_MS / 2, valuesFrame(dutyRaw = 500))
                else -> healthyScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(oneSilentCycleMs)
        runCurrent()

        // NOT "the late reply was dropped": this stays null however the reply
        // is mis-credited, because a reply credited to the NEXT request lands
        // on local 1, not here. What it pins is that the front uBox produced no
        // sample of its own — the drop itself is the assertion below.
        assertNull(p.latestMotion(0), "the front uBox's own request went unanswered, so it has no sample")
        assertTrue(
            abs(assertNotNull(p.latestMotion(1)).dutyPercent - 25f) < 0.01f,
            "the second uBox reports ITS own 0.250 duty, not the late 0.500 meant for the first"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * **The same hazard on the pair THIS task created.** Grouping the plan as
     * `[all SETUPs][all GET_VALUES]` puts the two SETUP requests next to each
     * other, and two SETUP replies are as byte-identical as two `GET_VALUES`
     * replies — the wire carries no transaction id either way. So the quiet
     * window has to hold for this pair too, and asserting it only for
     * `GET_VALUES` would leave the new pair covered by nothing but the belief
     * that the guard is generic.
     *
     * The front uBox's SETUP answers 10 ms after we gave up on it. Without the
     * guard the rear's SETUP is already armed by then, and the front's
     * 47.0 km/h / 1284.6 km would be published as the REAR's ground speed and
     * odometer — a number that is real, plausible, and 784 km wrong.
     *
     * Deliberately NOT on [FakeGateway]: its in-flight counter retires a
     * request on the DEVICE's clock, so when a late reply lets the client run
     * ahead of the device's queue the counter reports an overlap the client
     * never created — and `fail`ing there aborts before the attribution
     * assertions below can speak. That would still detect a missing guard, but
     * it would not show WHAT the guard prevents, which is the whole point of
     * this test. A plain scripted link has no such coupling.
     */
    @Test
    fun `a late SETUP reply is not credited to the next controller`() = runTest {
        val p = protocol(packs = emptyList())
        val scope = this
        val loop = launch {
            p.runPollLoop { frame ->
                // [2] FORWARD_CAN, [3] canId, [4] the inner opcode.
                val canId = frame[3].toInt() and 0xFF
                val inner = frame[4].toInt() and 0xFF
                val reply: Pair<Long, ByteArray>? = when {
                    inner == VescValues.OPCODE_GET_VALUES_SETUP && canId == CAN_A ->
                        // Answers just after we gave up: inside the window
                        // where, without the guard, the REAR's byte-identical
                        // SETUP request would already be armed.
                        (TIMEOUT_MS + LATENCY_MS / 2) to setupFrame()
                    inner == VescValues.OPCODE_GET_VALUES_SETUP ->
                        LATENCY_MS to setupFrame(
                            speedMsRaw = 8_000, tachAbsMRaw = 500_000_000, battLevelRaw = 500
                        )
                    inner == VescValues.OPCODE_GET_VALUES ->
                        LATENCY_MS to valuesFrame(dutyRaw = if (canId == CAN_A) 500 else 250)
                    else -> null
                }
                reply?.let { (after, payload) ->
                    scope.launch { delay(after); p.onNotification(payload) }
                }
            }
        }

        // setupA times out (400) + quiet window (100), then three answered
        // round-trips and the inter-cycle gap.
        advanceTimeBy(TIMEOUT_MS + GUARD_MS + 3 * LATENCY_MS + CYCLE_GAP_MS)
        runCurrent()

        val rear = assertNotNull(p.latestMotion(1))
        assertTrue(
            abs(rear.speedKmh - 28.8f) < 0.05f,
            "the rear uBox reports ITS own 28.8 km/h, not the late 47.0 meant for the front"
        )
        assertTrue(
            abs(rear.odometerKm - 500.0f) < 0.1f,
            "and its own 500.0 km odometer, not the front's 1284.6"
        )
        val front = assertNotNull(p.latestMotion(0), "the front uBox still answered its GET_VALUES")
        assertEquals(
            SpeedSource.DERIVED, front.speedSource,
            "while the front's own SETUP went unanswered, so it falls back to its wheel"
        )
        loop.cancel()
    }

    /**
     * A uBox that stops answering must stop having a latest sample — "no reply,
     * no sample" has to be true of the ACCESSOR, not only of the gate that
     * feeds `ConnectionSession`. Dropping the per-unit decode cache while
     * leaving what was published from it means `latestMotion` keeps handing out
     * a frozen duty and current for the rest of the ride: harmless to the
     * routing helpers (they discriminate on instance identity, so an unchanged
     * instance yields no new sample) and false to every other reader.
     */
    @Test
    fun `a controller that stops answering drops its last sample instead of freezing it`() = runTest {
        var awake = true
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (canId == CAN_B && !awake) ScriptedReply(TIMEOUT_MS, null)
            else healthyScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)
        assertNotNull(p.latestMotion(1), "the rear uBox did answer to begin with")

        awake = false
        runCycles(2, cycleMs = twoSilentCycleMs)

        assertNull(
            p.latestMotion(1),
            "a quiet uBox must have no sample AT ALL — a frozen duty and current beside a live " +
                "speed is a lie, whoever reads it"
        )
        assertNotNull(p.latestMotion(0), "its awake neighbour is untouched")
        assertNotNull(p.latestData(0), "and so is the hosted battery, which caches deliberately")
        loop.cancel(); device.cancel()
    }

    /**
     * **`applySetup` must not publish, and this is the test that says why.**
     *
     * Every other test here drains between whole cycles, where an extra publish
     * from the SETUP reply is invisible: the controller's own `GET_VALUES`
     * overwrites it microseconds later and only the final value is ever read.
     * That is what makes deleting the publish look equivalent. It is not.
     * [ru.sodovaya.volty.data.ble.ConnectionSession] drains the routing helpers
     * on **every notification**, so a publish between a SETUP reply and its
     * controller's `GET_VALUES` reply is a real sample, restamped with the
     * arrival time on its way out — carrying a whole cycle's-old duty and
     * current beside this cycle's speed.
     *
     * The uBox reports a DIFFERENT duty every cycle, so every submitted sample
     * can be traced to the reply it came from. With the publish in place the
     * sequence stutters — each cycle opens by resubmitting the previous cycle's
     * duty under the new speed — and when the per-unit frame dies while SETUP
     * keeps answering, one further sample goes out carrying numbers the
     * controller has stopped confirming.
     */
    @Test
    fun `a SETUP reply does not resubmit last cycle's per-unit numbers`() = runTest {
        var valuesAnswer = true
        var reply = 0
        val p = protocol(
            controllers = listOf(
                GatewaySource(globalIndex = 0, canId = CAN_A, motor = MotorConfig(wheelDiameterMm = 254))
            ),
            packs = emptyList()
        )
        val gate = MotionSampleGate(p.controllerCount)
        val duties = mutableListOf<Float>()

        val loop = launch {
            p.runPollLoop { frame ->
                // [2] FORWARD_CAN, [3] canId, [4] the inner opcode.
                when (frame[4].toInt() and 0xFF) {
                    VescValues.OPCODE_GET_VALUES_SETUP -> p.onNotification(setupFrame())
                    VescValues.OPCODE_GET_VALUES ->
                        if (valuesAnswer) {
                            reply++
                            // 10 %, 20 %, 30 % … — one distinguishable reading
                            // per cycle, so a resubmitted one is visible.
                            p.onNotification(valuesFrame(dutyRaw = reply * 100))
                        }
                    else -> Unit
                }
                // Exactly what ConnectionSession does after every notification.
                routeControllerSamples(p, gate) { _, data -> duties += data.dutyPercent }
            }
        }

        // Both answer: the uBox is healthy and reporting.
        advanceTimeBy(4 * CYCLE_GAP_MS)
        runCurrent()
        assertTrue(duties.size >= 3, "the healthy uBox submitted samples to begin with: $duties")
        assertEquals(
            duties.distinct(), duties,
            "no per-unit reading may be submitted twice — a publish on the SETUP reply reopens " +
                "each cycle with the PREVIOUS cycle's duty and current under the new speed: $duties"
        )

        // Its per-unit frame dies; its SETUP keeps answering.
        valuesAnswer = false
        val settled = duties.size
        advanceTimeBy(3 * (TIMEOUT_MS + GUARD_MS + CYCLE_GAP_MS))
        runCurrent()

        assertEquals(
            settled, duties.size,
            "a SETUP reply on its own is not a sample of any controller: it must not submit " +
                "numbers the controller has stopped confirming, restamped as if they were now"
        )
        assertNull(p.latestMotion(0), "and its cached decode is gone, as `no reply, no sample` says")
        loop.cancel()
    }

    /**
     * **The write-failure path must disarm before it paces, exactly as the
     * timeout path does.** A request whose write threw never reached the wire,
     * so nothing legitimate can answer it — but the loop still waits out the
     * reply budget before moving on, and while it waited the expectation used
     * to stay ARMED. A late reply to an EARLIER request (byte-identical, since
     * the wire has no transaction id — §10.1 reason 2) landing in that window
     * was consumed and credited to a request that was never sent.
     *
     * Asserted INSIDE the pace, before the loop's own `onSilence` would clear
     * the slot anyway: the harm is a wrong reading being published at all, not
     * whether something later overwrites it.
     */
    @Test
    fun `a stale reply arriving while a failed write is paced is not credited to it`() = runTest {
        val p = protocol(
            controllers = listOf(GatewaySource(globalIndex = 0, canId = CAN_A)),
            packs = emptyList()
        )
        val scope = this
        val loop = launch {
            p.runPollLoop { frame ->
                val len = frame[1].toInt() and 0xFF
                val payload = frame.copyOfRange(2, 2 + len)
                when (payload[2].toInt() and 0xFF) {
                    VescValues.OPCODE_GET_VALUES -> {
                        // The link drops mid-write. The reply that lands 20 ms
                        // later belongs to an earlier exchange — this request
                        // never made it out, so nothing can legitimately answer.
                        scope.launch {
                            delay(LATENCY_MS)
                            p.onNotification(valuesFrame(dutyRaw = 500))
                        }
                        throw IllegalStateException("write failed — the link dropped mid-cycle")
                    }
                    else -> p.onNotification(setupFrame())
                }
            }
        }

        // Well inside the 400 ms pace the failure starts, and well past the
        // 20 ms at which the stale reply arrives.
        advanceTimeBy(LATENCY_MS * 5)
        runCurrent()

        assertNull(
            p.latestMotion(0),
            "a reply to a request that never went out must be dropped, not credited to it"
        )
        loop.cancel()
    }

    /**
     * The loop's timeout budget is bounded by what the session's stale-sample
     * watchdog allows between two SAMPLES, and the binding case is the longest
     * run of silent sources that still leaves somebody reporting — every source
     * but one. That scales linearly with the plan, so it is checked at
     * construction rather than assumed: a plan that cannot meet it does not
     * work, and reconnecting forever while most of the CAN bus is healthy is a
     * far worse way to find out.
     */
    @Test
    fun `a plan too large for the stale-sample budget is refused at construction`() {
        // Two requests per controller now — a SETUP and a GET_VALUES — so 5
        // controllers are 10 requests: 9 x 500 ms + the 50 ms cycle gap =
        // 4550 ms, inside the 5 s budget.
        VescGatewayProtocol(
            controllers = (0 until 5).map { GatewaySource(globalIndex = it, canId = it + 1) },
            packs = emptyList()
        )

        val e = assertFailsWith<IllegalArgumentException> {
            // One more controller is two more requests: 11 x 500 + 50 = 5550 ms.
            VescGatewayProtocol(
                controllers = (0 until 6).map { GatewaySource(globalIndex = it, canId = it + 1) },
                packs = emptyList()
            )
        }
        assertTrue(
            e.message.orEmpty().contains("stale-sample budget"),
            "the refusal has to say what it is protecting, not just that it refused: ${e.message}"
        )
    }

    /**
     * **A derived battery is a request, so it moves the ceiling** (`I` Task 5).
     * A gateway that derives one spends `2 x controllers + 1`, which tops out at
     * FOUR controllers (9 requests: 8 x 500 + 50 = 4050 ms) where a pack-less plan managed
     * five. Stated as a test rather than as arithmetic in a report, because the
     * number is the one a future timing change has to be re-derived against.
     */
    @Test
    fun `the derived battery's own request counts against the budget`() {
        VescGatewayProtocol(
            controllers = (0 until 4).map { GatewaySource(globalIndex = it, canId = it + 1) },
            packs = listOf(GatewaySource(globalIndex = 0, canId = null, derived = true))
        )

        assertFailsWith<IllegalArgumentException>(
            "5 controllers + a derived battery is 11 requests — one past what the budget allows"
        ) {
            VescGatewayProtocol(
                controllers = (0 until 5).map { GatewaySource(globalIndex = it, canId = it + 1) },
                packs = listOf(GatewaySource(globalIndex = 0, canId = null, derived = true))
            )
        }
    }

    // ------------------------------------------------------------------
    // 6. Battery-frame semantics
    // ------------------------------------------------------------------

    @Test
    fun `the no-BMS-data-yet sentinel frame is dropped instead of published`() = runTest {
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescBmsValues.OPCODE_BMS_GET_VALUES) {
                // can_id 0xFF is the firmware's "-1" initial value: the whole
                // struct is the zeroed default, not a reading.
                ScriptedReply(LATENCY_MS, bmsFrame(vTotRaw = 0, iInRaw = 0, socRaw = 0, canIdRaw = 0xFF))
            } else {
                healthyScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(2)

        assertNull(p.latestData(0), "0 V / 0 % must never reach the Battery screen as a measurement")
        assertNotNull(p.latestMotion(0), "the controllers are unaffected")
        loop.cancel(); device.cancel()
    }

    @Test
    fun `an unsolicited frame is ignored`() = runTest {
        val p = protocol()
        p.onNotification(bmsFrame())
        assertNull(p.latestData(0), "nothing was asked for, so nothing is attributed")
    }

    @Test
    fun `a link that owns nothing does not spin`() = runTest {
        val p = protocol(controllers = emptyList(), packs = emptyList())
        var writes = 0
        val loop = launch { p.runPollLoop { writes++ } }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, writes)
        assertTrue(loop.isCompleted, "an empty plan returns rather than looping on a delay")
    }

    /**
     * **The third unattributable-late-reply pair** the plan's KDoc records: two
     * batteries on one gateway put two byte-identical `COMM_BMS_GET_VALUES`
     * requests next to each other, exactly as the two SETUPs and the two
     * `GET_VALUES` are. Task 4 reasoned it was covered by the same quiet window
     * and left it pinned by nothing; `I` Task 5 makes the shape reachable from a
     * single rider action (a head unit that hosts a battery AND derives one), so
     * the belief needs a test under it.
     *
     * The hosted battery's reply lands 10 ms after we gave up on it. Without the
     * guard the CAN-forwarded battery's request is already armed by then, and
     * the hosted pack's 75.5 V would be published as the OTHER pack's voltage —
     * on a vehicle whose two packs are meant to be separately readable.
     *
     * Driven from a plain scripted send rather than [FakeGateway], for the same
     * reason `a late SETUP reply is not credited to the next controller` is: the
     * fake retires requests on the DEVICE's clock, so a late reply makes its
     * in-flight counter `fail` before the attribution assertions can speak.
     */
    @Test
    fun `a late BMS reply is not credited to the next battery`() = runTest {
        val p = protocol(
            controllers = listOf(GatewaySource(globalIndex = 0, canId = CAN_A)),
            packs = listOf(
                GatewaySource(globalIndex = 0, canId = null),
                GatewaySource(globalIndex = 1, canId = 7)
            )
        )
        val scope = this
        val loop = launch {
            p.runPollLoop { frame ->
                val len = frame[1].toInt() and 0xFF
                val payload = frame.copyOfRange(2, 2 + len)
                val forwarded = (payload[0].toInt() and 0xFF) == VescCan.OPCODE_FORWARD_CAN
                val inner = if (forwarded) payload[2].toInt() and 0xFF else payload[0].toInt() and 0xFF
                val reply: Pair<Long, ByteArray> = when {
                    inner == VescValues.OPCODE_GET_VALUES_SETUP -> LATENCY_MS to setupFrame()
                    inner == VescValues.OPCODE_GET_VALUES -> LATENCY_MS to valuesFrame()
                    // The HOSTED battery answers just after we gave up, inside
                    // the window where the forwarded battery's byte-identical
                    // request would already be armed without the guard.
                    !forwarded -> (TIMEOUT_MS + LATENCY_MS / 2) to bmsFrame(vTotRaw = 75_500_000)
                    else -> LATENCY_MS to bmsFrame(vTotRaw = 60_000_000)
                }
                reply.let { (after, load) -> scope.launch { delay(after); p.onNotification(load) } }
            }
        }

        // The controller's two requests answer at 20 ms each (t = 40); the
        // hosted battery times out at t = 440 and holds the quiet window to
        // t = 540; the forwarded one is sent there and answers at t = 560.
        advanceTimeBy(2 * LATENCY_MS + TIMEOUT_MS + GUARD_MS + LATENCY_MS + 10)
        runCurrent()

        assertEquals(
            60.0f,
            assertNotNull(p.latestData(1), "the forwarded battery answered its own request").voltage,
            0.001f,
            "the CAN battery reports ITS own 60.0 V, not the 75.5 V meant for the hosted one"
        )
        assertNull(
            p.latestData(0),
            "and the hosted battery's own request went unanswered inside its window, so it has nothing"
        )
        loop.cancel()
    }

    // ------------------------------------------------------------------
    // 6b. The battery a gateway link DERIVES (`I` Task 5)
    // ------------------------------------------------------------------

    /**
     * The rider's own shape: one head-unit address, two controllers, and **no
     * pack in the profile at all** — so the link's battery slot is the DERIVED
     * one their controller-only vehicle was created with, and which adding the
     * CAN controller used to delete.
     */
    private fun TestScope.derivedProtocol() = protocol(
        packs = listOf(GatewaySource(globalIndex = 0, canId = null, derived = true))
    )

    /** What a VESC with no BMS behind it answers opcode 96 with: the zeroed default. */
    private fun noBmsFrame() = bmsFrame(vTotRaw = 0, iInRaw = 0, socRaw = 0, canIdRaw = 0xFF)

    /**
     * `COMM_GET_VALUES` (4) with a body far too short to decode. The opcode
     * MATCHES, so the request is answered and settled — `onSilence` never runs
     * and the controller's cached decode is never dropped. That asymmetry is the
     * whole reason the derived battery filters its contributors by age.
     */
    private fun undecodableValuesFrame() = VescPacket.frame(byteArrayOf(4, 0, 0))

    /**
     * Long enough for a contentless opcode-96 to stop being a warm-up, plus a
     * couple of cycles for the loop to act on it. Named because every derived
     * battery test has to spend it before it can assert anything at all.
     */
    private val pastWarmupMs = VescGatewayProtocol.HOSTED_BMS_WARMUP_MS + 2 * healthyCycleMs

    /**
     * Healthy, except that the FRONT uBox does not know the rail voltage
     * (`v_in = 0`) and the rear does. Any fold that took the first contributor,
     * or averaged them, reads 0 V or 39.1 V instead of the 78.2 V one of them
     * actually measured.
     */
    private fun railScript(): (Int?, Int) -> ScriptedReply = { canId, opcode ->
        if (opcode == VescValues.OPCODE_GET_VALUES) {
            ScriptedReply(
                LATENCY_MS,
                valuesFrame(
                    dutyRaw = if (canId == CAN_A) 500 else 250,
                    vInRaw = if (canId == CAN_A) 0 else 782
                )
            )
        } else {
            healthyScript()(canId, opcode)
        }
    }

    /**
     * A stock VESC hosts no BMS and says so — with the `can_id == 0xFF`
     * sentinel, which is an ANSWER, not silence. A rider whose plain VESC link
     * showed a rail-derived battery must not lose it for having added a second
     * controller over CAN, so the fallback has to hang off this path as well as
     * off the timeout.
     */
    @Test
    fun `a derived slot falls back to the controllers' rail when the gateway hosts no BMS`() = runTest {
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescBmsValues.OPCODE_BMS_GET_VALUES) ScriptedReply(LATENCY_MS, noBmsFrame())
            else railScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(pastWarmupMs)
        runCurrent()

        val battery = assertNotNull(
            p.latestData(0),
            "adding a CAN controller must not take away the battery the link already derived"
        )
        assertEquals(78.2f, battery.voltage, 0.01f, "the rail is what the unit that MEASURED it says")
        assertEquals(-60f, battery.current, 0.01f, "both uBoxes draw 30 A off one pack, and + is charging")
        assertEquals(-2346f, battery.power, 1f, "and only the unit with a rail contributes power")
        assertTrue(battery.socKnown, "the controllers' own gauge is a real state of charge")
        assertEquals(84f, battery.soc, 0.1f, "which rides the SETUP overlay, not the per-unit decode")
        loop.cancel(); device.cancel()
    }

    /**
     * The other road to the same place: a head unit that does not answer opcode
     * 96 **at all**, rather than answering it with the sentinel. Silence and a
     * contentless answer are two distinct paths through [VescGatewayProtocol] —
     * the request's `onSilence` and its `consume` — and a fallback wired to only
     * one of them leaves a whole class of head unit with no battery.
     */
    @Test
    fun `a derived slot falls back to the rail when the gateway answers nothing at all`() = runTest {
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescBmsValues.OPCODE_BMS_GET_VALUES) ScriptedReply(TIMEOUT_MS, null)
            else railScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(pastWarmupMs + 2 * oneSilentCycleMs)
        runCurrent()

        assertEquals(
            78.2f,
            assertNotNull(p.latestData(0), "a silent BMS ask must still leave a derived battery").voltage,
            0.01f
        )
        loop.cancel(); device.cancel()
    }

    /**
     * `0` is `VescValues`' "this controller has no battery configuration", and
     * [ru.sodovaya.volty.data.bms.derivedBatteryFrom] would render it as a
     * confident 0 % — the same unknown-vs-zero trap `G §9` names, reached
     * through the fold this time. The controller that DOES have a battery
     * configured is the one to ask.
     */
    @Test
    fun `an unconfigured controller's zero battery level is not a confident zero percent`() = runTest {
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when (opcode) {
                VescBmsValues.OPCODE_BMS_GET_VALUES -> ScriptedReply(LATENCY_MS, noBmsFrame())
                VescValues.OPCODE_GET_VALUES_SETUP ->
                    ScriptedReply(LATENCY_MS, setupFrame(battLevelRaw = if (canId == CAN_A) 0 else 500))
                else -> railScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(pastWarmupMs)
        runCurrent()

        val battery = assertNotNull(p.latestData(0))
        assertTrue(battery.socKnown, "an unconfigured front uBox must not make the whole pack unknown")
        assertEquals(50f, battery.soc, 0.1f, "the rear uBox's real gauge is the one that answers")
        loop.cancel(); device.cancel()
    }

    /**
     * **The sentinel means two things and the wire cannot tell them apart.**
     * `ant_bms.c:900-905` memsets `bms_values` and sets `can_id = -1` on every
     * BLE disconnect of the pack — including the ordinary blips the bridge
     * recovers from by itself — so `0xFF` is "this VESC has no BMS" *and* "the
     * bridge is not up yet". Believing it immediately would put a rail-derived
     * substitute on the Battery screen for the first cycles of every connect to
     * the rider's own head unit, indistinguishable from the real reading.
     *
     * Sampled repeatedly across the warm-up rather than once at the end: a
     * single assertion after the fact cannot tell "never published" from
     * "published and then replaced".
     */
    @Test
    fun `a sentinel is a warm-up before it is a verdict`() = runTest {
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescBmsValues.OPCODE_BMS_GET_VALUES) ScriptedReply(LATENCY_MS, noBmsFrame())
            else railScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        // The controllers are answering the whole time, so a fallback with no
        // warm-up would have published on the very first cycle.
        var elapsed = 0L
        while (elapsed + healthyCycleMs < VescGatewayProtocol.HOSTED_BMS_WARMUP_MS) {
            advanceTimeBy(healthyCycleMs)
            runCurrent()
            elapsed += healthyCycleMs
            assertNull(
                p.latestData(0),
                "at t=$elapsed ms the sentinel is still a bridge that may be coming up, not a verdict"
            )
        }
        assertNotNull(p.latestMotion(1), "the premise: the controllers were reporting all along")

        advanceTimeBy(pastWarmupMs)
        runCurrent()
        assertNotNull(
            p.latestData(0),
            "and once it has persisted, the substitute is the honest reading to show"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * **The rider's own head unit, on the connect where the ANT bridge is still
     * reconnecting.** It answers the sentinel for the first seconds and then the
     * real frame. The rider must never be shown the substitute in between — that
     * is a plausible wrong number standing in for a real one that is on its way,
     * which is precisely the failure this part exists to remove.
     */
    @Test
    fun `an ANT bridge that comes up during the warm-up is never preceded by a substitute`() = runTest {
        var bridgeUp = false
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                opcode != VescBmsValues.OPCODE_BMS_GET_VALUES -> railScript()(canId, opcode)
                bridgeUp -> ScriptedReply(LATENCY_MS, bmsFrame())
                else -> ScriptedReply(LATENCY_MS, noBmsFrame())
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        // ant_bms.c's fast path: 500 ms settle, direct open, first poll — well
        // inside the warm-up, and the whole time nothing may be published.
        repeat(20) {
            advanceTimeBy(healthyCycleMs)
            runCurrent()
            assertNull(p.latestData(0), "the bridge is reconnecting; a substitute here would be a lie")
        }

        bridgeUp = true
        advanceTimeBy(2 * healthyCycleMs)
        runCurrent()

        assertEquals(
            75.5f, assertNotNull(p.latestData(0)).voltage, 0.001f,
            "and the first thing the rider ever sees for this pack is the BMS's own number"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * **The rider's own head unit once it IS up**: a real reading outranks the
     * substitute, and goes on outranking it across the gaps a live BMS has.
     * Swapping a hosted reading for a computed one the moment it misses a cycle
     * would put a number on the Battery screen that reads exactly as real while
     * the pack it describes has stopped talking.
     */
    @Test
    fun `a real hosted reading outranks the substitute while it is still current`() = runTest {
        var bmsAnswers = true
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                opcode != VescBmsValues.OPCODE_BMS_GET_VALUES -> railScript()(canId, opcode)
                bmsAnswers -> ScriptedReply(LATENCY_MS, bmsFrame())
                else -> ScriptedReply(TIMEOUT_MS, null)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(pastWarmupMs)
        runCurrent()
        assertEquals(
            75.5f, assertNotNull(p.latestData(0)).voltage, 0.001f,
            "a real BMS reading beats the fallback, warm-up or no warm-up"
        )

        bmsAnswers = false
        // Deliberately past the WARM-UP and short of the staleness window: this
        // is the only stretch where the two rules disagree, so it is the only
        // stretch that can tell "the hosted reading outranks the substitute"
        // from "the substitute simply had not warmed up yet". Stopping at the
        // warm-up boundary instead left the precedence rule pinned by nothing.
        advanceTimeBy(VescGatewayProtocol.STALE_READING_MS - 4 * oneSilentCycleMs)
        runCurrent()
        assertTrue(
            currentTime > VescGatewayProtocol.HOSTED_BMS_WARMUP_MS,
            "premise: the substitute has had every chance to warm up by now"
        )

        assertEquals(
            75.5f, assertNotNull(p.latestData(0)).voltage, 0.001f,
            "and it keeps beating it while it is current — not quietly overwritten with 78.2 V " +
                "computed off the controllers' rail"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * The inequality the "not clearing [contentlessSinceMs] on a real reading is
     * equivalent" argument rests on: after a slot has ever produced a real
     * reading the substitute is held off by the staleness window, so a warm-up
     * shorter than that window can never be the binding constraint. Invert them
     * and the argument in `bmsRequest` stops being true — this test is what
     * makes that a build failure rather than a silent regression.
     */
    @Test
    fun `the warm-up is shorter than the staleness window`() {
        assertTrue(
            VescGatewayProtocol.HOSTED_BMS_WARMUP_MS < VescGatewayProtocol.STALE_READING_MS,
            "a warm-up longer than the precedence window would make the un-cleared contentless " +
                "run observable, and `bmsRequest` claims it is not"
        )
    }

    /**
     * The warm-up is per CONNECTION. A reconnect is exactly when the head unit's
     * ANT bridge is most likely to still be coming up, so a new session that
     * inherited the old one's elapsed warm-up would publish a substitute on its
     * very first cycle — the one moment this whole rule exists to cover.
     */
    @Test
    fun `reset restarts the sentinel's warm-up`() = runTest {
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescBmsValues.OPCODE_BMS_GET_VALUES) ScriptedReply(LATENCY_MS, noBmsFrame())
            else railScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(pastWarmupMs)
        runCurrent()
        assertNotNull(p.latestData(0), "the first session's warm-up elapsed and the substitute served")

        p.reset()
        advanceTimeBy(4 * healthyCycleMs)
        runCurrent()

        assertNull(
            p.latestData(0),
            "the new session has to wait out its own warm-up — the bridge may be reconnecting, " +
                "which is the whole reason a reconnect is not a verdict"
        )
        advanceTimeBy(pastWarmupMs)
        runCurrent()
        assertNotNull(p.latestData(0), "and then it serves again")
        loop.cancel(); device.cancel()
    }

    /**
     * **Precedence, not permanence.** The counterpart of the test above, and the
     * reason the rule is an age rather than a latch: a bridge that dies
     * permanently mid-ride used to leave the rider with no battery at all for
     * the rest of the connection, while the substitute sat there available. Once
     * the hosted reading is old enough that the orchestrator would be greying
     * the pack out anyway, the substitute is better than an empty slot.
     */
    @Test
    fun `a hosted BMS lost mid-ride hands back to the substitute once its reading is stale`() = runTest {
        var bmsAnswers = true
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                opcode != VescBmsValues.OPCODE_BMS_GET_VALUES -> railScript()(canId, opcode)
                bmsAnswers -> ScriptedReply(LATENCY_MS, bmsFrame())
                else -> ScriptedReply(TIMEOUT_MS, null)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(pastWarmupMs)
        runCurrent()
        assertEquals(75.5f, assertNotNull(p.latestData(0)).voltage, 0.001f)

        bmsAnswers = false
        advanceTimeBy(VescGatewayProtocol.STALE_READING_MS + 4 * oneSilentCycleMs)
        runCurrent()

        assertEquals(
            78.2f, assertNotNull(p.latestData(0)).voltage, 0.01f,
            "the bridge is not coming back; a rail-derived battery beats none at all"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * The counterpart on a slot the profile actually configured: a BMS the
     * rider named must go offline when it goes quiet, never be impersonated by
     * the controllers beside it. Only a DERIVED slot has a fallback.
     */
    @Test
    fun `a battery the profile owns is never filled from the controllers' rail`() = runTest {
        val p = protocol()
        val link = FakeGateway(p) { canId, opcode ->
            if (opcode == VescBmsValues.OPCODE_BMS_GET_VALUES) ScriptedReply(TIMEOUT_MS, null)
            else railScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(pastWarmupMs + 4 * oneSilentCycleMs)
        runCurrent()

        assertNull(p.latestData(0), "a configured BMS that stops answering must not be impersonated")
        assertNotNull(p.latestMotion(0), "while the controllers that would have fed a fallback answer fine")
        loop.cancel(); device.cancel()
    }

    /**
     * A derived battery is arithmetic on controller telemetry, so with no
     * telemetry there is no battery. Materialising the slot at 0 V would put a
     * permanently-flat pack on the Battery screen of a vehicle whose CAN bus is
     * simply asleep — and, worse, would end the slot's latency, so it could
     * never go back to being invisible.
     */
    @Test
    fun `a derived slot stays empty while no controller has answered`() = runTest {
        val p = derivedProtocol()
        val link = FakeGateway(p) { _, _ -> ScriptedReply(TIMEOUT_MS, null) }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(2 * VescGatewayProtocol.HOSTED_BMS_WARMUP_MS)
        runCurrent()

        assertNull(p.latestData(0), "nothing reported a rail, so there is no battery to compute")
        loop.cancel(); device.cancel()
    }

    /**
     * **The stamp is load-bearing, and the premise that made it look equivalent
     * was false.** A controller answering `GET_VALUES` with a right-opcode but
     * undecodable body has its reply CONSUMED and its request settled, so
     * `onSilence` never runs and its `motion` entry survives frozen. The derived
     * battery must be dated by the contributor the numbers actually came from,
     * not by the frozen one that happens to be first in the list.
     *
     * The premise is asserted rather than assumed: if the two contributors ever
     * carried the same decoder stamp this test would be vacuous, so it fails
     * loudly instead.
     */
    @Test
    fun `the derived battery is stamped by its newest contributor, not its first`() = runTest {
        var frontDecodes = true
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                opcode == VescBmsValues.OPCODE_BMS_GET_VALUES -> ScriptedReply(LATENCY_MS, noBmsFrame())
                opcode == VescValues.OPCODE_GET_VALUES && canId == CAN_A && !frontDecodes ->
                    ScriptedReply(LATENCY_MS, undecodableValuesFrame())
                else -> railScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(2 * healthyCycleMs)
        runCurrent()
        frontDecodes = false
        // Past the warm-up so the substitute publishes at all, and well inside
        // STALE_READING_MS so the frozen front uBox is still a contributor —
        // which is what makes `first` and `max` disagree here.
        advanceTimeBy(pastWarmupMs)
        runCurrent()

        val stale = assertNotNull(p.latestMotion(0), "the front uBox's frozen decode is still there").timestamp
        val fresh = assertNotNull(p.latestMotion(1), "while the rear keeps decoding").timestamp
        assertTrue(fresh > stale, "premise: the two contributors carry different decoder stamps")
        assertEquals(
            fresh,
            assertNotNull(p.latestData(0)).timestamp,
            "the derived battery is dated by the contributor its numbers came from"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * **The same hole, as a behaviour rather than a stamp.** A controller stuck
     * emitting undecodable `GET_VALUES` is never dropped by `onSilence`, so
     * without an age filter it contributes its frozen current and power to the
     * summed derived battery for the rest of the ride — a real 30 A that stopped
     * being measured minutes ago.
     */
    @Test
    fun `a controller whose decodes have gone stale stops feeding the derived battery`() = runTest {
        var frontDecodes = true
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                opcode == VescBmsValues.OPCODE_BMS_GET_VALUES -> ScriptedReply(LATENCY_MS, noBmsFrame())
                opcode == VescValues.OPCODE_GET_VALUES && canId == CAN_A && !frontDecodes ->
                    ScriptedReply(LATENCY_MS, undecodableValuesFrame())
                else -> railScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(2 * healthyCycleMs)
        runCurrent()
        frontDecodes = false
        advanceTimeBy(pastWarmupMs)
        runCurrent()

        assertEquals(
            -60f, assertNotNull(p.latestData(0)).current, 0.01f,
            "a decode that has only just gone stale still counts — one bad frame is not an outage"
        )

        advanceTimeBy(VescGatewayProtocol.STALE_READING_MS)
        runCurrent()

        assertEquals(
            -30f, assertNotNull(p.latestData(0)).current, 0.01f,
            "but a controller the orchestrator would already be drawing as offline must stop " +
                "contributing its frozen 30 A"
        )
        assertEquals(
            78.2f, assertNotNull(p.latestData(0)).voltage, 0.01f,
            "the rear uBox is still measuring the rail, so the battery itself stays"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * And when EVERY contributor has gone stale, the derived battery must stop
     * being republished at all — otherwise a fresh `BmsData` every cycle makes
     * it structurally incapable of ever reading as stale, and the orchestrator's
     * own sweep can never grey it out.
     *
     * Counted through the real [PackSampleGate] / [routePackSamples] pair a
     * `ConnectionSession` uses, because "stopped publishing" is a statement
     * about new SAMPLES: `latestData` keeps answering with the last one either
     * way, so an accessor assertion could not tell the two apart.
     */
    @Test
    fun `a derived battery stops producing samples once every contributor has gone stale`() = runTest {
        var decodes = true
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                opcode == VescBmsValues.OPCODE_BMS_GET_VALUES -> ScriptedReply(LATENCY_MS, noBmsFrame())
                opcode == VescValues.OPCODE_GET_VALUES && !decodes ->
                    ScriptedReply(LATENCY_MS, undecodableValuesFrame())
                else -> railScript()(canId, opcode)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }
        val gate = PackSampleGate(p.packCount)
        var samples = 0
        fun drain() { routePackSamples(p, gate) { _, _, _ -> samples++ } }

        advanceTimeBy(pastWarmupMs)
        runCurrent()
        drain()
        assertEquals(1, samples, "the substitute is publishing while its contributors are live")

        decodes = false
        advanceTimeBy(2 * healthyCycleMs)
        runCurrent()
        drain()
        assertEquals(2, samples, "a frozen-but-recent contributor still produces a sample")

        advanceTimeBy(VescGatewayProtocol.STALE_READING_MS)
        runCurrent()
        drain()
        val settled = samples
        repeat(5) { advanceTimeBy(healthyCycleMs); runCurrent(); drain() }

        assertEquals(
            settled, samples,
            "with every contributor older than the staleness window there is nothing to compute, " +
                "and the pack has to be allowed to age out"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * Every "how long since" this protocol holds is about ONE connection, and on
     * this app the next one can be a different vehicle — one whose head unit
     * hosts nothing at all. A `hostedBmsAtMs` carried over would hold the
     * substitute off that vehicle for a whole staleness window while its rider
     * looked at an empty Battery screen.
     */
    @Test
    fun `reset forgets that the gateway hosted a BMS`() = runTest {
        var bmsAnswers = true
        val p = derivedProtocol()
        val link = FakeGateway(p) { canId, opcode ->
            when {
                opcode != VescBmsValues.OPCODE_BMS_GET_VALUES -> railScript()(canId, opcode)
                bmsAnswers -> ScriptedReply(LATENCY_MS, bmsFrame())
                else -> ScriptedReply(TIMEOUT_MS, null)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        advanceTimeBy(2 * healthyCycleMs)
        runCurrent()
        assertEquals(75.5f, assertNotNull(p.latestData(0)).voltage, 0.001f)

        p.reset()
        bmsAnswers = false
        // Past the new session's own warm-up, but well inside the staleness
        // window the OLD session's hosted reading would otherwise still hold.
        advanceTimeBy(pastWarmupMs + 4 * oneSilentCycleMs)
        runCurrent()

        assertEquals(
            78.2f,
            assertNotNull(
                p.latestData(0),
                "a new session must derive its own battery, not wait out the old head unit's precedence"
            ).voltage,
            0.01f
        )
        loop.cancel(); device.cancel()
    }

    // ------------------------------------------------------------------
    // 7. The EXISTING staleness / latent-slot machinery covers the rest
    // ------------------------------------------------------------------

    private fun scooter(): Pair<LinkSpec, VescGatewayProtocol> {
        val packs = listOf(
            Pack(index = 0, label = "ANT", bmsType = BmsType.VESC_BMS, bmsAddress = "HEAD")
        )
        val controllers = listOf(
            Controller(0, "Front", ControllerType.VESC, "HEAD", canId = CAN_A),
            Controller(1, "Rear", ControllerType.VESC, "HEAD", canId = CAN_B)
        )
        val spec = planLinks(packs, controllers).single()
        val protocol = controllerMotionProtocol(
            kind = spec.protocolKind,
            deriveBattery = false,
            motor = MotorConfig(),
            link = spec
        )
        return spec to assertIs<VescGatewayProtocol>(
            protocol,
            "the product owner's scooter must resolve to the multiplexer"
        )
    }

    /**
     * Sleeping controllers come for free: no reply → no sample → the staleness
     * sweep already in [VehicleConnection.submitMotion] marks that controller
     * offline while the hosted battery keeps reporting. Nothing in this task
     * builds a second mechanism; this drives the real one, through the real
     * routing helpers a [ru.sodovaya.volty.data.ble.ConnectionSession] uses.
     */
    @Test
    fun `a silent controller goes offline while the hosted battery stays online`() = runTest {
        val (spec, p) = scooter()
        var awake = true
        val link = FakeGateway(p) { canId, opcode ->
            if (canId == CAN_B && !awake) ScriptedReply(TIMEOUT_MS, null)
            else healthyScript()(canId, opcode)
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        var nowMs = 0L
        var latest: VehicleData? = null
        val orchestrator = VehicleConnection(
            packs = listOf(Pack(0, "ANT", BmsType.VESC_BMS, "HEAD")),
            controllers = listOf(
                Controller(0, "Front", ControllerType.VESC, "HEAD", canId = CAN_A),
                Controller(1, "Rear", ControllerType.VESC, "HEAD", canId = CAN_B)
            ),
            topology = PackTopology.PARALLEL,
            onVehicleData = { latest = it },
            clock = { Instant.fromEpochMilliseconds(nowMs) }
        )
        val packGate = PackSampleGate(p.packCount)
        val motionGate = MotionSampleGate(p.controllerCount)

        fun drain() {
            routePackSamples(p, packGate) { local, data, sections ->
                orchestrator.submit(spec.globalPackIndex(local), data, sections)
            }
            routeControllerSamples(p, motionGate) { local, data ->
                orchestrator.submitMotion(spec.globalControllerIndex(local), data)
            }
        }

        repeat(3) { advanceTimeBy(1_000); runCurrent(); nowMs += 1_000; drain() }
        assertTrue(assertNotNull(latest).controllers.all { it.isOnline }, "both uBoxes are awake")

        awake = false
        // Past BleConfig.packOfflineAfterMs (12 s) of wall-clock, while the
        // front uBox and the battery keep feeding the sweep.
        repeat(20) { advanceTimeBy(1_000); runCurrent(); nowMs += 1_000; drain() }

        val snapshot = assertNotNull(latest)
        assertTrue(snapshot.controllers.first { it.controller.index == 0 }.isOnline, "front still riding")
        assertFalse(
            snapshot.controllers.first { it.controller.index == 1 }.isOnline,
            "the sleeping rear uBox aged out through the EXISTING staleness sweep"
        )
        assertTrue(snapshot.packs.single().isOnline, "the hosted battery never stopped reporting")

        awake = true
        repeat(3) { advanceTimeBy(1_000); runCurrent(); nowMs += 1_000; drain() }
        assertTrue(
            assertNotNull(latest).controllers.first { it.controller.index == 1 }.isOnline,
            "and it comes back online on its first reply"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * The same scooter with its sources numbered so that **every LOCAL index
     * the protocol speaks differs from the GLOBAL one the vehicle is keyed
     * by**: the battery is pack 0, the uBoxes are controllers 1 and 2, so local
     * 0 -> global 1 and local 1 -> global 2.
     */
    private fun offsetScooter(): Pair<LinkSpec, VescGatewayProtocol> {
        val spec = planLinks(offsetPacks(), offsetControllers()).single()
        return spec to assertIs<VescGatewayProtocol>(
            controllerMotionProtocol(
                kind = spec.protocolKind,
                deriveBattery = false,
                motor = MotorConfig(),
                link = spec
            )
        )
    }

    private fun offsetPacks() =
        listOf(Pack(index = 0, label = "ANT", bmsType = BmsType.VESC_BMS, bmsAddress = "HEAD"))

    private fun offsetControllers() = listOf(
        Controller(1, "Front", ControllerType.VESC, "HEAD", canId = CAN_A),
        Controller(2, "Rear", ControllerType.VESC, "HEAD", canId = CAN_B)
    )

    /**
     * The routing seam under the condition that can actually break it. Every
     * other end-to-end test on this link runs with local == global, where a
     * multiplexer that routed by arrival position would be indistinguishable
     * from one that routes by the source it asked. Here it is not: get it wrong
     * and the front uBox's 0.500 duty is published as the REAR's, on a vehicle
     * whose two motors are meant to be independently readable.
     */
    @Test
    fun `each uBox lands in its own vehicle-global slot when local and global differ`() = runTest {
        val (spec, p) = offsetScooter()
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        var latest: VehicleData? = null
        val orchestrator = VehicleConnection(
            packs = offsetPacks(),
            controllers = offsetControllers(),
            topology = PackTopology.PARALLEL,
            onVehicleData = { latest = it }
        )

        runCycles(1)
        routeControllerSamples(p, MotionSampleGate(p.controllerCount)) { local, data ->
            orchestrator.submitMotion(spec.globalControllerIndex(local), data)
        }
        routePackSamples(p, PackSampleGate(p.packCount)) { local, data, sections ->
            orchestrator.submit(spec.globalPackIndex(local), data, sections)
        }

        val published = assertNotNull(latest)
        assertEquals(listOf(1, 2), published.controllers.map { it.controller.index })
        assertTrue(
            abs(published.controllers.first { it.controller.index == 1 }.data.dutyPercent - 50f) < 0.01f,
            "canId $CAN_A rides LOCAL 0 and must land on GLOBAL 1"
        )
        assertTrue(
            abs(published.controllers.first { it.controller.index == 2 }.data.dutyPercent - 25f) < 0.01f,
            "canId $CAN_B rides LOCAL 1 and must land on GLOBAL 2"
        )
        assertTrue(
            abs(published.packs.single().data.voltage - 75.5f) < 0.001f,
            "and the hosted battery, local 0 as well, lands on global 0"
        )
        loop.cancel(); device.cancel()
    }

    /**
     * A uBox the stored profile does not know about materialises through the
     * EXISTING latent-slot machinery ([VehicleConnection]'s `latentControllers`)
     * on its first gateway sample — again, nothing new is built for it.
     */
    @Test
    fun `an unstored controller materialises its latent slot on its first gateway sample`() = runTest {
        val (spec, p) = scooter()
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        var latest: VehicleData? = null
        val orchestrator = VehicleConnection(
            packs = emptyList(),
            controllers = listOf(Controller(0, "Front", ControllerType.VESC, "HEAD", canId = CAN_A)),
            latentControllers = listOf(Controller(1, "Rear", ControllerType.VESC, "HEAD", canId = CAN_B)),
            topology = PackTopology.PARALLEL,
            onVehicleData = { latest = it }
        )
        assertEquals(1, orchestrator.snapshot().controllers.size, "the latent slot is invisible at first")

        runCycles(1)
        val motionGate = MotionSampleGate(p.controllerCount)
        routeControllerSamples(p, motionGate) { local, data ->
            orchestrator.submitMotion(spec.globalControllerIndex(local), data)
        }

        val published = assertNotNull(latest)
        assertEquals(2, published.controllers.size, "the rear uBox's first sample materialised its slot")
        assertTrue(published.controllers.all { it.isOnline })
        loop.cancel(); device.cancel()
    }

    // ------------------------------------------------------------------
    // 8. The decoder's output, folded by the REAL aggregator (`I` Task 7)
    // ------------------------------------------------------------------

    /**
     * **The cross-layer test nobody wrote.** Everything above stops at
     * [VescGatewayProtocol.latestMotion]; `MotionAggregatorTest` starts at a
     * hand-built [ru.sodovaya.volty.domain.model.ControllerData]. The only thing
     * that ever connected the two was a sentence in a comment — which is why a
     * decoder that could not express "I did not measure this" and a fold that
     * would have honoured it if it could were invisible to the whole suite.
     *
     * The vehicle here is the one shape a VESC head unit makes reachable: a
     * healthy uBox on [CAN_A] beside a node on [CAN_B] that **answers both
     * opcodes** while measuring nothing behind them — `v_in = 0`,
     * `battery_level = 0`, and a `speed` field left at 0 while its motor turns
     * at 12 000 eRPM. Every byte is the frame table's, every decode is
     * `VescValues`', and the number asserted is what
     * [ru.sodovaya.volty.data.ble.VehicleConnection] publishes off
     * [ru.sodovaya.volty.domain.stats.MotionAggregator].
     *
     * Before `I` Task 7 that node's three phantom fields were indistinguishable
     * from measurements at every layer: the decoder could not clear
     * `hasInputVoltage` (nothing in the VESC path ever assigned it), so the
     * average over both controllers reported **39.1 V** on a 78.2 V pack, and
     * the battery level was averaged with a confident `0` to **42 %**.
     */
    @Test
    fun `a node that answers without measuring does not halve the vehicle's rail`() = runTest {
        val (spec, p) = scooter()
        val link = FakeGateway(p) { canId, opcode ->
            when (opcode) {
                VescValues.OPCODE_GET_VALUES_SETUP ->
                    if (canId == CAN_A) ScriptedReply(LATENCY_MS, setupFrame())
                    // Answers opcode 47 with an empty speed field beside a
                    // turning motor, and no battery configuration.
                    else ScriptedReply(
                        LATENCY_MS,
                        setupFrame(speedMsRaw = 0, battLevelRaw = 0)
                    )
                VescValues.OPCODE_GET_VALUES ->
                    if (canId == CAN_A) ScriptedReply(LATENCY_MS, valuesFrame(dutyRaw = 500))
                    // Answers opcode 4 with a 0 V rail — a node that relays the
                    // frame without an ADC behind it.
                    else ScriptedReply(LATENCY_MS, valuesFrame(dutyRaw = 250, vInRaw = 0))
                VescBmsValues.OPCODE_BMS_GET_VALUES -> ScriptedReply(LATENCY_MS, bmsFrame())
                else -> ScriptedReply(TIMEOUT_MS, null)
            }
        }
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        var latest: VehicleData? = null
        val orchestrator = VehicleConnection(
            packs = listOf(Pack(0, "ANT", BmsType.VESC_BMS, "HEAD")),
            controllers = listOf(
                Controller(0, "Front", ControllerType.VESC, "HEAD", canId = CAN_A),
                Controller(1, "Rear", ControllerType.VESC, "HEAD", canId = CAN_B)
            ),
            topology = PackTopology.PARALLEL,
            onVehicleData = { latest = it }
        )

        runCycles(1)
        routeControllerSamples(p, MotionSampleGate(p.controllerCount)) { local, data ->
            orchestrator.submitMotion(spec.globalControllerIndex(local), data)
        }

        val published = assertNotNull(latest)
        // Both controllers really are in the fold — an aggregate that simply
        // never saw the second node would pass most of what follows.
        assertEquals(2, published.controllers.size)
        assertTrue(published.controllers.all { it.isOnline }, "the phantom node ANSWERED; it is online")
        assertFalse(
            published.controllers.first { it.controller.index == 1 }.data.hasInputVoltage,
            "and the decoder is what says its rail is not a measurement"
        )

        val motion = published.motion
        assertEquals(78.2f, motion.inputVoltageV, 0.01f, "the uBox's rail, NOT 39.1 V")
        assertTrue(motion.hasInputVoltage, "one controller measured it, and the average is over that one")
        assertEquals(
            0.84f, assertNotNull(motion.batteryLevelFraction), 0.001f,
            "the uBox's pack level, NOT 42 %"
        )
        assertFalse(motion.hasPower, "a total missing a term is not the vehicle's power")
        assertEquals(
            78.2f * 30f, motion.powerW, 1f,
            "…and the partial behind that flag is the uBox's real watts, not its watts plus a placeholder"
        )
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(47.0f, motion.speedKmh, 0.05f, "the only controller that reported a speed")

        // The per-unit folds are untouched by any of it: duty still maxes, and
        // the flagless currents still sum BOTH nodes.
        assertEquals(50f, motion.dutyPercent, 0.01f)
        assertEquals(60f, motion.batteryCurrentA, 0.01f)
        loop.cancel(); device.cancel()
    }
}
