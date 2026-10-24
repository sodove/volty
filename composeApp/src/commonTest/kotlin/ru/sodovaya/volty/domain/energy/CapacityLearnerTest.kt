package ru.sodovaya.volty.domain.energy

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
