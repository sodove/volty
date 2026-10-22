package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.history.*

data class HistoryRideDeletionState(val available: Boolean = false, val target: HistoryRide? = null,
    val confirmation: HistoryRideDeletionRequest? = null, val preparing: Boolean = false,
    val deleting: Boolean = false, val error: HistoryRideDeletionFailure? = null,
    val result: HistoryRideDeletionResult? = null) { val busy: Boolean get() = preparing || deleting }

class HistoryRideDeletionComponent(context: ComponentContext, parentScope: CoroutineScope,
    private val repository: HistoryRideDeletionRepository?) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryRideDeletionState(available = repository != null))
    val state = mutableState.asStateFlow()
    private var generation = Any()
    private var job: Job? = null
    init { context.lifecycle.doOnDestroy { close() } }
    fun prepare(ride: HistoryRide) {
        val target = repository ?: return
        if (!lifetime.isActive || state.value.deleting) return
        val captured = Any(); generation = captured
        val old = job; job = null; old?.cancel()
        mutableState.value = HistoryRideDeletionState(available = true, target = ride, preparing = true)
        if (!current(captured)) return
        launch(captured) {
            val preview = target.prepare(ride)
            check(preview.ride.favoriteSource() == ride.favoriteSource())
            return@launch { previous -> previous.copy(target = preview.ride, confirmation = preview) }
        }
    }
    fun confirm() {
        val target = repository ?: return
        val confirmation = state.value.confirmation ?: return
        if (!lifetime.isActive || state.value.busy) return
        val captured = Any(); generation = captured
        mutableState.value = state.value.copy(deleting = true, error = null, result = null)
        if (!current(captured)) return
        launch(captured) {
            val result = target.confirm(confirmation)
            check(result.source == confirmation.ride.favoriteSource())
            return@launch { previous -> previous.copy(target = null, confirmation = null, result = result) }
        }
    }
    fun dismiss() {
        if (!lifetime.isActive || state.value.deleting) return
        generation = Any(); val pending = job; job = null; pending?.cancel()
        mutableState.value = HistoryRideDeletionState(available = repository != null)
    }
    fun consumeResult() { if (lifetime.isActive) mutableState.value = state.value.copy(result = null) }
    fun close() { generation = Any(); job?.cancel(); job = null; lifetime.cancel() }

    private fun current(captured: Any) = lifetime.isActive && generation === captured
    private fun launch(captured: Any, work: suspend () -> ((HistoryRideDeletionState) -> HistoryRideDeletionState)) {
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val transform = work()
                currentCoroutineContext().ensureActive()
                if (current(captured)) mutableState.value = transform(state.value)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current(captured)) mutableState.value = state.value.copy(confirmation = null,
                    error = (failure as? HistoryRideDeletionException)?.reason ?: HistoryRideDeletionFailure.STORAGE)
            } finally {
                if (current(captured)) {
                    job = null
                    mutableState.value = state.value.copy(preparing = false, deleting = false)
                }
            }
        }
        job = task; task.start()
    }
}
