package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryWriterTest {
    private val tick = HistoryTick(10000, 100, 1)
    private val ride = HistoryRide("ride", "bike", tick, null, HistoryRideStatus.RECORDING)
    private val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "A"))
    private fun batch(count: Int = 1, source: String = "pack") = HistoryBatch("ride",
        List(count) { HistorySample(tick, HistorySeriesKey(source, "current_a"), it.toDouble()) }, emptyList(), emptyList())

    @Test fun summaryOnlyPacketsAreCopiedAndCommitted() = runTest {
        val store = Store()
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            val sample = batch().samples.single()
            val bucket = HistoryBucket(sample.key, 0, tick, tick, sample, sample, null, 1, true)
            val mutable = mutableListOf(bucket)
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch(0).copy(buckets = mutable)))
            mutable.clear()
            writer.flush()
            assertEquals(listOf(bucket), store.batches.single().buckets)
        } finally { writer.close() }
    }

    @Test fun recordingModeCommitsSummaryFromRawStreamAndThinsOnlyDetail() = runTest {
        val store = Store()
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow(), projectRecording = true)
        try {
            writer.begin(7, ride, sources)
            val raw = batch(500).samples.mapIndexed { index, sample -> sample.copy(
                tick = tick.copy(epochMillis = tick.epochMillis + index, elapsedMillis = tick.elapsedMillis + index),
                value = if (index == 221) -9999.0 else -100.0, key = sample.key.copy(metricId = "power_w")) }
            writer.offer(7, batch(0).copy(samples = raw))
            writer.finish(7, raw.last().tick, HistoryRideStatus.COMPLETED)
            val committed = store.batches.single()
            assertEquals(500, committed.buckets.single().sampleCount)
            assertEquals(-9999.0, committed.buckets.single().minimum?.value)
            assertTrue(committed.samples.size <= 4)
            assertEquals(raw.last(), committed.samples.last())
        } finally { writer.close() }
    }

    @Test fun projectedRetryKeepsExactPreparedPayloadAfterCommitAckLost() = runTest {
        val store = Store(); store.failAfterCommit = true
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow(), projectRecording = true)
        try {
            writer.begin(7, ride, sources)
            writer.offer(7, batch(2))
            assertFails { writer.flush() }
            writer.flush()
            assertEquals(2, store.batches.single().buckets.single().sampleCount)
            assertEquals(2, store.attempts.size)
            assertEquals(store.attempts.first(), store.attempts.last())
        } finally { writer.close() }
    }

    @Test fun flushCommitsAdmittedPrefixAndDoesNotFollowNewPacketsForever() = runTest {
        val store = Store(); val gate = CompletableDeferred<Unit>(); store.gate = gate
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            writer.offer(7, batch())
            val flush = async { writer.flush() }
            runCurrent()
            assertEquals(listOf("begin", "append-start"), store.calls)
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch()))
            gate.complete(Unit)
            flush.await()
            assertEquals(1, store.batches.size)
            writer.close()
            assertEquals(2, store.batches.size)
        } finally { gate.complete(Unit); writer.close() }
    }

    @Test fun summaryPacketsCannotBypassQueueCapacityOrLoseOverflowEvidence() = runTest {
        val store = Store()
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            val sample = batch().samples.single()
            val bucket = HistoryBucket(sample.key, 0, tick, tick, sample, sample, null, 1, true)
            assertEquals(HistoryOffer.OVERFLOW,
                writer.offer(7, batch(0).copy(buckets = List(2049) { bucket })))
            writer.flush()
            assertEquals(HistoryMarkerKind.OVERFLOW, store.batches.single().markers.single().kind)
        } finally { writer.close() }
    }

    @Test fun finishFencesIngressAndWaitsForActualAppendCommit() = runTest {
        val store = Store(); val gate = CompletableDeferred<Unit>(); store.gate = gate
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch()))
            val finishing = async { writer.finish(7, tick, HistoryRideStatus.COMPLETED) }
            runCurrent()
            assertEquals(listOf("begin", "append-start"), store.calls)
            assertFalse(finishing.isCompleted)
            assertEquals(HistoryOffer.RETIRED, writer.offer(7, batch()))
            gate.complete(Unit); finishing.await()
            assertEquals(listOf("begin", "append-start", "append-commit", "finish"), store.calls)
            assertEquals(1, store.batches.size)
        } finally { gate.complete(Unit); writer.close() }
    }

    @Test fun staleGenerationOrWrongRideCannotEnterQueueAndFlushCommitsPartialPacket() = runTest {
        val store = Store(); val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            assertEquals(HistoryOffer.RETIRED, writer.offer(6, batch()))
            assertEquals(HistoryOffer.RETIRED, writer.offer(7, batch().copy(rideId = "other")))
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch()))
            runCurrent(); assertTrue(store.batches.isEmpty())
            writer.flush(); assertEquals(1, store.batches.size)
            assertNotNull(store.batches.single().receiptId)
            writer.finish(6, tick, HistoryRideStatus.COMPLETED)
            assertFalse("finish" in store.calls)
        } finally { writer.close() }
    }

    @Test fun sizeOverflowIsRecordedAsGapWithoutKeepingOversizePayload() = runTest {
        val store = Store(); val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            assertEquals(HistoryOffer.OVERFLOW, writer.offer(7, batch(2049)))
            assertEquals(HistoryOffer.OVERFLOW, writer.offer(7, batch(source = "x".repeat(2 * 1024 * 1024))))
            writer.flush()
            assertTrue(store.batches.flatMap { it.samples }.isEmpty())
            assertEquals(HistoryMarkerKind.OVERFLOW, store.batches.flatMap { it.markers }.single().kind)
        } finally { writer.close() }
    }

    @Test fun thresholdAndFiniteTickerBothFlushWithoutScreenLifetime() = runTest {
        val store = Store(); val ticks = MutableSharedFlow<Unit>()
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), ticks)
        try {
            writer.begin(7, ride, sources)
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch(128)))
            runCurrent(); assertEquals(128, store.batches.sumOf { it.samples.size })
            writer.offer(7, batch()); runCurrent()
            assertEquals(128, store.batches.sumOf { it.samples.size })
            ticks.emit(Unit); runCurrent()
            assertEquals(129, store.batches.sumOf { it.samples.size })
        } finally { writer.close() }
    }

    @Test fun failedAcknowledgementRetriesSameReceiptAndRetiresNewIngress() = runTest {
        val store = Store(); store.failAfterCommit = true
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources); writer.offer(7, batch())
            assertFailsWith<IllegalStateException> { writer.flush() }
            assertEquals(HistoryOffer.RETIRED, writer.offer(7, batch()))
            writer.flush()
            assertEquals(1, store.batches.size)
            assertEquals(2, store.appendTokens.size)
            assertEquals(store.appendTokens.first(), store.appendTokens.last())
            writer.finish(7, tick, HistoryRideStatus.COMPLETED)
        } finally { writer.close() }
    }

    @Test fun delayedFinishForOldRideOnSameBleGenerationCannotEndNewRide() = runTest {
        val store = Store(); val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            writer.finish(7, tick, HistoryRideStatus.COMPLETED, rideId = "ride")
            writer.begin(7, ride.copy(id = "other"), sources)
            writer.finish(7, tick, HistoryRideStatus.COMPLETED, rideId = "ride")
            assertEquals(1, store.calls.count { it == "finish" })
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch().copy(rideId = "other")))
        } finally { writer.close() }
    }

    @Test fun capacityIncludesPacketAwaitingCommitAndControlsSurviveFullQueue() = runTest {
        val store = Store(); val gate = CompletableDeferred<Unit>(); store.gate = gate
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch(2048)))
            runCurrent(); assertEquals(2048, writer.diagnostics.value.pendingEvents)
            assertEquals(HistoryOffer.OVERFLOW, writer.offer(7, batch()))
            val stop = async { writer.finish(7, tick, HistoryRideStatus.COMPLETED) }
            val closing = async { writer.close() }
            runCurrent(); assertFalse(stop.isCompleted); assertFalse(closing.isCompleted)
            gate.complete(Unit); stop.await(); closing.await()
            assertEquals(2048, store.batches.sumOf { it.samples.size })
            assertEquals(1, store.batches.flatMap { it.markers }.count { it.kind == HistoryMarkerKind.OVERFLOW })
            assertEquals(1, store.calls.count { it == "finish" })
        } finally { gate.complete(Unit); writer.close() }
    }

    @Test fun admittedPacketIsSnapshotNotCallersMutableList() = runTest {
        val store = Store(); val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources)
            val mutable = batch().samples.toMutableList()
            assertEquals(HistoryOffer.ACCEPTED, writer.offer(7, batch().copy(samples = mutable)))
            mutable.clear(); writer.flush()
            assertEquals(1, store.batches.single().samples.size)
        } finally { writer.close() }
    }

    @Test fun deleteWaitsForInFlightAppendAndRetiresRideBeforeErasingIt() = runTest {
        val store = Store(); val gate = CompletableDeferred<Unit>(); store.gate = gate
        val writer = BufferedHistoryWriter(store, StandardTestDispatcher(testScheduler), emptyFlow())
        try {
            writer.begin(7, ride, sources); writer.offer(7, batch(128)); runCurrent()
            val deleting = async { writer.delete("ride") }
            runCurrent(); assertFalse(deleting.isCompleted)
            assertEquals(HistoryOffer.RETIRED, writer.offer(7, batch()))
            assertFalse("delete" in store.calls)
            gate.complete(Unit); deleting.await()
            assertEquals(listOf("begin", "append-start", "append-commit", "delete"), store.calls)
            assertTrue(store.batches.isEmpty())
            writer.finish(7, tick, HistoryRideStatus.COMPLETED)
            assertFalse("finish" in store.calls)
        } finally { gate.complete(Unit); writer.close() }
    }

    private class Store : HistoryStore {
        val calls = mutableListOf<String>()
        val batches = mutableListOf<HistoryBatch>()
        val appendTokens = mutableListOf<String?>()
        val attempts = mutableListOf<HistoryBatch>()
        var gate: CompletableDeferred<Unit>? = null
        var failAfterCommit = false
        override suspend fun begin(ride: HistoryRide, sources: List<HistorySource>) { calls += "begin" }
        override suspend fun append(batch: HistoryBatch) {
            attempts += batch
            calls += "append-start"; appendTokens += batch.receiptId
            gate?.await()
            if (batches.none { it.receiptId == batch.receiptId }) batches += batch
            calls += "append-commit"
            if (failAfterCommit) { failAfterCommit = false; error("Commit ACK lost") }
        }
        override suspend fun finish(rideId: String, ended: HistoryTick, status: HistoryRideStatus) { calls += "finish" }
        override suspend fun ride(rideId: String): HistoryRide? = null
        override suspend fun recoverInterrupted() = 0
        override suspend fun rides(before: HistoryCursor?, limit: Int) = HistoryPage<HistoryRide>(emptyList(), null, false)
        override suspend fun sources(rideId: String) = emptyList<HistorySource>()
        override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int) = HistoryPage<HistorySample>(emptyList(), null, false)
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?, limit: Int) = HistoryPage<HistoryGpsFix>(emptyList(), null, false)
        override suspend fun markers(rideId: String, range: HistoryRange) = emptyList<HistoryMarker>()
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) = HistoryStats(null, null, null, false)
        override suspend fun buckets(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int) = HistoryPage<HistoryBucket>(emptyList(), null, false)
        override suspend fun delete(rideId: String) { calls += "delete"; batches.removeAll { it.rideId == rideId } }
    }
}
