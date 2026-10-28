package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BegodeT4Fixture
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryObservation
import kotlin.test.*

/**
 * Task 6 of the telemetry-plausibility plan: a Begode ride's controller power must reach the chart overview and the
 * energy integral. Recording side (the 0x00 frame no longer stores unknown) and READ side (rides recorded before the
 * fix carry the artefact: PARTIAL_SOURCE unknowns between every pair of values).
 */
class ControllerPowerContinuityTest {
    private val origin = HistoryTick(1_000_000, 0, 1)
    private val source = HistorySource("ctl", HistorySourceKind.CONTROLLER, "Wheel")
    private val power = HistorySeriesKey("ctl", "controller_power_w")
    private fun tick(ms: Long) = HistoryTick(origin.epochMillis + ms, ms, 1)
    private fun known(ms: Long, w: Double = 1000.0) = HistorySample(tick(ms), power, w)
    private fun unknown(ms: Long, reason: HistoryUnknownReason) = HistorySample(tick(ms), power, null, reason)

    // ---- recording side: replay the real T4 capture end to end ------------------------------------------------

    private fun replayedPower(repeats: Int): List<HistorySample> {
        val protocol = BegodeProtocol(cellCount = 24)
        val rows = mutableListOf<HistorySample>()
        var now = 0L
        protocol.telemetryObserver = { observation: TelemetryObservation ->
            now += 20
            if (observation.origin == ru.sodovaya.volty.domain.model.TelemetryOrigin.CONTROLLER) {
                rows += historyTelemetrySamples(source, tick(now), observation).filter { it.key == power }
            }
        }
        repeat(repeats) { BegodeT4Fixture.notifications.forEach { protocol.onNotification(it) } }
        return rows
    }

    @Test fun theT4ReplayKeepsControllerPowerOnTheChartAndIntegratesEnergy() {
        val rows = replayedPower(repeats = 300)
        assertTrue(rows.isNotEmpty() && rows.any { it.value != null }, "the replay must reach the power series")
        // Today (before the fix) every 0x00 contributed an unknown, so this alternated value/unknown.
        assertTrue(rows.drop(10).none { it.unknown == HistoryUnknownReason.PARTIAL_SOURCE },
            "no artefact unknowns once the join is warm")
        val gate = HistoryDetailGate(origin, power)
        val detail = rows.flatMap(gate::offer) + gate.flush()
        assertTrue(detail.size > 600, "need more than the overview budget, got ${detail.size}")
        val overview = historyOverview(detail, emptyList(), maxPoints = 600)
        val knownPoints = overview.count { it.value != null }
        assertTrue(knownPoints > 4, "overview kept $knownPoints known points")
        assertTrue(overview.zipWithNext().any { (a, b) -> a.value != null && b.value != null }, "a segment of >= 2 known points")

        val accumulator = HistoryBucketAccumulator(origin, power)
        val buckets = rows.flatMap(accumulator::offer)
        assertTrue(buckets.any { it.energyWh != null }, "energy integrates across the rows")
    }

    // ---- read side: the bridge -------------------------------------------------------------------------------

    @Test fun anArtefactUnknownBetweenCloseKnownValuesIsNotABreak() {
        val rows = (0..40L).map { if (it % 2 == 0L) known(it * 100) else unknown(it * 100, HistoryUnknownReason.PARTIAL_SOURCE) }
        assertTrue(bridgePartialSourceArtefact(rows).all { it.value != null })
        val overview = historyOverview(rows, emptyList(), maxPoints = 600)
        assertEquals(21, overview.size)
        assertTrue(overview.all { it.value != null })
    }

    @Test fun onlyPartialSourceBracketedWithinTwoSecondsIsBridged() {
        // Genuine reasons still break.
        for (reason in listOf(HistoryUnknownReason.UNAVAILABLE, HistoryUnknownReason.INVALID_VALUE, HistoryUnknownReason.GAP)) {
            val rows = listOf(known(0), unknown(100, reason), known(200))
            assertEquals(rows, bridgePartialSourceArtefact(rows), "$reason breaks")
        }
        // A longer gap breaks, even for PARTIAL_SOURCE.
        val long = listOf(known(0), unknown(1000, HistoryUnknownReason.PARTIAL_SOURCE), known(2100))
        assertEquals(long, bridgePartialSourceArtefact(long))
        // Unbracketed on either side stays real.
        val leading = listOf(unknown(0, HistoryUnknownReason.PARTIAL_SOURCE), known(100))
        assertEquals(leading, bridgePartialSourceArtefact(leading))
        val trailing = listOf(known(0), unknown(100, HistoryUnknownReason.PARTIAL_SOURCE))
        assertEquals(trailing, bridgePartialSourceArtefact(trailing))
        // A clock-generation change is a boundary, never bridged.
        val clock = listOf(known(0), unknown(100, HistoryUnknownReason.PARTIAL_SOURCE),
            HistorySample(HistoryTick(origin.epochMillis + 200, 200, 2), power, 5.0))
        assertEquals(clock, bridgePartialSourceArtefact(clock))
    }

