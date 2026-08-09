package ru.sodovaya.volty.presentation.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsiveLayoutTest {
    @Test
    fun portrait_bounds_keep_portrait_mode() {
        assertEquals(ResponsiveLayoutMode.PORTRAIT, responsiveLayoutMode(width = 400f, height = 800f))
    }

    @Test
    fun square_bounds_keep_portrait_mode() {
        assertEquals(ResponsiveLayoutMode.PORTRAIT, responsiveLayoutMode(width = 600f, height = 600f))
    }

    @Test
    fun landscape_bounds_select_wide_mode_only_when_width_is_greater() {
        assertEquals(ResponsiveLayoutMode.WIDE, responsiveLayoutMode(width = 800f, height = 400f))
        assertEquals(ResponsiveLayoutMode.PORTRAIT, responsiveLayoutMode(width = 400f, height = 800f))
    }
}
