package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPreviewComponentTest {
    private val origin = HistoryTick(1000,10,1)
    private fun ride(id: String) = HistoryRide(id,"bike",origin,HistoryTick(5000,4010,1),HistoryRideStatus.COMPLETED)
    private fun fix(axis: Long, lat: Double = 56.0) = HistoryGpsFix(HistoryTick(axis,axis - 990,1),lat,60.0,3.0,null)
    private fun page(next: HistoryCursor? = null, fixes: List<HistoryGpsFix> = listOf(fix(1000),fix(2000))) =
        HistoryRoutePage(origin,HistoryPage(fixes,next,next != null),emptyList())
    private class Repo(val read: suspend (String) -> HistoryRoutePage) : HistoryReadRepository {
        val calls = mutableListOf<Triple<String,HistoryRange,HistoryCursor?>>()
        var active = 0; var maximumActive = 0
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
            calls += Triple(rideId,range,after); active++; maximumActive = maxOf(maximumActive,active)
            try { return read(rideId) } finally { active-- }
        }
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> = error("No catalog preload")
        override suspend fun ride(rideId: String): HistoryRide? = error("Receive immutable catalog metadata")
        override suspend fun sources(rideId: String): List<HistorySource> = error("No source preload")
        override suspend fun recordedKeys(rideId: String,sourceId: String,limit: Int): HistoryRecordedKeys = error("No metric preload")
        override suspend fun series(rideId: String,key: HistorySeriesKey,range: HistoryRange,maxPoints: Int): HistorySeries = error("No series preload")
        override suspend fun gps(rideId: String,range: HistoryRange,after: HistoryCursor?): HistoryPage<HistoryGpsFix> = error("Use atomic snapshot")
        override suspend fun stats(rideId: String,key: HistorySeriesKey,range: HistoryRange): HistoryStats = error("No stats preload")
    }
    private fun TestScope.component(repo: Repo, cacheSize: Int = 16, lifecycle: LifecycleRegistry = LifecycleRegistry(Lifecycle.State.RESUMED)) =
        HistoryPreviewComponent(DefaultComponentContext(lifecycle),backgroundScope,repo,1000,
            StandardTestDispatcher(testScheduler),maxConcurrentReads = 2,cacheSize = cacheSize,pointBudget = 3)

    @Test fun visibleReadsAreDeduplicatedAndAtMostTwoAtomicPagesRunTogether() = runTest {
        val gates = listOf("a","b","c").associateWith { CompletableDeferred<Unit>() }
        val repo = Repo { id -> gates.getValue(id).await(); page() }; val c = component(repo)
        try {
            val rides = listOf(ride("a"),ride("b"),ride("c"))
            c.setVisible(rides); c.setVisible(rides); runCurrent()
            assertEquals(setOf("a","b"),repo.calls.map { it.first }.toSet()); assertEquals(2,repo.maximumActive)
            gates.getValue("a").complete(Unit); runCurrent()
            assertEquals(setOf("a","b","c"),repo.calls.map { it.first }.toSet()); assertEquals(2,repo.maximumActive)
            assertTrue(repo.calls.all { it.third == null }); assertTrue(c.state.value.previews.getValue("a").segments.sumOf { it.fixes.size } <= 3)
        } finally { gates.values.forEach { it.complete(Unit) }; c.close(); runCurrent() }
    }

    @Test fun offscreenNonCancellableReadCannotPopulateAnotherVisibleRideOrCache() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Repo { id -> if (id == "a") withContext(NonCancellable) {
            entered.complete(Unit); release.await(); page(fixes = listOf(fix(1000,55.0)))
        } else page(fixes = listOf(fix(1000,57.0))) }; val c = component(repo)
        try {
            c.setVisible(listOf(ride("a"))); runCurrent(); assertTrue(entered.isCompleted)
            c.setVisible(listOf(ride("b"))); runCurrent(); release.complete(Unit); runCurrent()
            assertEquals(setOf("b"),c.state.value.previews.keys)
            assertEquals(57.0,c.state.value.previews.getValue("b").segments.single().fixes.single().lat)
            assertEquals(listOf("a","b"),repo.calls.map { it.first })
            c.setVisible(listOf(ride("a"))); runCurrent()
            assertEquals(listOf("a","b","a"),repo.calls.map { it.first }, "A retired late response must not warm the cache")
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }

    @Test fun cacheIsBoundedAndRefreshRevisionRetiresOldGeometry() = runTest {
        val repo = Repo { page() }; val c = component(repo,cacheSize = 1)
        try {
            c.setVisible(listOf(ride("a"))); runCurrent(); c.setVisible(emptyList()); c.setVisible(listOf(ride("a"))); runCurrent()
            assertEquals(listOf("a"),repo.calls.map { it.first })
            c.setVisible(listOf(ride("a")),revision = 1); runCurrent()
            c.setVisible(listOf(ride("b")),revision = 1); runCurrent()
            c.setVisible(listOf(ride("a")),revision = 1); runCurrent()
            assertEquals(listOf("a","a","b","a"),repo.calls.map { it.first })
        } finally { c.close() }
    }

    @Test fun failedReadDoesNotHotLoopAndOnlyExplicitRetryRestartsIt() = runTest {
        var attempts = 0; val repo = Repo { if (++attempts == 1) error("disk error") else page() }; val c = component(repo)
        try {
            c.setVisible(listOf(ride("a"))); runCurrent(); assertEquals(setOf("a"),c.state.value.errors)
            c.setVisible(listOf(ride("a"))); runCurrent(); assertEquals(1,repo.calls.size)
            c.retry("a"); runCurrent(); assertEquals(2,repo.calls.size)
            assertTrue(c.state.value.errors.isEmpty()); assertEquals(setOf("a"),c.state.value.previews.keys)
        } finally { c.close() }
    }

    @Test fun legacyHasNoGpsReadAndOpenOrOmittedPagesCannotClaimCompletePreview() = runTest {
        val repo = Repo { id -> when (id) {
            "partial" -> page(HistoryCursor(2000,"2"))
            "empty-partial" -> HistoryRoutePage(origin,HistoryPage(emptyList(),null,true),emptyList())
            else -> page()
        } }; val c = component(repo)
        try {
            val models = listOf(ride("legacy").copy(status = HistoryRideStatus.LEGACY),ride("partial"),
                ride("open").copy(status = HistoryRideStatus.RECORDING,ended = null),ride("empty-partial"))
            c.setVisible(models); runCurrent()
            assertEquals(setOf("partial","open","empty-partial"),repo.calls.map { it.first }.toSet())
            assertTrue(c.state.value.previews.getValue("legacy").segments.isEmpty()); assertFalse(c.state.value.previews.getValue("legacy").partial)
            for (id in listOf("partial","open","empty-partial")) assertTrue(c.state.value.previews.getValue(id).partial)
            assertTrue(c.state.value.errors.isEmpty())
            assertEquals(Long.MAX_VALUE,repo.calls.single { it.first == "open" }.second.endEpochMillis)
        } finally { c.close() }
    }

    @Test fun malformedSnapshotCannotBecomeAnEmptySuccessfulPreview() = runTest {
        val bad = listOf(page().copy(origin = origin.copy(clockGeneration = 2)),page(fixes = listOf(fix(2000),fix(1000))),
            page(fixes = listOf(fix(6000))),page(HistoryCursor(3000,"2")),
            page().copy(markers = listOf(HistoryMarker(fix(1000).tick.copy(clockGeneration = 2),HistoryMarkerKind.GPS_GAP))),
            page(fixes = List(20001) { fix(1000) }))
        for (snapshot in bad) {
            val repo = Repo { snapshot }; val c = component(repo)
            try { c.setVisible(listOf(ride("a"))); runCurrent(); assertEquals(1,repo.calls.size)
                assertEquals(setOf("a"),c.state.value.errors); assertTrue(c.state.value.previews.isEmpty()) }
            finally { c.close() }
        }
    }

    @Test fun destroyedParentFreezesStateAndCannotRestartAPendingPreview() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = Repo { withContext(NonCancellable) { entered.complete(Unit); release.await(); page() } }
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED); val c = component(repo,lifecycle = lifecycle)
        try {
            c.setVisible(listOf(ride("a"))); runCurrent(); assertTrue(entered.isCompleted)
            lifecycle.destroy(); val before = c.state.value; release.complete(Unit); runCurrent()
            c.setVisible(listOf(ride("b"))); c.retry("a"); runCurrent()
            assertEquals(before,c.state.value); assertEquals(listOf("a"),repo.calls.map { it.first })
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
}
