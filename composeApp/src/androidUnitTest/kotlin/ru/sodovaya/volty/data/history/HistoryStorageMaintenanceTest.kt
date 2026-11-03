package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
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
        /** Counts every statement that names the sample table, so "no scan" is observed rather than assumed. */
        var sampleQueries = 0
        private val counting = object : SqlDriver by driver {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                if ("HistorySampleV3" in sql) sampleQueries++
                return driver.executeQuery(identifier, sql, mapper, parameters, binders)
            }
        }
        val provider = VoltyDatabaseProvider(counting)
        val store = SqlDelightHistoryStore(provider, Dispatchers.Unconfined)
        var recording = false
        /** Answers to hand out before falling back to [recording], one per isRecording() call. */
        val recordingAnswers = ArrayDeque<Boolean>()
        var free = Long.MAX_VALUE
        val logged = ArrayList<String>()
        /** The app preference, modelled: the schema a completed pass was marked for, and the current schema. */
        var doneFor = 0
        var schema = 29
        var marks = 0
        val maintenance = HistoryStorageMaintenance(provider, { free }, { recordingAnswers.removeFirstOrNull() ?: recording }, Dispatchers.Unconfined,
            compactionDone = { doneFor >= schema }, markCompactionDone = { doneFor = schema; marks++ }) { message, failure ->
            logged += "$message ${failure?.message ?: ""}"
        }
        val compactor = HistoryRunCompactor(provider.database)
        fun rideNo(rideId: String) = provider.database.rideHistoryCompactQueries.rideNoV3(rideId).executeAsOne()

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

        // A failure inside ride 2's transaction (after its writes): ride 2 rolls back whole, is logged with its cause and
        // skipped, and the pass goes on to ride 3 (review I2). The pass is FAILED, so the once-only flag stays unset.
        db.maintenance.insideRideTransaction = { if (it == "r2") throw IllegalStateException("killed") }
        val killed = db.run()
        assertEquals(HistoryStorageMaintenance.Status.FAILED, killed.status)
        assertEquals(3, killed.ridesExamined)
        assertEquals(listOf(db.rideNo("r2")), killed.failedRides)
        assertEquals(reference, db.rows("r1"))
        assertEquals(exploded, db.rows("r2"), "nothing half-written")
        assertEquals(reference, db.rows("r3"), "one bad ride does not stop the rides after it")
        assertTrue(db.logged.any { "ride key ${db.rideNo("r2")}" in it && "killed" in it }, "${db.logged}")
        assertEquals(0, db.marks, "a failed pass never marks the flag")

        db.maintenance.insideRideTransaction = {}
        val resumed = db.run()
        assertEquals(HistoryStorageMaintenance.Status.COMPLETED, resumed.status)
        assertEquals(3, resumed.ridesExamined)
        ids.forEach { assertEquals(reference, db.rows(it), it) }
        assertEquals(1, db.marks)
        // Idempotent with the flag out of the way: a compacted ride has nothing left to fold.
        db.doneFor = 0
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
        assertEquals(HistoryStorageMaintenance.Status.DEFERRED, idle.status)
        assertEquals(0, idle.ridesExamined)
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "closed"))
        db.recording = false
        val done = db.run()
        assertEquals(1, done.ridesExamined, "only the closed ride is listed")
        assertEquals(listOf(1_000L to 2L), counts(db, "closed"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "open"), "the writer's ride is left to the writer")
        // Review I1: the open ride (a killed process's, not yet closed by recoverInterrupted) keeps the pass from completing.
        assertEquals(HistoryStorageMaintenance.Status.DEFERRED, done.status)
        assertFalse(done.stoppedByRecording, "no live recording: nothing to wait for in this process")
        assertEquals(1L, done.openRides)
        assertEquals(0, db.marks)
        // Once it is closed, the next start compacts it and only then marks the flag.
        runBlocking { db.store.finish("open", tick(100_000), HistoryRideStatus.INTERRUPTED) }
        val later = db.run()
        assertEquals(HistoryStorageMaintenance.Status.COMPLETED, later.status)
        assertEquals(listOf(1_000L to 2L), counts(db, "open"))
        assertEquals(1, db.marks)
    }

    @Test fun recordingThatStartsMidPassStopsBeforeTheNextRide() {
        val db = Db()
        listOf("a", "b").forEach { singles(db, it, listOf(Triple(1_000, 10, null), Triple(1_300, 11, null))) }
        db.maintenance.insideRideTransaction = { db.recording = true }
        val outcome = db.run()
        assertEquals(1, outcome.ridesExamined)
        assertTrue(outcome.stoppedByRecording)
        assertEquals(HistoryStorageMaintenance.Status.DEFERRED, outcome.status)
        assertEquals(0, db.marks)
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
        assertEquals(1, db.marks, "no rides: the first pass completes and marks the flag")
        assertEquals(freePages, db.long("PRAGMA freelist_count"))
        db.recording = true; db.free = Long.MAX_VALUE
        assertFalse(db.run().vacuumed)
        db.recording = false
        // Idle at the plan, recording by the time VACUUM would start (review M2): it does not start.
        db.recordingAnswers.addAll(listOf(false, true))
        assertFalse(db.run().vacuumed)
        assertEquals(freePages, db.long("PRAGMA freelist_count"))
        // The flag is set, yet the VACUUM plan is still derived on every start.
        val vacuumed = db.run()
        assertTrue(vacuumed.skippedAsDone)
        assertTrue(vacuumed.vacuumed)
        assertEquals(0L, db.long("PRAGMA freelist_count"))
        assertTrue(file.length() < 10 * 1024 * 1024, "${file.length()} bytes")
    }

    // ---- Fix round 1: the once-only flag, the race re-check, and the clauses the DB layer feeds ----

    @Test fun aCompletedPassMarksTheFlagAndTheNextStartReadsNoSample() {
        val db = Db()
        singles(db, "first", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)))
        val pass = db.run()
        assertEquals(HistoryStorageMaintenance.Status.COMPLETED, pass.status)
        assertEquals(1, db.marks)
        assertEquals(29, db.doneFor)
        // A ride whose chain is still unfolded: with the flag current nothing looks at it, not even a read.
        singles(db, "later", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)))
        db.sampleQueries = 0
        val skipped = db.run()
        assertTrue(skipped.skippedAsDone)
        assertEquals(HistoryStorageMaintenance.Status.COMPLETED, skipped.status)
        assertEquals(0, skipped.ridesExamined)
        assertEquals(0, db.sampleQueries, "no statement touched the sample table")
        assertEquals(1, db.marks, "not marked again")
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "later"))
        // A new schema re-arms it.
        db.schema = 30
        val rearmed = db.run()
        assertFalse(rearmed.skippedAsDone)
        assertEquals(listOf(1_000L to 2L), counts(db, "later"))
        assertEquals(30, db.doneFor)
    }

    @Test fun runUntilSettledWaitsForRecordingToEndAndThenFinishes() = runBlocking {
        val db = Db()
        listOf("a", "b").forEach { singles(db, it, listOf(Triple(1_000, 10, null), Triple(1_300, 11, null))) }
        db.recording = true
        var waits = 0
        val outcome = db.maintenance.runUntilSettled { waits++; db.recording = false }
        assertEquals(1, waits)
        assertEquals(HistoryStorageMaintenance.Status.COMPLETED, outcome.status)
        assertEquals(listOf(1_000L to 2L), counts(db, "a"))
        assertEquals(listOf(1_000L to 2L), counts(db, "b"))
        assertEquals(1, db.marks)
        // A pass that is not stopped by a live recording (here a failure) is final: no waiting.
        db.doneFor = 0
        db.maintenance.insideRideTransaction = { throw IllegalStateException("x") }
        singles(db, "c", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)))
        val failed = db.maintenance.runUntilSettled { waits++ }
        assertEquals(HistoryStorageMaintenance.Status.FAILED, failed.status)
        assertEquals(1, waits)
    }

    /** Review I5a: the ride is re-read inside the write transaction, and the plan is redone there. */
    @Test fun theWriteTransactionRechecksTheRideAndRedoesThePlan() {
        val db = Db()
        val pair = listOf(Triple(1_000L, 10L, null as Long?), Triple(1_300L, 11L, null))
        singles(db, "reopened", pair); singles(db, "deleted", pair); singles(db, "changed", pair)
        singles(db, "open", pair, status = HistoryRideStatus.RECORDING)
        // Called directly: the list query already filters RECORDING, so only this reaches the compactor's own check.
        assertEquals(HistoryRideCompaction.Recording, db.compactor.compactRide(db.rideNo("open")))
        assertEquals(HistoryRideCompaction.Gone, db.compactor.compactRide(9_999))
        // Between the read-only plan and the write: the ride turns RECORDING (incoherent, no producer does it), is
        // deleted, or gains a known row between the pair. None of the stale plan may be written.
        assertEquals(HistoryRideCompaction.Recording, db.compactor.compactRide(db.rideNo("reopened"), beforeWrite = {
            db.driver.execute(null, "UPDATE HistoryRideV2 SET status = 'RECORDING' WHERE id = '$it'", 0)
        }))
        val deletedNo = db.rideNo("deleted")
        assertEquals(HistoryRideCompaction.Gone, db.compactor.compactRide(deletedNo, beforeWrite = { runBlocking { db.store.delete(it) } }))
        val changedNo = db.rideNo("changed")
        assertEquals(HistoryRideCompaction.Folded(0), db.compactor.compactRide(changedNo, beforeWrite = {
            val q = db.provider.database.rideHistoryCompactQueries
            val id = q.seriesForV3(changedNo, "pack", "current_a", -1).executeAsOne()
            q.insertSampleV3(id, origin.elapsedMillis + 1_150, 50, 0, 0, 7.0, null, null, null, null, null)
        }))
        assertEquals(listOf(1_000L to null, 1_150L to null, 1_300L to null), counts(db, "changed"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "reopened"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "open"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM HistorySampleV3 s JOIN HistorySeriesV3 v ON v.seriesId = s.seriesId WHERE v.rideNo = $deletedNo"))
    }

    /** One row written exactly as given; a run when [endMs] is set. */
    private fun raw(db: Db, rideId: String, ms: Long, sequence: Long, skew: Long = 0, delta: Long = 0, code: Long = 1,
        endMs: Long? = null, endSequence: Long? = null, endSkew: Long? = null, count: Long? = null) {
        val q = db.provider.database.rideHistoryCompactQueries
        val id = q.seriesForV3(db.rideNo(rideId), "pack", "current_a", -1).executeAsOne()
        q.insertSampleV3(id, origin.elapsedMillis + ms, sequence, skew, delta, null, code,
            endMs?.let { origin.elapsedMillis + it }, endSequence, endSkew, count)
    }

    /** Review I5b: clock generation, skew and reason reach historyRunContinues from the stored columns. */
    @Test fun storedClockSkewAndReasonEachKeepRowsApart() {
        val db = Db()
        listOf("generation", "skew16", "skew15", "reason", "control").forEach { singles(db, it, emptyList()) }
        raw(db, "generation", 1_000, 10, delta = 1); raw(db, "generation", 1_300, 11, delta = 2)
        raw(db, "skew16", 1_000, 10, skew = 3); raw(db, "skew16", 1_300, 11, skew = 19)
        raw(db, "skew15", 1_000, 10, skew = 3); raw(db, "skew15", 1_300, 11, skew = 18)
        raw(db, "reason", 1_000, 10, code = 1); raw(db, "reason", 1_300, 11, code = 2)
        // Control in a later generation: equal deltas join, and the run keeps the generation and the end's own skew.
        raw(db, "control", 1_000, 10, delta = 2, skew = 4); raw(db, "control", 1_300, 11, delta = 2, skew = 9)
        db.run()
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "generation"))
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "skew16"))
        assertEquals(listOf(1_000L to 2L), counts(db, "skew15"), "15 ms is inside the tolerance")
        assertEquals(listOf(1_000L to null, 1_300L to null), counts(db, "reason"))
        val run = db.rows("control").single()
        assertEquals(listOf(4L, 2L, 9L), listOf(run[5], run[6], run[11]), "epochSkew, clockDelta, runEndEpochSkew")
    }

    /**
     * Rule 2 fixtures: stored runs no producer emits, so only the END member's columns can decide. Run A ends with a skew
     * 40 away from its head; its neighbour sits 2 ms from the END and 42 ms from the head (joins). Run B's neighbour matches
     * the HEAD's skew and is 40 ms from the end (stays apart). Run C's neighbour has a sequence above the run's head
     * sequence but below its end sequence (stays apart).
     */
    @Test fun aStoredRunIsJudgedByItsEndMember() {
        val db = Db()
        listOf("endSkew", "headSkew", "midSequence").forEach { singles(db, it, emptyList()) }
        raw(db, "endSkew", 1_000, 10, skew = 0, endMs = 1_600, endSequence = 12, endSkew = 40, count = 3)
        raw(db, "endSkew", 1_900, 13, skew = 42)
        raw(db, "headSkew", 1_000, 10, skew = 0, endMs = 1_600, endSequence = 12, endSkew = 40, count = 3)
        raw(db, "headSkew", 1_900, 13, skew = 0)
        raw(db, "midSequence", 1_000, 10, endMs = 1_600, endSequence = 20, endSkew = 0, count = 3)
        raw(db, "midSequence", 1_900, 15)
        db.run()
        assertEquals(listOf(1_000L to 4L), counts(db, "endSkew"))
        assertEquals(listOf(origin.elapsedMillis + 1_900, 13L, 42L), db.rows("endSkew").single().let { listOf(it[9], it[10], it[11]) })
        assertEquals(listOf(1_000L to 3L, 1_900L to null), counts(db, "headSkew"))
        assertEquals(listOf(1_000L to 3L, 1_900L to null), counts(db, "midSequence"))
    }

    /** Review M1: only gap kinds split; a GPS_GAP between two singletons does not. */
    @Test fun aNonGapMarkerDoesNotSplit() {
        val db = Db()
        singles(db, "gps", listOf(Triple(1_000, 10, null), Triple(1_300, 11, null)), listOf(HistoryMarker(tick(1_150), HistoryMarkerKind.GPS_GAP)))
        db.run()
        assertEquals(listOf(1_000L to 2L), counts(db, "gps"))
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
