package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** Project only the fields the accepted frame family actually refreshed, not the merged motion snapshot. */
internal fun observedBegodeController(frameType: Int, data: ControllerData): List<TelemetryReading> {
    fun value(metric: TelemetryMetric, number: Float, known: Boolean = true) =
        TelemetryReading.measured(metric, number.toDouble(), known)
    // Voltage and battery current arrive in different frame families. A receipt-aware join belongs to the source runtime.
    fun unpairedPower() = TelemetryReading(TelemetryMetric.CONTROLLER_POWER, null, TelemetryUnknown.PARTIAL_SOURCE)
    return when (frameType) {
        0x00 -> listOf(
            value(TelemetryMetric.SPEED, data.speedKmh, data.speedKnown),
            value(TelemetryMetric.MOTOR_CURRENT, data.motorCurrentA),
            value(TelemetryMetric.ESC_TEMPERATURE, data.escTempC, data.hasEscTemp),
            // Existing decoder calibration uses configured/proven topology; never record the raw 67.2V reference as rail voltage.
            value(TelemetryMetric.INPUT_VOLTAGE, data.inputVoltageV, data.hasInputVoltage),
            unpairedPower(),
        )
        0x04 -> listOf(value(TelemetryMetric.ODOMETER, data.odometerKm, data.hasDistance),
            value(TelemetryMetric.TRIP, data.tripKm, data.hasDistance))
        0x07 -> listOf(
            value(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, data.batteryCurrentA),
            value(TelemetryMetric.MOTOR_TEMPERATURE, data.motorTempC, data.hasMotorTemp),
            value(TelemetryMetric.DUTY, data.dutyPercent, data.hasDuty),
            unpairedPower(),
        )
        else -> emptyList()
    }
}
