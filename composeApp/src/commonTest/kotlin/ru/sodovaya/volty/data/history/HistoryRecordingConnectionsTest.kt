package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingConnectionsTest {
    private val battery = HistorySource("pack", HistorySourceKind.BATTERY, "Battery")
    private val motor = HistorySource("motor", HistorySourceKind.CONTROLLER, "Motor")
    private val plan get() = mapOf("battery-link" to listOf(battery), "motor-link" to listOf(motor))
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val begins = mutableListOf<Pair<HistoryRide, List<HistorySource>>>()
        val offers = mutableListOf<HistoryBatch>()
        val ends = mutableListOf<String?>()
        var beginGate: CompletableDeferred<Unit>? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) {
            begins += ride to sources.toList(); beginGate?.await()
        }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            offers += batch; return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { ends += rideId }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class Fixture {
        val writer = Writer()
        var time = 0L
        private var ids = 0
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++ids}" }, { 0 })
        val connections = HistoryRecordingConnections(controller) { HistoryTick(10000 + ++time, time, 1) }
        fun markers() = writer.offers.flatMap { it.markers }.map { it.kind }
        fun offer(source: String): HistoryOffer {
            val context = controller.captureTelemetryRecording() ?: return HistoryOffer.RETIRED
            return controller.offerTelemetry(context, HistoryBatch(context.rideId,
                listOf(HistorySample(HistoryTick(20000, 1000, 1), HistorySeriesKey(source, "voltage_v"), 42.0)),
                emptyList(), emptyList()))
        }
    }

    @Test fun firstReadyUsesWholeFrozenPlanAndSecondLinkDoesNotRotateRideOrAdmission() = runTest {
        val f = Fixture(); val c = f.connections
        val mutableBattery = mutableListOf(battery)
        val mutablePlan = mutableMapOf("battery-link" to mutableBattery, "motor-link" to mutableListOf(motor))
        val selected = c.install("bike", mutablePlan)
        mutableBattery.clear(); mutablePlan.clear()
        val a = assertNotNull(c.newAttempt(selected, "battery-link"))
        val b = assertNotNull(c.newAttempt(selected, "motor-link"))
        assertNull(f.controller.captureTelemetryRecording())
        assertTrue(c.ready(a))
        val captured = assertNotNull(f.controller.captureTelemetryRecording())
        assertEquals(setOf(battery, motor), f.writer.begins.single().second.toSet())
        assertTrue(c.ready(b)); assertTrue(c.ready(b))
        assertEquals(1, f.writer.begins.size)
        assertSame(captured.gate, assertNotNull(f.controller.captureTelemetryRecording()).gate)
        assertEquals(HistoryOffer.ACCEPTED, f.offer("motor"))
        assertEquals(listOf(HistoryMarkerKind.CONNECTED), f.markers())
    }

    @Test fun onlyLastLostOpensGapAndReconnectKeepsRideAndFullRegistry() = runTest {
        val f = Fixture(); val c = f.connections; val s = c.install("bike", plan)
        val a = assertNotNull(c.newAttempt(s, "battery-link")); val b = assertNotNull(c.newAttempt(s, "motor-link"))
        c.ready(a); c.ready(b)
        val old = assertNotNull(f.controller.captureTelemetryRecording())
        assertTrue(c.lost(a)); assertTrue(f.controller.state.value.intent.linkConnected)
        assertSame(old.gate, assertNotNull(f.controller.captureTelemetryRecording()).gate)
        assertTrue(c.lost(b)); assertFalse(c.lost(b)); assertFalse(c.ready(b))
        assertNull(f.controller.captureTelemetryRecording())
        assertEquals(listOf(HistoryMarkerKind.CONNECTED, HistoryMarkerKind.BLE_GAP), f.markers())
        val next = assertNotNull(c.newAttempt(s, "battery-link")); assertTrue(c.ready(next))
        val fresh = assertNotNull(f.controller.captureTelemetryRecording())
        assertEquals(old.rideId, fresh.rideId); assertNotSame(old.gate, fresh.gate)
        assertEquals(setOf("pack", "motor"), f.controller.state.value.intent.sourcePlan.keys)
        assertEquals(1, f.writer.begins.size); assertTrue(f.writer.ends.isEmpty())
    }

    @Test fun replacingAttemptRetiresOnlyThatPhysicalLinkAndStaleEventsCannotAffectSuccessor() = runTest {
        val f = Fixture(); val c = f.connections; val s = c.install("bike", plan)
        val a = assertNotNull(c.newAttempt(s, "battery-link")); val b = assertNotNull(c.newAttempt(s, "motor-link"))
        c.ready(a); c.ready(b)
        val next = assertNotNull(c.newAttempt(s, "battery-link"))
        assertFalse(c.ready(a)); assertFalse(c.lost(a))
        assertTrue(f.controller.state.value.intent.linkConnected)
        assertTrue(c.lost(b)); assertNull(f.controller.captureTelemetryRecording())
        assertTrue(c.ready(next)); assertTrue(f.controller.state.value.intent.linkConnected)
        assertEquals(1, f.markers().count { it == HistoryMarkerKind.BLE_GAP })
    }

    @Test fun selectionReplacementEndsOldRideAndGpsBeforeNewConnectionIsReady() = runTest {
        val f = Fixture(); val c = f.connections; val old = c.install("bike", plan)
        val a = assertNotNull(c.newAttempt(old, "battery-link")); c.ready(a)
        val oldGeneration = f.controller.state.value.intent.connectionGeneration!!
        val oldGps = assertNotNull(f.controller.captureGpsRecording())
        val next = c.install("wheel", mapOf("battery-link" to listOf(battery)))
        assertNull(f.controller.state.value.intent.ride)
        assertNull(f.controller.captureGpsRecording())
        assertFalse(f.controller.reportGpsObservationFailure(oldGps))
        assertEquals(HistoryOffer.RETIRED, f.controller.offerGps("ride-1",
            HistoryGpsFix(HistoryTick(20000, 1000, 1), 56.0, 60.0, 5.0, null)))
        assertEquals(listOf<String?>("ride-1"), f.writer.ends)
        assertNull(f.controller.captureTelemetryRecording())
        assertNull(c.newAttempt(old, "motor-link")); assertFalse(c.ready(a)); assertFalse(c.lost(a))
        assertFalse(c.disconnect(old)); assertFalse(c.stop(old, "ride-1")); assertFalse(c.start(old))
        val b = assertNotNull(c.newAttempt(next, "battery-link")); assertTrue(c.ready(b))
        assertEquals("wheel", f.controller.state.value.intent.ride?.vehicleId)
        assertTrue(f.controller.state.value.intent.connectionGeneration!! > oldGeneration)
        assertEquals(listOf<String?>("ride-1"), f.writer.ends)
        assertTrue(HistoryMarkerKind.VEHICLE_SWITCH in f.markers())
    }

    @Test fun registryChangeEndsOldRideBeforeNewReadyAndInvalidPlanDoesNotRetireCurrent() = runTest {
        val f = Fixture(); val c = f.connections; val s = c.install("bike", plan)
        val a = assertNotNull(c.newAttempt(s, "battery-link")); c.ready(a)
        assertFailsWith<IllegalArgumentException> { c.install("bike", mapOf("a" to listOf(battery), "b" to listOf(battery))) }
        assertFailsWith<IllegalArgumentException> { c.install("", plan) }
        assertFailsWith<IllegalArgumentException> { c.install("bike", mapOf("" to listOf(battery))) }
        assertTrue(c.ready(a)); assertTrue(f.controller.state.value.intent.linkConnected)
        assertNull(c.newAttempt(s, "unknown"))
        val next = c.install("bike", mapOf("battery-link" to listOf(battery.copy(id = "recalibrated"))))
        assertEquals(listOf<String?>("ride-1"), f.writer.ends)
        assertNull(f.controller.state.value.intent.ride)
        assertEquals(1, f.writer.begins.size)
        c.ready(assertNotNull(c.newAttempt(next, "battery-link")))
        assertEquals(listOf<String?>("ride-1"), f.writer.ends)
        assertTrue(HistoryMarkerKind.SOURCE_CHANGE in f.markers())
    }

    @Test fun repeatedUnreadySelectionsCannotHideOldRideBehindAnUnissuedGeneration() = runTest {
        val f = Fixture(); val c = f.connections; val a = c.install("bike", plan)
        c.ready(assertNotNull(c.newAttempt(a, "battery-link")))
        val oldGps = assertNotNull(f.controller.captureGpsRecording())
        val b = c.install("bike", plan)
        assertEquals("ride-1", f.controller.state.value.intent.ride?.id)
        assertNull(f.controller.captureTelemetryRecording())
        val next = c.install("wheel", plan)
        assertNull(f.controller.state.value.intent.ride)
        assertFalse(f.controller.reportGpsObservationFailure(oldGps))
        assertFalse(c.disconnect(b)); assertEquals(1, f.writer.begins.size)
        assertTrue(c.disconnect(next)); assertEquals(listOf<String?>("ride-1"), f.writer.ends)
    }

    @Test fun manualStopSurvivesReconnectAndDelayedStopCannotEndExplicitRestart() = runTest {
        val f = Fixture(); val c = f.connections; val s = c.install("bike", plan)
        val a = assertNotNull(c.newAttempt(s, "battery-link")); c.ready(a)
        assertTrue(c.stop(s, "ride-1")); c.lost(a)
        val b = assertNotNull(c.newAttempt(s, "battery-link")); c.ready(b)
        assertNull(f.controller.state.value.intent.ride)
        assertTrue(c.start(s)); assertEquals("ride-2", f.controller.state.value.intent.ride?.id)
        assertFalse(c.stop(s, "ride-1")); assertEquals("ride-2", f.controller.state.value.intent.ride?.id)
        assertTrue(c.disconnect(s)); assertFalse(c.disconnect(s)); assertFalse(c.ready(b))
        assertNull(c.newAttempt(s, "battery-link")); assertFalse(c.start(s))
        assertEquals(listOf<String?>("ride-1", "ride-2"), f.writer.ends)
    }

    @Test fun foreignCapabilitiesAndUnreadyExplicitStartAreRejectedWithoutWriterCalls() = runTest {
        val f = Fixture(); val other = Fixture(); val c = f.connections
        val s = c.install("bike", plan); val foreign = other.connections.install("bike", plan)
        assertFalse(c.start(s)); assertFalse(c.disconnect(foreign)); assertNull(c.newAttempt(foreign, "battery-link"))
        val a = assertNotNull(other.connections.newAttempt(foreign, "battery-link"))
        assertFalse(c.ready(a)); assertFalse(c.lost(a)); assertTrue(f.writer.begins.isEmpty())
        val pending = assertNotNull(c.newAttempt(s, "battery-link"))
        assertTrue(c.lost(pending)); assertFalse(c.ready(pending)); assertTrue(f.writer.begins.isEmpty())
    }

    @Test fun queuedLossWaitsForDurableBeginEvenWhenReadyCallerIsCancelled() = runTest {
        val f = Fixture(); val c = f.connections; val s = c.install("bike", plan)
        val a = assertNotNull(c.newAttempt(s, "battery-link")); val gate = CompletableDeferred<Unit>()
        f.writer.beginGate = gate
        var readyReturned = false
        val preparing = launch { c.ready(a); readyReturned = true }
        var loss: Deferred<Boolean>? = null
        try {
            runCurrent(); assertEquals(1, f.writer.begins.size)
            assertNull(f.controller.captureTelemetryRecording())
            preparing.cancel()
            loss = async { c.lost(a) }; runCurrent(); assertFalse(loss.isCompleted)
            gate.complete(Unit); preparing.join(); assertTrue(loss.await())
            assertFalse(readyReturned); assertNull(f.controller.captureTelemetryRecording())
            assertEquals(listOf(HistoryMarkerKind.CONNECTED, HistoryMarkerKind.BLE_GAP), f.markers())
            assertTrue(f.writer.ends.isEmpty())
        } finally { gate.complete(Unit); preparing.cancelAndJoin(); loss?.cancelAndJoin() }
    }
}
