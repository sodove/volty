package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.VideoSnapshot
import ru.sodovaya.volty.presentation.ride.LightTelemetrySmoother
import ru.sodovaya.volty.presentation.ride.LightTelemetryValues

/**
 * Runs the dashboard's [LightTelemetrySmoother] over the overlay's dials and bars, on the camera frame clock. Only the
 * sweep moves: the numbers stay raw, because a number burned into a video must be a reading and never a value between
 * two. An unknown clears at once, as on the dashboard, and the chase only ever lags: it can never lead the data.
 *
 * Metrics the smoother does not carry are simply absent from the result, and their dials step as they arrive.
 */
class VideoDialSmoother {
    private var smoother = LightTelemetrySmoother()
    private var vehicleId: String? = null
    private var lastFrameMillis: Long? = null

    fun advance(snapshot: VideoSnapshot?, frameMillis: Long): Map<VideoMetric, Double> {
        if (snapshot == null) return reset()
        if (snapshot.vehicleId != vehicleId) {
            reset()
            vehicleId = snapshot.vehicleId
        }
        fun value(metric: VideoMetric) = snapshot.readings[metric]?.value?.takeIf(Double::isFinite)?.toFloat()
        val target = LightTelemetryValues(
            speedKmh = value(VideoMetric.SPEED),
            dutyPercent = value(VideoMetric.DUTY),
            motorCurrentA = value(VideoMetric.MOTOR_CURRENT),
            batteryCurrentA = value(VideoMetric.BATTERY_CURRENT),
            controllerTemperatureC = value(VideoMetric.ESC_TEMPERATURE),
            motorTemperatureC = value(VideoMetric.MOTOR_TEMPERATURE),
            batterySocPercent = value(VideoMetric.SOC),
            batteryVoltageV = value(VideoMetric.VOLTAGE),
        )
        val delta = lastFrameMillis?.let { frameMillis - it } ?: 0L
        lastFrameMillis = frameMillis
        val shown = smoother.advance(target, delta)
        return listOfNotNull(
            shown.speedKmh?.let { VideoMetric.SPEED to it.toDouble() },
            shown.dutyPercent?.let { VideoMetric.DUTY to it.toDouble() },
            shown.motorCurrentA?.let { VideoMetric.MOTOR_CURRENT to it.toDouble() },
            shown.batteryCurrentA?.let { VideoMetric.BATTERY_CURRENT to it.toDouble() },
            shown.controllerTemperatureC?.let { VideoMetric.ESC_TEMPERATURE to it.toDouble() },
            shown.motorTemperatureC?.let { VideoMetric.MOTOR_TEMPERATURE to it.toDouble() },
            shown.batterySocPercent?.let { VideoMetric.SOC to it.toDouble() },
            shown.batteryVoltageV?.let { VideoMetric.VOLTAGE to it.toDouble() },
        ).toMap()
    }

    private fun reset(): Map<VideoMetric, Double> {
        smoother = LightTelemetrySmoother()
        lastFrameMillis = null
        return emptyMap()
    }
}
