package ru.sodovaya.volty.backend.nearby

import kotlin.test.*

class VoiceDestinationKindTest {
    private val policy = SessionOperationPolicy()

    @Test fun sameUuidCannotReplayOperationAsAnotherKind() {
        val original = policy.begin(null, "actor", "op", "same-uuid", "session", 100,
            VoiceDestinationKind.DIRECT_CALL).operation!!
        val replay = policy.begin(original, "actor", "op", "same-uuid", "new-session", 101)
        assertEquals(OperationRejection.DESTINATION_MISMATCH, replay.rejection)
        assertEquals(original, replay.operation)
    }

    @Test fun cancellationRetainsCallKindAndOriginalCleanupIdentity() {
        val original = policy.begin(null, "actor", "op", "same-uuid", "session", 100,
            VoiceDestinationKind.DIRECT_CALL).operation!!
        val cancelled = policy.cancel(original, "actor", "op", 101).operation!!
        assertEquals(VoiceDestinationKind.DIRECT_CALL, cancelled.destinationKind)
        assertEquals("session", cancelled.sessionId)
        assertEquals(OperationPhase.CANCELLED, cancelled.phase)
        assertNull(policy.cancel(null, "actor", "absent", 102).operation!!.destinationKind)
    }

    @Test fun concreteDestinationCannotCarryMissingKindAndTombstoneCannotCarryKind() {
        val channel = policy.begin(null, "actor", "op", "channel", "session", 100).operation!!
        assertEquals(VoiceDestinationKind.CHANNEL, channel.destinationKind)
        assertFailsWith<IllegalArgumentException> {channel.copy(destinationKind = null)}
        val absent = policy.cancel(null, "actor", "absent", 100).operation!!
        assertFailsWith<IllegalArgumentException> {absent.copy(destinationKind = VoiceDestinationKind.CHANNEL)}
    }
}
