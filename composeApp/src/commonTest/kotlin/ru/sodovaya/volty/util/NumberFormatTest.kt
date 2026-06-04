package ru.sodovaya.volty.util

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormatTest {

    @Test
    fun negativeWithFractionDoesNotRenderDoubleSign() {
        // Regression for "-6.-1": the fraction must use the absolute value.
        assertEquals("-6.1", formatFixed(-6.1f, 1))
    }

    @Test
    fun negativeRoundingToZeroHasNoMinus() {
        // -0.04 rounds to 0.0 at 1 decimal -> must not render "-0.0".
        assertEquals("0.0", formatFixed(-0.04f, 1))
    }

    @Test
    fun negativeZeroHasNoMinus() {
        assertEquals("0.0", formatFixed(-0.0f, 1))
        assertEquals("0", formatFixed(-0.0f, 0))
    }

    @Test
    fun positiveValues() {
        assertEquals("5.25", formatFixed(5.25f, 2))
        // round-half-to-even (banker's): 5.25 scales to the exact tie 52.5 and
        // rounds to the even 52 -> "5.2" at 1 decimal.
        assertEquals("5.2", formatFixed(5.25f, 1))
        assertEquals("5", formatFixed(5.25f, 0))
    }

    @Test
    fun negativeTwoDecimalsRoundHalfUp() {
        // -12.345 (float) scales to 1234.5+ -> rounds to 1235 -> "-12.35".
        assertEquals("-12.35", formatFixed(-12.345f, 2))
    }

    @Test
    fun zeroDecimalsNegative() {
        assertEquals("-6", formatFixed(-6.1f, 0))
    }

    @Test
    fun fractionIsZeroPadded() {
        assertEquals("3.05", formatFixed(3.05f, 2))
        assertEquals("3.005", formatFixed(3.005f, 3))
    }

    @Test
    fun signedPrefix() {
        assertEquals("+3.2", formatSigned(3.2f, 1))
        assertEquals("-3.2", formatSigned(-3.2f, 1))
        assertEquals("+0.0", formatSigned(0f, 1))
    }
}
