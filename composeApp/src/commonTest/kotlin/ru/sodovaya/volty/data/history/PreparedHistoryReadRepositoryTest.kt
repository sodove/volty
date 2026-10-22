package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class PreparedHistoryReadRepositoryTest {
    private val calls = mutableListOf<String>()
    private val delegate = object : HistoryReadRepository {
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> { calls += "catalog"; return HistoryPage(emptyList(), null, false) }
        override suspend fun ride(rideId: String): HistoryRide? { calls += "ride:$rideId"; return null }
        override suspend fun sources(rideId: String): List<HistorySource> = error("unused")
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys = error("unused")
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries = error("unused")
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryPage<HistoryGpsFix> = error("unused")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage = error("unused")
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats = error("unused")
    }
    @Test fun catalogAndDirectRideEntryAwaitRecoveryBeforeIssuingAnyRead() = runTest {
        val repository = PreparedHistoryReadRepository(delegate) { calls += "prepare" }
        repository.rides(); repository.ride("old")
        assertEquals(listOf("prepare", "catalog", "prepare", "ride:old"), calls)
    }
    @Test fun failedPreparationDoesNotExposeUnrecoveredRowsAndCanRetry() = runTest {
        var failed = true
        val repository = PreparedHistoryReadRepository(delegate) {
            if (failed) throw IllegalStateException("recovery unavailable")
            calls += "prepared"
        }
        assertFailsWith<IllegalStateException> { repository.rides() }; assertTrue(calls.isEmpty())
        failed = false; repository.rides()
        assertEquals(listOf("prepared", "catalog"), calls)
    }
    @Test fun cancellationAfterPreparationCannotIssueRead() = runTest {
        val repository = PreparedHistoryReadRepository(delegate) { currentCoroutineContext()[Job]!!.cancel() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { repository.ride("old") }
        job.join(); assertTrue(job.isCancelled); assertTrue(calls.isEmpty())
    }
}
