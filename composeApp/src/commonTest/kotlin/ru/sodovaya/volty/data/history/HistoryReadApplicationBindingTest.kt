package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.di.appModule
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class HistoryReadApplicationBindingTest {
    @Test fun applicationReaderSharesDatabaseReadsBothFormatsAndDoesNotRecoverOpenRecording() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            VoltyDatabase.Schema.create(driver)
            val provider = VoltyDatabaseProvider(driver)
            val database = provider.database
            val writer = SqlDelightHistoryStore(provider)
            val epoch = Instant.parse("2026-10-13T00:00:00Z").toEpochMilliseconds()
            val origin = HistoryTick(epoch, 500, 7)
            val source = HistorySource("physical-pack", HistorySourceKind.BATTERY, "Battery")
            val key = HistorySeriesKey(source.id, "power_w")
            writer.begin(HistoryRide("current", "bike", origin, null, HistoryRideStatus.RECORDING), listOf(source))
            val sample = HistorySample(origin, key, -100.0)
            writer.append(HistoryBatch("current", listOf(sample), emptyList(), emptyList()))
            database.rideRowQueries.insert("legacy", "old-bike", Instant.fromEpochMilliseconds(epoch).toString(), null)
            database.ridePointRowQueries.insert("legacy", "POWER", Instant.fromEpochMilliseconds(epoch).toString(), -1, 42.0, 1)
            val app = koinApplication { modules(appModule, module { single { provider } }) }
            try {
                // Only the database is supplied. Reading history must not require
                // Android/BLE/location services or instantiate a recording runtime.
                val reader = app.koin.get<HistoryReadRepository>()
                assertSame(reader, app.koin.get<SqlDelightHistoryReadRepository>())
                val range = HistoryRange(epoch, epoch + 1000)
                assertEquals(setOf("legacy", "current"), reader.rides().items.map { it.id }.toSet())
                assertEquals(listOf(sample), reader.series("current", key, range).samples)
                val old = reader.series("legacy", HistorySeriesKey("legacy-battery", "power_w"), range)
                assertEquals(listOf<Double?>(-42.0), old.samples.map { it.value })
                assertNull(old.origin)
                assertEquals(HistoryRideStatus.LEGACY, reader.ride("legacy")?.status)
                assertEquals(HistoryRideStatus.RECORDING, reader.ride("current")?.status)
                assertEquals(HistoryRideStatus.RECORDING, writer.ride("current")?.status)
                assertNull(writer.ride("current")?.ended)
                assertEquals(1, database.rideHistoryV2Queries.openRides().executeAsList().size)
                assertEquals(1, database.ridePointRowQueries.selectByRide("legacy").executeAsList().size)
            } finally { app.close() }
        } finally { driver.close() }
    }
}
