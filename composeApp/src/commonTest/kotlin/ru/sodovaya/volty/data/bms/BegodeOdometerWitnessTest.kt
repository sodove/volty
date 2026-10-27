package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.plausibility.LedgerKind
import ru.sodovaya.volty.domain.plausibility.PlausibilityRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R1: the 0x04 lifetime odometer is witnessed by the live frame's power-on counter; the trip is the sum of accepted
 * increments. Frames are the real dump-3 frames: the live frame at power-on 101 m and the 0x04 at 2 816 164 m,
 * edited byte by byte for the torn / reset variants. Everything asserted is what was PUBLISHED (observations,
 * ControllerData) or RECORDED (the ledger), never timing.
 *
 * Compose is not involved; the tile that shows the trip is judged on a device.
 */
class BegodeOdometerWitnessTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val liveBase = bytes("55 AA 19 06 F7 43 00 6A 00 65 FE 5C F1 D2 08 A0 00 05 00 18 5A 5A 5A 5A")
    private val odoBase = bytes("55 AA 00 2A F8 A4 28 00 1C 20 00 C8 00 00 01 00 00 12 04 18 5A 5A 5A 5A")

    private fun live(powerOn: Int) = liveBase.copyOf().also { it[8] = (powerOn shr 8).toByte(); it[9] = powerOn.toByte() }
    private fun odo(m: Long) = odoBase.copyOf().also {
        it[2] = (m shr 24).toByte(); it[3] = (m shr 16).toByte(); it[4] = (m shr 8).toByte(); it[5] = m.toByte()
    }
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val base = 2_816_164L
    private val pwron0 = 101
    private fun TelemetryObservation.value(metric: TelemetryMetric) = readings.single { it.metric == metric }.value
    private fun List<TelemetryObservation>.odoEvents() = filter { e -> e.readings.any { it.metric == TelemetryMetric.ODOMETER } }
    private fun capture(p: BegodeProtocol) = mutableListOf<TelemetryObservation>().also { ev -> p.telemetryObserver = { ev += it } }
    private fun BegodeProtocol.pair(powerOn: Int, odometer: Long) { onNotification(live(powerOn)); onNotification(odo(odometer)) }
    private fun BegodeProtocol.inStep(count: Int) {
        for (i in 0 until count) pair(pwron0 + 7 * i, base + 7 * i)
    }
    private fun BegodeProtocol.trip() = assertNotNull(latestMotion(0)).tripKm.toDouble()

    @Test fun inStepFramesAreAllAcceptedAndNothingIsRejected() {
        val p = BegodeProtocol(); val ev = capture(p)
        p.inStep(4)
        assertEquals(4, ev.odoEvents().size)
        assertEquals(0.021, p.trip(), 1e-6)
        assertEquals(2_816_185L, p.odometerMeters())
        assertEquals(0L, p.rejectedFrameCount)
        assertEquals(0L, p.rebaseCount)
    }

    @Test fun aTornNeedleInTheMiddlePublishesNothingAndIsLedgered() {
        val p = BegodeProtocol(); val ev = capture(p)
        p.pair(101, base)
        val torn = odo(base + 7 + 65_536)
        assertEquals(0x2B, torn[3].toInt()) // the real frame's byte 3 is 2A: one bit of 65 536
        p.onNotification(live(108)); p.onNotification(torn)
        assertEquals(1, ev.odoEvents().size, "the torn frame published no ODOMETER/TRIP observation")
        assertEquals(2816.164, ev.odoEvents().last().value(TelemetryMetric.ODOMETER)!!, 1e-3)
        assertEquals(2816.164f, assertNotNull(p.latestMotion(0)).odometerKm, 1e-3f)
        p.pair(115, base + 14)
        assertEquals(2, ev.odoEvents().size)
        assertEquals(0.014, ev.odoEvents().last().value(TelemetryMetric.TRIP)!!, 1e-6)
        assertEquals(0.014, p.trip(), 1e-6)
        assertEquals(1L, p.rejectedFrameCount)
        assertEquals(0L, p.rebaseCount)
        val entry = p.rejectedFrames().single()
        assertEquals(hex(torn), entry.rawHex)
        assertEquals(PlausibilityRule.R1_ODOMETER_WITNESS, entry.rule)
        assertEquals(LedgerKind.REJECTED, entry.kind)
        assertEquals(4, entry.frameType)
        assertEquals("2816164 m", entry.reference)
        assertEquals("+7 m", entry.witness)
    }

    @Test fun aTornFirstFrameCostsTheTripNothingAndTheOdometerOnlyUntilRebase() {
        // INCOHERENT fixture: the connection's FIRST 0x04 is the torn one, so the gate's anchor is the needle itself.
        val p = BegodeProtocol(); val ev = capture(p)
        p.pair(101, base + 65_536)
        assertEquals(0.0, p.trip(), 0.0)
        for (i in 1..3) {
            p.pair(pwron0 + 7 * i, base + 7 * i)
            assertTrue(p.trip() < 0.1, "trip stays under 0.1 km while the needle is the anchor")
        }
        assertEquals(2816.185, ev.odoEvents().last().value(TelemetryMetric.ODOMETER)!!, 1e-3)
        assertEquals(0.014, p.trip(), 1e-6)
        assertEquals(1L, p.rebaseCount)
        assertEquals(0L, p.rejectedFrameCount)
        assertEquals(LedgerKind.REBASED, p.rejectedFrames().single().kind)
    }

    @Test fun aWheelOdometerResetNeitherFreezesNorShrinksTheTrip() {
        val p = BegodeProtocol(); val ev = capture(p)
        p.inStep(4)
        val before = p.trip()
        var pw = pwron0 + 7 * 4
        val trips = mutableListOf(before)
        val newBase = 2_650_000L
        for (i in 0..3) {
            pw += 7
            p.pair(pw, newBase + 7 * i)
            trips += p.trip()
            when (i) {
                0, 1 -> assertEquals(4, ev.odoEvents().size, "the chain is held, nothing published")
                2 -> {
                    assertEquals(2650.014, ev.odoEvents().last().value(TelemetryMetric.ODOMETER)!!, 1e-3)
                    assertEquals(before + 0.014, p.trip(), 1e-6)
                }
                3 -> assertEquals(before + 0.014 + 0.007, p.trip(), 1e-6)
            }
        }
        assertTrue(trips.zipWithNext().all { (a, b) -> b >= a }, "never decreases")
        assertEquals(1L, p.rebaseCount)
        assertEquals(0L, p.rejectedFrameCount)
    }

    @Test fun aPersistentForwardJumpRebasesAfterThreeAgreeingFrames() {
        val p = BegodeProtocol(); val ev = capture(p)
        p.inStep(4)
        val before = p.trip()
        var pw = pwron0 + 7 * 4
        for (i in 0..2) { pw += 7; p.pair(pw, 3_000_000L + 7 * i) }
        assertEquals(3000.014, ev.odoEvents().last().value(TelemetryMetric.ODOMETER)!!, 1e-3)
        assertEquals(before + 0.014, p.trip(), 1e-6)
        pw += 7; p.pair(pw, 3_000_021L)
        assertEquals(before + 0.021, p.trip(), 1e-6)
        assertEquals(1L, p.rebaseCount)
    }

    @Test fun theU16WitnessWrapIsHandled() {
        val p = BegodeProtocol(); val ev = capture(p)
        p.pair(65_530, base); p.pair(4, base + 10)
        assertEquals(2, ev.odoEvents().size, "both accepted at once: the witness is present")
        assertEquals(0.010, p.trip(), 1e-6)
        assertEquals(0L, p.rejectedFrameCount)
    }

    @Test fun aPowerOnCounterResetFallsBackToTheMonotoneLookahead() {
        val p = BegodeProtocol(); val ev = capture(p)
        p.pair(182, base)
        p.pair(0, base + 3) // the wheel restarted its counter: no witness, held one 0x04
        assertEquals(1, ev.odoEvents().size)
        p.pair(7, base + 10)
        assertEquals(3, ev.odoEvents().size)
        assertEquals(0.010, p.trip(), 1e-6)
        assertEquals(0L, p.rejectedFrameCount)
    }

    @Test fun withoutALiveFrameBetweenThe0x04sAStepAndReturnIsRejected() {
        val p = BegodeProtocol(); val ev = capture(p)
        val o = 1_000_000L
        p.onNotification(odo(o)); p.onNotification(odo(o + 65_536)); p.onNotification(odo(o + 10))
        assertEquals(1, ev.odoEvents().size, "the +65 536 step was held, then rejected; the return is held")
        assertEquals(1L, p.rejectedFrameCount)
        val entry = p.rejectedFrames().single()
        assertNull(entry.witness, "no live frame: no witness to quote")
        assertEquals("1000000 m", entry.reference)
        p.onNotification(odo(o + 20))
        assertEquals(2, ev.odoEvents().size)
        assertEquals(0.010, p.trip(), 1e-6)
    }

    @Test fun aWheelWhoseCounterStandsStillStillGetsItsTrip() {
        // False-positive pin: a counter that disagrees with the odometer by less than the 40 m tolerance is fine.
        val p = BegodeProtocol()
        for (i in 0..5) p.pair(500, base + 7 * i)
        assertEquals(0.035, p.trip(), 1e-6)
        assertEquals(0L, p.rejectedFrameCount)
    }

    @Test fun theRingKeepsTheNewest32AndSurvivesResetWhileTheCountersDoNot() {
        val p = BegodeProtocol()
        val o = 1_000_000L
        p.onNotification(odo(o))
        for (k in 1..40) { p.onNotification(odo(o + 20L * k + 65_536)); p.onNotification(odo(o + 20L * k)) }
        assertEquals(40L, p.rejectedFrameCount)
        val ring = p.rejectedFrames()
        assertEquals(32, ring.size)
        assertEquals(hex(odo(o + 20L * 40 + 65_536)), ring.last().rawHex)
        assertTrue(ring.zipWithNext().all { (a, b) -> a.atFrameIndex < b.atFrameIndex })
        p.reset()
        assertEquals(0L, p.rejectedFrameCount)
        assertEquals(0L, p.rebaseCount)
        assertEquals(ring, p.rejectedFrames())
    }

    @Test fun aRejectedFrameWritesNoStateNotEvenItsAlertByte() {
        val p = BegodeProtocol()
        p.pair(101, base)
        val torn = odo(base + 7 + 65_536).also { it[14] = 0x08 } // a fault bit carried only by the needle
        p.onNotification(live(108)); p.onNotification(torn)
        p.pair(115, base + 14)
        assertTrue(assertNotNull(p.latestMotion(0)).faults.isEmpty(), "Low voltage rode only the needle")
    }
}
