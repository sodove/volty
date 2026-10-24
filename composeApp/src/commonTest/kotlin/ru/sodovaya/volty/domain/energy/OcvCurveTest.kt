package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.model.Chemistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcvCurveTest {

    @Test
    fun boundsAreExactlyZeroAndHundred() {
        val c = Chemistry.LI_ION_NMC
        assertEquals(0f, OcvCurve.stretchedSocPercent(c, 3.30f, 3.30f, 4.20f), 1e-4f)
        assertEquals(100f, OcvCurve.stretchedSocPercent(c, 4.20f, 3.30f, 4.20f), 1e-4f)
    }

    @Test
    fun midVoltageIsNotLinear() {
        // Ruling R2: a linear map of 3.60 V between 3.30/4.20 would read 33.3 %; the table gives ~21.9 %.
        val soc = OcvCurve.stretchedSocPercent(Chemistry.LI_ION_NMC, 3.60f, 3.30f, 4.20f)
        assertTrue(soc < 25f, "expected a non-linear value below 25 %, got $soc")
    }

    @Test
    fun monotoneAcrossRange() {
        for (c in Chemistry.entries) {
            val lo = c.emptyCellV
            val hi = c.defaultHighV
            var prev = -1f
            for (i in 0..200) {
                val v = lo - 0.1f + (hi - lo + 0.2f) * i / 200f
                val s = OcvCurve.stretchedSocPercent(c, v, lo, hi)
                assertTrue(s >= prev, "$c not monotone at $v: $s < $prev")
                prev = s
            }
        }
    }

    @Test
    fun usableFractionLiIonDefaults() {
        // Ruling R3: 1 - fraction(3.30) with the table (0.0667 -> 0.933).
        assertEquals(0.933f, OcvCurve.usableFraction(Chemistry.LI_ION_NMC, 3.30f, 4.20f), 0.005f)
    }

    @Test
    fun inverseRoundTrips() {
        val c = Chemistry.LI_ION_NMC
        listOf(3.31f, 3.45f, 3.55f, 3.62f, 3.70f, 3.80f, 3.90f, 4.00f, 4.10f, 4.19f).forEach { v ->
            val soc = OcvCurve.stretchedSocPercent(c, v, 3.30f, 4.20f)
            assertEquals(v, OcvCurve.cellVoltageAt(c, soc, 3.30f, 4.20f), 0.001f, "round trip at $v")
        }
    }

    @Test
    fun degenerateBoundsGiveZero() {
        for (c in Chemistry.entries) {
            val equal = OcvCurve.stretchedSocPercent(c, 3.7f, 3.9f, 3.9f)
            val inverted = OcvCurve.stretchedSocPercent(c, 3.7f, 4.2f, 3.3f)
            assertEquals(0f, equal); assertFalse(equal.isNaN())
            assertEquals(0f, inverted); assertFalse(inverted.isNaN())
            assertEquals(0f, OcvCurve.usableFraction(c, 4.2f, 3.3f))
        }
    }

    @Test
    fun leadAcidIsLinear() {
        val c = Chemistry.LEAD_ACID
        assertEquals(50f, OcvCurve.stretchedSocPercent(c, 2.20f, 1.95f, 2.45f), 0.01f)
        assertEquals(0.5f, OcvCurve.fraction(c, 2.20f), 0.01f)
    }

    @Test
    fun outOfTableRangeClamps() {
        assertEquals(0f, OcvCurve.fraction(Chemistry.LI_ION_NMC, 2.5f))
        assertEquals(1f, OcvCurve.fraction(Chemistry.LI_ION_NMC, 4.5f))
    }
}
