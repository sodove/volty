package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.history.HistoryMaintenanceStep.Compact
import ru.sodovaya.volty.data.history.HistoryMaintenanceStep.Vacuum
import ru.sodovaya.volty.domain.history.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Task 11 of the compact-history-storage plan: background compaction of stored unknown chains, and the VACUUM decision.
 *
 * The "migrated" ride of the brief is built by hand: `28.sqm` already detects runs (the brief predates that), so a ride
 * whose every unknown is its own row is made by recording it live and then rewriting each sample as its own row with the
 * sequence the live append gave it. That is the shape the plan expected the migration to leave, and the shape compaction
 * exists to repair.
 */
class HistoryStorageMaintenanceTest {
    private val root: File = Files.createTempDirectory("volty-maintenance-test-").toFile()
    private val drivers = ArrayList<JdbcSqliteDriver>()
    @AfterTest fun close() { drivers.forEach { it.close() }; root.deleteRecursively() }

    private val origin = HistoryTick(1_700_000_000_000, 5_000, 3)
    private val sources = listOf(HistorySource("ctl", HistorySourceKind.CONTROLLER, "ctl"), HistorySource("pack", HistorySourceKind.BATTERY, "pack"))
    private val whole = HistoryRange(origin.epochMillis, Long.MAX_VALUE)

