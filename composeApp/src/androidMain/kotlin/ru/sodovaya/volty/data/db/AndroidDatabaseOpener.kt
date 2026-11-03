package ru.sodovaya.volty.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
     */
    override fun storedVersion(): Int {
        if (!file.exists()) return 0
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
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
            if (generateSequence<Throwable>(failure) { it.cause }.take(8).any { it is SQLiteFullException }) {
                throw DatabaseFullException(failure)
            }
            throw failure
        }
    }

    /**
     * The stock callback deletes the database file when SQLite reports corruption. Retraction: that
     * was the behaviour until this task, and it is wrong for a history that holds the rider's only
     * copy of every ride; the open fails instead, the gate shows the error screen and nothing is deleted.
     */
    private class KeepDatabaseCallback : AndroidSqliteDriver.Callback(VoltyDatabase.Schema) {
        override fun onCorruption(db: SupportSQLiteDatabase) {
            Log.e("VoltyDb", "SQLite reported corruption; the database file is left in place")
        }
    }

    private companion object {
        const val DB_NAME = "volty.db"
    }
}
