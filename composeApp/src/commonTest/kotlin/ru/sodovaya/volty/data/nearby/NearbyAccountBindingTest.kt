package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAccountBindingTest {
    private val actor = "a0000000-0000-0000-0000-000000000001"
    private val other = "b0000000-0000-0000-0000-000000000002"

    @Test fun refreshIsBoundToTheOriginalActorAndCannotSubstituteAnotherAccount() {
        val binding = NearbyAccountBinding(NearbyAccountCredentials(actor, "first-secret"))
        binding.refresh(NearbyAccountCredentials(actor, "refreshed-secret"))
        assertEquals("refreshed-secret", binding.bearer())
        assertFailsWith<IllegalArgumentException> { binding.refresh(NearbyAccountCredentials(other, "foreign-secret")) }
        assertEquals(actor, binding.actorId)
        assertEquals("refreshed-secret", binding.bearer())
    }

    @Test fun retiredBindingCannotBeRevivedAndIndependentLoginHasItsOwnCredential() {
        val old = NearbyAccountBinding(NearbyAccountCredentials(actor, "old-secret"))
        old.retire(); old.retire()
        val fresh = NearbyAccountBinding(NearbyAccountCredentials(actor, "fresh-secret"))
        assertNull(old.bearer())
        assertFailsWith<IllegalStateException> { old.refresh(NearbyAccountCredentials(actor, "replacement-secret")) }
        assertNull(old.bearer())
        assertEquals("fresh-secret", fresh.bearer())
        assertFalse(old.toString().contains("old-secret"))
        assertFalse(NearbyAccountCredentials(actor, "private-secret").toString().contains("private-secret"))
    }

    @Test fun malformedActorOrEmptyCredentialCannotCreateABinding() {
        for (id in listOf("", actor.uppercase(), "$actor/else")) {
            assertFailsWith<IllegalArgumentException> { NearbyAccountCredentials(id, "private-secret") }
        }
        assertFailsWith<IllegalArgumentException> { NearbyAccountCredentials(actor, " ") }
    }

    @Test fun borrowedJobCancellationFencesBindingBeforeChildCompletesAndFreshLifetimeWorks() = runTest {
        val accountJob = Job()
        val old = NearbyAccountBinding(NearbyAccountCredentials(actor, "old-secret"), accountJob)
        assertTrue(accountJob.children.none(), "The binding must not create a child Job")

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
            assertTrue(accountJob.isCancelled)
            assertFalse(accountJob.isCompleted, "The held child keeps the cancelled account Job incomplete")
            assertNull(old.bearer(), "Cancellation must deny bearer checkout immediately")
            assertFailsWith<IllegalStateException> {
                old.refresh(NearbyAccountCredentials(actor, "replacement-secret"))
            }

            val freshJob = Job()
            val fresh = NearbyAccountBinding(NearbyAccountCredentials(actor, "fresh-secret"), freshJob)
            assertTrue(freshJob.children.none(), "A fresh binding must not create a child Job")
            assertEquals("fresh-secret", fresh.bearer())
            fresh.refresh(NearbyAccountCredentials(actor, "fresh-refreshed-secret"))
            assertEquals("fresh-refreshed-secret", fresh.bearer())
            fresh.retire()
            assertTrue(freshJob.isActive, "Retiring a binding must leave its borrowed Job active")
            freshJob.cancel()
        } finally {
            releaseChild.complete(Unit)
            accountJob.cancel()
            runCurrent()
        }
    }
}
