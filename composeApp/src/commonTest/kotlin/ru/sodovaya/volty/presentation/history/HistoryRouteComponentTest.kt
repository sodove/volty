package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRouteComponentTest {
    private val origin = HistoryTick(1000, 10, 1)
    private val range = HistoryRange(1000, 5000)
    private fun ride(id: String = "a") = HistoryRide(id, "bike", origin, HistoryTick(5000, 4010, 1), HistoryRideStatus.COMPLETED)
    private fun fix(axis: Long, lat: Double = 55.0) = HistoryGpsFix(HistoryTick(axis, axis - 990, 1), lat, 60.0, 2.0, null)
    private fun page(fixes: List<HistoryGpsFix>, next: HistoryCursor? = null, markers: List<HistoryMarker> = emptyList()) =
        HistoryRoutePage(origin, HistoryPage(fixes, next, next != null), markers)
    private class Repo(val read: suspend (String, HistoryRange, HistoryCursor?) -> HistoryRoutePage) : HistoryReadRepository {
        val calls = mutableListOf<Triple<String, HistoryRange, HistoryCursor?>>()
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
            calls += Triple(rideId, range, after); return read(rideId, range, after)
        }
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> = error("No catalog preload")
        override suspend fun ride(rideId: String): HistoryRide? = error("Owner receives selected immutable ride")
        override suspend fun sources(rideId: String): List<HistorySource> = error("No source preload")
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys = error("No metric preload")
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries = error("No series preload")
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryPage<HistoryGpsFix> = error("Use atomic snapshot")
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats = error("No stats preload")
    }
    private fun TestScope.component(repo: Repo, maxPages: Int = 8) = HistoryRouteComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo, maxPages)

    @Test fun pagesEqualTimeFixesByStoredCursorAndRetainsRecordedGaps() = runTest {
        val cursor = HistoryCursor(1000, "2"); val gap = HistoryMarker(fix(1001).tick, HistoryMarkerKind.GPS_GAP)
        val repo = Repo { _, _, after -> if (after == null) page(listOf(fix(1000), fix(1000, 56.0)), cursor, listOf(gap))
            else { assertEquals(cursor, after); page(listOf(fix(1002)), markers = listOf(gap)) } }
        val c = component(repo)
        try {
            c.open(ride(), range); runCurrent(); c.loadMore(); c.loadMore(); runCurrent()
            assertEquals(listOf(fix(1000), fix(1000, 56.0), fix(1002)), c.state.value.fixes)
            assertEquals(listOf(gap), c.state.value.markers); assertEquals(2, repo.calls.size)
            assertFalse(c.state.value.truncated); assertNull(c.state.value.nextCursor)
            c.loadMore(); runCurrent(); assertEquals(2, repo.calls.size)
        } finally { c.close() }
    }

    @Test fun newRideAndRangeFenceLateNonCancellableRoute() = runTest {
        for (sameRide in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
            val repo = Repo { _, requested, _ -> if (++calls == 1) withContext(NonCancellable) {
                entered.complete(Unit); release.await(); page(listOf(fix(1000)))
            } else page(listOf(fix(requested.startEpochMillis))) }
            val c = component(repo)
            try {
                c.open(ride(), range); runCurrent(); assertTrue(entered.isCompleted)
                val newRange = HistoryRange(2000, 3000); val selected = ride(if (sameRide) "a" else "b")
                c.open(selected, newRange); runCurrent(); release.complete(Unit); runCurrent()
                assertEquals(selected.id, c.state.value.rideId); assertEquals(newRange, c.state.value.range)
                assertEquals(listOf(fix(2000)), c.state.value.fixes); assertFalse(c.state.value.loading)
            } finally { release.complete(Unit); c.close(); runCurrent() }
        }
    }

    @Test fun legacyHasNoInventedRouteOrLocationRequest() = runTest {
        val repo = Repo { _, _, _ -> error("Legacy has no recorded GPS") }; val c = component(repo)
        try {
            c.open(ride().copy(status = HistoryRideStatus.LEGACY), range); runCurrent()
            assertTrue(c.state.value.noRoute); assertTrue(repo.calls.isEmpty()); assertTrue(c.state.value.fixes.isEmpty())
        } finally { c.close() }
    }

    @Test fun incompleteEmptySnapshotDoesNotProveThatNoRouteExists() = runTest {
        val repo = Repo { _, _, _ -> HistoryRoutePage(origin, HistoryPage(emptyList(), null, true), emptyList()) }
        val c = component(repo)
        try {
            c.open(ride(), range); runCurrent()
            assertTrue(c.state.value.loaded); assertTrue(c.state.value.truncated)
            assertFalse(c.state.value.noRoute)
        } finally { c.close() }
    }

    @Test fun failedContinuationPreservesAcceptedRouteAndCursorForRetry() = runTest {
        val cursor = HistoryCursor(1000, "1"); var calls = 0
        val repo = Repo { _, _, _ -> when (++calls) { 1 -> page(listOf(fix(1000)), cursor)
            2 -> error("disk error"); else -> page(listOf(fix(2000))) } }
        val c = component(repo)
        try {
            c.open(ride(), range); runCurrent(); c.loadMore(); runCurrent()
            assertEquals(listOf(fix(1000)), c.state.value.fixes); assertEquals(cursor, c.state.value.nextCursor)
            assertEquals(HistoryReadError.STORAGE, c.state.value.error)
            c.retry(); runCurrent(); assertEquals(listOf(fix(1000), fix(2000)), c.state.value.fixes)
            assertNull(c.state.value.error); assertEquals(listOf(null, cursor, cursor), repo.calls.map { it.third })
        } finally { c.close() }
    }

    @Test fun pageBudgetIsExplicitAndNarrowingRangeStartsFreshBudget() = runTest {
        val repo = Repo { _, requested, after -> val axis = if (after == null) requested.startEpochMillis else after.epochMillis + 1
            page(listOf(fix(axis)), HistoryCursor(axis, (axis - 999).toString())) }
        val c = component(repo, maxPages = 2)
        try {
            c.open(ride(), range); runCurrent(); c.loadMore(); runCurrent(); c.loadMore(); runCurrent()
            assertEquals(2, repo.calls.size); assertEquals(2, c.state.value.fixes.size)
            assertTrue(c.state.value.capped); assertTrue(c.state.value.truncated)
            c.open(ride(), HistoryRange(2000, 3000)); runCurrent()
            assertEquals(3, repo.calls.size); assertFalse(c.state.value.capped); assertEquals(listOf(fix(2000)), c.state.value.fixes)
        } finally { c.close() }
    }

    @Test fun invalidOriginRangeOrderOrCursorCannotPublishUnqualifiedFixes() = runTest {
        val invalid = listOf(page(listOf(fix(1000))).copy(origin = origin.copy(clockGeneration = 2)),
            page(listOf(fix(999))), page(listOf(fix(2000), fix(1000))),
            page(listOf(fix(1000)), HistoryCursor(2000, "1")), page(listOf(fix(1000)), HistoryCursor(1000, "bad")))
        for (snapshot in invalid) {
            val c = component(Repo { _, _, _ -> snapshot })
            try { c.open(ride(), range); runCurrent(); assertEquals(HistoryReadError.STORAGE, c.state.value.error)
                assertTrue(c.state.value.fixes.isEmpty()); assertFalse(c.state.value.loading) }
            finally { c.close() }
        }
    }

    @Test fun destroyedParentCannotPublishOrRestartPendingRouteRead() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Repo { _, _, _ -> withContext(NonCancellable) { entered.complete(Unit); release.await(); page(listOf(fix(1000))) } }
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        val c = HistoryRouteComponent(DefaultComponentContext(lifecycle), backgroundScope, repo)
        try {
            c.open(ride(), range); runCurrent(); assertTrue(entered.isCompleted); lifecycle.destroy()
            val before = c.state.value; release.complete(Unit); runCurrent(); c.open(ride("b"), range); c.retry(); runCurrent()
            assertEquals(before, c.state.value); assertEquals(1, repo.calls.size)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
}
