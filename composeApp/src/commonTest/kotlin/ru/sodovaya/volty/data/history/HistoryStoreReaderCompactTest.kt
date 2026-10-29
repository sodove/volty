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
 * Task 6 of the compact-history-storage plan: every `HistoryStore` reader answers a compact (V3) ride as it answers the
 * same recording in V2. Each test writes ONE stream twice, through `append` of a V2 store and of a compact store, and
 * compares the readers. Where the brief and Ruling 2 disagree (runs expand to `count` samples, so a page may end inside a
 * run), the test follows the ruling and says so.
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

    private class Fixture(val provider: VoltyDatabaseProvider, compact: Boolean, dispatcher: CoroutineDispatcher) {
        val db = provider.database
        val store = SqlDelightHistoryStore(provider, dispatcher, recordCompact = compact)
    }
    private fun fixture(compact: Boolean, dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        VoltyDatabase.Schema.create(driver)
        return Fixture(VoltyDatabaseProvider(driver), compact, dispatcher)
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }

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

    private suspend fun record(recording: Recording, dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): Pair<Fixture, Fixture> {
        val v2 = fixture(compact = false, dispatcher); val v3 = fixture(compact = true, dispatcher)
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        for (f in listOf(v2, v3)) {
            f.store.begin(ride, sources)
            val gap = recording.markers.single()
            val chunks = recording.samples.chunked(128)
            // The marker travels in the batch that first reaches it, as the recorder commits it.
            val carrier = chunks.indexOfFirst { chunk -> chunk.any { it.tick.elapsedMillis >= gap.tick.elapsedMillis } }
            f.appendProjected(ride, chunks.mapIndexed { index, chunk -> chunk to if (index == carrier) listOf(gap) else emptyList() })
            assertEquals(recording.markers, f.store.markers("ride", whole))
            f.store.finish("ride", tick(recording.endMs), HistoryRideStatus.COMPLETED)
        }
        assertEquals(1L, v3.db.rideHistoryCompactQueries.maxRideNoV3().executeAsOne(), "the compact store recorded compact")
        assertNull(v2.db.rideHistoryCompactQueries.rideNoV3("ride").executeAsOneOrNull(), "the V2 store recorded V2")
        return v2 to v3
    }

    /** Through the recorder's projection, as production writes: it also writes the summary buckets `stats` needs. */
    private suspend fun Fixture.appendProjected(ride: HistoryRide, batches: List<Pair<List<HistorySample>, List<HistoryMarker>>>) {
        var projection = HistoryRecordingProjection(ride, sources)
        batches.forEachIndexed { i, (samples, markers) ->
            val prepared = projection.prepare(HistoryBatch("ride", samples, emptyList(), markers, "r$i"), i == batches.lastIndex)
            projection = prepared.next
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
        val (v2, v3) = record(recording)
        val markers = v2.store.markers("ride", whole)
        // A cut edge: a range that starts and ends inside runs of the dead series (and of a speed block).
        val cut = HistoryRange(axis(100_001), axis(200_001))
        for (key in keys) {
            val full = v2.store.samples("ride", key, whole, null, 20000).items
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
        // The known rows are bit-equal to V2's, and the uneven run's interior really is interpolated (not vacuous).
        val v2Dead = v2.store.samples("ride", dead, whole, null, 20000).items
        val v3Dead = pages(v3.store, dead, whole, 20000).flatMap { it.items }
        assertNotEquals(lines(v2Dead), lines(v3Dead))
        assertEquals(lines(v2.store.samples("ride", voltage, whole, null, 20000).items),
            lines(pages(v3.store, voltage, whole, 20000).flatMap { it.items }))
    }

    @Test fun anEvenStreamPagesEqualV2ForEveryRange() = runTest {
        val (v2, v3) = record(stream(even = true, endMs = 120_000))
        for (key in keys) for (from in 0L..120_000L step 2_350) for (span in listOf(0L, 150L, 300L, 1_000L, 17_000L, 200_000L)) {
            val range = HistoryRange(axis(from), axis(from + span))
            val v2Items = v2.store.samples("ride", key, range, null, 20000).items
            assertEquals(lines(v2Items), lines(pages(v3.store, key, range, 7).flatMap { it.items }), "$key [$from, +$span]")
        }
    }

    @Test fun detailEqualsV2OnAnEvenStreamForEveryRange() = runTest {
        // The SQL reduction plus the two clipped runs, against V2's row-by-row query. On an even stream expand-then-clip
        // is exact, so every field must match: unknown MEMBERS in range and the last in-range member, not a run's end.
        val (v2, v3) = record(stream(even = true, endMs = 120_000))
        val reader = HistoryCompactReader(v3.db)
        val rideNo = checkNotNull(reader.rideNo("ride"))
        var cutBoth = 0
        for (key in keys) for (from in 0L..121_000L step 1_150) for (span in listOf(0L, 100L, 300L, 1_000L, 17_000L, 200_000L)) {
            val v2Row = v2.db.rideHistorySampleQueries.rangeDetailStats("ride", key.sourceId, key.metricId,
                key.cellIndex?.toLong() ?: -1, from, from + span).executeAsOne()
            val detail = reader.detail(rideNo, origin, key, from..(from + span))
            assertEquals(HistoryCompactReader.Detail(v2Row.minimum, v2Row.maximum, v2Row.unknownCount, v2Row.lastElapsed), detail,
                "$key [$from, +$span]")
            if (key == dead && from > 30_150 && span == 1_000L) cutBoth++
        }
        assertTrue(cutBoth > 0)
    }

    @Test fun statsEqualV2() = runTest {
        val recording = stream()
        val (v2, v3) = record(recording)
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
            val a = v2.store.stats("ride", key, range); val b = v3.store.stats("ride", key, range)
            assertEquals(a, b, "$key $range")
            if (b.pendingWalk) pending++
            if (b.maximum == null) unknownOnly++
        }
        assertTrue(pending > 0 && unknownOnly > 0, "pending=$pending unknownOnly=$unknownOnly: the verdict paths are reached")
    }

    @Test fun otherSourcesExactMax() = runTest {
        suspend fun ride(samples: List<HistorySample>, endMs: Long): Pair<Fixture, Fixture> {
            val v2 = fixture(compact = false); val v3 = fixture(compact = true)
            for (f in listOf(v2, v3)) {
                f.store.begin(HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING), sources)
                // Raw appends: the sequence order IS the fixture here (the projection may defer a series' detail).
                samples.chunked(128).forEachIndexed { i, chunk -> f.store.append(HistoryBatch("ride", chunk, emptyList(), emptyList(), "r$i")) }
                f.store.finish("ride", tick(endMs), HistoryRideStatus.COMPLETED)
            }
            return v2 to v3
        }
        // Source pack ends at 60 s; ctl is one unknown run to 1,200 s.
        val packSamples = (0L..200L).map { HistorySample(tick(it * 300), voltage, 60.0) }
        val ctlRun = (0L..4000L).map { HistorySample(tick(it * 300), speed, null, HistoryUnknownReason.UNAVAILABLE) }
        val (a2, a3) = ride((packSamples + ctlRun).sortedBy { it.tick.elapsedMillis }, 1_200_000)
        val compact = a3.db.rideHistoryCompactQueries
        val ctlSeries = compact.seriesForV3(checkNotNull(compact.rideNoV3("ride").executeAsOneOrNull()), "ctl", "speed_kmh", -1).executeAsOne()
        assertEquals(4001L, compact.samplesOfSeriesV3(ctlSeries).executeAsList().single().runCount, "ctl is ONE run row")
        assertEquals(1_200_000L, a3.store.lastSampleElapsedOfOtherSources("ride", "pack", Long.MAX_VALUE))
        assertEquals(1_200_000L, a2.store.lastSampleElapsedOfOtherSources("ride", "pack", Long.MAX_VALUE))
        // A bound inside the run: its expansion clipped (999,900), as V2's rows give; never the bound, never the run end.
        assertEquals(999_900L, a3.store.lastSampleElapsedOfOtherSources("ride", "pack", 1_000_050))
        assertEquals(999_900L, a2.store.lastSampleElapsedOfOtherSources("ride", "pack", 1_000_050))
        assertEquals(60_000L, a3.store.lastSampleElapsedOfOtherSources("ride", "ctl", Long.MAX_VALUE))
        assertNull(a3.store.lastSampleElapsedOfOtherSources("ride", "ctl", -1))

        // Incoherent (no recorder emits it): aux's one sample at 1,300 s is committed FIRST, then 4,001 ctl rows bury it
        // under V2's 200-row window. V2 says 1,200 s; the compact store is exact, 1,300 s: the KDoc'd retraction.
        val late = HistorySample(tick(1_300_000), auxVoltage, 50.0)
        val (b2, b3) = ride(listOf(late) + ctlRun, 1_300_000)
        assertEquals(1_200_000L, b2.store.lastSampleElapsedOfOtherSources("ride", "pack", Long.MAX_VALUE))
        assertEquals(1_300_000L, b3.store.lastSampleElapsedOfOtherSources("ride", "pack", Long.MAX_VALUE))
    }

    @Test fun recordedKeysIncludeRunOnlySeries() = runTest {
        val (v2, v3) = record(stream(even = true, endMs = 60_000))
        // The dead series must be listed for its run alone, not for a bucket: remove its buckets in both layouts.
        for (f in listOf(v2, v3)) {
            f.db.rideHistoryBucketQueries.deleteBuckets("ride")
        }
        for (limit in listOf(1, 2, 20000)) {
            val a = v2.store.recordedKeys("ride", "pack", limit); val b = v3.store.recordedKeys("ride", "pack", limit)
            assertEquals(a, b, "limit $limit")
        }
        assertEquals(listOf(dead, voltage), v3.store.recordedKeys("ride", "pack", 20000).keys)
        assertTrue(v3.store.recordedKeys("ride", "pack", 1).truncated)
        assertEquals(listOf(speed), v3.store.recordedKeys("ride", "ctl", 20000).keys)
        assertEquals(emptyList(), v3.store.recordedKeys("ride", "aux", 20000).keys)
    }

    @Test fun walkVerdictEqual() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val recording = stream()
        val (v2, v3) = record(recording, dispatcher)
        val repositories = listOf(v2, v3).map { DefaultHistoryReadRepository(it.store, computeDispatcher = dispatcher) }
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
     * Review I2, incoherent by design (the live writer cannot store it; a conversion of out-of-order V2 rows could):
     * run A spans 10 members over 2.7 s with stored sequences 10..12 (spread 2 < 9), run B is 6 members at ONE tick with
     * sequences 20..21, so its members form two tie groups of 3 identical `(elapsed, sequence)` pairs. A known row follows.
     * The pager must never throw, every page must advance, and the known row after them must be read. Where a tie group
     * fits a page the pages equal one whole read; where it does not (page size 1, 2), the rest of the group is skipped.
     */
    @Test fun aRunWithTooFewSequencesNeverBreaksThePager() = runTest {
        val f = fixture(compact = true)
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
        val v2 = fixture(compact = false); val v3 = fixture(compact = true)
        for (f in listOf(v2, v3)) {
            f.store.begin(HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING), sources)
            f.store.append(HistoryBatch("ride", tied, emptyList(), emptyList(), "r0"))
        }
        val q = v3.db.rideHistoryCompactQueries
        val series = q.seriesForV3(checkNotNull(q.rideNoV3("ride").executeAsOneOrNull()), dead.sourceId, dead.metricId, -1).executeAsOne()
        assertEquals(listOf(3L, null, null), q.samplesOfSeriesV3(series).executeAsList().map { it.runCount }, "a run, the known row, a lone unknown")
        val expected = lines(v2.store.samples("ride", dead, whole, null, 20000).items)
        assertEquals(5, expected.size)
        for (size in listOf(1, 2, 3, 7)) assertEquals(expected, lines(pages(v3.store, dead, whole, size).flatMap { it.items }), "size $size")
    }

    /**
     * Review M1: a cursor below the range start (never produced by a pager; possible from a caller mixing ranges) must
     * not skip a run that starts between the cursor and `from`. V2 answers every member from `from`.
     */
    @Test fun aCursorBelowTheRangeSkipsNothing() = runTest {
        val (v2, v3) = record(stream(even = true, endMs = 60_000))
        val range = HistoryRange(axis(31_000), axis(40_000))
        // The dead series' second run starts at 30.3 s (after the BLE_GAP at 30.15 s); the cursor sits on 30 s.
        val cursor = HistoryCursor(axis(30_000), "0")
        for (key in keys) {
            assertEquals(lines(v2.store.samples("ride", key, range, cursor, 20000).items),
                lines(v3.store.samples("ride", key, range, cursor, 20000).items), "$key")
        }
        assertTrue(v3.store.samples("ride", dead, range, cursor, 20000).items.isNotEmpty())
    }
}
