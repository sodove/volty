package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryComponentTest {
    private val origin = HistoryTick(1000, 10, 1)
    private val full = HistoryRange(1000, 5000)
    private val key = HistorySeriesKey("pack", "voltage_v")
    private fun ride(id: String) = HistoryRide(id, "bike", origin, HistoryTick(5000, 4010, 1), HistoryRideStatus.COMPLETED)
    private open inner class Repo : HistoryReadRepository {
        val detailReads = mutableListOf<String>(); val routeReads = mutableListOf<Pair<String, HistoryRange>>()
        var catalogReads = 0
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
            catalogReads++; return HistoryPage(listOf(this@HistoryComponentTest.ride(if (before == null) "a" else "b")),
                if (before == null) HistoryCursor(1000, "a") else null, before == null)
        }
        override suspend fun ride(rideId: String): HistoryRide? { detailReads += rideId; return this@HistoryComponentTest.ride(rideId) }
        override suspend fun sources(rideId: String) = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack"))
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = HistoryRecordedKeys(listOf(key), false)
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int) = HistorySeries(key,
            listOf(HistorySample(HistoryTick(range.startEpochMillis, range.startEpochMillis - 990, 1), key, 63.0)), emptyList(), false, origin)
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) = HistoryStats(63.0, 63.0, null, true)
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryPage<HistoryGpsFix> = error("Use route snapshot")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
            routeReads += rideId to range
            val fix = HistoryGpsFix(HistoryTick(range.startEpochMillis, range.startEpochMillis - 990, 1),
                if (rideId == "a") 55.0 else 56.0, 60.0, 2.0, null)
            return HistoryRoutePage(origin, HistoryPage(listOf(fix), null, false), emptyList())
        }
    }
    private fun TestScope.component(repo: Repo, onBack: () -> Unit = {}) = DefaultHistoryComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo, { HistoryTick(6000, 5010, 1) }, onBack)

    @Test fun explicitPresetSaveApplyUsesCapturedVehicleAndNeverPreloadsOnRead()=runTest {
        val repo=Repo();var saved=emptyList<HistoryMetricPreset>()
        val store=object:HistoryMetricPresetStore{
            override suspend fun read()=saved
            override suspend fun save(preset:HistoryMetricPreset):List<HistoryMetricPreset>{saved=listOf(preset);return saved}
            override suspend fun delete(id:String):List<HistoryMetricPreset>{saved=saved.filterNot{it.id==id};return saved}
        }
        val c=DefaultHistoryComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)),backgroundScope,repo,{origin},{},store)
        try{
            c.loadPresets();runCurrent();assertTrue(repo.detailReads.isEmpty());assertTrue(repo.routeReads.isEmpty())
            c.openRide("a");runCurrent();c.addMetric(key);runCurrent();c.savePreset("  Battery  ");runCurrent()
            assertEquals("Battery",saved.single().name);assertEquals("bike",saved.single().vehicleId)
            c.openRide("b");runCurrent();assertTrue(c.state.value.detail.selectedKeys.isEmpty())
            c.applyPreset(saved.single().id);runCurrent();assertEquals(listOf(key),c.state.value.detail.selectedKeys)
            c.deletePreset(saved.single().id);runCurrent();assertTrue(c.state.value.presets.presets.isEmpty())
        }finally{c.close()}
    }

    @Test fun latePresetSaveDoesNotModifyTheRideOpenedDuringTheWrite()=runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var captured:HistoryMetricPreset?=null
        val store=object:HistoryMetricPresetStore{
            override suspend fun read()=emptyList<HistoryMetricPreset>()
            override suspend fun save(preset:HistoryMetricPreset)=withContext(NonCancellable){captured=preset;entered.complete(Unit);release.await();listOf(preset)}
            override suspend fun delete(id:String)=emptyList<HistoryMetricPreset>()
        }
        val c=DefaultHistoryComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)),backgroundScope,Repo(),{origin},{},store)
        try{
            c.openRide("a");runCurrent();c.addMetric(key);runCurrent();c.savePreset("Pack");runCurrent();assertTrue(entered.isCompleted)
            c.openRide("b");runCurrent();release.complete(Unit);runCurrent()
            assertEquals(listOf(key.metricId),captured!!.metrics.map{it.metricId});assertEquals("b",c.state.value.selectedRideId)
            assertTrue(c.state.value.detail.selectedKeys.isEmpty());assertEquals(listOf(captured),c.state.value.presets.presets)
        }finally{release.complete(Unit);c.close();runCurrent()}
    }

    @Test fun lateRideAndRouteReadsCannotReplaceNewSelectedScreen() = runTest {
        for (holdRoute in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val repo = object : Repo() {
                override suspend fun ride(rideId: String): HistoryRide? {
                    if (!holdRoute && rideId == "a") withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    return super.ride(rideId)
                }
                override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
                    if (holdRoute && rideId == "a") withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    return super.route(rideId, range, after)
                }
            }
            val c = component(repo)
            try {
                c.openRide("a"); runCurrent(); assertTrue(entered.isCompleted)
                c.openRide("b"); runCurrent(); release.complete(Unit); runCurrent()
                assertEquals("b", c.state.value.selectedRideId); assertEquals("b", c.state.value.detail.ride?.id)
                assertEquals("b", c.state.value.route.rideId); assertEquals(56.0, c.state.value.route.fixes.single().lat)
                assertEquals(HistoryDestination.RIDE, c.state.value.destination)
            } finally { release.complete(Unit); c.close(); runCurrent() }
        }
    }

    @Test fun fullscreenBackPreservesSharedRangeCursorMetricsAndCatalog() = runTest {
        val repo = Repo(); var exits = 0; val c = component(repo) { exits++ }
        try {
            c.refresh(); runCurrent(); c.openRide("a"); runCurrent(); c.addMetric(key); runCurrent()
            val viewport = HistoryRange(2000, 4000); c.selectRange(viewport); runCurrent(); c.selectTime(2500)
            c.showCharts(); runCurrent(); assertEquals(HistoryDestination.CHARTS, c.state.value.destination)
            c.back(); runCurrent()
            assertEquals(HistoryDestination.RIDE, c.state.value.destination)
            assertEquals(viewport, c.state.value.detail.range); assertEquals(viewport, c.state.value.route.range)
            assertEquals(2500L, c.state.value.detail.cursorEpochMillis); assertEquals(listOf(key), c.state.value.detail.selectedKeys)
            assertEquals(listOf(63.0), c.state.value.detail.series.getValue(key).samples.map { it.value })
            c.loadOlder(); runCurrent(); assertEquals(listOf("a", "b"), c.state.value.catalog.rides.map { it.id })
            c.back(); runCurrent(); assertEquals(HistoryDestination.CATALOG, c.state.value.destination)
            assertNull(c.state.value.selectedRideId); assertTrue(c.state.value.route.fixes.isEmpty()); assertEquals(0, exits)
            c.back(); assertEquals(1, exits)
        } finally { c.close() }
    }

    @Test fun returningToCatalogRetiresPendingSelectionInsteadOfOpeningItLate() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() {
            override suspend fun ride(rideId: String): HistoryRide? = withContext(NonCancellable) {
                entered.complete(Unit); release.await(); super.ride(rideId)
            }
        }
        val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); assertTrue(entered.isCompleted); c.back()
            release.complete(Unit); runCurrent(); c.addMetric(key); c.showCharts(); runCurrent()
            assertEquals(HistoryDestination.CATALOG, c.state.value.destination); assertNull(c.state.value.detail.ride)
            assertNull(c.state.value.selectedRideId); assertTrue(repo.routeReads.isEmpty())
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }

    @Test fun catalogErrorsDoNotDiscardSelectedRideAndLegacyDoesNotAskForLocation() = runTest {
        val repo = object : Repo() {
            override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> = error("storage error")
            override suspend fun ride(rideId: String) = this@HistoryComponentTest.ride(rideId).copy(status = HistoryRideStatus.LEGACY)
        }
        val c = component(repo)
        try {
            c.openRide("old"); runCurrent(); c.refresh(); runCurrent()
            assertEquals(HistoryReadError.STORAGE, c.state.value.catalog.error); assertNull(c.state.value.detail.error)
            assertEquals("old", c.state.value.detail.ride?.id); assertTrue(c.state.value.route.noRoute)
            assertTrue(repo.routeReads.isEmpty()); assertEquals(HistoryDestination.RIDE, c.state.value.destination)
        } finally { c.close() }
    }

    @Test fun destroyAndParentCancellationRetireAllComposedOwners() = runTest {
        for (destroy in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val repo = object : Repo() {
                override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage =
                    withContext(NonCancellable) { entered.complete(Unit); release.await(); super.route(rideId, range, after) }
            }
            val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
            val parent = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            var exits = 0
            val c = DefaultHistoryComponent(DefaultComponentContext(lifecycle), parent, repo, { origin }, onBack = { exits++ })
            try {
                c.openRide("a"); runCurrent(); assertTrue(entered.isCompleted)
                if (destroy) lifecycle.destroy() else parent.cancel()
                val before = c.state.value; release.complete(Unit); runCurrent(); c.openRide("b"); c.refresh(); c.back(); runCurrent()
                assertEquals(before, c.state.value); assertEquals(0, exits); assertEquals(0, repo.catalogReads)
            } finally { release.complete(Unit); c.close(); parent.cancel(); runCurrent() }
        }
    }

    @Test fun visiblePreviewIdsUseCurrentCatalogAndRefreshRetiresRemovedRows() = runTest {
        val repo = object : Repo() {
            override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
                catalogReads++
                return HistoryPage(listOf(this@HistoryComponentTest.ride(if (catalogReads == 1) "a" else "b")
                    .copy(status = HistoryRideStatus.LEGACY)), null, false)
            }
        }
        val c = component(repo)
        try {
            c.refresh(); runCurrent(); c.setVisibleRides(listOf("unknown","a","a")); runCurrent()
            assertEquals(setOf("a"), c.state.value.previews.previews.keys)
            assertTrue(repo.routeReads.isEmpty()); assertTrue(repo.detailReads.isEmpty())
            c.refresh(); runCurrent()
            assertEquals(listOf("b"),c.state.value.catalog.rides.map { it.id })
            assertTrue(c.state.value.previews.previews.isEmpty())
            c.setVisibleRides(listOf("b")); runCurrent(); assertEquals(setOf("b"),c.state.value.previews.previews.keys)
            c.close(); val before = c.state.value; c.setVisibleRides(listOf("a")); c.retryPreview("b"); c.refresh(); runCurrent()
            assertEquals(before,c.state.value)
        } finally { c.close() }
    }

    @Test fun renderedImagesAreBoundToVisibleRecordedGeometryAndCatalogRevision()=runTest {
        val reads=mutableListOf<HistoryPreviewImageKey>()
        val c=DefaultHistoryComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)),backgroundScope,Repo(),{origin},{},
            imageLoader=HistoryPreviewImageLoader{reads+=it;HistoryPreviewImage(byteArrayOf(1))},previewDispatcher=StandardTestDispatcher(testScheduler))
        try{
            c.refresh();runCurrent();c.setVisibleRides(listOf("a"));runCurrent()
            val known=HistoryPreviewImageKey("a",c.state.value.previewRevision,128,128,false,0,c.state.value.previews.previews.getValue("a").segments)
            c.setVisiblePreviewImages(listOf(known));runCurrent();assertEquals(listOf(known),reads)
            assertTrue(c.state.value.previewImages.images.containsKey("a"))
            for(invalid in listOf(known.copy(rideId="unknown"),known.copy(revision=known.revision+1),known.copy(segments=emptyList()))){
                c.setVisiblePreviewImages(listOf(invalid));runCurrent();assertEquals(1,reads.size);assertTrue(c.state.value.previewImages.images.isEmpty())
            }
            c.refresh();runCurrent();c.setVisiblePreviewImages(listOf(known));runCurrent();assertEquals(1,reads.size)
            val fresh=known.copy(revision=c.state.value.previewRevision)
            c.setVisiblePreviewImages(listOf(fresh));runCurrent();assertEquals(listOf(known,fresh),reads)
            c.close();val closed=c.state.value;c.setVisiblePreviewImages(listOf(known));runCurrent();assertEquals(closed,c.state.value)
        }finally{c.close()}
    }

    @Test fun synchronousSelectionObserverCannotStartSupersededDetailOrRoute() = runTest {
        val repo = Repo(); val c = component(repo); var replaced = false
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { if (it.selectedRideId == "a" && !replaced) { replaced = true; c.openRide("b") } }
        }
        try {
            c.openRide("a"); runCurrent(); assertTrue(replaced)
            assertEquals(listOf("b"), repo.detailReads); assertEquals(listOf("b" to full), repo.routeReads)
            assertEquals("b", c.state.value.detail.ride?.id); assertEquals("b", c.state.value.route.rideId)
        } finally { observer.cancel(); c.close() }
    }
}
