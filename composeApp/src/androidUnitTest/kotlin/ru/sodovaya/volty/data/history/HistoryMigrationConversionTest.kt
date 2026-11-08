package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

/**
 * `28.sqm` (compact-history-storage plan, Task 9) against the rule it must obey: the rows a conversion stores are exactly
 * what [historyRunContinues] plus the writer's sequence rule make of the V2 rows, folded here in Kotlin with the real
 * functions. The fixtures are deliberately incoherent: a chain whose sequences fall, a reason that changes mid-chain, a
 * skew that steps by exactly the tolerance and one past it, a step of exactly the limit and one past it, a marker on
 * every kind, a backwards wall clock.
 */
class HistoryMigrationConversionTest {
    private val root: File = Files.createTempDirectory("volty-conversion-test-").toFile()
    private val drivers = ArrayList<JdbcSqliteDriver>()
    private var file = 0
    @AfterTest fun close() { drivers.forEach { it.close() }; root.deleteRecursively() }

    private fun v28(): JdbcSqliteDriver =
        HistoryV2Fixture.createAt(28, File(root, "v28-${file++}.db")).also { drivers += it }

    private fun migrate(driver: JdbcSqliteDriver) = VoltyDatabaseProvider(driver).database.transaction {
        VoltyDatabase.Schema.migrate(driver, 28, 29)
    }

    /** One transaction: on a file database every autocommitted insert is a sync, minutes for the T4 ride. */
    private fun bulk(driver: JdbcSqliteDriver, block: () -> Unit) = VoltyDatabaseProvider(driver).database.transaction { block() }

