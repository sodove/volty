package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class HistoryModelTest {
    private val tick = HistoryTick(epochMillis = -500L, elapsedMillis = 0L, clockGeneration = 0L)
    private val key = HistorySeriesKey(sourceId = "pack-a", metricId = "voltage_v")

    @Test
    fun tick_allows_negative_epoch_but_rejects_negative_monotonic_fields() {
        assertEquals(-500L, tick.epochMillis)
        assertEquals(0L, tick.elapsedMillis)
        assertEquals(0L, tick.clockGeneration)

        assertFailsWith<IllegalArgumentException> {
            HistoryTick(epochMillis = 0L, elapsedMillis = -1L, clockGeneration = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryTick(epochMillis = 0L, elapsedMillis = 0L, clockGeneration = -1L)
        }
    }

    @Test
    fun source_and_identifier_fields_reject_blank_values() {
        assertFailsWith<IllegalArgumentException> {
            HistorySource(id = " ", kind = HistorySourceKind.BATTERY, label = "Pack A")
        }
        assertFailsWith<IllegalArgumentException> {
            HistorySeriesKey(sourceId = "pack-a", metricId = "  ")
        }
        assertFailsWith<IllegalArgumentException> {
            HistorySeriesKey(sourceId = "", metricId = "voltage_v")
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryRide(
                id = "",
                vehicleId = "bike-1",
                started = tick,
                ended = null,
                status = HistoryRideStatus.RECORDING,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryRide(
                id = "ride-1",
                vehicleId = " ",
                started = tick,
                ended = null,
                status = HistoryRideStatus.RECORDING,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryBatch(rideId = " ", samples = emptyList(), gps = emptyList(), markers = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryCursor(epochMillis = 10L, stableId = "")
        }
    }

    @Test
    fun series_keys_keep_same_cell_and_metric_distinct_across_packs() {
        val packA = HistorySeriesKey(sourceId = "pack-a", metricId = "cell_voltage_v", cellIndex = 0)
        val packB = HistorySeriesKey(sourceId = "pack-b", metricId = "cell_voltage_v", cellIndex = 0)

        assertNotEquals(packA, packB)
        assertEquals(2, setOf(packA, packB).size)
        assertEquals(0, packA.cellIndex)
        assertEquals(null, key.cellIndex)
    }

    @Test
    fun series_key_rejects_negative_cell_indices() {
        assertFailsWith<IllegalArgumentException> {
            HistorySeriesKey(sourceId = "pack-a", metricId = "cell_voltage_v", cellIndex = -1)
        }
    }

    @Test
    fun samples_distinguish_known_zero_from_typed_unknown_and_reject_incoherent_values() {
        val knownZero = HistorySample(tick = tick, key = key, value = 0.0)
        val unavailable = HistorySample(
            tick = tick,
            key = key,
            value = null,
            unknown = HistoryUnknownReason.GAP,
        )

        assertEquals(0.0, knownZero.value)
        assertEquals(null, knownZero.unknown)
        assertEquals(null, unavailable.value)
        assertEquals(HistoryUnknownReason.GAP, unavailable.unknown)

        assertFailsWith<IllegalArgumentException> {
            HistorySample(tick = tick, key = key, value = null)
        }
        assertFailsWith<IllegalArgumentException> {
            HistorySample(tick = tick, key = key, value = 0.0, unknown = HistoryUnknownReason.UNAVAILABLE)
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                HistorySample(tick = tick, key = key, value = invalid)
            }
        }
    }

    @Test
    fun gps_fix_accepts_coordinate_and_heading_boundaries() {
        val fix = HistoryGpsFix(
            tick = tick,
            lat = -90.0,
            lon = 180.0,
            accuracyMeters = 0.0,
            heading = 0.0,
        )
        val noHeading = HistoryGpsFix(
            tick = tick,
            lat = 90.0,
            lon = -180.0,
            accuracyMeters = 1.0,
            heading = null,
        )

        assertEquals(-90.0, fix.lat)
        assertEquals(180.0, fix.lon)
        assertEquals(0.0, fix.accuracyMeters)
        assertEquals(null, noHeading.heading)
    }

    @Test
    fun gps_fix_rejects_non_finite_or_out_of_range_coordinates() {
        listOf(-90.1, 90.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidLat ->
            assertFailsWith<IllegalArgumentException> {
                HistoryGpsFix(tick, invalidLat, 0.0, 1.0, null)
            }
        }
        listOf(-180.1, 180.1, Double.NaN, Double.NEGATIVE_INFINITY).forEach { invalidLon ->
            assertFailsWith<IllegalArgumentException> {
                HistoryGpsFix(tick, 0.0, invalidLon, 1.0, null)
            }
        }
    }

    @Test
    fun gps_fix_rejects_invalid_accuracy_or_heading() {
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidAccuracy ->
            assertFailsWith<IllegalArgumentException> {
                HistoryGpsFix(tick, 0.0, 0.0, invalidAccuracy, null)
            }
        }
        listOf(-0.1, 360.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidHeading ->
            assertFailsWith<IllegalArgumentException> {
                HistoryGpsFix(tick, 0.0, 0.0, 1.0, invalidHeading)
            }
        }
    }

    @Test
    fun range_allows_zero_width_but_rejects_reversed_endpoints() {
        val instant = HistoryRange(startEpochMillis = -10L, endEpochMillis = -10L)

        assertEquals(-10L, instant.startEpochMillis)
        assertEquals(instant.startEpochMillis, instant.endEpochMillis)
        assertFailsWith<IllegalArgumentException> {
            HistoryRange(startEpochMillis = 1L, endEpochMillis = 0L)
        }
    }

    @Test
    fun stats_accept_zero_and_reject_non_finite_values_or_reversed_extrema() {
        val zeroStats = HistoryStats(minimum = 0.0, maximum = 0.0, energyWh = 0.0, complete = true)

        assertEquals(0.0, zeroStats.minimum)
        assertEquals(0.0, zeroStats.maximum)
        assertEquals(0.0, zeroStats.energyWh)

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                HistoryStats(minimum = invalid, maximum = 1.0, energyWh = null, complete = false)
            }
            assertFailsWith<IllegalArgumentException> {
                HistoryStats(minimum = null, maximum = invalid, energyWh = null, complete = false)
            }
            assertFailsWith<IllegalArgumentException> {
                HistoryStats(minimum = null, maximum = null, energyWh = invalid, complete = false)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryStats(minimum = 2.0, maximum = 1.0, energyWh = null, complete = true)
        }
    }
}
