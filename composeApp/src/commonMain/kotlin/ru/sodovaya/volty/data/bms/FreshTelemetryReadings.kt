package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*

/** [fields] comes from this accepted message, never from value changes or validity flags.
 * A retained snapshot may contain other perfectly valid readings; they remain absent here.
 */
internal fun freshControllerReadings(data: ControllerData, fields: Set<TelemetryMetric>): List<TelemetryReading> =
    fields.map { metric ->
        require(metric.origin == TelemetryOrigin.CONTROLLER)
        val (value, known) = when (metric) {
            TelemetryMetric.SPEED -> data.speedKmh to data.speedKnown
            TelemetryMetric.DUTY -> data.dutyPercent to data.hasDuty
            TelemetryMetric.MOTOR_CURRENT -> data.motorCurrentA to true
            TelemetryMetric.CONTROLLER_BATTERY_CURRENT -> data.batteryCurrentA to data.hasBatteryCurrent
            TelemetryMetric.INPUT_VOLTAGE -> data.inputVoltageV to data.hasInputVoltage
            TelemetryMetric.CONTROLLER_POWER -> data.powerW to data.hasPower
            TelemetryMetric.ERPM -> data.eRpm to true
            TelemetryMetric.ESC_TEMPERATURE -> data.escTempC to data.hasEscTemp
            TelemetryMetric.MOTOR_TEMPERATURE -> data.motorTempC to data.hasMotorTemp
            TelemetryMetric.ODOMETER -> data.odometerKm to data.hasDistance
            TelemetryMetric.TRIP -> data.tripKm to data.hasDistance
            else -> error("Not a controller metric")
        }
        TelemetryReading.measured(metric, value.toDouble(), known)
    }

/** CELL and cell extrema may be selected only when the decoded list is a complete fresh block.
 * Fragmented protocols publish their exact indexed fragment instead of marking a cached list fresh.
 */
internal fun freshBatteryReadings(data: BmsData, fields: Set<TelemetryMetric>): List<TelemetryReading> = buildList {
    for (metric in fields) {
        require(metric.origin == TelemetryOrigin.BATTERY)
        fun measured(value: Float, known: Boolean = true) = TelemetryReading.measured(metric, value.toDouble(), known)
        fun absent() = TelemetryReading(metric, null, TelemetryUnknown.UNAVAILABLE)
        when (metric) {
            TelemetryMetric.VOLTAGE -> add(measured(data.voltage))
            TelemetryMetric.CURRENT -> add(measured(data.current, data.hasCurrent))
            TelemetryMetric.POWER -> add(measured(data.power, data.hasPower))
            TelemetryMetric.SOC -> add(measured(data.soc, data.socKnown))
            TelemetryMetric.BATTERY_TEMPERATURE -> add(if (data.temperatures.isEmpty()) absent()
                else measured(if (data.temperatures.any { !it.isFinite() }) Float.NaN else data.temperatures.max()))
            TelemetryMetric.CELL -> data.cellVoltages.forEachIndexed { index, voltage ->
                add(TelemetryReading.measured(metric, voltage.toDouble(), cellIndex = index))
            }
            TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX, TelemetryMetric.CELL_DELTA -> {
                add(when {
                    data.cellVoltages.isEmpty() -> absent()
                    data.cellVoltages.any { !it.isFinite() } -> measured(Float.NaN)
                    metric == TelemetryMetric.CELL_MIN -> measured(data.cellVoltages.min())
                    metric == TelemetryMetric.CELL_MAX -> measured(data.cellVoltages.max())
                    else -> measured((data.cellVoltages.max() - data.cellVoltages.min()) * 1000)
                })
            }
            else -> error("Not a battery metric")
        }
    }
}