    private fun <T> query(driver: JdbcSqliteDriver, sql: String, map: (SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { c ->
            val rows = ArrayList<T>()
            while (c.next().value) rows += map(c)
            QueryResult.Value(rows)
        }, 0).value
    private fun long(driver: JdbcSqliteDriver, sql: String): Long = query(driver, sql) { checkNotNull(it.getLong(0)) }.single()
    private fun exists(driver: JdbcSqliteDriver, table: String) =
        long(driver, "SELECT COUNT(*) FROM sqlite_master WHERE name = '$table'") > 0

    private fun ride(driver: JdbcSqliteDriver, id: String, origin: HistoryTick, status: String = "COMPLETED", ended: HistoryTick? = origin) {
        driver.execute(null, "INSERT INTO HistoryRideV2(id, vehicleId, startedEpochMillis, startedElapsedMillis, clockGeneration, " +
            "status, endedEpochMillis, endedElapsedMillis, endedClockGeneration) VALUES (?,?,?,?,?,?,?,?,?)", 9) {
            bindString(0, id); bindString(1, "bike"); bindLong(2, origin.epochMillis); bindLong(3, origin.elapsedMillis)
            bindLong(4, origin.clockGeneration); bindString(5, status); bindLong(6, ended?.epochMillis)
            bindLong(7, ended?.elapsedMillis); bindLong(8, ended?.clockGeneration)
        }
    }
    private fun source(driver: JdbcSqliteDriver, rideId: String, id: String) =
        driver.execute(null, "INSERT INTO HistorySourceV2(rideId, id, kind, label) VALUES ('$rideId', '$id', 'BATTERY', '$id')", 0)

    private data class V2(val ride: String, val sequence: Long, val source: String, val metric: String, val cell: Long, val epoch: Long,
        val elapsed: Long, val generation: Long, val reading: Double?, val reason: String?)

    private fun insert(driver: JdbcSqliteDriver, r: V2) = HistoryV2Fixture.insertSample(driver, r.ride, r.sequence, r.source, r.metric,
        r.cell, r.epoch, r.elapsed, r.generation, r.reading, r.reason)
    private fun marker(driver: JdbcSqliteDriver, rideId: String, sequence: Long, elapsed: Long, kind: HistoryMarkerKind, sourceId: String? = null) =
        driver.execute(null, "INSERT INTO HistoryMarkerV2(rideId, sequence, epochMillis, elapsedMillis, clockGeneration, kind, sourceId) " +
            "VALUES (?,?,?,?,?,?,?)", 7) {
            bindString(0, rideId); bindLong(1, sequence); bindLong(2, elapsed); bindLong(3, elapsed); bindLong(4, 1)
            bindString(5, kind.name); bindString(6, sourceId)
        }
    private fun v2Rows(driver: JdbcSqliteDriver) = query(driver, "SELECT rideId, sequence, sourceId, metricId, cellIndex, epochMillis, " +
        "elapsedMillis, clockGeneration, reading, unknownReason FROM HistorySampleV2 ORDER BY rideId, sequence") {
        V2(it.getString(0)!!, it.getLong(1)!!, it.getString(2)!!, it.getString(3)!!, it.getLong(4)!!, it.getLong(5)!!, it.getLong(6)!!,
            it.getLong(7)!!, it.getDouble(8), it.getString(9))
    }

    /** One row of the compact table, in V2's terms: its first member, and for a run the end and the count. */
    private data class Stored(val ride: String, val sequence: Long, val source: String, val metric: String, val cell: Long, val epoch: Long,
        val elapsed: Long, val generation: Long, val bits: Long?, val reason: String?, val endElapsed: Long?, val endSequence: Long?,
        val endEpoch: Long?, val count: Long?)

    private fun compactRows(driver: JdbcSqliteDriver) = query(driver, """SELECT k.rideId, s.sequence, v.sourceId, v.metricId, v.cellIndex,
        s.elapsedMillis + s.epochSkew + r.startedEpochMillis - r.startedElapsedMillis, s.elapsedMillis, r.clockGeneration + s.clockDelta,
        s.reading, s.unknownCode, s.runEndElapsed, s.runEndSequence,
        s.runEndElapsed + s.runEndEpochSkew + r.startedEpochMillis - r.startedElapsedMillis, s.runCount
        FROM HistorySampleV3 s JOIN HistorySeriesV3 v ON v.seriesId = s.seriesId JOIN HistoryRideKeyV3 k ON k.rideNo = v.rideNo
        JOIN HistoryRideV2 r ON r.id = k.rideId ORDER BY k.rideId, v.sourceId, v.metricId, v.cellIndex, s.elapsedMillis, s.sequence""") {
        Stored(it.getString(0)!!, it.getLong(1)!!, it.getString(2)!!, it.getString(3)!!, it.getLong(4)!!, it.getLong(5)!!, it.getLong(6)!!,
            it.getLong(7)!!, it.getDouble(8)?.toRawBits(), it.getLong(9)?.let { c -> HistoryCompactCodec.reason(c).name }, it.getLong(10),
            it.getLong(11), it.getLong(12), it.getLong(13))
    }

    private fun sample(r: V2) = HistorySample(HistoryTick(r.epoch, r.elapsed, r.generation),
        HistorySeriesKey(r.source, r.metric, r.cell.takeIf { it >= 0 }?.toInt()), r.reading, r.reason?.let(HistoryUnknownReason::valueOf))

    /** The same fold `HistoryCompactSampleWriter` does: [historyRunContinues] between neighbours, and sequences that advance. */
    private fun expected(rows: List<V2>, markers: Map<String, List<HistoryMarker>>): List<Stored> {
        val result = ArrayList<Stored>()
        val series = rows.groupBy { listOf(it.ride, it.source, it.metric, it.cell.toString()) }.toSortedMap(compareBy({ it[0] }, { it[1] }, { it[2] }, { it[3].toLong() }))
        for ((_, members) in series) {
            val ordered = members.sortedWith(compareBy({ it.elapsed }, { it.sequence }))
            val breaks = historyRunBreakTicks(markers[ordered[0].ride].orEmpty(), ordered[0].source)
            var head = 0
            fun flush(last: Int) {
                val a = ordered[head]; val z = ordered[last]; val run = last > head
                result += Stored(a.ride, a.sequence, a.source, a.metric, a.cell, a.epoch, a.elapsed, a.generation, a.reading?.toRawBits(), a.reason,
                    z.elapsed.takeIf { run }, z.sequence.takeIf { run }, z.epoch.takeIf { run }, (last - head + 1L).takeIf { run })
            }
            for (i in 1 until ordered.size) {
                val p = ordered[i - 1]; val n = ordered[i]
                if (n.sequence <= p.sequence || !historyRunContinues(sample(p), sample(n), breaks)) { flush(i - 1); head = i }
            }
            flush(ordered.lastIndex)
        }
        return result.sortedWith(compareBy({ it.ride }, { it.source }, { it.metric }, { it.cell }, { it.elapsed }, { it.sequence }))
    }

    private fun assertRows(expected: List<Stored>, actual: List<Stored>) {
        assertEquals(expected.size, actual.size, "row count")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a) }
    }

    /** Ride 1 is T4-shaped: 17 series of 2,752 rows, six dead, 46,782 rows after a torn last tick of two series. */
    private fun t4Rows(id: String, origin: HistoryTick): List<V2> {
        val rows = ArrayList<V2>()
        var sequence = 0L; var ms = 0L
        for (i in 0 until 2752) {
            for (s in 0 until 17) {
                if (i == 2751 && s >= 15) continue
                val dead = s < 6
                val partial = s == 6 && (i / 50) % 3 == 1
                val skew = (i % 3).toLong()   // the measured 1 ms wobble
                rows += V2(id, sequence++, if (s % 2 == 0) "ctl" else "pack", "m$s", if (s == 16) 3L else -1L, origin.epochMillis + ms + skew,
                    origin.elapsedMillis + ms, origin.clockGeneration, if (dead || partial) null else 60.0 + i * 0.036,
                    if (dead) "UNAVAILABLE" else if (partial) "PARTIAL_SOURCE" else null)
            }
            ms += 279 + (i * 37) % 45
        }
        return rows
    }

    @Test fun conversionIsLosslessRowByRow() {
        val driver = v28()
        val o1 = HistoryTick(1_700_000_000_000, 5_000, 7)
        ride(driver, "ride-1", o1); listOf("ctl", "pack").forEach { source(driver, "ride-1", it) }
        val t4 = t4Rows("ride-1", o1)
        assertEquals(46_782, t4.size)
        bulk(driver) { t4.forEach { insert(driver, it) } }
        // Ride 2: an origin whose epoch is not elapsed + constant, a clock generation +1 mid-ride, and the readings a cast
        // to float, or a CAST through text, would change.
        val o2 = HistoryTick(1_600_000_000_123, 40, 2)
        ride(driver, "ride-2", o2); source(driver, "ride-2", "pack")
        fun r(sequence: Long, elapsed: Long, skew: Long, generation: Long, reading: Double?, reason: String? = null) =
            V2("ride-2", sequence, "pack", "voltage_v", -1, o2.epochMillis + elapsed - o2.elapsedMillis + skew, elapsed, generation, reading, reason)
        val odd = listOf(r(0, 40, 0, 2, -0.0), r(1, 340, 123_456, 2, 1e-300), r(2, 640, -7, 2, 1.0 / 3.0), r(3, 940, 0, 3, null, "GAP"),
            r(4, 1240, 0, 3, null, "GAP"), r(5, 1540, 2, 3, null, "GAP"), r(6, 1840, 0, 3, 123456.789012345))
        odd.forEach { insert(driver, it) }
        // Ride 3: INTERRUPTED with no samples still gets a key.
        val o3 = HistoryTick(1_800_000_000_000, 9, 1)
        ride(driver, "ride-3", o3, "INTERRUPTED")
        val before = v2Rows(driver)
        migrate(driver)

        assertRows(expected(before, emptyMap()), compactRows(driver))
        // Join back, the other way: every V2 row is a stored row, or a member of exactly one run of its series.
        val stored = compactRows(driver)
        assertEquals(before.size.toLong(), stored.sumOf { it.count ?: 1L }, "every V2 row is accounted for")
        val bySeries = stored.groupBy { Triple(it.ride, it.source to it.metric, it.cell) }
        for (row in before) {
            val owners = bySeries.getValue(Triple(row.ride, row.source to row.metric, row.cell))
                .filter { row.sequence == it.sequence || (it.count != null && row.elapsed >= it.elapsed && row.elapsed <= it.endElapsed!! && row.reading == null) }
            assertEquals(1, owners.size, "$row")
            if (row.reading != null) assertEquals(row.reading.toRawBits(), owners.single().bits)
            else assertEquals(row.reason, owners.single().reason)
        }
        // A dead T4 series is one row; the wobble does not fragment it.
        assertEquals(6 * 2752L - 0, stored.filter { it.ride == "ride-1" && it.reason == "UNAVAILABLE" }.sumOf { it.count ?: 1L })
        assertEquals(6, stored.count { it.ride == "ride-1" && it.reason == "UNAVAILABLE" })
        // The GAP chain moves its skew by 2 ms (inside the tolerance) after the generation step: one run of 3.
        assertEquals(listOf(3L), stored.filter { it.ride == "ride-2" }.mapNotNull { it.count })
        assertEquals(1L, stored.filter { it.ride == "ride-2" }.count { it.generation == 3L && it.reason == "GAP" }.toLong())
        assertEquals(3L, long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3"))
        assertEquals(listOf("ride-2", "ride-1", "ride-3"), query(driver, "SELECT rideId FROM HistoryRideKeyV3 ORDER BY rideNo") { it.getString(0)!! }, "rideNo in start order")
        assertFalse(exists(driver, "HistorySampleV2")); assertFalse(exists(driver, "HistorySampleV2_series_elapsed"))
        assertEquals(emptyList(), query(driver, "SELECT name FROM sqlite_master WHERE name LIKE 'HistoryConversion%'") { it.getString(0)!! })
    }

    @Test fun convertingOneRideIsIsolatedIdempotentAndRollsBackAtomically() {
        val driver = v28()
        val origin = HistoryTick(1_700_000_000_000, 5_000, 7)
        ride(driver, "ride-1", origin)
        ride(driver, "ride-2", origin.copy(epochMillis = origin.epochMillis + 10_000_000))
        listOf("ride-1", "ride-2").forEach { rideId -> source(driver, rideId, "pack") }
        val rideOneRows = listOf(
            V2("ride-1", 0, "pack", "voltage_v", -1, origin.epochMillis, origin.elapsedMillis, origin.clockGeneration, 93.5, null),
            V2("ride-1", 1, "pack", "current_a", -1, origin.epochMillis + 300, origin.elapsedMillis + 300, origin.clockGeneration, null, "UNAVAILABLE"),
            V2("ride-1", 2, "pack", "current_a", -1, origin.epochMillis + 600, origin.elapsedMillis + 600, origin.clockGeneration, null, "UNAVAILABLE"),
        )
        val rideTwoRows = listOf(
            V2("ride-2", 3, "pack", "voltage_v", -1, origin.epochMillis + 10_000_000, origin.elapsedMillis, origin.clockGeneration, 88.0, null),
        )
        bulk(driver) { (rideOneRows + rideTwoRows).forEach { insert(driver, it) } }
        marker(driver, "ride-1", 3, origin.elapsedMillis + 600, HistoryMarkerKind.BLE_GAP, "pack")
        val before = v2Rows(driver)

        val database = VoltyDatabaseProvider(driver).database
        database.transaction {
            convertLegacyRide(driver, "ride-1")
            rollback()
        }
        assertEquals(before, v2Rows(driver), "a rolled-back conversion keeps every legacy sample")
        assertEquals(0L, long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3"), "the compact key rolls back with its samples")
        assertEquals(0L, long(driver, "SELECT COUNT(*) FROM HistorySampleV3"))

        database.transaction { convertLegacyRide(driver, "ride-1") }
        val storedRideOne = compactRows(driver).filter { it.ride == "ride-1" }
        assertRows(expected(rideOneRows, mapOf("ride-1" to listOf(
            HistoryMarker(HistoryTick(origin.epochMillis + 600, origin.elapsedMillis + 600, origin.clockGeneration), HistoryMarkerKind.BLE_GAP, "pack"),
        ))), storedRideOne)
        assertEquals(rideTwoRows, v2Rows(driver), "the neighboring legacy ride remains available for a later batch")
        assertEquals(1L, ru.sodovaya.volty.data.db.pendingLegacyRideCount(driver))
        assertEquals("ride-2", ru.sodovaya.volty.data.db.nextLegacyRide(driver))
        assertEquals(1L, long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3 WHERE rideId = 'ride-1'"))

        database.transaction { convertLegacyRide(driver, "ride-1") }
        assertEquals(storedRideOne, compactRows(driver).filter { it.ride == "ride-1" }, "retrying a committed ride is a no-op")
        assertEquals(rideTwoRows, v2Rows(driver), "a retry does not consume neighboring legacy rows")
        assertEquals(1L, long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3 WHERE rideId = 'ride-1'"))
    }

    /**
     * Every boundary of the rule at its exact edge, in random order, with every marker kind: the SQL and the Kotlin fold must
     * agree row for row, and each cause must really have split something, or the fixture proves nothing about that clause.
     */
    @Test fun everyRuleAgreesWithTheKotlinFold() {
        val driver = v28()
        val random = Random(20261103)
        val origin = HistoryTick(1_700_000_000_000, 1_000, 5)
        val reasons = listOf("UNAVAILABLE", "PARTIAL_SOURCE", "INVALID_VALUE", "GAP", "LEGACY")
        val steps = longArrayOf(0, 1, 300, 300, 300, 1999, 2000, 2000, 2001, 2500)
        val skews = longArrayOf(0, 0, 0, 1, -1, 15, -15, 16, -16, -400, 5000)
        val kinds = HistoryMarkerKind.entries
        val rows = ArrayList<V2>(); val markers = HashMap<String, MutableList<HistoryMarker>>()
        var nextMarker = 0L
        for (rideIndex in 0 until 3) {
            val id = "r$rideIndex"
            ride(driver, id, origin); listOf("a", "b").forEach { source(driver, id, it) }
            var sequence = 0L
            for (seriesIndex in 0 until 4) {
                val src = if (seriesIndex % 2 == 0) "a" else "b"
                var ms = 0L; var skew = 0L; var generation = origin.clockGeneration; var reason = reasons[seriesIndex]
                repeat(1500) {
                    ms += steps[random.nextInt(steps.size)]
                    skew += skews[random.nextInt(skews.size)]
                    if (random.nextInt(25) == 0) generation++
                    if (random.nextInt(25) == 0) reason = reasons[random.nextInt(reasons.size)]
                    val known = random.nextInt(20) == 0
                    // One row in fifteen is committed out of order: its sequence is below the row before it.
                    val at = if (random.nextInt(15) == 0) sequence + 1_000_000 else sequence
                    sequence++
                    val elapsed = origin.elapsedMillis + ms
                    rows += V2(id, if (random.nextInt(15) == 0) 3_000_000 - sequence else at, src, "m$seriesIndex", -1, origin.epochMillis + ms + skew, elapsed,
                        generation, if (known) random.nextDouble() else null, if (known) null else reason)
                    if (random.nextInt(30) == 0) {
                        val kind = kinds[random.nextInt(kinds.size)]
                        // Only these two kinds can be scoped to a source (HistoryMarker refuses the rest).
                        val scopable = kind == HistoryMarkerKind.BLE_GAP || kind == HistoryMarkerKind.CONNECTED
                        val scope = if (!scopable) null else when (random.nextInt(3)) { 0 -> null; 1 -> "a"; else -> "b" }
                        val markedAt = elapsed - random.nextLong(0, 300)
                        marker(driver, id, nextMarker++, markedAt, kind, scope)
                        markers.getOrPut(id) { ArrayList() } += HistoryMarker(HistoryTick(markedAt, markedAt, 1), kind, scope)
                    }
                }
            }
        }
        val distinct = rows.distinctBy { it.ride to it.sequence }
        bulk(driver) { distinct.forEach { insert(driver, it) } }
        val before = v2Rows(driver)
        migrate(driver)
        val stored = compactRows(driver)
        assertRows(expected(before, markers), stored)
        assertTrue(stored.count { it.count != null } > 100, "runs: ${stored.count { it.count != null }}")

        // Which clause split where: on the rows, neighbour by neighbour, with the other clauses held true.
        val causes = HashMap<String, Int>()
        for ((_, series) in before.groupBy { listOf(it.ride, it.source, it.metric) }) {
            val ordered = series.sortedWith(compareBy({ it.elapsed }, { it.sequence }))
            val breaks = historyRunBreakTicks(markers[ordered[0].ride].orEmpty(), ordered[0].source)
            ordered.zipWithNext().forEach { (p, n) ->
                val step = n.elapsed - p.elapsed
                val move = kotlin.math.abs((n.epoch - n.elapsed) - (p.epoch - p.elapsed))
                val backwards = n.epoch < p.epoch
                val marked = breaks.any { it > p.elapsed && it <= n.elapsed }
                val others = listOf(p.reason != null && n.reason != null && p.reason == n.reason, p.generation == n.generation,
                    step <= 2000, move <= 15, !backwards, n.sequence > p.sequence, !marked)
                val names = listOf("reason", "generation", "step", "skew", "backwards", "sequence", "marker")
                val failing = others.indices.filter { !others[it] }
                if (failing.size == 1) causes.merge(names[failing.single()], 1, Int::plus)
            }
        }
        // `backwards` alone is unreachable with the skew clause true unless the skew moves against a small step.
        for (cause in listOf("reason", "generation", "step", "skew", "backwards", "sequence", "marker"))
            assertTrue((causes[cause] ?: 0) > 0, "no step split on $cause alone: $causes")
    }

    @Test fun outOfOrderChainSplitsOnItsSequences() = runBlocking {
        val driver = v28()
        val origin = HistoryTick(1_000_000, 0, 1)
        ride(driver, "ride", origin, ended = HistoryTick(1_002_000, 2_000, 1)); source(driver, "ride", "pack")
        // Elapsed order 0, 300, 600, 900; stored sequences 5, 3, 7, 8: the row at 300 was committed early.
        for ((ms, sequence) in listOf(0L to 5L, 300L to 3L, 600L to 7L, 900L to 8L))
            insert(driver, V2("ride", sequence, "pack", "voltage_v", -1, 1_000_000 + ms, ms, 1, null, "UNAVAILABLE"))
        migrate(driver)
        val rows = compactRows(driver)
        assertEquals(listOf(5L to null, 3L to 3L), rows.map { it.sequence to it.count })
        assertEquals(8L, rows[1].endSequence, "the second run keeps sequences that advance")

        val provider = VoltyDatabaseProvider(driver)
        val store = SqlDelightHistoryStore(provider)
        val key = HistorySeriesKey("pack", "voltage_v")
        val range = HistoryRange(0, Long.MAX_VALUE)
        assertEquals(listOf(0L, 300L, 600L, 900L), store.samples("ride", key, range, null).items.map { it.tick.elapsedMillis }, "every member reads back")
        // Pinned, a stated cost: V2 exported such a ride; a compact one is refused, because no stored order is left to merge
        // it in. A conversion cannot reorder what was recorded. The rider's database holds none (0 of 26 rides).
        val ride = checkNotNull(store.ride("ride"))
        val request = HistoryExportRequest(ride.favoriteSource(), HistoryRange(1_000_000, 1_002_000), HistoryExportFormat.LOGUI)
        val failure = assertFailsWith<HistoryExportException> { SqlDelightHistoryExportSource(provider).open(request).records.collect() }
        assertEquals(HistoryExportFailure.INVALID_DATA, failure.reason)
    }

    @Test fun incoherentUnknownReasonAbortsMigration() {
        val driver = v28()
        val origin = HistoryTick(1_000_000, 0, 1)
        ride(driver, "ride", origin); source(driver, "ride", "pack")
        insert(driver, V2("ride", 0, "pack", "voltage_v", -1, 1_000_000, 0, 1, 1.5, null))
        insert(driver, V2("ride", 1, "pack", "voltage_v", -1, 1_000_300, 300, 1, null, "BOGUS"))
        val before = v2Rows(driver)
        assertFails { migrate(driver) }
        assertEquals(before, v2Rows(driver), "the transaction rolled back")
        assertEquals(0L, long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3"))
        assertEquals(0L, long(driver, "SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(28L, long(driver, "PRAGMA user_version"))
        assertFalse(exists(driver, "HistoryConversionRow"))
    }

    /** A kill is a rollback at the next open. It is tried after EVERY statement, and the last try is a clean full run. */
    @Test fun killMidMigrationLeavesV2Intact() {
        val driver = v28()
        val origin = HistoryTick(1_000_000, 0, 1)
        ride(driver, "ride", origin); source(driver, "ride", "pack")
        for (i in 0L until 20L) insert(driver, V2("ride", i, "pack", "voltage_v", -1, 1_000_000 + i * 300, i * 300, 1, null, "UNAVAILABLE"))
        val before = v2Rows(driver)
        val tables = "SELECT name FROM sqlite_master ORDER BY name"
        val schema = query(driver, tables) { it.getString(0)!! }
        val statements = HistoryV2Fixture.conversionStatements()
        assertTrue(statements.size > 15, "${statements.size} statements")
        val database = VoltyDatabaseProvider(driver).database
        for (killAfter in 1..statements.size) {
            database.transaction {
                statements.take(killAfter).forEach { driver.execute(null, it, 0) }
                rollback()
            }
            assertEquals(before, v2Rows(driver), "after statement $killAfter")
            assertEquals(schema, query(driver, tables) { it.getString(0)!! }, "after statement $killAfter")
            assertEquals(0L, long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3"), "after statement $killAfter")
        }
        migrate(driver)
        assertEquals(20L, compactRows(driver).single().count)
    }

    private val NL = System.lineSeparator()

    /** Two realistic rides (T4 shape, ~47 k rows each, markers) so the planner sees tables of the size the conversion walks. */
    private fun seedForPlans(driver: JdbcSqliteDriver) = bulk(driver) {
        for (n in 0 until 2) {
            val id = "ride$n"
            val origin = HistoryTick(1_000_000L + n * 10_000_000L, n * 1000L, 1)
            ride(driver, id, origin); source(driver, id, "ctl"); source(driver, id, "pack")
            t4Rows(id, origin).forEach { insert(driver, it) }
            marker(driver, id, 900_000, 100_000, HistoryMarkerKind.BLE_GAP, "pack")
            marker(driver, id, 900_001, 200_000, HistoryMarkerKind.INTERRUPTED)
        }
    }

    /**
     * What the conversion's plan must be, on the SQLite that runs it (the JDBC one here; run this class against
     * `org.xerial:sqlite-jdbc:3.18.0`, the phone's floor, for the old planner). The disaster is not a temp B-tree but a SCAN of a
     * 1.3 M row table per outer row (~791 k x 1.3 M, a migration that never ends and so a database that never opens). So:
     * every correlated lookup (`p`, `e`, `f`, `x`, and the break probe `b`) is a SEARCH, and the only SCAN of an INSERT is its
     * one outer driving table. Planned after the earlier statements ran (the tables are real, as on the phone, with no
     * ANALYZE) and again after ANALYZE.
     */
    @Test fun conversionPlansSearchEveryCorrelatedLookup() {
        val driver = v28()
        seedForPlans(driver)
        val version = query(driver, "SELECT sqlite_version()") { it.getString(0)!! }.single()
        val statements = HistoryV2Fixture.conversionStatements()
        val database = VoltyDatabaseProvider(driver).database
        for (analyze in listOf(false, true)) {
            database.transaction {
                if (analyze) driver.execute(null, "ANALYZE", 0)
                val plans = ArrayList<Pair<String, List<String>>>()
                statements.forEach { statement ->
                    if (statement.startsWith("INSERT")) plans += statement to
                        query(driver, "EXPLAIN QUERY PLAN $statement") { it.getString(3)!! }
                    driver.execute(null, statement, 0)
                }
                assertEquals(8, plans.size, "every INSERT was explained")
                for ((statement, plan) in plans) println("PLAN sqlite=$version analyze=$analyze" + NL + statement.lines().first() + NL + "  " + plan.joinToString(NL + "  "))
                for ((statement, plan) in plans) {
                    val where = "sqlite $version, analyze=$analyze" + NL + statement + NL + plan.joinToString(NL)
                    // Series and keys: tens of rows per ride, not millions. Their sort is harmless and is left to the planner.
                    if ("INTO HistorySeriesV3" in statement || "INTO HistoryRideKeyV3" in statement) continue
                    assertFalse(plan.any { "USE TEMP B-TREE" in it }, where)
                    // With statistics a planner may scan the 4-row marker table per series instead of probing it; the app never runs ANALYZE
                    // (nothing in the sources does), so only the unanalysed plan, the phone's, is held to one SCAN.
                    if (!analyze) assertTrue(plan.count { it.startsWith("SCAN") } <= 1, "more than one SCAN, the driving table:" + NL + where)
                }
                fun planOf(marker: String) = plans.single { marker in it.first }.second
                fun needsSearch(plan: List<String>, vararg aliases: String) = aliases.forEach { alias ->
                    // 3.18 prints "SEARCH TABLE T AS p USING ...", a current SQLite "SEARCH p USING ...".
                    val search = Regex("^SEARCH (TABLE [^ ]+ AS )?$alias( .*)?$")
                    assertTrue(plan.any { search.matches(it) }, "no SEARCH on '$alias'" + NL + plan.joinToString(NL))
                }
                val conversion = planOf("INTO HistoryConversionRow")
                assertTrue(conversion.any { "HistorySampleV2_series_elapsed" in it }, conversion.joinToString(NL))
                needsSearch(planOf("INTO HistoryConversionHead"), "p", "b")
                needsSearch(planOf("INTO HistorySampleV3"), "f", "e", "x")
                rollback()
            }
        }
    }

    @Test fun legacyNullFingerprintReceiptsRemoved() {
        val driver = v28()
        ride(driver, "ride", HistoryTick(1_000_000, 0, 1))
        driver.execute(null, "INSERT INTO HistoryBatchReceiptV2(rideId, receiptId, firstSequence, nextSequence, fingerprint) VALUES " +
            "('ride', 'old', 0, 5, NULL), ('ride', 'new', 5, 9, 'abc')", 0)
        migrate(driver)
        assertEquals(listOf("new"), query(driver, "SELECT receiptId FROM HistoryBatchReceiptV2") { it.getString(0)!! })
    }

    @Test fun fingerprintedReceiptsKept() = runBlocking {
        val driver = v28()
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
        val origin = HistoryTick(1_000_000, 0, 1)
        store.begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING), listOf(HistorySource("pack", HistorySourceKind.BATTERY, "pack")))
        val batch = HistoryBatch("ride", listOf(HistorySample(origin, HistorySeriesKey("pack", "voltage_v"), 5.0)), emptyList(), emptyList(), "b1")
        store.append(batch)
        HistoryV2Fixture.insertSample(driver, "ride", 0, "pack", "voltage_v", -1, 1_000_000, 0, 1, 5.0, null)
        HistoryV2Fixture.dropCompactRows(driver)
        migrate(driver)
        assertEquals(1L, long(driver, "SELECT COUNT(*) FROM HistoryBatchReceiptV2"))
        // A retry of a committed batch is still answered by its receipt: nothing is appended twice.
        store.append(batch)
        assertEquals(1L, long(driver, "SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(listOf(5.0), store.samples("ride", batch.samples[0].key, HistoryRange(0, Long.MAX_VALUE), null).items.map { it.value })
    }

    @Test fun samplesWithoutARideAreNotConverted() {
        val driver = v28()
        ride(driver, "ride", HistoryTick(1_000_000, 0, 1)); source(driver, "ride", "pack")
        insert(driver, V2("ride", 0, "pack", "voltage_v", -1, 1_000_000, 0, 1, 1.5, null))
        insert(driver, V2("orphan", 0, "pack", "voltage_v", -1, 1_000_000, 0, 1, 2.5, null))
        migrate(driver)
        assertEquals(listOf("ride"), compactRows(driver).map { it.ride })
        assertEquals(1L, long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3"))
        assertEquals(1L, long(driver, "SELECT COUNT(*) FROM HistorySeriesV3"))
    }

    @Test fun aRideAlreadyCompactIsLeftAlone() = runBlocking {
        val driver = v28()
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
        val key = HistorySeriesKey("pack", "voltage_v")
        val origin = HistoryTick(2_000_000, 0, 1)
        // Older by start, recorded compact, so a conversion that renumbered rides would show here.
        store.begin(HistoryRide("compact", "bike", origin, null, HistoryRideStatus.RECORDING), listOf(HistorySource("pack", HistorySourceKind.BATTERY, "pack")))
        store.append(HistoryBatch("compact", listOf(HistorySample(origin, key, 5.0)), emptyList(), emptyList()))
        // Incoherent: a V2 row of the compact ride. Every reader ignored it, so converting it would make a sample appear.
        insert(driver, V2("compact", 77, "pack", "voltage_v", -1, 2_000_000, 0, 1, 99.0, null))
        ride(driver, "old", HistoryTick(1_000_000, 0, 1)); source(driver, "old", "pack")
        insert(driver, V2("old", 0, "pack", "voltage_v", -1, 1_000_000, 0, 1, 1.5, null))
        val keys = query(driver, "SELECT seriesId FROM HistorySeriesV3") { it.getLong(0)!! }
        migrate(driver)
        assertEquals(listOf(5.0), store.samples("compact", key, HistoryRange(0, Long.MAX_VALUE), null).items.map { it.value })
        assertEquals(listOf(1.5), store.samples("old", key, HistoryRange(0, Long.MAX_VALUE), null).items.map { it.value })
        assertEquals(1L, long(driver, "SELECT rideNo FROM HistoryRideKeyV3 WHERE rideId = 'compact'"))
        assertEquals(2L, long(driver, "SELECT rideNo FROM HistoryRideKeyV3 WHERE rideId = 'old'"))
        assertEquals(keys + 2L, query(driver, "SELECT seriesId FROM HistorySeriesV3 ORDER BY seriesId") { it.getLong(0)!! })
    }

    /** A process killed mid-ride on v28, upgraded: the ride is converted, then closed through the compact path, and a new ride fits. */
    @Test fun aRideLeftRecordingIsClosedAfterConversionAndNewIdsDoNotCollide() = runBlocking {
        val driver = v28()
        val origin = HistoryTick(1_000_000, 0, 1)
        ride(driver, "open", origin, "RECORDING", null); source(driver, "open", "pack")
        for (i in 0L until 10L) insert(driver, V2("open", i, "pack", "current_a", -1, 1_000_000 + i * 300, i * 300, 1, null, "UNAVAILABLE"))
        driver.execute(null, "UPDATE HistoryRideV2 SET nextSequence = 10, lastEpochMillis = 1002700, lastElapsedMillis = 2700 WHERE id = 'open'", 0)
        migrate(driver)
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
        assertEquals(1, store.recoverInterrupted())
        assertEquals(HistoryRideStatus.INTERRUPTED, store.ride("open")?.status)
        val key = HistorySeriesKey("pack", "current_a")
        assertEquals(10, store.samples("open", key, HistoryRange(0, Long.MAX_VALUE), null).items.size)
        assertEquals(listOf(9L, null), compactRows(driver).map { it.count }, "the INTERRUPTED marker on the last tick cut the run, as live")

        val next = HistoryTick(2_000_000, 0, 2)
        store.begin(HistoryRide("next", "bike", next, null, HistoryRideStatus.RECORDING), listOf(HistorySource("pack", HistorySourceKind.BATTERY, "pack")))
        store.append(HistoryBatch("next", listOf(HistorySample(next, key, 1.0)), emptyList(), emptyList()))
        assertEquals(2L, long(driver, "SELECT COUNT(*) FROM HistorySeriesV3"))
        assertEquals(listOf(1.0), store.samples("next", key, HistoryRange(0, Long.MAX_VALUE), null).items.map { it.value })
    }

    @Test fun readersGetExactlyTheRowsOfAConvertedRide() = runBlocking {
        val driver = v28()
        val origin = HistoryTick(1_000_000, 100, 1)
        ride(driver, "ride", origin, ended = HistoryTick(1_100_000, 100_100, 1)); source(driver, "ride", "pack")
        // An even step, so interpolated interior members are the real ones and the whole read is comparable bit for bit.
        val rows = (0L until 900L).map { i ->
            val unknown = i % 40 in 10L..30L
            V2("ride", i, "pack", "voltage_v", -1, 1_000_000 + i * 100, 100 + i * 100, 1, if (unknown) null else 50.0 + i * 0.1, if (unknown) "PARTIAL_SOURCE" else null)
        }
        rows.forEach { insert(driver, it) }
        marker(driver, "ride", 1000, 100 + 215, HistoryMarkerKind.BLE_GAP, "pack")
        migrate(driver)
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
        val key = HistorySeriesKey("pack", "voltage_v")
        for (size in listOf(1, 7, 20000)) {
            val read = ArrayList<HistorySample>()
            var after: HistoryCursor? = null
            do {
                val page = store.samples("ride", key, HistoryRange(1_000_000, 1_100_000), after, size)
                read += page.items; after = page.next
            } while (after != null)
            assertEquals(rows.map(::sample), read, "page size $size")
        }
        assertTrue(compactRows(driver).size < rows.size)
    }

    /**
     * The gate's measurements, on a COPY: `-Dvolty.migrationDb=<v27 file> -Dvolty.migrationWork=<dir>`, skipped without them.
     * Prints sizes and counts only, never a coordinate. Migrates as `onUpgrade` does (one transaction), 27 to 28 to 29.
     */
    @Test fun realDatabaseMigration() {
        val source = System.getProperty("volty.migrationDb")
        val work = System.getProperty("volty.migrationWork")
        if (source.isNullOrBlank() || work.isNullOrBlank()) {
            println("SKIPPED realDatabaseMigration: set -Dvolty.migrationDb and -Dvolty.migrationWork")
            assumeTrue("volty.migrationDb / volty.migrationWork not set", false)
        }
        val copy = File(work, "migration-${System.nanoTime()}.db")
        File(source).copyTo(copy)
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            fun pages() = "pages=${long(driver, "PRAGMA page_count")} free=${long(driver, "PRAGMA freelist_count")} " +
                "autoVacuum=${long(driver, "PRAGMA auto_vacuum")} file=${copy.length()}"
            val version = long(driver, "PRAGMA user_version")
            assertEquals(27L, version)
            val rows = long(driver, "SELECT COUNT(*) FROM HistorySampleV2")
            println("[migration] before: ${pages()} v2Rows=$rows rides=${long(driver, "SELECT COUNT(*) FROM HistoryRideV2")}")
            val orphans = long(driver, "SELECT COUNT(*) FROM HistorySampleV2 WHERE rideId NOT IN (SELECT id FROM HistoryRideV2)")
            val originalPages = long(driver, "PRAGMA page_count")
            val started = System.nanoTime()
            // The statements Schema.migrate runs, one by one in ONE transaction, so the peak file and rollback-journal sizes
            // can be read between them: the free space a phone needs. The commit's own auto-vacuum moves are not seen here.
            val journal = File(copy.path + "-journal")
            var peakPages = 0L; var peakJournal = 0L
            VoltyDatabaseProvider(driver).database.transaction {
                VoltyDatabase.Schema.migrate(driver, version, 28)
                for (statement in HistoryV2Fixture.conversionStatements()) {
                    driver.execute(null, statement, 0)
                    peakPages = maxOf(peakPages, long(driver, "PRAGMA page_count"))
                    peakJournal = maxOf(peakJournal, journal.length())
                }
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            println("[migration] peak: pages=$peakPages growth=${(peakPages - originalPages) * 4096} B journal=$peakJournal B")
            driver.execute(null, "PRAGMA user_version = 29", 0)
            val runs = long(driver, "SELECT COUNT(*) FROM HistorySampleV3 WHERE runCount IS NOT NULL")
            val total = long(driver, "SELECT COUNT(*) FROM HistorySampleV3")
            val members = long(driver, "SELECT COALESCE(SUM(COALESCE(runCount, 1)), 0) FROM HistorySampleV3")
            println("[migration] after: ${pages()} millis=$millis orphans=$orphans v3Rows=$total runRows=$runs " +
                "members=$members rides=${long(driver, "SELECT COUNT(*) FROM HistoryRideKeyV3")} " +
                "series=${long(driver, "SELECT COUNT(*) FROM HistorySeriesV3")} " +
                "nonZeroSkew=${long(driver, "SELECT COUNT(*) FROM HistorySampleV3 WHERE epochSkew != 0")}")
            assertEquals(rows - orphans, members, "every V2 row is a stored row or a member of a run")
            println("[migration] integrity=${query(driver, "PRAGMA integrity_check") { it.getString(0)!! }}")
            driver.execute(null, "VACUUM", 0)
            println("[migration] vacuumed: ${pages()}")
            println("[migration] integrity=${query(driver, "PRAGMA integrity_check") { it.getString(0)!! }}")
            driver.close()
        } finally {
            copy.delete(); File(copy.path + "-journal").delete()
        }
    }
}
