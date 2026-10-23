package ru.sodovaya.volty.presentation.vehicle

import kotlin.math.cos
import kotlin.math.PI
import kotlin.test.*

class RouteMapPreviewTest {
    @Test fun aStandingRiderStillGetsAStreetSizedArea() {
        // Ten metres of GPS jitter at 56.8 N: fitting the map to that would zoom into one roof.
        val b = assertNotNull(routePreviewBounds(listOf(56.8 to 60.6, 56.80009 to 60.60012)))
        val heightM = (b.north - b.south) * 111_320
        val widthM = (b.east - b.west) * 111_320 * cos(56.8 * PI / 180)
        assertEquals(400.0, heightM, 1.0); assertEquals(400.0, widthM, 1.0)
        assertTrue(b.south < 56.8 && b.north > 56.80009 && b.west < 60.6 && b.east > 60.60012)
    }

    @Test fun aLongRouteKeepsItsShapeWithAMargin() {
        val b = assertNotNull(routePreviewBounds(listOf(56.0 to 60.0, 56.1 to 60.4)))
        assertEquals(0.13, b.north - b.south, 1e-9); assertEquals(0.52, b.east - b.west, 1e-9)
        assertNull(routePreviewBounds(emptyList()))
    }
}
