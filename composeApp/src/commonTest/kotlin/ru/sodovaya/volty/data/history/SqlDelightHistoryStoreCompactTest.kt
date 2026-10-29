package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BegodeT4Fixture
import ru.sodovaya.volty.data.db.HistorySampleV3
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.TelemetryObservation
import kotlin.test.*

/** Task 5 of the compact-history-storage plan: new rides record into the compact (V3) tables. */
class SqlDelightHistoryStoreCompactTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val origin = HistoryTick(1_000_000, 100, 1)
    private val pack = HistorySeriesKey("pack", "voltage_v")
    private val packCurrent = HistorySeriesKey("pack", "current_a")
    private val ctl = HistorySeriesKey("ctl", "speed_kmh")
    private val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack"),
        HistorySource("ctl", HistorySourceKind.CONTROLLER, "Wheel"))
    private fun tick(ms: Long, skew: Long = 0, generation: Long = 1) =
        HistoryTick(origin.epochMillis + ms + skew, origin.elapsedMillis + ms, generation)
    private fun u(ms: Long, key: HistorySeriesKey = pack, reason: HistoryUnknownReason = HistoryUnknownReason.UNAVAILABLE,
        skew: Long = 0) = HistorySample(tick(ms, skew), key, null, reason)
    private fun k(ms: Long, value: Double, key: HistorySeriesKey = pack) = HistorySample(tick(ms), key, value)

    private class Fixture(val driver: JdbcSqliteDriver, val provider: VoltyDatabaseProvider, compact: Boolean) {
        val db = provider.database
        val store = SqlDelightHistoryStore(provider, recordCompact = compact)
        fun count(sql: String): Long = driver.executeQuery(null, sql, { c ->
            check(c.next().value); QueryResult.Value(checkNotNull(c.getLong(0)))
        }, 0).value
        fun rows(rideId: String = "ride", key: HistorySeriesKey): List<HistorySampleV3> {
            val q = db.rideHistoryCompactQueries
            val no = q.rideNoV3(rideId).executeAsOne()
            val id = q.seriesForV3(no, key.sourceId, key.metricId, key.cellIndex?.toLong() ?: -1L).executeAsOne()
            return q.samplesOfSeriesV3(id).executeAsList()
        }
    }
    private fun fixture(compact: Boolean = true): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        VoltyDatabase.Schema.create(driver)
        return Fixture(driver, VoltyDatabaseProvider(driver), compact)
    }
    private suspend fun Fixture.begin(rideId: String = "ride") =
        store.begin(HistoryRide(rideId, "wheel", origin, null, HistoryRideStatus.RECORDING), sources)
    private suspend fun Fixture.append(samples: List<HistorySample>, markers: List<HistoryMarker> = emptyList(),
        receipt: String? = null, rideId: String = "ride") =
        store.append(HistoryBatch(rideId, samples, emptyList(), markers, receipt))
    @AfterTest fun close() { drivers.forEach { it.close() } }

    @Test fun newRideGetsKeyAndNoV2Rows() = runTest {
        val f = fixture(); f.begin()
        f.append((0L until 6L).map { k(it * 300, 60.0 + it) } + (0L until 4L).map { k(it * 300, 10.0 + it, ctl) })
        assertEquals(1L, f.count("SELECT COUNT(*) FROM HistoryRideKeyV3"))
        assertEquals(10L, f.count("SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(0L, f.count("SELECT COUNT(*) FROM HistorySampleV2"))
        assertEquals(2L, f.count("SELECT COUNT(*) FROM HistorySeriesV3"), "one series per key")
        assertEquals(6, f.rows(key = pack).size); assertEquals(4, f.rows(key = ctl).size)
        // The rest of the ride is still V2: sequence, receipts and last tick advance exactly as before.
        assertEquals(10L, f.db.rideHistoryV2Queries.getRide("ride").executeAsOne().nextSequence)
    }

    @Test fun untilTheReadersLandANewRideStaysV2ByDefault() = runTest {
        val f = fixture(compact = false); f.begin()
        f.append(listOf(k(0, 60.0), u(300)))
        assertEquals(0L, f.count("SELECT COUNT(*) FROM HistoryRideKeyV3"))
        assertEquals(0L, f.count("SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(2L, f.count("SELECT COUNT(*) FROM HistorySampleV2"))
        // A ride begun compact stays compact for a store whose flag is off: dispatch is on the key.
        val g = fixture(); g.begin()
        SqlDelightHistoryStore(g.provider).append(HistoryBatch("ride", listOf(k(0, 1.0)), emptyList(), emptyList()))
        assertEquals(1L, g.count("SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(0L, g.count("SELECT COUNT(*) FROM HistorySampleV2"))
    }

    @Test fun deadSeriesBecomesOneRun() = runTest {
        val f = fixture(); f.begin()
        // 2,752 UNAVAILABLE ticks 279-323 ms apart (the T4's dead series), interleaved with a live series of another
        // source so the run's sequences are NOT 0..2751 (incoherent with "sequence = index").
        var ms = 0L; var lastMs = 0L
        val all = (0 until 2752).flatMap { i ->
            lastMs = ms
            val pair = listOf(u(ms), k(ms + 1, i.toDouble(), ctl))
            ms += 279 + (i * 37) % 45
            pair
        }
        all.chunked(128).forEachIndexed { i, batch -> f.append(batch, receipt = "b$i") }
        val row = f.rows(key = pack).single()
        assertEquals(2752L, row.runCount)
        assertEquals(origin.elapsedMillis, row.elapsedMillis)
        assertEquals(origin.elapsedMillis + lastMs, row.runEndElapsed)
        assertEquals(2L * 2751, row.runEndSequence, "the last pack sample's own sequence")
        assertEquals(HistoryCompactCodec.code(HistoryUnknownReason.UNAVAILABLE), row.unknownCode)
        assertEquals(2752, f.rows(key = ctl).size)
    }

    @Test fun markerInLaterBatchSplitsRun() = runTest {
        suspend fun runWith(marker: HistoryMarker?): Int {
            val f = fixture(); f.begin()
            f.append(listOf(u(0), u(300)))
            f.append(emptyList(), listOfNotNull(marker))
            f.append(listOf(u(600)))
            return f.rows(key = pack).size
        }
        assertEquals(2, runWith(HistoryMarker(tick(450), HistoryMarkerKind.BLE_GAP, "pack")))
        // Controls: the path is reached and only an interrupting marker of THIS source splits.
        assertEquals(1, runWith(null))
        assertEquals(1, runWith(HistoryMarker(tick(450), HistoryMarkerKind.BLE_GAP, "ctl")))
        assertEquals(1, runWith(HistoryMarker(tick(450), HistoryMarkerKind.MANUAL_STOP)))
        assertEquals(2, runWith(HistoryMarker(tick(450), HistoryMarkerKind.CONNECTED)))
        // A marker of the SAME batch, written after the open run, splits it too.
        val f = fixture(); f.begin()
        f.append(listOf(u(0), u(300)))
        f.append(listOf(u(600)), listOf(HistoryMarker(tick(450), HistoryMarkerKind.BLE_GAP, "pack")))
        assertEquals(2, f.rows(key = pack).size)
    }

    @Test fun knownValueBetweenUnknownsIsItsOwnRow() = runTest {
        val f = fixture(); f.begin()
        val reading = 0.1 + 0.2
        f.append(listOf(u(0), u(300), k(600, reading), u(900), u(1200)))
        val rows = f.rows(key = pack)
        assertEquals(3, rows.size)
        assertEquals(listOf(2L, null, 2L), rows.map { it.runCount })
        assertEquals(reading.toRawBits(), checkNotNull(rows[1].reading).toRawBits())
        assertNull(rows[1].unknownCode)
        // A changed reason is information and splits as well.
        f.append(listOf(u(1500, reason = HistoryUnknownReason.PARTIAL_SOURCE), u(1800, reason = HistoryUnknownReason.PARTIAL_SOURCE)))
        assertEquals(listOf(2L, null, 2L, 2L), f.rows(key = pack).map { it.runCount })
    }

    @Test fun incoherentSkewIsStoredExactly() = runTest {
        val f = fixture(); f.begin()
        val off = k(0, 1.0).copy(tick = tick(0, skew = 7))
        f.append(listOf(off, u(300), u(600, skew = 9), u(900, skew = 30)))
        val rows = f.rows(key = pack)
        assertEquals(7L, rows[0].epochSkew)
        assertEquals(off.tick, HistoryCompactCodec.decode(rows[0], pack, origin).sample.tick)
        // A wobble within 15 ms of the run's FIRST member extends it; 30 ms does not.
        assertEquals(listOf(null, 2L, null), rows.map { it.runCount })
        assertEquals(30L, rows[2].epochSkew)
        assertEquals(tick(900, skew = 30), HistoryCompactCodec.decode(rows[2], pack, origin).sample.tick)
    }

    @Test fun anotherClockGenerationGetsItsDeltaAndNeverExtendsARun() {
        // append refuses another generation (admit), so this drives the writer itself, as a conversion would.
        val f = fixture()
        val writer = HistoryCompactSampleWriter(f.db)
        f.db.transaction {
            writer.begin("ride")
            val session = writer.session("ride", checkNotNull(writer.rideNo("ride")), origin, emptyList())
            session.write(u(0), 0); session.write(u(300), 1)
            session.write(HistorySample(tick(600, generation = 2), pack, null, HistoryUnknownReason.UNAVAILABLE), 2)
        }
        val rows = f.rows(key = pack)
        assertEquals(listOf(0L, 1L), rows.map { it.clockDelta })
        assertEquals(listOf(2L, null), rows.map { it.runCount })
        assertEquals(tick(600, generation = 2), HistoryCompactCodec.decode(rows[1], pack, origin).sample.tick)
    }

    @Test fun aRunViolatingTheSpanBoundIsNeverExtendedAndAnUnknownCodeNeverJoins() {
        val f = fixture()
        val writer = HistoryCompactSampleWriter(f.db)
        val q = f.db.rideHistoryCompactQueries
        f.db.transaction {
            writer.begin("ride")
            val session = writer.session("ride", checkNotNull(writer.rideNo("ride")), origin, emptyList())
            session.write(u(0), 0)
            // Corrupt the lone row into a 2-member run spanning 4500 ms (> 2000 * 1): CHECK allows it, the writer must not
            // make it worse. The next step (100 ms) is within every rule of historyRunContinues.
            q.extendRunV3(2, origin.elapsedMillis + 4500, 1, 1, origin.elapsedMillis, 0)
            session.write(u(4600), 2)
            // A code this build does not know reads as invalid data and is never extended.
            session.write(u(6000, ctl), 3)
            f.driver.execute(null, "UPDATE HistorySampleV3 SET unknownCode = 99 WHERE sequence = 3", 0)
            session.write(u(6300, ctl), 4)
        }
        assertEquals(listOf(2L, null), f.rows(key = pack).map { it.runCount })
        assertEquals(listOf(null, null), f.rows(key = ctl).map { it.runCount })
        assertEquals(HistoryUnknownReason.INVALID_VALUE,
            HistoryCompactCodec.decode(f.rows(key = ctl)[0], ctl, origin).sample.unknown)
    }

    @Test fun codecIsAFixedTableAndRefusesANonFiniteReading() {
        val codes = HistoryUnknownReason.entries.map(HistoryCompactCodec::code)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), codes)
        assertEquals(HistoryCompactCodec.MAX_UNKNOWN_CODE, codes.max())
        HistoryUnknownReason.entries.forEach { assertEquals(it, HistoryCompactCodec.reasonOrNull(HistoryCompactCodec.code(it))) }
        assertNull(HistoryCompactCodec.reasonOrNull(0)); assertNull(HistoryCompactCodec.reasonOrNull(6))
        assertFailsWith<IllegalArgumentException> { HistoryCompactCodec.reading(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { HistoryCompactCodec.reading(Double.POSITIVE_INFINITY) }
    }

    @Test fun receiptRetryAfterRunExtensionIsIdempotent() = runTest {
        val f = fixture(); f.begin()
        f.append(listOf(u(0)), receipt = "a")
        val b = listOf(u(300), u(600))
        f.append(b, receipt = "b")
        assertEquals(3L, f.rows(key = pack).single().runCount)
        f.append(b, receipt = "b")
        assertEquals(3L, f.rows(key = pack).single().runCount)
        assertEquals(1L, f.count("SELECT COUNT(*) FROM HistorySampleV3"))
        val e = assertFailsWith<IllegalArgumentException> { f.append(listOf(u(300), u(900)), receipt = "b") }
        assertEquals("Receipt reused with a different payload", e.message)
        assertEquals(3L, f.rows(key = pack).single().runCount)
        // A fingerprint-less (legacy) receipt cannot exist for a compact ride; it is refused, not compared to rows.
        f.db.rideHistoryReceiptQueries.insertReceipt("ride", "legacy", 0, 1, null)
        assertFailsWith<IllegalStateException> { f.append(listOf(u(0)), receipt = "legacy") }
    }

    @Test fun deleteRemovesV3Rows() = runTest {
        val f = fixture(); f.begin("one"); f.begin("two")
        f.append(listOf(u(0), u(300), k(600, 1.0, ctl)), rideId = "one")
        f.append(listOf(u(0), k(600, 2.0, ctl)), rideId = "two")
        f.store.delete("one")
        assertEquals(2L, f.count("SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(2L, f.count("SELECT COUNT(*) FROM HistorySeriesV3"))
        assertEquals(1L, f.count("SELECT COUNT(*) FROM HistoryRideKeyV3"))
        // The user-facing deletion path does the same for a closed compact ride.
        f.store.finish("two", tick(700), HistoryRideStatus.COMPLETED)
        val deletion = SqlDelightHistoryRideDeletion(f.provider)
        deletion.confirm(deletion.prepare(checkNotNull(f.store.ride("two"))))
        listOf("HistorySampleV3", "HistorySeriesV3", "HistoryRideKeyV3").forEach {
            assertEquals(0L, f.count("SELECT COUNT(*) FROM $it"), it)
        }
        // Ids are reused MAX+1 and a fresh ride starts clean.
        f.begin("three"); f.append(listOf(u(0)), rideId = "three")
        assertEquals(1L, f.count("SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(1, f.rows("three", pack).size)
    }

    // ---- frames -> observations -> projection -> store, recorded twice: V2 and compact ----------------------------

    private fun t4Observations(repeats: Int): List<TelemetryObservation> {
        val protocol = BegodeProtocol(cellCount = 24)
        val observations = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = { observations += it }
        repeat(repeats) { BegodeT4Fixture.notifications.forEach { protocol.onNotification(it) } }
        return observations
    }

    @Test fun aT4ReplayStoresTheSameSeriesInBothLayouts() = runTest {
        val observations = t4Observations(repeats = 200)
        val kinds = observations.map { historySourceKindForTelemetry(it.origin, it.role) }.distinct()
        val replaySources = kinds.map { HistorySource(it.name.lowercase(), it, it.name) }
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        val v2 = fixture(compact = false); val v3 = fixture()
        listOf(v2, v3).forEach { it.store.begin(ride, replaySources) }
        // ~55 ms per observation with a 0-1 ms wall/monotonic wobble, as HistoryRecordingClock reads them.
        val samples = observations.flatMapIndexed { i, observation ->
            val kind = historySourceKindForTelemetry(observation.origin, observation.role)
            val ms = i * 55L
            historyTelemetrySamples(replaySources.single { it.kind == kind }, tick(ms, skew = (i % 2).toLong()), observation)
        }
        val gapAt = samples[samples.size / 2].tick.elapsedMillis - origin.elapsedMillis + 1
        val gap = HistoryMarker(tick(gapAt), HistoryMarkerKind.BLE_GAP, replaySources.first().id)
        var projection = HistoryRecordingProjection(ride, replaySources)
        val chunks = samples.chunked(128)
        chunks.forEachIndexed { i, chunk ->
            val markers = if (chunk.any { it.tick.elapsedMillis >= gap.tick.elapsedMillis } &&
                chunks.take(i).none { c -> c.any { it.tick.elapsedMillis >= gap.tick.elapsedMillis } }) listOf(gap) else emptyList()
            val prepared = projection.prepare(HistoryBatch("ride", chunk, emptyList(), markers, "r$i"), i == chunks.lastIndex)
            projection = prepared.next
            v2.store.append(prepared.batch); v3.store.append(prepared.batch)
        }
        val v2Rows = v2.db.rideHistorySampleQueries.samplesForReceipt("ride", 0, Long.MAX_VALUE).executeAsList()
        assertTrue(v2Rows.size > 1000, "the replay must reach the store, got ${v2Rows.size}")
        val markers = v2.store.markers("ride", HistoryRange(0, Long.MAX_VALUE))
        assertEquals(listOf(gap), markers)
        var runs = 0; var v3Count = 0
        v2Rows.groupBy { HistorySeriesKey(it.sourceId, it.metricId, it.cellIndex.takeIf { c -> c >= 0 }?.toInt()) }
            .forEach { (key, list) ->
                val ordered = list.sortedWith(compareBy({ it.elapsedMillis }, { it.sequence }))
                val full = ordered.map {
                    HistorySample(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration), key, it.reading,
                        it.unknownReason?.let(HistoryUnknownReason::valueOf))
                }
                // What the pure model says V2 collapses to, with the store's own sequences instead of indices.
                val expected = collapseUnknownRuns(full, markers).map { row ->
                    row.copy(sequence = ordered[row.sequence.toInt()].sequence,
                        run = row.run?.let { it.copy(sequence = ordered[it.sequence.toInt()].sequence) })
                }
                val stored = v3.rows(key = key).map { HistoryCompactCodec.decode(it, key, origin) }
                v3Count += stored.size
                assertEquals(expected.size, stored.size, "$key")
                expected.zip(stored).forEach { (e, s) ->
                    assertEquals(e.sample.tick, s.sample.tick, "$key"); assertEquals(e.sample.unknown, s.sample.unknown)
                    assertEquals(e.sample.value?.toRawBits(), s.sample.value?.toRawBits()); assertEquals(e.sequence, s.sequence)
                    assertEquals(e.run?.count, s.run?.count, "$key"); assertEquals(e.run?.sequence, s.run?.sequence)
                    assertEquals(e.run?.tick?.elapsedMillis, s.run?.tick?.elapsedMillis)
                    if (s.run != null) runs++
                }
                // Readers see the same number of samples at the same positions, and every known one bit-equal.
                val expanded = expandForReaders(stored)
                assertEquals(full.size, expanded.size, "$key")
                full.zip(expanded).forEach { (a, b) ->
                    assertEquals(a.unknown, b.unknown)
                    if (a.value != null) { assertEquals(a.tick, b.tick); assertEquals(a.value.toRawBits(), b.value?.toRawBits()) }
                }
            }
        assertTrue(runs > 0, "the replay must produce at least one run")
        assertTrue(v3Count < v2Rows.size, "compaction happened: $v3Count of ${v2Rows.size}")
        assertEquals(0L, v3.count("SELECT COUNT(*) FROM HistorySampleV2"))
    }
}
