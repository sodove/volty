package ru.sodovaya.volty.domain.stats

import kotlin.test.Test
import kotlin.test.assertEquals

class DutyBandsTest {
    @Test fun bands_are_green_below_75_amber_to_90_red_above() {
        assertEquals(DutyLevel.NORMAL, DutyBands.level(0f))
        assertEquals(DutyLevel.NORMAL, DutyBands.level(74.9f))
        assertEquals(DutyLevel.WARN, DutyBands.level(75f))
        assertEquals(DutyLevel.WARN, DutyBands.level(89.9f))
        assertEquals(DutyLevel.CRITICAL, DutyBands.level(90f))
        assertEquals(DutyLevel.CRITICAL, DutyBands.level(100f))
    }
    @Test fun thresholds_are_overridable_for_per_vehicle_alert_config() {
        assertEquals(DutyLevel.CRITICAL, DutyBands.level(70f, warnPercent = 50f, criticalPercent = 65f))
        assertEquals(DutyLevel.WARN, DutyBands.level(55f, warnPercent = 50f, criticalPercent = 65f))
    }
}
