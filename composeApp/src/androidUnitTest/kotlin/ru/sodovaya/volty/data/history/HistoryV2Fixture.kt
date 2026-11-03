package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.HistoryBatch
import ru.sodovaya.volty.domain.history.HistoryStore
import java.io.File

/**
 * A database as the last V2 build left it, for tests of `28.sqm`: the committed `28.db` snapshot holding V2 rides. Nothing
 * in production writes `HistorySampleV2` any more, so [V2RecordingStore] records through the real store (rides, sources,
 * GPS, markers, buckets, receipts are the same tables in both layouts) and copies each batch's samples into V2 with the
 * sequences they got; [dropCompactRows] then removes the compact twin, leaving what v28 would have stored.
 */
internal object HistoryV2Fixture {
    private fun sqlDelightDir(): File =
        listOf(File("src/commonMain/sqldelight"), File("composeApp/src/commonMain/sqldelight")).first { it.isDirectory }

    /** Copies the snapshot of [version] to [file], stamped with that version: snapshots carry `user_version` 0. */
    fun createAt(version: Int, file: File): JdbcSqliteDriver {
        File(sqlDelightDir(), "databases/$version.db").copyTo(file, overwrite = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        driver.execute(null, "PRAGMA user_version = $version", 0)
        return driver
    }

    fun sqlm(): File = File(sqlDelightDir(), "ru/sodovaya/volty/data/db/28.sqm")

    /** `28.sqm` as the statements the migration runs, comments stripped. */
    fun conversionStatements(): List<String> = sqlm().readLines().filterNot { it.trimStart().startsWith("--") }
        .joinToString("\n").split(';').map { it.trim() }.filter { it.isNotEmpty() }

    fun insertSample(driver: JdbcSqliteDriver, rideId: String, sequence: Long, sourceId: String, metricId: String, cellIndex: Long,
        epochMillis: Long, elapsedMillis: Long, clockGeneration: Long, reading: Double?, unknownReason: String?) {
        driver.execute(null, "INSERT INTO HistorySampleV2(rideId, sequence, sourceId, metricId, cellIndex, epochMillis, " +
            "elapsedMillis, clockGeneration, reading, unknownReason) VALUES (?,?,?,?,?,?,?,?,?,?)", 10) {
            bindString(0, rideId); bindLong(1, sequence); bindString(2, sourceId); bindString(3, metricId); bindLong(4, cellIndex)
            bindLong(5, epochMillis); bindLong(6, elapsedMillis); bindLong(7, clockGeneration); bindDouble(8, reading); bindString(9, unknownReason)
        }
    }

    fun dropCompactRows(driver: JdbcSqliteDriver) {
        listOf("HistorySampleV3", "HistorySeriesV3", "HistoryRideKeyV3").forEach { driver.execute(null, "DELETE FROM $it", 0) }
    }
}

/** See [HistoryV2Fixture]. The sequence of a sample is the ride's `nextSequence` plus its place in the batch: samples go first. */
internal class V2RecordingStore(provider: VoltyDatabaseProvider, private val driver: JdbcSqliteDriver,
    private val delegate: SqlDelightHistoryStore = SqlDelightHistoryStore(provider)) : HistoryStore by delegate {
    private val rides = provider.database.rideHistoryV2Queries

    override suspend fun append(batch: HistoryBatch) {
        val first = rides.getRide(batch.rideId).executeAsOne().nextSequence
        delegate.append(batch)
        batch.samples.forEachIndexed { index, it ->
            HistoryV2Fixture.insertSample(driver, batch.rideId, first + index, it.key.sourceId, it.key.metricId,
                it.key.cellIndex?.toLong() ?: -1L, it.tick.epochMillis, it.tick.elapsedMillis, it.tick.clockGeneration, it.value, it.unknown?.name)
        }
    }
}
