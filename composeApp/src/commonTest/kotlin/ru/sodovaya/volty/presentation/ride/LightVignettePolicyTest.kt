package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightVignettePolicyTest {
    @Test
    fun vignette_belongs_between_the_map_and_light_hud_content() {
        assertEquals(
            LightVignettePlacement.BETWEEN_MAP_AND_HUD,
            lightVignettePlacement,
        )
    }

    @Test
    fun bright_theme_uses_a_light_vignette() {
        assertTrue(lightVignetteTone(darkTheme = false) == LightVignetteTone.BRIGHT)
        assertTrue(lightVignetteTone(darkTheme = true) == LightVignetteTone.DARK)
    }
}
