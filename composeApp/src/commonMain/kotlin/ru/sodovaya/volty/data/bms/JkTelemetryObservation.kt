package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*

internal val jkTelemetryScalars = listOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT,
    TelemetryMetric.POWER, TelemetryMetric.SOC, TelemetryMetric.BATTERY_TEMPERATURE,
    TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX, TelemetryMetric.CELL_DELTA)

internal fun jkHistoryCellIndices(frame: ByteArray, layout: Int?, declared: Int?, previous: Set<Int>): Set<Int> {
    val capacity = if (layout == 32) 32 else 24
    val indices = if (declared != null) (0 until declared).toMutableSet() else previous.toMutableSet()
    if (layout != null) {
        val mask = frame.u32LE(54 + layout / 2)
        repeat(capacity) { if ((mask and (1L shl it)) != 0L) indices += it }
    } else {
        // The first 24 voltage words share both layouts. Positive words prove
        // reported slots; unverified padding/zero cannot invent physical cells.
        repeat(minOf(capacity, declared ?: capacity)) { if (frame.u16LE(6 + it * 2) > 0) indices += it }
    }
    return indices.toSet()
}

internal fun observedJkStatus(frame: ByteArray, layout: Int?, declared: Int?,
    previous: Set<Int>, indices: Set<Int>): List<TelemetryReading> {
    fun unknown(metric: TelemetryMetric, reason: TelemetryUnknown = TelemetryUnknown.PARTIAL_SOURCE, index: Int? = null) =
        TelemetryReading(metric, null, reason, index)
    val readings = mutableListOf<TelemetryReading>()
    if (layout == null) {
        readings += jkTelemetryScalars.map { unknown(it) }
    } else {
        val voltage = frame.u32LE(118 + layout) / 1000.0
        val current = frame.i32LE(126 + layout) / 1000.0
        readings += TelemetryReading.measured(TelemetryMetric.VOLTAGE, voltage)
        readings += TelemetryReading.measured(TelemetryMetric.CURRENT, current)
        readings += TelemetryReading.measured(TelemetryMetric.POWER, voltage * current)
        val soc = frame.u8(141 + layout)
        readings += if (soc <= 100) TelemetryReading.measured(TelemetryMetric.SOC, soc.toDouble())
            else unknown(TelemetryMetric.SOC, TelemetryUnknown.INVALID_VALUE)
        val temperatures = listOf((if (layout == 0) 134 else 112 + layout) to 0,
            (130 + layout) to 1, (132 + layout) to 2) +
            if (layout == 32) listOf((226 + layout) to 3, (224 + layout) to 4, (222 + layout) to 5) else emptyList()
        val absent = frame.u8(182 + layout)
        val raw = temperatures.filter { (_, bit) -> (absent and (1 shl bit)) == 0 }
            .map { (offset, _) -> frame.i16LE(offset) }.filter { it != -2000 }
        readings += when {
            raw.any { it !in -500..1500 } -> unknown(TelemetryMetric.BATTERY_TEMPERATURE)
            raw.isEmpty() -> unknown(TelemetryMetric.BATTERY_TEMPERATURE, TelemetryUnknown.UNAVAILABLE)
            else -> TelemetryReading.measured(TelemetryMetric.BATTERY_TEMPERATURE, raw.max() / 10.0)
        }
    }
    val capacity = if (layout == 32) 32 else 24
    val mask = layout?.let { frame.u32LE(54 + it / 2) }
    val cells = indices.sorted().map { index ->
        when {
            index >= capacity -> unknown(TelemetryMetric.CELL, index = index)
            mask != null && (mask and (1L shl index)) == 0L -> unknown(TelemetryMetric.CELL, TelemetryUnknown.UNAVAILABLE, index)
            mask == null && frame.u16LE(6 + index * 2) == 0 -> unknown(TelemetryMetric.CELL, TelemetryUnknown.UNAVAILABLE, index)
            else -> TelemetryReading.measured(TelemetryMetric.CELL, frame.u16LE(6 + index * 2) / 1000.0, cellIndex = index)
        }
    }
    readings += cells
    readings += (previous - indices).sorted().map { unknown(TelemetryMetric.CELL, TelemetryUnknown.UNAVAILABLE, it) }
    if (layout != null) {
        val complete = declared != null && declared in 1..capacity && mask == (1L shl declared) - 1L &&
            cells.size == declared && cells.all { it.value != null }
        if (complete) {
            val values = cells.map { checkNotNull(it.value) }
            readings += TelemetryReading.measured(TelemetryMetric.CELL_MIN, values.min())
            readings += TelemetryReading.measured(TelemetryMetric.CELL_MAX, values.max())
            readings += TelemetryReading.measured(TelemetryMetric.CELL_DELTA, (values.max() - values.min()) * 1000.0)
        } else {
            readings += listOf(TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX, TelemetryMetric.CELL_DELTA).map { unknown(it) }
        }
    }
    return readings
}
