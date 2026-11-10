package ru.sodovaya.volty.domain.energy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class CapacityLearnerTest {

    private fun segment(
        start: Float = 60f,
        end: Float = 20f,
        ah: Double = 12.0,
        duration: Long = 3_600_000L,
        gap: Long = 0L,
    ) = LearnSegment(start, end, ah, duration, gap)

    @Test
    fun acceptsLargeDrop() {
        assertEquals(30f, CapacityLearner.accept(segment())!!, 0.001f)
    }

    @Test
    fun rejectsSmallDrop() {
        assertNull(CapacityLearner.accept(segment(start = 60f, end = 45f, ah = 4.5)))
    }

    @Test
    fun rejectsGappyRide() {
        assertNull(CapacityLearner.accept(segment(duration = 1_000_000L, gap = 100_000L)))
    }

    @Test
    fun rejectsRiseOrZeroAh() {
        assertNull(CapacityLearner.accept(segment(start = 20f, end = 60f)))
        assertNull(CapacityLearner.accept(segment(ah = 0.0)))
        assertNull(CapacityLearner.accept(segment(ah = -3.0)))
    }

    @Test
    fun weightedMerge() {
        val first = CapacityLearner.merge(null, segment(start = 60f, end = 20f, ah = 12.0))!!
        assertEquals(30f, first.usableAh, 0.001f)
        assertEquals(40f, first.weight, 0.001f)
        assertEquals(1, first.rides)
        // 80 -> 60 is only 20 %; 6.8 Ah / 0.2 = 34 Ah
        val merged = CapacityLearner.merge(first, segment(start = 80f, end = 60f, ah = 6.8))!!
        assertEquals(31.333f, merged.usableAh, 0.01f)
        assertEquals(60f, merged.weight, 0.001f)
        assertEquals(2, merged.rides)
    }

    @Test
    fun outlierRejectedOnceEstablished() {
        val established = LearnedCapacity(usableAh = 30f, weight = 80f, rides = 2)
        // 50 Ah is > 40 % above 30
        val result = CapacityLearner.merge(established, segment(start = 80f, end = 40f, ah = 20.0))
        assertSame(established, result)
    }

    @Test
    fun firstLearnIsTakenEvenIfFar() {
        val oneRide = LearnedCapacity(usableAh = 30f, weight = 40f, rides = 1)
        val merged = CapacityLearner.merge(oneRide, segment(start = 80f, end = 40f, ah = 20.0))!!
        assertEquals(2, merged.rides)
        assertEquals(40f, merged.usableAh, 0.01f)
    }

    @Test
    fun rejectedSegmentLeavesNullAndCurrentUnchanged() {
        assertNull(CapacityLearner.merge(null, segment(start = 60f, end = 45f)))
        val current = LearnedCapacity(30f, 40f, 1)
        assertSame(current, CapacityLearner.merge(current, segment(start = 60f, end = 45f)))
    }

    @Test
    fun nonFiniteOrImpossibleInputsAreNotEvidence() {
        assertNull(CapacityLearner.accept(segment(start = Float.NaN)))
        assertNull(CapacityLearner.accept(segment(end = Float.NaN)))
        assertNull(CapacityLearner.accept(segment(end = Float.NEGATIVE_INFINITY)))
        assertNull(CapacityLearner.accept(segment(ah = Double.NaN)))
        assertNull(CapacityLearner.accept(segment(ah = Double.POSITIVE_INFINITY)))
        assertNull(CapacityLearner.accept(segment(start = 100f, end = -5f)), "a drop of 105 % cannot happen")
        assertNull(CapacityLearner.merge(null, segment(start = Float.NaN)))
        val current = LearnedCapacity(30f, 40f, 1)
        assertSame(current, CapacityLearner.merge(current, segment(ah = Double.NaN)))
    }

    @Test
    fun onlyAStillTooSmallDropKeepsGrowing() {
        assertEquals(true, CapacityLearner.isStillGrowing(segment(start = 60f, end = 54f, ah = 1.8)))
        assertEquals(false, CapacityLearner.isStillGrowing(segment(start = 60f, end = 20f)), "accepted: restart")
        assertEquals(false, CapacityLearner.isStillGrowing(segment(start = 60f, end = 70f)), "a rise: restart")
        assertEquals(false, CapacityLearner.isStillGrowing(segment(start = 60f, end = 60f)), "no fall: restart")
        assertEquals(false, CapacityLearner.isStillGrowing(segment(start = 60f, end = 54f, duration = 1_000_000L, gap = 100_000L)), "too gappy ever to be valid")
        assertEquals(false, CapacityLearner.isStillGrowing(segment(start = 60f, end = Float.NaN)))
    }

    @Test
    fun threeConsistentMeasurementsCanReplaceAnEstimateBasedOnTwoBadMeasurements() {
        val low = segment(ah = 4.0)
        val first = CapacityLearner.evaluate(null, null, low)
        val old = CapacityLearner.evaluate(first.learned, null, low)
        assertEquals(LearnedCapacity(10f, 80f, 2), old.learned)

        val one = CapacityLearner.evaluate(old.learned, null, segment(start = 80f, end = 60f, ah = 4.0))
        val two = CapacityLearner.evaluate(one.learned, one.candidate, segment(start = 80f, end = 50f, ah = 6.3))
        assertEquals(CapacityLearnDecision.DIVERGENT, one.decision)
        assertEquals(1, assertNotNull(one.candidate).estimate.rides)
        assertEquals(CapacityLearnDecision.DIVERGENT, two.decision)
        assertEquals(2, assertNotNull(two.candidate).estimate.rides)
        assertSame(old.learned, one.learned)
        assertSame(old.learned, two.learned)

        val recovered = CapacityLearner.evaluate(two.learned, two.candidate, segment(ah = 8.2))
        assertEquals(CapacityLearnDecision.RECOVERED, recovered.decision)
        val capacity = assertNotNull(recovered.learned)
        assertEquals((20f * 20f + 21f * 30f + 20.5f * 40f) / 90f, capacity.usableAh, 0.001f)
        assertEquals(90f, capacity.weight)
        assertEquals(3, capacity.rides)
        assertNull(recovered.candidate)
    }

    @Test
    fun outliersMustAgreeWithEachOtherAndAnInBandMeasurementClearsTheirProgress() {
        val current = LearnedCapacity(30f, 80f, 2)
        val one = CapacityLearner.evaluate(current, null, segment(ah = 20.0)) // 50 Ah
        val two = CapacityLearner.evaluate(current, one.candidate, segment(ah = 22.4)) // 56 Ah
        assertEquals(2, assertNotNull(two.candidate).estimate.rides)

        // 60.5 Ah is within 15% of the mean (53 Ah), but the candidate must
        // be within 15% across its extremes, from 50 to 60.5 Ah.
        val different = CapacityLearner.evaluate(current, two.candidate, segment(ah = 24.2))
        assertEquals(CapacityLearnDecision.DIVERGENT, different.decision)
        assertSame(current, different.learned)
        assertEquals(CapacityCandidate(LearnedCapacity(60.5f, 40f, 1), 60.5f, 60.5f), different.candidate)

        // Exactly 40% above the estimate still counts as in-band.
        val inBand = CapacityLearner.evaluate(current, different.candidate, segment(ah = 16.8))
        assertEquals(CapacityLearnDecision.ACCEPTED, inBand.decision)
        assertNull(inBand.candidate)
        assertEquals(LearnedCapacity(34f, 120f, 3), inBand.learned)
    }

    @Test
    fun ineligibleSegmentsKeepTheEstablishedEstimateAndPendingCandidateWithAnExplicitReason() {
        val current = LearnedCapacity(30f, 80f, 2)
        val pending = CapacityCandidate(LearnedCapacity(55f, 40f, 1), 55f, 55f)
        val examples = listOf(
            segment(end = 54f, ah = -1.0) to CapacityLearnDecision.NEED_MORE_DROP,
            segment(end = 54f, gap = 200_000L) to CapacityLearnDecision.TELEMETRY_GAPS,
            segment(start = 40f, end = 60f) to CapacityLearnDecision.SOC_NOT_FALLING,
            segment(ah = 0.0) to CapacityLearnDecision.NO_NET_DISCHARGE,
            segment(gap = -1) to CapacityLearnDecision.INVALID,
            segment(duration = 0) to CapacityLearnDecision.INVALID,
            segment(duration = 100, gap = 101) to CapacityLearnDecision.INVALID,
            segment(start = 105f, end = 60f) to CapacityLearnDecision.INVALID,
            segment(ah = Double.MAX_VALUE) to CapacityLearnDecision.INVALID,
            segment(ah = Double.MIN_VALUE) to CapacityLearnDecision.INVALID,
        )
        for ((measurement, expected) in examples) {
            val result = CapacityLearner.evaluate(current, pending, measurement)
            assertEquals(expected, result.decision)
            assertSame(current, result.learned)
            assertSame(pending, result.candidate)
        }
    }
}
