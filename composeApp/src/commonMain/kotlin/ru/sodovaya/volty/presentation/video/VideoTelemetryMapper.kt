package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.domain.model.BmsCurrentSource
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.stats.BmsReadings
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.batteryInputCurrent
import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.VideoReading
import ru.sodovaya.volty.domain.video.VideoSnapshot
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val MAX_SOURCE_AGE_MILLIS = 2_000L

/**
 * Makes one frame-time snapshot from the current vehicle fold. Controller
 * metrics come only from [VehicleData.motion], and battery metrics only from
 * [VehicleData.aggregate]. INPUT_CURRENT explicitly selects real BMS first, with controller fallback.
 * The receipt time remains on the camera's elapsed-
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
    val controllerInput = motion.inputSample?.takeIf { motionAvailable }
    val inputAgeMillis = if (controllerInput != null) {
        sourceAgeMillis(controllerInput.measuredAtEpochMillis, nowEpochMillis)
    } else {
        motionAgeMillis
    }

    val readings = VideoMetric.entries.associateWith { VideoReading(value = null) }.toMutableMap()
    val expiries = mutableMapOf<VideoMetric, Long>()
    val measurementTimes = mutableMapOf<VideoMetric, Long>()
    fun sourceTime(age: Long): Long =
        if (receivedAtMillis < Long.MIN_VALUE + age) Long.MIN_VALUE else receivedAtMillis - age

    fun put(
        metric: VideoMetric,
        value: Float?,
        sourceAgeMillis: Long?,
        estimated: Boolean = false,
        atCeiling: Boolean = false,
        expiryOverrideMillis: Long? = null,
        sampleTimeOverrideMillis: Long? = null,
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
            val measuredAt = sampleTimeOverrideMillis ?: sourceAgeMillis?.let(::sourceTime)
            if (measuredAt != null) measurementTimes[metric] = measuredAt
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
            sourceSampleMillis = sourceTime(motionAgeMillis!!),
        )
        put(
            VideoMetric.DUTY,
            duty.reading.value?.toFloat(),
            sourceAgeMillis = null,
            estimated = duty.reading.estimated,
            atCeiling = duty.reading.atCeiling,
            expiryOverrideMillis = duty.expiresAtMillis,
            sampleTimeOverrideMillis = duty.sampledAtMillis,
        )

        put(VideoMetric.ESC_TEMPERATURE, MotionReadings.escTempC(motion), motionAgeMillis)
        put(VideoMetric.MOTOR_TEMPERATURE, MotionReadings.motorTempC(motion), motionAgeMillis)
        // ControllerData has no independent phase-current presence flag. This
        // mirrors the Light HUD: a nonzero current or known power is its evidence.
        put(VideoMetric.MOTOR_CURRENT, motion.motorCurrentA.takeIf { it != 0f || motion.hasPower }, motionAgeMillis)
        val inputCurrent = MotionReadings.batteryCurrentA(motion).takeIf { inputAgeMillis != null }
        val inputPower = if (controllerInput != null) controllerInput.powerW else MotionReadings.powerW(motion)
        put(VideoMetric.CONTROLLER_CURRENT, inputCurrent, inputAgeMillis)
        put(VideoMetric.CONTROLLER_POWER, inputPower.takeIf { inputAgeMillis != null }, inputAgeMillis)
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

    // The aggregate timestamp is the newest pack's: it cannot certify the other current contributors as fresh.
    val contributors = data.packs.filter { it.isOnline && it.data.hasCurrent }
    val packCurrentAvailable = data.packs.isEmpty() || (contributors.isNotEmpty() && contributors.all {
        it.data.isConnected && sourceAgeMillis(it.data.timestamp.toEpochMilliseconds(), nowEpochMillis) != null
    })
    val currentBattery = contributors.minByOrNull { it.data.timestamp }?.let {
        battery.copy(timestamp = it.data.timestamp)
    } ?: battery
    // A synthetic pack republishes the controller's reading with the pack's newer timestamp.
    val controllerMotion = if (controllerInput != null) {
        motion.copy(timestamp = Instant.fromEpochMilliseconds(controllerInput.measuredAtEpochMillis))
    } else {
        motion
    }
    val mirroredCurrent = controllerInput != null && currentBattery.currentSource == BmsCurrentSource.CONTROLLER
    val inputCurrent = batteryInputCurrent(controllerMotion, currentBattery, nowEpochMillis,
        motionAvailable = motionAvailable, batteryAvailable = batteryAvailable && packCurrentAvailable && !mirroredCurrent)
    put(VideoMetric.INPUT_CURRENT, inputCurrent?.dischargePositiveAmps,
        inputCurrent?.let { sourceAgeMillis(it.sampleEpochMillis, nowEpochMillis) })

    return VideoSnapshot(
        timeMillis = receivedAtMillis,
        vehicleId = vehicle.id,
        readings = readings,
        demo = vehicle.isDemo,
        expiresAtMillis = expiries,
        sampledAtMillis = measurementTimes,
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
