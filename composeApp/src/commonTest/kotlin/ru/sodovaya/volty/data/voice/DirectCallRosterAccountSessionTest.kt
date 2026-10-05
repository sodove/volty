package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallRosterAccountSessionTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val first = "40000000-0000-0000-0000-000000000004"
    private val second = "50000000-0000-0000-0000-000000000005"
    private val tag = DirectCallRosterTag(call, peer, 8)
    private val base = "https://api.test/v2/nearby"
    private fun body() = """{"callId":"$call","revision":8,"serverTimeEpochMillis":100000,"peers":[{"actorId":"$actor","sessionId":"$first","phase":"CONNECTING","deadlineEpochMillis":101000,"mediaPresence":"UNKNOWN"},{"actorId":"$peer","sessionId":"$second","phase":"CONNECTING","deadlineEpochMillis":101000,"mediaPresence":"UNKNOWN"}]}"""
    private fun TestScope.account(job: Job, root: Job, token: suspend () -> String?) =
        SocialAuthenticatedAccount(SocialUserId(actor), job, root + StandardTestDispatcher(testScheduler), token)
    private fun TestScope.client(handler: MockRequestHandler) = HttpClient(MockEngine) {engine {
        dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler)
    }}

    @Test fun pinnedSubscriptionRefreshesOnlyCapturedAccountAndRetirementPreservesBorrowedOwners() = runTest {
        val root = Job(); val job = Job(root); var token = "original"; val headers = mutableListOf<String?>()
        val client = client {headers += it.headers[HttpHeaders.Authorization]; respond(body())}
        val session = DirectCallRosterAccountSession.create(account(job, root) {token}, client, base, tag, {100})
        try {
            assertIs<DirectCallRosterReply.Received>(session.snapshot(tag)); token = "refreshed"
            assertIs<DirectCallRosterReply.Received>(session.snapshot(tag))
            assertEquals<List<String?>>(listOf("Bearer original", "Bearer refreshed"), headers)
            assertFailsWith<IllegalArgumentException> {session.snapshot(tag.copy(generation = 9))}
            assertFailsWith<IllegalArgumentException> {session.snapshot(tag.copy(peerId = actor))}
            assertEquals(2, headers.size)
            session.retire(); assertTrue(job.isActive); assertTrue(root.isActive)
            assertFailsWith<CancellationException> {session.snapshot(tag)}
            client.get("https://api.test/parent-alive"); assertEquals(3, headers.size)
        } finally {session.retire(); client.close(); root.cancel()}
    }

    @Test fun retiredAccountCannotReturnHeldOriginalHttpReplyOrReviveThroughSameUserLogin() = runTest {
        val root = Job(); val job = Job(root); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val headers = mutableListOf<String?>()
        val client = client {
            headers += it.headers[HttpHeaders.Authorization]
            if (headers.size == 1) withContext(NonCancellable) {entered.complete(Unit); release.await()}
            respond(body())
        }
        val old = DirectCallRosterAccountSession.create(account(job, root) {"old"}, client, base, tag, {100})
        var new: DirectCallRosterAccountSession? = null
        try {
            val reply = async {old.snapshot(tag)}; runCurrent(); assertTrue(entered.isCompleted)
            job.cancel(); runCurrent()
            val nextJob = Job(root)
            new = DirectCallRosterAccountSession.create(account(nextJob, root) {"new"}, client, base, tag.copy(generation = 9), {100})
            assertIs<DirectCallRosterReply.Received>(new.snapshot(tag.copy(generation = 9)))
            release.complete(Unit); runCurrent()
            assertFailsWith<CancellationException> {reply.await()}
            assertFailsWith<CancellationException> {old.snapshot(tag)}
            assertEquals<List<String?>>(listOf("Bearer old", "Bearer new"), headers)
            assertTrue(nextJob.isActive)
        } finally {release.complete(Unit); old.retire(); new?.retire(); client.close(); root.cancel()}
    }

    @Test fun factoryRejectsRetiredAccountAndInsecureTransportBeforeIssuing() = runTest {
        val root = Job(); val job = Job(root); var issued = 0
        val client = client {issued++; respond(body())}
        try {
            val account = account(job, root) {"token"}
            assertFailsWith<IllegalArgumentException> {DirectCallRosterAccountSession.create(account, client, "http://api.test/v2/nearby", tag, {100})}
            job.cancel()
            assertFailsWith<IllegalStateException> {DirectCallRosterAccountSession.create(account, client, base, tag, {100})}
            assertEquals(0, issued)
        } finally {client.close(); root.cancel()}
    }
}
