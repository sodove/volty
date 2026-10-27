package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import ru.sodovaya.volty.domain.pwm.PwmEvidenceProgress
import ru.sodovaya.volty.domain.stats.MotionAggregator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 7 review I1. After any genuine live frame `sawTrueDuty` passes on the first 0x07 and every live rebuild
 * republishes the last 0x07 duty. Without `dutyFresh`, one corrupt 0x07 plus the next live frame gave
 * [PwmEvidenceProgress] two non-zero samples and latched the persisted REPORTS evidence. Drives the real
 * BegodeProtocol through MotionAggregator into the evidence reducer; the fixtures are deliberately incoherent
 * (a wheel whose duty is 0 on every real frame and 37 on one).
 */
class BegodeDutyFreshnessTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val live = bytes("55 AA 19 5D 00 00 00 4B 00 00 FF 74 EE A5 00 80 00 05 00 18 5A 5A 5A 5A")
    private fun motion(duty: Int): ByteArray {
        val p = ByteArray(16)
        p[1] = 0x43; p[3] = 0x01; p[6] = (duty shr 8).toByte(); p[7] = duty.toByte()
        return byteArrayOf(0x55, 0xAA.toByte()) + p + byteArrayOf(0x07, 0x18, 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /** The evidence after the frames, each frame's published sample passed through the aggregator. */
    private fun evidenceAfter(vararg frames: ByteArray): PwmEvidence {
        val p = BegodeProtocol()
        var progress = PwmEvidenceProgress()
        for (f in frames) {
            p.onNotification(f)
            val sample = assertNotNull(p.latestMotion(0))
            val agg = MotionAggregator.aggregate(listOf(ControllerState(Controller(0, "w", ControllerType.BEGODE, "A"), sample, isOnline = true)))
            progress = progress.next(agg)
        }
        return progress.evidence
    }

    @Test fun oneCorruptDutyFollowedByLiveFramesIsNotReports() {
        assertTrue(evidenceAfter(live, motion(0), motion(37), live, live) != PwmEvidence.REPORTS)
    }

    @Test fun twoConsecutiveFresh0x07DutiesAreReports() {
        assertEquals(PwmEvidence.REPORTS, evidenceAfter(live, motion(37), motion(41)))
    }

    @Test fun aLiveRebuildRepublishesTheDutyButNotAsFresh() {
        val p = BegodeProtocol()
        p.onNotification(live); p.onNotification(motion(37))
        assertTrue(assertNotNull(p.latestMotion(0)).dutyFresh)
        p.onNotification(live)
        val after = assertNotNull(p.latestMotion(0))
        assertEquals(37f, after.dutyPercent)
        assertFalse(after.dutyFresh)
        assertFalse(MotionAggregator.aggregate(listOf(ControllerState(Controller(0, "w", ControllerType.BEGODE, "A"), after, isOnline = true))).dutyFresh)
    }

    @Test fun anEstimatedDutySampleIsNeverEvidence() {
        val est = ControllerData(dutyPercent = 50f, hasDuty = true, dutySource = ru.sodovaya.volty.domain.model.DutySource.ESTIMATED)
        assertEquals(PwmEvidence.UNKNOWN, PwmEvidenceProgress().next(est).next(est).evidence)
    }
}
