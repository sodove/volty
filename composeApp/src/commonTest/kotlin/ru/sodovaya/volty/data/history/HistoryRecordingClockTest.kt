package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.history.HistoryTick
import ru.sodovaya.volty.domain.history.historyAxisEpochMillis
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryRecordingClockTest {
    @Test fun recoveryDeadlineLeavesClockUnavailableAndAllowsFreshRetry() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val clock = HistoryRecordingClock({ calls++; if (calls == 1) release.await(); 9L }, { 1L }, { 2L }, 100)
        val attempt = backgroundScope.async { runCatching { clock.prepare() } }
        try {
            runCurrent(); assertEquals(1, calls)
            advanceTimeBy(100); runCurrent()
            assertTrue(attempt.isCompleted, "Preparation must not indefinitely hold native startup")
            assertIs<TimeoutCancellationException>(attempt.await().exceptionOrNull())
            assertFailsWith<IllegalStateException> { clock.readTick() }
            clock.prepare()
            assertEquals(2, calls)
            assertEquals(9L, clock.readTick().clockGeneration)
        } finally { release.complete(Unit); attempt.cancelAndJoin() }
    }

    @Test fun waiterDeadlineIncludesMutexWaitAndCannotCancelOriginalRecovery() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val clock = HistoryRecordingClock({
            calls++; withContext(NonCancellable) { release.await() }; 10L
        }, { 1L }, { 2L }, 100)
        val first = backgroundScope.async { runCatching { clock.prepare() } }
        var waiter: kotlinx.coroutines.Deferred<Result<Unit>>? = null
        try {
            runCurrent(); assertEquals(1, calls)
            advanceTimeBy(20)
            waiter = backgroundScope.async { runCatching { clock.prepare() } }
            runCurrent(); advanceTimeBy(100); runCurrent()
            assertTrue(waiter.isCompleted, "A queued caller needs its own finite deadline")
            assertIs<TimeoutCancellationException>(waiter.await().exceptionOrNull())
            assertEquals(1, calls, "A waiter cannot start parallel database recovery")
            assertFalse(first.isCompleted, "Original native cleanup still owns its noncancellable work")
        } finally { release.complete(Unit); first.cancelAndJoin(); waiter?.cancelAndJoin() }
        assertFailsWith<IllegalStateException> { clock.readTick() }
        clock.prepare()
        assertEquals(2, calls)
        assertEquals(10L, clock.readTick().clockGeneration)
    }

    @Test fun lateNoncancellableRecoveryAcknowledgementCannotPublishExpiredClock() = runTest {
        val release = CompletableDeferred<Unit>()
        val clock = HistoryRecordingClock({ withContext(NonCancellable) { release.await() }; 11L }, { 1L }, { 2L }, 100)
        val attempt = backgroundScope.async { runCatching { clock.prepare() } }
        try {
            runCurrent(); advanceTimeBy(101); runCurrent()
            assertFailsWith<IllegalStateException> { clock.readTick() }
            release.complete(Unit); runCurrent()
            assertIs<TimeoutCancellationException>(attempt.await().exceptionOrNull())
            assertFailsWith<IllegalStateException> { clock.readTick() }
        } finally { release.complete(Unit); attempt.cancelAndJoin() }
        clock.prepare()
        assertEquals(11L, clock.readTick().clockGeneration)
    }

    @Test fun preparationRequiresPositiveDeadline() {
        for (budget in listOf(0L, -1L)) assertFailsWith<IllegalArgumentException> {
            HistoryRecordingClock({ 1L }, { 1L }, { 1L }, budget)
        }
    }

    @Test fun ticksUseNativeBootElapsedAndPreserveWallChangesAfterSinglePreparation() = runTest {
        var wall = 10000L
        var bootElapsed = 987654L
        var preparations = 0
        val clock = HistoryRecordingClock({ preparations++; 7L }, { wall }, { bootElapsed })
        assertFailsWith<IllegalStateException> { clock.readTick() }
        clock.prepare()
        val origin = clock.readTick()
        assertEquals(HistoryTick(10000, 987654, 7), origin)
        wall = 5000L; bootElapsed += 200
        val next = clock.readTick()
        assertEquals(HistoryTick(5000, 987854, 7), next)
        assertEquals(10200L, historyAxisEpochMillis(origin, next))
        clock.prepare()
        assertEquals(1, preparations)
    }

    @Test fun concurrentPreparationDoesNotExposeClockBeforeRecoveryAcknowledgement() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var preparations = 0
        val clock = HistoryRecordingClock({
            preparations++; entered.complete(Unit); release.await(); 12L
        }, { 10000L }, { 50000L })
        val first = backgroundScope.async { clock.prepare() }
        val second = backgroundScope.async { clock.prepare() }
        try {
            runCurrent(); assertTrue(entered.isCompleted)
            assertFalse(first.isCompleted); assertFalse(second.isCompleted)
            assertFailsWith<IllegalStateException> { clock.readTick() }
            release.complete(Unit)
            first.await(); second.await()
            assertEquals(1, preparations)
            assertEquals(12L, clock.readTick().clockGeneration)
        } finally {
            release.complete(Unit)
            first.cancelAndJoin(); second.cancelAndJoin()
        }
    }

    @Test fun failedOrCancelledRecoveryNeverPublishesClockAndCanBeRetried() = runTest {
        var fail = true
        val release = CompletableDeferred<Unit>()
        var entered = false
        val clock = HistoryRecordingClock({
            if (fail) error("database unavailable")
            entered = true; release.await(); 8L
        }, { 1L }, { 2L })
        assertFailsWith<IllegalStateException> { clock.prepare() }
        assertFailsWith<IllegalStateException> { clock.readTick() }
        fail = false
        val cancelled = backgroundScope.async { clock.prepare() }
        try {
            runCurrent(); assertTrue(entered)
            cancelled.cancelAndJoin()
            assertFailsWith<IllegalStateException> { clock.readTick() }
        } finally {
            release.complete(Unit)
            cancelled.cancelAndJoin()
        }
        clock.prepare()
        assertEquals(HistoryTick(1, 2, 8), clock.readTick())
    }

    @Test fun invalidGenerationAndNativeElapsedFailClosed() = runTest {
        val invalid = HistoryRecordingClock({ -1L }, { 1L }, { 2L })
        assertFailsWith<IllegalArgumentException> { invalid.prepare() }
        assertFailsWith<IllegalStateException> { invalid.readTick() }
        val invalidElapsed = HistoryRecordingClock({ 1L }, { 1L }, { -1L })
        invalidElapsed.prepare()
        assertFailsWith<IllegalArgumentException> { invalidElapsed.readTick() }
    }
}
