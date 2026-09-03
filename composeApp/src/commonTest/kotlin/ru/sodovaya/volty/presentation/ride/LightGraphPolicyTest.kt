package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals

class LightGraphPolicyTest {
    @Test
    fun graph_peak_uses_observed_samples_instead_of_gauge_scale_defaults() {
        assertEquals(0, lightGraphPeak(emptyList()))
        assertEquals(0, lightGraphPeak(listOf(0f, Float.NaN)))
        assertEquals(28, lightGraphPeak(listOf(0f, 27.6f, 28.4f)))
    }
}
