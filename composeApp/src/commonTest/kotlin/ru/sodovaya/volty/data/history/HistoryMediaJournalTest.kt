package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryMediaJournalTest {
    private val id = "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb"
    private val otherId = "aaaaaaaa-1111-4222-8333-cccccccccccc"
    private val hash = "a".repeat(64)
    private val limits = HistoryMediaLimits(8, 24, 16, 2, 3)
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private fun fixture(): Triple<VoltyDatabaseProvider, SqlDelightHistoryFavorites, HistoryMediaJournal> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        provider.database.rideHistoryV2Queries.insertRide("ride", "bike", 1000, 10, 1, "RECORDING")
        provider.database.rideHistoryV2Queries.finishRide(2000, 1010, 1, "COMPLETED", "ride")
        return Triple(provider, SqlDelightHistoryFavorites(provider) { 3000 }, HistoryMediaJournal(provider, limits))
    }
    private suspend fun favorite(store: SqlDelightHistoryFavorites, id: String = "favorite") = store.save(id,
        HistoryFavoriteSource("ride", "bike", HistoryStorageKind.V2, HistoryTick(1000, 10, 1)), HistoryRange(1000, 2000), "Favorite")
    private fun expect(reason: HistoryMediaFailure, block: () -> Unit) {
        assertEquals(reason, assertFailsWith<HistoryMediaException> { block() }.reason)
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }

    @Test fun favoriteCleanupRunsAfterCommittedRetirementAndFailureDoesNotUndoDeletion() = runTest {
        val (provider, initial, journal) = fixture(); favorite(initial)
        journal.reserve(id, "favorite", "image/png"); journal.complete(id, 3, hash, null)
        var calls = 0
        val favorites = SqlDelightHistoryFavorites(provider, onDeleted = {
            calls++
            assertNull(provider.database.historyFavoriteQueries.getFavorite("favorite").executeAsOneOrNull())
            assertEquals(HistoryMediaStatus.DELETING, journal.all().single().status)
            throw HistoryMediaException(HistoryMediaFailure.STORAGE)
        }) { 3000 }
        favorites.delete("favorite", 1)
        assertEquals(1, calls)
        assertNull(favorites.get("favorite"))
        assertEquals(HistoryMediaStatus.DELETING, journal.all().single().status)
    }

    @Test fun reservationIsHiddenUntilCompletionAndUnknownCaptureTimeStaysUnknown() = runTest {
        val (provider, favorites, journal) = fixture(); favorite(favorites)
        val pending = journal.reserve(id, "favorite", "image/png")
        assertEquals(HistoryMediaStatus.IMPORTING, pending.status); assertTrue(journal.ready("favorite").isEmpty())
        val complete = journal.complete(id, 3, hash, null)
        assertEquals(HistoryMediaStatus.READY, complete.status); assertNull(complete.capturedEpochMillis)
        assertEquals(3, complete.bytes); assertEquals(hash, complete.sha256)
        assertEquals(listOf(complete), HistoryMediaJournal(provider, limits).ready("favorite"))
        assertFailsWith<IllegalStateException> { journal.forgetDeleted(id) }
        assertEquals(listOf(complete), journal.all())
    }
    @Test fun deletingFavoriteRetiresItsPendingAndReadyMediaAtomicallyAndCannotReattach() = runTest {
        val (_, favorites, journal) = fixture(); favorite(favorites)
        journal.reserve(id, "favorite", "image/png"); journal.complete(id, 3, hash, 1500)
        journal.reserve(otherId, "favorite", "video/mp4")
        favorites.delete("favorite", 1)
        assertNull(favorites.get("favorite")); assertTrue(journal.ready("favorite").isEmpty())
        assertTrue(journal.all().all { it.status == HistoryMediaStatus.DELETING })
        favorite(favorites) // Even reusing this parent ID cannot revive the previous pending import.
        expect(HistoryMediaFailure.FAVORITE_UNAVAILABLE) { journal.complete(otherId, 4, hash, null) }
    }
    @Test fun reservationsAccountForFullFileCapAndDeletionDoesNotFreeQuotaBeforeCleanup() = runTest {
        val (_, favorites, journal) = fixture(); favorite(favorites)
        journal.reserve(id, "favorite", "image/png"); journal.reserve(otherId, "favorite", "image/png")
        expect(HistoryMediaFailure.LIMIT) { journal.reserve("dddddddd-1111-4222-8333-cccccccccccc", "favorite", "image/png") }
        journal.retire(id)
        expect(HistoryMediaFailure.LIMIT) { journal.reserve("dddddddd-1111-4222-8333-cccccccccccc", "favorite", "image/png") }
        journal.forgetDeleted(id)
        journal.reserve("dddddddd-1111-4222-8333-cccccccccccc", "favorite", "image/png")
        assertEquals(2, journal.all().size)
    }
    @Test fun recoveryRetiresIncompleteOrOrphanedRowsAndPreservesFinishedMetadata() = runTest {
        val (provider, favorites, journal) = fixture(); favorite(favorites); favorite(favorites, "other")
        journal.reserve(id, "favorite", "image/png"); journal.complete(id, 3, hash, 1500)
        journal.reserve(otherId, "other", "image/png")
        val recovery = HistoryMediaJournal(provider, limits)
        recovery.retireIncomplete()
        assertEquals(HistoryMediaStatus.READY, recovery.all().single { it.id == id }.status)
        assertEquals(HistoryMediaStatus.DELETING, recovery.all().single { it.id == otherId }.status)
        provider.database.historyFavoriteQueries.deleteFavorite("favorite") // Simulated old/crashed deletion path.
        recovery.retireIncomplete()
        assertTrue(recovery.all().all { it.status == HistoryMediaStatus.DELETING })
        recovery.all().forEach { recovery.forgetDeleted(it.id) }
        assertTrue(recovery.all().isEmpty())
    }
    @Test fun invalidMimeOrMetadataNeverBecomesReadyAndMissingFavoriteCannotReserve() = runTest {
        val (_, favorites, journal) = fixture(); favorite(favorites)
        expect(HistoryMediaFailure.INVALID_MEDIA) { journal.reserve(id, "favorite", "application/octet-stream") }
        expect(HistoryMediaFailure.FAVORITE_UNAVAILABLE) { journal.reserve(id, "gone", "image/png") }
        journal.reserve(id, "favorite", "image/png")
        for ((bytes, digest) in listOf(0L to hash, 9L to hash, 3L to "bad")) expect(HistoryMediaFailure.INVALID_MEDIA) {
            journal.complete(id, bytes, digest, null) }
        assertTrue(journal.ready("favorite").isEmpty())
        assertEquals(HistoryMediaStatus.IMPORTING, journal.all().single().status)
    }
    @Test fun importingRowCannotBeForgottenBeforeRetirement() = runTest {
        val (_, favorites, journal) = fixture(); favorite(favorites)
        journal.reserve(id, "favorite", "image/png")
        assertFailsWith<IllegalStateException> { journal.forgetDeleted(id) }
        assertEquals(HistoryMediaStatus.IMPORTING, journal.all().single().status)
    }
    @Test fun sqliteItselfRejectsReadyRowsWithMissingHashEvenWhenBytesArePresent() = runTest {
        val (provider, favorites, journal) = fixture(); favorite(favorites)
        journal.reserve(id, "favorite", "image/png")
        assertFailsWith<Exception> { provider.database.historyMediaQueries.completeMedia(3, null, null, "image/png", id) }
        assertEquals(HistoryMediaStatus.IMPORTING, journal.all().single().status)
    }
    @Test fun creationMetadataUsesItsOwnClockWithoutInventingCaptureTime() = runTest {
        val (provider, favorites, _) = fixture(); favorite(favorites)
        val journal = HistoryMediaJournal(provider, limits) { 12345 }
        val reserved = journal.reserve(id, "favorite", "image/png")
        assertEquals(12345L, reserved.createdEpochMillis)
        val ready = journal.complete(id, 3, hash, null)
        assertEquals(12345L, ready.createdEpochMillis); assertNull(ready.capturedEpochMillis)
    }
}
