package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertSeverity
import ru.sodovaya.volty.domain.usecase.resolveAlertConfig
import ru.sodovaya.volty.domain.usecase.topEnabledLevel

/**
 * One alert kind that is engaged **right now**, as the dashboard banner wants it.
 *
 * [peakValue] is the reading that describes the alert in canonical units (percent for duty and
 * charge, km/h, degrees C, volts for the cell kinds, millivolts for [AlertKind.CELL_DELTA]). The
 * tracker reports the current reading; the banner folds it into the episode's worst value with
 * [worseOf]. [severity] is what the engine and the alarm already grade it as.
 */
data class ActiveAlert(
    val kind: AlertKind,
    val peakValue: Float,
    val severity: AlertSeverity
)

/** Kinds where a **lower** reading is the worse one (the others get worse as they rise). */
private val LOWER_IS_WORSE = setOf(AlertKind.CELL_LOW, AlertKind.SOC_LOW, AlertKind.SOC_CUTOFF)

/** The worse of two readings of [kind]: the lowest for charge and cell-low, the highest otherwise. */
fun worseOf(kind: AlertKind, a: Float, b: Float): Float =
    if (kind in LOWER_IS_WORSE) minOf(a, b) else maxOf(a, b)

/** The more urgent of two severities (CRITICAL first). */
fun worseOf(a: AlertSeverity, b: AlertSeverity): AlertSeverity = if (a.ordinal <= b.ordinal) a else b

/**
 * What is engaged, for the dashboard banner. **A deterministic function of the samples fed in**
 * (no clock, no coroutines), driven from one collector, like [AlarmController].
 *
 * **Why the banner has its own instance instead of reading the engine's.** The notification
 * engine's state is shaped by things the banner must ignore: the per-kind notify switches
 * (rider-approved decision 6: rows show regardless), the 3 s debounce, and the fact that it runs
 * in the application scope. The smallest honest thing is to run the *same graded machinery* on
 * the same samples:
 *
 *  - **Motion kinds** (duty, speed, motor and controller temperature) are an [AlarmController]
 *    fed with [armedRules] - exactly what sounds the alarm, so the banner can never claim an
 *    alarm that the sound path would not raise, and the temperature confirmation (three agreeing
 *    frames, a >20 C jump discarded - decision 1) is inherited rather than copied.
 *  - **Battery kinds** (cells, battery temperature, charge) use [TemperatureGate] and
 *    [batteryConditions] - the one copy of the bands, guards and severities, shared with the
 *    notification engine. CHARGE_COMPLETE is not an alert and is not
 *    shown; BMS and controller faults already have their own banner rows.
 *
 * The caller owns time: this class never decides an episode, it only says what is engaged per
 * sample. Episodes, counts and linger are the banner's ([EPISODE_GAP]).
 */
class ActiveAlertTracker {

    private val alarm = AlarmController()

    // ---- battery side ----
    private val engaged = linkedSetOf<AlertKind>()
    private val lastValue = mutableMapOf<AlertKind, Float>()
    private val lastSeverity = mutableMapOf<AlertKind, AlertSeverity>()
    private var temperatureGate = TemperatureGate()

    /** Forget everything (vehicle switch). The next samples are judged afresh. */
    fun reset() {
        alarm.reset()
        resetBattery()
    }

    private fun resetBattery() {
        engaged.clear()
        lastValue.clear()
        lastSeverity.clear()
        temperatureGate = TemperatureGate()
    }

    /** The motion kinds engaged by [motion]. A disconnected sample or no vehicle: none. */
    fun updateMotion(motion: ControllerData, vehicle: Vehicle?): List<ActiveAlert> {
        if (vehicle == null || !motion.isConnected) {
            alarm.reset()
            return emptyList()
        }
        val rules = armedRules(vehicle, motion, vehicle.motionAlertRules)
        val state = alarm.update(motion, rules)
        val engaged = state.contributors.map { contributor ->
            val rule = rules.rules.first { it.kind == contributor.kind }
            val top = rule.topEnabledLevel
            ActiveAlert(
                kind = contributor.kind.alertKind(),
                peakValue = contributor.value,
                severity = if (contributor.level >= top) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            )
        }
        // The wheel's own PWM alarm is a fact the wheel reports, not a rule of ours: it needs no armed rule, no
        // duty availability and no notify switch (those gate sound and notifications, not what the banner tells the
        // rider). Its row carries the configured threshold, the number the wheel's alarm stands for.
        return if (motion.wheelPwmAlarm == true) {
            engaged + ActiveAlert(AlertKind.WHEEL_PWM_ALARM, vehicle.pwmAlarmThresholdPercent.toFloat(), AlertSeverity.WARNING)
        } else engaged
    }

    /** The battery kinds engaged by [data]. A disconnected sample or no vehicle: none. */
    fun updateBattery(data: BmsData, vehicle: Vehicle?): List<ActiveAlert> {
        if (vehicle == null || !data.isConnected) {
            resetBattery()
            return emptyList()
        }
        val cfg = resolveAlertConfig(vehicle.alertConfig, vehicle.chemistry)
        val temp = if (data.temperatures.isEmpty()) null else temperatureGate.confirm(data.temperatures.max())

        // Unconfirmed temperature: batteryConditions leaves those kinds out, so what was decided stands.
        for (c in batteryConditions(data, cfg, temp)) {
            condition(c.kind, c.enabled, c.value, c.severity, c.triggered, c.recovered)
        }
        // A rising battery temperature escalates warn -> high; one row, the higher one.
        val shown = AlertKind.entries.filter {
            it in engaged && !(it == AlertKind.TEMPERATURE_WARN && AlertKind.TEMPERATURE_HIGH in engaged)
        }
        return shown.map { kind ->
            ActiveAlert(kind, lastValue.getValue(kind), lastSeverity.getValue(kind))
        }
    }

    private fun condition(
        kind: AlertKind, enabled: Boolean, value: Float, severity: AlertSeverity,
        triggered: Boolean, recovered: Boolean
    ) {
        if (!enabled) {
            engaged.remove(kind)
            return
        }
        lastValue[kind] = value
        lastSeverity[kind] = severity
        if (kind in engaged) {
            if (recovered) engaged.remove(kind)
        } else if (triggered) {
            engaged.add(kind)
        }
    }
}

private fun MotionAlertKind.alertKind(): AlertKind = when (this) {
    MotionAlertKind.DUTY -> AlertKind.DUTY_HIGH
    MotionAlertKind.SPEED -> AlertKind.SPEED_HIGH
    MotionAlertKind.MOTOR_TEMP -> AlertKind.MOTOR_TEMP_HIGH
    MotionAlertKind.ESC_TEMP -> AlertKind.ESC_TEMP_HIGH
}
