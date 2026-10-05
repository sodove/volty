package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticatedCallRosterOwnerTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val sid = "40000000-0000-0000-0000-000000000004"
    private val other = "50000000-0000-0000-0000-000000000005"
    private val tag = DirectCallRosterTag(call, peer, 7)
    private val own = VoiceSessionTag(VoiceSessionId(sid), VoiceGeneration(5))
    private fun body(ownSid: String = sid) = """{"callId":"$call","revision":8,"serverTimeEpochMillis":100000,"peers":[{"actorId":"$actor","sessionId":"$ownSid","phase":"CONNECTING","deadlineEpochMillis":101000,"mediaPresence":"UNKNOWN"},{"actorId":"$peer","sessionId":"$other","phase":"CONNECTED","deadlineEpochMillis":101000,"mediaPresence":"CONNECTED","participantSid":"observed-sid"}]}"""

    @Test fun actualAccountHttpRosterMatchesOwnEarnedSessionAndClosePreservesBorrowedOwners() = runTest {
        val root = Job(); val job = Job(root); val dispatcher = StandardTestDispatcher(testScheduler)
        val account = SocialAuthenticatedAccount(SocialUserId(actor), job, root + dispatcher) {"captured-token"}
        val issued = mutableListOf<String>()
        val client = HttpClient(MockEngine) {engine {
            this.dispatcher = dispatcher
            addHandler {issued += it.url.encodedPath; respond(body())}
        }}
        val owner = AuthenticatedCallRosterOwner.create(account, client, "https://api.test/v2/nearby", tag, own, dispatcher, MutableSharedFlow(), {100})
        try {
            runCurrent(); assertEquals(listOf("/v2/nearby/calls/$call/roster"), issued)
            assertEquals(2, assertNotNull(owner.state.value.peers).size); assertEquals(1, owner.state.value.connectedCount)
            assertEquals(own, owner.ownSession); assertEquals(tag, owner.tag)
            val cancelled = Job().apply {cancel()}; var returned = false
            CoroutineScope(coroutineContext + cancelled).launch(start = CoroutineStart.UNDISPATCHED) {owner.close(); returned = true}
            runCurrent(); assertTrue(returned); assertTrue(owner.state.value.retired); assertNull(owner.state.value.peers)
            assertTrue(job.isActive); assertTrue(root.isActive); assertFalse(owner.cleanupFailed.value)
            client.get("https://api.test/parent-alive"); assertEquals(2, issued.size)
        } finally {owner.close(); client.close(); root.cancel(); runCurrent()}
    }

    @Test fun anotherSessionOfSameActorNeverBecomesThisVoiceCallsPresence() = runTest {
        val root = Job(); val job = Job(root); val dispatcher = StandardTestDispatcher(testScheduler)
        val account = SocialAuthenticatedAccount(SocialUserId(actor), job, root + dispatcher) {"token"}
        var issued = 0
        val client = HttpClient(MockEngine) {engine {this.dispatcher = dispatcher; addHandler {issued++; respond(body(call))}}}
        val owner = AuthenticatedCallRosterOwner.create(account, client, "https://api.test/v2/nearby", tag, own, dispatcher, MutableSharedFlow(), {100})
        try {
            runCurrent(); assertEquals(1, issued); assertNull(owner.state.value.peers); assertNull(owner.state.value.connectedCount)
            assertEquals(VoiceRosterUnknown.INVALID_RESPONSE, owner.state.value.unknown)
            assertTrue(job.isActive)
        } finally {owner.close(); client.close(); root.cancel(); runCurrent()}
    }

    @Test fun accountCancellationWithdrawsAlreadyPublishedActualRosterAndDeniesRefresh() = runTest {
        val root = Job(); val job = Job(root); val dispatcher = StandardTestDispatcher(testScheduler)
        val account = SocialAuthenticatedAccount(SocialUserId(actor), job, root + dispatcher) {"token"}
        var issued = 0
        val client = HttpClient(MockEngine) {engine {this.dispatcher = dispatcher; addHandler {issued++; respond(body())}}}
        val owner = AuthenticatedCallRosterOwner.create(account, client, "https://api.test/v2/nearby", tag, own, dispatcher, MutableSharedFlow(), {100})
        try {
            runCurrent(); assertEquals(2, assertNotNull(owner.state.value.peers).size)
            job.cancel(); runCurrent()
            assertTrue(owner.state.value.retired); assertNull(owner.state.value.peers); assertFalse(owner.refresh())
            assertEquals(1, issued); assertTrue(root.isActive)
        } finally {owner.close(); client.close(); root.cancel(); runCurrent()}
    }
}
