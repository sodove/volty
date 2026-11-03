package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking

/**
 * Hands out the driver every repository holds: the opener's driver behind a [GatedSqlDriver] that
 * waits for [DatabaseOpenGate] before the first statement. Nothing here opens the database.
 */
actual class SqlDriverFactory(
    private val opener: AndroidDatabaseOpener,
    private val gate: DatabaseOpenGate,
) {
    actual fun create(): SqlDriver = GatedSqlDriver(opener.rawDriver) {
        if (!gate.isReady) runBlocking { gate.awaitReady() }
    }
}