    private inner class Db(file: File? = null) {
        val driver = JdbcSqliteDriver(if (file == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:${file.absolutePath}").also { drivers += it }
        init { driver.execute(null, "PRAGMA foreign_keys=OFF", 0); VoltyDatabase.Schema.create(driver) }
        val provider = VoltyDatabaseProvider(driver)
        val store = SqlDelightHistoryStore(provider, Dispatchers.Unconfined)
        var recording = false
        var free = Long.MAX_VALUE
        val logged = ArrayList<String>()
        val maintenance = HistoryStorageMaintenance(provider, { free }, { recording }, Dispatchers.Unconfined) { message, _ -> logged += message }

        fun <T> query(sql: String, map: (SqlCursor) -> T): List<T> = driver.executeQuery(null, sql, { c ->
            val rows = ArrayList<T>()
            while (c.next().value) rows += map(c)
            QueryResult.Value(rows)
        }, 0).value
        fun long(sql: String) = query(sql) { checkNotNull(it.getLong(0)) }.single()

        /** The compact rows of a ride with the series key in place of seriesId, in reader order. */
        fun rows(rideId: String): List<List<Any?>> = query("""SELECT v.sourceId, v.metricId, v.cellIndex, s.elapsedMillis, s.sequence, s.epochSkew,
            s.clockDelta, s.reading, s.unknownCode, s.runEndElapsed, s.runEndSequence, s.runEndEpochSkew, s.runCount
            FROM HistorySampleV3 s JOIN HistorySeriesV3 v ON v.seriesId = s.seriesId JOIN HistoryRideKeyV3 k ON k.rideNo = v.rideNo
            WHERE k.rideId = '$rideId' ORDER BY v.sourceId, v.metricId, v.cellIndex, s.elapsedMillis, s.sequence""") { c ->
            (0 until 13).map { i -> if (i == 7) c.getDouble(i)?.toRawBits() else if (i <= 1) c.getString(i) else c.getLong(i) }
        }

        fun record(rideId: String, samples: List<HistorySample>, markers: List<HistoryMarker>, close: Boolean = true) = runBlocking {
            store.begin(HistoryRide(rideId, "bike", origin, null, HistoryRideStatus.RECORDING), sources)
            store.append(HistoryBatch(rideId, samples, emptyList(), markers))
            if (close) store.finish(rideId, (samples.map { it.tick } + markers.map { it.tick }).maxBy { it.elapsedMillis }, HistoryRideStatus.COMPLETED)
        }

        /** Rewrites [rideId]'s samples as one row each, with the sequence the single append gave them (samples go first). */
        fun explode(rideId: String, samples: List<HistorySample>) {
            val q = provider.database.rideHistoryCompactQueries
            val rideNo = checkNotNull(q.rideNoV3(rideId).executeAsOneOrNull())
            provider.database.transaction {
                q.deleteSamplesOfRideV3(rideNo)
                samples.forEachIndexed { index, s ->
                    val id = q.seriesForV3(rideNo, s.key.sourceId, s.key.metricId, s.key.cellIndex?.toLong() ?: -1L).executeAsOne()
                    q.insertSampleV3(id, s.tick.elapsedMillis, index.toLong(), HistoryCompactCodec.epochSkew(origin, s.tick),
                        HistoryCompactCodec.clockDelta(origin, s.tick), s.value, s.unknown?.let(HistoryCompactCodec::code), null, null, null, null)
                }
            }
        }

        fun run() = runBlocking { maintenance.run() }
    }

    private fun tick(ms: Long, skew: Long = 0) = HistoryTick(origin.epochMillis + ms + skew, origin.elapsedMillis + ms, origin.clockGeneration)

    /**
     * T4-shaped: 17 series, six dead (UNAVAILABLE), one alternating PARTIAL_SOURCE, uneven steps (279-323 ms) and the
     * measured 0-2 ms skew wobble, so interior ticks of a run are NOT what an even expansion gives back.
     */
    private fun t4(ticks: Int = 600): List<HistorySample> = buildList {
        var ms = 0L
        for (i in 0 until ticks) {
            for (s in 0 until 17) {
                val dead = s < 6
                val partial = s == 6 && (i / 50) % 3 == 1
                add(HistorySample(tick(ms, (i % 3).toLong()), HistorySeriesKey(if (s % 2 == 0) "ctl" else "pack", "m$s", if (s == 16) 3 else null),
                    if (dead || partial) null else 60.0 + i * 0.036,
                    if (dead) HistoryUnknownReason.UNAVAILABLE else if (partial) HistoryUnknownReason.PARTIAL_SOURCE else null))
            }
            ms += 279 + (i * 37) % 45
        }
    }
    /** A scoped BLE_GAP between two ticks and a global CONNECTED: both split the dead series. */
    private val t4Markers = listOf(HistoryMarker(tick(30_150), HistoryMarkerKind.BLE_GAP, "pack"), HistoryMarker(tick(90_100), HistoryMarkerKind.CONNECTED))

    private suspend fun readers(db: Db, rideId: String, samples: List<HistorySample>): List<String> = buildList {
        val window = HistoryRange(origin.epochMillis + 40_000, origin.epochMillis + 120_000)
        for (key in samples.map { it.key }.distinct().sortedBy { it.toString() }) {
            for (size in listOf(7, 5000)) {
                val read = ArrayList<HistorySample>()
                var after: HistoryCursor? = null
                do { val page = db.store.samples(rideId, key, whole, after, size); read += page.items; after = page.next } while (after != null)
                add("$key samples/$size ${read.map { HistoryReaderFingerprint.sampleLine(it) }}")
            }
            add("$key stats ${db.store.stats(rideId, key, whole)}")
            add("$key window ${db.store.stats(rideId, key, window)}")
            add("$key windowSamples ${db.store.samples(rideId, key, window, null, 5000).items.map { HistoryReaderFingerprint.sampleLine(it) }}")
        }
        sources.forEach { add("keys ${db.store.recordedKeys(rideId, it.id)}") }
    }

    @Test fun compactionMatchesFreshRecording() = runBlocking {
        val db = Db()
        val samples = t4()
        db.record("fresh", samples, t4Markers)
        db.record("migrated", samples, t4Markers)
        db.explode("migrated", samples)
        assertEquals(samples.size, db.rows("migrated").size, "one row per sample before")
        val fresh = db.rows("fresh")
        assertTrue(fresh.count { it[12] != null } > 6, "the markers split the dead series: ${fresh.count { it[12] != null }} runs")
        assertNotEquals(readers(db, "fresh", samples), readers(db, "migrated", samples), "interior ticks are real before: the readers can tell")

        val outcome = db.run()
        assertEquals(samples.size - fresh.size.toLong(), outcome.rowsRemoved)
        assertEquals(fresh, db.rows("migrated"), "row for row, except seriesId")
        assertEquals(readers(db, "fresh", samples), readers(db, "migrated", samples))
    }

    @Test fun liveRecordingIsAlreadyCompact() {
        val db = Db()
        val samples = t4(200)
        db.record("fresh", samples, t4Markers)
        val before = db.rows("fresh")
        assertEquals(0L, db.run().rowsRemoved)
        assertEquals(before, db.rows("fresh"))
    }

    @Test fun compactionIsIdempotentAndResumable() {
        val samples = t4(150)
        val reference = Db().apply { record("ref", samples, t4Markers) }.rows("ref")
        val db = Db()
        val ids = listOf("r1", "r2", "r3")
        ids.forEach { db.record(it, samples, t4Markers); db.explode(it, samples) }
        val exploded = db.rows("r1")
        assertNotEquals(reference, exploded)

        // A kill inside ride 2's transaction: ride 1 is done, ride 2 untouched, ride 3 not reached.
        db.maintenance.insideRideTransaction = { if (it == "r2") throw IllegalStateException("killed") }
        val killed = db.run()
        assertEquals(1, killed.ridesExamined)
        assertEquals(reference, db.rows("r1"))
        assertEquals(exploded, db.rows("r2"), "nothing half-written")
        assertEquals(exploded, db.rows("r3"))
        assertTrue(db.logged.any { "compaction stopped" in it })

        db.maintenance.insideRideTransaction = {}
        val resumed = db.run()
        assertEquals(3, resumed.ridesExamined)
        ids.forEach { assertEquals(reference, db.rows(it), it) }
        val again = db.run()
        assertEquals(0L, again.rowsRemoved, "a compacted ride has nothing left to fold")
        ids.forEach { assertEquals(reference, db.rows(it), it) }
    }

    /** Lone unknowns written by hand, so the rows are whatever the test says (no writer split them first). */
    private fun singles(db: Db, rideId: String, rows: List<Triple<Long, Long, Long?>>, markers: List<HistoryMarker> = emptyList(),
        status: HistoryRideStatus = HistoryRideStatus.COMPLETED, code: Long = 1) = runBlocking {
        val key = HistorySeriesKey("pack", "current_a")
        db.store.begin(HistoryRide(rideId, "bike", origin, null, HistoryRideStatus.RECORDING), sources)
        db.store.append(HistoryBatch(rideId, listOf(HistorySample(tick(0), key, 1.0)), emptyList(), markers))
        if (status != HistoryRideStatus.RECORDING) db.store.finish(rideId, tick(100_000), status)
        val q = db.provider.database.rideHistoryCompactQueries
        val rideNo = q.rideNoV3(rideId).executeAsOne()
        val id = q.seriesForV3(rideNo, "pack", "current_a", -1).executeAsOne()
        q.deleteSamplesOfRideV3(rideNo)
        // (elapsed, sequence, reading): a null reading is an unknown with [code].
        rows.forEach { (ms, sequence, reading) ->
            q.insertSampleV3(id, origin.elapsedMillis + ms, sequence, 0, 0, reading?.toDouble(), if (reading == null) code else null, null, null, null, null)
        }
    }
    private fun counts(db: Db, rideId: String) = db.rows(rideId).map { it[3] as Long - origin.elapsedMillis to it[12] }

    @Test fun incoherentMarkerBetweenSingletonsKeepsThemApart() {
        val db = Db()
        singles(db, "marked", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)), listOf(HistoryMarker(tick(1_150), HistoryMarkerKind.CONNECTED)))
        // Controls: the same pair with no marker, and with a marker scoped to ANOTHER source, joins.
        singles(db, "plain", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)))
        singles(db, "otherSource", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)), listOf(HistoryMarker(tick(1_150), HistoryMarkerKind.BLE_GAP, "ctl")))
        db.run()
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "marked"))
        assertEquals(listOf(1_000L to 2L), counts(db, "plain"))
        assertEquals(listOf(1_000L to 2L), counts(db, "otherSource"))
    }

    @Test fun aKnownRowBetweenUnknownsKeepsThemApart() {
        val db = Db()
        // The unknown rows are read without the known one; only the neighbour probe sees it.
        singles(db, "ride", listOf(Triple(1_000, 10, null), Triple(1_300, 11, 5), Triple(1_600, 12, null), Triple(1_900, 13, null)))
        db.run()
        assertEquals(listOf(1_000L to null, 1_300L to null, 1_600L to 2L), counts(db, "ride"))
    }

    @Test fun sequencesThatFallKeepRowsApart() {
        val db = Db()
        // Elapsed order 1000, 1300, 1600; sequences 10, 9, 11: the writer would not have extended across the fall.
        singles(db, "ride", listOf(Triple(1_000, 10, null), Triple(1_300, 9, null), Triple(1_600, 11, null)))
        db.run()
        assertEquals(listOf(1_000L to null, 1_300L to 2L), counts(db, "ride"))
        assertEquals(11L, db.rows("ride")[1][10], "the run ends on the advancing sequence")
    }

    @Test fun aCodeThisBuildDoesNotKnowNeverJoins() {
        val db = Db()
        singles(db, "ride", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)), code = 9)
        // Code 9 decodes as INVALID_VALUE (code 3), so beside a real code 3 only the code check keeps them apart, either side.
        singles(db, "knownThenNew", listOf(Triple(1_000, 10, null)), code = 3)
        singles(db, "newThenKnown", listOf(Triple(1_000, 10, null)), code = 9)
        val q = db.provider.database.rideHistoryCompactQueries
        for ((ride, code) in listOf("knownThenNew" to 9L, "newThenKnown" to 3L)) {
            val id = q.seriesForV3(q.rideNoV3(ride).executeAsOne(), "pack", "current_a", -1).executeAsOne()
            q.insertSampleV3(id, origin.elapsedMillis + 1_300, 11, 0, 0, null, code, null, null, null, null)
        }
        // Control: two real code-3 rows join.
        singles(db, "control", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)), code = 3)
        db.run()
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "ride"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "knownThenNew"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "newThenKnown"))
        assertEquals(listOf(1_000L to 2L), counts(db, "control"))
    }

    @Test fun nothingRunsWhileRecordingAndARecordingRideIsNeverTouched() {
        val db = Db()
        singles(db, "open", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)), status = HistoryRideStatus.RECORDING)
        singles(db, "closed", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)))
        db.recording = true
        val idle = db.run()
        assertTrue(idle.stoppedByRecording)
        assertEquals(0, idle.ridesExamined)
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "closed"))
        db.recording = false
        val done = db.run()
        assertEquals(1, done.ridesExamined, "only the closed ride is listed")
        assertEquals(listOf(1_000L to 2L), counts(db, "closed"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "open"), "the writer's ride is left to the writer")
    }

    @Test fun recordingThatStartsMidPassStopsBeforeTheNextRide() {
        val db = Db()
        listOf("a", "b").forEach { singles(db, it, listOf(Triple(1_000, 10, null), Triple(1_300, 11, null))) }
        db.maintenance.insideRideTransaction = { db.recording = true }
        val outcome = db.run()
        assertEquals(1, outcome.ridesExamined)
        assertTrue(outcome.stoppedByRecording)
        assertEquals(listOf(1_000L to 2L), counts(db, "a"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "b"))
    }

    @Test fun vacuumPlanRequiresSpaceAndIdle() {
        val mib = 1024L * 1024
        val live = 80 * mib
        fun plan(recording: Boolean = false, freelist: Long = 32 * mib, free: Long = 2 * live, left: Int = 0) =
            maintenancePlan(recording, freelist, live, free, left)
        assertEquals(listOf(Vacuum), plan(), "every condition holds, at its exact edge")
        assertEquals(emptyList(), plan(recording = true))
        assertEquals(emptyList(), plan(freelist = 31 * mib))
        assertEquals(emptyList(), plan(freelist = 32 * mib - 1))
        assertEquals(emptyList(), plan(free = 2 * live - 1))
        assertEquals(listOf(Compact), plan(left = 3), "compaction first: no VACUUM while rides are left")
        assertEquals(emptyList(), plan(recording = true, left = 3))
        assertEquals(listOf(Compact), plan(freelist = 0, free = 0, left = 1))
        assertEquals(emptyList(), plan(freelist = 0))
    }

    /** The runner wires the plan to real pragmas: a file with 40 MiB on its freelist is vacuumed only with the space for it. */
    @Test fun vacuumRunsOnlyWithFreePagesAndSpace() {
        val file = File(root, "vacuum.db")
        val db = Db(file)
        db.driver.execute(null, "CREATE TABLE Junk(b BLOB)", 0)
        db.provider.database.transaction { repeat(40) { db.driver.execute(null, "INSERT INTO Junk VALUES (zeroblob(1048576))", 0) } }
        db.driver.execute(null, "DELETE FROM Junk", 0)
        val freePages = db.long("PRAGMA freelist_count")
        assertTrue(freePages * db.long("PRAGMA page_size") >= HISTORY_VACUUM_MIN_FREELIST_BYTES, "$freePages free pages")
        val live = (db.long("PRAGMA page_count") - freePages) * db.long("PRAGMA page_size")

        db.free = 2 * live - 1
        assertFalse(db.run().vacuumed)
        assertEquals(freePages, db.long("PRAGMA freelist_count"))
        db.recording = true; db.free = Long.MAX_VALUE
        assertFalse(db.run().vacuumed)
        db.recording = false
        assertTrue(db.run().vacuumed)
        assertEquals(0L, db.long("PRAGMA freelist_count"))
        assertTrue(file.length() < 10 * 1024 * 1024, "${file.length()} bytes")
    }

    /** The merge fold itself, on the rows of one series with the known rows present (no neighbour probe needed). */
    @Test fun foldJoinsRunsToNeighboursAndKeepsTheSpanBound() {
        val key = HistorySeriesKey("pack", "current_a")
        fun unknown(ms: Long) = HistorySample(tick(ms), key, null, HistoryUnknownReason.UNAVAILABLE)
        // A run of 3 (0..600), a lone row at 2600 (step exactly 2000) and a run 2900..3200: one run of 6.
        val rows = listOf(HistoryStoredSample(unknown(0), 1, HistoryUnknownRunEnd(tick(600), 3, 3)), HistoryStoredSample(unknown(2_600), 4),
            HistoryStoredSample(unknown(2_900), 5, HistoryUnknownRunEnd(tick(3_200), 6, 2)),
            HistoryStoredSample(unknown(5_201), 7))
        val merge = planHistoryRunMerges(rows, LongArray(0)).single()
        assertEquals(rows[0], merge.head)
        assertEquals(rows.subList(1, 3), merge.absorbed)
        assertEquals(HistoryUnknownRunEnd(tick(3_200), 6, 6), merge.end)
        // The merged run expands within the Task 3 bound (the expansion would throw otherwise), and 5201 (2001 ms) stays apart.
        assertEquals(6, HistoryRunMembers(HistoryStoredSample(merge.head.sample, 1, merge.end)).size)
    }
}
