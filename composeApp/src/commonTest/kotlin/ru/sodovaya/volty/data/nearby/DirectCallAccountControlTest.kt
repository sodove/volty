package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallAccountControlTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val key = "40000000-0000-0000-0000-000000000004"
    private val operation = VoiceOperationId("50000000-0000-0000-0000-000000000005")
    private val generation = VoiceGeneration(1)
    private fun snapshot(phase: String = "CANCELLED", caller: String = actor, callee: String = peer, outcome: String? = "CALLER_CANCELLED") =
        """{"callId":"$call","callerId":"$caller","calleeId":"$callee","phase":"$phase","revision":0,"ringDeadlineEpochMillis":45100,"outcome":${outcome?.let {"\"$it\""} ?: "null"}}"""
    private fun TestScope.account(job: Job, root: Job, token: suspend () -> String?) =
        SocialAuthenticatedAccount(SocialUserId(actor), job, root + StandardTestDispatcher(testScheduler), token)

    @Test fun rememberedOutgoingCancellationSurvivesAccountRetirementBeforeProducerEverStarts() = runTest {
        val root = Job(); val job = Job(root); var tokenReads = 0; val issued = mutableListOf<String>()
        val account = account(job, root) {tokenReads++; "original-account-token"}
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                assertEquals("Bearer original-account-token", it.headers[HttpHeaders.Authorization])
                val body = (it.body as TextContent).text
                for (value in listOf(peer, key, operation.value)) assertTrue(body.contains(value))
                respond("""{"call":${snapshot()}}""")
            }
        }}
        val control = DirectCallAccountControl.create(account, http, "https://api.test/v2/nearby", {100})
        try {
            assertTrue(control.rememberOutgoing(call, peer, key, operation, generation))
            job.cancel(); control.retireAdmission()
            assertFailsWith<CancellationException> {control.create(call, peer, key, operation)}
            val cancelled = assertIs<DirectCallReply.Ready>(control.cancelOutgoing(call, peer, key, operation))
            assertTrue(cancelled.call.terminal); assertNull(cancelled.session)
            assertEquals(listOf("/v2/nearby/calls/$call/cancel-outgoing"), issued)
            assertEquals(1, tokenReads)
        } finally {control.close(); http.close(); root.cancel()}
    }

    @Test fun unregisteredOrReboundPayloadNeverEarnsCleanupHttp() = runTest {
        val root = Job(); val job = Job(root); var issued = 0
        val http = HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler {issued++; respond("", HttpStatusCode.Unauthorized)}}}
        val control = DirectCallAccountControl.create(account(job, root) {"original-token"}, http, "https://api.test/v2/nearby", {100})
        try {
            assertEquals(DirectCallFailure.ACCESS_DENIED, assertIs<DirectCallReply.Failed>(control.stop(call)).reason)
            assertTrue(control.rememberOutgoing(call, peer, key, operation, generation))
            assertFalse(control.rememberOutgoing(call, peer, actor, operation, generation))
            assertFalse(control.rememberOutgoing(call, peer, key, operation, VoiceGeneration(2)))
            assertFalse(control.rememberOutgoing(peer, actor, key, operation, generation))
            assertEquals(DirectCallFailure.ACCESS_DENIED, assertIs<DirectCallReply.Failed>(control.cancelOutgoing(call, peer, actor, operation)).reason)
            assertEquals(DirectCallFailure.ACCESS_DENIED, assertIs<DirectCallReply.Failed>(control.resume(call, VoiceOperationId(key), generation)).reason)
            assertEquals(0, issued)
        } finally {control.close(); http.close(); root.cancel()}
    }

    @Test fun actualLateAdmissionResponseCannotPublishAfterRetirement() = runTest {
        val root = Job(); val job = Job(root); val release = CompletableDeferred<Unit>()
        var issued = 0; var returned = false; var published = false
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {withContext(NonCancellable) {
                issued++; release.await()
                respond("""{"call":${snapshot("RINGING", outcome = null)}}""").also {returned = true}
            }}
        }}
        val control = DirectCallAccountControl.create(account(job, root) {"original-token"}, http, "https://api.test/v2/nearby", {100})
        try {
            assertTrue(control.rememberOutgoing(call, peer, key, operation, generation))
            val request = async {control.create(call, peer, key, operation).also {published = true}}
            runCurrent(); assertEquals(1, issued)
            job.cancel(); control.retireAdmission(); release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertFailsWith<CancellationException> {request.await()}
            assertFalse(published)
        } finally {release.complete(Unit); control.close(); http.close(); root.cancel(); runCurrent()}
    }

    @Test fun incomingDiscoveryNeedsExplicitOriginalAcceptanceIntentBeforeCleanup() = runTest {
        val root = Job(); val job = Job(root); val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                respond(if (it.url.encodedPath.endsWith("incoming")) """{"calls":[${snapshot("RINGING", peer, actor, null)}]}"""
                else """{"call":${snapshot("CANCELLED", peer, actor, "CALLEE_CANCELLED")}}""")
            }
        }}
        val control = DirectCallAccountControl.create(account(job, root) {"original-token"}, http, "https://api.test/v2/nearby", {100})
        try {
            val ring = assertIs<IncomingCallReply.Ready>(control.incoming()).calls.single()
            assertEquals(DirectCallFailure.ACCESS_DENIED, assertIs<DirectCallReply.Failed>(control.stop(call)).reason)
            assertFalse(control.rememberIncoming(ring.copy(revision = 10), operation, generation))
            assertTrue(control.rememberIncoming(ring, operation, generation))
            job.cancel(); control.retireAdmission()
            assertIs<DirectCallReply.Ready>(control.stop(call))
            assertEquals(listOf("/v2/nearby/calls/incoming", "/v2/nearby/calls/$call/stop"), issued)
        } finally {control.close(); http.close(); root.cancel()}
    }

    @Test fun closeDuringActuallyHeldBearerCannotResurrectCapabilityOrCloseBorrowedParent() = runTest {
        val root = Job(); val job = Job(root); val release = CompletableDeferred<Unit>(); var reads = 0; var issued = 0
        val account = account(job, root) {
            reads++
            if (reads > 1) withContext(NonCancellable) {release.await()}
            "original-token"
        }
        val http = HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler {issued++; respond("")}}}
        val control = DirectCallAccountControl.create(account, http, "https://api.test/v2/nearby", {100})
        try {
            assertTrue(control.rememberOutgoing(call, peer, key, operation, generation))
            val request = async {control.create(call, peer, key, operation)}
            runCurrent(); assertEquals(2, reads); assertEquals(0, issued)
            control.close(); release.complete(Unit); runCurrent()
            assertFailsWith<CancellationException> {request.await()}
            assertFailsWith<CancellationException> {control.cancelOutgoing(call, peer, key, operation)}
            assertFalse(control.rememberOutgoing(call, peer, key, operation, generation))
            assertEquals(0, issued); assertTrue(job.isActive); assertTrue(root.isActive)
            http.get("https://api.test/parent"); assertEquals(1, issued)
        } finally {release.complete(Unit); control.close(); http.close(); root.cancel(); runCurrent()}
    }

    @Test fun terminalObservationCannotBeOverwrittenByOlderIncomingRing() = runTest {
        val root = Job(); val job = Job(root); var issued = 0
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued++
                respond(if (it.url.encodedPath.endsWith("incoming")) """{"calls":[${snapshot("RINGING", peer, actor, null)}]}"""
                else """{"call":${snapshot("MISSED", peer, actor, "RING_TIMEOUT").replace("\"revision\":0", "\"revision\":1")}}""")
            }
        }}
        val control = DirectCallAccountControl.create(account(job, root) {"original-token"}, http, "https://api.test/v2/nearby", {100})
        try {
            assertIs<IncomingCallReply.Ready>(control.incoming())
            assertTrue(assertIs<DirectCallReply.Ready>(control.status(call)).call.terminal)
            val stale = assertIs<IncomingCallReply.Ready>(control.incoming()).calls.single()
            assertFalse(control.rememberIncoming(stale, operation, generation))
            assertEquals(DirectCallFailure.ACCESS_DENIED, assertIs<DirectCallReply.Failed>(control.stop(call)).reason)
            assertEquals(3, issued)
        } finally {control.close(); http.close(); root.cancel()}
    }

    @Test fun incomingAcceptRequiresRememberedRevisionOperationAndGeneration() = runTest {
        val root = Job(); val job = Job(root); var issued = 0
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued++
                if (it.url.encodedPath.endsWith("incoming")) respond("""{"calls":[${snapshot("RINGING", peer, actor, null)}]}""")
                else {
                    assertTrue((it.body as TextContent).text.contains(operation.value))
                    respond("""{"call":${snapshot("ACCEPTED", peer, actor, null)},"session":{"phase":"PENDING","operationId":"${operation.value}"}}""", HttpStatusCode.Accepted)
                }
            }
        }}
        val control = DirectCallAccountControl.create(account(job, root) {"original-token"}, http, "https://api.test/v2/nearby", {100})
        try {
            val ring = assertIs<IncomingCallReply.Ready>(control.incoming()).calls.single()
            assertTrue(control.rememberIncoming(ring, operation, generation))
            for ((op, gen, rev) in listOf(Triple(VoiceOperationId(key), generation, 0L),
                Triple(operation, VoiceGeneration(2), 0L), Triple(operation, generation, 1L)))
                assertEquals(DirectCallFailure.ACCESS_DENIED, assertIs<DirectCallReply.Failed>(control.accept(call, op, gen, rev)).reason)
            assertEquals(1, issued)
            val accepted = assertIs<DirectCallReply.Ready>(control.accept(call, operation, generation, 0))
            assertIs<ru.sodovaya.volty.data.voice.VoiceHttpReply.Pending>(accepted.session)
            assertEquals(2, issued)
        } finally {control.close(); http.close(); root.cancel()}
    }
}
