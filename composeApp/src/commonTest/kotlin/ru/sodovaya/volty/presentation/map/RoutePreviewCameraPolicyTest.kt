package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertTrue

class RoutePreviewCameraPolicyTest {
    @Test
    fun `route preview reserves more room below for the route card`() {
        val padding = RoutePreviewCameraPolicy.paddingFor(1080, 1900)

        assertTrue(padding.left > 0)
        assertTrue(padding.top > 0)
        assertTrue(padding.right > 0)
        assertTrue(padding.bottom > padding.top)
    }
}
