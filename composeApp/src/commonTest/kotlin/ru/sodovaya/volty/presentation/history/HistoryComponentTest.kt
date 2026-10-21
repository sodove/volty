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
    @Test fun recordingActionsKeepRenderedIdentityRefreshCatalogAndRetireWithScreen() = runTest {
        val reads = Repo()
        val statuses = kotlinx.coroutines.flow.MutableStateFlow(HistoryRecordingStatus(
            available = true, ride = ride("active"), canStop = true))
        val captured = mutableListOf<HistoryRecordingStatus>()
        val calls = mutableListOf<String>()
        val recording = object : HistoryRecordingRepository {
            override val states = statuses
            override fun captureControls(observed: HistoryRecordingStatus): HistoryRecordingControls {
                captured += observed
                return object : HistoryRecordingControls {
                    override suspend fun start(): HistoryRecordingActionResult {
                        calls += "start"; return HistoryRecordingActionResult.Applied
                    }
                    override suspend fun stop(rideId: String): HistoryRecordingActionResult {
                        calls += "stop:$rideId"; return HistoryRecordingActionResult.Applied
                    }
                }
            }
            override suspend fun retryLocation(): HistoryRecordingActionResult {
                calls += "gps"; return HistoryRecordingActionResult.Applied
            }
        }
        val c = DefaultHistoryComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)),
            backgroundScope, reads, { origin }, {}, recordingRepository = recording)
        try {
            runCurrent()
            assertTrue(c.state.value.recording.present)
            val rendered = c.state.value.recording.status
            statuses.value = rendered.copy(ride = ride("replacement")); runCurrent()
            c.stopRecording(rendered)
            assertEquals(listOf(rendered), captured)
            runCurrent()
            assertEquals(listOf("stop:active"), calls)
            assertEquals(1, reads.catalogReads)
            c.retryRecordingLocation(); runCurrent()
            assertEquals(listOf("stop:active", "gps"), calls)
            c.close()
            c.startRecording(rendered.copy(canStart = true)); c.retryRecordingLocation(); runCurrent()
            assertEquals(listOf("stop:active", "gps"), calls)
        } finally { c.close() }
    }
    private val origin = HistoryTick(1000, 10, 1)
    private val full = HistoryRange(1000, 5000)
    private val key = HistorySeriesKey("pack", "voltage_v")
    private fun ride(id: String) = HistoryRide(id, "bike", origin, HistoryTick(5000, 4010, 1), HistoryRideStatus.COMPLETED)
    private open inner class Repo : HistoryReadRepository {
        val detailReads = mutableListOf<String>(); val routeReads = mutableListOf<Pair<String, HistoryRange>>()
        var catalogReads = 0
        val seriesReads = mutableListOf<HistoryRange>()
        val statsReads = mutableListOf<HistoryRange>()
        override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
            catalogReads++; return HistoryPage(listOf(this@HistoryComponentTest.ride(if (before == null) "a" else "b")),
                if (before == null) HistoryCursor(1000, "a") else null, before == null)
        }
        override suspend fun ride(rideId: String): HistoryRide? { detailReads += rideId; return this@HistoryComponentTest.ride(rideId) }
        override suspend fun sources(rideId: String) = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack"))
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = HistoryRecordedKeys(listOf(key), false)
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
            seriesReads += range
            return HistorySeries(key, listOf(HistorySample(HistoryTick(range.startEpochMillis, range.startEpochMillis - 990, 1), key, 63.0)), emptyList(), false, origin)
        }
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
            statsReads += range; return HistoryStats(63.0, 63.0, null, true)
        }
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

    private open inner class Favorites : HistoryFavoriteRepository {
        var saved: HistoryFavorite? = null
        override suspend fun get(id: String) = saved?.takeIf { it.id == id }
        override suspend fun page(before: HistoryCursor?) = HistoryPage(listOfNotNull(saved), null, false)
        override suspend fun save(id: String, source: HistoryFavoriteSource, range: HistoryRange, name: String, expectedRevision: Long?): HistoryFavorite {
            return HistoryFavorite(id, source, range, name, (expectedRevision ?: 0) + 1, 9999).also { saved = it }
        }
        override suspend fun delete(id: String, expectedRevision: Long) { saved = null }
    }
    private fun TestScope.withFavorites(repo: Repo, favorites: Favorites) = DefaultHistoryComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo,
        { HistoryTick(6000, 5010, 1) }, {}, favoriteStore = favorites)

    private inner class Media : HistoryMediaRepository {
        var reads = 0; val picks = mutableListOf<HistoryMediaPick>()
        override suspend fun list(favoriteId: String): HistoryMediaListing {
            reads++; return HistoryMediaListing(listOf(mediaItem(favoriteId)), HistoryMediaRecovery())
        }
        override suspend fun importItem(pick: HistoryMediaPick): HistoryMediaRecord { picks += pick; return mediaItem(pick.favoriteId) }
        override suspend fun delete(id: String) {}
        override suspend fun reconcile() = HistoryMediaRecovery()
        override suspend fun readableUri(id: String) = "content://private/$id"
    }
    private fun mediaItem(favoriteId: String) = HistoryMediaRecord("photo", favoriteId, "image/png", null, 3,
        "a".repeat(64), HistoryMediaStatus.READY, 3000)
    private fun TestScope.withMedia(repo: Repo, store: Favorites, media: Media) = DefaultHistoryComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo,
        { HistoryTick(6000, 5010, 1) }, {}, favoriteStore = store, mediaRepository = media)

    private open inner class Deletion : HistoryRideDeletionRepository {
        val prepared = mutableListOf<HistoryRide>(); val confirmed = mutableListOf<HistoryRideDeletionRequest>()
        override suspend fun prepare(ride: HistoryRide): HistoryRideDeletionRequest {
            prepared += ride; return HistoryRideDeletionRequest(ride, 1, 1, this)
        }
        override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
            confirmed += request; return HistoryRideDeletionResult(request.ride.favoriteSource(), false)
        }
    }
    private fun TestScope.withDeletion(repo: Repo, deletion: Deletion, favorites: Favorites? = null, media: Media? = null) =
        DefaultHistoryComponent(DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo,
            { HistoryTick(6000, 5010, 1) }, {}, favoriteStore = favorites, mediaRepository = media, deletionRepository = deletion)

    @Test fun selectedRideDeletionClearsOwnedSelectionAndMediaAndRefreshesCatalogAndFavorites() = runTest {
        var deleted = false
        val repo = object : Repo() { override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
            val page = super.rides(before); return if (deleted) HistoryPage(emptyList(), null, false) else page
        } }
        val favorites = Favorites().apply { saved = HistoryFavorite("f", ride("a").favoriteSource(), full, "A", 1, 9999) }
        val deletion = object : Deletion() { override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
            val result = super.confirm(request); deleted = true; favorites.saved = null; return result
        } }
        val c = withDeletion(repo, deletion, favorites, Media())
        try {
            c.refresh(); c.loadFavorites(); c.openFavorite(assertNotNull(favorites.saved)); runCurrent(); c.loadMedia(); runCurrent()
            assertEquals(1, c.state.value.media.items.size); assertEquals("a", c.state.value.detail.ride?.id)
            val reads = repo.catalogReads
            c.requestRideDeletion(ride("a")); runCurrent(); assertEquals(1L, c.state.value.deletion.confirmation?.favoriteCount)
            c.confirmRideDeletion(); runCurrent()
            assertEquals(1, deletion.confirmed.size); assertEquals(HistoryDestination.CATALOG, c.state.value.destination)
            assertNull(c.state.value.selectedRideId); assertNull(c.state.value.detail.ride); assertTrue(c.state.value.route.fixes.isEmpty())
            assertNull(c.state.value.selectedFavorite); assertTrue(c.state.value.media.items.isEmpty()); assertNull(c.state.value.media.favoriteId)
            assertTrue(repo.catalogReads > reads); assertTrue(c.state.value.catalog.rides.isEmpty()); assertTrue(c.state.value.favorites.items.isEmpty())
        } finally { c.close() }
    }
    @Test fun completedDeletionCannotStealNewerRideSelection() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val deletion = object : Deletion() { override suspend fun confirm(request: HistoryRideDeletionRequest) = withContext(NonCancellable) {
            val result = super.confirm(request); entered.complete(Unit); release.await(); result
        } }
        val repo = Repo(); val c = withDeletion(repo, deletion)
        try {
            c.openRide("a"); runCurrent(); c.requestRideDeletion(ride("a")); runCurrent(); c.confirmRideDeletion(); runCurrent()
            assertTrue(entered.isCompleted); c.openRide("b"); runCurrent(); release.complete(Unit); runCurrent()
            assertEquals("b", c.state.value.selectedRideId); assertEquals("b", c.state.value.detail.ride?.id)
            assertEquals(HistoryDestination.RIDE, c.state.value.destination); assertEquals(1, repo.detailReads.count { it == "b" })
            assertEquals(ride("a").favoriteSource(), c.state.value.deletion.result?.source)
            c.dismissRideDeletionResult(); assertNull(c.state.value.deletion.result)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun navigationDismissesUnconfirmedDeletionAndLatePrepareCannotReopenIt() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val deletion = object : Deletion() { override suspend fun prepare(ride: HistoryRide) = withContext(NonCancellable) {
            entered.complete(Unit); release.await(); super.prepare(ride)
        } }
        val c = withDeletion(Repo(), deletion)
        try {
            c.openRide("a"); runCurrent(); c.requestRideDeletion(ride("a")); runCurrent(); assertTrue(entered.isCompleted)
            c.openRide("b"); runCurrent(); release.complete(Unit); runCurrent()
            assertNull(c.state.value.deletion.target); assertNull(c.state.value.deletion.confirmation)
            c.confirmRideDeletion(); runCurrent(); assertTrue(deletion.confirmed.isEmpty())
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun committedDeletionRetiresReloadingSelectedSourceBeforeItsLateDetailCanPublish() = runTest {
        val deleteEntered = CompletableDeferred<Unit>(); val deleteRelease = CompletableDeferred<Unit>()
        val detailEntered = CompletableDeferred<Unit>(); val detailRelease = CompletableDeferred<Unit>(); var reads = 0
        val repo = object : Repo() { override suspend fun ride(rideId: String): HistoryRide? {
            if (++reads > 1) withContext(NonCancellable) { detailEntered.complete(Unit); detailRelease.await() }
            return super.ride(rideId)
        } }
        val deletion = object : Deletion() { override suspend fun confirm(request: HistoryRideDeletionRequest) = withContext(NonCancellable) {
            deleteEntered.complete(Unit); deleteRelease.await(); super.confirm(request)
        } }
        val c = withDeletion(repo, deletion)
        try {
            c.openRide("a"); runCurrent(); c.requestRideDeletion(ride("a")); runCurrent(); c.confirmRideDeletion(); runCurrent()
            assertTrue(deleteEntered.isCompleted); c.retrySelectedRide(); runCurrent(); assertTrue(detailEntered.isCompleted)
            assertNull(c.state.value.detail.ride); deleteRelease.complete(Unit); runCurrent()
            assertEquals(HistoryDestination.CATALOG, c.state.value.destination); assertNull(c.state.value.selectedRideId)
            detailRelease.complete(Unit); runCurrent(); assertNull(c.state.value.detail.ride)
        } finally { deleteRelease.complete(Unit); detailRelease.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun closedParentDoesNotIssueDeletionWork() = runTest {
        val deletion = Deletion(); val c = withDeletion(Repo(), deletion)
        c.close(); c.requestRideDeletion(ride("a")); c.confirmRideDeletion(); runCurrent()
        assertTrue(deletion.prepared.isEmpty()); assertTrue(deletion.confirmed.isEmpty())
    }

    @Test fun mediaBindsOnlyAfterQualifiedFavoriteAndClearsOnPlainRide() = runTest {
        val media = Media(); val c = withMedia(Repo(), Favorites(), media)
        val favorite = HistoryFavorite("f", ride("a").favoriteSource(), full, "A", 1, 9999)
        try {
            c.openFavorite(favorite); runCurrent(); assertEquals("f", c.state.value.media.favoriteId)
            assertEquals(0, media.reads); c.loadMedia(); runCurrent()
            assertEquals(listOf(mediaItem("f")), c.state.value.media.items)
            c.openRide("b"); runCurrent(); assertNull(c.state.value.media.favoriteId); assertTrue(c.state.value.media.items.isEmpty())
        } finally { c.close() }
    }
    @Test fun failedFavoriteSourceCannotOpenItsMediaPickerOrLoadAttachments() = runTest {
        val repo = object : Repo() { override suspend fun ride(rideId: String) = this@HistoryComponentTest.ride(rideId).copy(vehicleId = "replacement") }
        val media = Media(); val c = withMedia(repo, Favorites(), media)
        try {
            c.openFavorite(HistoryFavorite("f", ride("a").favoriteSource(), full, "A", 1, 9999)); runCurrent()
            assertNotNull(c.state.value.detail.error); assertNull(c.beginMediaPick())
            c.loadMedia(); runCurrent(); assertEquals(0, media.reads); assertNull(c.state.value.media.favoriteId)
        } finally { c.close() }
    }
    @Test fun savingCurrentFavoriteActivatesItsOwnMediaTarget() = runTest {
        val media = Media(); val c = withMedia(Repo(), Favorites(), media)
        try {
            c.openRide("a"); runCurrent(); c.saveFavorite("New", full); runCurrent()
            val favorite = assertNotNull(c.state.value.selectedFavorite)
            assertEquals(favorite.id, c.state.value.media.favoriteId)
            c.mediaPicked(assertNotNull(c.beginMediaPick()), "content://picked/new", "image/png"); runCurrent()
            assertEquals(favorite.id, media.picks.single().favoriteId)
            assertEquals(listOf(mediaItem(favorite.id)), c.state.value.media.items)
        } finally { c.close() }
    }
    @Test fun parentNavigationRetiresPickerEvenWhenSameFavoriteIsReentered() = runTest {
        val media = Media(); val c = withMedia(Repo(), Favorites(), media)
        val favorite = HistoryFavorite("f", ride("a").favoriteSource(), full, "A", 1, 9999)
        try {
            c.openFavorite(favorite); runCurrent(); val old = assertNotNull(c.beginMediaPick())
            c.openRide("b"); c.openFavorite(favorite); runCurrent()
            c.mediaPicked(old, "content://picked/old", "image/png"); runCurrent()
            assertTrue(media.picks.isEmpty()); assertTrue(c.state.value.media.items.isEmpty())
        } finally { c.close() }
    }

    @Test fun favoriteSaveCapturesOriginalSelectionWithoutSwitchingTheNewRide() = runTest {
        val store = Favorites(); val c = withFavorites(Repo(), store)
        try {
            c.openRide("a"); runCurrent()
            c.saveFavorite("A", HistoryRange(2000, 4000)); c.openRide("b"); runCurrent()
            assertEquals(ride("a").favoriteSource(), assertNotNull(store.saved).source)
            assertEquals(HistoryRange(2000, 4000), store.saved?.range)
            assertEquals("b", c.state.value.detail.ride?.id)
        } finally { c.close() }
    }
    @Test fun openingFavoriteUsesSavedRangeWithWholeRecordingBounds() = runTest {
        val store = Favorites(); val c = withFavorites(Repo(), store)
        val favorite = HistoryFavorite("f", ride("a").favoriteSource(), HistoryRange(2000, 4000), "A", 1, 9999)
        try {
            c.openFavorite(favorite); runCurrent()
            assertEquals(favorite.range, c.state.value.detail.range)
            assertEquals(full, c.state.value.detail.availableRange)
            assertEquals(favorite.id, c.state.value.selectedFavorite?.id)
            c.openRide("b"); runCurrent(); assertNull(c.state.value.selectedFavorite)
        } finally { c.close() }
    }
    @Test fun favoriteCannotBindToReplacedVehicleOrExposeItsRoute() = runTest {
        val repo = object : Repo() { override suspend fun ride(rideId: String) = this@HistoryComponentTest.ride(rideId).copy(vehicleId = "replacement") }
        val c = withFavorites(repo, Favorites())
        try {
            c.openFavorite(HistoryFavorite("f", ride("a").favoriteSource(), full, "A", 1, 9999)); runCurrent()
            assertNull(c.state.value.detail.ride)
            assertNotNull(c.state.value.detail.error)
            assertTrue(repo.routeReads.isEmpty())
        } finally { c.close() }
    }
    @Test fun deletingOpenFavoriteClearsFavoriteSelectionButKeepsRideAndRange() = runTest {
        val store = Favorites(); val c = withFavorites(Repo(), store)
        val favorite = HistoryFavorite("f", ride("a").favoriteSource(), HistoryRange(2000, 4000), "A", 1, 9999)
        try {
            store.saved = favorite; c.loadFavorites(); runCurrent(); c.openFavorite(favorite); runCurrent()
            assertEquals("f", c.state.value.selectedFavorite?.id)
            c.deleteFavorite(favorite); runCurrent()
            assertNull(c.state.value.selectedFavorite)
            assertEquals("a", c.state.value.detail.ride?.id)
            assertEquals(favorite.range, c.state.value.detail.range)
        } finally { c.close() }
    }
    @Test fun editingOpenFavoriteAppliesPersistedRangeToSharedDetail() = runTest {
        val store = Favorites(); val c = withFavorites(Repo(), store)
        val favorite = HistoryFavorite("f", ride("a").favoriteSource(), full, "A", 1, 9999)
        try {
            store.saved = favorite; c.loadFavorites(); runCurrent(); c.openFavorite(favorite); runCurrent()
            val trimmed = HistoryRange(2000, 3000)
            c.saveFavorite("Trimmed", trimmed, favorite); runCurrent()
            assertEquals(trimmed, c.state.value.selectedFavorite?.range)
            assertEquals(trimmed, c.state.value.detail.range)
            assertEquals(full, c.state.value.detail.availableRange)
        } finally { c.close() }
    }
    @Test fun retryingFavoriteNeverDropsSourceQualification() = runTest {
        var replaced = true
        val repo = object : Repo() { override suspend fun ride(rideId: String) = this@HistoryComponentTest.ride(rideId)
            .let { if (replaced) it.copy(vehicleId = "replacement") else it } }
        val c = withFavorites(repo, Favorites())
        val favorite = HistoryFavorite("f", ride("a").favoriteSource(), HistoryRange(2000, 4000), "A", 1, 9999)
        try {
            c.openFavorite(favorite); runCurrent(); assertNotNull(c.state.value.detail.error)
            c.retrySelectedRide(); runCurrent()
            assertNotNull(c.state.value.detail.error); assertTrue(repo.routeReads.isEmpty())
            replaced = false; c.retrySelectedRide(); runCurrent()
            assertNull(c.state.value.detail.error); assertEquals(favorite.range, c.state.value.detail.range)
        } finally { c.close() }
    }
    @Test fun savingCurrentSectionSelectsSavedFavoriteAndShowsItsPersistedRange() = runTest {
        val store = Favorites(); val c = withFavorites(Repo(), store)
        try {
            c.openRide("a"); runCurrent(); c.saveFavorite("New", HistoryRange(2000, 3000)); runCurrent()
            assertEquals(assertNotNull(store.saved), c.state.value.selectedFavorite)
            assertEquals(HistoryRange(2000, 3000), c.state.value.detail.range)
        } finally { c.close() }
    }
    @Test fun lateFavoriteSaveCannotUndoNewerWindowSelection() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = object : Favorites() {
            override suspend fun save(id: String, source: HistoryFavoriteSource, range: HistoryRange, name: String,
                expectedRevision: Long?): HistoryFavorite {
                entered.complete(Unit); release.await(); return super.save(id, source, range, name, expectedRevision)
            }
        }
        val c = withFavorites(Repo(), store)
        try {
            c.openRide("a"); runCurrent(); c.saveFavorite("Earlier", HistoryRange(2000, 3000)); runCurrent()
            assertTrue(entered.isCompleted)
            c.selectRange(HistoryRange(3000, 4000)); runCurrent(); release.complete(Unit); runCurrent()
            assertEquals(HistoryRange(3000, 4000), c.state.value.detail.range)
            assertNull(c.state.value.selectedFavorite); assertNotNull(store.saved)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }

    @Test fun gesturePreviewsIssueNoReadsAndSettlesExactlyOnceForAllOwners() = runTest {
        val repo = Repo(); val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); c.addMetric(key); runCurrent(); c.selectTime(2500)
            val token = assertNotNull(c.beginWindowGesture())
            repeat(50) {
                c.transformWindowGesture(token, 2.0, 0.5, 0.0)
                assertEquals(HistoryRange(2000, 4000), c.state.value.windowPreview)
                c.transformWindowGesture(token, 0.5, 0.5, 0.0)
                assertEquals(full, c.state.value.windowPreview)
            }
            c.transformWindowGesture(token, 2.0, 0.5, 0.0); runCurrent()
            assertEquals(HistoryRange(2000, 4000), c.state.value.windowPreview)
            assertEquals(full, c.state.value.detail.range); assertEquals(listOf(full), repo.seriesReads)
            assertEquals(listOf(full), repo.statsReads); assertEquals(listOf("a" to full), repo.routeReads)
            c.finishWindowGesture(token); runCurrent(); c.finishWindowGesture(token); runCurrent()
            assertNull(c.state.value.windowPreview)
            assertEquals(listOf(full, HistoryRange(2000, 4000)), repo.seriesReads)
            assertEquals(repo.seriesReads, repo.statsReads)
            assertEquals(HistoryRange(2000, 4000), repo.routeReads.last().second)
            assertEquals(2500L, c.state.value.detail.cursorEpochMillis)
        } finally { c.close() }
    }

    @Test fun mapMetricUsesSelectedKeyAndSurvivesWindowCursorAndFullscreenWithoutReads() = runTest {
        val repo = Repo(); val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); c.addMetric(key); runCurrent()
            c.selectMapMetric(key); assertEquals(key, c.state.value.mapMetricKey)
            c.selectTime(1500); c.selectRange(HistoryRange(1000, 2000)); runCurrent()
            val reads = Triple(repo.seriesReads.toList(), repo.statsReads.toList(), repo.routeReads.toList())
            c.showCharts(); c.back(); c.selectMapMetric(key.copy(sourceId = "unselected")); runCurrent()
            assertEquals(key, c.state.value.mapMetricKey); assertEquals(1500L, c.state.value.detail.cursorEpochMillis)
            assertEquals(HistoryRange(1000, 2000), c.state.value.detail.range)
            assertEquals(reads, Triple(repo.seriesReads.toList(), repo.statsReads.toList(), repo.routeReads.toList()))
            c.selectMapMetric(null); assertNull(c.state.value.mapMetricKey)
        } finally { c.close() }
    }

    @Test fun removingMapSeriesOrChangingRideClearsMapChoiceAndCloseIgnoresCommands() = runTest {
        val repo = Repo(); val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); c.addMetric(key); runCurrent(); c.selectMapMetric(key)
            assertEquals(key, c.state.value.mapMetricKey)
            c.removeMetric(key); runCurrent(); assertNull(c.state.value.mapMetricKey)
            c.addMetric(key); runCurrent(); c.selectMapMetric(key); assertEquals(key, c.state.value.mapMetricKey)
            c.openRide("b"); assertNull(c.state.value.mapMetricKey); runCurrent()
            c.addMetric(key); runCurrent(); c.selectMapMetric(key); assertEquals(key, c.state.value.mapMetricKey)
            c.back(); assertNull(c.state.value.mapMetricKey)
            c.openRide("a"); runCurrent(); c.addMetric(key); runCurrent(); c.selectMapMetric(key)
            assertEquals(key, c.state.value.mapMetricKey); c.close()
            val frozen = c.state.value; c.selectMapMetric(null); assertEquals(frozen, c.state.value)
        } finally { c.close() }
    }

    @Test fun cancelledAndSupersededGestureCannotClearTheNewPreviewOrCommit() = runTest {
        val repo = Repo(); val c = component(repo)
        try {
            c.openRide("a"); runCurrent()
            val old = assertNotNull(c.beginWindowGesture()); c.transformWindowGesture(old, 2.0, 0.5, 0.0)
            c.cancelWindowGesture(old); assertNull(c.state.value.windowPreview)
            val fresh = assertNotNull(c.beginWindowGesture()); c.transformWindowGesture(fresh, 2.0, 0.0, 0.0)
            c.cancelWindowGesture(old); c.finishWindowGesture(old); runCurrent()
            assertEquals(HistoryRange(1000, 3000), c.state.value.windowPreview)
            assertEquals(listOf("a" to full), repo.routeReads)
            c.cancelWindowGesture(fresh); assertNull(c.state.value.windowPreview); assertEquals(full, c.state.value.detail.range)
        } finally { c.close() }
    }

    @Test fun explicitRangeCommandRetiresPendingGesture() = runTest {
        val repo = Repo(); val c = component(repo)
        try {
            c.openRide("a"); runCurrent(); val token = assertNotNull(c.beginWindowGesture())
            c.transformWindowGesture(token, 2.0, 0.5, 0.0)
            assertEquals(HistoryRange(2000, 4000), c.state.value.windowPreview)
            val buttonWindow = HistoryRange(1000, 2000); c.selectRange(buttonWindow); runCurrent()
            c.finishWindowGesture(token); runCurrent()
            assertNull(c.state.value.windowPreview); assertEquals(buttonWindow, c.state.value.detail.range)
            assertEquals(listOf("a" to full, "a" to buttonWindow), repo.routeReads)
        } finally { c.close() }
    }

    @Test fun rideNavigationAndCloseFenceLateGestureCallbacks() = runTest {
        for (action in 0..2) {
            val repo = Repo(); val c = component(repo)
            try {
                c.openRide("a"); runCurrent(); c.showCharts()
                val token = assertNotNull(c.beginWindowGesture()); c.transformWindowGesture(token, 2.0, 0.5, 0.0)
                assertEquals(HistoryRange(2000, 4000), c.state.value.windowPreview)
                when (action) { 0 -> c.openRide("b"); 1 -> c.back(); else -> c.close() }
                runCurrent(); val snapshot = c.state.value
                c.transformWindowGesture(token, 2.0, 0.5, 0.0); c.finishWindowGesture(token); runCurrent()
                assertEquals(snapshot, c.state.value)
                assertFalse(repo.routeReads.any { it.second != full })
                if (action != 2) assertNull(c.state.value.windowPreview)
            } finally { c.close() }
        }
    }

    @Test fun synchronousNavigationDuringPreviewRetirementStopsOldCommit() = runTest {
        val repo = Repo(); val c = component(repo); var sawPreview = false
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.state.collect { state ->
                if (state.windowPreview != null) sawPreview = true
                else if (sawPreview && state.selectedRideId == "a") { sawPreview = false; c.openRide("b") }
            }
        }
        try {
            c.openRide("a"); runCurrent(); val token = assertNotNull(c.beginWindowGesture())
            c.transformWindowGesture(token, 2.0, 0.5, 0.0); assertTrue(sawPreview)
            c.finishWindowGesture(token); runCurrent()
            assertEquals("b", c.state.value.selectedRideId); assertEquals(full, c.state.value.detail.range)
            assertEquals(listOf("a" to full, "b" to full), repo.routeReads)
        } finally { observer.cancel(); c.close() }
    }

    @Test fun invalidUpdateAndNoOpCompletionDoNotStartReads() = runTest {
        val repo = Repo(); val c = component(repo)
        try {
            assertNull(c.beginWindowGesture()); c.openRide("a"); runCurrent()
            val token = assertNotNull(c.beginWindowGesture())
            c.transformWindowGesture(token, Double.NaN, 0.5, 0.0)
            assertEquals(full, c.state.value.windowPreview)
            c.finishWindowGesture(token); runCurrent()
            assertEquals(listOf("a" to full), repo.routeReads); assertNull(c.state.value.windowPreview)
        } finally { c.close() }
    }

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
