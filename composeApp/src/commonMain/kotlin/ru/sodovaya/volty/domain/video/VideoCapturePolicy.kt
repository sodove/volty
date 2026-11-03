package ru.sodovaya.volty.domain.video

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The rail replaces the panel as soon as the window is wider than tall; a square window keeps the panel. */
fun videoCaptureIsLandscape(width: Float, height: Float): Boolean = width > height

// --- the shutter -------------------------------------------------------------------------------------------------

enum class VideoShutterAction { START, STOP, NONE }

/**
 * What a press of the shutter means. This is the only road to a recording: nothing calls start on entering the
 * screen, and a press while the file is being prepared or finalized does nothing rather than queue a second action.
 */
fun videoShutterAction(phase: VideoRecordingPhase, ready: Boolean, blocked: Boolean = false): VideoShutterAction = when (phase) {
    // [blocked]: the editor is opening and the live overlay is off for its still. A recording started in that
    // window would burn nothing in for its whole length, which is the loss this feature exists to prevent.
    VideoRecordingPhase.IDLE -> if (ready && !blocked) VideoShutterAction.START else VideoShutterAction.NONE
    VideoRecordingPhase.RECORDING -> VideoShutterAction.STOP
    VideoRecordingPhase.STARTING, VideoRecordingPhase.STOPPING -> VideoShutterAction.NONE
}

/** A double tap with a glove, or a bumped key, must not start a recording and stop it again. */
class VideoShutterGuard(private val minIntervalMillis: Long = 800L) {
    private var last: Long? = null

    fun allow(nowMillis: Long): Boolean {
        val previous = last
        if (previous != null && nowMillis - previous < minIntervalMillis) return false
        last = nowMillis
        return true
    }
}

enum class VideoVolumeKey {
    /** Not ours: the system changes the volume as usual. */
    PASS,

    /** Swallow the key without acting. */
    CONSUME,
    SHUTTER,
}

/**
 * The volume keys START a recording and nothing else. **Retraction:** they were a full second shutter, so one press
 * stopped a live recording, with no question asked, although Back asks first because a stray glove tap must not end
 * the ride's video. A handlebar clamp grips exactly the edges the keys are on. While a file exists, the keys are
 * swallowed (no stop, and no volume change mid-ride); stopping is the on-screen shutter.
 *
 * The keys can still start a recording from a pocket when the screen is on and this screen is in front. That is a
 * press, not an auto-start, and it is the rider's call: the screen is kept on deliberately for the mount.
 */
fun videoVolumeKey(phase: VideoRecordingPhase, ready: Boolean, editing: Boolean, repeatCount: Int): VideoVolumeKey = when {
    editing -> VideoVolumeKey.PASS
    phase == VideoRecordingPhase.IDLE && !ready -> VideoVolumeKey.PASS
    phase != VideoRecordingPhase.IDLE -> VideoVolumeKey.CONSUME
    repeatCount > 0 -> VideoVolumeKey.CONSUME
    else -> VideoVolumeKey.SHUTTER
}

/** The panel's big clock, `00:12:48`. It never drops the hours, so its width holds through a long recording. */
fun videoRecordingClock(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    fun two(value: Long) = value.toString().padStart(2, '0')
    return "${two(seconds / 3600)}:${two((seconds % 3600) / 60)}:${two(seconds % 60)}"
}

enum class VideoHaptic { STARTED, STOPPED, FAILED, LOW_STORAGE }

/** How a recording ended, as the rider must feel it; kept apart from whether the camera still works. */
enum class VideoStopOutcome {
    /** The rider stopped it, and the file is whole. */
    COMPLETE,

    /** Something else stopped it (storage, the camera going away, a limit). A file was kept. */
    INTERRUPTED,

    /** Nothing usable was kept, or the camera failed. */
    FAILED,
}

/**
 * [cameraFailed] wins: a camera that failed mid-recording finalizes a perfectly good file, and an earlier revision
 * reported exactly that ("saved") while the shutter was dead.
 */
fun videoStopOutcome(cameraFailed: Boolean, discarded: Boolean, interrupted: Boolean, stoppedForStorage: Boolean): VideoStopOutcome = when {
    cameraFailed || discarded -> VideoStopOutcome.FAILED
    interrupted || stoppedForStorage -> VideoStopOutcome.INTERRUPTED
    else -> VideoStopOutcome.COMPLETE
}

/**
 * Feedback follows what the recorder actually did, not the press: one pulse for a started recording, two for a stop
 * the rider asked for, and a long one for any stop they did not ask for. **Retraction:** an interrupted recording
 * (storage, source inactive) used to get the same double pulse as a deliberate stop, so a rider at speed who felt
 * "stopped" without touching anything had no reason to look.
 */
