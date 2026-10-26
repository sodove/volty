package ru.sodovaya.volty.domain.pwm

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PwmCalibrationLearnerTest {

    private fun onset(speed: Float, v: Float, i: Float? = null, at: Long = 0L) =
        AlarmOnset(speedKmh = speed, packV = v, phaseA = i, atMillis = at)

    // The rider's T4 free-spin: the alarm bit switched on at 68.4 km/h, 96.8 V; threshold 80 %.
    private val t4 = PwmCalibrationLearner.fromOnsets(listOf(onset(68.4f, 96.8f)), 80f)

    @Test
    fun `single measured onset yields the rider's k`() {
        val cal = assertNotNull(t4)
        assertEquals(1.1322f, cal.k, 0.001f)
        assertEquals(0f, cal.r)
        assertEquals(1, cal.points)
        assertEquals(PwmCalibrationSource.RIDING, cal.source)
    }

    @Test
    fun `onset calibration estimates the measured plateau at about 95_9 percent`() {
        val est = assertNotNull(PwmModel.estimatePercent(assertNotNull(t4), 81.7f, 96.5f, null))
        assertEquals(95.9f, est.percent, 0.5f)
        assertFalse(est.atCeiling)
    }

    @Test
    fun `free spin calibration maps the plateau to exactly 100 percent without ceiling`() {
        val cal = PwmCalibrationLearner.fromFreeSpin(81.7f, 96.5f)
        assertEquals(1.1812f, cal.k, 0.001f)
        assertEquals(0f, cal.r)
        assertEquals(PwmCalibrationSource.WIZARD, cal.source)
        val est = assertNotNull(PwmModel.estimatePercent(cal, 81.7f, 96.5f, null))
        assertEquals(100f, est.percent, 0.01f)
        assertFalse(est.atCeiling)
    }

    @Test
    fun `above the plateau is at the ceiling and never extrapolates`() {
        val cal = PwmCalibrationLearner.fromFreeSpin(81.7f, 96.5f)
        val est = assertNotNull(PwmModel.estimatePercent(cal, 90f, 96.5f, null))
        assertTrue(est.atCeiling)
        assertEquals(100f, est.percent)
    }

    @Test
    fun `non finite or non positive voltage gives null`() {
        val cal = assertNotNull(t4)
        assertNull(PwmModel.estimatePercent(cal, Float.NaN, 96f, null))
        assertNull(PwmModel.estimatePercent(cal, 50f, Float.NaN, null))
        assertNull(PwmModel.estimatePercent(cal, 50f, 96f, Float.NaN))
        assertNull(PwmModel.estimatePercent(cal, Float.POSITIVE_INFINITY, 96f, null))
        assertNull(PwmModel.estimatePercent(cal, 50f, 0f, null))
        assertNull(PwmModel.estimatePercent(cal, 50f, -3f, null))
    }

    @Test
    fun `onsets below 10 kmh are ignored`() {
        assertNull(PwmCalibrationLearner.fromOnsets(listOf(onset(9.9f, 96f)), 80f))
        val cal = assertNotNull(
            PwmCalibrationLearner.fromOnsets(listOf(onset(5f, 96f), onset(68.4f, 96.8f)), 80f),
        )
        assertEquals(1, cal.points)
        assertEquals(1.1322f, cal.k, 0.001f)
    }

    @Test
    fun `non finite onsets are ignored`() {
        assertNull(PwmCalibrationLearner.fromOnsets(listOf(onset(Float.NaN, 96f)), 80f))
        assertNull(PwmCalibrationLearner.fromOnsets(listOf(onset(60f, 0f)), 80f))
        assertNull(PwmCalibrationLearner.fromOnsets(emptyList(), 80f))
    }

    @Test
    fun `two loaded onsets are not enough to separate R and fall back to the median`() {
        val cal = assertNotNull(
            PwmCalibrationLearner.fromOnsets(
                listOf(onset(60f, 90f, 0f), onset(40f, 90f, 60f)), 80f,
            ),
        )
        assertEquals(0f, cal.r)
    }

    @Test
    fun `three onsets with distinct currents recover a synthetic k and R`() {
        val k = 1.1f
        val r = 0.2f
        val pts = listOf(60f to 0f, 50f to 30f, 40f to 60f, 30f to 90f)
        val onsets = pts.map { (s, i) -> onset(s, (k * s + r * i) / 0.8f, i) }
        val cal = assertNotNull(PwmCalibrationLearner.fromOnsets(onsets, 80f))
        assertEquals(k, cal.k, 0.005f)
        assertEquals(r, cal.r, 0.005f)
        assertEquals(4, cal.points)
        assertTrue(assertNotNull(cal.spread) < 0.01f)
    }

    @Test
    fun `current spread under 10 A keeps R at zero`() {
        val onsets = listOf(onset(60f, 90f, 10f), onset(55f, 85f, 15f), onset(50f, 80f, 12f))
        val cal = assertNotNull(PwmCalibrationLearner.fromOnsets(onsets, 80f))
        assertEquals(0f, cal.r)
    }

    @Test
    fun `R is clamped to the allowed range and k refitted`() {
        // True R is 0.9 ohm, outside [0, 0.5].
        val pts = listOf(60f to 0f, 50f to 30f, 40f to 60f)
        val onsets = pts.map { (s, i) -> onset(s, (1.1f * s + 0.9f * i) / 0.8f, i) }
        val cal = assertNotNull(PwmCalibrationLearner.fromOnsets(onsets, 80f))
        assertEquals(0.5f, cal.r, 1e-6f)
        // refit with R fixed: k = sum(s*(y - R*I)) / sum(s^2)
        var num = 0.0
        var den = 0.0
        for (o in onsets) {
            num += o.speedKmh * (0.8 * o.packV - 0.5 * o.phaseA!!)
            den += o.speedKmh * o.speedKmh
        }
        assertEquals((num / den).toFloat(), cal.k, 1e-3f)
    }

    @Test
    fun `spread is the relative std dev of per onset k`() {
        val cal = assertNotNull(
            PwmCalibrationLearner.fromOnsets(
                listOf(onset(60f, 90f), onset(60f, 100f)), 80f,
            ),
        )
        val k1 = 0.8f * 90f / 60f
        val k2 = 0.8f * 100f / 60f
        val mean = (k1 + k2) / 2
        val sd = abs(k1 - mean) // population std dev for two points
        assertEquals(sd / mean, assertNotNull(cal.spread), 1e-3f)
    }

    @Test
    fun `merge from nothing equals a single onset fit`() {
        val merged = assertNotNull(PwmCalibrationLearner.merge(null, onset(68.4f, 96.8f), 80f))
        assertEquals(1.1322f, merged.k, 0.001f)
        assertEquals(1, merged.points)
    }

    @Test
    fun `merge averages k running over points`() {
        val first = assertNotNull(PwmCalibrationLearner.merge(null, onset(60f, 90f), 80f))
        val second = assertNotNull(PwmCalibrationLearner.merge(first, onset(60f, 100f), 80f))
        assertEquals(2, second.points)
        assertEquals((1.2f + 1.3333f) / 2, second.k, 0.001f)
        assertTrue(assertNotNull(second.spread) > 0f)
    }

    @Test
    fun `merge ignores a slow onset`() {
        val first = assertNotNull(t4)
        assertEquals(first, PwmCalibrationLearner.merge(first, onset(4f, 96f), 80f))
    }

    @Test
    fun `merge never overwrites a wizard or manual calibration`() {
        val wizard = PwmCalibrationLearner.fromFreeSpin(81.7f, 96.5f)
        assertEquals(wizard, PwmCalibrationLearner.merge(wizard, onset(68.4f, 96.8f), 80f))
    }
}
