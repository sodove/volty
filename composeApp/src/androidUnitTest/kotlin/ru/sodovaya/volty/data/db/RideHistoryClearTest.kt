package ru.sodovaya.volty.data.db

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The NoSpace screen's «Удалить историю поездок…» on real SQLite (sqlite-jdbc), against the committed schema snapshots:
 * [rideHistoryClearStatements] in one transaction, as `AndroidDatabaseOpener.clearRideHistory` runs them (that wrapper,
 * the Android `SQLiteDatabase` calls and the VACUUM after it are device-only).
 *
 * Every table holds a row before the clear, history and setup alike, so a statement that empties the wrong table, or
 * misses one, shows up; the setup tables are compared row for row.
 */
class RideHistoryClearTest {
    private val root: File = Files.createTempDirectory("volty-history-clear-").toFile()
    private val open = ArrayList<Connection>()
    private var lastFile: File? = null
    @AfterTest fun cleanUp() { open.forEach { runCatching { it.close() } }; root.deleteRecursively() }

    /** Everything a rider set up rather than rode. A new table must be put here or in [RIDE_HISTORY_TABLES]. */
    private val kept = setOf("VehicleRow", "PackRow", "ControllerRow", "AlertLevelRow", "GaugePeakRow", "VehicleEnergyRow",
        "VehiclePwmRow", "DeviceTypeMemoryRow", "ChannelSharingRevokeRow", "HistoryPreviewPurge")

    private fun sqlDelightDir(): File =
        listOf(File("src/commonMain/sqldelight"), File("composeApp/src/commonMain/sqldelight")).first { it.isDirectory }

    private fun snapshot(version: Int): Connection {
        val file = File(root, "v$version-${System.nanoTime()}.db")
        File(sqlDelightDir(), "databases/$version.db").copyTo(file)
        lastFile = file
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").also { open += it }
    }

