package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryMediaComponentTest {
    private val item = HistoryMediaRecord("media", "a", "image/png", null, 3, "a".repeat(64), HistoryMediaStatus.READY, 3000)
    private open inner class Repo : HistoryMediaRepository {
        val picks = mutableListOf<HistoryMediaPick>(); var reads = 0
        override suspend fun importItem(pick: HistoryMediaPick): HistoryMediaRecord { picks += pick; return item.copy(favoriteId = pick.favoriteId) }
        override suspend fun list(favoriteId: String): HistoryMediaListing { reads++; return HistoryMediaListing(listOf(item.copy(favoriteId = favoriteId)), HistoryMediaRecovery()) }
        override suspend fun delete(id: String) {}
        override suspend fun reconcile() = HistoryMediaRecovery()
        override suspend fun readableUri(id: String) = "content://private/$id"
    }
    private fun TestScope.owner(repo: HistoryMediaRepository?) = HistoryMediaComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo)
    @Test fun selectedFavoriteReadsAndOpeningUsesOnlyRepositoryVerifiedUri() = runTest {
        val repo = Repo(); val c = owner(repo)
        try {
            c.selectFavorite("a"); runCurrent(); assertEquals(0, repo.reads)
            c.refresh(); runCurrent(); assertEquals(listOf(item), c.state.value.items)
            c.open(item); runCurrent(); assertEquals(HistoryMediaOpen(item.id, "content://private/media", "image/png"), c.state.value.open)
            c.consumeOpen(); assertNull(c.state.value.open)
        } finally { c.close() }
    }
    @Test fun pickerCancellationDoesNotImportAndStalePickerCannotAttachToReenteredFavorite() = runTest {
        val repo = Repo(); val c = owner(repo)
        try {
            c.selectFavorite("a"); val cancelled = assertNotNull(c.beginPick())
            c.picked(cancelled, null, null); runCurrent(); assertTrue(repo.picks.isEmpty()); assertNull(c.state.value.picker)
            val stale = assertNotNull(c.beginPick()); c.selectFavorite("b"); c.selectFavorite("a")
            c.picked(stale, "content://picked/old", "image/png"); runCurrent(); assertTrue(repo.picks.isEmpty())
            val current = assertNotNull(c.beginPick()); c.picked(current, "content://picked/new", "image/png"); runCurrent()
            assertEquals(listOf(HistoryMediaPick("a", "content://picked/new", "image/png")), repo.picks)
            assertEquals(listOf(item), c.state.value.items)
        } finally { c.close() }
    }
    @Test fun lateNonCancellableImportCannotPublishInAnotherFavorite() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() { override suspend fun importItem(pick: HistoryMediaPick) = withContext(NonCancellable) {
            entered.complete(Unit); release.await(); super.importItem(pick)
        } }
        val c = owner(repo)
        try {
            c.selectFavorite("a"); c.picked(assertNotNull(c.beginPick()), "content://picked/a", "image/png"); runCurrent()
            assertTrue(entered.isCompleted); c.selectFavorite("b"); release.complete(Unit); runCurrent()
            assertEquals("b", c.state.value.favoriteId); assertTrue(c.state.value.items.isEmpty()); assertNull(c.state.value.open)
            assertEquals("a", repo.picks.single().favoriteId)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun importFailureRetainsListAndReportsTypedReasonWithoutRawExceptionText() = runTest {
        val repo = object : Repo() { override suspend fun importItem(pick: HistoryMediaPick): HistoryMediaRecord {
            throw HistoryMediaException(HistoryMediaFailure.LIMIT)
        } }
        val c = owner(repo)
        try {
            c.selectFavorite("a"); c.refresh(); runCurrent()
            c.picked(assertNotNull(c.beginPick()), "content://picked/a", "image/png"); runCurrent()
            assertEquals(listOf(item), c.state.value.items); assertEquals(HistoryMediaFailure.LIMIT, c.state.value.error); assertFalse(c.state.value.busy)
        } finally { c.close() }
    }
    @Test fun closedOrUnavailableOwnerDoesNotLaunchPickerOrRepositoryWork() = runTest {
        val c = owner(null)
        try { c.selectFavorite("a"); c.refresh(); assertNull(c.beginPick()); runCurrent(); assertFalse(c.state.value.available) }
        finally { c.close() }
        val repo = Repo(); val closed = owner(repo); closed.selectFavorite("a"); closed.close()
        assertNull(closed.beginPick()); closed.refresh(); runCurrent(); assertEquals(0, repo.reads)
    }
    @Test fun cancelBeforeScheduledImportStartsReleasesBusyAndAllowsNewPick() = runTest {
        val repo = Repo(); val c = owner(repo)
        try {
            c.selectFavorite("a")
            c.picked(assertNotNull(c.beginPick()), "content://picked/cancelled", "image/png")
            assertTrue(c.state.value.busy); assertTrue(repo.picks.isEmpty())
            c.cancelImport(); runCurrent()
            assertFalse(c.state.value.busy); assertTrue(repo.picks.isEmpty())
            c.picked(assertNotNull(c.beginPick()), "content://picked/current", "image/png"); runCurrent()
            assertEquals(listOf(HistoryMediaPick("a", "content://picked/current", "image/png")), repo.picks)
        } finally { c.close() }
    }
}
