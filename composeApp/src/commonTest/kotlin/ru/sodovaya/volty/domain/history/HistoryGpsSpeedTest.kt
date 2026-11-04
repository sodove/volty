package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryGpsSpeedTest {
    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
    private val vehiclePack = HistorySource("vp", HistorySourceKind.VEHICLE_BATTERY, "Vehicle")
    private val esc = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
    private val motion = HistorySource("m", HistorySourceKind.VEHICLE_MOTION, "Motion")
    private val can = HistorySource("can", HistorySourceKind.REPORTED_CAN, "CAN")
    private val fix = HistoryGpsFix(HistoryTick(10_000, 100, 1), 56.0, 60.0, 4.0, null)

    @Test fun a_battery_only_registry_gains_the_gps_speed_source() {
        assertEquals(listOf(pack, historyGpsSpeedSource), historyRecordedSources(listOf(pack)))
        assertEquals(listOf(pack, vehiclePack, historyGpsSpeedSource), historyRecordedSources(listOf(pack, vehiclePack)))
        // The GPS-only segment of an explicitly empty registry (a demo vehicle, a plan that failed) is battery-less too.
        assertEquals(listOf(historyGpsSpeedSource), historyRecordedSources(emptyList()))
        // Reported CAN totals carry currents, never a speed: still no motion source.
        assertEquals(listOf(can, historyGpsSpeedSource), historyRecordedSources(listOf(can)))
    }

    @Test fun a_registry_with_any_motion_source_is_left_exactly_as_given() {
        assertEquals(listOf(pack, esc), historyRecordedSources(listOf(pack, esc)))
        assertEquals(listOf(motion), historyRecordedSources(listOf(motion)))
    }

    @Test fun an_already_registered_gps_source_is_not_bound_twice() {
        assertEquals(listOf(pack, historyGpsSpeedSource), historyRecordedSources(listOf(pack, historyGpsSpeedSource)))
    }

    @Test fun the_gps_speed_metric_is_registered_for_the_gps_kind_only() {
        val descriptor = HistoryMetricRegistry.descriptor(HISTORY_GPS_SPEED_METRIC)!!
        assertEquals(setOf(HistorySourceKind.GPS), descriptor.sourceKinds)
        assertEquals(HistoryUnit.KMH, descriptor.unit)
        assertEquals(listOf(HISTORY_GPS_SPEED_METRIC), HistoryMetricRegistry.forSource(HistorySourceKind.GPS).map { it.id })
        // The controller speed series is untouched: GPS cannot record into it, and it cannot record into GPS's.
        assertEquals(false, HistorySourceKind.GPS in HistoryMetricRegistry.descriptor("speed_kmh")!!.sourceKinds)
    }

    @Test fun a_speed_becomes_kmh_at_the_fix_tick() {
        val sample = historyGpsSpeedSample(fix, 10.0)
        assertEquals(fix.tick, sample.tick)
        assertEquals(HistorySeriesKey(HISTORY_GPS_SPEED_SOURCE_ID, HISTORY_GPS_SPEED_METRIC), sample.key)
        assertEquals(36.0, sample.value!!, 1e-9)
        assertEquals(0.0, historyGpsSpeedSample(fix, 0.0).value)
    }

    @Test fun a_fix_without_speed_is_unavailable_never_zero_and_garbage_is_invalid() {
        val none = historyGpsSpeedSample(fix, null)
        assertNull(none.value); assertEquals(HistoryUnknownReason.UNAVAILABLE, none.unknown)
        listOf(Double.NaN, -0.1, Double.POSITIVE_INFINITY).forEach { bad ->
            val s = historyGpsSpeedSample(fix, bad)
            assertNull(s.value, "$bad"); assertEquals(HistoryUnknownReason.INVALID_VALUE, s.unknown, "$bad")
        }
    }

    @Test fun a_fix_coarser_than_fifty_metres_records_no_speed() {
        // Review M2: the dashboard's 50 m bound. Incoherent on purpose: a coarse fix that claims a speed.
        val coarse = historyGpsSpeedSample(fix.copy(accuracyMeters = 50.1), 10.0)
        assertNull(coarse.value); assertEquals(HistoryUnknownReason.UNAVAILABLE, coarse.unknown)
        assertEquals(36.0, historyGpsSpeedSample(fix.copy(accuracyMeters = 50.0), 10.0).value!!, 1e-9)
    }
}
