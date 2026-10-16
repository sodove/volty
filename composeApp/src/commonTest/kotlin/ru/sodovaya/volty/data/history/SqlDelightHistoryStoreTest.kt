package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class SqlDelightHistoryStoreTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val origin = HistoryTick(10000, 100, 1)
    private val range = HistoryRange(10000, 11000)
    private val key = HistorySeriesKey("pack", "current_a")
    private fun tick(delta: Long) = HistoryTick(10000 + delta, 100 + delta, 1)
    private fun fixture(): Pair<JdbcSqliteDriver, SqlDelightHistoryStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        drivers += driver
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        VoltyDatabase.Schema.create(driver)
        return driver to SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
    }
    private suspend fun begin(store: SqlDelightHistoryStore, id: String = "ride") {
        store.begin(HistoryRide(id, "bike", origin, null, HistoryRideStatus.RECORDING),
            listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Battery A")))
    }
    @AfterTest fun closeDrivers() { drivers.forEach { it.close() } }

    @Test fun sourceScopedMarkersRoundTripAndExactReceiptRetriesKeepTheirScope() = runTest {
        val (_, store) = fixture(); begin(store)
        val markers = listOf(HistoryMarker(tick(10), HistoryMarkerKind.BLE_GAP, "pack"),
            HistoryMarker(tick(20), HistoryMarkerKind.CONNECTED, "pack"))
        val batch = HistoryBatch("ride", emptyList(), emptyList(), markers, "scoped")
        store.append(batch)
        assertEquals(markers, store.markers("ride", range))
        store.append(batch)
        assertEquals(markers, store.markers("ride", range))
        assertFailsWith<IllegalArgumentException> {
            store.append(batch.copy(markers = markers.map { it.copy(sourceId = null) }))
        }
    }

    @Test fun sourceScopedGapCannotMakeHealthyPeerSqlStatisticsIncomplete() = runTest {
        val (_, store) = fixture()
        val ride = HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING)
        val sources = listOf("pack", "peer").map { HistorySource(it, HistorySourceKind.BATTERY, it) }
        store.begin(ride, sources)
        val raw = listOf("pack", "peer").flatMap { id -> listOf(0L, 200L).map {
            HistorySample(tick(it), HistorySeriesKey(id, "power_w"), 100.0)
        } }
        val gap = HistoryMarker(tick(100), HistoryMarkerKind.BLE_GAP, "pack")
        val projected = HistoryRecordingProjection(ride, sources).prepare(
            HistoryBatch("ride", raw, emptyList(), listOf(gap), "scoped-projection"), true).batch
        assertTrue(projected.buckets.single { it.key.sourceId == "peer" }.complete)
        assertFalse(projected.buckets.single { it.key.sourceId == "pack" }.complete)
        store.append(projected); store.finish("ride", tick(200), HistoryRideStatus.COMPLETED)
        assertTrue(store.stats("ride", HistorySeriesKey("peer", "power_w"), range).complete)
        assertFalse(store.stats("ride", HistorySeriesKey("pack", "power_w"), range).complete)
    }

    @Test fun seriesReadsOnlyExposeItsOwnScopedAndGlobalBoundaries() = runTest {
        val (_, store) = fixture()
        store.begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING),
            listOf("pack", "peer").map { HistorySource(it, HistorySourceKind.BATTERY, it) })
        val rows = listOf("pack", "peer").flatMap { id -> listOf(0L, 200L).map {
            HistorySample(tick(it), HistorySeriesKey(id, "current_a"), 7.0)
        } }
        val scoped = HistoryMarker(tick(100), HistoryMarkerKind.BLE_GAP, "pack")
        store.append(HistoryBatch("ride", rows, emptyList(), listOf(scoped)))
        val reader = DefaultHistoryReadRepository(store)
        assertTrue(reader.series("ride", key.copy(sourceId = "peer"), range).markers.isEmpty())
        assertEquals(listOf(scoped), reader.series("ride", key, range).markers)
        val global = scoped.copy(sourceId = null)
        store.append(HistoryBatch("ride", emptyList(), emptyList(), listOf(global)))
        assertEquals(listOf(global), reader.series("ride", key.copy(sourceId = "peer"), range).markers)
    }

    @Test fun oldRowBasedReceiptReplaysExactSourceScopeAndRejectsAScopeChange() = runTest {
        val (driver, store) = fixture(); begin(store)
        val marker = HistoryMarker(tick(10), HistoryMarkerKind.BLE_GAP, "pack")
        val batch = HistoryBatch("ride", emptyList(), emptyList(), listOf(marker), "row-receipt")
        store.append(batch)
        driver.execute(null, "UPDATE HistoryBatchReceiptV2 SET fingerprint=NULL WHERE rideId='ride'", 0)
        store.append(batch)
        assertEquals(listOf(marker), store.markers("ride", range))
        assertFailsWith<IllegalArgumentException> {
            store.append(batch.copy(markers = listOf(marker.copy(sourceId = null))))
        }
    }

    @Test fun foreignScopedMarkerRollsBackAnAlreadyInsertedValidSample() = runTest {
        val (_, store) = fixture(); begin(store)
        val point = HistorySample(tick(10), key, 7.0)
        val invalid = HistoryBatch("ride", listOf(point), emptyList(),
            listOf(HistoryMarker(tick(20), HistoryMarkerKind.BLE_GAP, "foreign")), "invalid")
        assertFailsWith<IllegalArgumentException> { store.append(invalid) }
        assertTrue(store.samples("ride", key, range, null).items.isEmpty())
        assertTrue(store.markers("ride", range).isEmpty())
        store.append(invalid.copy(markers = emptyList()))
        assertEquals(listOf(point), store.samples("ride", key, range, null).items)
    }

    @Test fun applicationHistoryBindingsShareOneStoreWithoutDispatcherRegistration() = runTest {
        val (driver, _) = fixture()
        val app = org.koin.dsl.koinApplication {
            modules(ru.sodovaya.volty.di.appModule, org.koin.dsl.module {
                single { VoltyDatabaseProvider(driver) }
            })
        }
        try {
            val store = app.koin.get<SqlDelightHistoryStore>()
            assertSame(store, app.koin.get<HistoryStore>())
            assertEquals(1L, store.prepareRecordingClock())
        } finally { app.close() }
    }

    @Test fun summaryStatsIncludeEarnedDetailExtremumBeyondFirstPage() = runTest {
        val (_, store) = fixture(); begin(store)
        val point = HistorySample(origin, key, -1.0)
        // Deliberately incoherent with producer habits: certified prefix plus
        // an additional raw tail. Reading must consider all explicit evidence.
        val prefix = List(20000) { point }
        val bucket = HistoryBucket(key, 0, origin, origin, point, point, null, 20000, true)
        store.append(HistoryBatch("ride", prefix + point.copy(value = -5.0), emptyList(), emptyList(),
            "tail-extremum", listOf(bucket)))
        assertNotNull(store.samples("ride", key, range, null).next)
        val stats = store.stats("ride", key, range)
        assertEquals(-5.0, stats.minimum); assertEquals(-1.0, stats.maximum)
    }

    @Test fun summaryStatsCannotIgnoreUnknownDetailTailBeyondFirstPage() = runTest {
        val (_, store) = fixture(); begin(store)
        val point = HistorySample(origin, key, -1.0)
        val prefix = List(20000) { point }
        val bucket = HistoryBucket(key, 0, origin, origin, point, point, null, 20000, true)
        val absent = HistorySample(origin, key, null, HistoryUnknownReason.PARTIAL_SOURCE)
        store.append(HistoryBatch("ride", prefix + absent, emptyList(), emptyList(), "tail-unknown", listOf(bucket)))
        assertNotNull(store.samples("ride", key, range, null).next)
        val stats = store.stats("ride", key, range)
        assertEquals(-1.0, stats.minimum); assertEquals(-1.0, stats.maximum)
        assertFalse(stats.complete, "Removing the page-truncated check alone would hide this unknown tail")
    }

    @Test fun processClockPreparationRecoversAtLastCommitAndUsesGreatestGeneration() = runTest {
        val (_, store) = fixture()
        begin(store)
        store.append(HistoryBatch("ride", listOf(HistorySample(tick(25), key, 7.0)), emptyList(), emptyList()))
        val later = HistoryTick(5000, 50, 9)
        store.begin(HistoryRide("closed", "bike", later, null, HistoryRideStatus.RECORDING), emptyList())
        store.finish("closed", later, HistoryRideStatus.COMPLETED)

        assertEquals(10L, store.prepareRecordingClock())
        val recovered = assertNotNull(store.ride("ride"))
        assertEquals(HistoryRideStatus.INTERRUPTED, recovered.status)
        assertEquals(tick(25), recovered.ended)
        assertEquals(listOf(HistoryMarker(tick(25), HistoryMarkerKind.INTERRUPTED)),
            store.markers("ride", range))
        assertEquals(HistoryRideStatus.COMPLETED, store.ride("closed")?.status)
        // A retry before any new ride writes must not append a second interruption.
        assertEquals(10L, store.prepareRecordingClock())
        assertEquals(1, store.markers("ride", range).size)
        val fresh = HistoryTick(30000, 10, 10)
        store.begin(HistoryRide("new", "bike", fresh, null, HistoryRideStatus.RECORDING), emptyList())
        assertEquals(11L, store.prepareRecordingClock())
        assertEquals(fresh, store.ride("new")?.ended)
    }

    @Test fun emptyDatabaseHasUsableNonnegativeProcessClock() = runTest {
        val (_, store) = fixture()
        assertEquals(1L, store.prepareRecordingClock())
        assertTrue(store.rides(null, 10).items.isEmpty())
    }

    @Test fun exhaustedClockFailsBeforeChangingOpenRide() = runTest {
        val (_, store) = fixture()
        val start = HistoryTick(10000, 100, Long.MAX_VALUE)
        store.begin(HistoryRide("exhausted", "bike", start, null, HistoryRideStatus.RECORDING), emptyList())
        assertFailsWith<IllegalStateException> { store.prepareRecordingClock() }
        assertEquals(HistoryRideStatus.RECORDING, store.ride("exhausted")?.status)
        assertNull(store.ride("exhausted")?.ended)
        assertTrue(store.markers("exhausted", range).isEmpty())
    }

    @Test fun gpsProvidersAndUnknownSurviveEqualTimePagingAndIdempotentReplay() = runTest {
        val (_, store) = fixture(); begin(store)
        val fixes = listOf(HistoryGpsProvider.GPS, HistoryGpsProvider.NETWORK, HistoryGpsProvider.PASSIVE, null).map {
            HistoryGpsFix(tick(10), 56.0, 60.0, 5.0, null, provider = it)
        }
        val batch = HistoryBatch("ride", emptyList(), fixes, emptyList(), "provider-receipt")
        store.append(batch); store.append(batch)
        assertFailsWith<IllegalArgumentException> {
            store.append(batch.copy(gps = fixes.mapIndexed { index, fix ->
                if (index == 0) fix.copy(provider = HistoryGpsProvider.NETWORK) else fix
            }))
        }
        val first = store.gps("ride", range, null, 2)
        val second = store.gps("ride", range, assertNotNull(first.next), 2)
        assertEquals(fixes.take(2), first.items)
        assertEquals(fixes.drop(2), second.items)
        assertNull(second.next)
    }

    @Test fun legacyRowVerifiedReceiptCannotAcknowledgeDifferentGpsProvider() = runTest {
        val (driver, store) = fixture(); begin(store)
        val fix = HistoryGpsFix(tick(10), 56.0, 60.0, 5.0, null, HistoryGpsProvider.GPS)
        val batch = HistoryBatch("ride", emptyList(), listOf(fix), emptyList(), "legacy-provider")
        store.append(batch)
        driver.execute(null, "UPDATE HistoryBatchReceiptV2 SET fingerprint = NULL", 0)
        store.append(batch)
        assertFailsWith<IllegalArgumentException> {
            store.append(batch.copy(gps = listOf(fix.copy(provider = HistoryGpsProvider.NETWORK))))
        }
    }

    @Test fun auxiliaryAndVirtualSourceKindsSurviveDurableReadWithoutMergingWithPack() = runTest {
        val (_, store) = fixture()
        val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Battery"),
            HistorySource("section", HistorySourceKind.BATTERY_ASSEMBLY, "Section"),
            HistorySource("virtual", HistorySourceKind.DERIVED_BATTERY, "Derived"))
        store.begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING), sources)
        val points = sources.map { HistorySample(tick(10), HistorySeriesKey(it.id, "voltage_v"), 0.0) }
        store.append(HistoryBatch("ride", points, emptyList(), emptyList()))
        store.finish("ride", tick(20), HistoryRideStatus.COMPLETED)
        assertEquals(sources, store.sources("ride"))
        for (point in points) assertEquals(listOf(point), store.samples("ride", point.key, range, null).items)
    }

    @Test fun rideAndSourceRoundTripWithoutDependingOnCurrentVehicle() = runTest {
        val (_, store) = fixture()
        begin(store)
        assertEquals("bike", assertNotNull(store.ride("ride")).vehicleId)
        assertEquals(origin, store.ride("ride")?.started)
        assertEquals(listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Battery A")), store.sources("ride"))
        store.finish("ride", tick(50), HistoryRideStatus.COMPLETED)
        assertEquals(tick(50), store.ride("ride")?.ended)
        assertEquals(HistoryRideStatus.COMPLETED, store.ride("ride")?.status)
    }

    @Test fun measuredZeroAndTypedUnknownRemainDifferentAfterRead() = runTest {
        val (_, store) = fixture(); begin(store)
        val known = HistorySample(tick(10), key, 0.0)
        val unknown = HistorySample(tick(20), key, null, HistoryUnknownReason.UNAVAILABLE)
        store.append(HistoryBatch("ride", listOf(known, unknown), emptyList(), emptyList()))
        assertEquals(listOf(known, unknown), store.samples("ride", key, range, null).items)
    }

    @Test fun equalTimePageCursorDoesNotLoseOrRepeatRows() = runTest {
        val (_, store) = fixture(); begin(store)
        val samples = listOf(HistorySample(tick(10), key, 1.0), HistorySample(tick(10), key, 2.0), HistorySample(tick(20), key, 3.0))
        store.append(HistoryBatch("ride", samples, emptyList(), emptyList()))
        val first = store.samples("ride", key, range, null, 1)
        assertEquals(listOf(samples[0]), first.items)
        assertTrue(first.truncated)
        val second = store.samples("ride", key, range, assertNotNull(first.next), 1)
        assertEquals(listOf(samples[1]), second.items)
        val third = store.samples("ride", key, range, assertNotNull(second.next), 1)
        assertEquals(listOf(samples[2]), third.items)
        assertNull(third.next)
        assertFalse(third.truncated)
    }

    @Test fun packetFromOtherMonotonicOriginCannotEnterRide() = runTest {
        val (_, store) = fixture(); begin(store)
        try {
            store.append(HistoryBatch("ride", listOf(HistorySample(HistoryTick(10020, 120, 2), key, 13.0)), emptyList(), emptyList()))
            fail("Other origin was admitted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.samples("ride", key, range, null).items.isEmpty())
    }

    @Test fun rangeUsesMonotonicAxisInsteadOfJumpedWallClock() = runTest {
        val (_, store) = fixture(); begin(store)
        val changed = HistorySample(HistoryTick(70000, 120, 1), key, 12.0)
        store.append(HistoryBatch("ride", listOf(changed), emptyList(), emptyList()))
        assertEquals(listOf(changed), store.samples("ride", key, HistoryRange(10015, 10025), null).items)
        assertTrue(store.samples("ride", key, HistoryRange(69990, 70010), null).items.isEmpty())
    }

    @Test fun interruptedRecoveryEndsAtLastCommittedFactOnly() = runTest {
        val (_, store) = fixture(); begin(store)
        store.append(HistoryBatch("ride", listOf(HistorySample(tick(30), key, 3.0)), emptyList(), emptyList()))
        assertEquals(1, store.recoverInterrupted())
        val recovered = assertNotNull(store.ride("ride"))
        assertEquals(HistoryRideStatus.INTERRUPTED, recovered.status)
        assertEquals(tick(30), recovered.ended)
        assertEquals(0, store.recoverInterrupted())
    }

    @Test fun completedRideRejectsFurtherWritesAndDeletingItDoesNotDeleteAnother() = runTest {
        val (_, store) = fixture(); begin(store); begin(store, "other")
        store.finish("ride", tick(20), HistoryRideStatus.COMPLETED)
        try {
            store.append(HistoryBatch("ride", listOf(HistorySample(tick(30), key, 3.0)), emptyList(), emptyList()))
            fail("Completed ride accepted a write")
        } catch (_: IllegalStateException) { }
        store.delete("ride")
        assertNull(store.ride("ride"))
        assertNotNull(store.ride("other"))
    }

    @Test fun sameTimeRideListPagesHaveStableTieBreaker() = runTest {
        val (_, store) = fixture()
        (0..20).forEach { begin(store, "ride-${it.toString().padStart(2, '0')}") }
        val first = store.rides(null)
        assertEquals(20, first.items.size)
        val second = store.rides(assertNotNull(first.next))
        assertEquals(1, second.items.size)
        assertTrue(first.items.none { a -> second.items.any { it.id == a.id } })
        assertNull(second.next)
    }

    @Test fun sqliteFailureRollsBackEarlierRowsAndSequence() = runTest {
        val (driver, store) = fixture(); begin(store)
        driver.execute(null, """CREATE TRIGGER reject_history BEFORE INSERT ON HistorySampleV2
            WHEN NEW.reading = 13 BEGIN SELECT RAISE(ABORT, 'injected failure'); END""", 0)
        assertFails { store.append(HistoryBatch("ride", listOf(
            HistorySample(tick(10), key, 10.0), HistorySample(tick(20), key, 13.0)), emptyList(), emptyList())) }
        assertTrue(store.samples("ride", key, range, null).items.isEmpty())
        store.append(HistoryBatch("ride", listOf(HistorySample(tick(30), key, 30.0),
            HistorySample(tick(40), key, 40.0)), emptyList(), emptyList()))
        assertEquals("0", store.samples("ride", key, range, null, 1).next?.stableId)
    }

    @Test fun delayedGpsDoesNotMoveRecoveryBackAndGapMarkersRemainOrdered() = runTest {
        val (_, store) = fixture(); begin(store)
        val fixes = listOf(HistoryGpsFix(tick(10), 56.0, 60.0, 3.0, null),
            HistoryGpsFix(tick(10), 57.0, 61.0, 4.0, 90.0))
        val gap = HistoryMarker(tick(20), HistoryMarkerKind.GPS_GAP)
        store.append(HistoryBatch("ride", listOf(HistorySample(tick(80), key, 8.0)), emptyList(), emptyList()))
        store.append(HistoryBatch("ride", emptyList(), fixes, listOf(gap)))
        val first = store.gps("ride", range, null, 1)
        assertEquals(listOf(fixes.first()), first.items)
        assertEquals(listOf(fixes.last()), store.gps("ride", range, first.next, 1).items)
        assertEquals(listOf(gap), store.markers("ride", range))
        assertTrue(store.markers("ride", HistoryRange(10030, 10050)).isEmpty())
        store.recoverInterrupted()
        assertEquals(tick(80), store.ride("ride")?.ended)
        assertEquals(HistoryMarkerKind.INTERRUPTED, store.markers("ride", range).last().kind)
    }

    @Test fun unknownSourceIsRejectedEvenWithoutForeignKeys() = runTest {
        val (driver, store) = fixture(); begin(store)
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        val foreign = key.copy(sourceId = "other-pack")
        assertFailsWith<IllegalArgumentException> {
            store.append(HistoryBatch("ride", listOf(HistorySample(tick(10), foreign, 1.0)), emptyList(), emptyList()))
        }
        assertTrue(store.samples("ride", foreign, range, null).items.isEmpty())
    }

    @Test fun deletionRemovesChildrenWithForeignKeysDisabled() = runTest {
        val (driver, store) = fixture(); begin(store)
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        store.append(HistoryBatch("ride", listOf(HistorySample(tick(10), key, 10.0)),
            listOf(HistoryGpsFix(tick(10), 56.0, 60.0, 5.0, null)),
            listOf(HistoryMarker(tick(10), HistoryMarkerKind.CONNECTED))))
        store.delete("ride")
        // Reuse the ID: any orphan source/sequence would collide or reappear.
        begin(store)
        assertTrue(store.samples("ride", key, range, null).items.isEmpty())
        assertTrue(store.gps("ride", range, null).items.isEmpty())
        assertTrue(store.markers("ride", range).isEmpty())
    }

    @Test fun identicalFinishIsIdempotentButConflictingFinishCannotOverwrite() = runTest {
        val (_, store) = fixture(); begin(store)
        store.finish("ride", tick(10), HistoryRideStatus.COMPLETED)
        store.finish("ride", tick(10), HistoryRideStatus.COMPLETED)
        assertFailsWith<IllegalStateException> { store.finish("ride", tick(20), HistoryRideStatus.INTERRUPTED) }
        assertEquals(tick(10), store.ride("ride")?.ended)
    }

    @Test fun readLimitsAndMalformedCursorsAreRejected() = runTest {
        val (_, store) = fixture(); begin(store)
        assertFailsWith<IllegalArgumentException> { store.rides(null, 0) }
        assertFailsWith<IllegalArgumentException> { store.rides(null, 21) }
        assertFailsWith<IllegalArgumentException> { store.samples("ride", key, range, null, 20001) }
        assertFailsWith<IllegalArgumentException> { store.gps("ride", range, null, 0) }
        assertFailsWith<IllegalArgumentException> { store.samples("ride", key, range, HistoryCursor(10010, "oops")) }
        assertTrue(store.samples("ride", key, HistoryRange(Long.MIN_VALUE, 9999), null).items.isEmpty())
    }

    @Test fun sourceAndCellKeysAreIsolatedAndNoSummaryIsInventedFromDetail() = runTest {
        val (_, store) = fixture()
        store.begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING), listOf(
            HistorySource("pack", HistorySourceKind.BATTERY, "A"),
            HistorySource("other", HistorySourceKind.BATTERY, "B")))
        val a = HistorySeriesKey("pack", "cell_v", 0)
        val b = a.copy(sourceId = "other")
        val c = a.copy(cellIndex = 1)
        val rows = listOf(HistorySample(tick(10), a, 3.5), HistorySample(tick(10), b, 3.8), HistorySample(tick(10), c, 3.9))
        store.append(HistoryBatch("ride", rows, emptyList(), emptyList()))
        rows.forEach { assertEquals(listOf(it), store.samples("ride", it.key, range, null).items) }
        assertEquals(HistoryStats(null, null, null, false), store.stats("ride", a, range))
    }

    @Test fun retriedCommittedBatchDoesNotDuplicateAndCanBeAcknowledgedAfterFinish() = runTest {
        val (_, store) = fixture(); begin(store)
        val sample = HistorySample(tick(10), key, 10.0)
        val fix = HistoryGpsFix(tick(10), 56.0, 60.0, 5.0, null)
        val marker = HistoryMarker(tick(10), HistoryMarkerKind.CONNECTED)
        val batch = HistoryBatch("ride", listOf(sample), listOf(fix), listOf(marker), "receipt-1")
        store.append(batch)
        store.append(batch)
        store.finish("ride", tick(20), HistoryRideStatus.COMPLETED)
        store.append(batch)
        assertEquals(listOf(sample), store.samples("ride", key, range, null).items)
        assertEquals(listOf(fix), store.gps("ride", range, null).items)
        assertEquals(listOf(marker), store.markers("ride", range))
    }

    @Test fun reusingReceiptForDifferentPayloadCannotSilentlyDiscardNewData() = runTest {
        val (_, store) = fixture(); begin(store)
        val batch = HistoryBatch("ride", listOf(HistorySample(tick(10), key, 10.0)), emptyList(), emptyList(), "receipt-1")
        store.append(batch)
        assertFailsWith<IllegalArgumentException> {
            store.append(batch.copy(samples = listOf(HistorySample(tick(10), key, 20.0))))
        }
        assertEquals(batch.samples, store.samples("ride", key, range, null).items)
    }

    @Test fun failedSqlTransactionDoesNotPublishReceipt() = runTest {
        val (driver, store) = fixture(); begin(store)
        driver.execute(null, """CREATE TRIGGER reject_receipt BEFORE INSERT ON HistorySampleV2
            WHEN NEW.reading = 13 BEGIN SELECT RAISE(ABORT, 'injected failure'); END""", 0)
        val batch = HistoryBatch("ride", listOf(HistorySample(tick(10), key, 10.0),
            HistorySample(tick(20), key, 13.0)), emptyList(), emptyList(), "receipt-1")
        assertFails { store.append(batch) }
        driver.execute(null, "DROP TRIGGER reject_receipt", 0)
        store.append(batch)
        store.append(batch)
        assertEquals(batch.samples, store.samples("ride", key, range, null).items)
    }

    @Test fun sqliteNormalizationOfNegativeZeroDoesNotInvalidateReceipt() = runTest {
        val (_, store) = fixture(); begin(store)
        val batch = HistoryBatch("ride", listOf(HistorySample(tick(10), key, -0.0)),
            listOf(HistoryGpsFix(tick(10), -0.0, -0.0, -0.0, -0.0)), emptyList(), "zero-receipt")
        store.append(batch)
        store.append(batch)
        assertEquals(1, store.samples("ride", key, range, null).items.size)
        assertNull(store.samples("ride", key, range, null).items.single().unknown)
    }

    @Test fun fullStreamSummaryIsSavedAtomicallyAndNotRecomputedFromThinnedDetail() = runTest {
        val (_, store) = fixture(); begin(store)
        val power = key.copy(metricId = "power_w")
        val raw = listOf(HistorySample(tick(0), power, 10.0), HistorySample(tick(100), power, 4200.0), HistorySample(tick(400), power, 20.0))
        val accumulator = HistoryBucketAccumulator(origin, power)
        raw.forEach { accumulator.offer(it) }
        val bucket = assertNotNull(accumulator.snapshot())
        store.append(HistoryBatch("ride", listOf(raw.first(), raw.last()), emptyList(), emptyList(), "summary-1", listOf(bucket)))
        assertEquals(bucket.stats, store.stats("ride", power, range))
        assertEquals(listOf(bucket), store.buckets("ride", power, range, null).items)
        assertEquals(listOf(10.0, 20.0), store.samples("ride", power, range, null).items.map { it.value })
    }

    @Test fun earlierReceiptStillAcknowledgesAfterBucketHasAdvancedButConflictingSummaryIsRejected() = runTest {
        val (_, store) = fixture(); begin(store)
        val power = key.copy(metricId = "power_w"); val accumulator = HistoryBucketAccumulator(origin, power)
        accumulator.offer(HistorySample(tick(10), power, -100.0))
        val first = HistoryBatch("ride", emptyList(), emptyList(), emptyList(), "summary-1", listOf(assertNotNull(accumulator.snapshot())))
        store.append(first)
        accumulator.offer(HistorySample(tick(20), power, -200.0))
        val advanced = assertNotNull(accumulator.snapshot())
        store.append(first.copy(receiptId = "summary-2", buckets = listOf(advanced)))
        store.append(first)
        assertEquals(advanced.stats, store.stats("ride", power, range))
        assertFailsWith<IllegalArgumentException> { store.append(first.copy(buckets = listOf(advanced))) }
    }

    @Test fun summaryFailureRollsBackDetailAndReceiptTogether() = runTest {
        val (driver, store) = fixture(); begin(store)
        val power = key.copy(metricId = "power_w"); val accumulator = HistoryBucketAccumulator(origin, power)
        val point = HistorySample(tick(10), power, -100.0); accumulator.offer(point)
        driver.execute(null, "CREATE TRIGGER reject_bucket BEFORE INSERT ON HistoryBucketV2 BEGIN SELECT RAISE(ABORT, 'bucket failure'); END", 0)
        val batch = HistoryBatch("ride", listOf(point), emptyList(), emptyList(), "summary-1", listOf(assertNotNull(accumulator.snapshot())))
        assertFails { store.append(batch) }
        assertTrue(store.samples("ride", power, range, null).items.isEmpty())
        driver.execute(null, "DROP TRIGGER reject_bucket", 0)
        store.append(batch); store.append(batch)
        assertEquals(listOf(point), store.samples("ride", power, range, null).items)
    }

    @Test fun partialBucketRangeDoesNotClaimWholeBucketEnergyOrPeaksOutsideSelection() = runTest {
        val (_, store) = fixture(); begin(store)
        val power = key.copy(metricId = "power_w"); val accumulator = HistoryBucketAccumulator(origin, power)
        val raw = listOf(HistorySample(tick(10), power, 100.0), HistorySample(tick(20), power, 10.0))
        raw.forEach { accumulator.offer(it) }
        store.append(HistoryBatch("ride", raw, emptyList(), emptyList(), "summary-1", listOf(assertNotNull(accumulator.snapshot()))))
        val clipped = store.stats("ride", power, HistoryRange(10015, 10025))
        assertEquals(10.0, clipped.minimum); assertEquals(10.0, clipped.maximum)
        assertNull(clipped.energyWh); assertFalse(clipped.complete)
    }

    @Test fun bucketPagesUseAlignedSlotAndRecoveryUsesSummaryOnlyLastFact() = runTest {
        val (_, store) = fixture(); begin(store)
        val power = key.copy(metricId = "power_w"); val accumulator = HistoryBucketAccumulator(origin, power)
        accumulator.offer(HistorySample(tick(59900), power, -100.0))
        val updated = accumulator.offer(HistorySample(tick(60100), power, -100.0))
        store.append(HistoryBatch("ride", emptyList(), emptyList(), emptyList(), "summary-1", updated))
        val span = HistoryRange(10000, 80000)
        val first = store.buckets("ride", power, span, null, 1)
        assertEquals(0, first.items.single().slot)
        val second = store.buckets("ride", power, span, assertNotNull(first.next), 1)
        assertEquals(1, second.items.single().slot); assertFalse(second.truncated)
        store.recoverInterrupted()
        assertEquals(tick(60100), store.ride("ride")?.ended)
        assertFalse(store.stats("ride", power, span).complete)
    }

    @Test fun summaryValidatesSourceClockSlotAndMonotonicSnapshotEvenWithForeignKeysOff() = runTest {
        val (driver, store) = fixture(); begin(store)
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        val power = key.copy(metricId = "power_w")
        val sample = HistorySample(tick(10), power, -100.0)
        val bucket = HistoryBucket(power, 0, sample.tick, sample.tick, sample, sample, null, 1, true)
        val batch = HistoryBatch("ride", emptyList(), emptyList(), emptyList(), "summary", listOf(bucket))
        fun keyed(source: String) = bucket.copy(key = power.copy(sourceId = source),
            minimum = sample.copy(key = power.copy(sourceId = source)), maximum = sample.copy(key = power.copy(sourceId = source)))
        assertFailsWith<IllegalArgumentException> { store.append(batch.copy(buckets = listOf(keyed("missing")))) }
        assertFailsWith<IllegalArgumentException> { store.append(batch.copy(buckets = listOf(bucket.copy(slot = Long.MAX_VALUE)))) }
        val otherClock = sample.copy(tick = sample.tick.copy(clockGeneration = 2))
        assertFailsWith<IllegalArgumentException> { store.append(batch.copy(buckets = listOf(bucket.copy(
            first = otherClock.tick, last = otherClock.tick, minimum = otherClock, maximum = otherClock)))) }
        assertFailsWith<IllegalArgumentException> { store.append(batch.copy(receiptId = null)) }
        store.append(batch.copy(buckets = listOf(bucket.copy(sampleCount = 2))))
        assertFailsWith<IllegalArgumentException> { store.append(batch.copy(receiptId = "regressed")) }
        assertEquals(2, store.buckets("ride", power, range, null).items.single().sampleCount)
    }

    @Test fun legacyNullFingerprintReceiptStillVerifiesRowsButCannotCertifySummaries() = runTest {
        val (driver, store) = fixture(); begin(store)
        val sample = HistorySample(tick(10), key, -0.0)
        val batch = HistoryBatch("ride", listOf(sample), emptyList(), emptyList(), "old")
        store.append(batch)
        driver.execute(null, "UPDATE HistoryBatchReceiptV2 SET fingerprint = NULL", 0)
        store.append(batch)
        assertFailsWith<IllegalArgumentException> { store.append(batch.copy(samples = listOf(sample.copy(value = 1.0)))) }
        val bucket = HistoryBucket(key, 0, sample.tick, sample.tick, sample, sample, null, 1, true)
        assertFailsWith<IllegalArgumentException> { store.append(batch.copy(buckets = listOf(bucket))) }
        assertEquals(1, store.samples("ride", key, range, null).items.size)
    }

    @Test fun summaryReceiptCanonicalizesZeroAndGpsGapDoesNotInvalidateTelemetry() = runTest {
        val (_, store) = fixture(); begin(store)
        val sample = HistorySample(tick(10), key, -0.0)
        val bucket = HistoryBucket(key, 0, sample.tick, sample.tick, sample, sample, null, 1, true)
        val batch = HistoryBatch("ride", emptyList(), emptyList(),
            listOf(HistoryMarker(tick(10), HistoryMarkerKind.GPS_GAP)), "zero", listOf(bucket))
        store.append(batch)
        store.append(batch.copy(buckets = listOf(bucket.copy(minimum = sample.copy(value = 0.0), maximum = sample.copy(value = 0.0)))))
        assertEquals(HistoryStats(0.0, 0.0, null, true), store.stats("ride", key, range))
    }

    @Test fun explicitlyInterruptedRideCannotExposeCompleteSummaryWithoutRecoveryMarker() = runTest {
        val (_, store) = fixture(); begin(store)
        val point = HistorySample(tick(10), key, 100.0)
        val bucket = HistoryBucket(key, 0, point.tick, point.tick, point, point, null, 1, true)
        store.append(HistoryBatch("ride", listOf(point), emptyList(), emptyList(), "summary", listOf(bucket)))
        store.finish("ride", tick(20), HistoryRideStatus.INTERRUPTED)
        val summary = store.stats("ride", key, range)
        assertEquals(100.0, summary.maximum)
        assertFalse(summary.complete)
    }
}
