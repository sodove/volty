package ru.sodovaya.volty.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSessionLeaseTest {
    private val tag = VoiceSessionTag(VoiceSessionId("s1"), VoiceGeneration(1))
    private fun active() = VoiceSessionState(
        generation = tag.generation, sessionId = tag.sessionId,
        phase = VoicePhase.CONNECTING, wanted = true, leaseDeadlineMillis = 30_000, lastLeaseRequestId = VoiceLeaseRequestId(0),
    )

    @Test
    fun transportDoesNotInventMedia() {
        val result = reduce(active(), VoiceSessionEvent.TransportConnected(tag))
        assertTrue(result.state.transportConnected)
        assertEquals(VoicePhase.CONNECTED, result.state.phase)
        assertFalse(result.state.captureStarted)
        assertFalse(result.state.published)
        assertFalse(result.state.renderStarted)
        assertTrue(result.state.subscribedPeers.isEmpty())
    }

    @Test
    fun aliveTransportCannotOutliveUnconfirmedLease() {
        val initial = active().copy(transportConnected = true)
        val before = reduce(initial, VoiceSessionEvent.Tick(29_999))
        assertTrue(before.state.wanted)
        val expired = reduce(before.state, VoiceSessionEvent.Tick(30_000))
        assertStopped(expired)
        assertTrue(reduce(expired.state, VoiceSessionEvent.Tick(60_000)).effects.isEmpty())
    }

    @Test
    fun lateAckBeforeTickCannotExtendLease() {
        assertStopped(reduce(active(), VoiceSessionEvent.LeaseAck(tag, lease(1, 29_000, 30_001))))
    }

    @Test
    fun ackAfterStopCannotReviveIntent() {
        val stopped = reduce(active(), VoiceSessionEvent.Stop).state
        assertFalse(stopped.wanted)
        assertEquals(VoiceTransition(stopped, emptyList()), reduce(stopped, VoiceSessionEvent.LeaseAck(tag, lease(1, 49_000, 50_000))))
    }

    @Test
    fun matchingAckExtendsDeadlineButRegressionDoesNot() {
        val acked = reduce(active(), VoiceSessionEvent.LeaseAck(tag, lease(2, 20_000, 20_000))).state
        assertEquals(50_000, acked.leaseDeadlineMillis)
        val backwards = reduce(acked, VoiceSessionEvent.LeaseAck(tag, lease(1, 15_000, 21_000)))
        assertEquals(acked, backwards.state)
        assertTrue(backwards.effects.isEmpty())
        assertTrue(reduce(acked, VoiceSessionEvent.Tick(49_999)).state.wanted)
        assertStopped(reduce(acked, VoiceSessionEvent.Tick(50_000)))
    }

    @Test
    fun missingAckEvidenceFailsClosedEvenWithAliveTransport() {
        assertStopped(reduce(active().copy(leaseDeadlineMillis = null, transportConnected = true), VoiceSessionEvent.Tick(0)))
    }

    @Test
    fun clockRegressionDoesNotExpireSession() {
        val initial = active().copy(leaseDeadlineMillis = 35_000)
        assertEquals(VoiceTransition(initial, emptyList()), reduce(initial, VoiceSessionEvent.Tick(1_000)))
    }

    @Test
    fun eitherWrongSessionOrWrongGenerationRejectsAllEvidence() {
        val initial = active()
        val wrongTags = listOf(tag.copy(sessionId = VoiceSessionId("old")), tag.copy(generation = VoiceGeneration(0)))
        wrongTags.forEach { wrong ->
            val events = listOf(
                VoiceSessionEvent.TransportConnected(wrong), VoiceSessionEvent.CaptureStarted(wrong),
                VoiceSessionEvent.Published(wrong), VoiceSessionEvent.Subscribed(wrong, "peer"),
                VoiceSessionEvent.RenderStarted(wrong), VoiceSessionEvent.Reconnecting(wrong),
                VoiceSessionEvent.LeaseAck(wrong, lease(1, 20_000, 20_000)),
                VoiceSessionEvent.CaptureStopped(wrong), VoiceSessionEvent.Unpublished(wrong),
                VoiceSessionEvent.Unsubscribed(wrong, "peer"), VoiceSessionEvent.RenderStopped(wrong),
            )
            events.forEach { event -> assertEquals(VoiceTransition(initial, emptyList()), reduce(initial, event)) }
        }
    }

    @Test
    fun eachMediaEventEarnsOnlyItsOwnEvidence() {
        val captured = reduce(active(), VoiceSessionEvent.CaptureStarted(tag)).state
        assertTrue(captured.captureStarted)
        assertFalse(captured.published)
        assertFalse(captured.renderStarted)
        val published = reduce(captured, VoiceSessionEvent.Published(tag)).state
        assertTrue(published.published)
        assertTrue(published.subscribedPeers.isEmpty())
        val subscribed = reduce(published, VoiceSessionEvent.Subscribed(tag, "peer")).state
        assertEquals(setOf("peer"), subscribed.subscribedPeers)
        assertFalse(subscribed.renderStarted)
        assertTrue(reduce(subscribed, VoiceSessionEvent.RenderStarted(tag)).state.renderStarted)
    }

    @Test
    fun reconnectClearsEvidenceWithoutCreatingParallelEngine() {
        val initial = active().copy(transportConnected = true, captureStarted = true, published = true,
            subscribedPeers = setOf("peer"), renderStarted = true, muted = true, deafened = true)
        val reconnecting = reduce(initial, VoiceSessionEvent.Reconnecting(tag))
        assertEquals(VoicePhase.RECONNECTING, reconnecting.state.phase)
        assertFalse(reconnecting.state.transportConnected)
        assertFalse(reconnecting.state.captureStarted)
        assertFalse(reconnecting.state.published)
        assertFalse(reconnecting.state.renderStarted)
        assertTrue(reconnecting.state.subscribedPeers.isEmpty())
        assertTrue(reconnecting.state.muted)
        assertTrue(reconnecting.state.deafened)
        assertTrue(reconnecting.effects.isEmpty())
        val reconnected = reduce(reconnecting.state, VoiceSessionEvent.TransportConnected(tag))
        assertEquals(listOf(VoiceSessionEffect.SetMuted(tag, true), VoiceSessionEffect.SetDeafened(tag, true)), reconnected.effects)
        assertFalse(reconnected.state.published)
    }

    @Test
    fun stopClearsReadinessButStillAwaitsEngineRelease() {
        val stopped = reduce(active().copy(captureStarted = true, published = true, renderStarted = true,
            subscribedPeers = setOf("peer")), VoiceSessionEvent.Stop)
        assertStopped(stopped)
        assertTrue(stopped.state.cleanupPending)
        assertFalse(stopped.state.captureStarted)
        assertFalse(stopped.state.published)
        assertFalse(stopped.state.renderStarted)
        assertTrue(stopped.state.subscribedPeers.isEmpty())
        assertEquals(VoiceTransition(stopped.state, emptyList()), reduce(stopped.state, VoiceSessionEvent.Published(tag)))
    }

    @Test
    fun muteAndDeafenCommandsAreTaggedAndIdempotent() {
        val muted = reduce(active(), VoiceSessionEvent.SetMuted(true))
        assertTrue(muted.state.muted)
        assertEquals(listOf(VoiceSessionEffect.SetMuted(tag, true)), muted.effects)
        assertTrue(reduce(muted.state, VoiceSessionEvent.SetMuted(true)).effects.isEmpty())
        val deafened = reduce(muted.state, VoiceSessionEvent.SetDeafened(true))
        assertTrue(deafened.state.deafened)
        assertEquals(listOf(VoiceSessionEffect.SetDeafened(tag, true)), deafened.effects)
        assertTrue(reduce(deafened.state, VoiceSessionEvent.SetDeafened(true)).effects.isEmpty())
        assertFalse(deafened.state.captureStarted)
        assertFalse(deafened.state.renderStarted)
    }

    @Test
    fun preferencesBeforeJoinTravelInStartEffectWithoutHotMicDefault() {
        val muted = reduce(VoiceSessionState(), VoiceSessionEvent.SetMuted(true))
        assertTrue(muted.effects.isEmpty())
        val desired = reduce(muted.state, VoiceSessionEvent.SetDeafened(true)).state
        val op = VoiceOperationId("new")
        val joining = reduce(desired, VoiceSessionEvent.Join(op)).state
        val granted = reduce(joining, VoiceSessionEvent.SessionGranted(op, joining.generation, tag.sessionId, lease(0, 10_000, 10_000)))
        assertEquals(40_000, granted.state.leaseDeadlineMillis)
        assertEquals(listOf(VoiceSessionEffect.StartEngine(VoiceSessionTag(tag.sessionId, joining.generation),
            muted = true, deafened = true)), granted.effects)
    }

    private fun assertStopped(result: VoiceTransition) {
        assertFalse(result.state.wanted)
        assertEquals(listOf(
            VoiceSessionEffect.StopEngine(tag.generation),
            VoiceSessionEffect.StopSession(tag.sessionId),
            VoiceSessionEffect.RemoveNearbyDemand,
            VoiceSessionEffect.ClearSharingQueue,
        ), result.effects)
    }

    @Test
    fun mediaStopEventsWithdrawOnlyTheirOwnEvidence() {
        val initial = active().copy(captureStarted = true, published = true, renderStarted = true)
        val captureStopped = reduce(initial, VoiceSessionEvent.CaptureStopped(tag)).state
        assertFalse(captureStopped.captureStarted)
        assertTrue(captureStopped.published)
        assertTrue(captureStopped.renderStarted)
        val unpublished = reduce(captureStopped, VoiceSessionEvent.Unpublished(tag)).state
        assertFalse(unpublished.published)
        assertTrue(unpublished.renderStarted)
        val renderStopped = reduce(unpublished, VoiceSessionEvent.RenderStopped(tag))
        assertFalse(renderStopped.state.renderStarted)
        assertTrue(renderStopped.state.wanted)
        assertTrue(renderStopped.effects.isEmpty())
    }

    @Test
    fun unsubscribeWithdrawsOnlyDepartedPeerEvidence() {
        val initial = active().copy(subscribedPeers = setOf("gone", "remaining"), renderStarted = true)
        val result = reduce(initial, VoiceSessionEvent.Unsubscribed(tag, "gone"))
        assertEquals(setOf("remaining"), result.state.subscribedPeers)
        assertTrue(result.state.renderStarted)
        assertTrue(result.effects.isEmpty())
    }

    private fun lease(id: Long, start: Long, received: Long) = VoiceLeaseGrant(VoiceLeaseRequestId(id), start, received, 30_000)

    private fun reduce(state: VoiceSessionState, event: VoiceSessionEvent) = VoiceSessionReducer.reduce(state, event)
}
