package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryFavoritesSqlTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val origin = HistoryTick(10000, 100, 1)
    private val ride = HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING)
    private fun fixture(): Triple<VoltyDatabaseProvider, SqlDelightHistoryStore, SqlDelightHistoryFavorites> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return Triple(provider, SqlDelightHistoryStore(provider), SqlDelightHistoryFavorites(provider) { 12345 })
    }
    private suspend fun begin(store: SqlDelightHistoryStore) {
        store.begin(ride, listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack")))
        // Wall clock rolls back; favorites use persisted monotonic recording extent.
        store.append(HistoryBatch("ride", listOf(HistorySample(HistoryTick(5000, 1100, 1),
            HistorySeriesKey("pack", "voltage_v"), 72.0)), emptyList(), emptyList()))
    }
    private suspend fun expect(reason: HistoryFavoriteFailure, block: suspend () -> Unit) {
        assertEquals(reason, assertFailsWith<HistoryFavoriteException> { block() }.reason)
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }

    @Test fun createClampsToPersistedExtentAndRoundTripsWithoutChangingRecording() = runTest {
        val (_, store, favorites) = fixture(); begin(store)
        val saved = favorites.save("a", ride.favoriteSource(), HistoryRange(0, 999999), "  Sunday  ")
        assertEquals(HistoryRange(10000, 11000), saved.range)
        assertEquals("Sunday", saved.name); assertEquals(1L, saved.revision)
        assertEquals(saved, favorites.get("a")); assertEquals(ride, store.ride("ride"))
    }
    @Test fun duplicateNamesKeepDistinctIdsAndStablePagedOrder() = runTest {
        val (_, store, favorites) = fixture(); begin(store)
        repeat(23) { favorites.save("f${it.toString().padStart(2, '0')}", ride.favoriteSource(), HistoryRange(10000, 10500), "Same") }
        val first = favorites.page(); assertEquals(20, first.items.size); assertTrue(first.truncated)
        val second = favorites.page(assertNotNull(first.next))
        assertEquals(listOf("f02", "f01", "f00"), second.items.map { it.id })
        assertFalse(second.truncated); assertNull(second.next)
        assertEquals(23, (first.items + second.items).map { it.id }.distinct().size)
    }
    @Test fun renameAndTrimRequireRevisionAndCannotResurrectDeletedFavorite() = runTest {
        val (_, store, favorites) = fixture(); begin(store)
        val saved = favorites.save("a", ride.favoriteSource(), HistoryRange(10000, 11000), "A")
        val edited = favorites.save("a", saved.source, HistoryRange(10200, 10800), "B", saved.revision)
        assertEquals(2L, edited.revision); assertEquals(HistoryRange(10200, 10800), edited.range)
        expect(HistoryFavoriteFailure.CONFLICT) { favorites.delete("a", saved.revision) }
        expect(HistoryFavoriteFailure.CONFLICT) { favorites.save("a", saved.source, saved.range, "Stale", saved.revision) }
        favorites.delete("a", edited.revision); assertNull(favorites.get("a"))
        expect(HistoryFavoriteFailure.CONFLICT) { favorites.save("a", saved.source, saved.range, "Resurrect", edited.revision) }
        assertNotNull(store.ride("ride"))
    }
    @Test fun exactCreateRetryIsIdempotentButConflictingIdIsRejected() = runTest {
        val (_, store, favorites) = fixture(); begin(store)
        val a = favorites.save("a", ride.favoriteSource(), HistoryRange(10000, 11000), "A")
        assertEquals(a, favorites.save("a", a.source, a.range, "A"))
        expect(HistoryFavoriteFailure.CONFLICT) { favorites.save("a", a.source, a.range, "B") }
    }
    @Test fun invalidNamesAndEmptyOrOutsideRangesAreRejected() = runTest {
        val (_, store, favorites) = fixture(); begin(store)
        for (name in listOf(" ", "x".repeat(121))) expect(HistoryFavoriteFailure.INVALID_NAME) {
            favorites.save("a", ride.favoriteSource(), HistoryRange(10000, 11000), name) }
        for (range in listOf(HistoryRange(11000, 11000), HistoryRange(0, 1))) expect(HistoryFavoriteFailure.INVALID_RANGE) {
            favorites.save("a", ride.favoriteSource(), range, "A") }
        assertTrue(favorites.page().items.isEmpty())
    }
    @Test fun noPersistedExtentCannotBeExpandedUsingCurrentTime() = runTest {
        val (_, store, favorites) = fixture()
        store.begin(ride, emptyList())
        expect(HistoryFavoriteFailure.INVALID_RANGE) { favorites.save("a", ride.favoriteSource(), HistoryRange(0, Long.MAX_VALUE), "A") }
    }
    @Test fun sourceMustMatchVehicleClockAndStorageKind() = runTest {
        val (_, store, favorites) = fixture(); begin(store)
        val source = ride.favoriteSource()
        for (other in listOf(source.copy(vehicleId = "other"), source.copy(kind = HistoryStorageKind.LEGACY),
            source.copy(started = origin.copy(clockGeneration = 2)))) expect(HistoryFavoriteFailure.SOURCE_CHANGED) {
            favorites.save("a", other, HistoryRange(10000, 11000), "A") }
        expect(HistoryFavoriteFailure.SOURCE_UNAVAILABLE) { favorites.save("a", source.copy(rideId = "gone"), HistoryRange(10000, 11000), "A") }
    }
    @Test fun favoritePinsV2SourceAgainstUnconfirmedDeletionWithForeignKeysOff() = runTest {
        val (_, store, favorites) = fixture(); begin(store)
        favorites.save("a", ride.favoriteSource(), HistoryRange(10000, 11000), "A")
        assertFailsWith<IllegalStateException> { store.delete("ride") }
        assertNotNull(store.ride("ride")); favorites.delete("a", 1); store.delete("ride")
        assertNull(store.ride("ride"))
    }
    @Test fun closedLegacyFavoriteIsPinnedAgainstPruningAndDelete() = runTest {
        val (provider, _, favorites) = fixture()
        provider.database.rideRowQueries.insert("old", "bike", "2020-01-01T00:00:00Z", "2020-01-01T00:00:10Z")
        provider.database.rideRowQueries.insert("new", "bike", "2021-01-01T00:00:00Z", null)
        val legacy = assertNotNull(LegacyHistoryReader(provider).ride("old"))
        val saved = favorites.save("a", legacy.favoriteSource(), HistoryRange(0, Long.MAX_VALUE), "Old")
        assertEquals(10000, saved.range.endEpochMillis - saved.range.startEpochMillis)
        val old = SqlDelightRideHistoryRepository(provider)
        old.pruneOldest(0)
        assertNotNull(old.loadRide("old")); assertNull(old.loadRide("new"))
        assertFailsWith<IllegalStateException> { old.deleteRide("old") }
        favorites.delete("a", 1); old.deleteRide("old"); assertNull(old.loadRide("old"))
    }
    @Test fun unclosedLegacyUsesActualRecordedBoundsIncludingWallRollback() = runTest {
        val (provider, _, favorites) = fixture(); val db = provider.database
        db.rideRowQueries.insert("old", "bike", "2020-01-01T00:00:10Z", null)
        db.ridePointRowQueries.insert("old", "voltage", "2020-01-01T00:00:05Z", -1, 72.0, 1)
        val legacy = assertNotNull(LegacyHistoryReader(provider).ride("old"))
        val saved = favorites.save("a", legacy.favoriteSource(), HistoryRange(0, Long.MAX_VALUE), "Old")
        assertEquals(5000, saved.range.endEpochMillis - saved.range.startEpochMillis)
        assertNull(db.rideRowQueries.selectById("old").executeAsOne().endedAt)
    }
    @Test fun legacyWriterCannotReplacePinnedRecordingEvenWithSameId() = runTest {
        val (provider, _, favorites) = fixture()
        provider.database.rideRowQueries.insert("old", "bike", "2020-01-01T00:00:00Z", "2020-01-01T00:00:10Z")
        val original = provider.database.rideRowQueries.selectById("old").executeAsOne()
        val legacy = assertNotNull(LegacyHistoryReader(provider).ride("old"))
        favorites.save("a", legacy.favoriteSource(), HistoryRange(0, Long.MAX_VALUE), "Old")
        assertFailsWith<IllegalStateException> { SqlDelightRideHistoryRepository(provider).startRide(
            ru.sodovaya.volty.domain.repository.RideSummary("old", "replacement",
                kotlin.time.Instant.parse("2022-01-01T00:00:00Z"), null)) }
        assertEquals(original, provider.database.rideRowQueries.selectById("old").executeAsOne())
    }
}
