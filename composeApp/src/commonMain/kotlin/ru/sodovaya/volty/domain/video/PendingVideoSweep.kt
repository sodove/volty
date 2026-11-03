package ru.sodovaya.volty.domain.video

/** One `IS_PENDING` MediaStore row this app owns under `Movies/Volty`. */
data class PendingVideoRow(val id: Long, val sizeBytes: Long, val lastModifiedMillis: Long)

enum class PendingVideoAction {
    /** Nothing playable ever reached the file: remove the row so the gallery is not left with a ghost. */
    DELETE_EMPTY,

    /** Something was written: clear `IS_PENDING` so the rider can find the file (and try to repair it). */
    PUBLISH,

    /** Too fresh to judge; a recording may still own it. */
    LEAVE,
}

/**
 * Decides what the startup sweep does with a row a crashed process left pending. CameraX sets `IS_PENDING` when a
 * recording starts and clears it only on finalize, so a crash, kill or flat battery leaves the row hidden from the
 * gallery until the system expires it (about a week), and the video with it.
 *
 * Honest limit: a file that was never finalized has no MP4 index, and most players cannot open it. Publishing it
 * is still right (the bytes are the rider's, and an external repair tool can rebuild the index from them), but
 * this sweep does not repair anything; [VideoSegmentPolicy] is why short segments exist.
 *
 * The sweep runs once per process start, when no recording of this app can be active, so [minAgeMillis] is only a
 * guard against a clock that has just been stepped.
 */
fun pendingVideoAction(
    row: PendingVideoRow,
    nowMillis: Long,
    minAgeMillis: Long = 60_000L,
    emptyBelowBytes: Long = EMPTY_BELOW_BYTES,
): PendingVideoAction = when {
    nowMillis - row.lastModifiedMillis in 0 until minAgeMillis -> PendingVideoAction.LEAVE
    row.sizeBytes < emptyBelowBytes -> PendingVideoAction.DELETE_EMPTY
    else -> PendingVideoAction.PUBLISH
}

/** A header with no samples (ftyp + an empty mdat) is a few hundred bytes; a real second of video is far larger. */
const val EMPTY_BELOW_BYTES = 4_096L
