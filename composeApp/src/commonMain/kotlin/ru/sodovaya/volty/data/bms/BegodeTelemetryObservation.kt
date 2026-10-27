package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/**
 * Project only the fields the accepted frame family actually refreshed, not the merged motion snapshot.
 *
 * **Power is a join across two frame families** (voltage lives in 0x00, battery current in 0x07). It is recorded on
 * the 0x07 frame - the one that refreshed the current - and only when [powerJoinFresh]: the protocol's own receipt
 * state says a genuine live frame supplied the voltage at most [BegodeProtocol]'s `POWER_JOIN_MAX_MOTION_FRAMES`
 * 0x07 frames ago (about 1 s of wheel-frame time, counted in frames because the wire has no timestamps) AND the
 * decoder certified a voltage at all (`hasPower`). Otherwise the honest unknown stays. The 0x00 frame still records
 * unknown: its current is the PREVIOUS 0x07's, so the pair would be a guess in the other direction.
 *
 * Retraction: an earlier revision said "a receipt-aware join belongs to the source runtime" and recorded unknown on
 * both frames, so every Begode ride stored volts and amps with no power. The join was never built; this is it.
 */
internal fun observedBegodeController(frameType: Int, data: ControllerData, powerJoinFresh: Boolean = false): List<TelemetryReading> {
    fun value(metric: TelemetryMetric, number: Float, known: Boolean = true) =
        TelemetryReading.measured(metric, number.toDouble(), known)
    fun unpairedPower() = TelemetryReading(TelemetryMetric.CONTROLLER_POWER, null, TelemetryUnknown.PARTIAL_SOURCE)
    fun joinedPower() =
        if (powerJoinFresh && data.hasPower && data.powerW.isFinite()) value(TelemetryMetric.CONTROLLER_POWER, data.powerW)
        else unpairedPower()
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
            joinedPower(),
        )
        else -> emptyList()
    }
}
