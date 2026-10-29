package ru.sodovaya.volty.diagnostics

/** Why a database share was declined; the settings page turns it into a sentence. */
enum class SnapshotRefusal { RECORDING, NO_SPACE }

sealed interface SnapshotPlan {
    data object Copy : SnapshotPlan
    data class Refuse(val reason: SnapshotRefusal) : SnapshotPlan
}

/** Room that must stay free beyond the copy itself (the gzip pass and the system's own needs). */
const val SNAPSHOT_FREE_MARGIN_BYTES: Long = 16L * 1024 * 1024

/** Files at or above this size are gzipped before sharing; a small DB is shared as it is. */
const val SNAPSHOT_COMPRESS_FROM_BYTES: Long = 1L * 1024 * 1024

/**
 * The pure decision behind the debug "share database" action. A ride in progress is refused
 * whatever the free space is: the recorder is writing, and the rider's copy should not race it.
 */
fun databaseSnapshotPlan(recording: Boolean, dbBytes: Long, freeBytes: Long): SnapshotPlan = when {
    recording -> SnapshotPlan.Refuse(SnapshotRefusal.RECORDING)
    freeBytes < dbBytes + SNAPSHOT_FREE_MARGIN_BYTES -> SnapshotPlan.Refuse(SnapshotRefusal.NO_SPACE)
    else -> SnapshotPlan.Copy
}

/** `volty-db-<yyyy-MM-dd_HH-mm-ss>.db`, `.db.gz` when compressed. */
fun databaseSnapshotFileName(
    year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, compressed: Boolean,
): String {
    fun two(v: Int) = v.toString().padStart(2, '0')
    val stamp = "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}_${two(hour)}-${two(minute)}-${two(second)}"
    return "volty-db-$stamp.db" + if (compressed) ".gz" else ""
}

fun shouldCompressSnapshot(dbBytes: Long): Boolean = dbBytes >= SNAPSHOT_COMPRESS_FROM_BYTES

sealed interface DatabaseShareOutcome {
    /** The share sheet was opened. */
    data object Shared : DatabaseShareOutcome
    data class Refused(val reason: SnapshotRefusal) : DatabaseShareOutcome
    data object Failed : DatabaseShareOutcome
}

/**
 * Debug-only: hands a consistent copy of the history database to the share sheet. Read-only on
 * the database. Does its own IO off the caller's thread.
 */
interface DatabaseSnapshotExporter {
    suspend fun share(recording: Boolean): DatabaseShareOutcome
}

/** The part of the settings page that decides *whether* to share, split out so it is testable. */
class DatabaseShareAction(
    private val exporter: DatabaseSnapshotExporter,
    private val isRecording: suspend () -> Boolean,
) {
    suspend fun run(): DatabaseShareOutcome = exporter.share(isRecording())
}
