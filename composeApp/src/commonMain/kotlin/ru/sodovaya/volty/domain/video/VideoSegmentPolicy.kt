package ru.sodovaya.volty.domain.video

/**
 * Splits one ride into several files so a crash, a kill or a flat battery costs the last piece only.
 *
 * `MediaMuxer` writes the MP4 index when a file is finalized, so a process that dies mid-file leaves a file no
 * player can open (and a hidden `IS_PENDING` MediaStore row). The only protection that does not need an owned
 * MediaCodec pipeline is to finalize often.
 *
 * **Known cost, not measured.** CameraX 1.5.3's `Recorder` cannot rotate files seamlessly: a new recording started
 * while the previous one is stopping is queued (`PENDING_RECORDING`) until the recorder has finalized the old
 * file and re-configured, so there is a gap in picture AND sound at every rotation. Nobody has measured it on a
 * phone, and it may well be visible. So rotation is OFF by default ([DEFAULT_SEGMENT_MINUTES] = 0) until the
 * rider has measured the gap on the real device; flipping that one constant (or choosing a length on the camera
 * screen) turns it on.
 *
 * @param segmentMinutes 0 disables rotation.
 */
class VideoSegmentPolicy(val segmentMinutes: Int) {
    init {
        require(segmentMinutes >= 0) { "segmentMinutes must not be negative" }
    }

    val enabled: Boolean get() = segmentMinutes > 0
    private val segmentNanos: Long get() = segmentMinutes * 60L * 1_000_000_000L

    /** True once the current file has recorded its share and no rotation is already in flight. */
    fun shouldRotate(segmentRecordedNanos: Long, rotationInFlight: Boolean): Boolean =
        enabled && !rotationInFlight && segmentRecordedNanos >= segmentNanos

    companion object {
        const val DEFAULT_SEGMENT_MINUTES = 0
        val CHOICES_MINUTES: List<Int> = listOf(0, 5, 10, 15)

        /** Anything unknown (a stale preference, a hand edit) becomes the default instead of a surprise length. */
        fun sanitized(minutes: Int): Int = if (minutes in CHOICES_MINUTES) minutes else DEFAULT_SEGMENT_MINUTES
    }
}

/** `Volty_20261102_101500_123` for a single file, `..._part02` once the ride is split. */
fun videoSegmentName(rideStamp: String, partIndex: Int, segmented: Boolean): String =
    if (segmented) "${rideStamp}_part${partIndex.coerceAtLeast(1).toString().padStart(2, '0')}" else rideStamp

/** Total recorded time across finished segments plus the one in progress, for the on-screen timer. */
class VideoSegmentLedger {
    private var finishedNanos = 0L
    var partIndex: Int = 1
        private set

    fun segmentFinished(recordedNanos: Long) {
        finishedNanos += recordedNanos.coerceAtLeast(0L)
    }

    fun nextPart(): Int = ++partIndex

    fun totalNanos(currentSegmentNanos: Long): Long = finishedNanos + currentSegmentNanos.coerceAtLeast(0L)

    fun reset() {
        finishedNanos = 0L
        partIndex = 1
    }
}
