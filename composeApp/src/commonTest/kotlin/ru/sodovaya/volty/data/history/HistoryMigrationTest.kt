package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.data.db.VoltyDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class HistoryMigrationTest {
    @Test fun realVersion19RecordingSurvivesFavoriteMigration() = runTest {
        val snapshot = listOf(File("src/commonMain/sqldelight/databases/19.db"),
            File("composeApp/src/commonMain/sqldelight/databases/19.db")).firstOrNull { it.isFile }
        assertNotNull(snapshot)
        val copy = Files.createTempFile("history-v19-favorites-", ".db").toFile()
        try {
            snapshot.copyTo(copy, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try {
                val db = VoltyDatabase(driver)
                db.rideHistoryV2Queries.insertRide("ride", "bike", 10000, 100, 1, "RECORDING")
                db.rideHistoryV2Queries.finishRide(11000, 1100, 1, "COMPLETED", "ride")
                db.rideRowQueries.insert("legacy", "old-bike", "2020-01-01T00:00:00Z", null)
                val before = db.rideHistoryV2Queries.getRide("ride").executeAsOne()
                VoltyDatabase.Schema.migrate(driver, 19, VoltyDatabase.Schema.version)
                assertEquals(before, db.rideHistoryV2Queries.getRide("ride").executeAsOne())
                assertNull(db.rideRowQueries.selectById("legacy").executeAsOne().endedAt)
                val provider = VoltyDatabaseProvider(driver)
                val favorites = SqlDelightHistoryFavorites(provider) { 99999 }
                val ride = assertNotNull(SqlDelightHistoryStore(provider).ride("ride"))
                val saved = favorites.save("f", ride.favoriteSource(), HistoryRange(10000, 11000), "Migrated")
                assertEquals(saved, SqlDelightHistoryFavorites(provider) { 0 }.get("f"))
                assertEquals(before, db.rideHistoryV2Queries.getRide("ride").executeAsOne())
            } finally { driver.close() }
        } finally { copy.delete() }
    }

    @Test fun realVersion18GlobalMarkerReceiptSurvivesUpgradeAndNewMarkersKeepScope() = runTest {
        val snapshot = listOf(File("src/commonMain/sqldelight/databases/18.db"),
            File("composeApp/src/commonMain/sqldelight/databases/18.db")).firstOrNull { it.isFile }
        assertNotNull(snapshot)
        val copy = Files.createTempFile("history-v18-scope-", ".db").toFile()
        try {
            snapshot.copyTo(copy, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try {
                val db = VoltyDatabase(driver)
                db.rideHistoryV2Queries.insertRide("ride", "bike", 10000, 100, 1, "RECORDING")
                db.rideHistorySourceQueries.insertSource("ride", "pack", "BATTERY", "A")
                driver.execute(null, "INSERT INTO HistoryMarkerV2(rideId,sequence,epochMillis,elapsedMillis,clockGeneration,kind) VALUES('ride',0,10000,100,1,'BLE_GAP')", 0)
                db.rideHistoryV2Queries.advanceSequence(1, 10000, 100, "ride")
                db.rideHistoryReceiptQueries.insertReceipt("ride", "old", 0, 1,
                    "5ec83716f3523449e455c25a86d3a30f6e0d6937249429eb47940a40ca57572f")
                VoltyDatabase.Schema.migrate(driver, 18, VoltyDatabase.Schema.version)
                val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
                val marker = HistoryMarker(HistoryTick(10000, 100, 1), HistoryMarkerKind.BLE_GAP)
                val range = HistoryRange(10000, 10000)
                assertEquals(listOf(marker), store.markers("ride", range))
                store.append(HistoryBatch("ride", emptyList(), emptyList(), listOf(marker), "old"))
                val scoped = marker.copy(sourceId = "pack")
                store.append(HistoryBatch("ride", emptyList(), emptyList(), listOf(scoped), "new"))
                assertEquals(listOf(marker, scoped), store.markers("ride", range))
                assertFailsWith<IllegalArgumentException> {
                    store.append(HistoryBatch("ride", emptyList(), emptyList(), listOf(scoped), "old"))
                }
            } finally { driver.close() }
        } finally { copy.delete() }
    }

    @Test fun realVersion17GpsAndV1ReceiptRemainUnknownProviderAndReplayableAfterUpgrade() = runTest {
        val snapshot = listOf(File("src/commonMain/sqldelight/databases/17.db"),
            File("composeApp/src/commonMain/sqldelight/databases/17.db")).firstOrNull { it.isFile }
        assertNotNull(snapshot, "Real committed v17 snapshot must be present")
        val copy = Files.createTempFile("history-v17-gps-", ".db").toFile()
        try {
            snapshot.copyTo(copy, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try {
                val db = VoltyDatabase(driver)
                db.rideHistoryV2Queries.insertRide("ride", "retired-vehicle", 10000, 100, 1, "RECORDING")
                driver.execute(null, "INSERT INTO HistoryGpsV2(rideId,sequence,epochMillis,elapsedMillis,clockGeneration,latitude,longitude,accuracyMeters,headingDegrees) VALUES('ride',0,10000,100,1,56.0,60.0,5.0,NULL)", 0)
                db.rideHistoryV2Queries.advanceSequence(1, 10000, 100, "ride")
                val oldFingerprint = "f4b46b3dddf997ea6135a0020de23e3e377c5df906955c7fa931bf7c394b3d7a"
                db.rideHistoryReceiptQueries.insertReceipt("ride", "receipt", 0, 1, oldFingerprint)
                VoltyDatabase.Schema.migrate(driver, 17, VoltyDatabase.Schema.version)
                val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
                val tick = HistoryTick(10000, 100, 1)
                val fix = HistoryGpsFix(tick, 56.0, 60.0, 5.0, null)
                val batch = HistoryBatch("ride", emptyList(), listOf(fix), emptyList(), "receipt")
                assertEquals(listOf(fix), store.gps("ride", HistoryRange(10000, 10000), null).items)
                assertNull(db.rideHistoryGpsQueries.pageGps("ride", 100, 100, null, 0, 2).executeAsOne().provider)
                assertEquals(oldFingerprint, db.rideHistoryReceiptQueries.getReceipt("ride", "receipt").executeAsOne().fingerprint)
                assertEquals("retired-vehicle", store.ride("ride")?.vehicleId)
                store.finish("ride", tick, HistoryRideStatus.COMPLETED)
                store.append(batch)
                assertFailsWith<IllegalArgumentException> {
                    store.append(batch.copy(gps = listOf(fix.copy(provider = HistoryGpsProvider.GPS))))
                }
                assertEquals(listOf(fix), store.gps("ride", HistoryRange(10000, 10000), null).items)
            } finally { driver.close() }
        } finally { copy.delete() }
    }


    @Test fun realVersion14SnapshotPreservesEveryLegacyField() {
        val snapshot = listOf(File("src/commonMain/sqldelight/databases/14.db"),
            File("composeApp/src/commonMain/sqldelight/databases/14.db")).firstOrNull { it.isFile }
        assertNotNull(snapshot, "Real committed v14 snapshot must be present")
        val copy = Files.createTempFile("history-v14-", ".db").toFile()
        try {
            snapshot.copyTo(copy, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try {
                val db = VoltyDatabase(driver)
                db.rideRowQueries.insert("old", "vehicle-retired", "2026-10-13T00:00:00Z", null)
                db.ridePointRowQueries.insert("old", "POWER", "2026-10-13T00:00:00.001Z", -1, -420.25, 1)
                db.ridePointRowQueries.insert("old", "CURRENT", "2026-10-13T00:00:00Z", 7, 999.5, 0)
                val beforeRides = db.rideRowQueries.selectAll().executeAsList()
                val beforePoints = db.ridePointRowQueries.selectByRide("old").executeAsList()
                assertTrue(VoltyDatabase.Schema.version >= 15)
                VoltyDatabase.Schema.migrate(driver, 14, VoltyDatabase.Schema.version)
                assertEquals(beforeRides, db.rideRowQueries.selectAll().executeAsList())
                assertEquals(beforePoints, db.ridePointRowQueries.selectByRide("old").executeAsList())
                assertTrue(db.rideHistoryV2Queries.openRides().executeAsList().isEmpty())
                assertTrue(db.rideHistoryGpsQueries.pageGps("old", 0, Long.MAX_VALUE, null, 0, 1).executeAsList().isEmpty())
            } finally { driver.close() }
        } finally { copy.delete() }
    }
}
