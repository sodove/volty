package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** A reporter's SETUP network snapshot. One local voltage cannot certify summed network power. */
internal fun observedVescCanCurrents(data: ControllerData): List<TelemetryReading> = listOf(
    TelemetryReading.measured(TelemetryMetric.MOTOR_CURRENT, data.motorCurrentA.toDouble()),
    TelemetryReading.measured(TelemetryMetric.CONTROLLER_BATTERY_CURRENT,
        data.batteryCurrentA.toDouble(), data.hasBatteryCurrent),
)

/** Fresh local fields of one decoded reply. SETUP's CAN sums need a different source scope. */
internal fun observedVescController(data: ControllerData, setup: Boolean, sessionTripKm: Float?): List<TelemetryReading> {
    fun value(metric: TelemetryMetric, number: Float, known: Boolean = true) =
        TelemetryReading.measured(metric, number.toDouble(), known)
    // commands.c uses mc_interface_get_setup_values() totals for SETUP current fields.
    // A 'num_vescs == 1' trailer alone cannot prove absence of independently aged CAN status-4 input current.
    // Retraction: emitting unknown local currents for SETUP (or unknown distance for
    // VALUES) erased earned facts of another query family and invented energy gaps.
    return buildList {
        add(value(TelemetryMetric.SPEED, data.speedKmh, data.speedKnown))
        add(value(TelemetryMetric.DUTY, data.dutyPercent, data.hasDuty))
        if (!setup) {
            add(value(TelemetryMetric.MOTOR_CURRENT, data.motorCurrentA))
            add(value(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, data.batteryCurrentA, data.hasBatteryCurrent))
            add(value(TelemetryMetric.CONTROLLER_POWER, data.powerW,
                data.hasPower && data.hasBatteryCurrent && data.hasInputVoltage))
        }
        add(value(TelemetryMetric.INPUT_VOLTAGE, data.inputVoltageV, data.hasInputVoltage))
        add(value(TelemetryMetric.ERPM, data.eRpm))
        add(value(TelemetryMetric.ESC_TEMPERATURE, data.escTempC, data.hasEscTemp))
        add(value(TelemetryMetric.MOTOR_TEMPERATURE, data.motorTempC, data.hasMotorTemp))
        // Plain rotor counts cannot observe/restamp a decoded kilometre counter.
        if (setup) {
            add(value(TelemetryMetric.ODOMETER, data.odometerKm, data.hasDistance))
            add(value(TelemetryMetric.TRIP, sessionTripKm ?: 0f, data.hasDistance && sessionTripKm != null))
        }
    }
}


/** Fresh virtual rail facts; never claim physical cells or pack temperature. */
internal fun observedVescDerivedBattery(data: ControllerData, setup: Boolean): List<TelemetryReading> {
    fun value(metric: TelemetryMetric, number: Float, known: Boolean = true) =
        TelemetryReading.measured(metric, number.toDouble(), known)
    val level = data.batteryLevelFraction
    // Preserve the decoder's existing absence policy; a plain reply has no gauge.
    val soc = when {
        !setup || level == null -> TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.UNAVAILABLE)
        !level.isFinite() || level !in 0f..1f -> TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.INVALID_VALUE)
        else -> value(TelemetryMetric.SOC, level * 100f)
    }
    return buildList {
        if (setup) add(soc)
        add(value(TelemetryMetric.VOLTAGE, data.inputVoltageV, data.hasInputVoltage))
        if (!setup) {
            add(value(TelemetryMetric.CURRENT, -data.batteryCurrentA, data.hasBatteryCurrent))
            add(value(TelemetryMetric.POWER, -data.powerW,
                data.hasPower && data.hasBatteryCurrent && data.hasInputVoltage))
        }
    }
}
