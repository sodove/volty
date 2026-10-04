package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class AccountVoiceSessionControlTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val destination = "30000000-0000-0000-0000-000000000003"
    private val key = "40000000-0000-0000-0000-000000000004"
    private val channelOperation = VoiceOperationId("50000000-0000-0000-0000-000000000005")
    private val callOperation = VoiceOperationId("50000000-0000-0000-0000-000000000006")
    private val sid = "60000000-0000-0000-0000-000000000006"

    @Test fun sharedJournalSeparatesEqualDestinationUuidsAndOriginalOperations() = runTest {
        val root = Job(); val job = Job(root)
        val issued = mutableListOf<String>()
        val client = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                respond(if (it.url.encodedPath.endsWith("/create"))
                    """{"call":{"callId":"$destination","callerId":"$actor","calleeId":"$peer","phase":"RINGING","revision":1,"ringDeadlineEpochMillis":45100,"outcome":null}}"""
                else """{"phase":"CONNECTING","sessionId":"$sid","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://media.test","participantToken":"token","roomEpoch":"channel-room"}}""")
            }
        }}
        val account = SocialAuthenticatedAccount(SocialUserId(actor), job, root + StandardTestDispatcher(testScheduler)) {"bearer"}
        val control = AccountVoiceSessionControl.create(account, client, "https://api.test/v2/nearby", {100})
        try {
            control.rememberJoinIntent(channelOperation, VoiceGeneration(1), destination, false)
            assertFalse(control.registerOutgoing(destination, peer, key, channelOperation))
            assertIs<VoiceHttpReply.Credentials>(control.join(channelOperation, VoiceGeneration(1), destination))
            assertTrue(control.registerOutgoing(destination, peer, key, callOperation))
            control.rememberJoinIntent(callOperation, VoiceGeneration(2), destination, false)
            assertIs<VoiceHttpReply.Pending>(control.join(callOperation, VoiceGeneration(2), destination))
            assertIs<VoiceHttpReply.Failed>(control.join(callOperation, VoiceGeneration(3), destination))
            assertEquals(listOf("/v2/nearby/operations/${channelOperation.value}/join", "/v2/nearby/calls/$destination/create"), issued)
            control.retireAdmission(); job.cancel()
            assertFailsWith<CancellationException> {control.join(channelOperation, VoiceGeneration(1), destination)}
            assertEquals(2, issued.size)
        } finally {control.close(); client.close(); root.cancel()}
    }
}
