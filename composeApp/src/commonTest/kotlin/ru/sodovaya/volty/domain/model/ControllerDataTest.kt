package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerDataTest {
    @Test fun defaults_are_disconnected_and_speed_unknown() {
        val d = ControllerData()
        assertEquals(SpeedSource.NONE, d.speedSource)
        assertFalse(d.speedKnown)
        assertFalse(d.isConnected)
    }

    @Test fun speedKnown_tracks_speedSource() {
        assertTrue(ControllerData(speedKmh = 20f, speedSource = SpeedSource.REPORTED).speedKnown)
        assertTrue(ControllerData(speedKmh = 20f, speedSource = SpeedSource.DERIVED).speedKnown)
        assertFalse(ControllerData(speedSource = SpeedSource.NONE).speedKnown)
    }

    @Test fun controller_defaults() {
        val c = Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = "AA:BB")
        assertEquals(null, c.canId)
        assertFalse(c.providesDerivedBattery)
        assertEquals(15, c.motor.polePairs)
    }
}
