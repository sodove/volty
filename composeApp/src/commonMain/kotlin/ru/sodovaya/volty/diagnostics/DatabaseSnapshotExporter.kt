package ru.sodovaya.volty.diagnostics

/** Why a database share was declined; the settings page turns it into a sentence. */
enum class SnapshotRefusal {
    RECORDING, NO_SPACE,
    /** The write lock could not be taken within the driver's busy timeout; try again. */
    BUSY,
    /** The checkpoint reported success yet a WAL remained: the files disagree, so no copy is trusted. */
    INCONSISTENT,
}

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

/** What to copy once the checkpoint inside the write transaction has been tried. */
sealed interface SnapshotFiles {
    data object DbOnly : SnapshotFiles
    /** Committed data may still live in `volty.db-wal`: ship it with the main file. */
    data object DbAndWal : SnapshotFiles
    data class Refuse(val reason: SnapshotRefusal) : SnapshotFiles
}

/**
 * [checkpointBusy] is the `busy` column of `PRAGMA wal_checkpoint(TRUNCATE)` run inside the
 * write transaction (null when it threw: unknown). A non-empty WAL always travels with the db,
 * because a snapshot that silently misses committed data is worse than none. A checkpoint that
 * claims success while a WAL remains cannot happen under our lock, so it is refused.
 */
fun snapshotFilesDecision(checkpointBusy: Boolean?, walBytes: Long): SnapshotFiles = when {
    walBytes <= 0L -> SnapshotFiles.DbOnly
    checkpointBusy == false -> SnapshotFiles.Refuse(SnapshotRefusal.INCONSISTENT)
    else -> SnapshotFiles.DbAndWal
}

/** Every file SQLite may keep beside `volty.db`, in the order they go into the zip after it. */
val DATABASE_COMPANION_SUFFIXES: List<String> = listOf("-journal", "-wal", "-shm")

/**
 * The companions an UNOPENED share (the startup gate's error screen) ships with `volty.db`: every one that exists, whatever
 * its size. [lengthOf] is a suffix's file length, or null when there is no such file.
 *
 * Nothing has the database open there, so nothing has replayed a WAL or rolled back a journal. After a migration killed
 * mid-transaction `volty.db` alone is a torn file whose good pages are in `volty.db-journal` (final review I1); shipping the
 * journal lets whoever opens the copy read-write get the rider's database back. Retraction: this share used to copy
 * `volty.db` and a non-empty `-wal` only.
 */
fun unopenedSnapshotCompanions(lengthOf: (suffix: String) -> Long?): List<String> =
    DATABASE_COMPANION_SUFFIXES.filter { lengthOf(it) != null }

/**
 * True while the recorder owns the database: an open ride, or a GPS route starting/recording/
 * stopping. The same fields the app's own recording UI reads (`canStop` is `ride != null`).
 */
fun historyRecordingInProgress(status: ru.sodovaya.volty.domain.history.HistoryRecordingStatus): Boolean =
    status.ride != null || status.route == ru.sodovaya.volty.domain.history.HistoryRecordingRouteStatus.STARTING ||
        status.route == ru.sodovaya.volty.domain.history.HistoryRecordingRouteStatus.RECORDING ||
        status.route == ru.sodovaya.volty.domain.history.HistoryRecordingRouteStatus.STOPPING

/** `volty-db-<yyyy-MM-dd_HH-mm-ss>.db`, `.db.gz` when compressed, `.zip` for the db with any companion file. */
fun databaseSnapshotFileName(
    year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, compressed: Boolean,
    zipped: Boolean = false,
): String {
    fun two(v: Int) = v.toString().padStart(2, '0')
    val stamp = "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}_${two(hour)}-${two(minute)}-${two(second)}"
    if (zipped) return "volty-db-$stamp.zip"
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
    /**
     * [isRecording] is asked before any work and again inside the write transaction, so a ride
     * that starts in between still refuses the copy.
     */
    suspend fun share(isRecording: suspend () -> Boolean): DatabaseShareOutcome

    /**
     * Copies the files of a database that could not be opened (the startup gate's error screen):
     * no transaction, no recording check (nothing records while the gate is closed), `volty.db` plus
     * every companion [unopenedSnapshotCompanions] names. Only meaningful while nothing else has the
     * file open for writing.
     */
    suspend fun shareUnopened(): DatabaseShareOutcome = DatabaseShareOutcome.Failed
}

/** The part of the settings page that decides *whether* to share, split out so it is testable. */
class DatabaseShareAction(
    private val exporter: DatabaseSnapshotExporter,
    private val isRecording: suspend () -> Boolean,
) {
    suspend fun run(): DatabaseShareOutcome = exporter.share(isRecording)
}
