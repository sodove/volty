package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.ControllerData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionSampleGateTest {
    @Test fun same_instance_is_suppressed_new_instance_passes() {
        val gate = MotionSampleGate(1)
        val a = ControllerData(speedKmh = 10f)
        assertTrue(gate.advance(0, a))   // first sight
        assertFalse(gate.advance(0, a))  // same instance re-read
        val b = ControllerData(speedKmh = 10f)  // equal but new instance
        assertTrue(gate.advance(0, b))
    }

    @Test fun per_index_independent() {
        val gate = MotionSampleGate(2)
        val a = ControllerData()
        assertTrue(gate.advance(0, a))
        assertTrue(gate.advance(1, a))   // different index, first sight
    }
}
