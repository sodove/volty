package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideRecordingPolicyTest {
    private fun tick(ms: Long) = HistoryTick(10000 + ms, ms, 1)
    private fun connected() = recordingTransition(RecordingIntent(), RecordingInput.Connected("bike", 1, tick(0))) { "ride" }

    @Test fun firstConnectStartsOnceAndDuplicateConnectIsNoOp() {
        val first = connected()
        assertEquals("ride", first.begin?.id)
        assertEquals("bike", first.intent.ride?.vehicleId)
        val duplicate = recordingTransition(first.intent, RecordingInput.Connected("bike", 1, tick(1))) { error("duplicate allocation") }
        assertNull(duplicate.begin)
        assertNull(duplicate.finish)
        assertTrue(duplicate.markers.isEmpty())
    }

    @Test fun lossAndNewGenerationReconnectKeepSameRide() {
        val first = connected()
        val lost = recordingTransition(first.intent, RecordingInput.LinkLost(1, tick(100))) { error("loss allocation") }
        assertEquals("ride", lost.intent.ride?.id)
        assertEquals(listOf(HistoryMarkerKind.BLE_GAP), lost.markers.map { it.kind })
        assertFalse(acceptsRecordingTick(lost.intent, 1, tick(110)))
        val restored = recordingTransition(lost.intent, RecordingInput.Connected("bike", 2, tick(200))) { error("reconnect allocation") }
        assertNull(restored.begin)
        assertNull(restored.finish)
        assertEquals("ride", restored.intent.ride?.id)
        assertTrue(acceptsRecordingTick(restored.intent, 2, tick(210)))
        assertFalse(acceptsRecordingTick(restored.intent, 1, tick(210)))
        val staleLoss = recordingTransition(restored.intent, RecordingInput.LinkLost(1, tick(220))) { error("stale allocation") }
        assertEquals(restored.intent, staleLoss.intent)
        assertTrue(staleLoss.markers.isEmpty())
    }

    @Test fun manualStopSurvivesReconnectAndOnlyExplicitStartRearms() {
        val stopped = recordingTransition(connected().intent, RecordingInput.Stop(tick(100))) { error("stop allocation") }
        assertEquals(HistoryRideStatus.COMPLETED, stopped.finish?.status)
        assertEquals(tick(100), stopped.finish?.ended)
        assertNull(stopped.intent.ride)
        assertTrue(stopped.intent.manuallyStopped)
        val reconnect = recordingTransition(stopped.intent, RecordingInput.Connected("bike", 2, tick(200))) { error("stopped allocation") }
        assertNull(reconnect.begin)
        assertNull(reconnect.intent.ride)
        val started = recordingTransition(reconnect.intent, RecordingInput.Start("bike", 2, tick(300))) { "second" }
        assertEquals("second", started.begin?.id)
        assertFalse(started.intent.manuallyStopped)
        val old = recordingTransition(started.intent, RecordingInput.Connected("bike", 1, tick(350))) { error("old reconnect") }
        assertEquals(started.intent, old.intent)
    }

    @Test fun switchingVehicleEndsPreviousAndRetiresItsGeneration() {
        val next = recordingTransition(connected().intent, RecordingInput.Connected("scooter", 2, tick(100))) { "scooter-ride" }
        assertEquals("ride", next.finish?.id)
        assertEquals(HistoryRideStatus.COMPLETED, next.finish?.status)
        assertEquals("scooter-ride", next.begin?.id)
        assertEquals(listOf(HistoryMarkerKind.CONNECTED), next.markers.map { it.kind })
        assertEquals(listOf(HistoryMarkerKind.VEHICLE_SWITCH), next.finishedMarkers.map { it.kind })
        assertFalse(acceptsRecordingTick(next.intent, 1, tick(150)))
        assertTrue(acceptsRecordingTick(next.intent, 2, tick(150)))
    }

    @Test fun lateOwnedStopAndDisconnectCannotEndAnotherGeneration() {
        val next = recordingTransition(connected().intent, RecordingInput.Connected("scooter", 2, tick(100))) { "new" }
        val stop = recordingTransition(next.intent, RecordingInput.Stop(tick(200), generation = 1)) { error("old stop") }
        assertEquals(next.intent, stop.intent)
        assertNull(stop.finish)
        val disconnect = recordingTransition(next.intent, RecordingInput.ExplicitDisconnect(tick(200), generation = 1)) { error("old disconnect") }
        assertEquals(next.intent, disconnect.intent)
        assertNull(disconnect.finish)
    }

    @Test fun lateStopForPreviousRideCannotEndNewRideOnSameConnection() {
        val stopped = recordingTransition(connected().intent, RecordingInput.Stop(tick(100))) { error("stop allocation") }
        val started = recordingTransition(stopped.intent, RecordingInput.Start("bike", 1, tick(200))) { "second" }
        val retiredStop = recordingTransition(started.intent,
            RecordingInput.Stop(tick(300), generation = 1, rideId = "ride")) { error("retired stop") }
        assertEquals(started.intent, retiredStop.intent)
        assertNull(retiredStop.finish)
        val currentStop = recordingTransition(started.intent,
            RecordingInput.Stop(tick(310), generation = 1, rideId = "second")) { error("current stop") }
        assertEquals("second", currentStop.finish?.id)
    }

    @Test fun explicitDisconnectAllowsNextConnectionAndFinishesOnlyOnce() {
        val ended = recordingTransition(connected().intent, RecordingInput.ExplicitDisconnect(tick(100))) { error("disconnect allocation") }
        assertEquals("ride", ended.finish?.id)
        val staleReconnect = recordingTransition(ended.intent, RecordingInput.Connected("bike", 1, tick(105))) { error("retired connect") }
        assertNull(staleReconnect.begin)
        val duplicate = recordingTransition(ended.intent, RecordingInput.ExplicitDisconnect(tick(110))) { error("duplicate disconnect") }
        assertNull(duplicate.finish)
        val next = recordingTransition(duplicate.intent, RecordingInput.Connected("bike", 2, tick(200))) { "next" }
        assertEquals("next", next.begin?.id)
    }

    @Test fun oldSamplesBeforeStartAndAnotherMonotonicOriginAreRejected() {
        val active = recordingTransition(RecordingIntent(), RecordingInput.Start("bike", 1, tick(100))) { "new" }
        assertFalse(acceptsRecordingTick(active.intent, 1, tick(99)))
        assertTrue(acceptsRecordingTick(active.intent, 1, HistoryTick(1, 110, 1)))
        // A new monotonic origin is interrupted/recovered as a NEW ride, not a
        // source of comparable timestamps inside the retained ride.
        assertFalse(acceptsRecordingTick(active.intent, 1, HistoryTick(1, 0, 2)))
        assertFalse(acceptsRecordingTick(active.intent, 1, HistoryTick(99999, 999, 0)))
    }
}
