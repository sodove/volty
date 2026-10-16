package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class NativeHistorySelectedAliasTest {
    private class Emitter : BmsProtocol() {
        override val uuids = BmsUuids("service", "notify", "write")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() = Unit
        override fun onNotification(data: ByteArray) = observeTelemetry(TelemetryOrigin.BATTERY, 0) {
            listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, data.single().toDouble()))
        }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val begins = mutableListOf<Pair<HistoryRide, List<HistorySource>>>()
        val batches = mutableListOf<HistoryBatch>()
        val finishes = mutableListOf<String?>()
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins += ride to sources }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer { batches += batch; return HistoryOffer.ACCEPTED }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { finishes += rideId }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private val a = Pack(3, "Direct A", BmsType.ANT_BMS, "AA", aliasGroup = "physical-pack")
    private val b = a.copy(index = 9, label = "Direct B", bmsAddress = "BB")
    private fun vehicle(packs: List<Pack> = listOf(a, b)) = Vehicle("bike", "Bike", "bicycle", packs, emptyList(),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private fun spec(pack: Pack) = LinkSpec(pack.bmsAddress, pack.bmsType.protocolKind(), listOf(OwnedSource(pack.index)))
    private class Fixture(managed: Boolean = true) {
        val writer = Writer(); private var time = 100L; private var id = 0
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++id}" }, { 0 })
        val ingress = HistoryTelemetryIngress(controller, { HistoryTick(1000 + ++time, time, 1) }, manageConnections = managed)
        val channel = sampleFunnelChannel(8)
        fun capture(link: LinkSpec, profile: Vehicle) = ingress.capture(Emitter(), "${link.address}:attempt", link, profile,
            SampleSessionOwnership { true }, channel)
        suspend fun install(link: LinkSpec, profile: Vehicle) = ingress.installNativePlan(profile.id, listOf(link), profile) { true }
        fun take() = assertIs<HistoryTelemetrySample>(channel.tryReceive().getOrNull())
        fun consume() { assertEquals(HistoryOffer.ACCEPTED, take().consume()) }
    }

    @Test fun oneQualifiedDirectAliasPathRecordsWithoutChangingProfile() = runTest {
        for (type in listOf(BmsType.ANT_BMS, BmsType.JBD_BMS, BmsType.JK_BMS, BmsType.DALY_BMS)) {
            val f = Fixture(); val pack = a.copy(bmsType = type)
            // An unqualified dormant alternative must not own or block the selected path.
            val profile = vehicle(listOf(pack, b.copy(bmsType = BmsType.VESC_BMS)))
            val link = spec(pack)
            try {
                f.install(link, profile)
                val capture = assertNotNull(f.capture(link, profile)); assertTrue(capture.prepareForObservation())
                assertTrue(capture.processNotification(byteArrayOf(10))); f.consume()
                val source = f.writer.begins.single().second.single()
                assertEquals(HistorySourceKind.BATTERY, source.kind)
                assertEquals(source.id, f.writer.batches.flatMap { it.samples }.single().key.sourceId)
                assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
                assertEquals("physical-pack", profile.packs.first().aliasGroup)
            } finally { f.channel.cancel() }
        }
    }

    @Test fun selectedSameProtocolPathSwitchDrainsOldFactsAndRetainsRideWithGap() = runTest {
        val f = Fixture(); val profile = vehicle(); val first = spec(a); val second = spec(b)
        var accepted: HistoryTelemetrySample? = null; var switch: Job? = null
        try {
            f.install(first, profile)
            val old = assertNotNull(f.capture(first, profile)); assertTrue(old.prepareForObservation())
            val gps = assertNotNull(f.controller.captureGpsRecording())
            assertTrue(old.processNotification(byteArrayOf(11))); accepted = f.take()
            switch = backgroundScope.launch { f.install(second, profile) }
            runCurrent(); assertFalse(switch.isCompleted)
            assertEquals(HistoryOffer.ACCEPTED, accepted.consume()); switch.join()
            val next = assertNotNull(f.capture(second, profile)); assertTrue(next.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(12)))
            val unexpected = f.channel.tryReceive().getOrNull(); (unexpected as? HistoryTelemetrySample)?.discard()
            assertNull(unexpected)
            assertTrue(next.processNotification(byteArrayOf(13))); f.consume()
            assertEquals(1, f.writer.begins.size); assertTrue(f.writer.finishes.isEmpty())
            assertEquals(listOf<Double?>(11.0, 13.0), f.writer.batches.flatMap { it.samples }.map { it.value })
            assertEquals(1, f.writer.batches.flatMap { it.samples }.map { it.key.sourceId }.distinct().size)
            assertTrue(f.writer.batches.flatMap { it.markers }.any { it.kind == HistoryMarkerKind.BLE_GAP })
            assertSame(gps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
        } finally { accepted?.discard(); f.channel.cancel(); switch?.cancelAndJoin() }
    }

    @Test fun physicalAliasIdentityIgnoresAddressSlotAndLabelsButTracksVehicleAndGroup() = runTest {
        suspend fun source(profile: Vehicle, pack: Pack): String {
            val f = Fixture(); val link = spec(pack)
            try {
                f.install(link, profile); assertTrue(assertNotNull(f.capture(link, profile)).prepareForObservation())
                return f.writer.begins.single().second.single().id
            } finally { f.channel.cancel() }
        }
        val original = source(vehicle(), a)
        val moved = b.copy(index = 80, label = "Renamed", bmsAddress = "cc")
        assertEquals(original, source(vehicle(listOf(moved)), moved))
        assertNotEquals(original, source(vehicle().copy(id = "other-bike"), a))
        val other = a.copy(aliasGroup = "other-pack")
        assertNotEquals(original, source(vehicle(listOf(other)), other))
    }

    @Test fun producerProtocolChangeCannotHideInsideSamePhysicalAlias() = runTest {
        val f = Fixture(); val other = b.copy(bmsType = BmsType.JBD_BMS)
        val profile = vehicle(listOf(a, other)); val first = spec(a); val second = spec(other)
        try {
            f.install(first, profile); assertTrue(assertNotNull(f.capture(first, profile)).prepareForObservation())
            f.install(second, profile); assertTrue(assertNotNull(f.capture(second, profile)).prepareForObservation())
            assertEquals(2, f.writer.begins.size); assertEquals(listOf<String?>("ride-1"), f.writer.finishes)
            assertNotEquals(f.writer.begins[0].second.single().id, f.writer.begins[1].second.single().id)
            assertTrue(f.writer.batches.flatMap { it.markers }.any { it.kind == HistoryMarkerKind.SOURCE_CHANGE })
        } finally { f.channel.cancel() }
    }

    @Test fun twoInstalledAliasPathsRemainRejectedByWholePlanAdmission() = runTest {
        val f = Fixture(); val profile = vehicle(); val first = spec(a); val second = spec(b)
        try {
            f.ingress.installNativePlan(profile.id, listOf(first, second), profile) { true }
            assertNull(f.capture(first, profile)); assertNull(f.capture(second, profile))
            assertEquals(1L, f.ingress.diagnostics.value.setupFailures); assertTrue(f.writer.begins.isEmpty())
        } finally { f.channel.cancel() }
    }

    @Test fun unmanagedAndUnqualifiedProducersCannotBorrowSelectedPathPermission() = runTest {
        val unmanaged = Fixture(managed = false)
        try { assertNull(unmanaged.capture(spec(a), vehicle())) }
        finally { unmanaged.channel.cancel() }
        for (type in listOf(BmsType.BEGODE, BmsType.VESC_BMS)) {
            val f = Fixture(); val pack = a.copy(bmsType = type); val profile = vehicle(listOf(pack))
            val link = if (type == BmsType.BEGODE) spec(pack).copy(ownedPacks = listOf(OwnedSource(3), OwnedSource(4)))
                else LinkSpec(pack.bmsAddress, ProtocolKind.VESC, listOf(OwnedSource(3, kind = ProtocolKind.VESC_BMS)))
            try {
                f.install(link, profile); assertNull(f.capture(link, profile)); assertTrue(f.writer.begins.isEmpty())
                assertEquals(1L, f.ingress.diagnostics.value.setupFailures)
            } finally { f.channel.cancel() }
        }
    }

    @Test fun wrongSelectionVehicleCannotReuseProfilesAliasNamespace() = runTest {
        val f = Fixture(); val profile = vehicle(); val link = spec(a)
        try {
            f.install(link, profile); assertTrue(assertNotNull(f.capture(link, profile)).prepareForObservation())
            f.ingress.installNativePlan("other-vehicle", listOf(link), profile) { true }
            assertNull(f.capture(link, profile)); assertNull(f.ingress.manualControls())
            assertEquals(1L, f.ingress.diagnostics.value.setupFailures)
            assertEquals(1, f.writer.begins.size); assertEquals(listOf<String?>("ride-1"), f.writer.finishes)
        } finally { f.channel.cancel() }
    }
}
