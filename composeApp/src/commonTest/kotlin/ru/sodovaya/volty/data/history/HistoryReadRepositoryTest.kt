package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryReadRepositoryTest {
    private val origin = HistoryTick(10000, 500, 1)
    private val ride = HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING)
    private val source = HistorySource("pack", HistorySourceKind.BATTERY, "Recorded pack")
    private val key = HistorySeriesKey(source.id, "power_w")
    private val range = HistoryRange(10000, 20000)
    private fun sample(offset: Long, value: Double) = HistorySample(
        HistoryTick(10000 + offset, 500 + offset, 1), key, value)
    private fun page(vararg samples: HistorySample, next: HistoryCursor? = null) =
        HistoryPage(samples.toList(), next, next != null)

    @Test fun pagesKeepCanonicalSignsEqualTimeOrderZerosPeaksAndRecordedOrigin() = runTest {
        val store = Store()
        val cursor = HistoryCursor(10001, "2")
        val equalTimeCursor = HistoryCursor(10001, "3")
        val a = sample(0, 0.0); val b = sample(1, -4200.0); val c = sample(1, 900.0)
        store.readPage = { after -> when (after) {
            null -> page(a, b, next = cursor)
            cursor -> page(c, next = equalTimeCursor)
            equalTimeCursor -> page(sample(2, 2.0))
            else -> error("Unexpected cursor $after")
        } }
        val marker = HistoryMarker(sample(1, 0.0).tick, HistoryMarkerKind.GPS_GAP)
        store.markerRows = listOf(marker)
        val result = DefaultHistoryReadRepository(store).series(ride.id, key, range)
        assertEquals(listOf(a, b, c, sample(2, 2.0)), result.samples)
        assertEquals(origin, result.origin)
        assertEquals(listOf(marker), result.markers)
        assertEquals(key, result.key)
        assertFalse(result.truncated)
        assertEquals(listOf(null, cursor, equalTimeCursor), store.sampleReads.map { it.first })
        assertTrue(store.sampleReads.all { it.second == 20000 })
        assertEquals(0, store.statsReads)
    }

    @Test fun pageBudgetStopsIssuingReadsAndReportsTheOmittedTail() = runTest {
        val store = Store()
        store.readPage = { after ->
            val offset = (after?.stableId?.toLong() ?: 0) + 1
            page(sample(offset, offset.toDouble()), next = HistoryCursor(10000 + offset, offset.toString()))
        }
        val result = DefaultHistoryReadRepository(store, maxReadPages = 2).series(ride.id, key, range, 3)
        assertEquals(2, store.sampleReads.size)
        assertTrue(result.truncated)
        assertEquals(listOf(1.0, 2.0), result.samples.map { it.value })
    }

    @Test fun absentSourceForbiddenMetricAndWrongCellShapeCannotReadNumericData() = runTest {
        val store = Store()
        val repository = DefaultHistoryReadRepository(store)
        for (invalid in listOf(key.copy(sourceId = "other"), key.copy(metricId = "speed_kmh"),
            key.copy(metricId = "future_metric"), key.copy(cellIndex = 0), key.copy(metricId = "cell_v"))) {
            val result = repository.series(ride.id, invalid, range)
            assertEquals(invalid, result.key)
            assertTrue(result.samples.isEmpty())
            assertEquals(origin, result.origin)
            assertEquals(HistoryStats(null, null, null, false), repository.stats(ride.id, invalid, range))
        }
        assertTrue(store.sampleReads.isEmpty())
        assertEquals(0, store.markerReads)
        assertEquals(0, store.statsReads)
        store.sourceRows = listOf(source.copy(kind = HistorySourceKind.DERIVED_BATTERY))
        assertTrue(repository.series(ride.id, key.copy(metricId = "cell_v", cellIndex = 0), range).samples.isEmpty())
    }

    @Test fun statsAreAnIndependentStoreQueryAndNeverAnOverviewEstimate() = runTest {
        val store = Store()
        store.storedStats = HistoryStats(-4200.0, 1000.0, 72.5, false)
        assertEquals(store.storedStats, DefaultHistoryReadRepository(store).stats(ride.id, key, range))
        assertEquals(1, store.statsReads)
        assertTrue(store.sampleReads.isEmpty())
        assertEquals(0, store.markerReads)
    }

    @Test fun repeatedCursorFailsInsteadOfLoopingOrPublishingDuplicateData() = runTest {
        val store = Store()
        val cursor = HistoryCursor(10001, "1")
        store.readPage = { page(sample(1, 1.0), next = cursor) }
        assertFailsWith<IllegalStateException> { DefaultHistoryReadRepository(store).series(ride.id, key, range) }
        assertEquals(2, store.sampleReads.size)
    }

    @Test fun foreignSeriesAndOutOfOrderRowsFailBeforePublishing() = runTest {
        val store = Store()
        val repository = DefaultHistoryReadRepository(store)
        store.readPage = { page(sample(0, 1.0).copy(key = key.copy(sourceId = "other"))) }
        assertFailsWith<IllegalStateException> { repository.series(ride.id, key, range) }
        store.readPage = { page(sample(1, 1.0), sample(0, 2.0)) }
        assertFailsWith<IllegalStateException> { repository.series(ride.id, key, range) }
    }

    @Test fun cancellationAfterNonCooperativePageDoesNotIssueNextReadOrPublish() = runTest {
        val store = Store()
        val pending = CompletableDeferred<Unit>()
        store.readPage = {
            withContext(NonCancellable) { pending.await() }
            page(sample(0, 1.0), next = HistoryCursor(10000, "1"))
        }
        var published = false
        val job = launch { DefaultHistoryReadRepository(store).series(ride.id, key, range); published = true }
        try {
            runCurrent()
            assertEquals(1, store.sampleReads.size) // The driver reached the suspended read.
            job.cancel()
        } finally { pending.complete(Unit) }
        job.join()
        assertFalse(published)
        assertEquals(1, store.sampleReads.size)
    }

    @Test fun invalidBudgetsFailBeforeAnyStoreRead() = runTest {
        val store = Store()
        val repository = DefaultHistoryReadRepository(store)
        for (budget in listOf(2, 601)) {
            assertFailsWith<IllegalArgumentException> { repository.series(ride.id, key, range, budget) }
        }
        assertFailsWith<IllegalArgumentException> { DefaultHistoryReadRepository(store, 0) }
        assertFailsWith<IllegalArgumentException> { DefaultHistoryReadRepository(store, 65) }
        assertEquals(0, store.rideReads)
    }

    @Test fun absentRideHasNoNumericFallbackAndGpsKeepsItsEqualTimeCursor() = runTest {
        val store = Store()
        val repository = DefaultHistoryReadRepository(store)
        val cursor = HistoryCursor(10000, "9")
        val fix = HistoryGpsFix(origin, 0.0, 0.0, 0.0, null)
        store.gpsPage = HistoryPage(listOf(fix), cursor, true)
        assertEquals(store.gpsPage, repository.gps(ride.id, range, cursor))
        assertEquals(listOf<Pair<HistoryCursor?, Int>>(cursor to 20000), store.gpsReads)
        assertEquals(listOf(ride), repository.rides().items)
        assertEquals(listOf(source), repository.sources(ride.id))
        assertEquals(ride, repository.ride(ride.id))
        store.recordedRide = null
        assertNull(repository.ride(ride.id))
        assertTrue(repository.sources(ride.id).isEmpty())
        assertTrue(repository.series(ride.id, key, range).samples.isEmpty())
        assertTrue(repository.gps(ride.id, range).items.isEmpty())
        assertEquals(HistoryStats(null, null, null, false), repository.stats(ride.id, key, range))
        assertTrue(store.sampleReads.isEmpty())
        assertEquals(1, store.gpsReads.size)
    }

    private inner class Store : HistoryStore {
        var recordedRide: HistoryRide? = ride
        var sourceRows = listOf(source)
        var markerRows = emptyList<HistoryMarker>()
        var readPage: suspend (HistoryCursor?) -> HistoryPage<HistorySample> = { page() }
        var gpsPage = HistoryPage<HistoryGpsFix>(emptyList(), null, false)
        var storedStats = HistoryStats(null, null, null, false)
        var rideReads = 0; var markerReads = 0; var statsReads = 0
        val sampleReads = mutableListOf<Pair<HistoryCursor?, Int>>()
        val gpsReads = mutableListOf<Pair<HistoryCursor?, Int>>()
        override suspend fun ride(rideId: String): HistoryRide? { rideReads++; return recordedRide }
        override suspend fun rides(before: HistoryCursor?, limit: Int): HistoryPage<HistoryRide> {
            assertEquals(20, limit); return HistoryPage(listOfNotNull(recordedRide), null, false)
        }
        override suspend fun sources(rideId: String) = sourceRows
        override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange,
            after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> {
            assertEquals(this@HistoryReadRepositoryTest.ride.id, rideId)
            assertEquals(this@HistoryReadRepositoryTest.key, key)
            assertEquals(this@HistoryReadRepositoryTest.range, range)
            sampleReads += after to limit; return readPage(after)
        }
        override suspend fun markers(rideId: String, range: HistoryRange): List<HistoryMarker> { markerReads++; return markerRows }
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistoryGpsFix> {
            gpsReads += after to limit; return gpsPage
        }
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats { statsReads++; return storedStats }
        override suspend fun begin(ride: HistoryRide, sources: List<HistorySource>): Unit = error("write")
        override suspend fun append(batch: HistoryBatch): Unit = error("write")
        override suspend fun finish(rideId: String, ended: HistoryTick, status: HistoryRideStatus): Unit = error("write")
        override suspend fun recoverInterrupted(): Int = error("write")
        override suspend fun delete(rideId: String): Unit = error("write")
        override suspend fun buckets(rideId: String, key: HistorySeriesKey, range: HistoryRange,
            after: HistoryCursor?, limit: Int): HistoryPage<HistoryBucket> = error("overview cannot substitute bucket endpoints")
    }
}
