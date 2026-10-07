package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRosterRuntimeTest {
    private val tag = VoiceRosterTag("channel", 3)
    private fun port(block: suspend (VoiceRosterTag) -> VoiceRosterSnapshotReply) = object : VoiceRosterSnapshotPort {
        override suspend fun snapshot(tag: VoiceRosterTag) = block(tag)
    }
    private fun sample(now: Long, ordinal: Long = 0): VoiceRosterSnapshotReply {
        val peer = VoiceRosterPeer("actor", VoiceSessionId("session"), VoiceOperationId("op"),
            VoiceRosterPhase.CONNECTED, 1_000, "sid", true)
        return VoiceRosterSnapshotReply.Received(VoiceRosterObservation(VoiceRosterUpdate(tag, 1, listOf(peer)),
            VoiceLeaseRequestId(ordinal), now, now, 500, mapOf(peer.sessionId to now + 500)))
    }

    @Test fun initialReadUsesExactSubscriptionTagAndPublishesAuthoritativeRows() = runTest {
        val issued = mutableListOf<VoiceRosterTag>()
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag,
            port { issued += it; sample(100) }, pulses.receiveAsFlow(), {100})
        assertNull(runtime.state.value.connectedCount)
        runCurrent()
        assertEquals(listOf(tag), issued)
        assertEquals(1, runtime.state.value.connectedCount)
        runtime.close()
        assertTrue(runtime.state.value.retired)
        assertNull(runtime.state.value.connectedCount)
        pulses.close()
    }

    @Test fun finitePulsesExpireLeaseAndIssuePollOnlyAtCadence() = runTest {
        var now = 100L
        var issued = 0
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag,
            port { sample(now, issued++.toLong()) }, pulses.receiveAsFlow(), {now})
        runCurrent()
        now = 600; pulses.send(Unit); runCurrent()
        assertEquals(0, runtime.state.value.connectedCount)
        assertEquals(1, issued)
        now = 5_099; pulses.send(Unit); runCurrent()
        assertEquals(1, issued)
        now = 5_100; pulses.send(Unit); runCurrent()
        assertEquals(2, issued)
        assertEquals(1, runtime.state.value.connectedCount)
        runtime.close(); pulses.close()
    }

    @Test fun activeReadCoalescesAndFiniteTimeoutCancelsActualRequestBeforeNextPoll() = runTest {
        var now = 100L
        var issued = 0
        var cancelled = false
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag, port {
            issued++
            if (issued == 1) try { awaitCancellation() } finally { cancelled = true }
            else sample(now, 1)
        }, pulses.receiveAsFlow(), {now})
        runCurrent()
        assertFalse(runtime.refresh())
        now = 5_100; pulses.send(Unit); runCurrent()
        assertEquals(1, issued)
        assertFalse(cancelled)
        now = 10_101; advanceTimeBy(10_001); runCurrent()
        assertTrue(cancelled)
        assertEquals(VoiceRosterUnknown.UNAVAILABLE, runtime.state.value.unknown)
        pulses.send(Unit); runCurrent()
        assertEquals(2, issued)
        assertEquals(1, runtime.state.value.connectedCount)
        runtime.close(); pulses.close()
    }

    @Test fun denialRetiresAndNeitherPulseNorManualRefreshIssuesAnotherRead() = runTest {
        var issued = 0
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag,
            port { issued++; VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.ACCESS_DENIED) },
            pulses.receiveAsFlow(), {100})
        runCurrent()
        assertEquals(VoiceRosterUnknown.ACCESS_DENIED, runtime.state.value.unknown)
        assertTrue(runtime.state.value.retired)
        pulses.send(Unit); runCurrent()
        assertFalse(runtime.refresh())
        assertEquals(1, issued)
        runtime.close(); pulses.close()
    }

    @Test fun clockRollbackRetiresAndCancelsActualInFlightRead() = runTest {
        var now = 100L
        var issued = 0
        var cancelled = false
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag, port {
            issued++
            try { awaitCancellation() } finally { cancelled = true }
        }, pulses.receiveAsFlow(), {now})
        runCurrent()
        now = 99; pulses.send(Unit); runCurrent()
        assertEquals(VoiceRosterUnknown.CLOCK_RESET, runtime.state.value.unknown)
        assertTrue(cancelled)
        assertFalse(runtime.refresh())
        assertEquals(1, issued)
        runtime.close(); pulses.close()
    }

    @Test fun parentCancellationClearsPreviouslyPublishedRowsAndStopsScheduling() = runTest {
        val parentJob = Job()
        val parent = CoroutineScope(coroutineContext + parentJob)
        var issued = 0
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        var cancelled = false
        val runtime = VoiceRosterRuntime(parent, StandardTestDispatcher(testScheduler), tag,
            port {
                if (++issued == 1) (sample(100) as VoiceRosterSnapshotReply.Received).let {
                    it.copy(observation=it.observation.copy(profileNames=mapOf("actor" to "Rider")))
                }
                else try { awaitCancellation() } finally { cancelled = true }
            }, pulses.receiveAsFlow(), {100})
        try {
            runCurrent()
            assertEquals(1, runtime.state.value.connectedCount)
            assertEquals(mapOf("actor" to "Rider"),runtime.state.value.profileNames)
            assertTrue(runtime.refresh()); runCurrent()
            assertEquals(2, issued)
            assertFalse(cancelled)
            parentJob.cancel(); runCurrent()
            assertTrue(cancelled)
            assertTrue(runtime.state.value.retired)
            assertNull(runtime.state.value.connectedCount)
            assertTrue(runtime.state.value.profileNames.isEmpty())
            pulses.send(Unit); runCurrent()
            assertFalse(runtime.refresh())
            assertEquals(2, issued)
        } finally {parentJob.cancel();pulses.close();runtime.close()}
    }

    @Test fun rejectedImmediateDispatcherDoesNotAttachAnOrphanChildToParent() = runTest {
        val parentJob = Job()
        try {
            val parent = CoroutineScope(coroutineContext + parentJob)
            assertFailsWith<IllegalArgumentException> {
                VoiceRosterRuntime(parent, UnconfinedTestDispatcher(testScheduler), tag,
                    port { error("No request allowed") }, emptyFlow(), {100})
            }
            assertTrue(parentJob.children.none(), "Rejected runtime must not leave a live child")
        } finally { parentJob.cancel() }
    }

    @Test fun cancelledParentClearsRowsBeforeUncooperativeReadFinishesAndLateReplyCannotRestoreThem() = runTest {
        val parentJob = Job()
        val release = CompletableDeferred<Unit>()
        var issued = 0
        var completed = false
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(CoroutineScope(coroutineContext + parentJob),
            StandardTestDispatcher(testScheduler), tag, port {
                if (++issued == 1) sample(100)
                else withContext(NonCancellable) {
                    release.await(); completed = true; sample(100, 1)
                }
            }, pulses.receiveAsFlow(), {100})
        runCurrent()
        assertTrue(runtime.refresh()); runCurrent()
        assertEquals(2, issued)
        parentJob.cancel(); runCurrent()
        assertFalse(completed)
        assertNull(runtime.state.value.connectedCount)
        assertTrue(runtime.state.value.retired)
        release.complete(Unit); runCurrent()
        assertTrue(completed)
        assertNull(runtime.state.value.connectedCount)
        pulses.close()
    }

    @Test fun independentlyCancelledPulseSourceCannotLeaveFreshRowsFrozen() = runTest {
        val trigger = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag,
            port { sample(100) }, flow { trigger.receive(); throw CancellationException("pulse stopped") }, {100})
        runCurrent()
        assertEquals(1, runtime.state.value.connectedCount)
        trigger.send(Unit); runCurrent()
        try {
            assertTrue(runtime.state.value.retired)
            assertNull(runtime.state.value.connectedCount)
            assertFalse(runtime.refresh())
        } finally { runtime.close(); trigger.close() }
    }

    @Test fun independentlyCancelledClockRetiresInsteadOfAbandoningItsScheduler() = runTest {
        var broken = false
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag,
            port { sample(100) }, pulses.receiveAsFlow(), {
                if (broken) throw CancellationException("clock failed") else 100
            })
        runCurrent()
        broken = true; pulses.send(Unit); runCurrent()
        try {
            assertTrue(runtime.state.value.retired)
            assertEquals(VoiceRosterUnknown.CLOCK_RESET, runtime.state.value.unknown)
            assertNull(runtime.state.value.connectedCount)
        } finally { runtime.close(); pulses.close() }
    }

    @Test fun exceptionHasSafeUnknownStateAndExplicitRefreshRecoversUntilClose() = runTest {
        var issued = 0
        var cancelled = false
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRosterRuntime(this, StandardTestDispatcher(testScheduler), tag, port {
            when (++issued) {
                1 -> error("private-token")
                2 -> sample(100, 1)
                else -> try { awaitCancellation() } finally { cancelled = true }
            }
        }, pulses.receiveAsFlow(), {100})
        runCurrent()
        assertEquals(VoiceRosterUnknown.UNAVAILABLE, runtime.state.value.unknown)
        assertFalse(runtime.state.value.toString().contains("private-token"))
        assertTrue(runtime.refresh()); runCurrent()
        assertEquals(1, runtime.state.value.connectedCount)
        assertTrue(runtime.refresh()); runCurrent()
        runtime.close(); runCurrent()
        assertTrue(cancelled)
        assertNull(runtime.state.value.connectedCount)
        assertFalse(runtime.refresh())
        assertEquals(3, issued)
        pulses.close()
    }
}
