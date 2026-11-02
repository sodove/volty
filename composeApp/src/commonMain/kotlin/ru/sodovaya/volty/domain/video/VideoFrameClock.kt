package ru.sodovaya.volty.domain.video

/** Maps camera timestamps to receipt-time elapsed realtime, never wall clock.
 * UNKNOWN camera clocks have no exact cross-device synchronization contract. Their offset is
 * estimated from the earliest observed delivery; this preserves cadence, with delivery latency
 * as the remaining synchronization uncertainty. REALTIME clocks retain the actual capture time.
 */
class VideoFrameClock(private val realtime: Boolean) {
    private var offset: Long? = null
    private var previousCameraMillis: Long? = null

    fun frameMillis(cameraTimestampNanos: Long, elapsedNowMillis: Long): Long {
        val cameraMillis = cameraTimestampNanos / 1_000_000L
        if (realtime) return cameraMillis.coerceAtMost(elapsedNowMillis)
        if (previousCameraMillis?.let { cameraMillis < it } == true) offset = null
        previousCameraMillis = cameraMillis
        val observed = elapsedNowMillis - cameraMillis
        val selected = offset?.coerceAtMost(observed) ?: observed
        offset = selected
        return (cameraMillis + selected).coerceAtMost(elapsedNowMillis)
    }
}
