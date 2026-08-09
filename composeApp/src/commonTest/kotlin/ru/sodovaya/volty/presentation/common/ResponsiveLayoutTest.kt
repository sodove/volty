package ru.sodovaya.volty.presentation.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsiveLayoutTest {
    @Test
    fun portrait_bounds_keep_portrait_mode() {
        assertEquals(ResponsiveLayoutMode.PORTRAIT, responsiveLayoutMode(widthPx = 400, heightPx = 800))
    }

    @Test
    fun square_bounds_keep_portrait_mode() {
        assertEquals(ResponsiveLayoutMode.PORTRAIT, responsiveLayoutMode(widthPx = 600, heightPx = 600))
    }

    @Test
    fun landscape_bounds_select_wide_mode_only_when_width_is_greater() {
        assertEquals(ResponsiveLayoutMode.WIDE, responsiveLayoutMode(widthPx = 800, heightPx = 400))
        assertEquals(ResponsiveLayoutMode.PORTRAIT, responsiveLayoutMode(widthPx = 400, heightPx = 800))
    }
}
