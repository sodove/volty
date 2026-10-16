package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DetachedSessionCleanupTest {
    @Test fun successful_batch_retires_each_original_after_its_native_ack() = runTest {
        val issued = mutableListOf<String>()
        completeDetachedSessionCleanup(listOf(1, 2), { issued += "close$it" }, { issued += "retire$it" })
        assertEquals(listOf("close1", "retire1", "close2", "retire2"), issued)
    }

    @Test fun caller_cancellation_cannot_abandon_the_rest_of_an_accepted_batch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val issued = mutableListOf<String>()
        val task = launch {
            completeDetachedSessionCleanup(listOf(1, 2), {
                issued += "close$it"
                if (it == 1) gate.await()
                issued += "ack$it"
            }, { issued += "retire$it" })
        }
        try {
            runCurrent()
            assertEquals(listOf("close1"), issued)
            task.cancel()
            gate.complete(Unit)
            task.join()
            assertEquals(listOf("close1", "ack1", "retire1", "close2", "ack2", "retire2"), issued)
        } finally { gate.complete(Unit); task.cancelAndJoin() }
    }

    @Test fun native_failure_still_attempts_every_retirement_and_preserves_all_errors() = runTest {
        val issued = mutableListOf<String>()
        val native = IllegalStateException("native")
        val recording = IllegalArgumentException("recording")
        val failure = assertFailsWith<IllegalStateException> {
            completeDetachedSessionCleanup(listOf(1, 2), {
                issued += "close$it"
                if (it == 1) throw native
            }, {
                issued += "retire$it"
                if (it == 1) throw recording
            })
        }
        // JVM coroutine stack recovery may copy the exception at withContext's boundary.
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === native })
        assertEquals(listOf(recording), native.suppressedExceptions)
        assertEquals(listOf("close1", "retire1", "close2", "retire2"), issued)
    }

    @Test fun recording_failure_cannot_abandon_later_detached_native_sessions() = runTest {
        val issued = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            completeDetachedSessionCleanup(listOf(1, 2), { issued += "close$it" }, {
                issued += "retire$it"
                if (it == 1) error("recording")
            })
        }
        assertEquals(listOf("close1", "retire1", "close2", "retire2"), issued)
    }

    @Test fun already_cancelled_caller_still_completes_the_entire_detached_batch() = runTest {
        val issued = mutableListOf<Int>()
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            completeDetachedSessionCleanup(listOf(1, 2), { yield(); issued += it })
        }
        task.join()
        assertEquals(listOf(1, 2), issued)
    }
}
