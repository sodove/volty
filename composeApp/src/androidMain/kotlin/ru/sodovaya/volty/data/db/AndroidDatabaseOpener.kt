package ru.sodovaya.volty.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import ru.sodovaya.volty.BuildConfig
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.os.StatFs
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * Opens `volty.db` for [DatabaseOpenGate]. [rawDriver] is built here and not opened until the
 * gate calls [open]; the repositories only ever see it wrapped in a [GatedSqlDriver].
 */
class AndroidDatabaseOpener(private val context: Context) : DatabaseOpener, DatabaseRecovery {
    private val file: File get() = context.getDatabasePath(DB_NAME)

    /** Constructing the driver does not touch the file: `AndroidSqliteDriver` opens on its first statement. */
    val rawDriver: SqlDriver by lazy {
        AndroidSqliteDriver(VoltyDatabase.Schema, context, DB_NAME, callback = KeepDatabaseCallback())
    }

    override fun targetVersion(): Int = VoltyDatabase.Schema.version.toInt()

    override fun defersHistoryConversion(storedVersion: Int, targetVersion: Int): Boolean =
        storedVersion in 1 until CONVERSION_SCHEMA_VERSION && targetVersion == CONVERSION_SCHEMA_VERSION

    /**
     * `PRAGMA user_version` through a separate connection, closed before the driver opens the file: asking the driver would
     * open (and migrate) it on this thread. This connection runs no `onUpgrade` and, with [VERSION_PROBE_FLAGS], writes
     * nothing of its own (no `android_metadata` locale row).
     *
     * READ-WRITE ON PURPOSE. A process killed inside 28.sqm's one transaction leaves a HOT `volty.db-journal` once SQLite
     * has spilled a page into `volty.db` (seconds into a 600 MB conversion). Only a connection that may write can roll that
     * journal back; a read-only one fails every read with SQLITE_READONLY_ROLLBACK (776). Retraction: this probe used to
     * open with OPEN_READONLY, so after such a kill every start, and every Retry, ended on the error screen with the rides
     * intact on disk and nothing in the app able to reach them (final review C1). Opened read-write, the first read rolls
     * the journal back, the version read is the one before the kill, and [databaseBytes]/[freeBytes], which the gate asks
     * only after this, see the file as it was and the journal's space returned.
     *
     * The three-argument `openDatabase` installs `DefaultDatabaseErrorHandler`, which DELETES the file (and its -wal, -shm and
     * -journal) when SQLite reports corruption, on every cold start. [KEEP_FILES] replaces it with one that only logs; a
     * read-write open of a corrupt file is as safe as the read-only one was (AVD corrupted-copy check, 2026-11-03: Failed,
     * file kept).
     *
     * DEVICE-ONLY: no JVM test reaches `android.database`. The SQLite behaviour this relies on is reproduced with a real
     * leftover journal on sqlite-jdbc in `HotJournalRecoveryTest`; the flag itself is pinned by `AndroidDatabaseOpenerFlagsTest`;
     * the whole path was checked on the AVD by killing the app mid-conversion (final-fix-report.md).
     */
    override fun storedVersion(): Int {
        if (!file.exists()) return 0
        return SQLiteDatabase.openDatabase(file.path, null, VERSION_PROBE_FLAGS, KEEP_FILES).use { it.version }
    }

    /**
     * The database and its WAL. A rollback journal is not counted: the gate measures after [storedVersion], whose open has
     * already rolled a hot journal back (and a cold one holds nothing the conversion needs).
     */
    override fun databaseBytes(): Long = file.length() + File(file.path + "-wal").length()

    override fun freeBytes(): Long {
        // DEBUG BUILDS ONLY (BuildConfig.DEBUG is a compile-time false in release, so the branch is not compiled in):
        // `files/debug_gate_free_bytes` holding a number replaces the measured free space, so the NoSpace screen and its
        // clear can be exercised on an emulator with room to spare. Written with run-as; absent on every real install.
        if (BuildConfig.DEBUG) {
            File(context.filesDir, "debug_gate_free_bytes").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { return it }
        }
        // The databases directory exists once the file does; before that its parent is on the same volume.
        val dir = generateSequence(file.parentFile) { it.parentFile }.firstOrNull { it.exists() } ?: file
        return StatFs(dir.path).availableBytes
    }

