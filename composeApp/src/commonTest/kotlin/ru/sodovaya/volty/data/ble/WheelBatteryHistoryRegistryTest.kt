package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.BmsUuids
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Coordinator follow-up to review I2 (2026-11-04): a saved vehicle with ONE stored Begode or Leaperkim pack (a wheel
 * added as a battery), taken through the PRODUCTION path: `KableBmsRepository.effectiveLinkSpecs` (planLinkPacks
 * expands the link to the decoder plan's pack count) -> `prepareHistoryForInstalledLinks` -> `installNativePlan` ->
 * `HistoryRecordingConnections`, down to the registry the writer is begun with. Not a hand-built 2-pack fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class WheelBatteryHistoryRegistryTest {
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        var sources: List<HistorySource>? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { this.sources = sources }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer = HistoryOffer.ACCEPTED
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) = Unit
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class Protocol : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() = Unit
        override fun onNotification(data: ByteArray) = Unit
    }

    /** The registry a real connect begins the ride with, for a vehicle with one stored pack of [type]. */
    private fun recordedRegistry(type: BmsType): Pair<Vehicle, List<HistorySource>> {
        val writer = Writer()
        // The battery route of the wizard / BMS picker: one stored pack, no controllers.
        val vehicle = singlePackVehicle(id = "v", name = "Wheel", iconKey = "generic", bmsType = type, bmsAddress = "AA",
            chemistry = Chemistry.LI_ION_NMC, cellCount = 20, createdAt = Instant.fromEpochSeconds(0))
        assertEquals(1, vehicle.packs.size); assertTrue(vehicle.controllers.isEmpty())
        bleRepositoryTest(historyStartup = {
            HistoryTelemetryIngress(RideRecordingController(writer, HistoryRecordingDemand {}, { "ride" }, { 0 }),
                { HistoryTick(100, 10, 1) }, manageConnections = true)
        }) { repo ->
            repo.sealHistoryTelemetryConfigurationForTest()
            repo.installLinksForTest(vehicle, "AA", type)
            repo.prepareInstalledHistoryForTest()
            assertTrue(assertNotNull(repo.historyTelemetryCaptureForTest(Protocol(), "AA", SampleSessionOwnership { true }))
                .prepareForObservation())
        }
        return vehicle to assertNotNull(writer.sources, "the ride began")
    }

    @Test fun a_single_pack_begode_registers_its_controller_and_no_gps_speed() {
        val (vehicle, registry) = recordedRegistry(BmsType.BEGODE)
        assertTrue(registry.any { it.kind == HistorySourceKind.CONTROLLER }, registry.map { it.kind }.toString())
        assertFalse(registry.any { it.kind == HistorySourceKind.GPS }, "no GPS speed beside a wheel's own speed")
        assertTrue(vehicle.hasMotionSource, "the dashboard agrees")
        assertEquals(vehicle.hasMotionSource, !historyRecordsGpsSpeed(registry))
    }

    @Test fun a_single_pack_leaperkim_registers_its_controller_and_no_gps_speed() {
        val (vehicle, registry) = recordedRegistry(BmsType.LEAPERKIM)
        assertTrue(registry.any { it.kind == HistorySourceKind.CONTROLLER }, registry.map { it.kind }.toString())
        assertFalse(registry.any { it.kind == HistorySourceKind.GPS })
        assertEquals(vehicle.hasMotionSource, !historyRecordsGpsSpeed(registry))
    }

    @Test fun a_plain_bms_gets_the_gps_speed_source_and_the_dashboard_agrees() {
        val (vehicle, registry) = recordedRegistry(BmsType.ANT_BMS)
        assertEquals(setOf(HistorySourceKind.BATTERY, HistorySourceKind.GPS), registry.map { it.kind }.toSet())
        assertFalse(vehicle.hasMotionSource)
        assertEquals(vehicle.hasMotionSource, !historyRecordsGpsSpeed(registry))
    }
}
