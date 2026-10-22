package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryFavoritesComponentTest {
    private val source = HistoryFavoriteSource("ride", "bike", HistoryStorageKind.V2, HistoryTick(1000, 10, 1))
    private val favorite = HistoryFavorite("f", source, HistoryRange(1000, 2000), "Ride", 1, 3000)
    private open inner class Store : HistoryFavoriteRepository {
        var reads = 0; var writes = 0
        var items = listOf(favorite)
        override suspend fun page(before: HistoryCursor?): HistoryPage<HistoryFavorite> {
            reads++; return HistoryPage(items, null, false)
        }
        override suspend fun get(id: String) = items.singleOrNull { it.id == id }
        override suspend fun save(id: String, source: HistoryFavoriteSource, range: HistoryRange, name: String, expectedRevision: Long?): HistoryFavorite {
            writes++
            return HistoryFavorite(id, source, range, name, (expectedRevision ?: 0) + 1, 3000)
        }
        override suspend fun delete(id: String, expectedRevision: Long) { writes++ }
    }
    private fun TestScope.owner(store: HistoryFavoriteRepository?) = HistoryFavoritesComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, store)

    @Test fun readsAreExplicitAndSaveDeletePublishPersistedResult() = runTest {
        val store = Store(); val c = owner(store)
        try {
            runCurrent(); assertEquals(0, store.reads)
            c.refresh(); runCurrent(); assertEquals(listOf(favorite), c.state.value.items)
            c.save("f", source, HistoryRange(1200, 1800), "Edited", 1); runCurrent()
            assertEquals("Edited", c.state.value.items.single().name)
            assertEquals(2L, c.state.value.items.single().revision)
            assertEquals("f", c.state.value.savedId)
            c.delete(c.state.value.items.single()); runCurrent()
            assertTrue(c.state.value.items.isEmpty()); assertEquals(2, store.writes)
        } finally { c.close() }
    }
    @Test fun failuresKeepListAndExposeTypedReasonWithExplicitRetry() = runTest {
        var fail = false
        val store = object : Store() {
            override suspend fun page(before: HistoryCursor?): HistoryPage<HistoryFavorite> {
                if (fail) error("disk")
                return super.page(before)
            }
            override suspend fun delete(id: String, expectedRevision: Long) {
                throw HistoryFavoriteException(HistoryFavoriteFailure.CONFLICT)
            }
        }
        val c = owner(store)
        try {
            c.refresh(); runCurrent(); fail = true; c.refresh(); runCurrent()
            assertEquals(listOf(favorite), c.state.value.items); assertEquals(HistoryFavoritesError.STORAGE, c.state.value.error)
            c.delete(favorite); runCurrent(); assertEquals(HistoryFavoritesError.CONFLICT, c.state.value.error)
            fail = false; c.refresh(); runCurrent(); assertNull(c.state.value.error)
        } finally { c.close() }
    }
    @Test fun heldWriteBlocksDuplicateCommandsAndCloseRejectsLatePublication() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = object : Store() {
            override suspend fun save(id: String, source: HistoryFavoriteSource, range: HistoryRange, name: String, expectedRevision: Long?) = withContext(NonCancellable) {
                entered.complete(Unit); release.await(); super.save(id, source, range, name, expectedRevision)
            }
        }
        val c = owner(store)
        try {
            c.refresh(); runCurrent(); c.save("f", source, favorite.range, "Later", 1); runCurrent()
            assertTrue(entered.isCompleted); assertTrue(c.state.value.busy)
            c.refresh(); c.delete(favorite); runCurrent(); assertEquals(1, store.reads); assertEquals(0, store.writes)
            c.close(); val closed = c.state.value; release.complete(Unit); runCurrent()
            assertEquals(closed, c.state.value); assertEquals(1, store.writes)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun pagingAppendsDistinctIdsAndDoesNotInventContinuation() = runTest {
        val cursor = HistoryCursor(3000, "f")
        val store = object : Store() {
            override suspend fun page(before: HistoryCursor?) = if (before == null) HistoryPage(listOf(favorite), cursor, true)
                else HistoryPage(listOf(favorite, favorite.copy(id = "older")), null, false)
        }
        val c = owner(store)
        try {
            c.refresh(); runCurrent(); c.loadOlder(); runCurrent()
            assertEquals(listOf("f", "older"), c.state.value.items.map { it.id }); assertNull(c.state.value.next)
        } finally { c.close() }
    }
    @Test fun unavailableStoreNeverClaimsSuccess() = runTest {
        val c = owner(null)
        try { c.refresh(); c.loadOlder(); c.save("f", source, favorite.range, "A"); c.delete(favorite); runCurrent()
            assertEquals(HistoryFavoritesState(), c.state.value)
        } finally { c.close() }
    }
}
