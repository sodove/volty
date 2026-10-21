package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryRideDeletionSqlTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val start = HistoryTick(1000, 10, 1)
    private val end = HistoryTick(2000, 1010, 1)
    private val id = "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb"
    private val otherId = "aaaaaaaa-1111-4222-8333-cccccccccccc"
    private val tables = listOf("HistorySampleV2", "HistoryGpsV2", "HistoryMarkerV2", "HistoryBucketV2", "HistoryBatchReceiptV2", "HistorySourceV2")
    private class Fixture(val driver: JdbcSqliteDriver, val provider: VoltyDatabaseProvider) {
        val db = provider.database; val store = SqlDelightHistoryStore(provider)
        val favorites = SqlDelightHistoryFavorites(provider) { 3000 }
        val media = HistoryMediaJournal(provider)
        val deletion = SqlDelightHistoryRideDeletion(provider)
        fun count(table: String, rideId: String) = driver.executeQuery(null, "SELECT COUNT(*) FROM $table WHERE rideId = ?", { cursor ->
            check(cursor.next().value); QueryResult.Value(checkNotNull(cursor.getLong(0)))
        }, 1) { bindString(0, rideId) }.value
    }
    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        VoltyDatabase.Schema.create(driver)
        return Fixture(driver, VoltyDatabaseProvider(driver))
    }
    private suspend fun Fixture.closed(rideId: String = "ride"): HistoryRide {
        store.begin(HistoryRide(rideId, "bike", start, null, HistoryRideStatus.RECORDING),
            listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack")))
        store.append(HistoryBatch(rideId, listOf(HistorySample(start, HistorySeriesKey("pack", "voltage_v"), 72.0)),
            listOf(HistoryGpsFix(start, 56.0, 60.0, 3.0, null)), listOf(HistoryMarker(start, HistoryMarkerKind.CONNECTED)), "receipt"))
        db.rideHistoryBucketQueries.putBucket(rideId, "pack", "voltage_v", -1, 10, 1010, 72.0, 72.0, null, 1, 1,
            1000, 10, 2000, 1000, 10, 1000, 10)
        store.finish(rideId, end, HistoryRideStatus.COMPLETED)
        return assertNotNull(store.ride(rideId))
    }
    private suspend fun Fixture.favorite(ride: HistoryRide, name: String = "fav") =
        favorites.save(name, ride.favoriteSource(), HistoryRange(1000, 2000), "Same name")
    private suspend fun expect(reason: HistoryRideDeletionFailure, block: suspend () -> Unit) {
        assertEquals(reason, assertFailsWith<HistoryRideDeletionException> { block() }.reason)
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }

    @Test fun previewEarnsCountsWithoutDeletingAnythingAndCountsPendingCopies() = runTest {
        val f = fixture(); val ride = f.closed(); f.favorite(ride); f.favorite(ride, "second")
        f.media.reserve(id, "fav", "image/png"); f.media.complete(id, 3, "a".repeat(64), null)
        f.media.reserve(otherId, "second", "video/mp4")
        val request = f.deletion.prepare(ride)
        assertEquals(ride, request.ride); assertEquals(2L, request.favoriteCount); assertEquals(2L, request.mediaCount)
        assertNotNull(f.store.ride("ride")); assertEquals(2, f.favorites.page().items.size)
        assertEquals(setOf(HistoryMediaStatus.READY, HistoryMediaStatus.IMPORTING), f.media.all().map { it.status }.toSet())
    }
    @Test fun confirmedV2CascadeExplicitlyDeletesAllPayloadWithForeignKeysOffAndRetiresOnlyItsMedia() = runTest {
        val f = fixture(); val ride = f.closed(); val other = f.closed("other"); f.favorite(ride); f.favorite(other, "keep")
        f.media.reserve(id, "fav", "image/png"); f.media.complete(id, 3, "a".repeat(64), null)
        f.media.reserve(otherId, "keep", "image/png"); f.media.complete(otherId, 3, "b".repeat(64), null)
        tables.forEach { assertEquals(1L, f.count(it, "ride"), it) }
        val result = f.deletion.confirm(f.deletion.prepare(ride))
        assertTrue(result.cleanupPending)
        assertEquals(1L, HistoryPreviewPurgeJournal(f.provider).pendingGeneration())
        assertEquals(ride.favoriteSource(), result.source); assertNull(f.store.ride("ride")); assertNotNull(f.store.ride("other"))
        tables.forEach { assertEquals(0L, f.count(it, "ride"), it); assertEquals(1L, f.count(it, "other"), it) }
        assertNull(f.favorites.get("fav")); assertNotNull(f.favorites.get("keep"))
        assertEquals(HistoryMediaStatus.DELETING, f.media.all().single { it.id == id }.status)
        assertEquals(HistoryMediaStatus.READY, f.media.all().single { it.id == otherId }.status)
    }
    @Test fun newFavoriteAfterPreviewRequiresFreshConfirmationAndMutatesNothing() = runTest {
        val f = fixture(); val ride = f.closed(); val request = f.deletion.prepare(ride); f.favorite(ride)
        expect(HistoryRideDeletionFailure.CONFLICT) { f.deletion.confirm(request) }
        assertNotNull(f.store.ride("ride")); assertNotNull(f.favorites.get("fav"))
    }
    @Test fun newCopyAfterPreviewRequiresFreshConfirmationAndKeepsItsState() = runTest {
        val f = fixture(); val ride = f.closed(); f.favorite(ride); val request = f.deletion.prepare(ride)
        f.media.reserve(id, "fav", "image/png")
        expect(HistoryRideDeletionFailure.CONFLICT) { f.deletion.confirm(request) }
        assertNotNull(f.store.ride("ride")); assertEquals(HistoryMediaStatus.IMPORTING, f.media.all().single().status)
    }
    @Test fun openOrIncoherentV2AndUnclosedLegacyCannotBeDeleted() = runTest {
        val f = fixture(); val open = HistoryRide("ride", "bike", start, null, HistoryRideStatus.RECORDING)
        f.store.begin(open, emptyList())
        expect(HistoryRideDeletionFailure.ACTIVE_RECORDING) { f.deletion.prepare(open) }
        f.db.rideHistoryV2Queries.finishRide(2000, 1010, 1, "RECORDING", "ride")
        expect(HistoryRideDeletionFailure.ACTIVE_RECORDING) { f.deletion.prepare(open) }
        f.db.rideRowQueries.insert("old", "bike", "2020-01-01T00:00:00Z", null)
        expect(HistoryRideDeletionFailure.ACTIVE_RECORDING) { f.deletion.prepare(assertNotNull(LegacyHistoryReader(f.provider).ride("old"))) }
        assertNotNull(f.store.ride("ride")); assertNotNull(f.db.rideRowQueries.selectById("old").executeAsOneOrNull())
    }
    @Test fun sourceIdentityAndIssuingRepositoryFenceStaleConfirmation() = runTest {
        val f = fixture(); val ride = f.closed()
        expect(HistoryRideDeletionFailure.SOURCE_CHANGED) { f.deletion.prepare(ride.copy(vehicleId = "other")) }
        expect(HistoryRideDeletionFailure.SOURCE_CHANGED) { f.deletion.prepare(ride.copy(started = start.copy(clockGeneration = 2))) }
        val request = f.deletion.prepare(ride)
        expect(HistoryRideDeletionFailure.CONFLICT) { SqlDelightHistoryRideDeletion(f.provider).confirm(request) }
        f.db.rideHistoryV2Queries.finishRide(2500, 1510, 1, "COMPLETED", "ride")
        expect(HistoryRideDeletionFailure.CONFLICT) { f.deletion.confirm(request) }
        assertNotNull(f.store.ride("ride"))
    }
    @Test fun legacyDeletionDoesNotTouchV2WithSameIdOrItsFavorites() = runTest {
        val f = fixture(); val v2 = f.closed(); f.favorite(v2)
        f.db.rideRowQueries.insert("ride", "legacy-bike", "1970-01-01T00:00:01Z", "1970-01-01T00:00:02Z")
        f.db.ridePointRowQueries.insert("ride", "voltage", "1970-01-01T00:00:01Z", -1, 72.0, 1)
        val old = assertNotNull(LegacyHistoryReader(f.provider).ride("ride"))
        // Deliberately incoherent twin storage IDs: ordinary favorite creation
        // prefers V2 and cannot construct this isolation fixture for us.
        f.db.historyFavoriteQueries.insertFavorite("old-fav", "ride", "legacy-bike", "LEGACY", 1000, 0, 0,
            1000, 2000, "Old", 1, 3000)
        val request = f.deletion.prepare(old); assertEquals(1L, request.favoriteCount)
        f.deletion.confirm(request)
        assertNull(f.db.rideRowQueries.selectById("ride").executeAsOneOrNull()); assertEquals(0L, f.count("RidePointRow", "ride"))
        assertNull(f.favorites.get("old-fav")); assertNotNull(f.favorites.get("fav")); assertEquals(v2, f.store.ride("ride"))
        tables.forEach { assertEquals(1L, f.count(it, "ride"), it) }
        assertNull(HistoryPreviewPurgeJournal(f.provider).pendingGeneration())
    }
    @Test fun cleanupFailureReportsPendingAfterCommittedDeletionInsteadOfFalseFailure() = runTest {
        val f = fixture(); val ride = f.closed(); f.favorite(ride); f.media.reserve(id, "fav", "image/png")
        var calls = 0
        val deletion = SqlDelightHistoryRideDeletion(f.provider, cleanup = {
            calls++; assertNull(f.store.ride("ride")); assertNull(f.favorites.get("fav"))
            assertEquals(HistoryMediaStatus.DELETING, f.media.all().single().status)
            throw HistoryMediaException(HistoryMediaFailure.STORAGE)
        })
        val result = deletion.confirm(deletion.prepare(ride))
        assertTrue(result.cleanupPending); assertEquals(1, calls); assertNull(f.store.ride("ride"))
    }
    @Test fun legacyRetiredCopiesRemainPendingWhenNoFilesystemCleanerWasProvided() = runTest {
        val f = fixture()
        f.db.rideRowQueries.insert("old", "bike", "1970-01-01T00:00:01Z", "1970-01-01T00:00:02Z")
        val ride = assertNotNull(LegacyHistoryReader(f.provider).ride("old")); f.favorite(ride)
        f.media.reserve(id, "fav", "image/png"); f.media.complete(id, 3, "a".repeat(64), null)
        val result = f.deletion.confirm(f.deletion.prepare(ride))
        assertNull(f.db.rideRowQueries.selectById("old").executeAsOneOrNull())
        assertEquals(HistoryMediaStatus.DELETING, f.media.all().single().status)
        assertTrue(result.cleanupPending)
    }
    @Test fun replayOfConfirmedMissingRideCannotDeleteUnrelatedRecording() = runTest {
        val f = fixture(); val ride = f.closed(); f.closed("keep")
        val request = f.deletion.prepare(ride); f.deletion.confirm(request); f.deletion.confirm(request)
        assertNull(f.store.ride("ride")); assertNotNull(f.store.ride("keep"))
        val replacement = HistoryRide("ride", "different-bike", start.copy(clockGeneration = 2), null, HistoryRideStatus.RECORDING)
        f.store.begin(replacement, emptyList())
        expect(HistoryRideDeletionFailure.SOURCE_CHANGED) { f.deletion.confirm(request) }
        assertEquals(replacement, f.store.ride("ride"))
    }
    @Test fun failingSqlDeleteRollsBackChildRetirementAndEveryPayloadTable() = runTest {
        val f = fixture(); val ride = f.closed(); f.favorite(ride)
        f.media.reserve(id, "fav", "image/png"); f.media.complete(id, 3, "a".repeat(64), null)
        val request = f.deletion.prepare(ride)
        f.driver.execute(null, "CREATE TRIGGER fail_ride_delete BEFORE DELETE ON HistoryRideV2 BEGIN SELECT RAISE(ABORT, 'controlled failure'); END", 0)
        expect(HistoryRideDeletionFailure.STORAGE) { f.deletion.confirm(request) }
        assertEquals(ride, f.store.ride("ride")); assertNotNull(f.favorites.get("fav"))
        assertEquals(HistoryMediaStatus.READY, f.media.all().single().status)
        tables.forEach { assertEquals(1L, f.count(it, "ride"), it) }
        assertNull(HistoryPreviewPurgeJournal(f.provider).pendingGeneration())
    }
    @Test fun acknowledgedPreviewPurgeAfterCommitReportsCompleteCleanup() = runTest {
        val f = fixture(); val ride = f.closed(); val journal = HistoryPreviewPurgeJournal(f.provider)
        var cleared = 0
        val deletion = SqlDelightHistoryRideDeletion(f.provider, cleanup = {
            // Inspect the committed row directly: switching to a real dispatcher
            // inside a virtual 5s deadline can time out before it gets scheduled.
            assertNull(f.db.rideHistoryV2Queries.getRide("ride").executeAsOneOrNull())
            assertTrue(HistoryPreviewPurgeRecovery(journal) { cleared++ }.reconcile())
            HistoryMediaRecovery()
        })
        val result = deletion.confirm(deletion.prepare(ride))
        assertEquals(1, cleared); assertFalse(result.cleanupPending); assertNull(journal.pendingGeneration())
    }
    @Test fun renamingAndTrimmingExistingFavoriteDoesNotExpandWholeRecordingDeletionScope() = runTest {
        val f = fixture(); val ride = f.closed(); val saved = f.favorite(ride)
        val request = f.deletion.prepare(ride)
        f.favorites.save(saved.id, saved.source, HistoryRange(1200, 1800), "Renamed", saved.revision)
        f.deletion.confirm(request)
        assertNull(f.store.ride("ride")); assertNull(f.favorites.get(saved.id))
    }
    @Test fun stalledOptionalCleanupCannotStrandAlreadyCommittedDeletion() = runTest {
        val f = fixture(); val ride = f.closed()
        var entered = 0
        val deletion = SqlDelightHistoryRideDeletion(f.provider, cleanup = {
            entered++; assertNull(f.store.ride("ride")); awaitCancellation()
        })
        val result = deletion.confirm(deletion.prepare(ride))
        assertEquals(1, entered); assertTrue(result.cleanupPending); assertNull(f.store.ride("ride"))
    }
}
