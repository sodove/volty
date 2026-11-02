package ru.sodovaya.volty.domain.video

enum class VideoOutputStatus { COMPLETE, INTERRUPTED, DISCARD }

/** A documented recoverable stop can leave a usable video; don't discard the ride's recorded part. */
fun videoOutputStatus(success: Boolean, recoverable: Boolean, hasOutput: Boolean, recordedNanos: Long): VideoOutputStatus = when {
    !hasOutput || recordedNanos <= 0 -> VideoOutputStatus.DISCARD
    success -> VideoOutputStatus.COMPLETE
    recoverable -> VideoOutputStatus.INTERRUPTED
    else -> VideoOutputStatus.DISCARD
}
