package ru.sodovaya.volty.domain.audio

import kotlin.math.abs
import ru.sodovaya.volty.domain.model.AutoVolumeSettings

/**
 * Pure decision core for the vehicle's speed-based media-volume curve.
 *
 * A decision is emitted only when speed moved beyond the configured deadband
 * since the last applied decision and the target changed after quantization to
 * the phone's media-volume steps. Manual volume changes therefore survive at a
 * standstill and until the curve has a reason to reclaim control.
 */
class AutoVolumeLogic(
    private val settings: AutoVolumeSettings,
    private val volumeSteps: Int
) {
    data class Decision(val step: Int, val targetPercent: Int, val speedKmh: Int)

    private var lastAppliedSpeed: Int? = null
    private var lastAppliedStep: Int? = null

    fun targetPercent(speedKmh: Int): Int {
        if (speedKmh <= settings.minSpeedKmh) return settings.minVolumePercent
        if (speedKmh >= settings.maxSpeedKmh) return settings.maxVolumePercent
        val fraction = (speedKmh - settings.minSpeedKmh).toFloat() /
            (settings.maxSpeedKmh - settings.minSpeedKmh)
        return (settings.minVolumePercent +
            (settings.maxVolumePercent - settings.minVolumePercent) * fraction).toInt()
    }

    fun onSpeed(speedKmh: Int): Decision? {
        if (volumeSteps <= 0) return null
        val lastSpeed = lastAppliedSpeed
        if (lastSpeed != null && abs(speedKmh - lastSpeed) <= settings.deadbandKmh) return null
        val target = targetPercent(speedKmh)
        val step = ((target * volumeSteps) + 50) / 100
        if (step == lastAppliedStep) return null
        lastAppliedSpeed = speedKmh
        lastAppliedStep = step
        return Decision(step = step.coerceIn(0, volumeSteps), targetPercent = target, speedKmh = speedKmh)
    }

    fun reset() {
        lastAppliedSpeed = null
        lastAppliedStep = null
    }
}
