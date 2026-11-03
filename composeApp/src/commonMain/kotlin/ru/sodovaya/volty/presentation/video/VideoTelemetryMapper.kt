package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.stats.BmsReadings
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.VideoReading
import ru.sodovaya.volty.domain.video.VideoSnapshot
import kotlin.time.ExperimentalTime

private const val MAX_SOURCE_AGE_MILLIS = 2_000L

/**
 * Makes one frame-time snapshot from the current vehicle fold. Controller
 * metrics come only from [VehicleData.motion], and battery metrics only from
 * [VehicleData.aggregate]. The receipt time remains on the camera's elapsed-
 * realtime clock; epoch time is used separately to reject stale source data.
 */
@OptIn(ExperimentalTime::class)
fun videoTelemetrySnapshot(
    data: VehicleData,
    vehicle: Vehicle?,
    connection: ConnectionState,
    receivedAtMillis: Long,
    nowEpochMillis: Long,
    dutyHold: VideoDutyHold = VideoDutyHold(),
): VideoSnapshot? {
    if (vehicle == null || connection !is ConnectionState.Connected) { dutyHold.reset(); return null }
    if (connection.vehicle?.id?.let { it != vehicle.id } == true) { dutyHold.reset(); return null }

    val motion = data.motion
    val battery = data.aggregate
    val motionAgeMillis = if (!data.motionPartial && motion.isConnected) {
        sourceAgeMillis(motion.timestamp.toEpochMilliseconds(), nowEpochMillis)
    } else {
        null
    }
    val batteryAgeMillis = if (!data.isPartial && battery.isConnected) {
        sourceAgeMillis(battery.timestamp.toEpochMilliseconds(), nowEpochMillis)
    } else {
        null
    }
    val motionAvailable = motionAgeMillis != null
    val batteryAvailable = batteryAgeMillis != null

    val readings = VideoMetric.entries.associateWith { VideoReading(value = null) }.toMutableMap()
    val expiries = mutableMapOf<VideoMetric, Long>()

    fun put(
        metric: VideoMetric,
        value: Float?,
        sourceAgeMillis: Long?,
        estimated: Boolean = false,
        atCeiling: Boolean = false,
        expiryOverrideMillis: Long? = null,
    ) {
        val finite = value?.takeIf { it.isFinite() }?.toDouble()
        readings[metric] = VideoReading(
            value = finite,
            estimated = finite != null && estimated,
            atCeiling = finite != null && estimated && atCeiling,
        )
        if (finite != null) {
            val expiry = expiryOverrideMillis
                ?: sourceAgeMillis?.let { expiryAtMillis(receivedAtMillis, it) }
            if (expiry != null) expiries[metric] = expiry
        }
    }

    if (motionAvailable) {
        put(VideoMetric.SPEED, MotionReadings.speedKmh(motion), motionAgeMillis)

        // dutyFresh means this snapshot's own frame decoded the duty - a fact about evidence, not about whether
        // the number is still true. The hold keeps a measured duty on screen between a Begode's 0x07 frames,
        // bounded in time (see VideoDutyHold; an earlier revision blanked it on every non-0x07 snapshot).
        val dutyValue = MotionReadings.dutyPercent(motion)?.takeIf { it.isFinite() }?.toDouble()
        val isEstimatedDuty = dutyValue != null && motion.dutySource == DutySource.ESTIMATED
        val duty = dutyHold.resolve(
            vehicleId = vehicle.id,
            hasDuty = motion.hasDuty,
            fresh = motion.dutyFresh,
            reading = VideoReading(dutyValue, isEstimatedDuty, isEstimatedDuty && motion.dutyAtCeiling),
            receivedAtMillis = receivedAtMillis,
            sourceExpiryMillis = motionAgeMillis?.let { expiryAtMillis(receivedAtMillis, it) },
        )
        put(
            VideoMetric.DUTY,
            duty.reading.value?.toFloat(),
            sourceAgeMillis = null,
            estimated = duty.reading.estimated,
            atCeiling = duty.reading.atCeiling,
            expiryOverrideMillis = duty.expiresAtMillis,
        )

        put(VideoMetric.ESC_TEMPERATURE, MotionReadings.escTempC(motion), motionAgeMillis)
        put(VideoMetric.MOTOR_TEMPERATURE, MotionReadings.motorTempC(motion), motionAgeMillis)
        // ControllerData has no independent phase-current presence flag. This
        // mirrors the Light HUD: a nonzero current or known power is its evidence.
        put(VideoMetric.MOTOR_CURRENT, motion.motorCurrentA.takeIf { it != 0f || motion.hasPower }, motionAgeMillis)
        put(VideoMetric.CONTROLLER_CURRENT, MotionReadings.batteryCurrentA(motion), motionAgeMillis)
        put(VideoMetric.CONTROLLER_POWER, MotionReadings.powerW(motion), motionAgeMillis)
        put(VideoMetric.DISTANCE, MotionReadings.tripKm(motion), motionAgeMillis)
    } else {
        dutyHold.reset()
    }

    if (batteryAvailable) {
        put(VideoMetric.SOC, battery.soc.takeIf { battery.socKnown }, batteryAgeMillis)
        // BmsData has no voltage-known flag and defaults it to zero, so follow
        // the Light HUD's positive-voltage evidence rule for this one metric.
        put(VideoMetric.VOLTAGE, battery.voltage.takeIf { it > 0f }, batteryAgeMillis)
        put(VideoMetric.BATTERY_CURRENT, BmsReadings.current(battery), batteryAgeMillis)
        put(VideoMetric.BATTERY_POWER, BmsReadings.power(battery), batteryAgeMillis)
        put(
            VideoMetric.BATTERY_TEMPERATURE,
            battery.temperatures.filter { it.isFinite() }.maxOrNull(),
            batteryAgeMillis,
        )
    }

    return VideoSnapshot(
        timeMillis = receivedAtMillis,
        vehicleId = vehicle.id,
        readings = readings,
        demo = vehicle.isDemo,
        expiresAtMillis = expiries,
    )
}

private fun sourceAgeMillis(sampleEpochMillis: Long, nowEpochMillis: Long): Long? {
    if (sampleEpochMillis > nowEpochMillis) return null
    val oldestFreshMillis = if (nowEpochMillis < Long.MIN_VALUE + MAX_SOURCE_AGE_MILLIS) {
        Long.MIN_VALUE
    } else {
        nowEpochMillis - MAX_SOURCE_AGE_MILLIS
    }
    if (sampleEpochMillis < oldestFreshMillis) return null
    return nowEpochMillis - sampleEpochMillis
}

private fun expiryAtMillis(receivedAtMillis: Long, sourceAgeMillis: Long): Long {
    val remainingFreshMillis = MAX_SOURCE_AGE_MILLIS - sourceAgeMillis
    return if (receivedAtMillis > Long.MAX_VALUE - remainingFreshMillis) {
        Long.MAX_VALUE
    } else {
        receivedAtMillis + remainingFreshMillis
    }
}
