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
    private open inner class Repo : HistoryRideDeletionRepository {
        val prepared = mutableListOf<HistoryRide>(); val confirmed = mutableListOf<HistoryRideDeletionRequest>()
        override suspend fun prepare(rides: List<HistoryRide>): HistoryRideDeletionRequest {
            prepared += rides
            return HistoryRideDeletionRequest(rides.map { HistoryRideDeletionEntry(it, 2, 3) }, this)
        }
        override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
            confirmed += request
            return HistoryRideDeletionResult(request.rides.map { it.favoriteSource() }.toSet(), false)
        }
    }
    private fun TestScope.owner(repo: HistoryRideDeletionRepository?) = HistoryRideDeletionComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo)

    @Test fun confirmationRequiresExplicitPrepareAndCancelNeverDeletes() = runTest {
        val repo = Repo(); val c = owner(repo)
        try {
            c.confirm(); runCurrent(); assertTrue(repo.prepared.isEmpty()); assertTrue(repo.confirmed.isEmpty())
            c.prepare(listOf(ride)); runCurrent()
            assertEquals(listOf(ride), repo.prepared); val preview = assertNotNull(c.state.value.confirmation)
            assertEquals(2L, preview.favoriteCount); assertEquals(3L, preview.mediaCount)
            c.dismiss(); c.confirm(); runCurrent()
            assertTrue(c.state.value.targets.isEmpty()); assertTrue(repo.confirmed.isEmpty())
        } finally { c.close() }
    }
    @Test fun latePrepareCannotReplaceNewerTarget() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() { override suspend fun prepare(rides: List<HistoryRide>): HistoryRideDeletionRequest {
            if (rides.single().id == "ride") return withContext(NonCancellable) { entered.complete(Unit); release.await(); super.prepare(rides) }
            return super.prepare(rides)
        } }
        val c = owner(repo)
        try {
            c.prepare(listOf(ride)); runCurrent(); assertTrue(entered.isCompleted)
            val newer = ride.copy(id = "other"); c.prepare(listOf(newer)); runCurrent()
            assertEquals(listOf(newer), c.state.value.confirmation?.rides)
            release.complete(Unit); runCurrent(); assertEquals(listOf(newer), c.state.value.confirmation?.rides)
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
            val selected = listOf(ride, ride.copy(id = "second"))
            c.prepare(selected); runCurrent(); c.confirm(); runCurrent(); assertTrue(entered.isCompleted)
            c.confirm(); c.dismiss(); c.prepare(listOf(ride.copy(id = "other"))); runCurrent()
            assertEquals(1, repo.confirmed.size); assertTrue(c.state.value.deleting); assertEquals(selected, c.state.value.targets)
            release.complete(Unit); runCurrent()
            assertEquals(HistoryRideDeletionResult(selected.map { it.favoriteSource() }.toSet(), false), c.state.value.result)
            assertFalse(c.state.value.busy); assertTrue(c.state.value.targets.isEmpty())
            c.consumeResult(); assertNull(c.state.value.result)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun conflictDropsStaleConfirmationAndRequiresPreparingAgain() = runTest {
        val repo = object : Repo() { override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
            super.confirm(request); throw HistoryRideDeletionException(HistoryRideDeletionFailure.CONFLICT)
        } }
        val c = owner(repo)
        try {
            c.prepare(listOf(ride)); runCurrent(); c.confirm(); runCurrent()
            assertEquals(1, repo.confirmed.size); assertEquals(listOf(ride), c.state.value.targets)
            assertEquals(HistoryRideDeletionFailure.CONFLICT, c.state.value.error); assertNull(c.state.value.confirmation)
            c.confirm(); runCurrent(); assertEquals(1, repo.confirmed.size)
            c.prepare(listOf(ride)); runCurrent(); assertEquals(2, repo.prepared.size); assertNotNull(c.state.value.confirmation)
        } finally { c.close() }
    }
    @Test fun closeFencesLateNonCancellableConfirmationAndUnavailableOwnerDoesNoWork() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : Repo() { override suspend fun confirm(request: HistoryRideDeletionRequest) = withContext(NonCancellable) {
            val result = super.confirm(request); entered.complete(Unit); release.await(); result
        } }
        val c = owner(repo)
        try {
            c.prepare(listOf(ride)); runCurrent(); c.confirm(); runCurrent(); assertTrue(entered.isCompleted)
            c.close(); release.complete(Unit); runCurrent(); assertNull(c.state.value.result)
            c.prepare(listOf(ride.copy(id = "other"))); c.confirm(); runCurrent(); assertEquals(1, repo.confirmed.size)
        } finally { release.complete(Unit); c.close(); runCurrent() }
        val unavailable = owner(null)
        try { unavailable.prepare(listOf(ride)); unavailable.confirm(); runCurrent(); assertFalse(unavailable.state.value.available); assertTrue(unavailable.state.value.targets.isEmpty()) }
        finally { unavailable.close() }
    }
    @Test fun dismissedScheduledPreparationDoesNotStrandBusyOrIssueWork() = runTest {
        val repo = Repo(); val c = owner(repo)
        try {
            c.prepare(listOf(ride)); assertTrue(c.state.value.preparing); assertTrue(repo.prepared.isEmpty())
            c.dismiss(); runCurrent(); assertFalse(c.state.value.busy); assertTrue(repo.prepared.isEmpty())
            c.prepare(listOf(ride)); runCurrent(); assertEquals(listOf(ride), repo.prepared); assertNotNull(c.state.value.confirmation)
        } finally { c.close() }
    }
}
