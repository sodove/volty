package ru.sodovaya.volty.presentation.ride

/** Numeric values used by the light HUD before formatting them for text. */
internal data class LightTelemetryValues(
    val speedKmh: Float? = null,
    val dutyPercent: Float? = null,
    val motorCurrentA: Float? = null,
    val batteryCurrentA: Float? = null,
    val controllerTemperatureC: Float? = null,
    val motorTemperatureC: Float? = null,
    val batterySocPercent: Float? = null,
    val batteryVoltageV: Float? = null,
)

/**
 * Interpolates the HUD between controller frames. This is display-only: raw
 * samples still drive the history and all calculations. A dropped connection
 * clears values immediately instead of animating stale telemetry forever.
 */
internal class LightTelemetrySmoother(
    private val responseMillis: Long = 120L,
) {
    private var current: LightTelemetryValues? = null

    fun advance(target: LightTelemetryValues, deltaMillis: Long): LightTelemetryValues {
        val previous = current
        if (previous == null) return target.also { current = it }
        val alpha = (deltaMillis.coerceAtLeast(0L).toDouble() / responseMillis.coerceAtLeast(1L))
            .coerceIn(0.0, 1.0)
        return LightTelemetryValues(
            speedKmh = interpolate(previous.speedKmh, target.speedKmh, alpha),
            dutyPercent = interpolate(previous.dutyPercent, target.dutyPercent, alpha),
            motorCurrentA = interpolate(previous.motorCurrentA, target.motorCurrentA, alpha),
            batteryCurrentA = interpolate(previous.batteryCurrentA, target.batteryCurrentA, alpha),
            controllerTemperatureC = interpolate(previous.controllerTemperatureC, target.controllerTemperatureC, alpha),
            motorTemperatureC = interpolate(previous.motorTemperatureC, target.motorTemperatureC, alpha),
            batterySocPercent = interpolate(previous.batterySocPercent, target.batterySocPercent, alpha),
            batteryVoltageV = interpolate(previous.batteryVoltageV, target.batteryVoltageV, alpha),
        ).also { current = it }
    }

    private fun interpolate(previous: Float?, target: Float?, alpha: Double): Float? = when {
        target == null -> null
        previous == null -> target
        else -> (previous + (target - previous) * alpha.toFloat())
    }
}
