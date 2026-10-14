package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

internal fun observedDalyCells(frame: ByteArray, declaredCount: Int?): List<TelemetryReading> {
    val number = frame.u8(4)
    val firstIndex = (number - 1) * 3
    val validFragment = number > 0 && declaredCount != null && firstIndex < declaredCount
    // Fixed triplets contain padding. Without a declaration even a zero pair has ambiguous physical presence.
    val cells = if (validFragment) (firstIndex until minOf(firstIndex + 3, declaredCount!!)).map { index ->
        TelemetryReading.measured(TelemetryMetric.CELL, frame.u16BE(5 + (index - firstIndex) * 2) / 1000.0, cellIndex = index)
    } else emptyList()
    // Fragment numbers alone do not identify a poll round; never mix old and new fragments into a fresh maximum.
    val complete = validFragment && number == 1 && declaredCount in 1..3
    val reason = if (declaredCount == 0) TelemetryUnknown.UNAVAILABLE else TelemetryUnknown.PARTIAL_SOURCE
    val minimum = cells.mapNotNull { it.value }.minOrNull()
    val maximum = cells.mapNotNull { it.value }.maxOrNull()
    fun summary(metric: TelemetryMetric, value: Double?) = if (complete) TelemetryReading(metric, checkNotNull(value))
        else TelemetryReading(metric, null, reason)
    return cells + listOf(summary(TelemetryMetric.CELL_MIN, minimum), summary(TelemetryMetric.CELL_MAX, maximum),
        summary(TelemetryMetric.CELL_DELTA, if (minimum != null && maximum != null) (maximum - minimum) * 1000 else null))
}

internal fun observedDalyTemperature(frame: ByteArray, declaredCount: Int?): TelemetryReading {
    val metric = TelemetryMetric.BATTERY_TEMPERATURE
    if (declaredCount == 0) return TelemetryReading(metric, null, TelemetryUnknown.UNAVAILABLE)
    if (declaredCount == null || declaredCount !in 1..7 || frame.u8(4) != 1)
        return TelemetryReading(metric, null, TelemetryUnknown.PARTIAL_SOURCE)
    // Unlike the dashboard filter, a declared raw zero is -40C, not unused frame padding.
    val temperatures = (0 until declaredCount).map { frame.u8(5 + it) - 40 }
    return if (temperatures.all { it in -40..150 }) TelemetryReading.measured(metric, temperatures.max().toDouble())
    else TelemetryReading(metric, null, TelemetryUnknown.PARTIAL_SOURCE)
}

internal fun observedDalyLayout(previousCells: Int?, cells: Int, previousTemperatures: Int?, temperatures: Int): List<TelemetryReading> = buildList {
    if (previousCells != cells) {
        for (index in cells until (previousCells ?: 0)) add(TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, index))
        for (metric in listOf(TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX, TelemetryMetric.CELL_DELTA))
            add(TelemetryReading(metric, null, TelemetryUnknown.UNAVAILABLE))
    }
    if (previousTemperatures != temperatures) add(TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.UNAVAILABLE))
}
