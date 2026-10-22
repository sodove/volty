package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*

/** These fields are decoded together by parseController, never from retained BMS pages. */
internal fun observedVeteranController(data: ControllerData): List<TelemetryReading> {
    fun value(metric: TelemetryMetric, number: Float, known: Boolean = true) =
        TelemetryReading.measured(metric, number.toDouble(), known)
    return listOf(value(TelemetryMetric.SPEED, data.speedKmh, data.speedKnown),
        value(TelemetryMetric.DUTY, data.dutyPercent, data.hasDuty),
        value(TelemetryMetric.MOTOR_CURRENT, data.motorCurrentA),
        value(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, data.batteryCurrentA, data.hasBatteryCurrent),
        value(TelemetryMetric.INPUT_VOLTAGE, data.inputVoltageV, data.hasInputVoltage),
        value(TelemetryMetric.CONTROLLER_POWER, data.powerW, data.hasPower),
        value(TelemetryMetric.ESC_TEMPERATURE, data.escTempC, data.hasEscTemp),
        value(TelemetryMetric.ODOMETER, data.odometerKm, data.hasDistance),
        value(TelemetryMetric.TRIP, data.tripKm, data.hasDistance))
}

/** Controller rail estimates have their own source, distinct from either smart BMS. */
internal fun observedVeteranRail(data: ControllerData, voltageCurveSoc: Float?): List<TelemetryReading> = listOf(
    TelemetryReading.measured(TelemetryMetric.VOLTAGE, data.inputVoltageV.toDouble(), data.hasInputVoltage),
    TelemetryReading.measured(TelemetryMetric.CURRENT, -data.batteryCurrentA.toDouble(), data.hasBatteryCurrent),
    TelemetryReading.measured(TelemetryMetric.POWER, -data.powerW.toDouble(), data.hasPower),
    TelemetryReading.measured(TelemetryMetric.SOC, voltageCurveSoc?.toDouble() ?: 0.0, voltageCurveSoc != null),
)

/** Mirror VeteranProtocol's page layout, but emit only facts in this CRC-validated
 * body. Retained current/cells/SoC and cross-page voltage sums are never restamped.
 * parseAvailable already strips the CRC. An earlier helper subtracted the
 * trailer again here and lost the last two earned cells of a complete page.
 */
internal fun observedVeteranSmartBms(frame: ByteArray, page: Int, expectedCells: Int?): List<TelemetryReading> {
    val bodyEnd = frame.size
    fun unsigned(offset: Int): Int? = if (offset + 1 < bodyEnd)
        ((frame[offset].toInt() and 255) shl 8) or (frame[offset + 1].toInt() and 255) else null
    fun signed(offset: Int) = unsigned(offset)?.toShort()?.toInt()
    fun absent(metric: TelemetryMetric) = TelemetryReading(metric, null, TelemetryUnknown.PARTIAL_SOURCE)
    return buildList {
        when (page) {
            0, 4 -> add(signed(if (page == 0) 69 else 71)?.let {
                TelemetryReading.measured(TelemetryMetric.CURRENT, -it / 100.0)
            } ?: absent(TelemetryMetric.CURRENT))
            1, 2, 3, 5, 6, 7 -> {
                val first = when (page) { 1, 5 -> 0; 2, 6 -> 15; else -> 30 }
                val offset = if (first == 30) 59 else 53
                val capacity = if (first == 30) 12 else 15
                val declared = expectedCells?.takeIf { it in 1..42 }
                val count = minOf(capacity, ((declared ?: 42) - first).coerceAtLeast(0))
                val cells = (0 until count).mapNotNull { local -> unsigned(offset + local * 2)?.let { raw ->
                    if (raw in 1500..5000) TelemetryReading.measured(TelemetryMetric.CELL, raw / 1000.0, cellIndex = first + local)
                    else TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.INVALID_VALUE, first + local)
                } }
                addAll(cells)
                val complete = first == 0 && declared != null && cells.size == declared && cells.all { it.value != null }
                val values = cells.mapNotNull { it.value }
                if (complete) {
                    add(TelemetryReading.measured(TelemetryMetric.CELL_MIN, values.min()))
                    add(TelemetryReading.measured(TelemetryMetric.CELL_MAX, values.max()))
                    add(TelemetryReading.measured(TelemetryMetric.CELL_DELTA, (values.max() - values.min()) * 1000))
                } else addAll(listOf(absent(TelemetryMetric.CELL_MIN), absent(TelemetryMetric.CELL_MAX), absent(TelemetryMetric.CELL_DELTA)))
                if (page == 2 && 50 < bodyEnd) {
                    val soc = frame[50].toInt() and 255
                    add(when {
                        soc <= 100 -> TelemetryReading.measured(TelemetryMetric.SOC, soc.toDouble())
                        soc == 0x80 -> TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.UNAVAILABLE)
                        else -> TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.INVALID_VALUE)
                    })
                }
                if (first == 30) {
                    val temperatures = (0 until 6).mapNotNull { signed(47 + it * 2)?.div(100.0)?.takeIf { it in -50.0..150.0 } }
                    add(if (temperatures.size == 6) TelemetryReading.measured(TelemetryMetric.BATTERY_TEMPERATURE, temperatures.max())
                        else absent(TelemetryMetric.BATTERY_TEMPERATURE))
                }
            }
        }
    }
}
