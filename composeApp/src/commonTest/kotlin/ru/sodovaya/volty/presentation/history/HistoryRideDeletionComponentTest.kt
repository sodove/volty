package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import ru.sodovaya.volty.domain.history.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRideDeletionComponentTest {
    private val start = HistoryTick(1000, 10, 1)
    private val ride = HistoryRide("ride", "bike", start, HistoryTick(2000, 1010, 1), HistoryRideStatus.COMPLETED)
    private fun request(ride: HistoryRide) = HistoryRideDeletionRequest(ride, 2, 3, this)
    private open inner class Repo : HistoryRideDeletionRepository {
        val prepared = mutableListOf<HistoryRide>(); val confirmed = mutableListOf<HistoryRideDeletionRequest>()
        override suspend fun prepare(ride: HistoryRide): HistoryRideDeletionRequest { prepared += ride; return request(ride) }
        override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
            confirmed += request; return HistoryRideDeletionResult(request.ride.favoriteSource(), false)
        }
    }
    private fun TestScope.owner(repo: HistoryRideDeletionRepository?) = HistoryRideDeletionComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo)

    @Test fun confirmationRequiresExplicitPrepareAndCancelNeverDeletes() = runTest {
        val repo = Repo(); val c = owner(repo)
        try {
            c.confirm(); runCurrent(); assertTrue(repo.prepared.isEmpty()); assertTrue(repo.confirmed.isEmpty())
            c.prepare(ride); runCurrent()
            assertEquals(listOf(ride), repo.prepared); val preview = assertNotNull(c.state.value.confirmation)
            assertEquals(2L, preview.favoriteCount); assertEquals(3L, preview.mediaCount)
            c.dismiss(); c.confirm(); runCurrent()
            assertNull(c.state.value.target); assertTrue(repo.confirmed.isEmpty())
        } finally { c.close() }
    }
    @Test fun latePrepareCannotReplaceNewerTarget() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() { override suspend fun prepare(ride: HistoryRide): HistoryRideDeletionRequest {
            if (ride.id == "ride") return withContext(NonCancellable) { entered.complete(Unit); release.await(); super.prepare(ride) }
            return super.prepare(ride)
        } }
        val c = owner(repo)
        try {
            c.prepare(ride); runCurrent(); assertTrue(entered.isCompleted)
            val newer = ride.copy(id = "other"); c.prepare(newer); runCurrent()
            assertEquals(newer, c.state.value.confirmation?.ride)
            release.complete(Unit); runCurrent(); assertEquals(newer, c.state.value.confirmation?.ride)
            assertEquals(listOf(newer, ride), repo.prepared); assertTrue(repo.confirmed.isEmpty())
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun duplicateConfirmAndDismissCannotPretendToCancelCommittedDeletion() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() { override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
            val result = super.confirm(request); entered.complete(Unit); release.await(); return result
        } }
        val c = owner(repo)
        try {
            c.prepare(ride); runCurrent(); c.confirm(); runCurrent(); assertTrue(entered.isCompleted)
            c.confirm(); c.dismiss(); c.prepare(ride.copy(id = "other")); runCurrent()
            assertEquals(1, repo.confirmed.size); assertTrue(c.state.value.deleting); assertEquals(ride, c.state.value.target)
            release.complete(Unit); runCurrent()
            assertEquals(HistoryRideDeletionResult(ride.favoriteSource(), false), c.state.value.result)
            assertFalse(c.state.value.busy); assertNull(c.state.value.target)
            c.consumeResult(); assertNull(c.state.value.result)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun conflictDropsStaleConfirmationAndRequiresPreparingAgain() = runTest {
        val repo = object : Repo() { override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
            super.confirm(request); throw HistoryRideDeletionException(HistoryRideDeletionFailure.CONFLICT)
        } }
        val c = owner(repo)
        try {
            c.prepare(ride); runCurrent(); c.confirm(); runCurrent()
            assertEquals(1, repo.confirmed.size); assertEquals(ride, c.state.value.target)
            assertEquals(HistoryRideDeletionFailure.CONFLICT, c.state.value.error); assertNull(c.state.value.confirmation)
            c.confirm(); runCurrent(); assertEquals(1, repo.confirmed.size)
            c.prepare(ride); runCurrent(); assertEquals(2, repo.prepared.size); assertNotNull(c.state.value.confirmation)
        } finally { c.close() }
    }
    @Test fun closeFencesLateNonCancellableConfirmationAndUnavailableOwnerDoesNoWork() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() { override suspend fun confirm(request: HistoryRideDeletionRequest) = withContext(NonCancellable) {
            val result = super.confirm(request); entered.complete(Unit); release.await(); result
        } }
        val c = owner(repo)
        try {
            c.prepare(ride); runCurrent(); c.confirm(); runCurrent(); assertTrue(entered.isCompleted)
            c.close(); release.complete(Unit); runCurrent(); assertNull(c.state.value.result)
            c.prepare(ride.copy(id = "other")); c.confirm(); runCurrent(); assertEquals(1, repo.confirmed.size)
        } finally { release.complete(Unit); c.close(); runCurrent() }
        val unavailable = owner(null)
        try { unavailable.prepare(ride); unavailable.confirm(); runCurrent(); assertFalse(unavailable.state.value.available); assertNull(unavailable.state.value.target) }
        finally { unavailable.close() }
    }
    @Test fun dismissedScheduledPreparationDoesNotStrandBusyOrIssueWork() = runTest {
        val repo = Repo(); val c = owner(repo)
        try {
            c.prepare(ride); assertTrue(c.state.value.preparing); assertTrue(repo.prepared.isEmpty())
            c.dismiss(); runCurrent(); assertFalse(c.state.value.busy); assertTrue(repo.prepared.isEmpty())
            c.prepare(ride); runCurrent(); assertEquals(listOf(ride), repo.prepared); assertNotNull(c.state.value.confirmation)
        } finally { c.close() }
    }
}