    private fun Connection.tables(): Set<String> = createStatement().use { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'")
            .use { r -> buildSet { while (r.next()) add(r.getString(1)) } }
    }
    private fun Connection.long(sql: String): Long = createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getLong(1) } }
    private fun Connection.rows(table: String): List<List<Any?>> = createStatement().use { s ->
        s.executeQuery("SELECT * FROM $table ORDER BY 1").use { r ->
            buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getObject(it) }) }
        }
    }

    /** One row in [table]: every NOT NULL column without a default gets a value of its type, overridden where a CHECK needs it. */
    private fun Connection.seed(table: String, overrides: Map<String, Any?> = emptyMap()) {
        val columns = createStatement().use { s -> s.executeQuery("PRAGMA table_info($table)").use { r ->
            buildList { while (r.next()) add(Triple(r.getString("name"), r.getString("type").uppercase(), r.getInt("notnull") == 1 && r.getString("dflt_value") == null)) }
        } }
        val chosen = columns.filter { (name, _, required) -> required || name in overrides }
        val values = chosen.map { (name, type, _) ->
            if (name in overrides) overrides[name] else when {
                "INT" in type -> 1L
                "REAL" in type -> 1.0
                else -> "x"
            }
        }
        prepareStatement("INSERT INTO $table(${chosen.joinToString { it.first }}) VALUES (${chosen.joinToString { "?" }})").use { st ->
            values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    private fun Connection.seedEverything() {
        val history = mapOf(
            "HistoryRideV2" to mapOf("id" to "ride", "status" to "COMPLETED"),
            "HistorySourceV2" to mapOf("rideId" to "ride", "id" to "src"),
            "HistorySampleV2" to mapOf("rideId" to "ride", "sourceId" to "src", "reading" to 1.0),
            "HistoryBucketV2" to mapOf("rideId" to "ride", "sourceId" to "src"),
            "HistoryGpsV2" to mapOf("rideId" to "ride"),
            "HistoryMarkerV2" to mapOf("rideId" to "ride"),
            "HistoryBatchReceiptV2" to mapOf("rideId" to "ride"),
            "HistoryFavorite" to mapOf("id" to "fav", "rideId" to "ride", "rangeStart" to 0L, "rangeEnd" to 2L),
            "HistoryMedia" to mapOf("favoriteId" to "fav", "state" to "IMPORTING"),
            "HistoryRideKeyV3" to mapOf("rideId" to "ride"),
            "HistorySeriesV3" to emptyMap(),
            "HistorySampleV3" to mapOf("reading" to 1.0),
            "RideRow" to mapOf("id" to "legacy"),
            "RidePointRow" to mapOf("rideId" to "legacy"),
        )
        val setup = mapOf(
            "VehicleRow" to mapOf("id" to "veh"),
            "DeviceTypeMemoryRow" to mapOf("bmsType" to "JK_BMS"),
            "HistoryPreviewPurge" to mapOf("id" to 1L, "generation" to 3L, "acknowledgedGeneration" to 3L),
        )
        val tables = tables()
        for (table in tables) seed(table, history[table] ?: setup[table] ?: mapOf("vehicleId" to "veh"))
        tables.forEach { assertEquals(1L, long("SELECT COUNT(*) FROM $it"), "seeded $it") }
    }

    private fun Connection.clear() {
        autoCommit = false
        createStatement().use { s -> rideHistoryClearStatements(tables()).forEach { s.execute(it) } }
        commit()
        autoCommit = true
    }

    @Test fun everyTableOfEveryStoredSchemaIsEitherHistoryOrKept() {
        for (version in 1..29) {
            val tables = snapshot(version).tables()
            val unclassified = tables - RIDE_HISTORY_TABLES.toSet() - kept
            assertEquals(emptySet(), unclassified, "v$version has a table the clear does not know about")
            assertEquals(emptySet(), tables.intersect(kept).intersect(RIDE_HISTORY_TABLES.toSet()), "v$version")
        }
        assertTrue(RIDE_HISTORY_TABLES.none { it in kept }, "no setup table is ever emptied")
    }

    @Test fun clearingAV27DatabaseEmptiesTheRidesKeepsTheVehiclesAndTheConversionThenRuns() {
        val db = snapshot(27)
        db.createStatement().use { it.execute("PRAGMA user_version = 27") }
        db.seedEverything()
        val tables = db.tables()
        val before = (tables intersect kept).associateWith { db.rows(it) }

        db.clear()

        for (table in tables intersect RIDE_HISTORY_TABLES.toSet()) assertEquals(0L, db.long("SELECT COUNT(*) FROM $table"), table)
        for (table in (tables intersect kept) - "HistoryPreviewPurge") assertEquals(before[table], db.rows(table), "$table kept row for row")
        assertEquals(4L, db.long("SELECT generation FROM HistoryPreviewPurge"), "previews and exports of the deleted rides are purged")
        assertEquals(3L, db.long("SELECT acknowledgedGeneration FROM HistoryPreviewPurge"))
        assertEquals(27L, db.long("PRAGMA user_version"), "the clear does not migrate")
        assertEquals("ok", db.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { it.next(); it.getString(1) } })
        db.close()

        // The gate's next attempt: the conversion over an empty history, 27 -> 29 in one transaction as onUpgrade runs it.
        val path = checkNotNull(lastFile)
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        try {
            VoltyDatabaseProvider(driver).database.transaction {
                VoltyDatabase.Schema.migrate(driver, 27, 29)
                driver.execute(null, "PRAGMA user_version = 29", 0)
            }
            fun count(sql: String) = driver.executeQuery(null, sql, { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value
            assertEquals(29L, count("PRAGMA user_version"))
            assertEquals(0L, count("SELECT COUNT(*) FROM HistorySampleV3"))
            assertEquals(0L, count("SELECT COUNT(*) FROM HistoryRideKeyV3"))
            assertEquals(1L, count("SELECT COUNT(*) FROM VehicleRow"), "the vehicle is still there after the conversion")
            assertEquals(1L, count("SELECT COUNT(*) FROM PackRow"))
        } finally { driver.close() }
    }

    @Test fun clearingAV28DatabaseAlsoEmptiesTheCompactTables() {
        val db = snapshot(28)
        db.seedEverything()
        db.clear()
        for (table in listOf("HistorySampleV3", "HistorySeriesV3", "HistoryRideKeyV3", "HistorySampleV2", "HistoryRideV2"))
            assertEquals(0L, db.long("SELECT COUNT(*) FROM $table"), table)
        assertEquals(1L, db.long("SELECT COUNT(*) FROM VehicleRow"))
    }

    @Test fun anOldSchemaWithoutThePurgeJournalIsClearedWithoutIt() {
        // The 14.db snapshot predates HistoryPreviewPurge and the V2 tables: only the legacy rides exist.
        val db = snapshot(14)
        assertTrue("HistoryPreviewPurge" !in db.tables())
        db.seedEverything()
        db.clear()
        assertEquals(0L, db.long("SELECT COUNT(*) FROM RideRow"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM VehicleRow"))
    }
}
