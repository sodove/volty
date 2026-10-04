package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.nearby.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class PreparedCallSessionIdentityTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val operation = VoiceOperationId("40000000-0000-0000-0000-000000000004")
    private val session = VoiceSessionId("50000000-0000-0000-0000-000000000005")
    private val generation = VoiceGeneration(5)
    private val base = "https://api.test/v2/nearby"
    private fun grant() = """{"call":{"callId":"$call","callerId":"$actor","calleeId":"$peer","phase":"ACCEPTED","revision":1,"ringDeadlineEpochMillis":45100},"session":{"phase":"CONNECTING","sessionId":"${session.value}","remainingLeaseMillis":5000,"credentials":{"serverUrl":"wss://media.test","participantToken":"private-token","roomEpoch":"volty-call-$call"}}}"""

    @Test fun actualPreparedCallGrantEarnsOnlyOriginalJournalHeartbeatAndCleanupWithoutChannelJoin() = runTest {
        val root = Job(); val job = Job(root); val dispatcher = StandardTestDispatcher(testScheduler)
        val account = SocialAuthenticatedAccount(SocialUserId(actor), job, root + dispatcher) {"original-account-token"}
        var clock = 100L; val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            this.dispatcher = dispatcher
            addHandler {
                assertEquals("Bearer original-account-token", it.headers[HttpHeaders.Authorization])
                issued += it.url.encodedPath
                respond(when {
                    it.url.encodedPath.contains("/calls/") -> grant()
                    it.url.encodedPath.endsWith("heartbeat") -> """{"phase":"CONNECTED","sessionId":"${session.value}","remainingLeaseMillis":7000}"""
                    else -> """{"phase":"CANCELLED","operationId":"${operation.value}","sessionId":"${session.value}","cleanupPending":false}"""
                })
            }
        }}
        val sequence = VoiceLeaseRequestSequence()
        val journal = VoiceSessionAccountControl.create(account, http, base, {clock}, sequence)
        val calls = DirectCallHttpPort(actor, http, base, {"original-account-token"}, {clock}, sequence)
        try {
            val grant = assertIs<VoiceHttpReply.Credentials>(assertIs<DirectCallReply.Ready>(calls.resume(call, operation, generation)).session)
            assertEquals(VoiceHttpFailure.ACCESS_DENIED, assertIs<VoiceHttpReply.Failed>(journal.heartbeat(grant.tag)).reason)
            assertFalse(journal.rememberPreparedCallSession(call, operation, generation, grant))
            assertTrue(journal.rememberPreparedCallIntent(call, operation, generation))
            assertFalse(journal.rememberPreparedCallSession(peer, operation, generation, grant))
            assertFalse(journal.rememberPreparedCallSession(call, VoiceOperationId(peer), generation, grant))
            assertFalse(journal.rememberPreparedCallSession(call, operation, VoiceGeneration(6), grant))
            assertFalse(journal.rememberPreparedCallSession(call, operation, generation,
                grant.copy(tag = grant.tag.copy(generation = VoiceGeneration(6)))))
            assertEquals(1, issued.size)
            assertTrue(journal.rememberPreparedCallSession(call, operation, generation, grant))
            clock = 200
            val ack = assertIs<VoiceHttpReply.Acknowledged>(journal.heartbeat(grant.tag))
            assertTrue(ack.lease.requestId.value > grant.lease.requestId.value)
            job.cancel(); journal.retireAdmission()
            assertEquals(false, assertIs<VoiceHttpReply.Ended>(journal.disconnect(grant.tag)).cleanupPending)
            assertEquals(listOf("/v2/nearby/calls/$call/resume", "/v2/nearby/sessions/${session.value}/heartbeat",
                "/v2/nearby/sessions/${session.value}/disconnect"), issued)
        } finally {journal.close(); calls.close(); http.close(); root.cancel()}
    }

    @Test fun expiredActualCallIdentityRemainsStoppableAfterRetirementButNeverAfterClose() = runTest {
        val root = Job(); val job = Job(root); val dispatcher = StandardTestDispatcher(testScheduler)
        val account = SocialAuthenticatedAccount(SocialUserId(actor), job, root + dispatcher) {"original-account-token"}
        var clock = 100L; val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            this.dispatcher = dispatcher
            addHandler {
                issued += it.url.encodedPath
                if (it.url.encodedPath.contains("/calls/")) {clock = 7000; respond(grant())}
                else respond("""{"phase":"CANCELLED","sessionId":"${session.value}","cleanupPending":false}""")
            }
        }}
        val sequence = VoiceLeaseRequestSequence()
        val journal = VoiceSessionAccountControl.create(account, http, base, {clock}, sequence)
        val calls = DirectCallHttpPort(actor, http, base, {"original-account-token"}, {clock}, sequence)
        try {
            assertTrue(journal.rememberPreparedCallIntent(call, operation, generation))
            val expired = assertIs<VoiceHttpReply.Expired>(assertIs<DirectCallReply.Ready>(calls.resume(call, operation, generation)).session)
            job.cancel(); journal.retireAdmission()
            assertTrue(journal.rememberPreparedCallSession(call, operation, generation, expired))
            assertEquals(false, assertIs<VoiceHttpReply.Ended>(journal.disconnect(expired.tag)).cleanupPending)
            journal.close()
            assertFalse(journal.rememberPreparedCallSession(call, operation, generation, expired))
            assertEquals(listOf("/v2/nearby/calls/$call/resume", "/v2/nearby/sessions/${session.value}/disconnect"), issued)
        } finally {journal.close(); calls.close(); http.close(); root.cancel()}
    }

    @Test fun identicalUuidAndOperationCannotReinterpretChannelIntentAsCall() = runTest {
        val root = Job(); val job = Job(root); val dispatcher = StandardTestDispatcher(testScheduler)
        val account = SocialAuthenticatedAccount(SocialUserId(actor), job, root + dispatcher) {"original-account-token"}
        val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            this.dispatcher = dispatcher
            addHandler {issued += it.url.encodedPath; respond(grant())}
        }}
        val sequence = VoiceLeaseRequestSequence()
        val channelJournal = VoiceSessionAccountControl.create(account, http, base, {100}, sequence)
        val callJournal = VoiceSessionAccountControl.create(account, http, base, {100}, sequence)
        val calls = DirectCallHttpPort(actor, http, base, {"original-account-token"}, {100}, sequence)
        try {
            val grant = assertIs<VoiceHttpReply.Credentials>(assertIs<DirectCallReply.Ready>(calls.resume(call, operation, generation)).session)
            channelJournal.rememberJoinIntent(operation, generation, call, false)
            assertFalse(channelJournal.rememberPreparedCallSession(call, operation, generation, grant))
            assertFalse(channelJournal.rememberPreparedCallIntent(call, operation, generation))
            assertTrue(callJournal.rememberPreparedCallIntent(call, operation, generation))
            assertEquals(VoiceHttpFailure.ACCESS_DENIED, assertIs<VoiceHttpReply.Failed>(callJournal.join(operation, generation, call)).reason)
            assertTrue(callJournal.rememberPreparedCallSession(call, operation, generation, grant))
            assertEquals(listOf("/v2/nearby/calls/$call/resume"), issued)
        } finally {channelJournal.close(); callJournal.close(); calls.close(); http.close(); root.cancel()}
    }
}
