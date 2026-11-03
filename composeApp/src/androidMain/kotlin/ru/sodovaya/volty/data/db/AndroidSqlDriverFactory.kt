package ru.sodovaya.volty.data.db

import android.os.Looper
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import ru.sodovaya.volty.BuildConfig

/**
 * Hands out the driver every repository holds: the opener driver behind a [GatedSqlDriver] that
 * waits for [DatabaseOpenGate] before the first statement. Nothing here opens the database.
 *
 * In debug builds a statement reached on the main looper before Ready throws instead of freezing the
 * UI for the length of a migration (a release build would stall; see [GatedSqlDriver]).
 */
actual class SqlDriverFactory(
    private val opener: AndroidDatabaseOpener,
    private val gate: DatabaseOpenGate,
) {
    actual fun create(): SqlDriver = GatedSqlDriver(opener.rawDriver) {
        if (!gate.isReady) {
            check(!BuildConfig.DEBUG || Looper.myLooper() != Looper.getMainLooper()) {
                "Database queried on the main thread before it is open"
            }
            runBlocking { gate.awaitReady() }
        }
    }
}
