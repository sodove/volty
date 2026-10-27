package ru.sodovaya.volty.domain.model

enum class TelemetryOrigin { BATTERY, CONTROLLER }
/** DERIVED names a virtual battery. REPORTED_CAN is a reporter's network view, not a physical contributor. */
enum class TelemetryRole { CONTRIBUTOR, ASSEMBLY, DERIVED, REPORTED_CAN }

internal fun requireTelemetryRole(origin: TelemetryOrigin, role: TelemetryRole) {
    require(when (role) {
        TelemetryRole.CONTRIBUTOR -> true
        TelemetryRole.ASSEMBLY, TelemetryRole.DERIVED -> origin == TelemetryOrigin.BATTERY
        TelemetryRole.REPORTED_CAN -> origin == TelemetryOrigin.CONTROLLER
    })
}

internal fun requireTelemetryAddress(origin: TelemetryOrigin, localIndex: Int,
    role: TelemetryRole, assemblyIndex: Int?) {
    require(localIndex >= 0)
    require((role == TelemetryRole.ASSEMBLY) == (assemblyIndex != null))
    require(assemblyIndex == null || assemblyIndex >= 0)
    requireTelemetryRole(origin, role)
}
enum class TelemetryUnknown { UNAVAILABLE, INVALID_VALUE, PARTIAL_SOURCE }

/** Canonical measurement vocabulary; omitted fields were not observed in this decode. */
enum class TelemetryMetric(val id: String, val origin: TelemetryOrigin, val indexed: Boolean = false) {
    SOC("soc_percent", TelemetryOrigin.BATTERY),
    POWER("power_w", TelemetryOrigin.BATTERY),
    CURRENT("current_a", TelemetryOrigin.BATTERY),
    VOLTAGE("voltage_v", TelemetryOrigin.BATTERY),
    BATTERY_TEMPERATURE("battery_temperature_c", TelemetryOrigin.BATTERY),
    CELL_MIN("cell_min_v", TelemetryOrigin.BATTERY),
    CELL_MAX("cell_max_v", TelemetryOrigin.BATTERY),
    CELL_DELTA("cell_delta_mv", TelemetryOrigin.BATTERY),
    CELL("cell_v", TelemetryOrigin.BATTERY, true),
    SPEED("speed_kmh", TelemetryOrigin.CONTROLLER),
    /**
     * History records the duty the DECODER reported (the per-controller raw value), never the per-wheel PWM estimate:
     * the estimate is a vehicle-level display value ([ControllerData.dutySource]) and is not a measurement.
     *
     * Retraction (ride fixes after 0.7.54): that was too strict for a wheel PROVEN not to report PWM. Its raw
     * duty is a constant 0, so history stored a flat zero while the dashboard showed the estimate. History now
     * records the estimate for such a wheel (`withHistoryEstimatedDuty`), UNMARKED - a sample has no per-reading
     * flag and a marker needs a schema migration, which is left for later. A wheel that reports its own duty is
     * still recorded raw.
     *
     * Second retraction (telemetry plausibility, Task 11): "a marker needs a schema migration" was wrong. A sample
     * is keyed by a TEXT `metricId` and the registry is code, so a new id needs no migration; the estimate is now its
     * own metric, [DUTY_ESTIMATED]. This id keeps ONLY what a controller reported. Rides recorded before that change
     * hold the estimate here, indistinguishable from a measurement, and show unmarked.
     */
    DUTY("duty_percent", TelemetryOrigin.CONTROLLER),
    /** The PWM ESTIMATE of a wheel proven not to report one; never a measurement, shown with a tilde. */
    DUTY_ESTIMATED("duty_estimated_percent", TelemetryOrigin.CONTROLLER),
    MOTOR_CURRENT("motor_current_a", TelemetryOrigin.CONTROLLER),
    CONTROLLER_BATTERY_CURRENT("battery_current_a", TelemetryOrigin.CONTROLLER),
    INPUT_VOLTAGE("input_voltage_v", TelemetryOrigin.CONTROLLER),
    CONTROLLER_POWER("controller_power_w", TelemetryOrigin.CONTROLLER),
    ERPM("erpm", TelemetryOrigin.CONTROLLER),
    ESC_TEMPERATURE("esc_temperature_c", TelemetryOrigin.CONTROLLER),
    MOTOR_TEMPERATURE("motor_temperature_c", TelemetryOrigin.CONTROLLER),
    ODOMETER("odometer_km", TelemetryOrigin.CONTROLLER),
    TRIP("trip_distance_km", TelemetryOrigin.CONTROLLER),
}

data class TelemetryReading(val metric: TelemetryMetric, val value: Double?,
    val unknown: TelemetryUnknown? = null, val cellIndex: Int? = null) {
    init {
        require((value == null && unknown != null) || (value != null && value.isFinite() && unknown == null))
        require(metric.indexed == (cellIndex != null))
        require(cellIndex == null || cellIndex >= 0)
    }
    companion object {
        fun measured(metric: TelemetryMetric, value: Double, known: Boolean = true, cellIndex: Int? = null): TelemetryReading =
            when {
                !known -> TelemetryReading(metric, null, TelemetryUnknown.UNAVAILABLE, cellIndex)
                !value.isFinite() -> TelemetryReading(metric, null, TelemetryUnknown.INVALID_VALUE, cellIndex)
                else -> TelemetryReading(metric, value, cellIndex = cellIndex)
            }
    }
}

class TelemetryObservation(val origin: TelemetryOrigin, val localIndex: Int, readings: List<TelemetryReading>,
    val role: TelemetryRole = TelemetryRole.CONTRIBUTOR, val assemblyIndex: Int? = null) {
    val readings: List<TelemetryReading> = readings.toList()
    init {
        requireTelemetryAddress(origin, localIndex, role, assemblyIndex)
        require(this.readings.all { it.metric.origin == origin })
        require(this.readings.map { it.metric to it.cellIndex }.distinct().size == this.readings.size)
    }
}
