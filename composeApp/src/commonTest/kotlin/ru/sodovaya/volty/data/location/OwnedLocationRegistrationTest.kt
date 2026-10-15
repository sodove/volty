package ru.sodovaya.volty.data.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedLocationRegistrationTest {
    @Test fun identical_applied_request_keeps_exact_listener() {
        val f = Fixture()
        assertTrue(f.owner.reconcile("ordinary"))
        assertFalse(f.owner.reconcile("ordinary"))
        assertEquals(listOf("register:ordinary:1"), f.events)
        assertTrue(f.owner.active)
    }

    @Test fun replacement_releases_original_before_any_new_positive_call() {
        val f = Fixture()
        f.owner.reconcile("ordinary")
        f.owner.reconcile("history")
        assertEquals(listOf("register:ordinary:1", "remove:1", "register:history:2"), f.events)
        assertEquals("history", f.owner.key)
    }

    @Test fun failed_release_fences_callbacks_and_retains_original_for_negative_retry() {
        val f = Fixture()
        f.owner.reconcile("history")
        f.cleanupFails = true
        assertFailsWith<IllegalStateException> { f.owner.reconcile(null) }
        assertFalse(f.owner.active, "Callbacks remain fenced while native cleanup is unacknowledged")
        assertEquals("history", f.owner.key)
        f.cleanupFails = false
        f.owner.reconcile(null)
        assertEquals(listOf("register:history:1", "remove:1", "remove:1"), f.events)
        assertEquals(null, f.owner.key)
    }

    @Test fun failed_release_blocks_replacement_and_retries_exact_original() {
        val f = Fixture()
        f.owner.reconcile("ordinary")
        f.cleanupFails = true
        assertFailsWith<IllegalStateException> { f.owner.reconcile("history") }
        assertEquals(1, f.created)
        assertFalse(f.owner.active)
        f.cleanupFails = false
        f.owner.reconcile("history")
        assertEquals(listOf("register:ordinary:1", "remove:1", "remove:1", "register:history:2"), f.events)
    }

    @Test fun partial_registration_and_failed_rollback_retain_candidate_until_cleanup_ack() {
        val f = Fixture()
        f.registrationFails = true
        f.cleanupFails = true
        val error = assertFailsWith<IllegalArgumentException> { f.owner.reconcile("history") }
        assertSame(f.registrationError, error)
        assertFalse(f.owner.active)
        assertEquals("history", f.owner.key, "A partially registered candidate must not be orphaned")
        assertEquals(1, error.suppressedExceptions.size)
        f.registrationFails = false
        f.cleanupFails = false
        f.owner.reconcile("history")
        assertEquals(listOf("register:history:1", "remove:1", "remove:1", "register:history:2"), f.events)
        assertTrue(f.owner.active)
    }

    @Test fun partial_registration_with_successful_rollback_can_retry_same_request() {
        val f = Fixture()
        f.registrationFails = true
        assertFailsWith<IllegalArgumentException> { f.owner.reconcile("history") }
        assertEquals(null, f.owner.key)
        assertFalse(f.owner.active)
        f.registrationFails = false
        f.owner.reconcile("history")
        assertEquals(listOf("register:history:1", "remove:1", "register:history:2"), f.events)
        assertTrue(f.owner.active)
    }

    @Test fun retirement_fences_before_calling_native_remove() {
        val f = Fixture()
        f.owner.reconcile("history")
        var removalObserved = false
        f.beforeRemove = {
            removalObserved = true
            assertFalse(f.owner.active)
            assertEquals("history", f.owner.key)
        }
        f.owner.reconcile(null)
        assertTrue(removalObserved)
    }

    @Test fun acquisition_ack_is_published_only_after_all_positive_calls_return() {
        val f = Fixture()
        f.beforeRegister = {
            assertFalse(f.owner.active)
            assertEquals("history", f.owner.key, "Candidate ownership must precede first native call")
        }
        f.owner.reconcile("history")
        assertTrue(f.owner.active)
    }

    @Test fun already_released_negative_is_idempotent() {
        val f = Fixture()
        f.owner.reconcile("history")
        f.owner.reconcile(null)
        assertFalse(f.owner.reconcile(null))
        assertEquals(listOf("register:history:1", "remove:1"), f.events)
        assertFalse(f.owner.active)
    }

    @Test fun stale_request_cannot_replace_newer_native_owner() {
        val f = Fixture()
        f.owner.reconcile("newer")
        assertFalse(f.owner.reconcile("retired", isCurrent = { false }))
        assertEquals(listOf("register:newer:1"), f.events)
        assertEquals("newer", f.owner.key)
        assertTrue(f.owner.active)
    }

    @Test fun withdrawal_during_old_release_prevents_new_allocation() {
        val f = Fixture()
        f.owner.reconcile("ordinary")
        var current = true
        f.beforeRemove = { current = false }
        f.owner.reconcile("history", isCurrent = { current })
        assertEquals(listOf("register:ordinary:1", "remove:1"), f.events)
        assertEquals(1, f.created, "Recheck admission after original cleanup ACK")
        assertEquals(null, f.owner.key)
        assertFalse(f.owner.active)
    }

    private class Fixture {
        val events = mutableListOf<String>()
        var created = 0
        var registrationFails = false
        var cleanupFails = false
        var beforeRemove: () -> Unit = {}
        var beforeRegister: () -> Unit = {}
        val registrationError = IllegalArgumentException("partial provider registration")
        val owner = OwnedLocationRegistration<String, Int>(
            create = { ++created },
            register = { key, listener ->
                events += "register:$key:$listener"
                beforeRegister()
                if (registrationFails) throw registrationError
            },
            unregister = { listener ->
                events += "remove:$listener"
                beforeRemove()
                if (cleanupFails) error("native release failed")
            },
        )
    }
}
