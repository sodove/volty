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
        // hasDuty is the one absence-flag that defaults to PRESENT: the static
        // reportsDuty table already refuses the protocols that report no duty,
        // so a decoder saying nothing here keeps exactly the availability it
        // had. Only a decoder that can tell "reported" from "not yet reported"
        // — Begode's truePWM latch — has anything to add.
        assertTrue(d.hasDuty)
        assertFalse(d.hasMotorTemp, "…unlike hasMotorTemp, which defaults to absent")
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

    @Test fun hasEscTemp_is_false_only_for_the_no_sensor_sentinel() {
        // A controller that never reports temp_mos (or reports VESC's "no
        // sensor wired" sentinel) must not read as a confident value.
        assertFalse(ControllerData(escTempC = -60f).hasEscTemp)
        assertFalse(ControllerData(escTempC = -50f).hasEscTemp)
        assertTrue(ControllerData(escTempC = -49.9f).hasEscTemp)
        assertTrue(ControllerData(escTempC = 0f).hasEscTemp)
        assertTrue(ControllerData(escTempC = 42f).hasEscTemp)
    }
}
