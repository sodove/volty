package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.domain.video.VideoReading

/**
 * Holds a duty (PWM) reading across the frames that republish it, so a Begode's overlay neither blinks nor sticks
 * at `—`.
 *
 * `ControllerData.dutyFresh` is true only for the rebuild that follows a 0x07 frame; the live (0x00) and odometer
 * rebuilds in between republish the same duty with `dutyFresh = false`. That flag exists for the PWM *evidence*
 * latch, where "a second genuine measurement" is the question. Reading it as "is this on screen right now" was a
 * second, different question answered with the wrong fact: the mapper blanked the duty on every non-0x07 snapshot,
 * and the overlay showed `—` for most of every 0x07 interval, while the dashboard (which has no such check) showed
 * the number. **Freshness for evidence is not staleness for display.**
 *
 * The rule here: a duty is *measured* by a fresh frame, then *held* for at most [maxHoldMillis] of the receipt
 * clock after that frame. A republish with no fresh frame behind it inside the bound shows `—`, exactly as before,
 * so a wheel that stops sending 0x07 does not freeze a number on screen. The hold is per vehicle and is dropped
 * when the vehicle changes, the link goes away, or the controller stops reporting duty at all.
 *
 * Not thread-safe; owned by the one collector that maps snapshots.
 */
class VideoDutyHold(private val maxHoldMillis: Long = DEFAULT_MAX_HOLD_MILLIS) {
    init {
        require(maxHoldMillis > 0L) { "maxHoldMillis must be positive" }
    }

    private var vehicleId: String? = null
    private var held: Held? = null

    /** What to show for the duty now, and until when; [Result.reading] has a null value when nothing is earned. */
    fun resolve(
        vehicleId: String,
        hasDuty: Boolean,
        fresh: Boolean,
        reading: VideoReading,
        receivedAtMillis: Long,
        sourceExpiryMillis: Long?,
        sourceSampleMillis: Long = receivedAtMillis,
    ): Result {
        if (this.vehicleId != vehicleId) {
            this.vehicleId = vehicleId
            held = null
        }
        if (!hasDuty || reading.value == null) {
            held = null
            return Result(VideoReading(value = null), null)
        }
        if (fresh) {
            // A measured duty is as good as any other source value (its own expiry); only the HOLD is bounded tighter.
            val measured = Held(reading, sourceSampleMillis, holdExpiry(sourceSampleMillis, sourceExpiryMillis))
            held = measured
            return Result(reading, sourceExpiryMillis ?: measured.expiresAtMillis, measured.freshAtMillis)
        }
        val current = held ?: return Result(VideoReading(value = null), null)
        if (receivedAtMillis < current.freshAtMillis || receivedAtMillis > current.expiresAtMillis) {
            held = null
            return Result(VideoReading(value = null), null)
        }
        // The republished value is the held one by construction; keep the held copy so the provenance flags
        // (estimated, ceiling) stay those of the frame that measured it.
        return Result(current.reading, current.expiresAtMillis, current.freshAtMillis)
    }

    /** Forget everything, e.g. when the vehicle disconnects. */
    fun reset() {
        vehicleId = null
        held = null
    }

    private fun holdExpiry(freshAtMillis: Long, sourceExpiryMillis: Long?): Long {
        val bound = if (freshAtMillis > Long.MAX_VALUE - maxHoldMillis) Long.MAX_VALUE else freshAtMillis + maxHoldMillis
        return if (sourceExpiryMillis == null) bound else minOf(bound, sourceExpiryMillis)
    }

    class Result(val reading: VideoReading, val expiresAtMillis: Long?, val sampledAtMillis: Long? = null)

    private class Held(val reading: VideoReading, val freshAtMillis: Long, val expiresAtMillis: Long)

    companion object {
        /** About four missed 0x07 frames at the wheel's ~3 Hz; longer than that is a silent field, not a held one. */
        const val DEFAULT_MAX_HOLD_MILLIS = 1_500L
    }
}