fun videoHapticFor(previous: VideoRecordingPhase, next: VideoRecordingPhase, outcome: VideoStopOutcome?): VideoHaptic? = when {
    previous == next -> null
    next == VideoRecordingPhase.RECORDING -> VideoHaptic.STARTED
    next == VideoRecordingPhase.IDLE && previous != VideoRecordingPhase.IDLE ->
        if (outcome == null || outcome == VideoStopOutcome.COMPLETE) VideoHaptic.STOPPED else VideoHaptic.FAILED
    else -> null
}

/** One pulse at the moment the storage chip turns orange during a recording, and only then. */
fun videoStorageAlert(wasLow: Boolean, isLow: Boolean, recording: Boolean): Boolean = recording && isLow && !wasLow

/** Seconds of video the app keeps in hand: it stops the file itself rather than lean on CameraX's own reserve. */
const val STORAGE_STOP_MARGIN_SECONDS = 60L

/**
 * True when the app should stop the recording now: less than [STORAGE_STOP_MARGIN_SECONDS] of video fits. The
 * file then finalizes with room to spare for its index, instead of depending on the Recorder stopping in time.
 */
fun videoStorageMustStop(freeBytes: Long?, bitsPerSecond: Long): Boolean {
    if (freeBytes == null || freeBytes < 0 || bitsPerSecond <= 0) return false
    return freeBytes * 8 < bitsPerSecond * STORAGE_STOP_MARGIN_SECONDS
}

/**
 * Caps how often the RECORDED overlay is repainted. Each repaint is a full software raster of the whole frame on the
 * camera's GL thread, and with smoothed dials the redraw gate alone would let almost every frame through. The preview
 * is not capped. A change held back is not lost: the gate has not seen it, so it is drawn on the first frame after
 * the interval.
 */
class OverlayRepaintLimiter(private val minIntervalMillis: Long = 1000L / MAX_RECORDED_REPAINT_HZ) {
    private var last: Long? = null

    fun allow(nowMillis: Long, limited: Boolean): Boolean {
        val previous = last
        if (limited && previous != null && nowMillis - previous in 0 until minIntervalMillis) return false
        last = nowMillis
        return true
    }

    fun reset() {
        last = null
    }
}

const val MAX_RECORDED_REPAINT_HZ = 15L

// --- storage ---------------------------------------------------------------------------------------------------

/** Below this the chip turns orange. The owner's number. */
const val LOW_STORAGE_MINUTES = 10L

private const val AUDIO_BITS_PER_SECOND = 156_000L
private const val MEASURE_AFTER_NANOS = 10_000_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val BYTES_PER_MB = 1024L * 1024L
private const val BYTES_PER_GB = BYTES_PER_MB * 1024L

/** What CameraX's default profiles run at, roughly, by frame height. Only a guess until [videoBitsPerSecond] measures. */
fun videoNominalBitsPerSecond(height: Int): Long = when {
    height >= 2160 -> 45_000_000L
    height >= 1080 -> 17_000_000L
    height >= 720 -> 10_000_000L
    else -> 4_000_000L
}

/** The rate to divide free space by: this recording's own, once it has run long enough to mean something. */
fun videoBitsPerSecond(height: Int, audio: Boolean, recordedBytes: Long, recordedNanos: Long): Long {
    if (recordedNanos >= MEASURE_AFTER_NANOS && recordedBytes > 0) {
        return (recordedBytes.toDouble() * 8 * NANOS_PER_SECOND / recordedNanos).roundToLong()
    }
    return videoNominalBitsPerSecond(height) + if (audio) AUDIO_BITS_PER_SECOND else 0L
}

data class VideoStorageStatus(val freeBytes: Long, val minutesLeft: Long, val low: Boolean)

/** Null when the free space could not be read: the chip then says nothing rather than something made up. */
fun videoStorageStatus(freeBytes: Long?, bitsPerSecond: Long): VideoStorageStatus? {
    if (freeBytes == null || freeBytes < 0 || bitsPerSecond <= 0) return null
    val minutes = freeBytes * 8 / bitsPerSecond / 60
    return VideoStorageStatus(freeBytes, minutes, minutes < LOW_STORAGE_MINUTES)
}

/** A number and whether it is in GB (false: MB): `38`, `1.2`, `640`. */
data class VideoStorageSize(val text: String, val gigabytes: Boolean)

fun videoStorageSize(bytes: Long): VideoStorageSize {
    if (bytes < BYTES_PER_GB) return VideoStorageSize((bytes / BYTES_PER_MB).toString(), gigabytes = false)
    val tenths = (bytes * 10 / BYTES_PER_GB).toInt()
    val text = if (tenths >= 100) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    return VideoStorageSize(text, gigabytes = true)
}

