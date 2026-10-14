package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** Fresh local fields of one decoded reply. SETUP's CAN sums need a different source scope. */
internal fun observedVescController(data: ControllerData, setup: Boolean, sessionTripKm: Float?): List<TelemetryReading> {
    fun value(metric: TelemetryMetric, number: Float, known: Boolean = true) =
        TelemetryReading.measured(metric, number.toDouble(), known)
    fun localCurrent(metric: TelemetryMetric, number: Float, known: Boolean = true) =
        if (setup) TelemetryReading(metric, null, TelemetryUnknown.PARTIAL_SOURCE)
        else value(metric, number, known)
    // commands.c uses mc_interface_get_setup_values() totals for SETUP current fields.
    // A 'num_vescs == 1' trailer alone cannot prove absence of independently aged CAN status-4 input current.
    return listOf(
        value(TelemetryMetric.SPEED, data.speedKmh, data.speedKnown),
        value(TelemetryMetric.DUTY, data.dutyPercent, data.hasDuty),
        localCurrent(TelemetryMetric.MOTOR_CURRENT, data.motorCurrentA),
        localCurrent(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, data.batteryCurrentA, data.hasBatteryCurrent),
        value(TelemetryMetric.INPUT_VOLTAGE, data.inputVoltageV, data.hasInputVoltage),
        localCurrent(TelemetryMetric.CONTROLLER_POWER, data.powerW, data.hasPower && data.hasBatteryCurrent && data.hasInputVoltage),
        value(TelemetryMetric.ERPM, data.eRpm),
        value(TelemetryMetric.ESC_TEMPERATURE, data.escTempC, data.hasEscTemp),
        value(TelemetryMetric.MOTOR_TEMPERATURE, data.motorTempC, data.hasMotorTemp),
        // GET_VALUES contains rotor counts, not a decoded kilometre counter; default hasDistance is no witness.
        value(TelemetryMetric.ODOMETER, data.odometerKm, setup && data.hasDistance),
        value(TelemetryMetric.TRIP, sessionTripKm ?: 0f, setup && data.hasDistance && sessionTripKm != null),
    )
}
