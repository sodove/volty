package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.Tag
import java.net.URI
import java.util.UUID
import kotlin.test.*

@Tag("sfu")
class HttpSfuLeaseRuntimeTest {
    private val client = HttpSfuLeaseClient(
        URI(checkNotNull(System.getenv("VOLTY_SFU_TEST_URL")) { "Disposable SFU origin required" }),
        "nearby-probe", "local-nearby-probe-secret-not-production-20261003",
    )
    private val room = "kotlin-control-test-" + UUID.randomUUID()
    private val session = UUID.randomUUID().toString()

    @Test fun realNodeRegistersRenewsAndRejectsTerminalSession() {
        val status = ready()
        try {
            assertIs<SfuReply.Accepted<LeaseReceipt>>(client.register(room, session, status.nodeEpoch))
            val observation = (client.observeParticipant(room, session, status.nodeEpoch) as SfuReply.Accepted).value
            assertNull(observation.participantSid, "Registered lease must not invent media")
            assertNull(observation.state)
            assertIs<SfuReply.Accepted<LeaseReceipt>>(client.renew(room, session, status.nodeEpoch))
            assertIs<SfuReply.Accepted<Unit>>(client.revoke(room, session, status.nodeEpoch))
            assertEquals(SfuFailure.REJECTED, (client.observeParticipant(room, session, status.nodeEpoch) as SfuReply.Rejected).reason)
            assertEquals(SfuFailure.REJECTED, (client.register(room, session, status.nodeEpoch) as SfuReply.Rejected).reason)
            assertEquals(SfuFailure.REJECTED, (client.renew(room, session, status.nodeEpoch) as SfuReply.Rejected).reason)
            assertIs<SfuReply.Accepted<Unit>>(client.revoke(room, session, status.nodeEpoch))
        } finally { client.revoke(room, session, status.nodeEpoch) }
    }

    @Test fun realNodeRejectsAllCommandsWithDifferentIncarnation() {
        val status = ready()
        val stale = if (status.nodeEpoch == "a".repeat(32)) "b".repeat(32) else "a".repeat(32)
        assertEquals(SfuFailure.REJECTED, (client.register(room, session, stale) as SfuReply.Rejected).reason)
        assertEquals(SfuFailure.REJECTED, (client.renew(room, session, stale) as SfuReply.Rejected).reason)
        assertEquals(SfuFailure.REJECTED, (client.revoke(room, session, stale) as SfuReply.Rejected).reason)
        assertEquals(SfuFailure.REJECTED, (client.observeParticipant(room, session, stale) as SfuReply.Rejected).reason)
        // Rejected old-node revoke must not create a current-node terminal tombstone.
        try {
            assertIs<SfuReply.Accepted<LeaseReceipt>>(client.register(room, session, status.nodeEpoch))
        } finally { client.revoke(room, session, status.nodeEpoch) }
    }

    private fun ready(): SfuNodeStatus {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            val response = client.inspect(room)
            if (response is SfuReply.Accepted && response.value.ready) {
                assertTrue(response.value.canRegisterNew)
                return response.value
            }
            Thread.sleep(50)
        }
        error("Disposable SFU readiness not confirmed")
    }
}
