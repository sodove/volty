package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.MotorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `CanBusScanner` on both VESC protocols (G2 Task 5) — the wire half of
 * `G §3` flow 4.
 *
 * ## Why there are two implementations, and why both are tested here
 *
 * A rider who has just picked their head unit owns a **one-controller** vehicle.
 * That is not a `LinkSpec.isGatewayLink`, so it speaks [VescProtocol], not
 * [VescGatewayProtocol] — and CAN discovery has to work there or it would only
 * ever be reachable once the rider had already typed in the CAN ids it exists to
 * find. The two protocols get the scan onto the wire differently (a burst-polled
 * link can write from the caller; a gateway must hand it to its serial loop, see
 * `C §10.1`), so both paths are pinned.
 *
 * ## Bounded on purpose
 *
 * Every test here drives virtual time by a fixed amount and cancels the poll
 * loop. An unbounded delayed loop under `runTest` advances virtual time forever
 * and **wedges the build instead of failing it**, and a 3.5 s reply window is
 * exactly the shape that trips it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanBusScanTest {

    private companion object {
        const val LATENCY_MS = 20L
        const val CYCLE_GAP_MS = 50L
        const val TIMEOUT_MS = 400L
        const val GUARD_MS = 100L
        const val CAN_A = 41
    }

    /** A `COMM_PING_CAN` reply: `[62][id][id]…`, no count prefix, no terminator. */
    private fun pingReply(vararg ids: Int): ByteArray =
        VescPacket.frame(byteArrayOf(VescCan.OPCODE_PING_CAN.toByte()) + ids.map { it.toByte() }.toByteArray())

    /** Strip the VESC framing and return the payload's first byte. */
    private fun opcodeOf(frame: ByteArray): Int = frame[2].toInt() and 0xFF

    // ==================================================================
    // VescProtocol — the plain link a picked head unit starts on
    // ==================================================================

    @Test
    fun `a plain VESC link puts one unwrapped PING_CAN on the wire and returns its ids`() = runTest {
        val p = VescProtocol()
        val sent = mutableListOf<ByteArray>()

        val scan = async { p.scanCanBus { sent += it } }
        runCurrent()

        assertEquals(1, sent.size, "exactly one PING_CAN")
        assertEquals(VescCan.OPCODE_PING_CAN, opcodeOf(sent.single()))
        // Never wrapped: PING_CAN asks the endpoint we are connected to, which
        // is the only node that knows what else is on the bus.
        assertTrue(
            opcodeOf(sent.single()) != VescCan.OPCODE_FORWARD_CAN,
            "PING_CAN must not be forwarded"
        )

        p.onNotification(pingReply(10, 11))
        assertEquals(listOf(10, 11), scan.await())
    }

    /** An empty bus is a real answer, and must not be reported as silence. */
    @Test
    fun `an empty reply is an empty list, not null`() = runTest {
        val p = VescProtocol()
        val scan = async { p.scanCanBus { } }
        runCurrent()
        p.onNotification(pingReply())
        assertEquals(emptyList(), scan.await())
    }

    @Test
    fun `silence within the window is null`() = runTest {
        val p = VescProtocol()
        val scan = async { p.scanCanBus { } }
        runCurrent()
        advanceTimeBy(CanBusScanner.REPLY_TIMEOUT_MS + 1)
        assertNull(scan.await())
    }

    /**
     * The firmware takes ~2.55 s on every `PING_CAN` (`C §10.2`), so a window
     * that expired before then would time out on a perfectly healthy bus. This
     * pins that the reply is still accepted well past any per-frame budget.
     */
    @Test
    fun `a reply after the firmware's two and a half seconds is still accepted`() = runTest {
        val p = VescProtocol()
        val scan = async { p.scanCanBus { } }
        runCurrent()
        advanceTimeBy(2_550)
        p.onNotification(pingReply(7))
        assertEquals(listOf(7), scan.await())
    }

    /**
     * A `PING_CAN` reply that arrives with no scan armed — a late one we already
     * gave up on — must be dropped, not held for the next scan. Otherwise the
     * next scan would answer instantly with the previous bus.
     */
    @Test
    fun `a ping reply with no scan armed is dropped`() = runTest {
        val p = VescProtocol()
        val first = async { p.scanCanBus { } }
        runCurrent()
        advanceTimeBy(CanBusScanner.REPLY_TIMEOUT_MS + 1)
        assertNull(first.await())

        // The late reply lands while nothing is armed.
        p.onNotification(pingReply(10))

        val second = async { p.scanCanBus { } }
        runCurrent()
        advanceTimeBy(CanBusScanner.REPLY_TIMEOUT_MS + 1)
        assertNull(second.await(), "the previous bus must not answer this scan")
    }

    /**
     * Single-flight on the plain link too: the firmware discards a second
     * `PING_CAN` inside the window with no error reply, so a second caller joins
     * the first rather than putting a request on the wire that can only be
     * dropped.
     */
    @Test
    fun `two concurrent plain scans share one PING_CAN`() = runTest {
        val p = VescProtocol()
        val sent = mutableListOf<ByteArray>()
        val a = async { p.scanCanBus { sent += it } }
        val b = async { p.scanCanBus { sent += it } }
        runCurrent()

        assertEquals(1, sent.size, "two callers, one PING_CAN")
        p.onNotification(pingReply(10))
        assertEquals(listOf(10), a.await())
        assertEquals(listOf(10), b.await())
    }

    /**
     * The other half of single-flight, and the one that would rot silently: a
     * scan whose window expired must not leave the request armed, or every later
     * scan would join a dead one and wait the window out again without ever
     * putting a `PING_CAN` on the wire.
     */
    @Test
    fun `a scan after a timed-out one issues a fresh PING_CAN`() = runTest {
        val p = VescProtocol()
        val sent = mutableListOf<ByteArray>()

        val first = async { p.scanCanBus { sent += it } }
        runCurrent()
        advanceTimeBy(CanBusScanner.REPLY_TIMEOUT_MS + 1)
        assertNull(first.await())
        assertEquals(1, sent.size)

        val second = async { p.scanCanBus { sent += it } }
        runCurrent()
        assertEquals(2, sent.size, "the second scan must reach the wire, not join a dead one")
        p.onNotification(pingReply(10))
        assertEquals(listOf(10), second.await())
    }

    /** Telemetry keeps decoding while a scan is armed — the scan is not a mode. */
    @Test
    fun `a scan does not stop the link decoding values`() = runTest {
        val p = VescProtocol()
        val scan = async { p.scanCanBus { } }
        runCurrent()

        p.onNotification(setupFrame())
        assertTrue((p.latestMotion(0)?.speedKmh ?: 0f) > 0f, "a live frame must still decode")

        p.onNotification(pingReply(10))
        assertEquals(listOf(10), scan.await())
    }

    /**
     * A torn-down session answers **at once**, and answers silence.
     *
     * Promptness is the assertion, not just the value: a scan left to time out
     * makes a rider watch a spinner for the whole window for a reply that can
     * no longer arrive. And a reply that lands after the teardown must not be
     * credited to it.
     */
    @Test
    fun `reset answers a plain scan at once, with silence`() = runTest {
        val p = VescProtocol()
        val scan = async { p.scanCanBus { } }
        runCurrent()

        p.reset()
        runCurrent()
        assertTrue(scan.isCompleted, "a torn-down session must answer now, not after the whole window")
        assertNull(scan.await())

        // And the disarm holds: a late reply is not credited to the dead scan.
        p.onNotification(pingReply(10))
        assertNull(scan.await())
    }

    // ==================================================================
    // VescGatewayProtocol — the scan goes through the serial loop
    // ==================================================================

    private fun gateway() = VescGatewayProtocol(
        controllers = listOf(GatewaySource(globalIndex = 0, canId = CAN_A, motor = MotorConfig())),
        packs = emptyList(),
        pollIntervalMs = CYCLE_GAP_MS,
        replyTimeoutMs = TIMEOUT_MS,
        lateReplyGuardMs = GUARD_MS
    )

    /**
     * The gateway must NOT write from the caller's coroutine: `C §10.1` allows
     * exactly one request in flight and the loop is the only coroutine that ever
     * holds it. So a scan requested while the loop is not running produces no
     * bytes at all — and, because nothing services it, times out.
     */
    @Test
    fun `a gateway scan writes nothing until its poll loop services it`() = runTest {
        val p = gateway()
        val sent = mutableListOf<ByteArray>()

        val scan = async { p.scanCanBus { sent += it } }
        runCurrent()
        assertTrue(sent.isEmpty(), "the caller's send must never be used on a gateway link")

        advanceTimeBy(VescGatewayProtocol.SCAN_WAIT_MS + 1)
        assertNull(scan.await())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the poll loop puts the scan on the wire and routes the reply back`() = runTest {
        val p = gateway()
        val sent = mutableListOf<ByteArray>()
        // A device that answers whatever it is asked, one latency later.
        val loop = launch {
            p.runPollLoop { frame ->
                sent += frame
                val opcode = opcodeOf(frame)
                launch {
                    kotlinx.coroutines.delay(LATENCY_MS)
                    if (opcode == VescCan.OPCODE_PING_CAN) p.onNotification(pingReply(10, 11))
                }
            }
        }
        runCurrent()

        val scan = async { p.scanCanBus { } }
        // One cycle is enough for the loop to reach the top and take the
        // request: the plan's own request goes silent (nothing answers a
        // forward here), costing timeout + guard, then the gap.
        advanceTimeBy(TIMEOUT_MS + GUARD_MS + CYCLE_GAP_MS + LATENCY_MS + 1)
        runCurrent()

        assertEquals(listOf(10, 11), scan.await())
        // Unwrapped, and exactly one of them.
        val pings = sent.filter { opcodeOf(it) == VescCan.OPCODE_PING_CAN }
        assertEquals(1, pings.size, "one PING_CAN — the firmware discards a second one silently")
        loop.cancel()
    }

    /**
     * Single-flight at the protocol, independent of the component's own refusal:
     * a second call joins the SAME request rather than parking a second
     * `PING_CAN`, which the firmware would silently discard (`C §10.2`).
     */
    @Test
    fun `two concurrent gateway scans share one request`() = runTest {
        val p = gateway()
        val sent = mutableListOf<ByteArray>()
        val loop = launch {
            p.runPollLoop { frame ->
                sent += frame
                val opcode = opcodeOf(frame)
                launch {
                    kotlinx.coroutines.delay(LATENCY_MS)
                    if (opcode == VescCan.OPCODE_PING_CAN) p.onNotification(pingReply(10))
                }
            }
        }
        runCurrent()

        val a = async { p.scanCanBus { } }
        val b = async { p.scanCanBus { } }
        advanceTimeBy(TIMEOUT_MS + GUARD_MS + CYCLE_GAP_MS + LATENCY_MS + 1)
        runCurrent()

        assertEquals(listOf(10), a.await())
        assertEquals(listOf(10), b.await())
        assertEquals(
            1,
            sent.count { opcodeOf(it) == VescCan.OPCODE_PING_CAN },
            "two callers, one PING_CAN"
        )
        loop.cancel()
    }

    /**
     * The plan's own poll must resume afterwards — a scan is one extra request
     * in the cycle, not a state the loop gets stuck in.
     */
    @Test
    fun `the poll loop keeps polling after a scan`() = runTest {
        val p = gateway()
        val sent = mutableListOf<ByteArray>()
        val loop = launch {
            p.runPollLoop { frame ->
                sent += frame
                val opcode = opcodeOf(frame)
                launch {
                    kotlinx.coroutines.delay(LATENCY_MS)
                    if (opcode == VescCan.OPCODE_PING_CAN) p.onNotification(pingReply(10))
                }
            }
        }
        runCurrent()
        val scan = async { p.scanCanBus { } }
        advanceTimeBy(TIMEOUT_MS + GUARD_MS + CYCLE_GAP_MS + LATENCY_MS + 1)
        runCurrent()
        assertEquals(listOf(10), scan.await())

        val afterScan = sent.size
        advanceTimeBy(2 * (TIMEOUT_MS + GUARD_MS + CYCLE_GAP_MS))
        runCurrent()
        assertTrue(sent.size > afterScan, "the plan must keep being polled after a scan")
        assertEquals(
            1,
            sent.count { opcodeOf(it) == VescCan.OPCODE_PING_CAN },
            "and must not re-issue the scan every cycle"
        )
        loop.cancel()
    }

    /**
     * The gateway's plan waits 400 ms per reply; the firmware takes **~2.55 s**
     * on `PING_CAN` alone (`C §10.2`). A scan that inherited the plan's budget
     * would time out on a perfectly healthy bus every single time — so the scan
     * carries its own window, and this is what pins it.
     */
    @Test
    fun `a gateway ping is still accepted after the firmware's two and a half seconds`() = runTest {
        val p = gateway()
        val loop = launch {
            p.runPollLoop { frame ->
                val opcode = opcodeOf(frame)
                if (opcode == VescCan.OPCODE_PING_CAN) {
                    launch {
                        // Longer than replyTimeoutMs by a wide margin, and
                        // exactly the firmware's own cost.
                        kotlinx.coroutines.delay(2_550)
                        p.onNotification(pingReply(10, 11))
                    }
                }
            }
        }
        runCurrent()

        val scan = async { p.scanCanBus { } }
        advanceTimeBy(TIMEOUT_MS + GUARD_MS + CYCLE_GAP_MS + 2_550 + 1)
        runCurrent()

        assertEquals(listOf(10, 11), scan.await())
        loop.cancel()
    }

    @Test
    fun `a gateway scan nobody answers is silence`() = runTest {
        val p = gateway()
        val loop = launch { p.runPollLoop { } }
        runCurrent()
        val scan = async { p.scanCanBus { } }
        advanceTimeBy(VescGatewayProtocol.SCAN_WAIT_MS + 1)
        runCurrent()
        assertNull(scan.await())
        loop.cancel()
    }

    /**
     * A session torn down mid-scan answers immediately rather than leaving the
     * composer's spinner running for the whole outer window.
     */
    @Test
    fun `reset answers a parked gateway scan at once`() = runTest {
        val p = gateway()
        val scan = async { p.scanCanBus { } }
        runCurrent()

        p.reset()
        runCurrent()
        // Promptness IS the assertion: waiting SCAN_WAIT_MS out would give the
        // same null, which is why this cannot be written with advanceUntilIdle.
        assertTrue(scan.isCompleted, "a torn-down session must answer now, not after the whole window")
        assertNull(scan.await())
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun i16(o: MutableList<Byte>, v: Int) {
        o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
    }

    private fun i32(o: MutableList<Byte>, v: Int) {
        o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
        o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
    }

    /** `COMM_GET_VALUES_SETUP` (47) — enough of it for a non-zero speed. */
    private fun setupFrame(): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescValues.OPCODE_GET_VALUES_SETUP.toByte()
        i16(o, 520); i16(o, 680)
        i32(o, -8_250); i32(o, 9_999)
        i16(o, 760)
        i32(o, 12_000)
        i32(o, 13_056)                       // m/s ×1000 -> 47.0 km/h
        i16(o, 782); i16(o, 840)
        i32(o, 154_000); i32(o, 21_000); i32(o, 9_800_000); i32(o, 1_200_000)
        i32(o, 12_400_000); i32(o, 1_284_600_000)
        i32(o, 0)                            // position — unused
        o += 0                               // fault
        return VescPacket.frame(o.toByteArray())
    }
}
