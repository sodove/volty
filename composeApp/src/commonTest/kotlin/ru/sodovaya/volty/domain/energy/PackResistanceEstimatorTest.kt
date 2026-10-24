package ru.sodovaya.volty.domain.energy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PackResistanceEstimatorTest {

    private val trueOhm = 0.08f
    private val ocv = 100f

    private fun v(a: Float, noise: Float = 0f) = ocv - trueOhm * a + noise

    @Test
    fun recoversKnownResistance() {
        val e = PackResistanceEstimator()
        val noise = floatArrayOf(0.2f, -0.2f, 0.1f, -0.15f, 0.05f, -0.1f, 0.2f, -0.2f)
        val currents = floatArrayOf(0f, 60f, 120f, 30f)
        var t = 0L
        var n = 0
        repeat(10) {
            for (a in currents) {
                e.feed(v(a, noise[n % noise.size]), a, t)
                n++
                t += 500L
            }
        }
        val ohm = assertNotNull(e.ohm)
        assertEquals(trueOhm, ohm, 0.01f)
    }

    @Test
    fun smallStepsAreIgnored() {
        val e = PackResistanceEstimator()
        e.feed(v(20f), 20f, 0L)
        e.feed(v(25f), 25f, 500L)
        e.feed(v(20f), 20f, 1000L)
        assertNull(e.ohm)
    }

    @Test
    fun slowStepsAreIgnored() {
        val e = PackResistanceEstimator()
        e.feed(v(0f), 0f, 0L)
        e.feed(v(60f), 60f, 1500L)
        assertNull(e.ohm)
    }

    @Test
    fun implausibleCandidatesRejected() {
        val e = PackResistanceEstimator()
        e.feed(100f, 0f, 0L)
        // Current rose 60 A but voltage ROSE: negative resistance.
        e.feed(103f, 60f, 500L)
        // Zero timestamp delta is not a step either.
        e.feed(90f, 0f, 500L)
        // Absurdly large drop: 60 A -> 150 V drop would be > MAX_OHM.
        e.feed(100f, 0f, 1000L)
        e.feed(-50f, 60f, 1500L)
        assertNull(e.ohm)
    }

    @Test
    fun oneOutlierDoesNotMoveMedian() {
        val e = PackResistanceEstimator()
        var t = 0L
        repeat(10) {
            e.feed(v(0f), 0f, t); t += 500L
            e.feed(v(60f), 60f, t); t += 500L
        }
        val before = assertNotNull(e.ohm)
        // One in-range but wild candidate: R = 3.0/60 = 0.05... use a big one, 1.5 ohm.
        e.feed(v(0f), 0f, t); t += 500L
        e.feed(v(0f) - 90f, 60f, t)
        assertEquals(before, assertNotNull(e.ohm), 0.003f)
    }

    @Test
    fun initialValueSurvivesUntilEvidence() {
        val e = PackResistanceEstimator(initialOhm = 0.12f)
        assertEquals(0.12f, e.ohm)
        e.feed(100f, 0f, 0L)
        e.feed(99f, 5f, 500L) // small step, ignored
        e.feed(98f, 5f, 3000L) // no step
        assertEquals(0.12f, e.ohm)
    }
}
