package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAccountHttpSessionTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val other = "20000000-0000-0000-0000-000000000002"
    private val community = "30000000-0000-0000-0000-000000000003"
    private val channel = "40000000-0000-0000-0000-000000000004"
    private val invitation = "50000000-0000-0000-0000-000000000005"
    private val operation = "60000000-0000-0000-0000-000000000006"
    private val root = "https://example.test/v2/nearby"
    private val token = "A".repeat(43)

    @Test fun cancellingAccountImmediatelyDeniesAllMethodsWithoutWaitingForItsChildren() = runTest {
        val accountJob = Job()
        val release = CompletableDeferred<Unit>()
        CoroutineScope(accountJob + StandardTestDispatcher(testScheduler)).launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { release.await() }
        }
        var issued = 0
        val client = HttpClient(MockEngine) { engine { addHandler { issued++; respond("", HttpStatusCode.Unauthorized) } } }
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, root, accountJob)
        try {
            accountJob.cancel()
            assertFalse(accountJob.isActive)
            assertFalse(accountJob.isCompleted, "Held child must prevent account completion")
            for (intent in intents(session)) assertFailsWith<CancellationException> { intent.call() }
            assertEquals(0, issued)
            assertFailsWith<IllegalStateException> { session.refresh(NearbyAccountCredentials(actor, "new")) }
        } finally { release.complete(Unit); accountJob.cancel(); session.retire(); client.close(); runCurrent() }
    }

    @Test fun cancelledAccountCannotPublishAnActuallyIssuedResponseToAnIndependentCaller() = runTest {
        val accountJob = Job()
        val release = CompletableDeferred<Unit>()
        var issued = 0
        var returned = false
        var published = false
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { withContext(NonCancellable) {
                issued++; release.await()
                respond("", HttpStatusCode.Forbidden).also { returned = true }
            } }
        } }
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, root, accountJob)
        try {
            val request = async { session.communities.memberships().also { published = true } }
            runCurrent()
            assertEquals(1, issued)
            accountJob.cancel()
            release.complete(Unit); runCurrent()
            assertTrue(returned, "Producer must return so the publication guard is reached")
            assertFailsWith<CancellationException> { request.await() }
            assertFalse(published)
        } finally { release.complete(Unit); accountJob.cancel(); session.retire(); client.close(); runCurrent() }
    }

    @Test fun explicitHttpRetirementDoesNotCancelOrAttachChildrenToBorrowedAccountJob() = runTest {
        val accountJob = Job()
        val client = HttpClient(MockEngine) { engine { addHandler { respond("") } } }
        val before = accountJob.children.toList()
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, root, accountJob)
        try {
            assertEquals(before, accountJob.children.toList())
            session.retire()
            assertTrue(accountJob.isActive)
            client.get("https://example.test/parent")
        } finally { accountJob.cancel(); session.retire(); client.close() }
    }
    private data class Intent(val method: HttpMethod, val path: String, val call: suspend () -> Any)
    private fun intents(session: NearbyAccountHttpSession): List<Intent> {
        val base = "/v2/nearby/communities"
        return listOf(
            Intent(HttpMethod.Get, base) { session.communities.memberships() },
            Intent(HttpMethod.Get, "$base/catalog") { session.communities.catalog("riders") },
            Intent(HttpMethod.Get, "$base/$community") { session.communities.detail(community) },
            Intent(HttpMethod.Put, "$base/$community") { session.communities.create(community, "Riders") },
            Intent(HttpMethod.Post, "$base/$community/join") { session.communities.joinPublic(community) },
            Intent(HttpMethod.Post, "$base/$community/leave") { session.removals.leave(community, operation) },
            Intent(HttpMethod.Post, "$base/$community/delete") { session.removals.delete(community, operation) },
            Intent(HttpMethod.Put, "$base/$community/channels/$channel") { session.channels.create(community, channel, "Ride") },
            Intent(HttpMethod.Post, "$base/$community/channels/$channel/delete") { session.channels.delete(community, channel, operation) },
            Intent(HttpMethod.Put, "$base/$community/invitations/$invitation") { session.invitations.create(community, invitation, token, 900_000, 4) },
            Intent(HttpMethod.Post, "$base/$community/invitations/$invitation/revoke") { session.invitations.revoke(community, invitation) },
            Intent(HttpMethod.Post, "/v2/nearby/invitations/accept") { session.invitations.accept(operation, token) },
            Intent(HttpMethod.Get, "$base/$community/invitations") { session.invitationListing.list(community) },
        )
    }
    private fun unauthorized(value: Any) = when (value) {
        is CommunityResult.Failed -> value.reason == CommunityFailure.UNAUTHORIZED
        is CommunityRemovalResult.Failed -> value.reason == CommunityRemovalFailure.UNAUTHORIZED
        is ChannelCreationCommandResult.Failed -> value.reason == ChannelCommandFailure.UNAUTHORIZED
        is ChannelDeletionCommandResult.Failed -> value.reason == ChannelCommandFailure.UNAUTHORIZED
        is InvitationCreationCommandResult.Failed -> value.reason == InvitationCommandFailure.UNAUTHORIZED
        is InvitationRevocationCommandResult.Failed -> value.reason == InvitationCommandFailure.UNAUTHORIZED
        is InvitationAcceptanceCommandResult.Failed -> value.reason == InvitationCommandFailure.UNAUTHORIZED
        is InvitationListingCommandResult.Failed -> value.reason == InvitationCommandFailure.UNAUTHORIZED
        else -> false
    }

    @Test fun allThirteenMethodsUseBoundAuthAndExistingWireRoutes() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { requests += it; respond("", HttpStatusCode.Unauthorized) }
        } }
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, "$root/")
        try {
            val calls = intents(session)
            assertEquals(13, calls.size)
            for (intent in calls) assertTrue(unauthorized(intent.call()))
            assertEquals(calls.map { it.method to it.path }, requests.map { it.method to it.url.encodedPath })
            assertEquals(List<String?>(13) { "Bearer private" }, requests.map { it.headers[HttpHeaders.Authorization] })
        } finally { session.retire(); client.close() }
    }

    @Test fun terminalRetirementFencesEveryMethodBeforeHttpAndKeepsParentUsable() = runTest {
        var issued = 0
        val client = HttpClient(MockEngine) { engine { addHandler { issued++; respond("", HttpStatusCode.Unauthorized) } } }
        val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, root)
        try {
            session.retire(); session.retire()
            for (intent in intents(session)) {
                val failure = assertFailsWith<CancellationException> { intent.call() }
                assertNull(failure.cause); assertFalse(failure.toString().contains("private"))
            }
            assertEquals(0, issued)
            client.get("https://example.test/parent")
            assertEquals(1, issued)
        } finally { session.retire(); client.close() }
    }

    @Test fun refreshAndIndependentAccountDoNotSubstituteTheOldPrincipal() = runTest {
        val auth = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { auth += it.headers[HttpHeaders.Authorization]; respond("", HttpStatusCode.Unauthorized) }
        } }
        val old = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "old"), client, root)
        val fresh = NearbyAccountHttpSession(NearbyAccountCredentials(other, "fresh"), client, root)
        try {
            old.refresh(NearbyAccountCredentials(actor, "refreshed"))
            assertFailsWith<IllegalArgumentException> { old.refresh(NearbyAccountCredentials(other, "foreign")) }
            assertTrue(unauthorized(old.communities.memberships()))
            old.retire()
            assertFailsWith<IllegalStateException> { old.refresh(NearbyAccountCredentials(actor, "revive")) }
            assertFailsWith<CancellationException> { old.communities.memberships() }
            assertTrue(unauthorized(fresh.communities.memberships()))
            assertEquals(listOf<String?>("Bearer refreshed", "Bearer fresh"), auth)
        } finally { old.retire(); fresh.retire(); client.close() }
    }

    @Test fun retirementDuringActualHttpSuppressesLateResponseOrTypedFailure() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var issued = 0
            var returned = false
            val client = HttpClient(MockEngine) { engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { withContext(NonCancellable) {
                    issued++; started.complete(Unit); finish.await(); respond("[]", status)
                } }
            } }
            val session = NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, root)
            try {
                val reading = async { session.communities.memberships().also { returned = true } }
                runCurrent(); assertTrue(started.isCompleted)
                session.retire(); finish.complete(Unit); runCurrent()
                assertFailsWith<CancellationException> { reading.await() }
                assertEquals(1, issued); assertFalse(returned)
            } finally { finish.complete(Unit); session.retire(); client.close() }
        }
    }

    @Test fun invalidRootIsRejectedBeforeAnyRequests() = runTest {
        var issued = 0
        val client = HttpClient(MockEngine) { engine { addHandler { issued++; respond("[]") } } }
        try {
            for (url in listOf("http://example.test/v2", "$root?", "$root#", "https://user@example.test/v2", "https://", "https:///v2", "https://@/v2", "https://:443/v2")) {
                assertFailsWith<IllegalArgumentException>(url) { NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, url) }
            }
            for (url in listOf("https://[::1]:8443/v2", "https://localhost:8443/v2")) {
                NearbyAccountHttpSession(NearbyAccountCredentials(actor, "private"), client, url).retire()
            }
            assertEquals(0, issued)
        } finally { client.close() }
    }
}
