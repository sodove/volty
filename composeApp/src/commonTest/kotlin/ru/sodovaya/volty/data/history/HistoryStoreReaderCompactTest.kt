package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

/**
 * Task 6 of the compact-history-storage plan: every `HistoryStore` reader answers a compact (V3) ride as V2 answered the
 * same recording. Where the brief and Ruling 2 disagree (runs expand to `count` samples, so a page may end inside a run),
 * the test follows the ruling and says so.
 *
 * Retraction: each test wrote ONE stream through a V2 store and a compact store and compared them. 28.sqm dropped V2, so
 * the reference is now [Fixture.rows], the samples handed to `append`: what V2 stored, in its order. [RowsStore] answers
 * `samples` from them for the walk. `stats` itself has no reference left, but it reads only [HistoryCompactReader.detail],
 * whose every field is compared with V2's query semantics here, and the real-database fingerprint compared every reader.
 */
class HistoryStoreReaderCompactTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val origin = HistoryTick(1_000_000, 0, 1)
    private val voltage = HistorySeriesKey("pack", "voltage_v")
    private val dead = HistorySeriesKey("pack", "current_a")
    private val speed = HistorySeriesKey("ctl", "speed_kmh")
    private val auxVoltage = HistorySeriesKey("aux", "voltage_v")
    private val keys = listOf(voltage, dead, speed)
    private val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack"),
        HistorySource("ctl", HistorySourceKind.CONTROLLER, "Wheel"), HistorySource("aux", HistorySourceKind.BATTERY, "Aux"))
    private fun tick(ms: Long) = HistoryTick(origin.epochMillis + ms, origin.elapsedMillis + ms, origin.clockGeneration)
    private fun axis(ms: Long) = origin.epochMillis + ms
    private val whole = HistoryRange(origin.epochMillis, Long.MAX_VALUE)

    private class Fixture(val provider: VoltyDatabaseProvider, dispatcher: CoroutineDispatcher) {
        val db = provider.database
        val store = SqlDelightHistoryStore(provider, dispatcher)
        val rows = ArrayList<HistorySample>()
        /** One series as V2 read it: `(elapsed, sequence)` order, and a sequence is the place in the append order. */
        fun full(key: HistorySeriesKey) = rows.filter { it.key == key }.sortedBy { it.tick.elapsedMillis }
    }
    private fun fixture(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        VoltyDatabase.Schema.create(driver)
        return Fixture(VoltyDatabaseProvider(driver), dispatcher)
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }

    /** Answers `samples` from the handed-over rows, as V2 paged them; everything else is the compact store's. */
    private inner class RowsStore(private val f: Fixture) : HistoryStore by f.store {
        override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> {
            val inRange = f.full(key).filter { historyAxisEpochMillis(origin, it.tick)!! in range.startEpochMillis..range.endEpochMillis }
            val from = after?.stableId?.toInt()?.plus(1) ?: 0
            val page = inRange.drop(from).take(limit)
            val more = from + page.size < inRange.size
            return HistoryPage(page, if (more) HistoryCursor(historyAxisEpochMillis(origin, page.last().tick)!!, (from + page.size - 1).toString()) else null, more)
        }
    }

    private class Recording(val samples: List<HistorySample>, val markers: List<HistoryMarker>, val endMs: Long)

    /**
     * T4-shaped: 856 s at 279-323 ms (uneven, so interior members of a run are interpolated, not the real ticks), a
     * known voltage, a dead current (one run, cut by a BLE_GAP of its source at 400.123 s), and a speed that alternates
     * between blocks of 50 PARTIAL_SOURCE and known values with a lone PARTIAL_SOURCE every 7th known tick.
     * [even] uses a fixed 300 ms step instead, where expand-then-clip is EXACTLY V2 even on a range that cuts a run.
     */
    private fun stream(even: Boolean = false, endMs: Long = 856_000): Recording {
        val samples = ArrayList<HistorySample>()
        var ms = 0L; var i = 0
        while (ms <= endMs) {
            samples += HistorySample(tick(ms), voltage, 60.0 + (i % 10) * 0.1)
            samples += HistorySample(tick(ms), dead, null, HistoryUnknownReason.UNAVAILABLE)
            samples += if ((i / 50) % 3 == 1 || i % 7 == 3) HistorySample(tick(ms), speed, null, HistoryUnknownReason.PARTIAL_SOURCE)
                else HistorySample(tick(ms), speed, 10.0 + (i % 13))
            ms += if (even) 300 else 279L + (i * 37) % 45
            i++
        }
        val gapAt = if (even) 30_150L else 400_123L
        return Recording(samples, listOf(HistoryMarker(tick(gapAt), HistoryMarkerKind.BLE_GAP, "pack")), ms)
    }

    private suspend fun record(recording: Recording, dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): Fixture {
        val f = fixture(dispatcher)
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        f.store.begin(ride, sources)
        val gap = recording.markers.single()
        val chunks = recording.samples.chunked(128)
        // The marker travels in the batch that first reaches it, as the recorder commits it.
        val carrier = chunks.indexOfFirst { chunk -> chunk.any { it.tick.elapsedMillis >= gap.tick.elapsedMillis } }
        f.appendProjected(ride, chunks.mapIndexed { index, chunk -> chunk to if (index == carrier) listOf(gap) else emptyList() })
        assertEquals(recording.markers, f.store.markers("ride", whole))
        f.store.finish("ride", tick(recording.endMs), HistoryRideStatus.COMPLETED)
        return f
    }

    /** Through the recorder's projection, as production writes: it also writes the summary buckets `stats` needs. */
    private suspend fun Fixture.appendProjected(ride: HistoryRide, batches: List<Pair<List<HistorySample>, List<HistoryMarker>>>) {
        var projection = HistoryRecordingProjection(ride, sources)
        batches.forEachIndexed { i, (samples, markers) ->
            val prepared = projection.prepare(HistoryBatch("ride", samples, emptyList(), markers, "r$i"), i == batches.lastIndex)
            projection = prepared.next
            rows += prepared.batch.samples
            store.append(prepared.batch)
        }
    }

    private suspend fun pages(store: HistoryStore, key: HistorySeriesKey, range: HistoryRange, size: Int): List<HistoryPage<HistorySample>> {
        val result = ArrayList<HistoryPage<HistorySample>>()
        var after: HistoryCursor? = null
        while (true) {
            val page = store.samples("ride", key, range, after, size)
            assertTrue(page.items.size <= size)
            result += page
            val next = page.next ?: break
            after?.let { previous ->
                assertTrue(next.epochMillis > previous.epochMillis || (next.epochMillis == previous.epochMillis &&
                    next.stableId.toLong() > previous.stableId.toLong()), "the cursor advances")
            }
            after = next
        }
        return result
    }

    private fun lines(samples: List<HistorySample>) = samples.map {
        "${it.tick.elapsedMillis},${it.tick.epochMillis},${it.tick.clockGeneration},${it.value?.toRawBits()}|${it.unknown}"
    }

    @Test fun samplesPagesEqualToCollapsedV2() = runTest {
        val recording = stream()
        val v3 = record(recording)
        val markers = v3.store.markers("ride", whole)
        // A cut edge: a range that starts and ends inside runs of the dead series (and of a speed block).
        val cut = HistoryRange(axis(100_001), axis(200_001))
        for (key in keys) {
            val full = v3.full(key)
            val expected = expandForReaders(collapseUnknownRuns(full, markers))
            assertEquals(full.size, expected.size, "$key: a run reads back as its count")
            for (size in listOf(1, 2, 3, 7, 20000)) {
                val read = pages(v3.store, key, whole, size)
                assertEquals(lines(expected), lines(read.flatMap { it.items }), "$key at page size $size")
                // Every page but the last is full, so pages of the dead series end INSIDE its runs (Ruling 2: a run of
                // ~1,400 members cannot be kept whole on a page of 1..20000) and the next page resumes on the next member.
                assertEquals((expected.size + size - 1) / size, read.size, "$key at page size $size")
            }
            // Expand first, clip second (Ruling 4): the same members, clipped to the range, at any page size.
            val clipped = expected.filter { historyAxisEpochMillis(origin, it.tick)!! in cut.startEpochMillis..cut.endEpochMillis }
            for (size in listOf(1, 7, 20000)) {
                assertEquals(lines(clipped), lines(pages(v3.store, key, cut, size).flatMap { it.items }), "$key cut at $size")
            }
        }
        // The known rows are bit-equal to what was handed over, and the uneven run's interior really is interpolated (not vacuous).
        val v3Dead = pages(v3.store, dead, whole, 20000).flatMap { it.items }
        assertNotEquals(lines(v3.full(dead)), lines(v3Dead))
        assertEquals(lines(v3.full(voltage)), lines(pages(v3.store, voltage, whole, 20000).flatMap { it.items }))
    }

    @Test fun anEvenStreamPagesEqualV2ForEveryRange() = runTest {
        val v3 = record(stream(even = true, endMs = 120_000))
        for (key in keys) for (from in 0L..120_000L step 2_350) for (span in listOf(0L, 150L, 300L, 1_000L, 17_000L, 200_000L)) {
            val range = HistoryRange(axis(from), axis(from + span))
            val expected = v3.full(key).filter { it.tick.elapsedMillis in from..(from + span) }
            assertEquals(lines(expected), lines(pages(v3.store, key, range, 7).flatMap { it.items }), "$key [$from, +$span]")
        }
    }

    @Test fun detailEqualsV2OnAnEvenStreamForEveryRange() = runTest {
        // The SQL reduction plus the two clipped runs, against V2's row-by-row query (MIN, MAX, COUNT(unknownReason) and
        // MAX(elapsed) of the rows in range). On an even stream expand-then-clip is exact, so every field must match:
        // unknown MEMBERS in range and the last in-range member, not a run's end.
        val v3 = record(stream(even = true, endMs = 120_000))
        val reader = HistoryCompactReader(v3.db)
        val rideNo = checkNotNull(reader.rideNo("ride"))
        var cutBoth = 0
        for (key in keys) for (from in 0L..121_000L step 1_150) for (span in listOf(0L, 100L, 300L, 1_000L, 17_000L, 200_000L)) {
            val inRange = v3.full(key).filter { it.tick.elapsedMillis in from..(from + span) }
            val known = inRange.mapNotNull { it.value }
            val detail = reader.detail(rideNo, origin, key, from..(from + span))
            assertEquals(HistoryCompactReader.Detail(known.minOrNull(), known.maxOrNull(), inRange.count { it.unknown != null }.toLong(),
                inRange.maxOfOrNull { it.tick.elapsedMillis }), detail, "$key [$from, +$span]")
            if (key == dead && from > 30_150 && span == 1_000L) cutBoth++
        }
        assertTrue(cutBoth > 0)
    }

    @Test fun statsVerdictsFollowTheRows() = runTest {
        val recording = stream()
        val v3 = record(recording)
        val speedTicks = recording.samples.filter { it.key == speed }.map { it.tick.elapsedMillis }
        val ranges = listOf(
            whole,
            HistoryRange(axis(0), axis(recording.endMs)),
            HistoryRange(axis(100_000), axis(200_000)),                        // cuts the dead run on both sides
            HistoryRange(axis(speedTicks[370] + 1), axis(speedTicks[660] + 1)), // cuts a speed block on both sides
            HistoryRange(axis(300_000), axis(300_500)),                         // inside one run
            HistoryRange(axis(speedTicks[210] + 1), axis(speedTicks[212] - 1)), // inside a speed block, between members
        )
        var pending = 0; var unknownOnly = 0
        for (key in keys) for (range in ranges) {
            val b = v3.store.stats("ride", key, range)
            val rows = v3.full(key).filter { historyAxisEpochMillis(origin, it.tick)!! in range.startEpochMillis..range.endEpochMillis }
            if (rows.any { it.unknown != null }) assertFalse(b.complete, "$key $range: an unknown in range")
            if (b.pendingWalk) pending++
            if (b.maximum == null) unknownOnly++
        }
        assertTrue(pending > 0 && unknownOnly > 0, "pending=$pending unknownOnly=$unknownOnly: the verdict paths are reached")
    }

    @Test fun otherSourcesExactMax() = runTest {
        suspend fun ride(samples: List<HistorySample>, endMs: Long): Fixture {
            val f = fixture()
            f.store.begin(HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING), sources)
            // Raw appends: the sequence order IS the fixture here (the projection may defer a series' detail).
            samples.chunked(128).forEachIndexed { i, chunk -> f.store.append(HistoryBatch("ride", chunk, emptyList(), emptyList(), "r$i")) }
            f.store.finish("ride", tick(endMs), HistoryRideStatus.COMPLETED)
            return f
        }
        // Source pack ends at 60 s; ctl is one unknown run to 1,200 s.
        val packSamples = (0L..200L).map { HistorySample(tick(it * 300), voltage, 60.0) }
        val ctlRun = (0L..4000L).map { HistorySample(tick(it * 300), speed, null, HistoryUnknownReason.UNAVAILABLE) }
        val a3 = ride((packSamples + ctlRun).sortedBy { it.tick.elapsedMillis }, 1_200_000)
        val compact = a3.db.rideHistoryCompactQueries
        val ctlSeries = compact.seriesForV3(checkNotNull(compact.rideNoV3("ride").executeAsOneOrNull()), "ctl", "speed_kmh", -1).executeAsOne()
        assertEquals(4001L, compact.samplesOfSeriesV3(ctlSeries).executeAsList().single().runCount, "ctl is ONE run row")
        assertEquals(1_200_000L, a3.store.lastSampleElapsedOfOtherSources("ride", "pack", Long.MAX_VALUE))
        // A bound inside the run: its expansion clipped (999,900), as V2's rows give; never the bound, never the run end.
        assertEquals(999_900L, a3.store.lastSampleElapsedOfOtherSources("ride", "pack", 1_000_050))
        assertEquals(60_000L, a3.store.lastSampleElapsedOfOtherSources("ride", "ctl", Long.MAX_VALUE))
        assertNull(a3.store.lastSampleElapsedOfOtherSources("ride", "ctl", -1))

        // Incoherent (no recorder emits it): aux's one sample at 1,300 s is committed FIRST, then 4,001 ctl rows bury it
        // under V2's 200-row window. V2 said 1,200 s; the compact store is exact, 1,300 s: the KDoc'd retraction.
        val late = HistorySample(tick(1_300_000), auxVoltage, 50.0)
        val b3 = ride(listOf(late) + ctlRun, 1_300_000)
        assertEquals(1_300_000L, b3.store.lastSampleElapsedOfOtherSources("ride", "pack", Long.MAX_VALUE))
    }

    @Test fun recordedKeysIncludeRunOnlySeries() = runTest {
        val v3 = record(stream(even = true, endMs = 60_000))
        // The dead series must be listed for its run alone, not for a bucket: remove its buckets.
        v3.db.rideHistoryBucketQueries.deleteBuckets("ride")
        assertEquals(listOf(dead), v3.store.recordedKeys("ride", "pack", 1).keys)
        assertEquals(listOf(dead, voltage), v3.store.recordedKeys("ride", "pack", 20000).keys)
        assertTrue(v3.store.recordedKeys("ride", "pack", 1).truncated)
        assertEquals(listOf(speed), v3.store.recordedKeys("ride", "ctl", 20000).keys)
        assertEquals(emptyList(), v3.store.recordedKeys("ride", "aux", 20000).keys)
    }

    @Test fun walkVerdictEqual() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val recording = stream()
        val v3 = record(recording, dispatcher)
        val repositories = listOf<HistoryStore>(RowsStore(v3), v3.store).map { DefaultHistoryReadRepository(it, computeDispatcher = dispatcher) }
        val ranges = listOf(whole, HistoryRange(axis(100_000), axis(200_000)), HistoryRange(axis(500_000), axis(856_000)))
        var walked = 0
        for (key in keys) for (range in ranges) {
            if (v3.store.stats("ride", key, range).pendingWalk) walked++
            val (a, b) = repositories.map { it.stats("ride", key, range) }
            assertEquals(a.complete to a.hasGap, b.complete to b.hasGap, "$key $range")
            assertEquals(a, b, "$key $range")
            // The cached answer (a finished ride) is the same one.
            val (a2, b2) = repositories.map { it.stats("ride", key, range) }
            assertEquals(a, a2); assertEquals(b, b2)
        }
        assertTrue(walked > 0, "the completeness walk ran")
    }

    // ---- Task 6 fix round ------------------------------------------------------------------------------------------

    /**
     * Review I2, incoherent by design (neither the live writer nor 28.sqm can store it):
     * run A spans 10 members over 2.7 s with stored sequences 10..12 (spread 2 < 9), run B is 6 members at ONE tick with
     * sequences 20..21, so its members form two tie groups of 3 identical `(elapsed, sequence)` pairs. A known row follows.
     * The pager must never throw, every page must advance, and the known row after them must be read. Where a tie group
     * fits a page the pages equal one whole read; where it does not (page size 1, 2), the rest of the group is skipped.
     */
    @Test fun aRunWithTooFewSequencesNeverBreaksThePager() = runTest {
        val f = fixture()
        f.store.begin(HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING), sources)
        val q = f.db.rideHistoryCompactQueries
        val rideNo = checkNotNull(q.rideNoV3("ride").executeAsOneOrNull())
        q.insertSeriesV3(1, rideNo, dead.sourceId, dead.metricId, -1)
        val code = HistoryCompactCodec.code(HistoryUnknownReason.UNAVAILABLE)
        q.insertSampleV3(1, 1_000, 10, 0, 0, null, code, 3_700, 12, 0, 10)
        q.insertSampleV3(1, 5_000, 20, 0, 0, null, code, 5_000, 21, 0, 6)
        q.insertSampleV3(1, 5_300, 30, 0, 0, 1.5, null, null, null, null, null)
        val all = f.store.samples("ride", dead, whole, null, 20000).items
        assertEquals(17, all.size)
        assertEquals(1.5, all.last().value)
        for (size in listOf(1, 2, 3, 4, 7, 100)) {
            val read = pages(f.store, dead, whole, size).flatMap { it.items }
            if (size >= 3) assertEquals(lines(all), lines(read), "page size $size: every tie group fits a page")
            else {
                // A subsequence that keeps every distinct tick and the known row; only tie duplicates are dropped.
                assertEquals(lines(all).distinct(), lines(read).distinct(), "page size $size")
                assertTrue(read.size in 13 until 17, "page size $size read ${read.size}")
            }
        }
        // Through the repository too: its page checks (bound, order, cursor progress) accept every page.
        val repository = DefaultHistoryReadRepository(f.store)
        var after: HistoryCursor? = null
        var total = 0
        do {
            val page = checkNotNull(repository.samples("ride", dead, whole, after, 2))
            total += page.items.size
            after = page.next
        } while (after != null)
        assertTrue(total in 13 until 17)
    }

    /** Review M5: members at one tick (zero steps) and a known row at the run's end tick, written by `append`. */
    @Test fun zeroStepMembersAndARowAtTheRunEndPageAsV2() = runTest {
        val tied = List(3) { HistorySample(tick(0), dead, null, HistoryUnknownReason.UNAVAILABLE) } +
            HistorySample(tick(0), dead, 7.0) + HistorySample(tick(300), dead, null, HistoryUnknownReason.UNAVAILABLE)
        val v3 = fixture()
        v3.store.begin(HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING), sources)
        v3.store.append(HistoryBatch("ride", tied, emptyList(), emptyList(), "r0"))
        val q = v3.db.rideHistoryCompactQueries
        val series = q.seriesForV3(checkNotNull(q.rideNoV3("ride").executeAsOneOrNull()), dead.sourceId, dead.metricId, -1).executeAsOne()
        assertEquals(listOf(3L, null, null), q.samplesOfSeriesV3(series).executeAsList().map { it.runCount }, "a run, the known row, a lone unknown")
        val expected = lines(tied)
        assertEquals(5, expected.size)
        for (size in listOf(1, 2, 3, 7)) assertEquals(expected, lines(pages(v3.store, dead, whole, size).flatMap { it.items }), "size $size")
    }

    /**
     * Review M1: a cursor below the range start (never produced by a pager; possible from a caller mixing ranges) must
     * not skip a run that starts between the cursor and `from`. V2 answered every member from `from`.
     */
    @Test fun aCursorBelowTheRangeSkipsNothing() = runTest {
        val v3 = record(stream(even = true, endMs = 60_000))
        val range = HistoryRange(axis(31_000), axis(40_000))
        // The dead series' second run starts at 30.3 s (after the BLE_GAP at 30.15 s); the cursor sits on 30 s.
        val cursor = HistoryCursor(axis(30_000), "0")
        for (key in keys) {
            assertEquals(lines(v3.full(key).filter { it.tick.elapsedMillis in 31_000L..40_000L }),
                lines(v3.store.samples("ride", key, range, cursor, 20000).items), "$key")
        }
        assertTrue(v3.store.samples("ride", dead, range, cursor, 20000).items.isNotEmpty())
    }
}
