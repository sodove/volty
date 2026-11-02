package ru.sodovaya.volty.domain.video

import kotlin.math.abs

/**
 * Turns the accelerometer's device angle (`OrientationEventListener`, degrees clockwise, -1 when flat or unknown)
 * into a `Surface.ROTATION_*` value (0, 1, 2, 3 for 0, 90, 180, 270) for the video file.
 *
 * **Retraction.** The file's rotation used to come from the display only, updated from `onConfigurationChanged`.
 * With auto-rotate off a phone clamped in landscape stays at display rotation 0 and the file came out sideways;
 * a 90 to 270 flip fires no configuration change at all and came out upside down. The sensor knows how the phone
 * is held whatever the UI is doing.
 *
 * A change needs [hysteresisDegrees] beyond the 45 degree boundary, so a phone held near 45 degrees on a
 * vibrating handlebar does not flap between two rotations.
 */
class VideoOrientationPolicy(private val hysteresisDegrees: Int = 10) {
    var rotation: Int? = null
        private set

    /** The new rotation if [degrees] changed it, else null. */
    fun onSensorDegrees(degrees: Int): Int? {
        if (degrees !in 0..359) return null
        val current = rotation
        val candidate = sectorOf(degrees)
        if (current == null) {
            rotation = candidate
            return candidate
        }
        if (candidate == current) return null
        if (angularDistance(degrees, centerOf(current)) < 45 + hysteresisDegrees) return null
        rotation = candidate
        return candidate
    }

    private fun sectorOf(degrees: Int): Int = when (degrees) {
        in 45 until 135 -> ROTATION_270
        in 135 until 225 -> ROTATION_180
        in 225 until 315 -> ROTATION_90
        else -> ROTATION_0
    }

    private fun centerOf(rotation: Int): Int = when (rotation) {
        ROTATION_270 -> 90
        ROTATION_180 -> 180
        ROTATION_90 -> 270
        else -> 0
    }

    private fun angularDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    companion object {
        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val ROTATION_180 = 2
        const val ROTATION_270 = 3
    }
}