    @Test fun otherMetricsAreNeverBridged() {
        val cell = HistorySeriesKey("ctl", "cell_min_v")
        val rows = listOf(HistorySample(tick(0), cell, 3.9), HistorySample(tick(100), cell, null, HistoryUnknownReason.PARTIAL_SOURCE),
            HistorySample(tick(200), cell, 3.9))
        assertEquals(rows, bridgePartialSourceArtefact(rows))
    }

    @Test fun aMarkerBetweenBridgedValuesStillBreaksTheOverview() {
        val rows = listOf(known(0), unknown(100, HistoryUnknownReason.PARTIAL_SOURCE), known(200))
        val gap = HistoryMarker(tick(150), HistoryMarkerKind.BLE_GAP, null)
        val overview = historyOverview(rows, listOf(gap), maxPoints = 600)
        assertTrue(overview.any { it.unknown == HistoryUnknownReason.GAP })
    }

    @Test fun theAccumulatorBridgesAcrossPagesAndReleasesAnUnbracketedTail() {
        val rows = (0..40L).map { if (it % 2 == 0L) known(it * 100) else unknown(it * 100, HistoryUnknownReason.PARTIAL_SOURCE) }
        val accumulator = HistoryOverviewAccumulator(emptyList(), 600)
        // The page boundary falls between an unknown and the next value.
        accumulator.offerPage(rows.subList(0, 11)); accumulator.offerPage(rows.subList(11, rows.size))
        assertTrue(accumulator.snapshot().all { it.value != null })
        assertEquals(21, accumulator.snapshot().size)

        val tail = HistoryOverviewAccumulator(emptyList(), 600)
        tail.offerPage(listOf(known(0), unknown(100, HistoryUnknownReason.PARTIAL_SOURCE)))
        assertEquals(HistoryUnknownReason.PARTIAL_SOURCE, tail.snapshot().last().unknown)
        // snapshot() is repeatable and does not consume the held unknown
        tail.offerPage(listOf(known(200)))
        assertTrue(tail.snapshot().all { it.value != null })
    }

    // ---- read side: energy of an already-recorded ride --------------------------------------------------------

    private val drivers = mutableListOf<JdbcSqliteDriver>()
    @AfterTest fun close() { drivers.forEach { it.close() } }