    /**
     * Gate action CLEAR_RIDE_HISTORY (NoSpace only, so nothing has the file open). Same open as the version probe:
     * read-write, the no-op error handler, no foreign-key enforcement (Android's default), so each bare DELETE takes the
     * truncate optimisation. The journal is NOT switched off: a kill mid-clear must roll back, never take the vehicles
     * with it (checked on the AVD: killed mid-clear with a 359 MB journal, the next start rolled back to the intact v27
     * file and showed NoSpace again). Device-only wrapper; the statements are tested on sqlite-jdbc in `RideHistoryClearTest`.
     *
     * Retraction: the first revision relied on the desktop measurement alone (38.6 MB journal). On the AVD the journal
     * passed 359 MB, because Android builds SQLite with SECURE_DELETE: every freed page is zeroed, so it is written, so
     * it is journaled. secure_delete is turned off for this one connection: the rows are the rider's own, deleted at
     * their request, and the file is truncated (auto_vacuum FULL) or VACUUMed right after.
     */
    override fun clearRideHistory() {
        SQLiteDatabase.openDatabase(file.path, null, VERSION_PROBE_FLAGS, KEEP_FILES).use { db ->
            val secureDelete = db.rawQuery("PRAGMA secure_delete = 0", null).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            db.beginTransaction()
            try {
                rideHistoryClearStatements(tables).forEach(db::execSQL)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val freePages = DatabaseUtils.longForQuery(db, "PRAGMA freelist_count", null)
            Log.i("VoltyDb", "ride history cleared: secureDelete=$secureDelete tables=${tables.count { it in RIDE_HISTORY_TABLES }} freePages=$freePages file=${file.length()}")
            if (freePages > 0) {
                try { db.execSQL("VACUUM"); Log.i("VoltyDb", "vacuumed after the clear: file=${file.length()}") }
                catch (failure: Exception) { Log.w("VoltyDb", "VACUUM after the clear failed; the free-space rule sees the old size", failure) }
            }
        }
    }

    /** Gate action START_WITH_EMPTY_DATABASE (Failed only): the file and its -journal, -wal, -shm (and -mj*) go. */
    override fun deleteDatabase() {
        if (!file.exists()) return
        check(SQLiteDatabase.deleteDatabase(file)) { "the database file could not be deleted" }
        Log.i("VoltyDb", "database deleted by the rider; the next open creates an empty one")
    }

    override fun open() {
        try {
            rawDriver.executeQuery(null, "SELECT 1", { cursor -> QueryResult.Value(cursor.next().value) }, 0).value
        } catch (failure: Exception) {
            val chain = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
            // ENOSPC can surface as SQLITE_IOERR_WRITE on some file systems; with the volume nearly empty that is "full" too.
            if (chain.any { it is SQLiteFullException } || (chain.any { it is SQLiteDiskIOException } && freeBytes() < DISK_IO_IS_FULL_BELOW_BYTES)) {
                throw DatabaseFullException(failure)
            }
            throw failure
        }
    }

    /**
     * The stock callback deletes the database file when SQLite reports corruption. Retraction: that
     * was the behaviour until this task, and it is wrong for a history that holds the rider's only
     * copy of every ride; the open fails instead, the gate shows the error screen and nothing is deleted.
     *
     * The trade: corruption that the open does not touch (anything past the header and first page) now surfaces later, as a
     * SQLiteDatabaseCorruptException at whatever query reads the damaged page, with the gate already Ready. Routing that to
     * the Failed screen (a gate.reportCorruption()) was left out on purpose: it would swap a running app for the error screen
     * mid-ride and leave a live connection open beside shareUnopened(). Not deleting is still the right call. DEVICE-ONLY.
     */
    private class KeepDatabaseCallback : AndroidSqliteDriver.Callback(VoltyDatabase.Schema) {
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 27.sqm prepares the compact tables. Skip the eager conversion in 28.sqm;
            // retain the legacy sample table as staging until the background job drains it.
            if (oldVersion in 1 until CONVERSION_SCHEMA_VERSION && newVersion == CONVERSION_SCHEMA_VERSION) {
                val preparationVersion = CONVERSION_SCHEMA_VERSION - 1
                if (oldVersion < preparationVersion) super.onUpgrade(db, oldVersion, preparationVersion)
            } else {
                super.onUpgrade(db, oldVersion, newVersion)
            }
        }

        override fun onCorruption(db: SupportSQLiteDatabase) {
            Log.e("VoltyDb", "SQLite reported corruption; the database file is left in place")
        }
    }

    internal companion object {
        /**
         * The version probe's flags: READ-WRITE so a hot journal is rolled back (see [storedVersion]), no CREATE (an absent
         * file is answered before the open), and no localized collators so the probe does not write `android_metadata`.
         */
        internal const val VERSION_PROBE_FLAGS: Int = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        private const val DB_NAME = "volty.db"
        private const val DISK_IO_IS_FULL_BELOW_BYTES = 32L * 1024 * 1024
        private val KEEP_FILES = DatabaseErrorHandler { Log.e("VoltyDb", "SQLite reported corruption in the version probe; the files are left in place") }
    }
}
