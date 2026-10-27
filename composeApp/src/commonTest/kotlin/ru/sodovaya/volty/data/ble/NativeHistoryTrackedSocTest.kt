package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The ingress wiring of the tracker's charge: what a recording ISSUES for a gauge-less wheel's battery. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class NativeHistoryTrackedSocTest {
    private val source = HistorySource("bat", HistorySourceKind.BATTERY, "T4")
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
    private fun wheel() = Vehicle(id = "wheel", name = "T4", iconKey = "unicycle", packs = emptyList(),
        controllers = listOf(Controller(0, "T4", ControllerType.BEGODE, "AA", providesDerivedBattery = true, derivedCellCount = 24)),
        topology = PackTopology.PARALLEL, chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private class GaugeLessBattery : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() {}
        override fun onNotification(data: ByteArray) { observeTelemetry(TelemetryOrigin.BATTERY, 0) { listOf(
            TelemetryReading.measured(TelemetryMetric.VOLTAGE, 90.8),
            TelemetryReading(TelemetryMetric.SOC, null, TelemetryUnknown.UNAVAILABLE)) } }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val samples = mutableListOf<HistorySample>()
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) {}
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer { samples += batch.samples; return HistoryOffer.ACCEPTED }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {}
        override suspend fun flush() {}
        override suspend fun delete(rideId: String) {}
        override suspend fun close() {}
    }

    private suspend fun recorded(view: () -> HistorySocView?): List<Double?> {
        val writer = Writer(); var n = 0
        val c = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++n}" }, { 0 })
        val spec = LinkSpec("AA", ProtocolKind.BEGODE, listOf(OwnedSource(0)))
        var now = tick()
        val ingress = HistoryTelemetryIngress(c, { now },
            { _, _ -> mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source) }, manageConnections = true)
        ingress.installNativePlan("wheel", listOf(spec), wheel()) { true }
        val capture = assertNotNull(ingress.capture(GaugeLessBattery(), "attempt", spec, wheel(), SampleSessionOwnership { true },
            sampleFunnelChannel(4), socView = view))
        assertTrue(capture.prepareForObservation())
        now = tick(20); assertTrue(capture.processNotification(byteArrayOf(1)))
        return writer.samples.filter { it.key.metricId == TelemetryMetric.SOC.id }.map { it.value }
    }

    @Test fun aSeededTrackersChargeIsWhatTheRideRecords() = kotlinx.coroutines.test.runTest {
        assertEquals(listOf<Double?>(71.5), recorded { HistorySocView("wheel", 71.5f) })
    }

    @Test fun anUnseededTrackerRecordsTheVoltageEstimateAsBefore() = kotlinx.coroutines.test.runTest {
        val seeded = recorded { HistorySocView("wheel", 71.5f) }.single()
        val unseeded = recorded { HistorySocView("wheel", null) }.single()
        assertNotNull(unseeded); assertNotEquals(seeded, unseeded)
        assertEquals(unseeded, recorded { null }.single())
    }
}
