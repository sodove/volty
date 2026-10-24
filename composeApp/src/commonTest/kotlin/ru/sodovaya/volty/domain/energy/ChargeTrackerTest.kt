package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.model.Chemistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargeTrackerTest {

    private val chem = Chemistry.LI_ION_NMC
    private val low = 3.30f
    private val high = 4.20f
    private val cells = 24

    private fun config(usableAh: Float? = 30f) = ChargeConfig(chem, low, high, usableAh)

    /** Pack terminal voltage of a pack whose true resting charge is [soc], drawing [amps] through true resistance [trueR]. */
    private fun packV(soc: Float, amps: Float = 0f, trueR: Float = 0f): Float =
        OcvCurve.cellVoltageAt(chem, soc, low, high) * cells - amps * trueR

    private fun input(
        at: Long,
        v: Float,
        amps: Float?,
        r: Float? = null,
    ) = ChargeInput(at, v, cells, amps, r)

    /** A tracker fed 5 s of rest at [soc] (t = 0..4000 ms, 1 s apart), so it is seeded and its clock stands at 4000. */
    private fun seeded(
        soc: Float,
        persisted: PersistedCharge? = null,
        usableAh: Float? = 30f,
    ): ChargeTracker {
        val t = ChargeTracker(config(usableAh), persisted)
        for (i in 0..4) t.update(input(i * 1000L, packV(soc), 0f))
        return t
    }

    private val seedEnd = 4_000L

    @Test
    fun nullUntilSeeded() {
        val t = ChargeTracker(config())
        assertNull(t.update(input(0, packV(60f), 0f)))
        assertNull(t.update(input(1000, packV(60f), 0f)))
        // three samples are not enough: they span only 2 s, and 3 s of stability is required
        assertNull(t.update(input(2000, packV(60f), 0f)))
        assertNotNull(t.update(input(3000, packV(60f), 0f)))
    }

    @Test
    fun unstableVoltageDoesNotSeed() {
        val t = ChargeTracker(config())
        // pack voltage jumping ~5 % between samples never holds still
        for (i in 0..6) {
            val v = if (i % 2 == 0) packV(60f) else packV(60f) * 0.95f
            assertNull(t.update(input(i * 1000L, v, 0f)))
        }
    }

    @Test
    fun hardPullDoesNotCrash() {
        val t = seeded(60f)
        var shown: Float? = null
        var trueSoc = 60f
        // True pack resistance is 0.1 ohm (20 V of sag at 200 A) but the estimate fed in lags at 0.09:
        // the compensated voltage still reads about 1 V low. A mutant whose voltage correction is fast
        // follows that residual and falls below 58 within 10 s; the counter must not.
        for (i in 1..10) {
            trueSoc -= 200f / 3600f / 30f * 100f
            shown = t.update(input(seedEnd + i * 1000L, packV(trueSoc, 200f, 0.1f), 200f, 0.09f))
        }
        assertNotNull(shown)
        assertTrue(shown >= 58.0f, "hard pull crashed the readout to $shown")
        assertTrue(shown <= 60.0f, "readout rose to $shown")
    }

    @Test
    fun steadyRideDecreasesSteadily() {
        val t = seeded(80f)
        var trueSoc = 80f
        var last = t.update(input(seedEnd + 1000, packV(80f, 20f, 0.1f), 20f, 0.1f))!!
        val first = last
        for (i in 2..1800) {
            trueSoc = 80f - 20f * (i / 3600f) / 30f * 100f
            val now = t.update(input(seedEnd + i * 1000L, packV(trueSoc, 20f, 0.1f), 20f, 0.1f))!!
            assertTrue(now <= last + 1e-4f, "readout rose from $last to $now at step $i")
            last = now
        }
        val drop = 80f - last
        assertEquals(33.3f, drop, 3f, "total drop $drop")
        assertTrue(first > last)
    }

    /** Rides [seconds] at [amps] from a counter that started [offset] points above [truth0]; returns counter - truth. */
    private fun errorAfterRide(truth0: Float, offset: Float, seconds: Int, amps: Float): Float {
        // persisted is 9.5 points above the voltage seed (inside the 10-point tolerance, so it wins)
        val t = seeded(truth0, PersistedCharge(truth0 + offset, 0))
        var truth = truth0
        for (i in 1..seconds) {
            truth = truth0 - amps * (i / 3600f) / 30f * 100f
            t.update(input(seedEnd + i * 1000L, packV(truth, amps, 0.1f), amps, 0.1f))
        }
        return t.counterPercent!! - truth
    }

    @Test
    fun voltageCorrectsSlowlyInMotion() {
        val err = errorAfterRide(60f, 9.5f, 600, 20f)
        assertTrue(err < 9.4f, "error not reduced at all: $err")
        assertTrue(err > 4.75f, "error reduced by half or more in motion: $err")
    }

    @Test
    fun restCorrectsFaster() {
        val t = seeded(60f, PersistedCharge(69.5f, 0))
        assertEquals(69.5f, t.counterPercent!!, 0.05f)
        for (i in 1..180) t.update(input(seedEnd + i * 1000L, packV(60f), 0f))
        val err = t.counterPercent!! - 60f
        assertTrue(err < 0.95f, "rest left an error of $err (10 % of 9.5 is 0.95)")
    }

    @Test
    fun snapsToFullAtRest() {
        val t = seeded(100f, PersistedCharge(92f, 0)) // 8 points off: inside tolerance, persisted wins
        assertEquals(92f, t.counterPercent!!, 0.05f)
        var shown: Float? = null
        for (i in 1..8) shown = t.update(input(seedEnd + i * 1000L, packV(100f), 0f))
        assertEquals(100f, shown)
    }

    @Test
    fun regenRaisesChargeButClampsAtFull() {
        val t = seeded(99f)
        var last = t.update(input(seedEnd + 1000, packV(99f), -200f))!!
        val start = last
        for (i in 2..12) {
            val now = t.update(input(seedEnd + i * 1000L, packV(99f), -200f))!!
            assertTrue(now <= 100f, "above full: $now")
            assertTrue(now - last <= 0.2f + 1e-4f, "rose ${now - last} in one second")
            last = now
        }
        assertTrue(last > start, "regen did not raise the charge")
        assertTrue(last > 99.5f, "regen of 200 A for 12 s should have nearly filled the pack: $last")
    }

    @Test
    fun gapIsNotIntegrated() {
        val t = seeded(60f)
        val before = t.update(input(seedEnd + 1000, packV(60f), 0f))
        val counter = t.counterPercent
        val ah = t.integratedAh
        val after = t.update(input(seedEnd + 6000, packV(60f, 100f, 0.1f), 100f, 0.1f))
        assertEquals(before, after)
        assertEquals(counter, t.counterPercent)
        assertEquals(ah, t.integratedAh)
        assertEquals(5000L, t.gapMillis)
    }

    @Test
    fun longGapReseeds() {
        val t = seeded(60f)
        val gapAt = seedEnd + 60_000L
        assertNull(t.update(input(gapAt, packV(40f), 0f)), "readout from before a 60 s silence must not survive")
        assertEquals(60_000L, t.gapMillis)
        var shown: Float? = null
        for (i in 1..4) shown = t.update(input(gapAt + i * 1000L, packV(40f), 0f))
        assertNotNull(shown)
        assertEquals(40f, shown, 0.5f)
    }

    @Test
    fun seedUnderLoadUsesCompensatedVoltage() {
        val t = ChargeTracker(config())
        var shown: Float? = null
        for (i in 0..4) shown = t.update(input(i * 1000L, packV(70f, 80f, 0.1f), 80f, 0.1f))
        assertNotNull(shown)
        assertEquals(70f, shown, 2f)
    }

    @Test
    fun persistedWithinToleranceWins() {
        val t = ChargeTracker(config(), PersistedCharge(66f, 0))
        var shown: Float? = null
        for (i in 0..4) shown = t.update(input(i * 1000L, packV(60f), 0f))
        assertEquals(66f, shown!!, 0.05f)
    }

    @Test
    fun persistedOutsideToleranceLoses() {
        val t = ChargeTracker(config(), PersistedCharge(80f, 0))
        var shown: Float? = null
        for (i in 0..4) shown = t.update(input(i * 1000L, packV(60f), 0f))
        assertNotNull(shown)
        assertEquals(60f, shown, 0.5f)
    }

    @Test
    fun noCapacityFallsBackToFilteredVoltage() {
        val t = seeded(60f, usableAh = null)
        var shown = 60f
        for (i in 1..2) shown = t.update(input(seedEnd + i * 1000L, packV(60f, 200f, 0.1f), 200f, null))!!
        assertTrue(60f - shown < 5f, "no-capacity readout moved ${60f - shown} in 2 s")
        assertTrue(shown <= 60f)
    }

    @Test
    fun unmeasuredCurrentFallsBack() {
        val t = seeded(60f)
        var shown = 60f
        // current not measured, yet the voltage is incoherent with a rest: sagged by 20 V
        for (i in 1..2) shown = t.update(input(seedEnd + i * 1000L, packV(60f, 200f, 0.1f), null, 0.1f))!!
        assertTrue(60f - shown < 5f, "unmeasured-current readout moved ${60f - shown} in 2 s")
        assertTrue(shown <= 60f)
        assertEquals(0.0, t.integratedAh)
    }

    @Test
    fun nonPositiveDtIsSkipped() {
        val t = seeded(60f)
        val shown = t.update(input(seedEnd + 1000, packV(60f), 0f))
        val counter = t.counterPercent
        val ah = t.integratedAh
        assertEquals(shown, t.update(input(seedEnd + 500, packV(10f, 300f), 300f)))
        assertEquals(shown, t.update(input(seedEnd + 1000, packV(10f, 300f), 300f)))
        assertEquals(counter, t.counterPercent)
        assertEquals(ah, t.integratedAh)
        assertEquals(0L, t.gapMillis)
    }

    @Test
    fun restVoltageSocOnlyAtRest() {
        val t = seeded(60f)
        // seeding samples ran 4 s of rest: not yet 5 s
        t.update(input(seedEnd + 500, packV(60f), 0f))
        assertNull(t.restVoltageSocPercent)
        t.update(input(seedEnd + 1500, packV(60f), 0f))
        assertEquals(60f, t.restVoltageSocPercent!!, 0.5f)
        t.update(input(seedEnd + 2500, packV(60f, 30f, 0.1f), 30f, 0.1f))
        assertNull(t.restVoltageSocPercent)
    }

    @Test
    fun integratedAhIsDischargePositive() {
        val t = seeded(60f)
        t.update(input(seedEnd + 1000, packV(60f), 36f))
        assertEquals(0.01, t.integratedAh, 1e-6) // 36 A for 1 s
        t.update(input(seedEnd + 2000, packV(60f), -36f))
        assertEquals(0.0, t.integratedAh, 1e-6)
    }
}
