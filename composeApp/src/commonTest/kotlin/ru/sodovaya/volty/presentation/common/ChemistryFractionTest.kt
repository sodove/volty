package ru.sodovaya.volty.presentation.common

import ru.sodovaya.volty.domain.model.Chemistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the cell-bar fill bug. The old logic computed fraction from
 * per-pack min/max spread, so a fully charged, well-balanced pack showed empty
 * bars. The new logic anchors to the chemistry's defaultLow/defaultHigh range.
 */
class ChemistryFractionTest {

    @Test
    fun `fully charged li-ion cell reads as 1`() {
        val frac = chemistryFraction(4.20f, Chemistry.LI_ION_NMC)
        assertEquals(1.0f, frac, absTol = 0.001f)
    }

    @Test
    fun `fully discharged li-ion cell reads as 0`() {
        val frac = chemistryFraction(2.80f, Chemistry.LI_ION_NMC)
        assertEquals(0.0f, frac, absTol = 0.001f)
    }

    @Test
    fun `mid-charge li-ion cell is around half`() {
        // (3.5 - 2.8) / (4.2 - 2.8) = 0.7 / 1.4 = 0.5
        val frac = chemistryFraction(3.5f, Chemistry.LI_ION_NMC)
        assertEquals(0.5f, frac, absTol = 0.001f)
    }

    @Test
    fun `over-voltage clamps to 1`() {
        val frac = chemistryFraction(5.0f, Chemistry.LI_ION_NMC)
        assertEquals(1.0f, frac, absTol = 0.001f)
    }

    @Test
    fun `under-voltage clamps to 0`() {
        val frac = chemistryFraction(1.5f, Chemistry.LI_ION_NMC)
        assertEquals(0.0f, frac, absTol = 0.001f)
    }

    @Test
    fun `lifepo4 full cell reads as 1`() {
        val frac = chemistryFraction(3.65f, Chemistry.LIFEPO4)
        assertEquals(1.0f, frac, absTol = 0.001f)
    }

    @Test
    fun `balanced full pack — every cell shows full bar`() {
        // Regression: with the old per-pack-spread logic, all four cells would
        // render as 0.5 because (v - min) / (max - min) is meaningless when
        // max == min.
        val volts = listOf(4.198f, 4.199f, 4.200f, 4.198f)
        val fractions = volts.map { chemistryFraction(it, Chemistry.LI_ION_NMC) }
        fractions.forEach { f ->
            assertTrue(f > 0.95f, "expected near-full bar, got $f")
        }
    }
}

private fun assertEquals(expected: Float, actual: Float, absTol: Float) {
    val diff = kotlin.math.abs(expected - actual)
    assertTrue(diff <= absTol, "expected $expected ± $absTol but got $actual (diff=$diff)")
}
