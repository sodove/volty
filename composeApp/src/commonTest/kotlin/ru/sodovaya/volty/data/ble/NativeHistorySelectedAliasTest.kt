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
        var duringOffer: ((Long, HistoryBatch) -> Unit)? = null
        var finishGate: CompletableDeferred<Unit>? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins += ride to sources }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            duringOffer?.invoke(generation, batch)
            batches += batch
            return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {
            finishes += rideId
            finishGate?.await()
        }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class CacheEmitter(private val reason: TelemetryUnknown = TelemetryUnknown.PARTIAL_SOURCE,
        private val withController: Boolean = false) : BmsProtocol() {
        override val uuids = BmsUuids("service", "notify", "write")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() = Unit
        override fun onNotification(data: ByteArray) {
            observeTelemetry(TelemetryOrigin.BATTERY, 0) {
                listOf(TelemetryReading(TelemetryMetric.CURRENT, null, reason))
            }
            if (withController) observeTelemetry(TelemetryOrigin.CONTROLLER, 0) {
                listOf(TelemetryReading.measured(TelemetryMetric.SPEED, 9.0))
            }
        }
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
        fun capture(link: LinkSpec, profile: Vehicle, protocol: BmsProtocol = Emitter()) = ingress.capture(protocol, "${link.address}:attempt", link, profile,
            SampleSessionOwnership { true }, channel)
        suspend fun install(link: LinkSpec, profile: Vehicle) = ingress.installNativePlan(profile.id, listOf(link), profile) { true }

        /** Retraction: managed telemetry is issued directly to the writer; it no longer waits in the dashboard sample queue. */
        fun assertDashboardEmpty() = assertTrue(channel.tryReceive().isFailure,
            "managed history must not enqueue a dashboard sample")
    }

    private val cached = b.copy(bmsType = BmsType.VESC_BMS)
    private fun gateway(pack: Pack = cached) = Controller(7, "Motor", ControllerType.VESC, pack.bmsAddress, canId = 41)
    private fun cacheVehicle(pack: Pack = cached) = vehicle(listOf(a, pack)).copy(controllers = listOf(gateway(pack)))
    private fun cacheSpec(pack: Pack = cached) = LinkSpec(pack.bmsAddress, ProtocolKind.VESC,
        listOf(OwnedSource(pack.index, pack.canId, ProtocolKind.VESC_BMS)), listOf(OwnedSource(7, 41)))

    @Test fun selectedCacheAliasAdmitsAbsenceWithoutBlockingItsControllerRegistry() = runTest {
        val f = Fixture(); val profile = cacheVehicle(); val link = cacheSpec()
        try {
            f.install(link, profile)
            val capture = assertNotNull(f.capture(link, profile, CacheEmitter()))
            assertTrue(capture.prepareForObservation())
            assertTrue(capture.processNotification(byteArrayOf(1))); f.assertDashboardEmpty()
            val registry = f.writer.begins.single().second
            assertEquals(setOf(HistorySourceKind.BATTERY, HistorySourceKind.CONTROLLER, HistorySourceKind.REPORTED_CAN), registry.map { it.kind }.toSet())
            val sample = f.writer.batches.flatMap { it.samples }.single()
            assertEquals(registry.single { it.kind == HistorySourceKind.BATTERY }.id, sample.key.sourceId)
            assertNull(sample.value); assertEquals(HistoryUnknownReason.PARTIAL_SOURCE, sample.unknown)
            assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
        } finally { f.channel.cancel() }
    }

    @Test fun directToCacheSelectionSplitsProducerPolicyAfterWriterAcceptance() = runTest {
        val f = Fixture(); val profile = cacheVehicle(); val direct = spec(a); val cache = cacheSpec()
        val finishAck = CompletableDeferred<Unit>()
        var switch: Job? = null
        try {
            f.install(direct, profile)
            val old = assertNotNull(f.capture(direct, profile)); assertTrue(old.prepareForObservation())
            val oldGps = assertNotNull(f.controller.captureGpsRecording())
            assertTrue(old.processNotification(byteArrayOf(11)))
            f.assertDashboardEmpty()
            assertEquals(listOf(11.0), f.writer.batches.flatMap { it.samples }.map { it.value },
                "the accepted fact already belongs to the writer before selection changes")
            f.writer.finishGate = finishAck
            switch = backgroundScope.launch { f.install(cache, profile) }
            runCurrent()
            assertFalse(switch.isCompleted)
            assertEquals(listOf<String?>("ride-1"), f.writer.finishes)
            assertEquals(1, f.writer.begins.size, "new begin must wait for the old ride's durable finish ACK")
            finishAck.complete(Unit)
            runCurrent()
            switch.join()
            f.writer.finishGate = null
            val next = assertNotNull(f.capture(cache, profile, CacheEmitter())); assertTrue(next.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(12)))
            assertEquals(1, f.writer.batches.flatMap { it.samples }.size, "retired callback must not issue another fact")
            f.assertDashboardEmpty()
            assertTrue(next.processNotification(byteArrayOf(1))); f.assertDashboardEmpty()
            assertEquals(2, f.writer.begins.size); assertEquals(listOf<String?>("ride-1"), f.writer.finishes)
            val oldSource = f.writer.begins[0].second.single().id
            val cacheSource = f.writer.begins[1].second.single { it.kind == HistorySourceKind.BATTERY }.id
            assertNotEquals(oldSource, cacheSource)
            val samples = f.writer.batches.flatMap { it.samples }
            assertEquals(listOf<Double?>(11.0, null), samples.map { it.value })
            assertEquals(listOf(oldSource, cacheSource), samples.map { it.key.sourceId })
            assertEquals(HistoryUnknownReason.PARTIAL_SOURCE, samples.last().unknown)
            assertTrue(f.writer.batches.flatMap { it.markers }.any { it.kind == HistoryMarkerKind.SOURCE_CHANGE })
            assertNotSame(oldGps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
            assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
        } finally { finishAck.complete(Unit); f.channel.cancel(); switch?.cancelAndJoin() }
    }

    @Test fun selectedCacheIdentityTracksVehicleGroupAndPolicyInsteadOfSelectedEndpoint() {
        fun source(pack: Pack, profile: Vehicle = cacheVehicle(pack)) =
            nativeHistorySourcesForSelectedLink(cacheSpec(pack), profile).getValue(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0)).id
        val first = source(cached)
        val moved = cached.copy(index = 80, label = "Renamed", bmsAddress = "CC", canId = 42)
        assertEquals(first, source(moved))
        assertNotEquals(first, source(cached, cacheVehicle().copy(id = "other-bike")))
        assertNotEquals(first, source(cached.copy(aliasGroup = "other-pack")))
        assertNotEquals(first, nativeHistorySourcesForSelectedLink(spec(a), cacheVehicle()).values.single().id)
        assertFailsWith<IllegalArgumentException> { nativeHistorySourcesForLink(cacheSpec(), cacheVehicle()) }
        assertFailsWith<IllegalArgumentException> { source(cached.copy(aliasGroup = " ")) }
    }

    @Test fun returnFromCacheCannotReviveOriginalDirectCapture() = runTest {
        val f = Fixture(); val profile = cacheVehicle(); val direct = spec(a); val cache = cacheSpec()
        val originalProtocol = Emitter()
        var switch: Job? = null
        try {
            f.install(direct, profile)
            val original = assertNotNull(f.capture(direct, profile, originalProtocol))
            assertTrue(original.prepareForObservation())
            assertTrue(original.processNotification(byteArrayOf(11))); f.assertDashboardEmpty()
            val originalGps = assertNotNull(f.controller.captureGpsRecording())
            f.install(cache, profile)
            val cached = assertNotNull(f.capture(cache, profile, CacheEmitter())); assertTrue(cached.prepareForObservation())
            assertTrue(cached.processNotification(byteArrayOf(1))); f.assertDashboardEmpty()
            assertEquals(listOf(11.0, null), f.writer.batches.flatMap { it.samples }.map { it.value },
                "both facts have already been issued to the writer before the next selection")
            switch = backgroundScope.launch { f.install(direct, profile) }
            runCurrent(); switch.join()
            assertEquals(listOf<String?>("ride-1", "ride-2"), f.writer.finishes,
                "each producer-policy change finishes the ride that was active before it")
            // Reuse both the exact original spec and protocol object. Only the
            // newly acquired binding may observe this new recording.
            val restored = assertNotNull(f.capture(direct, profile, originalProtocol))
            assertTrue(restored.prepareForObservation())
            assertTrue(original.processNotification(byteArrayOf(12)))
            assertTrue(cached.processNotification(byteArrayOf(1)))
            assertEquals(2, f.writer.batches.flatMap { it.samples }.size, "retired direct/cache callbacks must not leak")
            f.assertDashboardEmpty()
            assertTrue(restored.processNotification(byteArrayOf(14))); f.assertDashboardEmpty()
            assertEquals(3, f.writer.begins.size)
            assertEquals(listOf<String?>("ride-1", "ride-2"), f.writer.finishes)
            val firstSource = f.writer.begins[0].second.single().id
            assertEquals(firstSource, f.writer.begins[2].second.single().id)
            val samples = f.writer.batches.flatMap { it.samples }
            assertEquals(listOf<Double?>(11.0, null, 14.0), samples.map { it.value })
            assertEquals(listOf(firstSource, f.writer.begins[1].second.single { it.kind == HistorySourceKind.BATTERY }.id, firstSource),
                samples.map { it.key.sourceId })
            assertEquals(HistoryUnknownReason.PARTIAL_SOURCE, samples[1].unknown)
            assertNotSame(originalGps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
            assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
        } finally { f.channel.cancel(); switch?.cancelAndJoin() }
    }

    @Test fun sameCachePolicyCanPathSwitchRetainsRideAndGpsAfterAcceptedPrefix() = runTest {
        val f = Fixture(); val alternate = cached.copy(index = 80, label = "CAN path", canId = 42)
        val profile = cacheVehicle().copy(packs = listOf(a, cached, alternate))
        val first = cacheSpec(); val second = cacheSpec(alternate)
        var switch: Job? = null
        try {
            f.install(first, profile)
            val old = assertNotNull(f.capture(first, profile, CacheEmitter(TelemetryUnknown.UNAVAILABLE)))
            assertTrue(old.prepareForObservation()); val gps = assertNotNull(f.controller.captureGpsRecording())
            assertTrue(old.processNotification(byteArrayOf(1))); f.assertDashboardEmpty()
            assertEquals(1, f.writer.batches.flatMap { it.samples }.size,
                "the accepted prefix already belongs to the writer before the path switch")
            switch = backgroundScope.launch { f.install(second, profile) }
            runCurrent(); switch.join()
            assertTrue(f.writer.finishes.isEmpty())
            val next = assertNotNull(f.capture(second, profile, CacheEmitter())); assertTrue(next.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(1)))
            assertEquals(1, f.writer.batches.flatMap { it.samples }.size, "retired callback must not issue another fact")
            f.assertDashboardEmpty()
            assertTrue(next.processNotification(byteArrayOf(1))); f.assertDashboardEmpty()
            assertEquals(1, f.writer.begins.size); assertTrue(f.writer.finishes.isEmpty())
            val samples = f.writer.batches.flatMap { it.samples }
            assertEquals(listOf(HistoryUnknownReason.UNAVAILABLE, HistoryUnknownReason.PARTIAL_SOURCE), samples.map { it.unknown })
            assertTrue(samples.all { it.value == null })
            assertEquals(1, samples.map { it.key.sourceId }.distinct().size)
            val markers = f.writer.batches.flatMap { it.markers }
            assertTrue(markers.any { it.kind == HistoryMarkerKind.BLE_GAP })
            assertTrue(markers.none { it.kind == HistoryMarkerKind.SOURCE_CHANGE })
            assertSame(gps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
            assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
        } finally { f.channel.cancel(); switch?.cancelAndJoin() }
    }

    @Test fun cacheAliasStillRequiresUsableGroupAndMatchingVehicleNamespace() = runTest {
        for (variant in 1..2) {
            val f = Fixture(); val pack = if (variant == 2) cached.copy(aliasGroup = " ") else cached
            val profile = cacheVehicle(pack); val link = cacheSpec(pack)
            try {
                f.ingress.installNativePlan(if (variant == 1) "other-bike" else profile.id,
                    listOf(link), profile) { true }
                assertNull(f.capture(link, profile, CacheEmitter()))
                assertNull(f.ingress.manualControls()); assertTrue(f.writer.begins.isEmpty())
                assertEquals(1L, f.ingress.diagnostics.value.setupFailures)
            } finally { f.channel.cancel() }
        }
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
                assertTrue(capture.processNotification(byteArrayOf(10))); f.assertDashboardEmpty()
                val source = f.writer.begins.single().second.single()
                assertEquals(HistorySourceKind.BATTERY, source.kind)
                assertEquals(source.id, f.writer.batches.flatMap { it.samples }.single().key.sourceId)
                assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
                assertEquals("physical-pack", profile.packs.first().aliasGroup)
            } finally { f.channel.cancel() }
        }
    }

    @Test fun selectedSameProtocolPathSwitchRetainsRideWithGap() = runTest {
        val f = Fixture(); val profile = vehicle(); val first = spec(a); val second = spec(b)
        var switch: Job? = null
        try {
            f.install(first, profile)
            val old = assertNotNull(f.capture(first, profile)); assertTrue(old.prepareForObservation())
            val gps = assertNotNull(f.controller.captureGpsRecording())
            assertTrue(old.processNotification(byteArrayOf(11)))
            f.assertDashboardEmpty()
            assertEquals(listOf(11.0), f.writer.batches.flatMap { it.samples }.map { it.value })
            switch = backgroundScope.launch { f.install(second, profile) }
            runCurrent(); switch.join()
            assertTrue(f.writer.finishes.isEmpty(), "same physical alias path retains the ride")
            val next = assertNotNull(f.capture(second, profile)); assertTrue(next.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(12)))
            assertEquals(1, f.writer.batches.flatMap { it.samples }.size,
                "the retired attempt cannot issue another fact")
            f.assertDashboardEmpty()
            assertTrue(next.processNotification(byteArrayOf(13))); f.assertDashboardEmpty()
            assertEquals(1, f.writer.begins.size); assertTrue(f.writer.finishes.isEmpty())
            assertEquals(listOf<Double?>(11.0, 13.0), f.writer.batches.flatMap { it.samples }.map { it.value })
            assertEquals(1, f.writer.batches.flatMap { it.samples }.map { it.key.sourceId }.distinct().size)
            assertTrue(f.writer.batches.flatMap { it.markers }.any { it.kind == HistoryMarkerKind.BLE_GAP })
            assertSame(gps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
        } finally { f.channel.cancel(); switch?.cancelAndJoin() }
    }

    @Test fun aliasSwitchWaitsForInFlightDirectWriterOfferThenRetainsTheRide() = runTest {
        val f = Fixture(); val profile = vehicle(); val first = spec(a); val second = spec(b)
        var switch: Job? = null
        var selectionWaitedInsideOffer = false
        try {
            f.install(first, profile)
            val old = assertNotNull(f.capture(first, profile)); assertTrue(old.prepareForObservation())
            val gps = assertNotNull(f.controller.captureGpsRecording())
            f.writer.duringOffer = { _, batch ->
                if (batch.samples.any { it.value == 11.0 }) {
                    switch = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { f.install(second, profile) }
                    selectionWaitedInsideOffer = !assertNotNull(switch).isCompleted
                }
            }

            // The selection closes this attempt's observation gate inside offer(). It must wait
            // for this exact direct writer call to return before replacing the installed plan.
            assertTrue(old.processNotification(byteArrayOf(11)))
            assertTrue(selectionWaitedInsideOffer, "the switch must suspend while offer is in flight")
            f.writer.duringOffer = null
            assertEquals(listOf(11.0), f.writer.batches.flatMap { it.samples }.map { it.value })
            f.assertDashboardEmpty()
            runCurrent()
            assertNotNull(switch).join()

            val next = assertNotNull(f.capture(second, profile)); assertTrue(next.prepareForObservation())
            assertTrue(old.processNotification(byteArrayOf(12)))
            assertEquals(1, f.writer.batches.flatMap { it.samples }.size,
                "the retired attempt cannot publish after its in-flight offer")
            f.assertDashboardEmpty()
            assertTrue(next.processNotification(byteArrayOf(13))); f.assertDashboardEmpty()
            assertEquals(listOf(11.0, 13.0), f.writer.batches.flatMap { it.samples }.map { it.value })
            assertEquals(1, f.writer.begins.size)
            assertTrue(f.writer.finishes.isEmpty())
            assertTrue(f.writer.batches.flatMap { it.markers }.any { it.kind == HistoryMarkerKind.BLE_GAP })
            assertTrue(f.writer.batches.flatMap { it.markers }.none { it.kind == HistoryMarkerKind.SOURCE_CHANGE })
            assertSame(gps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
        } finally { f.channel.cancel(); switch?.cancelAndJoin() }
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

    @Test fun installedDirectAlternativesSelectOneStablePhysicalPathRegardlessOfPlanOrder() = runTest {
        for (reverse in listOf(false, true)) {
            val f = Fixture(); val profile = vehicle(); val first = spec(a); val second = spec(b)
            try {
                f.ingress.installNativePlan(profile.id, if (reverse) listOf(second, first) else listOf(first, second), profile) { true }
                val selected = assertNotNull(f.capture(first, profile)); assertTrue(selected.prepareForObservation())
                assertNull(f.capture(second, profile))
                assertTrue(selected.processNotification(byteArrayOf(11))); f.assertDashboardEmpty()
                assertEquals(1, f.writer.begins.single().second.size)
                assertEquals(11.0, f.writer.batches.flatMap { it.samples }.single().value)
                assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
            } finally { f.channel.cancel() }
        }
    }

    @Test fun installedDirectAndCachePathsRecordOneBatteryWithoutDroppingGatewayControllers() = runTest {
        val f = Fixture(); val profile = cacheVehicle(); val direct = spec(a); val cache = cacheSpec()
        val directProtocol = Emitter()
        try {
            f.ingress.installNativePlan(profile.id, listOf(cache, direct), profile) { true }
            val battery = assertNotNull(f.capture(direct, profile, directProtocol))
            val gateway = assertNotNull(f.capture(cache, profile, CacheEmitter(withController = true)))
            assertTrue(gateway.prepareForObservation()); assertTrue(battery.prepareForObservation())
            val gps = assertNotNull(f.controller.captureGpsRecording())
            assertTrue(battery.processNotification(byteArrayOf(11))); f.assertDashboardEmpty()
            assertTrue(gateway.processNotification(byteArrayOf(1))); f.assertDashboardEmpty()
            val sources = f.writer.begins.single().second
            assertEquals(3, sources.size)
            val physical = sources.single { it.kind == HistorySourceKind.BATTERY }.id
            val motor = sources.single { it.kind == HistorySourceKind.CONTROLLER }.id
            assertEquals(listOf(physical, motor), f.writer.batches.flatMap { it.samples }.map { it.key.sourceId })
            // The live repository may yield the direct link to its head unit.
            // Cache availability does not make its numbers earned history.
            f.ingress.retireNativeAttempt(directProtocol)
            assertTrue(battery.processNotification(byteArrayOf(12)))
            assertTrue(gateway.processNotification(byteArrayOf(1))); f.assertDashboardEmpty()
            assertEquals(listOf<Double?>(11.0, 9.0, 9.0), f.writer.batches.flatMap { it.samples }.map { it.value })
            assertEquals(1, f.writer.begins.size); assertTrue(f.writer.finishes.isEmpty())
            assertTrue(f.writer.batches.flatMap { it.markers }.any { it.kind == HistoryMarkerKind.BLE_GAP })
            assertSame(gps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
            assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
        } finally { f.channel.cancel() }
    }

    @Test fun repositoryInstallsItsCompleteNativeAliasPlanWithOneBatteryRecorder() = bleRepositoryTest { repo ->
        val f = Fixture(); val profile = cacheVehicle()
        try {
            repo.configureHistoryTelemetryIngress(f.ingress)
            repo.installLinksForTest(profile, a.bmsAddress, a.bmsType)
            repo.prepareInstalledHistoryForTest()
            val battery = assertNotNull(repo.historyTelemetryCaptureForTest(Emitter(), a.bmsAddress, SampleSessionOwnership { true }))
            val gateway = assertNotNull(repo.historyTelemetryCaptureForTest(CacheEmitter(withController = true), cached.bmsAddress,
                SampleSessionOwnership { true }))
            assertTrue(battery.prepareForObservation()); assertTrue(gateway.prepareForObservation())
            assertTrue(battery.processNotification(byteArrayOf(11)))
            assertTrue(gateway.processNotification(byteArrayOf(1)))
            f.assertDashboardEmpty()
            runCurrent()
            assertEquals(3, f.writer.begins.single().second.size)
            assertEquals(listOf<Double?>(11.0, 9.0), f.writer.batches.flatMap { it.samples }.map { it.value })
            assertEquals(setOf("current_a", "speed_kmh"), f.writer.batches.flatMap { it.samples }.map { it.key.metricId }.toSet())
            assertEquals(0L, f.ingress.diagnostics.value.setupFailures)
            repo.disconnect()
            assertEquals(listOf<String?>("ride-1"), f.writer.finishes)
            assertNull(f.controller.captureGpsRecording())
        } finally { f.channel.cancel() }
    }

    @Test fun unmanagedAndUnqualifiedProducersCannotBorrowSelectedPathPermission() = runTest {
        val unmanaged = Fixture(managed = false)
        try { assertNull(unmanaged.capture(spec(a), vehicle())) }
        finally { unmanaged.channel.cancel() }
        for (type in listOf(BmsType.BEGODE)) {
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
