package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** Uses only actual payload bytes. Dashboard merging/filtering remains a separate contract. */
internal fun observedJbdTemperature(frame: ByteArray): TelemetryReading {
    val length = frame[3].toInt() and 255
    if (length < 23) return TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.UNAVAILABLE)
    val count = frame[26].toInt() and 255
    if (count == 0) return TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.UNAVAILABLE)
    val values = mutableListOf<Double>()
    for (index in 0 until count) {
        val offset = 27 + index * 2
        if (offset + 1 >= 4 + length) break
        val celsius = (frame.u16BE(offset) - 2731) / 10.0
        if (celsius in -40.0..150.0) values += celsius
    }
    return if (values.size == count) TelemetryReading.measured(TelemetryMetric.BATTERY_TEMPERATURE, values.max())
    else TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.PARTIAL_SOURCE)
}

internal fun observedJbdCells(frame: ByteArray, previousCellCount: Int = 0, declaredCellCount: Int? = null): List<TelemetryReading> {
    val length = frame[3].toInt() and 255
    val cells = (0 until length / 2).map { index ->
        val millivolts = frame.u16BE(4 + index * 2)
        // Retraction: the first observation revision copied the dashboard's 1..5000 filter.
        // A CRC-valid 0mV is an earned reading, potentially a fault; keep raw reported values and indices.
        TelemetryReading.measured(TelemetryMetric.CELL, millivolts / 1000.0, cellIndex = index)
    }
    val known = cells.mapNotNull { it.value }
    // Retraction: an even reply body proves only the returned pairs, not whole-pack coverage.
    // Basic-info payload offset 21 declares the layout (pinned batmon-ha source audit).
    val complete = cells.isNotEmpty() && cells.size == declaredCellCount && length % 2 == 0
    val reason = if (cells.isEmpty() && length == 0) TelemetryUnknown.UNAVAILABLE else TelemetryUnknown.PARTIAL_SOURCE
    val minimum = known.minOrNull()
    val maximum = known.maxOrNull()
    fun summary(metric: TelemetryMetric, value: Double?) = if (complete) TelemetryReading(metric, checkNotNull(value))
        else TelemetryReading(metric, null, reason)
    val retired = (cells.size until previousCellCount).map {
        TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, it)
    }
    return cells + retired + listOf(summary(TelemetryMetric.CELL_MIN, minimum), summary(TelemetryMetric.CELL_MAX, maximum),
        summary(TelemetryMetric.CELL_DELTA, if (minimum != null && maximum != null) (maximum - minimum) * 1000 else null))
}

internal fun observedJbdFailure(command: Int, cellCount: Int): List<TelemetryReading> {
    val metrics = if (command == 0x03) listOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT,
        TelemetryMetric.POWER, TelemetryMetric.SOC, TelemetryMetric.BATTERY_TEMPERATURE)
    else listOf(TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX, TelemetryMetric.CELL_DELTA)
    val cells = if (command == 0x04) (0 until cellCount).map {
        TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, it)
    } else emptyList()
    return cells + metrics.map { TelemetryReading(it, null, TelemetryUnknown.UNAVAILABLE) }
}
