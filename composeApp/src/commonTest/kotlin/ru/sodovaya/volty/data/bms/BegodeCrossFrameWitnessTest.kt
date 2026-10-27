package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.plausibility.LedgerKind
import ru.sodovaya.volty.domain.plausibility.PlausibilityRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Task 7. R13 (a 0x01 pack voltage witnessed by the live 0x00 voltage) and the two-consecutive-frame confirmation of
 * the 0x07 one-way latches. Frames are real dump-3 frames edited byte by byte: live `55 AA 19 5D ...` (raw 6493,
 * 64.93 V on the 67.2 V reference) and 0x01 `55 AA 00 50 00 00 03 CE ...` (97.4 V, a 24S pack: 97.4 / 64.93 = 1.5).
 *
 * R13 false-positive risk (also in the rule's KDoc): a wheel whose two boards sample far apart in time under heavy
 * sag; it would show as the rejection counter growing on a healthy wheel. Compose is not involved.
 */
class BegodeCrossFrameWitnessTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val live = bytes("55 AA 19 5D 00 00 00 4B 00 00 FF 74 EE A5 00 80 00 05 00 18 5A 5A 5A 5A")
    private val pack = bytes("55 AA 00 50 00 00 03 CE 00 00 00 00 00 00 00 00 00 00 01 00 5A 5A 5A 5A")

    private fun packAt(raw: Int) = pack.copyOf().also { it[6] = (raw shr 8).toByte(); it[7] = raw.toByte() }

    /** Certify the 24S branch ratio the way a wheel does: live and 0x01 frames alternating, no cell pages. */
    private fun certified(): BegodeProtocol = BegodeProtocol().also { p ->
        repeat(6) { p.onNotification(live); p.onNotification(pack) }
        assertEquals(24, p.derivedCellCount())
    }

    @Test fun theDump3PairIsAcceptedAndNothingIsRejected() {
        val p = certified()
        p.onNotification(live); p.onNotification(pack)
        assertEquals(0L, p.rejectedFrameCount)
        assertEquals(97.4f, assertNotNull(p.latestData(0)).voltage, 0.15f)
    }

    @Test fun aCorruptPackVoltageIsRejectedLeavesTheBranchUntouchedAndIsLedgered() {
        val p = certified()
        val before = assertNotNull(p.latestData(0))
        val bad = packAt(1200) // 04 B0 = 120.0 V against an expected 97.4 V
        p.onNotification(bad)
        assertSame(before, p.latestData(0), "no SoC / branch update comes from the rejected frame")
        assertEquals(1L, p.rejectedFrameCount)
        val e = p.rejectedFrames().single()
        assertEquals(PlausibilityRule.R13_PACK_VOLTAGE_WITNESS, e.rule)
        assertEquals(LedgerKind.REJECTED, e.kind)
        assertEquals(1, e.frameType)
        assertTrue(e.rawHex.startsWith("55aa0050"), e.rawHex)
        // the very next genuine frame is accepted: a needle, not a state
        p.onNotification(pack)
        assertEquals(1L, p.rejectedFrameCount)
    }

    @Test fun theLiveFrameIsNeverTouchedByR13() {
        val p = certified()
        val motionBefore = assertNotNull(p.latestMotion(0))
        p.onNotification(packAt(1200))
        assertSame(motionBefore, p.latestMotion(0))
        // a wild LIVE voltage is displayed raw: R13 judges only the 0x01 frame
        p.onNotification(live.copyOf().also { it[2] = 0x10; it[3] = 0x00 })
        assertEquals(1L, p.rejectedFrameCount)
    }

    @Test fun beforeCertificationR13DoesNotApply() {
        val p = BegodeProtocol()
        p.onNotification(live)
        p.onNotification(packAt(1200))
        assertEquals(0L, p.rejectedFrameCount)
        assertEquals(120.0f, assertNotNull(p.latestData(0)).voltage, 0.5f)
    }

    @Test fun anInsideThreeSixPercentDifferenceIsAccepted() {
        val p = certified()
        p.onNotification(packAt(960)) // 96.0 V vs 97.4 V: 1.4 %
        assertEquals(0L, p.rejectedFrameCount)
    }

    @Test fun theThreePercentBoundaryIsExact() {
        val p = certified() // expected 97.395 V
        p.onNotification(packAt(1002)) // 100.2 V: +2.9 % accepted
        assertEquals(0L, p.rejectedFrameCount)
        p.onNotification(packAt(1004)) // 100.4 V: +3.1 % rejected
        assertEquals(1L, p.rejectedFrameCount)
        p.onNotification(packAt(945)) // 94.5 V: -3.0 % boundary side, 94.47 is the edge; 94.5 is inside
        assertEquals(1L, p.rejectedFrameCount)
        p.onNotification(packAt(944)) // 94.4 V: -3.07 % rejected
        assertEquals(2L, p.rejectedFrameCount)
    }

    @Test fun theReferenceIsTheNewestGenuineLiveVoltageNotAZeroPaddedBootFrame() {
        val p = certified()
        // a zero-padded live frame zeroes liveVoltageRaw but is not a genuine voltage
        p.onNotification(bytes("55 AA 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 18 5A 5A 5A 5A"))
        p.onNotification(packAt(1200))
        assertEquals(1L, p.rejectedFrameCount, "the last genuine live voltage still witnesses the 0x01")
    }

    // --- latches -----------------------------------------------------------------------------------------------

    private fun motion(tempRaw: Int, dutyRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[1] = 0x43; p[3] = 0x01 // a genuine, non-zero payload whose temp and duty words are what the test varies
        p[4] = (tempRaw shr 8).toByte(); p[5] = tempRaw.toByte()
        p[6] = (dutyRaw shr 8).toByte(); p[7] = dutyRaw.toByte()
        return byteArrayOf(0x55, 0xAA.toByte()) + p + byteArrayOf(0x07, 0x18, 0x5A, 0x5A, 0x5A, 0x5A)
    }

    @Test fun oneCorruptMotionFrameBetweenZeroFramesLatchesNothing() {
        val p = BegodeProtocol() // no live frame: the sawLiveMotion path of the duty latch is closed
        p.onNotification(motion(0, 0))
        p.onNotification(motion(130, 37))
        p.onNotification(motion(0, 0))
        val m = assertNotNull(p.latestMotion(0))
        assertFalse(m.hasMotorTemp, "130 C for one frame is not evidence of a thermistor")
        assertFalse(m.hasDuty, "37 % for one frame does not latch the duty field")
        assertNull(p.dutyPercent())
    }

    @Test fun twoConsecutiveMotionFramesLatchBoth() {
        val p = BegodeProtocol()
        p.onNotification(motion(0, 0))
        p.onNotification(motion(130, 37))
        p.onNotification(motion(131, 38))
        val m = assertNotNull(p.latestMotion(0))
        assertTrue(m.hasMotorTemp)
        assertEquals(131f, m.motorTempC)
        assertTrue(m.hasDuty)
        assertEquals(38f, m.dutyPercent)
    }

    @Test fun aZeroBetweenTwoNonZerosBreaksTheRun() {
        val p = BegodeProtocol()
        p.onNotification(motion(130, 37)); p.onNotification(motion(0, 0)); p.onNotification(motion(130, 37))
        val m = assertNotNull(p.latestMotion(0))
        assertFalse(m.hasMotorTemp); assertFalse(m.hasDuty)
    }

    @Test fun resetClearsTheLatchRuns() {
        val p = BegodeProtocol()
        p.onNotification(motion(130, 37))
        p.reset()
        p.onNotification(motion(130, 37))
        val m = assertNotNull(p.latestMotion(0))
        assertFalse(m.hasMotorTemp, "one frame before and one after a reset are not two consecutive frames")
        assertFalse(m.hasDuty)
    }

    @Test fun theLiveMotionPathOfTheDutyLatchIsUnchanged() {
        val p = BegodeProtocol()
        p.onNotification(live)
        p.onNotification(motion(0, 0)) // a genuine live frame exists: a zero duty is a measurement at once
        val m = assertNotNull(p.latestMotion(0))
        assertTrue(m.hasDuty)
        assertFalse(m.hasMotorTemp)
    }

    // --- final review M1: R13 only against a reference of this frame cycle -------------------------------------------

    @Test fun aStaleLiveReferenceSkipsR13AndAFreshOneStillJudges() {
        // A 0x07 between the live frame and the 0x01 means this cycle's 0x00 was lost (a 0x01 follows its 0x00 by ~3 ms):
        // the reference is a cycle old or more, the 3 ms witness does not hold, so a correct 0x01 that sagged away from it
        // must not be rejected. Incoherent on purpose: the 0x01 voltage is the same wild 120 V that is rejected against a
        // fresh reference. An earlier revision of this test pinned "one 0x07 between is still the same cycle"; that was
        // the one-lost-0x00 case itself, so it now skips too.
        val stale = certified()
        stale.onNotification(motion(0, 0)); stale.onNotification(motion(0, 0))
        stale.onNotification(packAt(1200))
        assertEquals(0L, stale.rejectedFrameCount, "stale reference: the check is skipped, nothing rejected")
        val oneBetween = certified()
        oneBetween.onNotification(motion(0, 0))
        oneBetween.onNotification(packAt(1200))
        assertEquals(0L, oneBetween.rejectedFrameCount, "one lost 0x00 (one 0x07 between) is already a stale reference")
        val fresh = certified()
        fresh.onNotification(packAt(1200))
        assertEquals(1L, fresh.rejectedFrameCount)
        stale.onNotification(live)
        stale.onNotification(packAt(1200))
        assertEquals(1L, stale.rejectedFrameCount, "a new live frame makes the reference fresh again")
    }

    // --- final review M4: ledger entries carry wall-clock time ---------------------------------------------------------

    @Test fun aLedgerEntryIsStampedWithTheInjectedWallClock() {
        var now = 1_700_000_000_123L
        val p = BegodeProtocol(nowMillis = { now }).also { q ->
            repeat(6) { q.onNotification(live); q.onNotification(pack) }
        }
        now = 1_700_000_000_999L
        p.onNotification(packAt(1200))
        assertEquals(1_700_000_000_999L, p.rejectedFrames().single().atEpochMillis)
    }
}
