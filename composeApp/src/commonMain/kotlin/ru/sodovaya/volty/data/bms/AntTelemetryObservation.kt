package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** Called only for a CRC-valid ANT status. Frame suffix and following frames are never payload. */
internal fun observedAntStatus(frame: ByteArray, frameLength: Int, previousCellCount: Int): List<TelemetryReading> {
    val payloadEnd = frameLength - 4
    val cellCount = frame.u8(9)
    val temperatureCount = frame.u8(8)
    fun coveredWord(offset: Int) = offset >= 6 && offset + 1 < payloadEnd
    val cells = (0 until cellCount).map { index ->
        val offset = 34 + index * 2
        if (coveredWord(offset)) TelemetryReading.measured(TelemetryMetric.CELL, frame.u16LE(offset) / 1000.0, cellIndex = index)
        else TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.PARTIAL_SOURCE, index)
    }
    val retired = (cellCount until previousCellCount).map { index ->
        TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, index)
    }
    val knownCells = cells.mapNotNull { it.value }
    val completeCells = cellCount > 0 && knownCells.size == cellCount
    val minimum = knownCells.minOrNull()
    val maximum = knownCells.maxOrNull()
    fun summary(metric: TelemetryMetric, value: Double?) = if (completeCells) TelemetryReading(metric, checkNotNull(value))
        else TelemetryReading(metric, null, if (cellCount == 0) TelemetryUnknown.UNAVAILABLE else TelemetryUnknown.PARTIAL_SOURCE)

    val temperatureStart = 34 + cellCount * 2
    val presentTemperatures = mutableListOf<Double>()
    var completeTemperatures = true
    // Match the dashboard metric: declared NTCs and MOS, excluding balancer temperature.
    for (index in 0..temperatureCount) {
        val offset = temperatureStart + index * 2
        if (!coveredWord(offset)) {
            completeTemperatures = false
            continue
        }
        val temperature = frame.i16LE(offset)
        when {
            temperature == -40 -> Unit // protocol's explicit sensor-not-connected sentinel
            temperature in -39..150 -> presentTemperatures += temperature.toDouble()
            else -> completeTemperatures = false
        }
    }
    val temperature = when {
        !completeTemperatures -> TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.PARTIAL_SOURCE)
        presentTemperatures.isEmpty() -> TelemetryReading(TelemetryMetric.BATTERY_TEMPERATURE, null, TelemetryUnknown.UNAVAILABLE)
        else -> TelemetryReading.measured(TelemetryMetric.BATTERY_TEMPERATURE, presentTemperatures.max())
    }
    // Use declared offsets even when a block is truncated; never slide later fields into its place.
    val voltageOffset = temperatureStart + temperatureCount * 2 + 4
    fun scalar(metric: TelemetryMetric, offset: Int, value: () -> Double) =
        if (coveredWord(offset)) TelemetryReading.measured(metric, value())
        else TelemetryReading(metric, null, TelemetryUnknown.PARTIAL_SOURCE)
    val voltage = scalar(TelemetryMetric.VOLTAGE, voltageOffset) { frame.u16LE(voltageOffset) / 100.0 }
    // ANT is discharge-positive on the wire; the application's canonical sign is charge-positive.
    val current = scalar(TelemetryMetric.CURRENT, voltageOffset + 2) { -frame.i16LE(voltageOffset + 2) / 10.0 }
    val power = if (voltage.value != null && current.value != null)
        TelemetryReading.measured(TelemetryMetric.POWER, voltage.value * current.value)
    else TelemetryReading(TelemetryMetric.POWER, null, TelemetryUnknown.PARTIAL_SOURCE)
    val soc = scalar(TelemetryMetric.SOC, voltageOffset + 4) { frame.u16LE(voltageOffset + 4).toDouble() }
    return cells + retired + listOf(
        summary(TelemetryMetric.CELL_MIN, minimum), summary(TelemetryMetric.CELL_MAX, maximum),
        summary(TelemetryMetric.CELL_DELTA, if (minimum != null && maximum != null) (maximum - minimum) * 1000 else null),
        temperature, voltage, current, power, soc,
    )
}
