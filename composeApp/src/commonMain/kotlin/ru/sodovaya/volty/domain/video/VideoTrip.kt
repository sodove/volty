package ru.sodovaya.volty.domain.video

/**
 * The two numbers a snapshot cannot carry alone. Null is unknown and prints `—`.
 *
 * [OverlayTripScope.RECORDING] (the default): time is the file's own recorded duration, distance is what the
 * controller's counter added since the recording began. [OverlayTripScope.RIDE]: both are the connection session the
 * dashboard shows, so the video agrees with it.
 */
data class OverlayTrip(val elapsedSeconds: Long? = null, val distanceKm: Double? = null)

/** The value a widget shows: the trip numbers for the two trip metrics, the snapshot's reading for the rest. */
fun overlayReading(metric: VideoMetric, snapshot: VideoSnapshot?, trip: OverlayTrip): VideoReading? = when (metric) {
    VideoMetric.ELAPSED -> VideoReading(trip.elapsedSeconds?.toDouble())
    VideoMetric.DISTANCE -> VideoReading(trip.distanceKm)
    else -> snapshot?.readings?.get(metric)
}

/** True when [preset] prints a time or a distance, so the scope setting means something for it. */
fun overlayUsesTrip(preset: OverlayPreset): Boolean = preset.widgets.any { widget ->
    widget.metric == VideoMetric.ELAPSED || widget.metric == VideoMetric.DISTANCE
}

/**
 * Folds the controller's session counter into distance per recording. The counter restarts from zero when a link
 * reconnects, so a drop in it is a new baseline and not negative distance: what was covered before the drop is kept.
 * While the counter is unknown the result is null, and the distance is not lost: it resumes from where it was.
 */
class VideoTripTracker {
    private var baseline: Double? = null
    private var last = 0.0
    private var carried = 0.0

    fun reset() {
        baseline = null
        last = 0.0
        carried = 0.0
    }

    /** Distance since the last [reset], from [sessionKm], the counter's current value. */
    fun recordingDistance(sessionKm: Double?): Double? {
        val current = sessionKm?.takeIf(Double::isFinite) ?: return null
        val start = baseline
        if (start == null) {
            baseline = current
        } else if (current < last) {
            carried += last - start
            baseline = current
        }
        last = current
        return carried + (current - (baseline ?: current))
    }

    /** The whole frame's trip values for [scope]. [rideSeconds] comes from [RideSessionClock]. */
    fun trip(
        scope: OverlayTripScope,
        recording: Boolean,
        recordedSeconds: Long,
        rideSeconds: Long?,
        sessionKm: Double?,
    ): OverlayTrip = when (scope) {
        OverlayTripScope.RIDE -> OverlayTrip(rideSeconds, sessionKm?.takeIf(Double::isFinite))
        OverlayTripScope.RECORDING -> {
            if (!recording) reset()
            OverlayTrip(recordedSeconds.takeIf { recording } ?: 0L, if (recording) recordingDistance(sessionKm) else null)
        }
    }
}

/**
 * When the dashboard's current ride session began, for the [OverlayTripScope.RIDE] time. The dashboard owns that
 * clock and the camera screen sits on top of it in another activity, so this one value is shared rather than a second
 * clock being started that would disagree. Null means no session is running and the time prints `—`.
 */
object RideSessionClock {
    @kotlin.concurrent.Volatile
    var startedAtEpochMillis: Long? = null

    fun secondsAt(nowEpochMillis: Long): Long? = startedAtEpochMillis?.let { ((nowEpochMillis - it) / 1000).coerceAtLeast(0) }
}
