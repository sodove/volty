package ru.sodovaya.volty.domain.video

import kotlin.math.abs

/** Maps camera timestamps to receipt-time elapsed realtime, never wall clock.
 * UNKNOWN camera clocks have no exact cross-device synchronization contract. Their offset is
 * estimated from the earliest observed delivery; this preserves cadence, with delivery latency
 * as the remaining synchronization uncertainty. REALTIME clocks retain the actual capture time.
 *
 * **Timebase check.** CameraX documents a quirk (`CameraUseInconsistentTimebaseQuirk`): some Samsung and some
 * Qualcomm devices report `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` yet stamp frames on the uptime clock. Uptime
 * stops in deep sleep and elapsed realtime does not, so the frame time then trails real time by every second the
 * phone ever slept, and since [VideoTimeline.sample] only accepts a sample at most two seconds old, EVERY value in
 * the whole video would show `—` without a word of explanation. A real REALTIME camera lags by delivery latency
 * (tens of milliseconds, a second under load), so a lag beyond [MAX_PLAUSIBLE_LAG_MILLIS] on [CONFIRM_FRAMES]
 * consecutive frames means the claim is false. The clock then latches [timebaseMismatch] and estimates its
 * offset the way an UNKNOWN clock does, which is exact up to delivery latency, and the caller surfaces the flag
 * to the rider instead of letting the numbers vanish silently. This is code reading: no device with the quirk
 * has been tried.
 */
class VideoFrameClock(private val realtime: Boolean) {
    private var offset: Long? = null
    private var previousCameraMillis: Long? = null
    private var implausibleRun = 0

    /** True once a REALTIME claim was caught contradicting the receipt clock; never cleared. */
    var timebaseMismatch: Boolean = false
        private set

    fun frameMillis(cameraTimestampNanos: Long, elapsedNowMillis: Long): Long {
        val cameraMillis = cameraTimestampNanos / 1_000_000L
        if (realtime && !timebaseMismatch) {
            val lag = elapsedNowMillis - cameraMillis
            implausibleRun = if (abs(lag) > MAX_PLAUSIBLE_LAG_MILLIS) implausibleRun + 1 else 0
            if (implausibleRun >= CONFIRM_FRAMES) timebaseMismatch = true
            if (!timebaseMismatch) return cameraMillis.coerceAtMost(elapsedNowMillis)
        }
        if (previousCameraMillis?.let { cameraMillis < it } == true) offset = null
        previousCameraMillis = cameraMillis
        val observed = elapsedNowMillis - cameraMillis
        val selected = offset?.coerceAtMost(observed) ?: observed
        offset = selected
        return (cameraMillis + selected).coerceAtMost(elapsedNowMillis)
    }

    companion object {
        const val MAX_PLAUSIBLE_LAG_MILLIS = 5_000L
        const val CONFIRM_FRAMES = 15
    }
}
