package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PushInstallationLedgerTest {
    private val a = "10000000-0000-0000-0000-000000000001"
    private val b = "20000000-0000-0000-0000-000000000002"
    private class Generator : PushInstallationIdentityGenerator {
        var ids = 0; var keys = 0
        override fun uuid() = "30000000-0000-0000-0000-" + (++ids).toString().padStart(12, '0')
        override fun capability(): String {keys++; return "a".repeat(64)}
    }
    private class Store : PushInstallationStore {
        var value: PushInstallationSnapshot? = null
        var writes = 0; var failure = false
        var entered: CompletableDeferred<Unit>? = null; var release: CompletableDeferred<Unit>? = null
        override suspend fun read() = value
        override suspend fun write(snapshot: PushInstallationSnapshot) {
            entered?.complete(Unit); release?.await()
            if (failure) throw IllegalStateException("Controlled persistence failure")
            value = snapshot; writes++
        }
    }
    @Test fun restartTokenSwitchAndLogoutPreserveInstallationAndAdvanceDurably() = runTest {
        val store = Store(); val generator = Generator(); val first = PushInstallationLedger(store, generator)
        val initial = first.prepare(a, "token-a")
        assertSame(initial, store.value); assertEquals(1L, initial.intent.generation); assertFalse(initial.acknowledged)
        assertTrue(first.acknowledge(initial)); assertTrue(first.current()!!.acknowledged)
        val restarted = PushInstallationLedger(store, generator)
        val same = restarted.prepare(a, "token-a")
        assertEquals(initial.intent.bindingId, same.intent.bindingId); assertTrue(same.acknowledged)
        val rotated = restarted.prepare(a, "token-b")
        val switched = restarted.prepare(b, "token-b")
        val logout = restarted.prepare(b, null)
        assertEquals(listOf(2L, 3L, 4L), listOf(rotated, switched, logout).map {it.intent.generation})
        assertEquals(1, generator.keys)
        for (state in listOf(rotated, switched, logout)) {
            assertEquals(initial.intent.installationId, state.intent.installationId)
            assertEquals(initial.intent.installationKey, state.intent.installationKey)
            assertFalse(state.acknowledged)
        }
        assertNull(logout.intent.token); assertSame(logout, store.value)
        assertEquals(4, listOf(initial, rotated, switched, logout).map {it.intent.bindingId}.distinct().size)
        assertFalse(logout.toString().contains("token-b"))
    }
    @Test fun lateAcknowledgementCannotCrossAccountOrTokenReturningToOriginalValue() = runTest {
        val store = Store(); val ledger = PushInstallationLedger(store, Generator())
        val old = ledger.prepare(a, "same-token")
        ledger.prepare(b, "other-token")
        val newest = ledger.prepare(a, "same-token")
        assertFalse(ledger.acknowledge(old)); assertFalse(store.value!!.acknowledged)
        assertTrue(ledger.acknowledge(newest)); assertTrue(store.value!!.acknowledged)
        val renewed = ledger.prepare(a, "same-token", renew = true)
        assertEquals(4L, renewed.intent.generation); assertNotEquals(newest.intent.bindingId, renewed.intent.bindingId)
        assertFalse(ledger.acknowledge(newest))
    }
    @Test fun failedPersistenceDoesNotPublishIntentOrForgetLastAcknowledgedBinding() = runTest {
        val store = Store(); val ledger = PushInstallationLedger(store, Generator())
        val old = ledger.prepare(a, "old"); assertTrue(ledger.acknowledge(old))
        val persisted = store.value
        store.failure = true
        assertFailsWith<IllegalStateException> {ledger.prepare(b, "new")}
        assertSame(persisted, ledger.current())
        store.failure = false
        val retry = ledger.prepare(b, "new")
        assertEquals(2L, retry.intent.generation); assertSame(retry, store.value)
    }
    @Test fun overlappingPreparationWaitsForActualDurableWriteAndUsesItsNextGeneration() = runTest {
        val store = Store(); val ledger = PushInstallationLedger(store, Generator())
        ledger.prepare(a, "first")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.entered = entered; store.release = release
        val second = async {ledger.prepare(a, "second")}
        entered.await()
        val third = async {ledger.prepare(b, "third")}
        runCurrent(); assertEquals(1, store.writes)
        release.complete(Unit)
        assertEquals(2L, second.await().intent.generation)
        assertEquals(3L, third.await().intent.generation)
        assertEquals(b, ledger.current()!!.actorId); assertEquals("third", ledger.current()!!.intent.token)
    }
    @Test fun acknowledgementChecksWholePrivateBindingNotOnlyGeneration() = runTest {
        val store = Store(); val ledger = PushInstallationLedger(store, Generator())
        val actual = ledger.prepare(a, "actual")
        val i = actual.intent
        for (different in listOf(
            PushInstallationSnapshot(b, i, false),
            PushInstallationSnapshot(a, PushRegistrationIntent(i.installationId, "b".repeat(64), i.generation, i.bindingId, i.token), false),
            PushInstallationSnapshot(a, PushRegistrationIntent(i.installationId, i.installationKey, i.generation, i.bindingId, "other"), false),
        )) assertFalse(ledger.acknowledge(different))
        assertFalse(store.value!!.acknowledged); assertTrue(ledger.acknowledge(actual))
    }
    @Test fun exhaustedGenerationFailsWithoutPersistingWrappedOrReplacementIdentity() = runTest {
        val store = Store(); val generator = Generator()
        store.value = PushInstallationSnapshot(a,
            PushRegistrationIntent(generator.uuid(), generator.capability(), Long.MAX_VALUE, generator.uuid(), "old"), true)
        val old = store.value; val ids = generator.ids; val ledger = PushInstallationLedger(store, generator)
        assertFailsWith<IllegalStateException> {ledger.prepare(b, "new")}
        assertSame(old, store.value); assertEquals(0, store.writes); assertEquals(ids, generator.ids)
    }
}
