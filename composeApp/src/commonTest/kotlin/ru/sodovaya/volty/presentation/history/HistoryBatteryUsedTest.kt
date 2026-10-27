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
        // Half up, one rule for sign, digit and visibility: exactly 0.5 prints 1, never "−0".
        assertEquals(1, historyBatteryUsedDigit(0.5)); assertEquals("−", historyBatteryUsedSign(0.5)); assertEquals("+", historyBatteryUsedSign(-0.5))
        assertEquals(3, historyBatteryUsedDigit(2.5)); assertEquals(0, historyBatteryUsedDigit(0.4999)); assertEquals("", historyBatteryUsedSign(-0.4999))
    }

    private fun ms(millis: Long, key: HistorySeriesKey, v: Double) =
        HistorySample(HistoryTick(1_000_000L + millis, millis, 1), key, v)

    @Test fun aWindowMixingCalmAndLoadedSamplesReadsTheCalmOnes() {
        // 30 calm samples at 80 and 30 sagged ones at 60 interleaved in the first minute: all-samples would read 70.
        val soc = (0..59).map { t -> s(t, socKey, if (t % 2 == 0) 80.0 else 60.0) } + (840..900).map { t -> s(t, socKey, 50.0) }
        val cur = (0..59).map { t -> s(t, amps, if (t % 2 == 0) -1.0 else -25.0) } + (840..900).map { t -> s(t, amps, -0.5) }
        assertEquals(30.0, assertNotNull(historyBatteryUsed(soc, cur, true)).percent, 1e-9)
    }

    /** Start window of two samples, A (80, calm) and B (60) with [bAmps] [bOffsetMs] away from B; an all-calm 50 end. */
    private fun startWith(bAmps: Double, bOffsetMs: Long): Double {
        val soc = listOf(ms(0, socKey, 80.0), ms(10_000, socKey, 60.0), ms(900_000, socKey, 50.0))
        val cur = listOf(ms(0, amps, -0.5), ms(10_000 + bOffsetMs, amps, bAmps), ms(900_000, amps, -0.5))
        return assertNotNull(historyBatteryUsed(soc, cur, true)).percent + 50.0
    }

    @Test fun theCalmBoundaryIsStrictlyBelowThreeAmps() {
        assertEquals(70.0, startWith(-2.999, 0), 1e-9, "2.999 A is calm: both samples count, median 70")
        assertEquals(80.0, startWith(-3.0, 0), 1e-9, "exactly 3.0 A is not calm: only the calm sample counts")
        assertEquals(80.0, startWith(3.0, 0), 1e-9, "sign does not matter")
    }

    @Test fun theCurrentPairingWindowIsExactlyOneSecondEitherSide() {
        assertEquals(70.0, startWith(-1.0, 1_000), 1e-9, "a current 1000 ms away still pairs")
        assertEquals(70.0, startWith(-1.0, -1_000), 1e-9, "and before the sample too")
        assertEquals(80.0, startWith(-1.0, 1_001), 1e-9, "1001 ms away does not: no nearby current, not calm")
        assertEquals(80.0, startWith(-1.0, 2_000), 1e-9)
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
