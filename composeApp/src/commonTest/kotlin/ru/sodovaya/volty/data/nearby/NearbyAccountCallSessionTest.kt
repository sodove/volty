package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAccountCallSessionTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val key = "40000000-0000-0000-0000-000000000004"
    private val operation = VoiceOperationId("50000000-0000-0000-0000-000000000005")
    private val generation = VoiceGeneration(1)
    private fun session(http: HttpClient, job: Job) = NearbyAccountCallSession(
        NearbyAccountCredentials(actor, "private-account-token"), http, "https://api.test/v2/nearby", {100}, job)

    @Test fun cancellingAccountRejectsAllCallMethodsBeforeHttp() = runTest {
        val job = Job(); var issued = 0
        val http = HttpClient(MockEngine) {engine {addHandler {issued++; respond("", HttpStatusCode.Unauthorized)}}}
        val session = session(http, job)
        val release = CompletableDeferred<Unit>()
        CoroutineScope(job + StandardTestDispatcher(testScheduler)).launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {release.await()}
        }
        try {
            job.cancel(); assertFalse(job.isCompleted)
            val calls: List<suspend () -> Any> = listOf(
                {session.calls.create(call, peer, key, operation)}, {session.calls.cancelOutgoing(call, peer, key, operation)},
                {session.calls.status(call)}, {session.calls.incoming()}, {session.calls.accept(call, operation, generation, 1)},
                {session.calls.resume(call, operation, generation)}, {session.calls.decline(call)}, {session.calls.stop(call)},
            )
            for (action in calls) assertFailsWith<CancellationException> {action()}
            assertEquals(0, issued)
        } finally {release.complete(Unit); session.retire(); http.close(); job.cancel(); runCurrent()}
    }

    @Test fun issuedResponseAfterAccountCancellationCannotPublish() = runTest {
        val job = Job(); val release = CompletableDeferred<Unit>(); var issued = 0; var returned = false; var published = false
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {withContext(NonCancellable) {
                issued++; release.await()
                respond("", HttpStatusCode.Unauthorized).also {returned = true}
            }}
        }}
        val session = session(http, job)
        try {
            val request = async {session.calls.status(call).also {published = true}}
            runCurrent(); assertEquals(1, issued)
            job.cancel(); release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertFailsWith<CancellationException> {request.await()}
            assertFalse(published)
        } finally {release.complete(Unit); session.retire(); http.close(); job.cancel(); runCurrent()}
    }

    @Test fun retirementPreservesBorrowedAuthenticationAndParentHttp() = runTest {
        val job = Job(); val http = HttpClient(MockEngine) {engine {addHandler {respond("")}}}
        val session = session(http, job)
        try {
            session.retire(); assertTrue(job.isActive); assertTrue(job.children.none())
            http.get("https://api.test/parent")
            assertFailsWith<CancellationException> {session.calls.status(call)}
        } finally {session.retire(); http.close(); job.cancel()}
    }

    @Test fun sameActorRefreshChangesBearerButCannotReviveRetiredConsumer() = runTest {
        val job = Job(); val tokens = mutableListOf<String?>()
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {tokens += it.headers[HttpHeaders.Authorization]; respond("", HttpStatusCode.Unauthorized)}
        }}
        val session = session(http, job)
        try {
            session.calls.status(call)
            session.refresh(NearbyAccountCredentials(actor, "refreshed-token"))
            session.calls.status(call)
            assertEquals<List<String?>>(listOf("Bearer private-account-token", "Bearer refreshed-token"), tokens)
            assertFailsWith<IllegalArgumentException> {session.refresh(NearbyAccountCredentials(peer, "other-token"))}
            session.retire()
            assertFailsWith<IllegalStateException> {session.refresh(NearbyAccountCredentials(actor, "late-token"))}
            assertEquals(2, tokens.size)
        } finally {session.retire(); http.close(); job.cancel()}
    }
}
