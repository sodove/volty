package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightMapOverlayPolicyTest {
    @Test
    fun vignette_blur_is_clear_in_the_center_and_stronger_at_the_edges() {
        val spec = lightMapOverlaySpec(darkTheme = true)

        assertTrue(spec.blurRadiusDp in 16f..32f)
        assertTrue(spec.clearUntilFraction > 0f)
        assertTrue(spec.clearUntilFraction < spec.edgeStartFraction)
        assertTrue(spec.edgeStartFraction < 1f)
        assertTrue(spec.fallbackAlpha in 0.3f..0.8f)
    }

    @Test
    fun bright_theme_keeps_a_light_fallback_for_devices_without_blur() {
        assertEquals(
            LightVignetteTone.BRIGHT,
            lightMapOverlaySpec(darkTheme = false).fallbackTone,
        )
        assertEquals(
            LightVignetteTone.DARK,
            lightMapOverlaySpec(darkTheme = true).fallbackTone,
        )
    }

    @Test
    fun vignette_is_explicit_and_stronger_at_the_edges_than_in_the_center() {
        val spec = lightMapOverlaySpec(darkTheme = false)

        assertTrue(spec.vignetteCenterAlpha >= 0f)
        assertTrue(spec.vignetteEdgeAlpha > spec.vignetteCenterAlpha)
        assertTrue(spec.vignetteEdgeAlpha >= 0.7f)
    }
}
