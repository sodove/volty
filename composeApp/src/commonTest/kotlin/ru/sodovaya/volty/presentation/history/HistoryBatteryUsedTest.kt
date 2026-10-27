package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryBatteryUsedTest {
    private val socKey = HistorySeriesKey("pack", "soc_percent")
    private val amps = HistorySeriesKey("pack", "current_a")
    private fun s(sec: Int, key: HistorySeriesKey, v: Double?, gen: Long = 1) =
        HistorySample(HistoryTick(1_000_000L + sec * 1000L, sec * 1000L, gen), key, v, if (v == null) HistoryUnknownReason.UNAVAILABLE else null)

    /** 15 min at 1 Hz: [start] for the first 60 s, a sag dip at t = 400 s, [end] for the last 60 s. */
    private fun ride(start: Double, mid: Double, dip: Double, end: Double, seconds: Int = 900,
        iStart: Double, iDip: Double, iEnd: Double, iMid: Double = -10.0): Pair<List<HistorySample>, List<HistorySample>> {
        val soc = (0..seconds).map { t -> s(t, socKey, when { t <= 60 -> start; t == 400 -> dip; t >= seconds - 60 -> end; else -> mid }) }
        val cur = (0..seconds).map { t -> s(t, amps, when { t <= 60 -> iStart; t == 400 -> iDip; t >= seconds - 60 -> iEnd; else -> iMid }) }
        return soc to cur
    }

    @Test fun aSagDipIsNotTheRidesUsage() {
        val (soc, cur) = ride(80.0, 80.0, 40.0, 79.0, iStart = -1.0, iDip = -45.0, iEnd = -0.5)
        val used = assertNotNull(historyBatteryUsed(soc, cur, ownCurrent = true))
        assertEquals(1.0, used.percent, 1e-9)
        assertFalse(used.approximate, "calm windows read from the battery's own current")
        assertEquals("−", historyBatteryUsedSign(used.percent)); assertEquals("", historyBatteryUsedSign(0.3))
    }

    @Test fun aChargingSessionIsNegativeAndSignedPlus() {
        val soc = (0..600).map { t -> s(t, socKey, if (t <= 60) 50.0 else if (t >= 540) 62.0 else 55.0) }
        val used = assertNotNull(historyBatteryUsed(soc, null, ownCurrent = true))
        assertEquals(-12.0, used.percent, 1e-9)
        assertEquals("+", historyBatteryUsedSign(used.percent))
    }

    @Test fun everyStartSampleLoadedFallsBackToTheWholeWindowAndSaysApproximate() {
        // Incoherent: a current series exists, yet every start-window sample is under load; the SoC dips there with it.
        val (soc, cur) = ride(70.0, 80.0, 80.0, 79.0, iStart = -30.0, iDip = -2.0, iEnd = -0.5)
        val used = assertNotNull(historyBatteryUsed(soc, cur, ownCurrent = true))
        assertEquals(70.0 - 79.0, used.percent, 1e-9, "the whole window is read rather than nothing")
        assertTrue(used.approximate)
    }

    @Test fun noCurrentSeriesIsApproximate() {
        val (soc, _) = ride(80.0, 80.0, 40.0, 79.0, iStart = 0.0, iDip = 0.0, iEnd = 0.0)
        val used = assertNotNull(historyBatteryUsed(soc, null, ownCurrent = false))
        assertEquals(1.0, used.percent, 1e-9, "the median still discards the one-sample dip")
        assertTrue(used.approximate)
    }

    @Test fun aControllersCurrentNeverMakesTheTileExact() {
        val (soc, cur) = ride(80.0, 80.0, 40.0, 79.0, iStart = -1.0, iDip = -45.0, iEnd = -0.5)
        assertTrue(assertNotNull(historyBatteryUsed(soc, cur, ownCurrent = false)).approximate)
    }

    @Test fun aRideShorterThanTheWindowsIsStillComputedButApproximate() {
        val soc = (0..30).map { t -> s(t, socKey, 80.0 - t * 0.01) }
        val cur = (0..30).map { t -> s(t, amps, -0.5) }
        val used = assertNotNull(historyBatteryUsed(soc, cur, ownCurrent = true))
        assertTrue(used.approximate)
        assertEquals(0.0, used.percent, 1e-9, "both windows are the same samples")
    }

    @Test fun unknownChargeGivesNoTile() {
        assertNull(historyBatteryUsed(listOf(s(0, socKey, null), s(1, socKey, null)), null, true))
        assertNull(historyBatteryUsed(emptyList(), null, true))
    }

    @Test fun severalBatteriesAreAveragedAndApproximateIfAnyIs() {
        val total = assertNotNull(historyBatteryUsedTotal(listOf(HistoryBatteryUsed(10.0, false), HistoryBatteryUsed(20.0, true))))
        assertEquals(15.0, total.percent); assertTrue(total.approximate)
        assertNull(historyBatteryUsedTotal(emptyList()))
    }
}
