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
        var onRegistered: suspend (SocialAuthenticatedAccount) -> Unit = {}
        var configure: (Client) -> Unit = {}
        val owner = PushRegistrationOwner(accounts, app, dispatcher, pulses, hints, ledger, tokens,
            {account -> Client().also {configure(it); clients += account to it}}, {registered += it; onRegistered(it)})
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
            assertEquals(3, client.issued.size)
            assertEquals(1, f.tokens.rotations); assertEquals("renewed-token", f.store.value!!.intent.token)
            assertEquals(3, client.issued.size); assertEquals(3L, client.issued.last().generation); assertTrue(f.store.value!!.acknowledged)
        } finally {f.close()}
    }
    @Test fun coldWakeWaitsForIssuedRegistrationAndCompletedFreshDiscovery() = runTest {
        val f = Fixture(this); val account = f.account(a)
        val http = CompletableDeferred<Unit>(); val discovery = CompletableDeferred<Unit>()
        val restored = f.ledger.prepare(a, "initial-token"); assertTrue(f.ledger.acknowledge(restored))
        f.configure = {c -> c.send = {http.await(); PushRegistrationResult.APPLIED}}
        f.onRegistered = {discovery.await()}
        try {
            f.accounts.value = account; f.owner.start(); runCurrent(); val client = f.clients.single().second
            assertEquals(1, client.issued.size); assertEquals(2L, f.store.value!!.intent.generation); assertFalse(f.store.value!!.acknowledged)
            val wake = async {f.owner.wake(account)}; runCurrent(); assertFalse(wake.isCompleted)
            http.complete(Unit); runCurrent(); assertTrue(f.store.value!!.acknowledged); assertEquals(listOf(account), f.registered)
            assertFalse(wake.isCompleted); discovery.complete(Unit)
            assertTrue(wake.await()); assertEquals(1, client.issued.size); assertTrue(account.job.isActive)
        } finally {http.complete(Unit); discovery.complete(Unit); f.close()}
    }

    @Test fun pushWakeRetriesFailedDurableIntentWithoutWaitingForPeriodicPulse() = runTest {
        val f = Fixture(this); val account = f.account(a)
        f.configure = {c -> c.send = {if(c.issued.size==1) PushRegistrationResult.UNAVAILABLE else PushRegistrationResult.APPLIED}}
        try {
            f.accounts.value = account; f.owner.start(); runCurrent(); val client = f.clients.single().second
            assertEquals(1, client.issued.size); assertFalse(f.store.value!!.acknowledged)
            val first = client.issued.single()
            assertTrue(f.owner.wake(account)); assertEquals(2, client.issued.size)
            assertEquals(first, client.issued.last()); assertTrue(f.store.value!!.acknowledged); assertEquals(listOf(account), f.registered)
        } finally {f.close()}
    }

    @Test fun closingOwnerReleasesHeldWakeWithoutJoiningOrCancellingBorrowedAccount() = runTest {
        val f = Fixture(this); val independent = Job(); val release = CompletableDeferred<Unit>(); var returned = false
        val account = SocialAuthenticatedAccount(SocialUserId(a), independent, f.root+f.dispatcher) {"token"}
        f.configure = {c -> c.send = {if(c.issued.size==1) PushRegistrationResult.APPLIED else withContext(NonCancellable) {release.await(); returned=true; PushRegistrationResult.APPLIED}}}
        try {
            f.accounts.value = account; f.owner.start(); runCurrent(); val client = f.clients.single().second
            val wake = async {f.owner.wake(account)}; runCurrent(); assertEquals(2, client.issued.size); assertFalse(wake.isCompleted)
            f.owner.close(); runCurrent(); assertFalse(wake.await()); assertTrue(client.closed); assertTrue(independent.isActive)
            release.complete(Unit); runCurrent(); assertTrue(returned); assertEquals(listOf(account), f.registered)
        } finally {release.complete(Unit); independent.cancel(); f.close()}
    }

    @Test fun wakeArrivingDuringFailedInitialHttpRetriesBeforeItCompletes() = runTest {
        val f = Fixture(this); val account = f.account(a); val http = CompletableDeferred<Unit>()
        f.configure = {c -> c.send = {if(c.issued.size==1) {http.await(); PushRegistrationResult.UNAVAILABLE} else PushRegistrationResult.APPLIED}}
        try {
            f.accounts.value = account; f.owner.start(); runCurrent(); val client = f.clients.single().second
            assertEquals(1, client.issued.size)
            val wake = async {f.owner.wake(account)}; runCurrent(); assertFalse(wake.isCompleted)
            http.complete(Unit); assertTrue(wake.await())
            assertEquals(2, client.issued.size); assertSame(client.issued.first(), client.issued.last())
            assertTrue(f.store.value!!.acknowledged); assertEquals(listOf(account), f.registered)
        } finally {http.complete(Unit); f.close()}
    }
    @Test fun cancelledColdWakeStillOwnsItsRetryAfterInitialNetworkFailure() = runTest {
        val f = Fixture(this); val account = f.account(a); val http = CompletableDeferred<Unit>()
        f.configure = {c -> c.send = {if(c.issued.size==1) {http.await(); PushRegistrationResult.UNAVAILABLE} else PushRegistrationResult.APPLIED}}
        try {
            f.accounts.value = account; f.owner.start(); runCurrent(); val client = f.clients.single().second
            assertEquals(1, client.issued.size)
            val wake = async {f.owner.wake(account)}; runCurrent(); assertFalse(wake.isCompleted)
            wake.cancel(); runCurrent(); assertTrue(wake.isCancelled); assertTrue(account.job.isActive)
            http.complete(Unit); runCurrent()
            assertEquals(2, client.issued.size); assertSame(client.issued.first(), client.issued.last())
            assertTrue(f.store.value!!.acknowledged); assertEquals(listOf(account), f.registered)
            assertTrue(f.root.isActive); assertTrue(account.job.isActive)
        } finally {http.complete(Unit); f.close()}
    }
    @Test fun actualReceiverWaitsForColdDiscoveryEvenWhenOldPushBindingIsRetired() = runTest {
        val f = Fixture(this); val account = f.account(a)
        val http = CompletableDeferred<Unit>(); val discovery = CompletableDeferred<Unit>(); var verified = 0
        val old = f.ledger.prepare(a, "initial-token"); f.ledger.acknowledge(old)
        val gate = IncomingCallPushGate({f.accounts.value}, f.ledger) {_, _, _ -> verified++; true}
        val receiver = IncomingCallPushWake(f.accounts, f.owner, gate)
        val payload = mapOf("type" to "nearby_call", "callId" to a, "revision" to "1",
            "bindingId" to old.intent.bindingId, "generation" to old.intent.generation.toString())
        f.configure = {c -> c.send = {http.await(); PushRegistrationResult.APPLIED}}
        f.onRegistered = {discovery.await()}
        try {
            f.accounts.value = account; f.owner.start(); runCurrent()
            val receive = async {receiver.receive(payload)}; runCurrent()
            assertEquals(1, f.clients.single().second.issued.size); assertFalse(receive.isCompleted)
            http.complete(Unit); runCurrent(); assertEquals(listOf(account), f.registered); assertFalse(receive.isCompleted)
            discovery.complete(Unit); assertFalse(receive.await()); assertEquals(0, verified)
            assertTrue(account.job.isActive)
        } finally {http.complete(Unit); discovery.complete(Unit); f.close()}
    }
    @Test fun cancellingActualReceiverCannotCancelBorrowedRegistrationAndDiscovery() = runTest {
        val f = Fixture(this); val account = f.account(a); val http = CompletableDeferred<Unit>()
        f.configure = {c -> c.send = {http.await(); PushRegistrationResult.APPLIED}}
        val receiver = IncomingCallPushWake(f.accounts, f.owner,
            IncomingCallPushGate({f.accounts.value}, f.ledger) {_, _, _ -> true})
        try {
            f.accounts.value = account; f.owner.start(); runCurrent()
            val intent = f.clients.single().second.issued.single()
            val receive = async {receiver.receive(mapOf("type" to "nearby_call", "callId" to a, "revision" to "1",
                "bindingId" to intent.bindingId, "generation" to intent.generation.toString()))}
            runCurrent(); assertFalse(receive.isCompleted); receive.cancel(); runCurrent()
            assertTrue(receive.isCancelled); assertTrue(account.job.isActive); assertTrue(f.root.isActive)
            http.complete(Unit); runCurrent(); assertTrue(f.store.value!!.acknowledged)
            assertEquals(listOf(account), f.registered); assertEquals(1, f.clients.single().second.issued.size)
        } finally {http.complete(Unit); f.close()}
    }
    @Test fun repeatedStaleReceiptsBoundImmediateAttemptsAndNeverAcknowledge() = runTest {
        val f = Fixture(this); f.accounts.value = f.account(a)
        f.configure = {c -> c.send = {PushRegistrationResult.STALE}}
        try {
            f.owner.start(); runCurrent(); val client = f.clients.single().second
            assertEquals(3, client.issued.size); assertEquals(4L, f.store.value!!.intent.generation)
            assertFalse(f.store.value!!.acknowledged); assertTrue(f.registered.isEmpty())
            runCurrent(); assertEquals(3, client.issued.size)
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
