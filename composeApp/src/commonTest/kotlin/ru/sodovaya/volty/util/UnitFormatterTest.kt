package ru.sodovaya.volty.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitFormatterTest {
    @Test fun metric_speed_passes_through_rounded() {
        assertEquals("47", UnitFormatter.speed(47.4f, UnitSystem.METRIC))
        assertEquals("km/h", UnitFormatter.speedUnit(UnitSystem.METRIC))
    }
    @Test fun imperial_speed_converts_to_mph() {
        assertEquals("29", UnitFormatter.speed(47.0f, UnitSystem.IMPERIAL))  // 47 km/h = 29.2 mph
        assertEquals("mph", UnitFormatter.speedUnit(UnitSystem.IMPERIAL))
    }

    // speedValue is the raw-number counterpart to speed()'s formatted string — added so a caller
    // that needs the NUMBER (e.g. a gauge scale maximum) doesn't reconstruct the conversion
    // itself. This is also exactly what speed() is now built on top of (see below).
    @Test fun metric_speed_value_passes_through_unconverted() {
        assertEquals(47.4f, UnitFormatter.speedValue(47.4f, UnitSystem.METRIC))
    }
    @Test fun imperial_speed_value_converts_to_mph() {
        assertTrue(abs(UnitFormatter.speedValue(70f, UnitSystem.IMPERIAL) - 43.496f) < 0.01f)
        assertTrue(abs(UnitFormatter.speedValue(47f, UnitSystem.IMPERIAL) - 29.204f) < 0.01f)
    }
    @Test fun speed_string_is_speedValue_rounded() {
        assertEquals(
            UnitFormatter.speedValue(83f, UnitSystem.IMPERIAL).let { kotlin.math.round(it).toInt().toString() },
            UnitFormatter.speed(83f, UnitSystem.IMPERIAL)
        )
    }
    @Test fun metric_distance_keeps_one_decimal() {
        assertEquals("1284.6", UnitFormatter.distance(1284.6f, UnitSystem.METRIC))
        assertEquals("km", UnitFormatter.distanceUnit(UnitSystem.METRIC))
    }
    @Test fun imperial_distance_converts_to_miles() {
        assertEquals("62.1", UnitFormatter.distance(100f, UnitSystem.IMPERIAL))  // 100 km = 62.14 mi
        assertEquals("mi", UnitFormatter.distanceUnit(UnitSystem.IMPERIAL))
    }
    @Test fun zero_and_negative_are_formatted_not_crashed() {
        assertEquals("0", UnitFormatter.speed(0f, UnitSystem.METRIC))
        assertEquals("0.0", UnitFormatter.distance(0f, UnitSystem.METRIC))
    }

    @Test fun metric_consumption_passes_through_unconverted() {
        assertEquals(20f, UnitFormatter.consumptionValue(20f, UnitSystem.METRIC))
        assertEquals("Wh/km", UnitFormatter.consumptionUnit(UnitSystem.METRIC))
    }

    /**
     * Consumption converts the OPPOSITE way to distance — it is energy PER distance, so a longer
     * display unit makes the number bigger. Getting this backwards is silent (both directions
     * produce a plausible number), so it is asserted against distance's own direction rather than
     * against a hand-copied constant: 20 Wh/km must come out ABOVE 20 Wh/mi, while 100 km comes
     * out BELOW 100 mi.
     */
    @Test fun imperial_consumption_converts_the_opposite_way_to_distance() {
        val whPerMile = UnitFormatter.consumptionValue(20f, UnitSystem.IMPERIAL)
        assertTrue(abs(whPerMile - 32.187f) < 0.01f, "20 Wh/km is 32.2 Wh/mi, was $whPerMile")
        assertTrue(whPerMile > 20f, "a mile is longer than a kilometre, so Wh/mi must be the bigger number")
        assertTrue(UnitFormatter.distance(100f, UnitSystem.IMPERIAL).toFloat() < 100f)
        assertEquals("Wh/mi", UnitFormatter.consumptionUnit(UnitSystem.IMPERIAL))
    }

    /** Converting out and back is lossless — the two directions share one constant. */
    @Test fun consumption_and_distance_use_the_same_conversion_factor() {
        val whPerMile = UnitFormatter.consumptionValue(20f, UnitSystem.IMPERIAL)
        val milesPerHundredKm = UnitFormatter.distance(100f, UnitSystem.IMPERIAL).toFloat()
        // (Wh/mi / Wh/km) and (km / mi) are the same ratio.
        assertTrue(abs((whPerMile / 20f) - (100f / milesPerHundredKm)) < 0.001f)
    }
}
