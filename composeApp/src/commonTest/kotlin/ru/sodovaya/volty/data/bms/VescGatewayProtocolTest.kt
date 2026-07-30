package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
        tempMosRaw: Int = 400
    ): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescValues.OPCODE_GET_VALUES.toByte()
        i16(o, tempMosRaw); i16(o, 680)
        i32(o, -8_250); i32(o, currentInRaw)
        i32(o, 0); i32(o, 0)                      // id, iq
        i16(o, dutyRaw)
        i32(o, rpm)
        i16(o, 782)
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
        battLevelRaw: Int = 840
    ): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescValues.OPCODE_GET_VALUES_SETUP.toByte()
        i16(o, 520); i16(o, 680)
        i32(o, -8_250); i32(o, currentInRaw)
        i16(o, 760)
        i32(o, 12_000)
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

    private fun protocol(
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
        lateReplyGuardMs = GUARD_MS
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
        val a = assertNotNull(p.latestMotion(0), "the primary answered")
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
     * that decode through as the primary's own `ControllerData` and
     * `MotionAggregator`, which SUMS currents across controllers, doubles it.
     */
    @Test
    fun `setup contributes speed and odometer but never the summed per-unit currents`() = runTest {
        val p = protocol()
        val link = FakeGateway(p, healthyScript())
        val device = link.runDevice(this)
        val loop = launch { p.runPollLoop(link.send) }

        runCycles(1)

        val primary = assertNotNull(p.latestMotion(0))
        // From SETUP: the vehicle-level scalars.
        assertEquals(SpeedSource.REPORTED, primary.speedSource)
        assertTrue(abs(primary.speedKmh - 47.0f) < 0.05f, "speed came from the SETUP frame")
        assertTrue(abs(primary.odometerKm - 1284.6f) < 0.1f, "odometer came from the SETUP frame")
        assertEquals(0.84f, primary.batteryLevelFraction)
        // From GET_VALUES: everything per-unit. 3_000/100 = 30 A, not 9_999/100.
        assertTrue(
            abs(primary.batteryCurrentA - 30f) < 0.01f,
            "per-unit current must come from GET_VALUES, not the SETUP frame's CAN-wide sum"
        )
        assertTrue(abs(primary.dutyPercent - 50f) < 0.01f, "per-unit duty likewise")

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
                // The primary's per-unit request answers just after we gave up
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
        // on local 1, not here. What it pins is that the primary produced no
        // sample of its own — the drop itself is the assertion below.
        assertNull(p.latestMotion(0), "the primary's own request went unanswered, so it has no sample")
        assertTrue(
            abs(assertNotNull(p.latestMotion(1)).dutyPercent - 25f) < 0.01f,
            "the second uBox reports ITS own 0.250 duty, not the late 0.500 meant for the first"
        )
        loop.cancel(); device.cancel()
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
        VescGatewayProtocol(controllers = (0 until 5).map { GatewaySource(globalIndex = it, canId = it + 1) })

        val e = assertFailsWith<IllegalArgumentException> {
            // One more controller is two more requests: 11 x 500 + 50 = 5550 ms.
            VescGatewayProtocol(controllers = (0 until 6).map { GatewaySource(globalIndex = it, canId = it + 1) })
        }
        assertTrue(
            e.message.orEmpty().contains("stale-sample budget"),
            "the refusal has to say what it is protecting, not just that it refused: ${e.message}"
        )
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
}
