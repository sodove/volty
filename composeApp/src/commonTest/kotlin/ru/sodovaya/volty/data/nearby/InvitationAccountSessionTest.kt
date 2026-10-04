package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationAccountSessionTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val other = "20000000-0000-0000-0000-000000000002"
    private val invitation = "30000000-0000-0000-0000-000000000003"
    private val community = "40000000-0000-0000-0000-000000000004"
    private val token = "A".repeat(43)
    private class Store(override val actorId: String) : InvitationDraftStore {
        var saved: InvitationCreationDraft? = null
        var reads = 0
        override suspend fun read(invitationId: String): InvitationCreationDraft? { reads++; return saved }
        override suspend fun retain(draft: InvitationCreationDraft) = (saved ?: draft).also { saved = it }
    }
    private fun metadata() = """{"invitationId":"$invitation","communityId":"$community","createdAtMillis":100,"expiresAtMillis":900100,"maxUses":4,"uses":0,"revokedAtMillis":null,"creationRevision":1,"revocationRevision":null}"""

    @Test fun actualInFlightHttpReceiptCannotPublishAfterSessionRetirement() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var issued = 0
        var published = false
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { withContext(NonCancellable) {
                issued++; started.complete(Unit); finish.await(); respond(metadata())
            } }
        } }
        val session = InvitationAccountSession(NearbyAccountCredentials(actor, "private"), Store(actor),
            InvitationSecretGenerator { InvitationSecret.fromToken(token) }, client, "https://example.test/v2/nearby")
        try {
            session.owner.prepare(invitation, community, 900_000, 4)
            val executing = async { session.owner.execute(invitation).also { published = true } }
            runCurrent()
            assertTrue(started.isCompleted, "The request must actually reach the HTTP engine")
            session.retire(); finish.complete(Unit); runCurrent()
            assertFailsWith<CancellationException> { executing.await() }
            assertEquals(1, issued); assertFalse(published)
        } finally { finish.complete(Unit); session.retire(); client.close() }
    }

    @Test fun actualRequestUsesSameAccountRefreshAndRetirementKeepsParentClientUsable() = runTest {
        val auth = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { auth += it.headers[HttpHeaders.Authorization]; respond(metadata()) }
        } }
        val store = Store(actor)
        val session = InvitationAccountSession(NearbyAccountCredentials(actor, "first"), store,
            InvitationSecretGenerator { InvitationSecret.fromToken(token) }, client, "https://example.test/v2/nearby")
        try {
            assertIs<InvitationDraftPreparation.Prepared>(session.owner.prepare(invitation, community, 900_000, 4))
            session.refresh(NearbyAccountCredentials(actor, "refreshed"))
            assertIs<InvitationDraftExecution.Created>(session.owner.execute(invitation))
            assertEquals(listOf<String?>("Bearer refreshed"), auth)
            session.retire(); session.retire()
            val before = store.reads
            assertFailsWith<CancellationException> { session.owner.execute(invitation) }
            assertEquals(before, store.reads)
            assertNotNull(store.saved)
            client.get("https://example.test/parent")
            assertEquals(listOf("Bearer refreshed", null), auth)
        } finally { session.retire(); client.close() }
    }

    @Test fun independentAccountSessionCannotRefreshOrReviveTheOldSession() = runTest {
        val auth = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { auth += it.headers[HttpHeaders.Authorization]; respond(metadata()) }
        } }
        fun session(id: String, bearer: String) = InvitationAccountSession(NearbyAccountCredentials(id, bearer), Store(id),
            InvitationSecretGenerator { InvitationSecret.fromToken(token) }, client, "https://example.test/v2/nearby")
        val old = session(actor, "old"); val fresh = session(other, "fresh")
        try {
            assertFailsWith<IllegalArgumentException> { old.refresh(NearbyAccountCredentials(other, "foreign")) }
            old.retire()
            assertFailsWith<IllegalStateException> { old.refresh(NearbyAccountCredentials(actor, "revival")) }
            assertFailsWith<CancellationException> { old.owner.prepare(invitation, community, 900_000, 4) }
            assertIs<InvitationDraftPreparation.Prepared>(fresh.owner.prepare(invitation, community, 900_000, 4))
            assertIs<InvitationDraftExecution.Created>(fresh.owner.execute(invitation))
            assertEquals(listOf<String?>("Bearer fresh"), auth)
        } finally { old.retire(); fresh.retire(); client.close() }
    }

    @Test fun foreignAccountStoreIsRejectedBeforeAnyExternalWork() = runTest {
        var issued = 0
        val client = HttpClient(MockEngine) { engine { addHandler { issued++; respond(metadata()) } } }
        val store = Store(other)
        try {
            assertFailsWith<IllegalArgumentException> {
                InvitationAccountSession(NearbyAccountCredentials(actor, "private"), store,
                    InvitationSecretGenerator { error("No entropy") }, client, "https://example.test/v2/nearby")
            }
            assertEquals(0, issued); assertEquals(0, store.reads)
        } finally { client.close() }
    }
}
