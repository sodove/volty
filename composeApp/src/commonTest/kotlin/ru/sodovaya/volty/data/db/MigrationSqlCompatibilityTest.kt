package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Compact-history Task 4. minSdk 26 ships SQLite 3.19, so the schema files must stay inside what 3.19 runs (see the
 * plan's Global Constraints). `27.sqm` is the 27th migration: it takes `27.db` to `28.db` and only ADDS tables.
 */
class MigrationSqlCompatibilityTest {
    private val v3Tables = listOf("HistoryRideKeyV3", "HistorySeriesV3", "HistorySampleV3")

    private val forbidden = listOf(
        "window function (SQLite 3.25)" to Regex("""\bOVER\s*\(""", RegexOption.IGNORE_CASE),
        "UPSERT (SQLite 3.24)" to Regex("""\bON\s+CONFLICT\b[^;]*\bDO\b""", RegexOption.IGNORE_CASE),
        "VACUUM INTO (SQLite 3.27)" to Regex("""\bVACUUM\s+INTO\b""", RegexOption.IGNORE_CASE),
        "DROP COLUMN (SQLite 3.35)" to Regex("""\bDROP\s+COLUMN\b""", RegexOption.IGNORE_CASE),
        "RENAME COLUMN (SQLite 3.25)" to Regex("""\bRENAME\s+COLUMN\b""", RegexOption.IGNORE_CASE),
    )

    private fun sqlDelightDir(): File =
        listOf(File("src/commonMain/sqldelight"), File("composeApp/src/commonMain/sqldelight")).first { it.isDirectory }

    private fun snapshot(version: Int): File = File(sqlDelightDir(), "databases/$version.db").also { assertTrue(it.isFile) }

    private fun long(driver: JdbcSqliteDriver, sql: String): Long = driver.executeQuery(null, sql, { c ->
        check(c.next().value); QueryResult.Value(c.getLong(0)!!)
    }, 0).value

    private fun string(driver: JdbcSqliteDriver, sql: String): String? = driver.executeQuery(null, sql, { c ->
        check(c.next().value); QueryResult.Value(c.getString(0))
    }, 0).value

    private fun tableExists(driver: JdbcSqliteDriver, table: String) =
        long(driver, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'") == 1L

    @Test fun migration27KeepsEveryV2RowAndAddsEmptyV3() {
        val copy = Files.createTempFile("history-v27-compact-", ".db").toFile()
        try {
            snapshot(27).copyTo(copy, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try {
                // 27.db has no V3 table: the migration is what creates them.
                v3Tables.forEach { assertFalse(tableExists(driver, it), it) }
                driver.execute(null, "INSERT INTO HistoryRideV2(id, vehicleId, startedEpochMillis, startedElapsedMillis, " +
                    "clockGeneration, status) VALUES ('ride', 'bike', 1000, 10, 1, 'RECORDING')", 0)
                repeat(3) { i ->
                    driver.execute(null, "INSERT INTO HistorySampleV2(rideId, sequence, sourceId, metricId, cellIndex, " +
                        "epochMillis, elapsedMillis, clockGeneration, reading, unknownReason) VALUES " +
                        "('ride', $i, 's', 'm', -1, ${1000 + i}, ${10 + i}, 1, $i.5, NULL)", 0)
                }
                val digest = "SELECT group_concat(sequence || ':' || elapsedMillis || ':' || reading) FROM HistorySampleV2"
                val before = string(driver, digest)
                assertEquals(3L, long(driver, "SELECT COUNT(*) FROM HistorySampleV2"))

                VoltyDatabase.Schema.migrate(driver, 27, 28)

                assertEquals(3L, long(driver, "SELECT COUNT(*) FROM HistorySampleV2"))
                assertEquals(1L, long(driver, "SELECT COUNT(*) FROM HistoryRideV2"))
                assertEquals(before, string(driver, digest))
                v3Tables.forEach {
                    assertTrue(tableExists(driver, it), it)
                    assertEquals(0L, long(driver, "SELECT COUNT(*) FROM $it"), it)
                }
            } finally { driver.close() }
        } finally { copy.delete() }
    }

    /** A STATIC, line-based scan: it cannot run SQL on 3.19 (the JDBC driver is newer) and misses constructs split over lines. */
    @Test fun noSqlNewerThan3_19() {
        val files = sqlDelightDir().walkTopDown().filter { it.isFile && (it.extension == "sq" || it.extension == "sqm") }.toList()
        assertTrue(files.size > 30, "the scan found only ${files.size} files")
        val hits = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, raw ->
                val code = raw.substringBefore("--") // comments may name the constructs they warn about
                forbidden.firstOrNull { it.second.containsMatchIn(code) }?.let { "${file.name}:${i + 1}: ${it.first}: $raw" }
            }
        }
        assertEquals(emptyList(), hits)
    }

    @Test fun scannerSeesEachForbiddenConstruct() {
        // Control: a scan whose patterns matched nothing would pass the test above just the same.
        val samples = listOf("SELECT row_number() OVER (ORDER BY a)", "INSERT INTO t VALUES (1) ON CONFLICT(a) DO UPDATE SET b = 1",
            "VACUUM INTO 'x'", "ALTER TABLE t DROP COLUMN a", "ALTER TABLE t RENAME COLUMN a TO b")
        assertEquals(forbidden.size, samples.size)
        forbidden.zip(samples).forEach { (rule, sample) -> assertTrue(rule.second.containsMatchIn(sample), rule.first) }
    }

    @Test fun checkRejectsIncoherentRows() {
        val copy = Files.createTempFile("history-v28-checks-", ".db").toFile()
        try {
            snapshot(27).copyTo(copy, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try {
                VoltyDatabase.Schema.migrate(driver, 27, 28)
                val head = "INSERT INTO HistorySampleV3(seriesId, elapsedMillis, sequence, reading, unknownCode, " +
                    "runEndElapsed, runEndSequence, runEndEpochSkew, runCount) VALUES "
                fun accepted(values: String): Boolean = runCatching { driver.execute(null, head + values, 0).value }.isSuccess
                // Controls: the coherent shapes go in, so each failure below is the CHECK and not a typo.
                assertTrue(accepted("(1, 0, 1, 1.0, NULL, NULL, NULL, NULL, NULL)"), "known")
                assertTrue(accepted("(1, 10, 2, NULL, 1, NULL, NULL, NULL, NULL)"), "single unknown")
                assertTrue(accepted("(1, 20, 3, NULL, 2, 2000, 5, 0, 2)"), "run")
                // Combinations no producer emits.
                assertFalse(accepted("(1, 30, 6, 1.0, 1, NULL, NULL, NULL, NULL)"), "a value and a code")
                assertFalse(accepted("(1, 31, 7, NULL, NULL, NULL, NULL, NULL, NULL)"), "neither a value nor a code")
                assertTrue(accepted("(1, 32, 8, NULL, 6, NULL, NULL, NULL, NULL)"), "a 6th reason fits: the upper bound lives in the codec")
                assertFalse(accepted("(1, 38, 17, NULL, 0, NULL, NULL, NULL, NULL)"), "code 0")
                assertFalse(accepted("(1, 39, 18, NULL, 1, 50, 19, 0, NULL)"), "end fields without a runCount")
                assertFalse(accepted("(1, 33, 9, NULL, 1, 40, 10, 0, 1)"), "runCount 1")
                assertFalse(accepted("(1, 34, 11, NULL, 1, 40, 11, 0, 2)"), "runEndSequence = sequence")
                assertFalse(accepted("(1, 35, 12, NULL, 1, 34, 13, 0, 2)"), "runEndElapsed < elapsed")
                assertFalse(accepted("(1, 36, 14, 1.0, NULL, 40, 15, 0, 2)"), "a known row as a run")
                assertFalse(accepted("(1, 37, 16, NULL, 1, NULL, 20, 0, 2)"), "partial run fields")
                assertFalse(accepted("(1, 40, 21, NULL, 1, 50, 22, NULL, 2)"), "a run without its end skew")
                assertFalse(accepted("(1, 41, 23, NULL, 1, NULL, NULL, 0, NULL)"), "an end skew without a run")
                assertFalse(accepted("(1, 0, 1, 2.0, NULL, NULL, NULL, NULL, NULL)"), "duplicate primary key")
                assertEquals(4L, long(driver, "SELECT COUNT(*) FROM HistorySampleV3"))
                // Series: negative cell index and a duplicate (ride, source, metric, cell).
                val series = "INSERT INTO HistorySeriesV3(seriesId, rideNo, sourceId, metricId, cellIndex) VALUES "
                fun seriesAccepted(values: String) = runCatching { driver.execute(null, series + values, 0).value }.isSuccess
                assertTrue(seriesAccepted("(1, 1, 's', 'm', -1)"), "series")
                assertFalse(seriesAccepted("(2, 1, 's', 'm', -1)"), "duplicate series key")
                assertFalse(seriesAccepted("(3, 1, 's', 'm', -2)"), "cellIndex -2")
            } finally { driver.close() }
        } finally { copy.delete() }
    }
}
