package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.data.db.VoltyDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class HistoryMigrationTest {
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
