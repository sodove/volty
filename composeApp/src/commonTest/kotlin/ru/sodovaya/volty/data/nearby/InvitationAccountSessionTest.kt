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
        val accountJob = Job()
        val session = InvitationAccountSession(NearbyAccountCredentials(actor, "private"), Store(actor),
            InvitationSecretGenerator { InvitationSecret.fromToken(token) }, client, "https://example.test/v2/nearby",
            accountJob = accountJob)
        assertTrue(accountJob.children.none(), "The invitation session must not create a child Job")
        try {
            session.owner.prepare(invitation, community, 900_000, 4)
            val executing = async { session.owner.execute(invitation).also { published = true } }
            runCurrent()
            assertTrue(started.isCompleted, "The request must actually reach the HTTP engine")
            session.retire(); finish.complete(Unit); runCurrent()
            assertFailsWith<CancellationException> { executing.await() }
            assertEquals(1, issued); assertFalse(published)
            assertTrue(accountJob.isActive, "Retiring a session must leave its borrowed Job active")
        } finally { finish.complete(Unit); session.retire(); accountJob.cancel(); client.close() }
    }

    @Test fun cancelledBorrowedJobDeniesPrepareBeforeStoreReadOrSecretGeneration() = runTest {
        val accountJob = Job()
        var generated = 0
        val store = Store(actor)
        val client = HttpClient(MockEngine) { engine { addHandler { error("No HTTP request expected") } } }
        val session = InvitationAccountSession(
            NearbyAccountCredentials(actor, "private"), store,
            InvitationSecretGenerator { generated++; InvitationSecret.fromToken(token) },
            client, "https://example.test/v2/nearby", accountJob = accountJob,
        )
        assertTrue(accountJob.children.none(), "The invitation session must not create a child Job")
        val childEntered = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        CoroutineScope(accountJob + StandardTestDispatcher(testScheduler)).launch {
            withContext(NonCancellable) {
                childEntered.complete(Unit)
                releaseChild.await()
            }
        }
        try {
            runCurrent()
            assertTrue(childEntered.isCompleted)
            accountJob.cancel()
            assertFalse(accountJob.isCompleted, "The held child keeps the cancelled account Job incomplete")

            assertFailsWith<CancellationException> {
                session.owner.prepare(invitation, community, 900_000, 4)
            }
            assertEquals(0, store.reads)
            assertEquals(0, generated)
        } finally {
            releaseChild.complete(Unit)
            accountJob.cancel()
            runCurrent()
            session.retire()
            client.close()
        }
    }

    @Test fun heldStoreReadAfterAccountCancellationCannotPublishOrGenerateSecret() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<InvitationCreationDraft?>()
        val childEntered = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        var reads = 0
        var retained = 0
        var generated = 0
        var published = false
        var readReturned = false
        val store = object : InvitationDraftStore {
            override val actorId = actor
            override suspend fun read(invitationId: String): InvitationCreationDraft? = withContext(NonCancellable) {
                reads++
                readStarted.complete(Unit)
                finishRead.await().also { readReturned = true }
            }
            override suspend fun retain(draft: InvitationCreationDraft): InvitationCreationDraft {
                retained++
                return draft
            }
        }
        val client = HttpClient(MockEngine) { engine { addHandler { error("No HTTP request expected") } } }
        val accountJob = Job()
        val session = InvitationAccountSession(
            NearbyAccountCredentials(actor, "private"), store,
            InvitationSecretGenerator { generated++; InvitationSecret.fromToken(token) },
            client, "https://example.test/v2/nearby", accountJob = accountJob,
        )
        assertTrue(accountJob.children.none(), "The invitation session must not create a child Job")
        CoroutineScope(accountJob + StandardTestDispatcher(testScheduler)).launch {
            withContext(NonCancellable) {
                childEntered.complete(Unit)
                releaseChild.await()
            }
        }
        try {
            val preparation = async {
                session.owner.prepare(invitation, community, 900_000, 4).also { published = true }
            }
            runCurrent()
            assertTrue(readStarted.isCompleted, "Preparation must reach the deliberately held store read")
            assertTrue(childEntered.isCompleted)

            accountJob.cancel()
            assertFalse(accountJob.isCompleted, "The held child keeps the cancelled account Job incomplete")
            finishRead.complete(null)
            runCurrent()

            assertTrue(readReturned, "The held read must return before judging its publication guard")
            assertFailsWith<CancellationException> { preparation.await() }
            assertEquals(1, reads)
            assertEquals(0, generated)
            assertEquals(0, retained)
            assertFalse(published, "A late store result must not publish preparation")
        } finally {
            finishRead.complete(null)
            releaseChild.complete(Unit)
            accountJob.cancel()
            runCurrent()
            session.retire()
            client.close()
        }
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
