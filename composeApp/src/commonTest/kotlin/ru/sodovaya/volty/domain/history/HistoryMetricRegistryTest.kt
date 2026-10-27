package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryMetricRegistryTest {
    @Test fun auxiliaryAndVirtualSourcesOnlyOfferTheirQualifiedMetricFamilies() {
        assertEquals(setOf("voltage_v", "battery_temperature_c"),
            HistoryMetricRegistry.forSource(HistorySourceKind.BATTERY_ASSEMBLY).map { it.id }.toSet())
        assertEquals(setOf("voltage_v", "current_a", "power_w", "soc_percent"),
            HistoryMetricRegistry.forSource(HistorySourceKind.DERIVED_BATTERY).map { it.id }.toSet())
        assertFalse(HistoryMetricRegistry.forSource(HistorySourceKind.DERIVED_BATTERY).any { it.indexedByCell })
    }
    @Test fun incoherentUnknownsAndNonfiniteValuesNeverBecomeZero() {
        assertNull(historyValue(4200.0, false))
        assertNull(historyValue(Double.NaN, true))
        assertNull(historyValue(Double.POSITIVE_INFINITY, true))
        assertEquals(0.0, historyValue(0.0, true))
        assertEquals(-10.0, historyValue(-10.0, true))
    }

    @Test fun batteryAndControllerConsumptionSignsRemainDistinct() {
        val battery = assertNotNull(HistoryMetricRegistry.descriptor("power_w"))
        val controller = assertNotNull(HistoryMetricRegistry.descriptor("controller_power_w"))
        assertEquals(-1.0, battery.consumptionMultiplier)
        assertEquals(1.0, controller.consumptionMultiplier)
        assertEquals(-1.0, battery.displayMultiplier)
        assertEquals(1.0, controller.displayMultiplier)
        assertEquals(HistoryUnit.WATT, battery.unit)
        assertNull(assertNotNull(HistoryMetricRegistry.descriptor("voltage_v")).consumptionMultiplier)
    }

    @Test fun individualCellsBelongToBatteriesAndNotVehicleAggregates() {
        val pack = HistoryMetricRegistry.forSource(HistorySourceKind.BATTERY)
        assertTrue(pack.any { it.id == "cell_v" && it.indexedByCell })
        assertFalse(HistoryMetricRegistry.forSource(HistorySourceKind.VEHICLE_BATTERY).any { it.indexedByCell })
        assertFalse(HistoryMetricRegistry.forSource(HistorySourceKind.CONTROLLER).any { it.indexedByCell })
        assertTrue(pack.any { it.id == "cell_delta_mv" && it.unit == HistoryUnit.MILLIVOLT })
    }

    @Test fun theEstimatedDutyIsItsOwnMetricOnTheMotionSources() {
        val d = assertNotNull(HistoryMetricRegistry.descriptor("duty_estimated_percent"))
        assertEquals(HistoryUnit.PERCENT, d.unit); assertEquals(HistoryMetricGroup.CONTROLLER, d.group)
        assertEquals(HistoryMetricRegistry.descriptor("duty_percent")!!.sourceKinds, d.sourceKinds)
        assertEquals(1.0, d.displayMultiplier)
    }

    @Test fun registryCoversExistingGraphMetricsWithUniqueStableKeys() {
        val required = setOf("soc_percent", "power_w", "current_a", "voltage_v", "battery_temperature_c",
            "cell_min_v", "cell_max_v", "cell_delta_mv", "speed_kmh", "duty_percent", "motor_current_a",
            "input_voltage_v", "controller_power_w", "erpm", "esc_temperature_c", "motor_temperature_c")
        val all = HistoryMetricRegistry.descriptors
        assertTrue(all.map { it.id }.toSet().containsAll(required))
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertTrue(all.all { it.sourceKinds.isNotEmpty() && it.id.matches(Regex("[a-z][a-z0-9_]*")) })
        assertNull(HistoryMetricRegistry.descriptor("unknown-future-metric"))
    }
}
