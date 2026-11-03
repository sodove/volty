package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.history.HistoryV2Fixture
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Final review C1 on real SQLite (sqlite-jdbc): the files a process killed inside the 27 -> 29 conversion leaves behind,
 * opened the way the app opens them.
 *
 * The crash state is a FILE-LEVEL SNAPSHOT, not an in-process ROLLBACK (which is what `killMidMigrationLeavesV2Intact`
 * models, and why that test could not see C1): a v27 database in DELETE journal mode with auto_vacuum FULL, as the rider's
 * is, runs the whole conversion inside one transaction, as `onUpgrade` does, with a cache small enough that SQLite spills
 * pages into `volty.db` before the commit; `volty.db` and `volty.db-journal` are copied out at that moment. The copy is
 * then exactly what the next process finds: a torn main file and a hot journal.
 *
 * DEVICE-ONLY PART, stated plainly: the app's probe is `android.database.sqlite.SQLiteDatabase.openDatabase` with
 * [AndroidDatabaseOpener.VERSION_PROBE_FLAGS], which no JVM test can run. Here the probe is a read-write JDBC connection, the
 * same SQLite operation; the Android call itself was checked on the AVD by killing the app mid-conversion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HotJournalRecoveryTest {
    private val root: File = Files.createTempDirectory("volty-hot-journal-").toFile()
    private val drivers = ArrayList<JdbcSqliteDriver>()
    @AfterTest fun cleanUp() { drivers.forEach { runCatching { it.close() } }; root.deleteRecursively() }

    private fun long(driver: JdbcSqliteDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> check(c.next().value); QueryResult.Value(checkNotNull(c.getLong(0))) }, 0).value
    private fun string(driver: JdbcSqliteDriver, sql: String): String =
        driver.executeQuery(null, sql, { c -> check(c.next().value); QueryResult.Value(checkNotNull(c.getString(0))) }, 0).value

    private fun connect(file: File, readOnly: Boolean): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}",
        Properties().apply { if (readOnly) setProperty("open_mode", "1") })   // 1 = SQLITE_OPEN_READONLY, as OPEN_READONLY
    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> check(r.next()); r.getInt(1) } }

    /** Two T4-sized rides of V2 rows (~51 k), enough that a small page cache must spill while 28.sqm rewrites them. */
    private fun seedV27(file: File): Long {
        val driver = HistoryV2Fixture.createAt(27, file).also { drivers += it }
        connect(file, readOnly = false).use { c ->
            c.createStatement().use { it.execute("PRAGMA auto_vacuum = 1") }
            c.createStatement().use { it.execute("VACUUM") }
        }
        assertEquals(1L, long(driver, "PRAGMA auto_vacuum"), "FULL, as the rider's database")
        assertEquals("delete", string(driver, "PRAGMA journal_mode"), "a rollback journal, as the AVD measured")
        VoltyDatabaseProvider(driver).database.transaction {
            for (n in 0 until 2) {
                val id = "ride-$n"
                val origin = 1_700_000_000_000L + n * 10_000_000L
                driver.execute(null, "INSERT INTO HistoryRideV2(id, vehicleId, startedEpochMillis, startedElapsedMillis, " +
                    "clockGeneration, status, endedEpochMillis, endedElapsedMillis, endedClockGeneration) " +
                    "VALUES ('$id', 'wheel', $origin, 0, 1, 'COMPLETED', $origin, 0, 1)", 0)
                for (source in listOf("ctl", "pack")) driver.execute(null,
                    "INSERT INTO HistorySourceV2(rideId, id, kind, label) VALUES ('$id', '$source', 'BATTERY', '$source')", 0)
                var sequence = 0L
                for (i in 0 until 1500) for (s in 0 until 17) {
                    val dead = s < 6
                    HistoryV2Fixture.insertSample(driver, id, sequence++, if (s % 2 == 0) "ctl" else "pack", "m$s", -1,
                        origin + i * 300L + i % 3, i * 300L, 1, if (dead) null else 60.0 + i * 0.01, if (dead) "UNAVAILABLE" else null)
                }
            }
        }
        val rows = long(driver, "SELECT COUNT(*) FROM HistorySampleV2")
        assertEquals(2L * 1500 * 17, rows)
        return rows
    }

    @Test fun aConversionKilledMidTransactionIsRolledBackByAReadWriteProbeAndRunsAgain() = runTest {
        val live = File(File(root, "live").also { it.mkdirs() }, "volty.db")
        val v2Rows = seedV27(live)
        val driver = drivers.single()
        val pristine = live.readBytes()

        // The kill: the whole conversion (and the version stamp onUpgrade writes) inside ONE open transaction, then a copy of
        // the files as they are on disk. Nothing below commits; the live database is rolled back in-process afterwards.
        val crash = File(File(root, "crash").also { it.mkdirs() }, "volty.db")
        val crashJournal = File(crash.path + "-journal")
        driver.execute(null, "PRAGMA cache_size = 16", 0)
        VoltyDatabaseProvider(driver).database.transaction {
            VoltyDatabase.Schema.migrate(driver, 27, 29)
            driver.execute(null, "PRAGMA user_version = 29", 0)
            live.copyTo(crash)
            File(live.path + "-journal").copyTo(crashJournal)
            rollback()
        }
        assertTrue(crashJournal.length() > 0, "a journal was left behind")
        assertFalse(crash.readBytes().contentEquals(pristine), "SQLite spilled into the main file: it is torn, the journal is hot")
        val journalBytes = crashJournal.length()

        // The old probe (OPEN_READONLY): SQLite will not roll back through a read-only connection, so every read fails, on
        // every start and every Retry, and the journal stays.
        connect(crash, readOnly = true).use { readOnly ->
            val failure = try { userVersion(readOnly); null } catch (e: SQLException) { e }
            assertNotNull(failure, "a read-only connection read a database with a hot journal")
            assertTrue("SQLITE_READONLY_ROLLBACK" in failure.message.orEmpty(), failure.message)
            println("[hot-journal] read-only probe: ${failure.message} (errorCode ${failure.errorCode})")
        }
        assertEquals(journalBytes, crashJournal.length(), "the read-only probe changed nothing")

        // The app's order, through the real gate: a read-write probe (the recovery), then the sizes, then the driver's open,
        // which runs the conversion from the start in one transaction as onUpgrade does.
        val seen = ArrayList<String>()
        val opener = object : DatabaseOpener {
            override fun storedVersion(): Int = connect(crash, readOnly = false).use { userVersion(it) }
                .also { seen += "probe=$it journal=${crashJournal.exists() && crashJournal.length() > 0}" }
            override fun targetVersion(): Int = VoltyDatabase.Schema.version.toInt()
            override fun databaseBytes(): Long = crash.length().also { seen += "bytes journal=${crashJournal.exists() && crashJournal.length() > 0}" }
            override fun freeBytes(): Long = Long.MAX_VALUE
            override fun open() {
                val app = JdbcSqliteDriver("jdbc:sqlite:${crash.absolutePath}").also { drivers += it }
                val stored = long(app, "PRAGMA user_version")
                seen += "open from $stored"
                VoltyDatabaseProvider(app).database.transaction {
                    VoltyDatabase.Schema.migrate(app, stored, 29)
                    app.execute(null, "PRAGMA user_version = 29", 0)
                }
            }
        }
        val failures = ArrayList<String>()
        val gate = DatabaseOpenGate(opener, StandardTestDispatcher(testScheduler)) { message, failure ->
            if (failure != null) failures += "$message: $failure"
        }
        gate.start(backgroundScope); runCurrent()
        assertEquals(emptyList(), failures)
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertEquals(listOf("probe=27 journal=false", "bytes journal=false", "open from 27"), seen,
            "the old version is read, the journal is gone before anything is sized, and the conversion restarts from 27")

        val after = drivers.last()
        assertEquals(29L, long(after, "PRAGMA user_version"))
        assertEquals("ok", string(after, "PRAGMA integrity_check"))
        assertEquals(0L, long(after, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'HistorySampleV2'"), "V2 converted and dropped")
        assertEquals(v2Rows, long(after, "SELECT COALESCE(SUM(COALESCE(runCount, 1)), 0) FROM HistorySampleV3"),
            "every V2 row of the rolled-back file is a stored row or a run member")
        assertEquals(2L, long(after, "SELECT COUNT(*) FROM HistoryRideKeyV3"))
        assertFalse(crashJournal.exists() && crashJournal.length() > 0, "no journal left after the commit")
        println("[hot-journal] journal=$journalBytes B at the kill; ${crash.length()} B after the recovered conversion")
    }
}
