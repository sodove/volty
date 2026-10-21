package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCatalogComponentTest {
    @Test fun forgettingDeletedSourceRetiresLatePageAndPreservesDifferentIdentity() = runTest {
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); var reads = 0
        val other = ride("a").copy(vehicleId = "other")
        val repo = Repo {
            if (++reads == 1) HistoryPage(listOf(ride("a"), ride("b")), cursor, false)
            else withContext(NonCancellable) { entered.complete(Unit); gate.await(); HistoryPage(listOf(ride("a")), null, false) }
        }
        val c = component(repo)
        try {
            c.refresh(); runCurrent(); c.loadOlder(); runCurrent(); assertTrue(entered.isCompleted)
            c.forget(other.favoriteSource()); assertEquals(2, c.state.value.rides.size)
            c.forget(ride("a").favoriteSource()); assertEquals(listOf("b"), c.state.value.rides.map { it.id })
            gate.complete(Unit); runCurrent(); assertEquals(listOf("b"), c.state.value.rides.map { it.id })
            assertFalse(c.state.value.loadingOlder)
        } finally { gate.complete(Unit); c.close(); runCurrent() }
    }
    private val cursor = HistoryCursor(1000, "b")
    private fun ride(id: String, status: HistoryRideStatus = HistoryRideStatus.COMPLETED) =
        HistoryRide(id, "bike", HistoryTick(1000, 10, 1), HistoryTick(2000, 1010, 1), status)
    private class Repo(val read: suspend (HistoryCursor?) -> HistoryPage<HistoryRide>) : HistoryReadRepository {
        val calls = mutableListOf<HistoryCursor?>()
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> { calls += before; return read(before) }
        override suspend fun ride(rideId: String): HistoryRide? = error("Catalog cannot preload details")
        override suspend fun sources(rideId: String): List<HistorySource> = error("No source preload")
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys = error("No metric preload")
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries = error("No series preload")
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryPage<HistoryGpsFix> = error("No GPS preload")
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats = error("No stats preload")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage = error("No route preload")
    }
    private fun TestScope.component(repo: Repo, lifecycle: LifecycleRegistry = LifecycleRegistry(Lifecycle.State.RESUMED)) =
        HistoryCatalogComponent(DefaultComponentContext(lifecycle), backgroundScope, repo)

    @Test fun pagesBothFormatsOnlyOnDemandAndMergesOverlappingIdsWithoutEagerReads() = runTest {
        val legacy = ride("b", HistoryRideStatus.LEGACY)
        val updated = legacy.copy(status = HistoryRideStatus.INTERRUPTED)
        val repo = Repo { before ->
            if (before == null) HistoryPage(listOf(ride("a"), legacy), cursor, false)
            else { assertEquals(cursor, before); HistoryPage(listOf(updated, ride("c")), null, true) }
        }
        val c = component(repo)
        try {
            runCurrent(); assertTrue(repo.calls.isEmpty()); c.loadOlder(); runCurrent(); assertTrue(repo.calls.isEmpty())
            c.refresh(); runCurrent()
            assertEquals(listOf(ride("a"), legacy), c.state.value.rides)
            assertTrue(c.state.value.hasMore); assertTrue(c.state.value.loaded)
            c.loadOlder(); c.loadOlder(); runCurrent()
            assertEquals(listOf(ride("a"), updated, ride("c")), c.state.value.rides)
            assertEquals(listOf(null, cursor), repo.calls)
            assertFalse(c.state.value.hasMore); assertTrue(c.state.value.truncated)
            c.loadOlder(); runCurrent(); assertEquals(2, repo.calls.size)
        } finally { c.close() }
    }

    @Test fun lateNonCancellableRefreshCannotReplaceNewCatalog() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val repo = Repo {
            if (++calls == 1) withContext(NonCancellable) { entered.complete(Unit); release.await(); HistoryPage(listOf(ride("old")), null, false) }
            else HistoryPage(listOf(ride("new")), null, false)
        }
        val c = component(repo)
        try {
            c.refresh(); runCurrent(); assertTrue(entered.isCompleted)
            c.refresh(); runCurrent(); assertEquals(listOf("new"), c.state.value.rides.map { it.id })
            release.complete(Unit); runCurrent()
            assertEquals(listOf("new"), c.state.value.rides.map { it.id }); assertFalse(c.state.value.refreshing)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }

    @Test fun lateOlderPageCannotAppendIntoRefreshedCatalog() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var firstPages = 0
        val repo = Repo { before ->
            if (before == null) HistoryPage(listOf(ride(if (++firstPages == 1) "a" else "new")), cursor, false)
            else withContext(NonCancellable) { entered.complete(Unit); release.await(); HistoryPage(listOf(ride("old-tail")), null, false) }
        }
        val c = component(repo)
        try {
            c.refresh(); runCurrent(); c.loadOlder(); runCurrent(); assertTrue(entered.isCompleted)
            c.refresh(); runCurrent(); release.complete(Unit); runCurrent()
            assertEquals(listOf("new"), c.state.value.rides.map { it.id }); assertEquals(cursor, c.state.value.nextCursor)
            assertFalse(c.state.value.loadingOlder); assertNull(c.state.value.error)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }

    @Test fun storageFailurePreservesRowsAndCursorForExplicitRetry() = runTest {
        var calls = 0
        val repo = Repo {
            when (++calls) {
                1 -> HistoryPage(listOf(ride("a")), cursor, false)
                2, 4 -> error("storage unavailable")
                else -> HistoryPage(listOf(ride("b")), null, false)
            }
        }
        val c = component(repo)
        try {
            c.refresh(); runCurrent(); c.loadOlder(); runCurrent()
            assertEquals(listOf("a"), c.state.value.rides.map { it.id }); assertEquals(cursor, c.state.value.nextCursor)
            assertEquals(HistoryReadError.STORAGE, c.state.value.error); assertFalse(c.state.value.loadingOlder)
            c.loadOlder(); runCurrent(); assertEquals(listOf("a", "b"), c.state.value.rides.map { it.id }); assertNull(c.state.value.error)
            c.refresh(); runCurrent()
            assertEquals(listOf("a", "b"), c.state.value.rides.map { it.id }); assertEquals(HistoryReadError.STORAGE, c.state.value.error)
            assertFalse(c.state.value.refreshing)
        } finally { c.close() }
    }

    @Test fun consumingFinalContinuationClearsResolvedPageTruncation() = runTest {
        val repo = Repo { before -> if (before == null) HistoryPage(listOf(ride("a")), cursor, true)
            else HistoryPage(listOf(ride("b")), null, false) }
        val c = component(repo)
        try {
            c.refresh(); runCurrent(); assertTrue(c.state.value.truncated)
            c.loadOlder(); runCurrent()
            assertEquals(listOf("a", "b"), c.state.value.rides.map { it.id })
            assertFalse(c.state.value.hasMore); assertFalse(c.state.value.truncated)
        } finally { c.close() }
    }

    @Test fun destroyAndParentCancellationFenceLatePublicationsAndNewCommands() = runTest {
        for (destroy in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val repo = Repo { withContext(NonCancellable) { entered.complete(Unit); release.await(); HistoryPage(listOf(ride("late")), null, false) } }
            val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
            val parent = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val c = HistoryCatalogComponent(DefaultComponentContext(lifecycle), parent, repo)
            try {
                c.refresh(); runCurrent(); assertTrue(entered.isCompleted)
                if (destroy) lifecycle.destroy() else parent.cancel()
                val before = c.state.value; release.complete(Unit); runCurrent(); c.refresh(); c.loadOlder(); runCurrent()
                assertEquals(before, c.state.value); assertEquals(1, repo.calls.size)
            } finally { release.complete(Unit); c.close(); parent.cancel(); runCurrent() }
        }
    }

    @Test fun repeatingPageCursorDoesNotPublishTailOrLoop() = runTest {
        val repo = Repo { before -> HistoryPage(listOf(ride(if (before == null) "a" else "bad-tail")), cursor, false) }
        val c = component(repo)
        try {
            c.refresh(); runCurrent(); c.loadOlder(); runCurrent()
            assertEquals(listOf("a"), c.state.value.rides.map { it.id })
            assertEquals(HistoryReadError.STORAGE, c.state.value.error); assertEquals(cursor, c.state.value.nextCursor)
            assertEquals(2, repo.calls.size); assertFalse(c.state.value.loadingOlder)
        } finally { c.close() }
    }

    @Test fun synchronousRefreshObserverCannotStartSupersededRead() = runTest {
        val repo = Repo { HistoryPage(listOf(ride("current")), null, false) }; val c = component(repo)
        var replaced = false
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { if (it.refreshing && !replaced) { replaced = true; c.refresh() } }
        }
        try {
            c.refresh(); runCurrent()
            assertTrue(replaced); assertEquals(listOf<HistoryCursor?>(null), repo.calls)
            assertEquals(listOf("current"), c.state.value.rides.map { it.id }); assertFalse(c.state.value.refreshing)
        } finally { observer.cancel(); c.close() }
    }
}
