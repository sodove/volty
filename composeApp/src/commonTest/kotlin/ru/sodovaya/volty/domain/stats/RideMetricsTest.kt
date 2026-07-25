package ru.sodovaya.volty.domain.stats

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideMetricsTest {
    @Test fun instant_consumption_is_power_over_speed() {
        // 1000 W at 50 km/h = 20 Wh/km
        assertTrue(abs(RideMetrics.instantWhPerKm(1000f, 50f)!! - 20f) < 0.01f)
    }
    @Test fun instant_consumption_is_unknown_when_stopped() {
        assertNull(RideMetrics.instantWhPerKm(500f, 0f))
        assertNull(RideMetrics.instantWhPerKm(500f, 0.4f))   // creeping: still meaningless
    }
    @Test fun instant_consumption_is_absolute_so_regen_does_not_read_negative() {
        assertTrue(RideMetrics.instantWhPerKm(-800f, 40f)!! > 0f)
    }
    @Test fun session_consumption_divides_energy_by_distance() {
        assertTrue(abs(RideMetrics.sessionWhPerKm(980f, 58f)!! - 16.9f) < 0.05f)
    }
    @Test fun session_consumption_is_unknown_before_any_distance() {
        assertNull(RideMetrics.sessionWhPerKm(980f, 0f))
    }
}
