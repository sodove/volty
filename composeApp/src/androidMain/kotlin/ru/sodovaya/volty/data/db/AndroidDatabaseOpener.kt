package ru.sodovaya.volty.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
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
class AndroidDatabaseOpener(private val context: Context) : DatabaseOpener {
    private val file: File get() = context.getDatabasePath(DB_NAME)

    /** Constructing the driver does not touch the file: `AndroidSqliteDriver` opens on its first statement. */
    val rawDriver: SqlDriver by lazy {
        AndroidSqliteDriver(VoltyDatabase.Schema, context, DB_NAME, callback = KeepDatabaseCallback())
    }

    override fun targetVersion(): Int = VoltyDatabase.Schema.version.toInt()

    /**
     * `PRAGMA user_version` through a separate read-only connection, closed before the driver
     * opens the file for writing: asking the driver would open (and migrate) it on this thread.
     *
     * The three-argument `openDatabase` installs `DefaultDatabaseErrorHandler`, which DELETES the file (and its -wal, -shm and
     * -journal) when SQLite reports corruption, on every cold start. [KEEP_FILES] replaces it with one that only logs.
     * DEVICE-ONLY: this is platform code and no JVM test reaches it; the corrupted-copy check on the AVD is still to be done.
     */
    override fun storedVersion(): Int {
        if (!file.exists()) return 0
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, KEEP_FILES).use { it.version }
    }

    override fun databaseBytes(): Long = file.length() + File(file.path + "-wal").length()

    override fun freeBytes(): Long {
        // The databases directory exists once the file does; before that its parent is on the same volume.
        val dir = generateSequence(file.parentFile) { it.parentFile }.firstOrNull { it.exists() } ?: file
        return StatFs(dir.path).availableBytes
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
        override fun onCorruption(db: SupportSQLiteDatabase) {
            Log.e("VoltyDb", "SQLite reported corruption; the database file is left in place")
        }
    }

    private companion object {
        const val DB_NAME = "volty.db"
        const val DISK_IO_IS_FULL_BELOW_BYTES = 32L * 1024 * 1024
        val KEEP_FILES = DatabaseErrorHandler { Log.e("VoltyDb", "SQLite reported corruption in the version probe; the files are left in place") }
    }
}
