package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTeardownOwnerTest {
    @Test fun successful_cleanup_is_once_only_and_fences_before_native_work() = runTest {
        val issued = mutableListOf<String>()
        lateinit var owner: SessionTeardownOwner
        owner = SessionTeardownOwner {
            assertTrue(owner.retired)
            issued += "jobs"
            yield()
            issued += "disconnect"
            issued += "reset"
        }
        owner.run()
        owner.run()
        assertEquals(listOf("jobs", "disconnect", "reset"), issued)
    }

    @Test fun caller_cancellation_during_join_cannot_abandon_disconnect_or_reset() = runTest {
        val gate = CompletableDeferred<Unit>()
        val issued = mutableListOf<String>()
        val owner = SessionTeardownOwner {
            issued += "jobs"
            gate.await()
            issued += "disconnect"
            issued += "reset"
        }
        val close = launch { owner.run() }
        try {
            runCurrent()
            assertEquals(listOf("jobs"), issued, "Driver must reach the suspended native join")
            assertTrue(owner.retired)
            close.cancel()
            gate.complete(Unit)
            close.join()
            assertEquals(listOf("jobs", "disconnect", "reset"), issued)
            owner.run()
            assertEquals(3, issued.size)
        } finally { gate.complete(Unit); close.cancelAndJoin() }
    }

    @Test fun another_cleanup_waits_original_native_ack_even_after_its_caller_cancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        var entered = 0
        var completed = 0
        val owner = SessionTeardownOwner { entered++; gate.await(); completed++ }
        val first = launch { owner.run() }
        var second: Deferred<Unit>? = null
        try {
            runCurrent()
            assertEquals(1, entered)
            first.cancel()
            second = async { owner.run() }
            runCurrent()
            assertFalse(second.isCompleted, "Retirement alone cannot stand in for native cleanup ACK")
            gate.complete(Unit)
            first.join()
            second.await()
            assertEquals(1, entered)
            assertEquals(1, completed)
        } finally { gate.complete(Unit); first.cancelAndJoin(); second?.cancelAndJoin() }
    }

    @Test fun failed_native_cleanup_remains_retired_but_retryable() = runTest {
        var calls = 0
        var fail = true
        val owner = SessionTeardownOwner {
            calls++
            if (fail) error("Native reset failed")
        }
        assertFailsWith<IllegalStateException> { owner.run() }
        assertTrue(owner.retired)
        fail = false
        owner.run()
        assertEquals(2, calls, "The retired flag must not suppress unfinished cleanup")
        owner.run()
        assertEquals(2, calls)
    }

    @Test fun already_cancelled_caller_still_completes_an_accepted_native_negative() = runTest {
        val issued = mutableListOf<String>()
        val owner = SessionTeardownOwner {
            issued += "jobs"
            yield()
            issued += "disconnect"
            issued += "reset"
        }
        val close = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            owner.run()
        }
        close.join()
        assertEquals(listOf("jobs", "disconnect", "reset"), issued)
        assertTrue(owner.retired)
    }
}
