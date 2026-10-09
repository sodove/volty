package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallPushGateTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val call = "20000000-0000-0000-0000-000000000002"
    private class Store : PushInstallationStore {
        var state: PushInstallationSnapshot? = null; var reads = 0; var release: CompletableDeferred<Unit>? = null
        override suspend fun read(): PushInstallationSnapshot? {
            reads++; val captured = state; release?.let {withContext(NonCancellable) {it.await()}}; return captured
        }
        override suspend fun write(snapshot: PushInstallationSnapshot) {state = snapshot}
    }
    private class Ids : PushInstallationIdentityGenerator {
        var n = 0
        override fun uuid() = "30000000-0000-0000-0000-" + (++n).toString().padStart(12, '0')
        override fun capability() = "a".repeat(64)
    }
    private inner class Fixture(scope: TestScope) {
        val root = Job(); val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val account = SocialAuthenticatedAccount(SocialUserId(actor), Job(root), root + dispatcher) {"token"}
        val current = MutableStateFlow<SocialAuthenticatedAccount?>(account)
        val store = Store(); val ledger = PushInstallationLedger(store, Ids())
        var issued = 0; var verification: suspend () -> Boolean = {true}
        var publicationFence: (suspend () -> Boolean)? = null
        val gate = IncomingCallPushGate({current.value}, ledger) {bound, id, fence ->
            publicationFence = fence
            assertSame(account, bound); assertEquals(call, id); issued++; verification()
        }
        suspend fun bind(): PushInstallationSnapshot {
            val snapshot = ledger.prepare(actor, "private-device-token")
            assertTrue(ledger.acknowledge(snapshot)); return store.state!!
        }
        fun hint(snapshot: PushInstallationSnapshot, generation: Long = snapshot.intent.generation,
            binding: String = snapshot.intent.bindingId): IncomingCallPushHint = assertNotNull(IncomingCallPushHint.decode(mapOf(
                "type" to "nearby_call", "callId" to call, "revision" to "1", "generation" to generation.toString(), "bindingId" to binding)))
        fun close() {store.release?.complete(Unit); root.cancel()}
    }
    @Test fun publicationFenceRejectsChangedBindingAndAccountBeforeTheVerifierPublishes() = runTest {
        val f = Fixture(this)
        try {
            val snapshot = f.bind()
            f.verification = {
                val fence = assertNotNull(f.publicationFence)
                assertTrue(fence())
                f.ledger.prepare(actor, "replacement-token")
                assertFalse(fence())
                f.store.state = snapshot
                assertTrue(fence())
                f.current.value = SocialAuthenticatedAccount(SocialUserId(actor), Job(f.root), f.root + f.dispatcher) {"new-token"}
                assertFalse(fence())
                false
            }
            assertFalse(f.gate.receive(f.hint(snapshot))); assertEquals(1, f.issued)
        } finally {f.close()}
    }

    @Test fun exactAcknowledgedLiveBindingActuallyVerifiesCallButDoesNotOverrideBackendDenial() = runTest {
        val f = Fixture(this)
        try {
            val snapshot = f.bind(); assertTrue(f.gate.receive(f.hint(snapshot))); assertEquals(1, f.issued)
            f.verification = {false}; assertFalse(f.gate.receive(f.hint(snapshot))); assertEquals(2, f.issued)
        } finally {f.close()}
    }
    @Test fun deliberatelyUnacknowledgedOrLoggedOutBindingNeverCallsBackend() = runTest {
        val f = Fixture(this)
        try {
            val snapshot = f.bind(); val i = snapshot.intent
            for (state in listOf(
                PushInstallationSnapshot(actor, i, false),
                PushInstallationSnapshot(actor, PushRegistrationIntent(i.installationId, i.installationKey, i.generation, i.bindingId, null), true),
                PushInstallationSnapshot("40000000-0000-0000-0000-000000000004", i, true),
            )) {f.store.state = state; assertFalse(f.gate.receive(f.hint(snapshot)))}
            assertEquals(0, f.issued)
        } finally {f.close()}
    }
    @Test fun wrongBindingOrGenerationCannotRequestEvenFreshBackendState() = runTest {
        val f = Fixture(this)
        try {
            val snapshot = f.bind()
            assertFalse(f.gate.receive(f.hint(snapshot, generation = snapshot.intent.generation + 1)))
            assertFalse(f.gate.receive(f.hint(snapshot, binding = call))); assertEquals(0, f.issued)
            assertTrue(f.gate.receive(f.hint(snapshot))); assertEquals(1, f.issued)
        } finally {f.close()}
    }
    @Test fun logoutDuringActuallyIssuedStorageReadPreventsVerification() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>()
        try {
            val snapshot = f.bind(); f.store.reads = 0; f.store.release = release
            val pending = async {f.gate.receive(f.hint(snapshot))}
            runCurrent(); assertEquals(1, f.store.reads)
            f.account.job.cancel(); f.current.value = null; release.complete(Unit)
            assertFalse(pending.await()); assertEquals(0, f.issued)
        } finally {release.complete(Unit); f.close()}
    }
    @Test fun actualReturnedVerificationCannotPublishToANewSameUserAccount() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); var returned = false
        try {
            val snapshot = f.bind(); f.verification = {withContext(NonCancellable) {release.await(); returned = true; true}}
            val pending = async {f.gate.receive(f.hint(snapshot))}
            runCurrent(); assertEquals(1, f.issued)
            f.current.value = SocialAuthenticatedAccount(SocialUserId(actor), Job(f.root), f.root + f.dispatcher) {"new-token"}
            release.complete(Unit); assertFalse(pending.await()); assertTrue(returned)
        } finally {release.complete(Unit); f.close()}
    }
    @Test fun currentBindingMustStillMatchAfterActuallyReturnedVerification() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); var returned = false
        try {
            val snapshot = f.bind(); f.verification = {withContext(NonCancellable) {release.await(); returned = true; true}}
            val pending = async {f.gate.receive(f.hint(snapshot))}; runCurrent(); assertEquals(1, f.issued)
            f.ledger.prepare(actor, "changed-token")
            release.complete(Unit); assertFalse(pending.await()); assertTrue(returned)
        } finally {release.complete(Unit); f.close()}
    }
}
