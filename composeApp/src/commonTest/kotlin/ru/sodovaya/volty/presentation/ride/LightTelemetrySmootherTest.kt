package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightTelemetrySmootherTest {
    @Test
    fun a_controller_jump_is_drawn_as_an_intermediate_value() {
        val smoother = LightTelemetrySmoother()
        val first = LightTelemetryValues(speedKmh = 30f)
        val second = LightTelemetryValues(speedKmh = 70f)

        assertEquals(first, smoother.advance(first, deltaMillis = 16L))
        val frame = smoother.advance(second, deltaMillis = 16L)

        assertTrue(frame.speedKmh!! > 30f)
        assertTrue(frame.speedKmh < 70f)
    }

    @Test
    fun a_large_frame_gap_catches_up_to_the_latest_controller_sample() {
        val smoother = LightTelemetrySmoother()
        smoother.advance(LightTelemetryValues(speedKmh = 30f), deltaMillis = 16L)

        assertEquals(
            70f,
            smoother.advance(LightTelemetryValues(speedKmh = 70f), deltaMillis = 200L).speedKmh,
        )
    }

    @Test
    fun unavailable_values_do_not_linger_after_disconnect() {
        val smoother = LightTelemetrySmoother()
        smoother.advance(LightTelemetryValues(speedKmh = 30f), deltaMillis = 16L)

        assertEquals(null, smoother.advance(LightTelemetryValues(), deltaMillis = 16L).speedKmh)
    }
}
