package ru.sodovaya.volty.presentation.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitSnapshotTest {
    @Test fun a_constructor_that_throws_yields_null_not_a_crash() = runTest {
        val result = awaitSnapshotOrNull<String> { _, _ -> throw IllegalStateException("invalid native peer") }
        assertNull(result)
    }

    @Test fun a_cancellation_thrown_while_starting_propagates() = runTest {
        assertFailsWith<CancellationException> {
            awaitSnapshotOrNull<String> { _, _ -> throw CancellationException("stop") }
        }
    }

    @Test fun a_delivered_result_is_returned_and_a_later_failure_is_ignored() = runTest {
        val result = awaitSnapshotOrNull<String> { ok, fail -> ok("bitmap"); fail(); ok("again"); {} }
        assertEquals("bitmap", result)
    }

    @Test fun a_reported_failure_is_null() = runTest {
        assertNull(awaitSnapshotOrNull<String> { _, fail -> fail(); {} })
    }

    @Test fun cancelling_the_caller_cancels_the_native_request_once() = runTest {
        var cancels = 0
        val job = launch { awaitSnapshotOrNull<String> { _, _ -> { cancels++ } } }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(1, cancels)
        assertTrue(job.isCancelled)
    }

    @Test fun a_dead_peer_whose_cancel_throws_does_not_escape() = runTest {
        val job = launch { awaitSnapshotOrNull<String> { _, _ -> { throw IllegalStateException("invalid native peer") } } }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(job.isCancelled)
    }
}
