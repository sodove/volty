package ru.sodovaya.volty.domain.plausibility

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryNeedlesTest {
    private val key = HistorySeriesKey("esc", "speed_kmh")

    private fun series(values: List<Double?>, stepMillis: Long = 300, gapBefore: Map<Int, Long> = emptyMap()): List<HistorySample> {
        var t = 0L
        return values.mapIndexed { i, v ->
            t += gapBefore[i] ?: stepMillis
            HistorySample(HistoryTick(t, t, 1), key, v, if (v == null) HistoryUnknownReason.UNAVAILABLE else null)
        }
    }

    @Test fun aSingleSampleNeedleIsExcludedAndMarksThePeakApproximate() {
        val peak = historyRobustPeak(series(listOf(40.0, 41.0, 42.0, 1180.0, 43.0, 44.0)))!!
        assertEquals(44.0, peak.value); assertTrue(peak.approximate); assertEquals(1, peak.needlesExcluded)
    }

    @Test fun aRealRampFromDumpThreeIsNotTouched() {
        // Magnitudes from dump (3): the 81.0 is above both neighbours but does not return to the prior level.
        val ramp = (400..790 step 10).map { it / 10.0 } + listOf(79.7, 80.5, 80.4, 80.6, 81.0, 79.9)
        val peak = historyRobustPeak(series(ramp))!!
        assertEquals(81.0, peak.value); assertFalse(peak.approximate)
    }

    @Test fun aTemperatureNeedleIsExcluded() {
        assertEquals(45.0, historyRobustPeak(series(listOf(45.0, 45.0, 130.0, 45.0)))!!.value)
    }

    @Test fun aTwoSamplePlateauIsCorroboratedAndKept() {
        val peak = historyRobustPeak(series(listOf(45.0, 130.0, 130.0, 45.0)))!!
        assertEquals(130.0, peak.value); assertFalse(peak.approximate)
    }

    @Test fun aNeedleBesideAGapIsNotExcluded() {
        // The following neighbour is 5 s away: nobody vouches for the level it should return to.
        val peak = historyRobustPeak(series(listOf(40.0, 41.0, 1180.0, 41.0), gapBefore = mapOf(3 to 5_000L)))!!
        assertEquals(1180.0, peak.value); assertFalse(peak.approximate)
    }

    @Test fun aNeedleBesideAnUnknownIsNotExcluded() {
        val peak = historyRobustPeak(series(listOf(40.0, 1180.0, null, 40.0)))!!
        assertEquals(1180.0, peak.value)
    }

    @Test fun aClockBreakBesideTheSampleIsNotExcluded() {
        val s = series(listOf(40.0, 41.0, 1180.0, 41.0, 41.0))
        val broken = s.mapIndexed { i, x -> if (i >= 3) x.copy(tick = HistoryTick(x.tick.epochMillis, x.tick.elapsedMillis, 2)) else x }
        assertEquals(1180.0, historyRobustPeak(broken)!!.value)
    }

    @Test fun aLargeValueInNoisySignalIsNotANeedleBecauseTheNoiseScalesTheThreshold() {
        // Median step is 10, so an excursion of 40 is within 6 x 10 = 60: ordinary variation, kept.
        val noisy = series(listOf(100.0, 110.0, 100.0, 110.0, 100.0, 140.0, 100.0, 110.0, 100.0))
        assertEquals(140.0, historyRobustPeak(noisy)!!.value)
    }

    @Test fun anEmptyOrAllUnknownSeriesHasNoPeak() {
        assertNull(historyRobustPeak(emptyList()))
        assertNull(historyRobustPeak(series(listOf(null, null))))
    }

    @Test fun aNegativeNeedleBelowTheSeriesIsAlsoANeedleButNeverRaisesThePeak() {
        val peak = historyRobustPeak(series(listOf(45.0, 45.0, -900.0, 45.0)))!!
        assertEquals(45.0, peak.value); assertEquals(1, peak.needlesExcluded)
    }
}