// --- the vehicle link ------------------------------------------------------------------------------------------

sealed interface VideoLinkStatus {
    /** No vehicle is connected and none has been since this screen opened. */
    data object NoVehicle : VideoLinkStatus

    /** Connected, nothing measured yet. */
    data object Waiting : VideoLinkStatus

    data class Live(val ageMillis: Long) : VideoLinkStatus

    /** Data stopped [sinceMillis] ago. The same window that turns the overlay's values to dashes. */
    data class Lost(val sinceMillis: Long) : VideoLinkStatus
}

/**
 * Folds the telemetry stream into the capture screen's status chip. Age comes from the metrics' expiry, which already
 * knows how old the source was, because a re-emitted snapshot carries a new receipt time on old data.
 */
class VideoLinkMonitor(private val freshMillis: Long = 2_000L) {
    private var connected = false
    private var vehicleId: String? = null
    private var lastDataAt: Long? = null

    fun onSample(snapshot: VideoSnapshot?) {
        connected = snapshot != null
        if (snapshot == null) return
        if (snapshot.vehicleId != vehicleId) {
            vehicleId = snapshot.vehicleId
            lastDataAt = null
        }
        if (overlayNoLink(snapshot)) return
        // Duty's hold outlives its source on purpose, so it says nothing about the link.
        val sourceExpiry = snapshot.expiresAtMillis.filterKeys { it != VideoMetric.DUTY }.values.maxOrNull()
        val sourceTime = sourceExpiry?.let { minOf(it - freshMillis, snapshot.timeMillis) } ?: snapshot.timeMillis
        lastDataAt = maxOf(lastDataAt ?: sourceTime, sourceTime)
    }

    fun status(nowMillis: Long): VideoLinkStatus {
        val last = lastDataAt ?: return if (connected) VideoLinkStatus.Waiting else VideoLinkStatus.NoVehicle
        val age = (nowMillis - last).coerceAtLeast(0)
        return if (connected && age <= freshMillis) VideoLinkStatus.Live(age) else VideoLinkStatus.Lost(age)
    }
}

/** `0.2`, the seconds of a live link's age. */
fun videoAgeSeconds(ageMillis: Long): String {
    val tenths = (ageMillis.coerceAtLeast(0) / 100.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** Measures the stream's real rate, so the resolution chip prints a measurement instead of assuming 30. */
class VideoFrameRateMeter {
    private var windowStart: Long? = null
    private var frames = 0

    /** Returns the rate each time a one-second window closes, otherwise null. */
    fun onFrame(timestampNanos: Long): Int? {
        val start = windowStart
        if (start == null) {
            windowStart = timestampNanos
            frames = 0
            return null
        }
        frames++
        val elapsed = timestampNanos - start
        if (elapsed < NANOS_PER_SECOND) return null
        val rate = (frames.toDouble() * NANOS_PER_SECOND / elapsed).roundToInt()
        windowStart = timestampNanos
        frames = 0
        return rate
    }
}

// --- notices ---------------------------------------------------------------------------------------------------

enum class VideoCaptureNotice { ERROR, STORAGE_STOPPED, OVERLAY_FAILED, AUDIO_DENIED, CLOCK_MISMATCH, INTERRUPTED, SAVED_PARTS, SAVED }

data class VideoCaptureFacts(
    val error: Boolean = false,
    val overlayFailed: Boolean = false,
    val audioDenied: Boolean = false,
    val clockMismatch: Boolean = false,
    val saved: Boolean = false,
    val savedParts: Int = 0,
    val interrupted: Boolean = false,
    val stoppedForStorage: Boolean = false,
)

/** Most serious first; the screen shows the first [MAX_NOTICES], so a saved file can never hide a failure. */
fun videoCaptureNotices(facts: VideoCaptureFacts): List<VideoCaptureNotice> = buildList {
    if (facts.error) add(VideoCaptureNotice.ERROR)
    if (facts.stoppedForStorage) add(VideoCaptureNotice.STORAGE_STOPPED)
    if (facts.overlayFailed) add(VideoCaptureNotice.OVERLAY_FAILED)
    if (facts.audioDenied) add(VideoCaptureNotice.AUDIO_DENIED)
    if (facts.clockMismatch) add(VideoCaptureNotice.CLOCK_MISMATCH)
    if (facts.saved) add(
        when {
            facts.savedParts > 1 -> VideoCaptureNotice.SAVED_PARTS
            facts.interrupted -> VideoCaptureNotice.INTERRUPTED
            else -> VideoCaptureNotice.SAVED
        },
    )
}.take(MAX_NOTICES)

private const val MAX_NOTICES = 2
