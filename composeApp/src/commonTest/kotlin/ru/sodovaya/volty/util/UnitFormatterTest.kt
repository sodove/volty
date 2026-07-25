package ru.sodovaya.volty.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitFormatterTest {
    @Test fun metric_speed_passes_through_rounded() {
        assertEquals("47", UnitFormatter.speed(47.4f, UnitSystem.METRIC))
        assertEquals("km/h", UnitFormatter.speedUnit(UnitSystem.METRIC))
    }
    @Test fun imperial_speed_converts_to_mph() {
        assertEquals("29", UnitFormatter.speed(47.0f, UnitSystem.IMPERIAL))  // 47 km/h = 29.2 mph
        assertEquals("mph", UnitFormatter.speedUnit(UnitSystem.IMPERIAL))
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
}
