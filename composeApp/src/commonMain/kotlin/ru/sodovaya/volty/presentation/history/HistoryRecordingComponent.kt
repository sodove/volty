package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import ru.sodovaya.volty.domain.history.*

data class HistoryRecordingUiState(val present: Boolean = false,
    val status: HistoryRecordingStatus = HistoryRecordingStatus(), val busy: Boolean = false,
    val error: HistoryRecordingActionFailure? = null)

class HistoryRecordingComponent(context: ComponentContext, parentScope: CoroutineScope,
    private val repository: HistoryRecordingRepository?, private val onApplied: () -> Unit = {}) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryRecordingUiState(present = repository != null))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = Any()
    init {
        context.lifecycle.doOnDestroy { close() }
        repository?.let { repo -> scope.launch {
            try { repo.states.collect { if (lifetime.isActive) mutableState.value = state.value.copy(status = it) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (lifetime.isActive) mutableState.value = state.value.copy(
                status = HistoryRecordingStatus(), error = HistoryRecordingActionFailure.UNAVAILABLE) }
        } }
    }
    fun start(observed: HistoryRecordingStatus) {
        if (observed.canStart && !observed.storageFailure) control(observed, null)
    }
    fun stop(observed: HistoryRecordingStatus) {
        val id = observed.ride?.id ?: return
        if (observed.canStop) control(observed, id)
    }
    private fun control(observed: HistoryRecordingStatus, rideId: String?) {
        if (!lifetime.isActive || state.value.busy) return
        val controls = try { repository?.captureControls(observed) } catch (_: Exception) { null }
        if (controls == null) {
            mutableState.value = state.value.copy(error = HistoryRecordingActionFailure.UNAVAILABLE)
            return
        }
        submit { if (rideId == null) controls.start() else controls.stop(rideId) }
    }
    fun retryLocation() {
        val repo = repository ?: return
        submit { repo.retryLocation() }
    }
    private fun submit(work: suspend () -> HistoryRecordingActionResult) {
        if (!lifetime.isActive || state.value.busy) return
        val captured = Any(); generation = captured
        mutableState.value = state.value.copy(busy = true, error = null)
        if (!current(captured)) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = work()
                currentCoroutineContext().ensureActive()
                if (current(captured)) {
                    mutableState.value = state.value.copy(error = (result as? HistoryRecordingActionResult.Failed)?.reason)
                    if (result is HistoryRecordingActionResult.Applied) runCatching { onApplied() }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(captured)) mutableState.value = state.value.copy(error = HistoryRecordingActionFailure.STORAGE) }
            finally { if (current(captured)) { job = null; mutableState.value = state.value.copy(busy = false) } }
        }
        job = task; task.start()
    }
    fun close() {
        generation = Any(); job?.cancel(); job = null; lifetime.cancel()
        mutableState.value = state.value.copy(busy = false)
    }
    private fun current(captured: Any) = lifetime.isActive && generation === captured
}
