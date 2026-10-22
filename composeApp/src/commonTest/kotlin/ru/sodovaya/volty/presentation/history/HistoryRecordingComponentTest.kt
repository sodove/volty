package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingComponentTest {
    private val tick = HistoryTick(1000, 10, 1)
    private val ride = HistoryRide("ride", "bike", tick, null, HistoryRideStatus.RECORDING)
    private class Repo : HistoryRecordingRepository {
        override val states = MutableStateFlow(HistoryRecordingStatus(available = true, canStart = true, manuallyStopped = true))
        val captured = mutableListOf<HistoryRecordingStatus>()
        val calls = mutableListOf<String>()
        var result: HistoryRecordingActionResult = HistoryRecordingActionResult.Applied
        var gate: CompletableDeferred<Unit>? = null
        var nonCancellable = false
        var completed = 0
        override fun captureControls(observed: HistoryRecordingStatus): HistoryRecordingControls {
            captured += observed
            return object : HistoryRecordingControls {
                override suspend fun start() = perform("start")
                override suspend fun stop(rideId: String) = perform("stop:$rideId")
            }
        }
        private suspend fun perform(name: String): HistoryRecordingActionResult {
            calls += name
            if (nonCancellable) withContext(NonCancellable) { gate?.await() } else gate?.await()
            completed++
            return result
        }
    }
    private fun TestScope.owner(repo: Repo, onApplied: () -> Unit = {}) = HistoryRecordingComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repo, onApplied)

    @Test fun capturesRenderedSnapshotBeforeDispatchAndIgnoresDuplicateWhileBusy() = runTest {
        val repo = Repo(); val gate = CompletableDeferred<Unit>(); repo.gate = gate; var applied = 0
        val c = owner(repo) { applied++ }
        try {
            runCurrent(); val rendered = c.state.value.status
            repo.states.value = rendered.copy(linkConnected = true); runCurrent()
            assertTrue(repo.calls.isEmpty()); assertTrue(repo.captured.isEmpty())
            c.start(rendered); assertEquals(listOf(rendered), repo.captured)
            c.start(rendered); runCurrent()
            assertEquals(listOf("start"), repo.calls); assertTrue(c.state.value.busy)
            gate.complete(Unit); runCurrent()
            assertFalse(c.state.value.busy); assertNull(c.state.value.error); assertEquals(1, applied)
        } finally { gate.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun stopKeepsRenderedRideIdAndTypedFailureCanRetryNewDisplayedState() = runTest {
        val repo = Repo(); repo.states.value = HistoryRecordingStatus(available = true, ride = ride, canStop = true)
        var applied = 0; val c = owner(repo) { applied++ }
        try {
            runCurrent(); val rendered = c.state.value.status
            repo.states.value = rendered.copy(ride = ride.copy(id = "other")); runCurrent()
            repo.result = HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.NOT_APPLIED)
            c.stop(rendered); runCurrent()
            assertEquals(listOf("stop:ride"), repo.calls)
            assertEquals(HistoryRecordingActionFailure.NOT_APPLIED, c.state.value.error); assertFalse(c.state.value.busy)
            repo.result = HistoryRecordingActionResult.Applied
            c.stop(c.state.value.status); runCurrent()
            assertEquals(listOf("stop:ride", "stop:other"), repo.calls); assertEquals(1, applied); assertNull(c.state.value.error)
        } finally { c.close(); runCurrent() }
    }
    @Test fun closingBeforeDispatchIssuesNothingAndAcceptedWorkFinishesWithoutLateUiCallback() = runTest {
        val repo = Repo(); var applied = 0
        val early = owner(repo) { applied++ }
        runCurrent(); early.start(early.state.value.status); early.close(); runCurrent()
        assertTrue(repo.calls.isEmpty())
        val gate = CompletableDeferred<Unit>(); repo.gate = gate; repo.nonCancellable = true
        repo.states.value = HistoryRecordingStatus(available = true, ride = ride, canStop = true)
        val c = owner(repo) { applied++ }
        try {
            runCurrent(); c.stop(c.state.value.status); runCurrent()
            assertEquals(listOf("stop:ride"), repo.calls); assertTrue(c.state.value.busy)
            c.close(); gate.complete(Unit); runCurrent()
            assertEquals(1, repo.completed); assertEquals(0, applied); assertFalse(c.state.value.busy)
        } finally { gate.complete(Unit); early.close(); c.close(); runCurrent() }
    }
}
