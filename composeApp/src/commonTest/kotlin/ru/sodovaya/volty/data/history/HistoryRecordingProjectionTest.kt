package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryRecordingProjectionTest {
    private val origin = HistoryTick(10000, 100, 1)
    private val ride = HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING)
    private val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "A"))
    private val key = HistorySeriesKey("pack", "power_w")
    private fun sample(delta: Long, value: Double) = HistorySample(HistoryTick(10000 + delta, 100 + delta, 1), key, value)
    private fun batch(vararg samples: HistorySample) = HistoryBatch("ride", samples.toList(), emptyList(), emptyList(), "receipt")

    @Test fun highFrequencyStreamProducesFullSummaryAndAtMostFourDetailFacts() {
        val raw = (0..499).map { sample(it.toLong(), if (it == 221) -9999.0 else -100.0) }
        val result = HistoryRecordingProjection(ride, sources).prepare(batch(*raw.toTypedArray()), true).batch
        assertEquals("receipt", result.receiptId)
        assertEquals(500, result.buckets.single().sampleCount)
        assertEquals(-9999.0, result.buckets.single().minimum?.value)
        assertEquals(raw.first(), result.samples.first())
        assertEquals(raw.last(), result.samples.last())
        assertTrue(result.samples.size <= 4)
    }

    @Test fun retryPreparationDoesNotAdvanceStreamAndOnlyAcknowledgedForkCarriesEnergy() {
        val projection = HistoryRecordingProjection(ride, sources)
        val first = batch(sample(0, -100.0), sample(1000, -100.0))
        val prepared = projection.prepare(first, true)
        assertEquals(prepared.batch, projection.prepare(first, true).batch)
        val continued = prepared.next.prepare(batch(sample(2000, -100.0)), true).batch.buckets.single()
        assertEquals(3, continued.sampleCount)
        assertEquals(200.0 / 3600, continued.energyWh)
        assertEquals(2, projection.prepare(first, true).batch.buckets.single().sampleCount)
    }

    @Test fun markersAreAppliedBeforeLaterSamplesAndPreventEnergyAcrossGap() {
        val a = sample(0, -100.0); val b = sample(1000, -100.0); val c = sample(2000, -100.0)
        val marker = HistoryMarker(sample(1500, 0.0).tick, HistoryMarkerKind.BLE_GAP)
        val result = HistoryRecordingProjection(ride, sources).prepare(batch(a, b, c).copy(markers = listOf(marker)), true).batch
        assertEquals(listOf(marker), result.markers)
        assertFalse(result.buckets.single().complete)
        assertEquals(100.0 / 3600, result.buckets.single().energyWh)
    }

    @Test fun pendingDetailFlushesOnStopAndSourceCellIdentityDoesNotMix() {
        val projection = HistoryRecordingProjection(ride, sources)
        val a = sample(0, -100.0)
        val cell = a.copy(key = HistorySeriesKey("pack", "cell_v", 3), value = 3.8)
        val pending = projection.prepare(batch(a, cell), false)
        assertTrue(pending.batch.samples.isEmpty())
        val flushed = pending.next.prepare(batch().copy(receiptId = "stop"), true)
        assertEquals(setOf(a, cell), flushed.batch.samples.toSet())
        assertTrue(flushed.next.prepare(batch().copy(receiptId = "next"), true).batch.samples.isEmpty())
    }

    @Test fun malformedSourceAndOutOfOrderSeriesRejectWithoutAdvancingOriginal() {
        val projection = HistoryRecordingProjection(ride, sources)
        assertFailsWith<IllegalArgumentException> { projection.prepare(batch(sample(0, 1.0).copy(key = key.copy(sourceId = "other"))), true) }
        assertFailsWith<IllegalArgumentException> { projection.prepare(batch(sample(10, 1.0), sample(0, 1.0)), true) }
        assertFailsWith<IllegalArgumentException> { projection.prepare(batch(sample(0, 1.0)).copy(rideId = "other"), true) }
        assertEquals(1, projection.prepare(batch(sample(0, 1.0)), true).batch.buckets.single().sampleCount)
    }
}
