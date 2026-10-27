package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The ingress wiring of [withHistoryEstimatedDuty]: what a recording ISSUES for a wheel's raw-zero duty. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class NativeHistoryEstimatedDutyTest {
    private val source = HistorySource("ctl", HistorySourceKind.CONTROLLER, "T4")
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
    private fun wheel() = Vehicle(id = "wheel", name = "T4", iconKey = "unicycle", packs = emptyList(),
        controllers = listOf(Controller(0, "T4", ControllerType.BEGODE, "AA", providesDerivedBattery = true)),
        topology = PackTopology.PARALLEL, chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private class RawZeroDuty : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() {}
        override fun onNotification(data: ByteArray) { observeTelemetry(TelemetryOrigin.CONTROLLER, 0) {
            listOf(TelemetryReading.measured(TelemetryMetric.DUTY, 0.0)) } }
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

    private suspend fun recorded(view: () -> HistoryPwmView?): List<Double?> {
        val writer = Writer(); var n = 0
        val c = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++n}" }, { 0 })
        val spec = LinkSpec("a", ProtocolKind.BEGODE, listOf(OwnedSource(0)))
        var now = tick()
        val ingress = HistoryTelemetryIngress(c, { now },
            { _, _ -> mapOf(HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0) to source) }, manageConnections = true)
        ingress.installNativePlan("wheel", listOf(spec), wheel()) { true }
        val capture = assertNotNull(ingress.capture(RawZeroDuty(), "attempt", spec, wheel(), SampleSessionOwnership { true },
            sampleFunnelChannel(4), pwmView = view))
        assertTrue(capture.prepareForObservation())
        now = tick(20); assertTrue(capture.processNotification(byteArrayOf(1)))
        return writer.samples.filter { it.key.metricId == TelemetryMetric.DUTY.id }.map { it.value }
    }

    @Test fun aNeverReportingWheelsRecordedDutyIsTheLiveEstimate() = kotlinx.coroutines.test.runTest {
        assertEquals(listOf<Double?>(42.5), recorded { HistoryPwmView("wheel", PwmEvidence.NEVER_REPORTED, 42.5f) })
    }

    @Test fun noEstimateRecordsUnknownNotTheRawZero() = kotlinx.coroutines.test.runTest {
        assertEquals(listOf<Double?>(null), recorded { HistoryPwmView("wheel", PwmEvidence.NEVER_REPORTED, null) })
    }

    @Test fun withoutAnEstimatorViewTheDecodersReadingStands() = kotlinx.coroutines.test.runTest {
        assertEquals(listOf<Double?>(0.0), recorded { null })
    }
}
