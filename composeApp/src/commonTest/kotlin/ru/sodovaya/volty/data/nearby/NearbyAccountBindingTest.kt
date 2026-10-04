package ru.sodovaya.volty.data.nearby

import kotlin.test.*

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
}