    private suspend fun recorded(rows: List<HistorySample>, finish: Boolean = true): SqlDelightHistoryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); drivers += driver
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        VoltyDatabase.Schema.create(driver)
        val store = SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
        store.begin(HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING), listOf(source))
        // Exactly what the recorder did: the gate thins detail, the accumulator sees every row.
        val gate = HistoryDetailGate(origin, power)
        val accumulator = HistoryBucketAccumulator(origin, power)
        val detail = rows.flatMap(gate::offer) + gate.flush()
        val buckets = rows.flatMap(accumulator::offer).groupBy { it.slot }.map { (_, group) -> group.last() }
        store.append(HistoryBatch("ride", detail, emptyList(), emptyList(), "r1", buckets))
        if (finish) store.finish("ride", tick(rows.last().tick.elapsedMillis), HistoryRideStatus.COMPLETED)
        return store
    }

    private class CountingStore(val inner: HistoryStore) : HistoryStore by inner {
        var samplePages = 0
        override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange,
            after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> { samplePages++; return inner.samples(rideId, key, range, after, limit) }
    }

    private val range get() = HistoryRange(origin.epochMillis, origin.epochMillis + 60_000)

    /** Known every 100 ms with a varying value, an artefact unknown between: thinning to first/min/max/last matters. */
    private fun artefactRide(count: Long = 400) = (0 until count).flatMap { i ->
        listOf(known(i * 100, 1000.0 + (i * 37 % 11) * 300.0 + (i % 5) * 120.0),
            unknown(i * 100 + 50, HistoryUnknownReason.PARTIAL_SOURCE))
    }.dropLast(1)

    @Test fun anAlreadyRecordedRideGetsApproximateEnergyBackAtReadTime() = runTest {
        val rows = artefactRide()
        val store = recorded(rows)
        // The stored buckets carry no energy at all (no pair was ever usable): that is what cannot be fixed in place.
        assertTrue(store.buckets("ride", power, range, null, 100).items.all { it.energyWh == null })
        val stats = DefaultHistoryReadRepository(store).stats("ride", power, range)
        val exact = assertNotNull(historyStats(bridgePartialSourceArtefact(rows), emptyList(), 1.0).energyWh)
        val repaired = assertNotNull(stats.energyWh)
        assertTrue(kotlin.math.abs(repaired - exact) / exact < 0.05, "repaired $repaired vs exact $exact")
        assertFalse(stats.complete, "thinned-detail energy is approximate, so never complete")
        assertTrue(DefaultHistoryReadRepository(store).series("ride", power, range, 600).samples.all { it.value != null })
    }

    @Test fun storedEnergyIsNeverOverwrittenAndNeverTriggersAPage() = runTest {
        // A post-fix ride: values every 100 ms plus ONE stale-join PARTIAL_SOURCE unknown. Buckets keep exact energy.
        val rows = (0..40L).map { if (it == 20L) unknown(it * 100, HistoryUnknownReason.PARTIAL_SOURCE) else known(it * 100, 500.0 + it * 40) }
        val store = recorded(rows)
        val stored = store.stats("ride", power, range)
        assertNotNull(stored.energyWh)
        val counting = CountingStore(store)
        // The store leaves the unknown-or-gap verdict to the repository's walk (one page, tiny ride): that verdict is the
        // only difference, and it is the only paging: no energy recompute when the stored energy exists.
        assertTrue(stored.pendingWalk)
        assertEquals(stored.copy(complete = true, pendingWalk = false), DefaultHistoryReadRepository(counting).stats("ride", power, range))
        assertEquals(1, counting.samplePages, "one walk page, no repair page when the stored energy exists")
    }

    @Test fun twoReadsOfTheSameArtefactRidePageTheDetailOnce() = runTest {
        val counting = CountingStore(recorded(artefactRide()))
        val repository = DefaultHistoryReadRepository(counting)
        val first = repository.stats("ride", power, range)
        val pages = counting.samplePages
        assertTrue(pages > 0)
        assertEquals(first, repository.stats("ride", power, range))
        assertEquals(pages, counting.samplePages, "the second read is served from the cache")
    }

    /** Fails the SECOND markers read: the first is the completeness walk's (Fix round I1), the second the repair's. */
    private class FlakyMarkersStore(val inner: HistoryStore, var failure: Exception?) : HistoryStore by inner {
        private var reads = 0
        override suspend fun markers(rideId: String, range: HistoryRange) =
            if (++reads >= 2) failure?.let { failure = null; throw it } ?: inner.markers(rideId, range) else inner.markers(rideId, range)
    }

    @Test fun aFailedRepairIsNotCachedSoTheNextReadRepairs() = runTest {
        val flaky = FlakyMarkersStore(recorded(artefactRide()), IllegalStateException("db busy"))
        val repository = DefaultHistoryReadRepository(flaky)
        assertNull(repository.stats("ride", power, range).energyWh, "a failed read is not guessed at")
        assertNotNull(repository.stats("ride", power, range).energyWh, "and not remembered as a negative either")
    }

    @Test fun aCancelledRepairPropagatesAndIsNotCached() = runTest {
        val flaky = FlakyMarkersStore(recorded(artefactRide()), kotlinx.coroutines.CancellationException("screen left"))
        val repository = DefaultHistoryReadRepository(flaky)
        assertFailsWith<kotlinx.coroutines.CancellationException> { repository.stats("ride", power, range) }
        assertNotNull(repository.stats("ride", power, range).energyWh)
    }

    @Test fun aRideStillRecordingIsNeverRepaired() = runTest {
        val stats = DefaultHistoryReadRepository(recorded(artefactRide(), finish = false)).stats("ride", power, range)
        assertNull(stats.energyWh)
    }

    @Test fun genuineUnknownsAreNotTheArtefactSoNothingIsRepaired() = runTest {
        val rows = (0..40L).map { if (it % 2 == 0L) known(it * 100) else unknown(it * 100, HistoryUnknownReason.UNAVAILABLE) }
        val stats = DefaultHistoryReadRepository(recorded(rows)).stats("ride", power, range)
        assertNull(stats.energyWh)
        assertFalse(stats.complete)
    }
}
