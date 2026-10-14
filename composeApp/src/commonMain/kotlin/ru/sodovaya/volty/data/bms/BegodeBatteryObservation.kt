package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

internal fun observedBegodeBranch(frame: ByteArray, bootPlaceholder: Boolean): List<TelemetryReading> = listOf(
    TelemetryReading.measured(TelemetryMetric.CURRENT, -(frame.i16BE(8) * 0.1).toDouble(), !bootPlaceholder),
    // The dashboard can substitute cached cell sums for nominal frame voltage. Neither certifies a fresh calibrated branch voltage here.
    TelemetryReading(TelemetryMetric.VOLTAGE, null, TelemetryUnknown.PARTIAL_SOURCE),
    TelemetryReading(TelemetryMetric.POWER, null, TelemetryUnknown.PARTIAL_SOURCE),
    TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.UNAVAILABLE),
    // Each frame refreshes only one assembly's two sensors, not the whole branch maximum.
    TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null,
        if (bootPlaceholder) TelemetryUnknown.UNAVAILABLE else TelemetryUnknown.PARTIAL_SOURCE),
)

internal fun observedBegodeCells(frame: ByteArray, expectedCount: Int?, previouslySeen: Set<Int>): List<TelemetryReading> {
    val first = frame.u8(19) * 8
    val raw = (0 until 8).map { frame.u16BE(2 + it * 2) }
    val boot = raw.all { it == 0 }
    val cells = buildList {
        for (offset in 0 until 8) {
            val index = first + offset
            if (expectedCount != null && index >= expectedCount) continue
            when {
                boot && (expectedCount != null || index in previouslySeen) ->
                    add(TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, index))
                expectedCount != null || raw[offset] > 0 ->
                    add(TelemetryReading.measured(TelemetryMetric.CELL, raw[offset] / 1000.0, cellIndex = index))
                index in previouslySeen -> add(TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, index))
            }
        }
    }
    // Page positions do not identify a fresh complete acquisition round.
    val complete = !boot && first == 0 && expectedCount != null && expectedCount in 1..8 && cells.size == expectedCount
    val values = cells.mapNotNull { it.value }
    val minimum = values.minOrNull()
    val maximum = values.maxOrNull()
    fun summary(metric: TelemetryMetric, value: Double?) = if (complete) TelemetryReading(metric, checkNotNull(value))
        else TelemetryReading(metric, null, TelemetryUnknown.PARTIAL_SOURCE)
    return cells + listOf(summary(TelemetryMetric.CELL_MIN, minimum), summary(TelemetryMetric.CELL_MAX, maximum),
        summary(TelemetryMetric.CELL_DELTA, if (minimum != null && maximum != null) (maximum - minimum) * 1000 else null))
}
