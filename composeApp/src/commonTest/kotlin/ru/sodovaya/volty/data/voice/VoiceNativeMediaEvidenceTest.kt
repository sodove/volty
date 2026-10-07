package ru.sodovaya.volty.data.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.voice.*

class VoiceNativeMediaEvidenceTest {
    private val tag = VoiceSessionTag(VoiceSessionId("owned"), VoiceGeneration(4))
    private fun snapshot(capture: Long = 100, render: Long = 200,
                         captureRunning: Boolean? = true, renderRunning: Boolean? = true,
                         generation: VoiceGeneration = tag.generation) = VoiceDiagnosticSnapshot(
        generation, captureBytes = capture, renderBytes = render,
        captureRunning = captureRunning, renderRunning = renderRunning,
    )
    private fun track(peer: String = "peer", audio: Boolean = true, subscribed: Boolean = true,
                      hasTrack: Boolean = true) = VoiceRemoteAudioObservation(peer, audio, subscribed, hasTrack)

    @Test fun retainedNativeStreamsNeedNewBytesAfterEachReconnectAndReportIndependently() {
        val evidence = VoiceNativeAudioEvidence(tag)
        evidence.reconnecting(snapshot())
        assertTrue(evidence.observe(snapshot()).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.CaptureStarted(tag)), evidence.observe(snapshot(capture = 101)))
        assertEquals(listOf(VoiceSessionEvent.RenderStarted(tag)), evidence.observe(snapshot(capture = 101, render = 201)))
        assertTrue(evidence.observe(snapshot(capture = 102, render = 202)).isEmpty())
        evidence.reconnecting(snapshot(capture = 102, render = 202))
        assertTrue(evidence.observe(snapshot(capture = 102, render = 202)).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.CaptureStarted(tag), VoiceSessionEvent.RenderStarted(tag)),
            evidence.observe(snapshot(capture = 103, render = 203)))
    }

    @Test fun freshBytesWithUnknownOrFalseRunningCannotLaterBeReusedAsProof() {
        val evidence = VoiceNativeAudioEvidence(tag)
        evidence.reconnecting(snapshot())
        assertTrue(evidence.observe(snapshot(capture = 101, render = 201, captureRunning = false, renderRunning = null)).isEmpty())
        assertTrue(evidence.observe(snapshot(capture = 101, render = 201)).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.CaptureStarted(tag), VoiceSessionEvent.RenderStarted(tag)),
            evidence.observe(snapshot(capture = 102, render = 202)))
    }

    @Test fun nativeStopSuppressesOldProgressAndLaterDirectStartNeedsNoInventedBytes() {
        val evidence = VoiceNativeAudioEvidence(tag)
        assertTrue(evidence.captureState(true, snapshot()))
        assertTrue(evidence.renderState(true, snapshot()))
        assertTrue(evidence.observe(snapshot()).isEmpty())
        assertTrue(evidence.captureState(false, snapshot(capture = 110)))
        assertTrue(evidence.renderState(false, snapshot(render = 210)))
        assertTrue(evidence.observe(snapshot(capture = 110, render = 210)).isEmpty())
        assertTrue(evidence.captureState(true, snapshot(capture = 110, render = 210)))
        assertTrue(evidence.observe(snapshot(capture = 110, render = 210)).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.RenderStarted(tag)), evidence.observe(snapshot(capture = 110, render = 211)))
    }

    @Test fun stoppedSnapshotCanRetirePreviouslyReportedStreamsWithoutNewPackets() {
        val evidence = VoiceNativeAudioEvidence(tag)
        evidence.captureState(true, snapshot()); evidence.renderState(true, snapshot())
        assertEquals(listOf(VoiceSessionEvent.CaptureStopped(tag), VoiceSessionEvent.RenderStopped(tag)),
            evidence.observe(snapshot(captureRunning = false, renderRunning = false)))
        assertTrue(evidence.observe(snapshot(captureRunning = false, renderRunning = false)).isEmpty())
    }

    @Test fun foreignGenerationCannotClaimConsumeOrResetOwnedEvidence() {
        val evidence = VoiceNativeAudioEvidence(tag)
        evidence.reconnecting(snapshot())
        val foreign = snapshot(capture = 10_000, render = 20_000, generation = VoiceGeneration(3))
        assertFalse(evidence.captureState(true, foreign)); assertFalse(evidence.renderState(true, foreign))
        evidence.reconnecting(foreign)
        assertTrue(evidence.observe(foreign).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.CaptureStarted(tag), VoiceSessionEvent.RenderStarted(tag)),
            evidence.observe(snapshot(capture = 101, render = 201)))
    }

    @Test fun decreasingOrNegativeCountersCannotBeRebasedIntoFreshProgress() {
        val evidence = VoiceNativeAudioEvidence(tag)
        evidence.reconnecting(snapshot())
        assertTrue(evidence.observe(snapshot(capture = -1, render = 0)).isEmpty())
        assertTrue(evidence.observe(snapshot(capture = 0, render = 1)).isEmpty())
        assertTrue(evidence.observe(snapshot()).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.CaptureStarted(tag), VoiceSessionEvent.RenderStarted(tag)),
            evidence.observe(snapshot(capture = 101, render = 201)))
    }

    @Test fun retiredAudioOwnerCannotEmitOrAcceptLateCallbacks() {
        val evidence = VoiceNativeAudioEvidence(tag)
        evidence.reconnecting(snapshot()); evidence.close()
        assertFalse(evidence.captureState(true, snapshot())); assertFalse(evidence.renderState(true, snapshot()))
        evidence.reconnecting(snapshot(capture = 0, render = 0))
        assertTrue(evidence.observe(snapshot(capture = 101, render = 201)).isEmpty())
    }

    @Test fun retainedPeerSubscriptionsAreReacknowledgedAfterReconnectFromCurrentSnapshot() {
        val evidence = VoiceRemoteAudioEvidence(tag)
        val current = listOf(track("second"), track("first"))
        assertEquals(listOf(VoiceSessionEvent.Subscribed(tag, "first"), VoiceSessionEvent.Subscribed(tag, "second")), evidence.reconcile(current))
        assertTrue(evidence.reconcile(current).isEmpty())
        evidence.reconnecting()
        assertEquals(listOf(VoiceSessionEvent.Subscribed(tag, "first"), VoiceSessionEvent.Subscribed(tag, "second")), evidence.reconcile(current))
    }

    @Test fun peerRemainsSubscribedWhileAnyCurrentAudioTrackIsComplete() {
        val evidence = VoiceRemoteAudioEvidence(tag)
        assertEquals(listOf(VoiceSessionEvent.Subscribed(tag, "peer")), evidence.reconcile(listOf(track(), track())))
        assertTrue(evidence.reconcile(listOf(track())).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.Unsubscribed(tag, "peer")), evidence.reconcile(emptyList()))
        assertTrue(evidence.reconcile(emptyList()).isEmpty())
    }

    @Test fun obsoleteCallbackCannotRemoveOrAddPeersOutsideCurrentSdkSnapshot() {
        val evidence = VoiceRemoteAudioEvidence(tag)
        assertEquals(listOf(VoiceSessionEvent.Subscribed(tag, "current")), evidence.reconcile(listOf(track("current"))))
        // Both delayed subscribe and delayed unsubscribe handlers read this same current map.
        assertTrue(evidence.reconcile(listOf(track("current"))).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.Unsubscribed(tag, "current"), VoiceSessionEvent.Subscribed(tag, "replacement")),
            evidence.reconcile(listOf(track("replacement"))))
    }

    @Test fun incompleteDeniedOrNonAudioTracksNeverContributeAndCanRetirePeer() {
        val evidence = VoiceRemoteAudioEvidence(tag)
        val incoherent = listOf(track(audio = false), track(subscribed = false), track(hasTrack = false), track(peer = ""))
        assertTrue(evidence.reconcile(incoherent).isEmpty())
        assertEquals(listOf(VoiceSessionEvent.Subscribed(tag, "peer")), evidence.reconcile(listOf(track())))
        assertEquals(listOf(VoiceSessionEvent.Unsubscribed(tag, "peer")), evidence.reconcile(incoherent))
    }

    @Test fun retiredRemoteOwnerRejectsLateSnapshotsAndReconnectReset() {
        val evidence = VoiceRemoteAudioEvidence(tag)
        assertEquals(listOf(VoiceSessionEvent.Subscribed(tag, "peer")), evidence.reconcile(listOf(track())))
        evidence.close(); evidence.reconnecting()
        assertTrue(evidence.reconcile(listOf(track("late"))).isEmpty())
        assertTrue(evidence.reconcile(emptyList()).isEmpty())
    }

    @Test fun recoveredObservationsRepairReducerFlagsWithoutTransportInventingMediaOrChangingIntent() {
        val audio = VoiceNativeAudioEvidence(tag)
        val remote = VoiceRemoteAudioEvidence(tag)
        var state = VoiceSessionState(generation = tag.generation, sessionId = tag.sessionId,
            wanted = true, phase = VoicePhase.CONNECTED, leaseDeadlineMillis = 30_000,
            muted = true, deafened = true, sharingEnabled = true,
            captureStarted = true, renderStarted = true, published = true, subscribedPeers = setOf("peer"))
        audio.reconnecting(snapshot()); remote.reconnecting()
        state = VoiceSessionReducer.reduce(state, VoiceSessionEvent.Reconnecting(tag)).state
        assertFalse(state.captureStarted); assertFalse(state.renderStarted); assertTrue(state.subscribedPeers.isEmpty())
        state = VoiceSessionReducer.reduce(state, VoiceSessionEvent.TransportConnected(tag)).state
        assertFalse(state.captureStarted); assertFalse(state.renderStarted); assertFalse(state.published)
        for (event in audio.observe(snapshot(capture = 101, render = 201)) + remote.reconcile(listOf(track()))) {
            val transition = VoiceSessionReducer.reduce(state, event)
            assertTrue(transition.effects.isEmpty())
            state = transition.state
        }
        assertTrue(state.captureStarted); assertTrue(state.renderStarted); assertEquals(setOf("peer"), state.subscribedPeers)
        assertFalse(state.published)
        assertTrue(state.muted); assertTrue(state.deafened); assertTrue(state.sharingEnabled)
        assertEquals(30_000L, state.leaseDeadlineMillis)
    }

    @Test fun observationsCannotReviveStoppedOrReplacementReducerOwner() {
        for (current in listOf(VoiceSessionState(), VoiceSessionState(
            wanted = true, generation = VoiceGeneration(5), sessionId = VoiceSessionId("replacement")))) {
            val audio = VoiceNativeAudioEvidence(tag)
            val remote = VoiceRemoteAudioEvidence(tag)
            audio.reconnecting(snapshot())
            val events = audio.observe(snapshot(capture = 101, render = 201)) + remote.reconcile(listOf(track()))
            assertEquals(3, events.size)
            for (event in events) assertEquals(VoiceTransition(current, emptyList()), VoiceSessionReducer.reduce(current, event))
        }
    }
}
