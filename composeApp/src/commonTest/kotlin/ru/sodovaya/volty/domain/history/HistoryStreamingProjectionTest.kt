package ru.sodovaya.volty.domain.history

import kotlin.test.*

class HistoryStreamingProjectionTest {
    private val origin = HistoryTick(10000, 100, 1)
    private val key = HistorySeriesKey("pack", "power_w")
    private fun sample(delta: Long, value: Double?) = HistorySample(HistoryTick(10000 + delta, 100 + delta, 1), key, value,
        if (value == null) HistoryUnknownReason.PARTIAL_SOURCE else null)

    @Test fun subCadencePeakAndRegenerationAreCapturedBeforeDetailGate() {
        val raw = listOf(sample(0, 10.0), sample(100, 4200.0), sample(200, -200.0), sample(400, 20.0))
        val accumulator = HistoryBucketAccumulator(origin, key)
        val detail = HistoryDetailGate(origin, key)
        raw.forEach { accumulator.offer(it); assertTrue(detail.offer(it).isEmpty()) }
        val bucket = assertNotNull(accumulator.snapshot())
        assertEquals(raw[1], bucket.maximum)
        assertEquals(raw[2], bucket.minimum)
        assertEquals(4, bucket.sampleCount)
        assertEquals(historyStats(raw, emptyList(), -1.0), bucket.stats)
        val emitted = detail.offer(sample(500, 30.0))
        assertEquals(raw, emitted)
        assertEquals(listOf(sample(500, 30.0)), detail.flush())
        assertTrue(detail.flush().isEmpty())
    }

    @Test fun minuteBoundaryOwnsCrossingIntervalExactlyOnce() {
        val accumulator = HistoryBucketAccumulator(origin, key)
        val raw = listOf(sample(59000, -100.0), sample(59900, -100.0), sample(60100, -100.0))
        accumulator.offer(raw[0]); accumulator.offer(raw[1])
        val published = accumulator.offer(raw[2])
        assertEquals(listOf(0L, 1L), published.map { it.slot })
        assertEquals(raw[1].tick, published[1].first)
        assertEquals(3, published.sumOf { it.sampleCount })
        assertEquals(110.0 / 3600, published.sumOf { assertNotNull(it.energyWh) }, 1e-12)
        assertEquals(raw[2].tick, published.last().last)
    }

    @Test fun unknownFlushesDetailBoundaryAndDoesNotInventZeroEnergy() {
        val accumulator = HistoryBucketAccumulator(origin, key)
        val gate = HistoryDetailGate(origin, key)
        val known = sample(0, 10.0); val unknown = sample(100, null)
        accumulator.offer(known); gate.offer(known)
        accumulator.offer(unknown)
        assertEquals(listOf(known, unknown), gate.offer(unknown))
        val bucket = assertNotNull(accumulator.snapshot())
        assertEquals(10.0, bucket.stats.maximum)
        assertNull(bucket.energyWh)
        assertFalse(bucket.complete)
        gate.offer(sample(200, 0.0))
        assertEquals(listOf(sample(200, 0.0)), gate.flush())
    }

    @Test fun overflowMarksSummaryPartialAndPreventsNextIntervalBridge() {
        val accumulator = HistoryBucketAccumulator(origin, key)
        accumulator.offer(sample(0, -100.0)); accumulator.offer(sample(1000, -100.0))
        val gap = HistoryMarker(sample(1500, 0.0).tick, HistoryMarkerKind.OVERFLOW)
        // Retraction: was assertFalse; the marker only blocks the next interval (the final `result.complete` below).
        assertTrue(assertNotNull(accumulator.mark(gap)).complete)
        accumulator.offer(sample(2000, -100.0))
        val result = assertNotNull(accumulator.snapshot())
        assertEquals(100.0 / 3600, result.energyWh)
        assertFalse(result.complete)
    }

    @Test fun gpsGapDoesNotDamageBatterySummaryAndWrongOriginIsRejectedWithoutMutation() {
        val accumulator = HistoryBucketAccumulator(origin, key)
        accumulator.offer(sample(0, -100.0))
        accumulator.mark(HistoryMarker(sample(500, 0.0).tick, HistoryMarkerKind.GPS_GAP))
        accumulator.offer(sample(1000, -100.0))
        val before = assertNotNull(accumulator.snapshot())
        assertTrue(before.complete)
        assertFailsWith<IllegalArgumentException> { accumulator.offer(sample(1500, 1.0).copy(tick = sample(1500, 1.0).tick.copy(clockGeneration = 2))) }
        assertEquals(before, accumulator.snapshot())
        assertFailsWith<IllegalArgumentException> { accumulator.offer(sample(1500, 1.0).copy(key = key.copy(sourceId = "other"))) }
    }

    @Test fun detailGateKeepsFourFactsAtMostDuringLongHighFrequencyWindow() {
        val gate = HistoryDetailGate(origin, key)
        val raw = (0..499).map { sample(it.toLong(), if (it == 221) 9999.0 else it.toDouble()) }
        raw.forEach { assertTrue(gate.offer(it).isEmpty()) }
        val result = gate.flush()
        assertTrue(result.size <= 4)
        assertEquals(raw.first(), result.first())
        assertEquals(raw.last(), result.last())
        assertEquals(9999.0, result.maxOf { assertNotNull(it.value) })
    }

    @Test fun trueNumericOverflowCannotReturnPreviouslyEarnedEnergyAsTheBucketTotal() {
        val accumulator = HistoryBucketAccumulator(origin, key, 5000000)
        accumulator.offer(sample(0, -100.0))
        accumulator.offer(sample(1000, -100.0))
        accumulator.offer(sample(1100, -Double.MAX_VALUE))
        accumulator.offer(sample(4001100, -Double.MAX_VALUE))
        accumulator.offer(sample(4001200, -Double.MAX_VALUE))
        val bucket = assertNotNull(accumulator.snapshot())
        assertNull(bucket.energyWh)
        assertFalse(bucket.complete)
    }
}
