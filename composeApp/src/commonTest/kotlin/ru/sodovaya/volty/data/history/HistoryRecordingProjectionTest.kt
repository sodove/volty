package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.ble.LinkSpec
import ru.sodovaya.volty.data.ble.OwnedSource
import ru.sodovaya.volty.data.ble.ProtocolKind
import ru.sodovaya.volty.data.ble.nativeHistorySourcesForLink
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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

    @OptIn(ExperimentalTime::class)
    @Test fun nativeVescControllersRetainEveryReceivedSampleWithoutIncreasingOtherSources() {
        val address = "AA:BB:CC:DD:EE:01"
        val controller = Controller(0, "ESC", ControllerType.VESC, address)
        val vehicle = Vehicle("bike", "Bike", "bicycle", emptyList(), listOf(controller),
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
        val directLink = LinkSpec(address, ProtocolKind.VESC, listOf(OwnedSource(0)), listOf(OwnedSource(0)))
        val gatewayLink = directLink.copy(ownedControllers = listOf(OwnedSource(0, canId = 117)))
        val direct = nativeHistorySourcesForLink(directLink, vehicle).values
        val gatewayVehicle = vehicle.copy(controllers = listOf(controller.copy(canId = 117)))
        val gateway = nativeHistorySourcesForLink(gatewayLink, gatewayVehicle).values
        val other = HistorySource("native-history-v1:17:${address}6:BEGODE", HistorySourceKind.CONTROLLER, "Other")
        val registered = direct + gateway + other
        val times = 0L..450L step 50
        fun readings(source: HistorySource, metricId: String, unknownAt: Long? = null) = times.map { time ->
            val unknown = time == unknownAt
            HistorySample(sample(time, 10.0).tick, HistorySeriesKey(source.id, metricId),
                if (unknown) null else 10.0, if (unknown) HistoryUnknownReason.UNAVAILABLE else null)
        }
        val vescControllers = (direct + gateway).filter { it.kind == HistorySourceKind.CONTROLLER }
        assertEquals(2, vescControllers.size)
        val vescReadings = vescControllers.flatMap { readings(it, "speed_kmh", unknownAt = 250L) }
        val otherReadings = registered.filter { it.kind != HistorySourceKind.CONTROLLER }.flatMap { source ->
            val metricId = if (source.kind == HistorySourceKind.REPORTED_CAN) "battery_current_a" else "voltage_v"
            readings(source, metricId)
        } + readings(other, "speed_kmh")
        val result = HistoryRecordingProjection(ride, registered)
            .prepare(batch(*(vescReadings + otherReadings).toTypedArray()), false)
        assertEquals(vescReadings.sortedBy { it.tick.elapsedMillis }, result.batch.samples)
        val flushed = result.next.prepare(batch(), true).batch.samples
        assertEquals(otherReadings.groupBy { it.key }.values.flatMap { listOf(it.first(), it.last()) }
            .sortedBy { it.tick.elapsedMillis }, flushed)
        assertEquals(registered.size, result.batch.buckets.size)
        assertTrue(result.batch.buckets.all { it.sampleCount == times.count().toLong() })
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
