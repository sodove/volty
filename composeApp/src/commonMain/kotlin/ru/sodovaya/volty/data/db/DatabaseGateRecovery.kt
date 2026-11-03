package ru.sodovaya.volty.data.db

/**
 * What the rider can do from the gate's screen beyond Retry, each behind a confirmation (owner request, 2026-11-03:
 * «добавь ещё возможность очистить бд с поездками на этом экране»).
 */
enum class DatabaseGateAction {
    /**
     * NoSpace: the database is healthy, only too big to convert in place. Empty the ride history (and nothing else), then
     * open again: a conversion of an empty history is instant and the free-space rule sees the small file.
     */
    CLEAR_RIDE_HISTORY,

    /**
     * Failed for any other reason (corrupt, unknown): the file cannot be trusted, so the only clean way out is to delete
     * `volty.db` and its `-journal`, `-wal` and `-shm` and open a new, empty database. Vehicles and settings stored in the
     * database are lost too.
     */
    START_WITH_EMPTY_DATABASE,
}

/** The destructive actions [state] offers. Nothing while the database is open, opening or converting. */
fun databaseGateActions(state: DatabaseOpenState): Set<DatabaseGateAction> = when (state) {
    is DatabaseOpenState.Failed -> when (state.failure) {
        is DatabaseOpenFailure.NotEnoughSpace -> setOf(DatabaseGateAction.CLEAR_RIDE_HISTORY)
        is DatabaseOpenFailure.Other -> setOf(DatabaseGateAction.START_WITH_EMPTY_DATABASE)
    }
    else -> emptySet()
}

/**
 * Every table that holds ride history in ANY stored schema the gate can meet (1 to 28), children before parents so the
 * order is right even with foreign keys on. The legacy `RideRow`/`RidePointRow` (pre-V2 recording) are history too.
 *
 * Deliberately NOT here, because they are the rider's setup, not rides: VehicleRow, PackRow, ControllerRow,
 * AlertLevelRow, GaugePeakRow, VehicleEnergyRow, VehiclePwmRow, DeviceTypeMemoryRow, ChannelSharingRevokeRow, and
 * HistoryPreviewPurge, which is a journal the clear bumps instead (so the preview and export files of the deleted rides
 * are removed on the next start). Media files of deleted favourites are removed by the media reconcile, which deletes
 * owned files no `HistoryMedia` row retains.
 */
val RIDE_HISTORY_TABLES: List<String> = listOf(
    "HistoryMedia", "HistoryFavorite",
    "HistorySampleV3", "HistorySeriesV3", "HistoryRideKeyV3",
    "HistorySampleV2", "HistoryGpsV2", "HistoryMarkerV2", "HistoryBucketV2", "HistoryBatchReceiptV2", "HistorySourceV2",
    "HistoryRideV2",
    "RidePointRow", "RideRow",
)

/**
 * The statements that empty the ride history of a database whose tables are [existingTables], to run in ONE
 * transaction on a connection without foreign-key enforcement. A bare `DELETE FROM t` (no WHERE) takes SQLite's truncate
 * optimisation: pages are freed, not rewritten row by row, so the rollback journal stays small. Measured on a copy of the
 * rider's 600 MB v27 database (desktop SQLite, DELETE journal, auto_vacuum FULL): 1.0 s, journal peak 38.6 MB, file
 * 600,178,688 to 249,856 bytes at the commit, 4 vehicles kept, integrity ok. So the journal is kept on (crash-safe:
 * a kill mid-clear rolls back like any transaction) instead of journal_mode OFF or MEMORY, which would risk the
 * vehicles for a saving of ~40 MB.
 */
fun rideHistoryClearStatements(existingTables: Set<String>): List<String> =
    RIDE_HISTORY_TABLES.filter { it in existingTables }.map { "DELETE FROM $it" } +
        if ("HistoryPreviewPurge" in existingTables) listOf(
            "INSERT OR IGNORE INTO HistoryPreviewPurge(id, generation, acknowledgedGeneration) VALUES (1, 0, 0)",
            "UPDATE HistoryPreviewPurge SET generation = generation + 1 WHERE id = 1 AND generation < 9223372036854775807",
        ) else emptyList()

/**
 * The platform side of [DatabaseGateAction]s. Kept apart from [DatabaseOpener], which has no delete on purpose: these
 * run only from an explicit, confirmed rider action, through [DatabaseOpenGate.recover], and only in the state that
 * offers them. Neither may be called while anything has the database open.
 */
interface DatabaseRecovery {
    /**
     * Empties [RIDE_HISTORY_TABLES] in one transaction ([rideHistoryClearStatements]); everything else in the file is kept.
     * Then, if the file still holds free pages (auto_vacuum NONE on an old install never shrinks it), VACUUMs it, best
     * effort: with the history gone the live data is a few hundred KB, so the VACUUM is cheap, and the gate's free-space
     * rule, which reads the FILE size, then sees what the conversion will actually rewrite. With auto_vacuum FULL (the
     * rider's database) the commit itself truncates the file and there is nothing to VACUUM.
     */
    fun clearRideHistory()
    /** Deletes the database file and its -journal, -wal and -shm. */
    fun deleteDatabase()
}
