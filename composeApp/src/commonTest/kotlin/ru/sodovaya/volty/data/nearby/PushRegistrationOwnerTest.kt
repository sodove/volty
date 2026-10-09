package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationOwnerTest {
    private val a = "10000000-0000-0000-0000-000000000001"
    private val b = "20000000-0000-0000-0000-000000000002"
    private class Store : PushInstallationStore {
        var value: PushInstallationSnapshot? = null
        override suspend fun read() = value
        override suspend fun write(snapshot: PushInstallationSnapshot) {value = snapshot}
    }
    private class Ids : PushInstallationIdentityGenerator {
        var count = 0
        override fun uuid() = "30000000-0000-0000-0000-" + (++count).toString().padStart(12, '0')
        override fun capability() = "a".repeat(64)
    }
    private class Tokens : PushTokenSource {
        var value = "initial-token"; var reads = 0; var rotations = 0
        var activationFails = false
        override fun setActive(active: Boolean) {if (activationFails) throw IllegalStateException("Controlled SDK switch failure")}
        override suspend fun current(): String {reads++; return value}
        override suspend fun renew(): String {rotations++; value = "renewed-token"; return value}
    }
    private class Client : PushRegistrationClient {
        val issued = mutableListOf<PushRegistrationIntent>(); var closed = false
        var send: suspend () -> PushRegistrationResult = {PushRegistrationResult.APPLIED}
        override suspend fun submit(intent: PushRegistrationIntent): PushRegistrationResult {issued += intent; return send()}
        override fun retire() {closed = true}
    }
    private inner class Fixture(val scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val root = Job(); val app = CoroutineScope(root + dispatcher)
        val accounts = MutableStateFlow<SocialAuthenticatedAccount?>(null)
        val store = Store(); val ledger = PushInstallationLedger(store, Ids()); val tokens = Tokens()
        val pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val clients = mutableListOf<Pair<SocialAuthenticatedAccount, Client>>()
        val registered = mutableListOf<SocialAuthenticatedAccount>()
        var configure: (Client) -> Unit = {}
        val owner = PushRegistrationOwner(accounts, app, dispatcher, pulses, hints, ledger, tokens,
            {account -> Client().also {configure(it); clients += account to it}}, {registered += it})
        fun account(id: String) = SocialAuthenticatedAccount(SocialUserId(id), Job(root), root + dispatcher) {"repository-token"}
        suspend fun close() {owner.close(); root.cancel(); scope.runCurrent()}
    }
    @Test fun initialAuthenticationPersistsBeforeSendThenAcknowledgesAndDiscoversUnderThatAccount() = runTest {
        val f = Fixture(this); val account = f.account(a); f.accounts.value = account
        f.configure = {c -> c.send = {assertEquals(c.issued.last().bindingId, f.store.value!!.intent.bindingId); assertFalse(f.store.value!!.acknowledged); PushRegistrationResult.APPLIED}}
        try {
            f.owner.start(); f.owner.start(); runCurrent()
            assertEquals(1, f.clients.size); assertEquals(1, f.clients.single().second.issued.size)
            assertTrue(f.store.value!!.acknowledged); assertEquals(a, f.store.value!!.actorId)
            assertEquals(listOf(account), f.registered)
            f.owner.close(); assertTrue(f.root.isActive); assertTrue(account.job.isActive); assertTrue(f.clients.single().second.closed)
        } finally {f.close()}
    }
    @Test fun unavailableRetriesExactDurableIntentAndTokenHintAdvancesTheBinding() = runTest {
        val f = Fixture(this); f.accounts.value = f.account(a)
        f.configure = {c -> c.send = {if (c.issued.size == 1) PushRegistrationResult.UNAVAILABLE else PushRegistrationResult.APPLIED}}
        try {
            f.owner.start(); runCurrent(); val client = f.clients.single().second
            assertFalse(f.store.value!!.acknowledged); assertTrue(f.registered.isEmpty())
            f.pulses.emit(Unit); runCurrent()
            assertEquals(2, client.issued.size); assertEquals(client.issued[0].bindingId, client.issued[1].bindingId)
            assertTrue(f.store.value!!.acknowledged)
            f.tokens.value = "changed-token"; f.hints.emit(Unit); runCurrent()
            assertEquals(3, client.issued.size); assertEquals("changed-token", client.issued.last().token)
            assertEquals(2L, client.issued.last().generation); assertNotEquals(client.issued.first().bindingId, client.issued.last().bindingId)
        } finally {f.close()}
    }
    @Test fun staleJwtReceiptAdvancesVersionAndTokenConflictActuallyRenewsToken() = runTest {
        val f = Fixture(this); f.accounts.value = f.account(a)
        f.configure = {c -> c.send = {when(c.issued.size) {1 -> PushRegistrationResult.STALE; 2 -> PushRegistrationResult.TOKEN_CONFLICT; else -> PushRegistrationResult.APPLIED}}}
        try {
            f.owner.start(); runCurrent(); val client = f.clients.single().second
            assertEquals(1, client.issued.size); assertEquals(2L, f.store.value!!.intent.generation)
            f.pulses.emit(Unit); runCurrent()
            assertEquals(1, f.tokens.rotations); assertEquals("renewed-token", f.store.value!!.intent.token)
            f.pulses.emit(Unit); runCurrent()
            assertEquals(3, client.issued.size); assertEquals(3L, client.issued.last().generation); assertTrue(f.store.value!!.acknowledged)
        } finally {f.close()}
    }
    @Test fun newAccountRegistersWithoutJoiningAnOldActuallyIssuedUncooperativeRequest() = runTest {
        val f = Fixture(this); val old = f.account(a); val fresh = f.account(b)
        val release = CompletableDeferred<Unit>(); var returned = false
        f.configure = {c -> if (f.clients.isEmpty()) c.send = {withContext(NonCancellable) {release.await(); returned = true; PushRegistrationResult.APPLIED}}}
        try {
            f.accounts.value = old; f.owner.start(); runCurrent(); assertEquals(1, f.clients.single().second.issued.size)
            old.job.cancel(); f.accounts.value = fresh; runCurrent()
            assertEquals(2, f.clients.size); assertEquals(1, f.clients.last().second.issued.size)
            assertTrue(f.clients.first().second.closed); assertEquals(b, f.store.value!!.actorId); assertTrue(f.store.value!!.acknowledged)
            val newest = f.store.value; release.complete(Unit); runCurrent()
            assertTrue(returned); assertSame(newest, f.store.value); assertEquals(listOf(fresh), f.registered)
        } finally {release.complete(Unit); f.close()}
    }
    @Test fun startupNullPreservesRestoreBindingButActualLogoutPersistsNullToken() = runTest {
        val f = Fixture(this); val persisted = f.ledger.prepare(a, "initial-token"); f.ledger.acknowledge(persisted)
        val before = f.store.value
        try {
            f.owner.start(); runCurrent(); assertSame(before, f.store.value); assertTrue(f.clients.isEmpty())
            val account = f.account(a); f.accounts.value = account; runCurrent()
            assertEquals(2L, f.store.value!!.intent.generation); assertTrue(f.store.value!!.acknowledged)
            account.job.cancel(); f.accounts.value = null; runCurrent()
            assertNull(f.store.value!!.intent.token); assertEquals(3L, f.store.value!!.intent.generation)
            assertFalse(f.store.value!!.acknowledged); assertTrue(f.clients.single().second.closed)
            f.hints.emit(Unit); f.pulses.emit(Unit); runCurrent(); assertEquals(1, f.clients.single().second.issued.size)
        } finally {f.close()}
    }
    @Test fun rootCancellationRetiresClientAndStopsMetadataWithoutCancellingBorrowedAccount() = runTest {
        val f = Fixture(this); val independent = Job()
        val account = SocialAuthenticatedAccount(SocialUserId(a), independent, f.root + f.dispatcher) {"token"}
        try {
            f.accounts.value = account; f.owner.start(); runCurrent(); assertEquals(1, f.clients.single().second.issued.size)
            f.root.cancel(); runCurrent(); assertTrue(f.clients.single().second.closed); assertTrue(independent.isActive)
            f.hints.emit(Unit); runCurrent(); assertEquals(1, f.clients.single().second.issued.size)
        } finally {independent.cancel(); f.close()}
    }
    @Test fun sdkActivationFailureCannotKillAccountCollectionOrPreventOwnerShutdown() = runTest {
        val f = Fixture(this); val account = f.account(a)
        try {
            f.tokens.activationFails = true; f.accounts.value = account; f.owner.start(); runCurrent()
            assertEquals(1, f.clients.size); assertEquals(1, f.clients.single().second.issued.size)
            assertTrue(f.store.value!!.acknowledged)
            f.owner.close(); runCurrent()
            assertTrue(f.clients.single().second.closed); assertTrue(f.root.isActive); assertTrue(account.job.isActive)
            assertEquals(listOf(account.job), f.root.children.toList())
        } finally {f.tokens.activationFails = false; f.close()}
    }
}
