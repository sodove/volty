package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.history.*

data class HistoryExportState(val available: Boolean = false, val source: HistoryFavoriteSource? = null,
    val range: HistoryRange? = null, val busy: Boolean = false,
    val ready: HistoryExportResult.Success? = null, val error: HistoryExportFailure? = null)

class HistoryExportComponent(context: ComponentContext, parentScope: CoroutineScope,
    private val repository: HistoryExportRepository?) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryExportState(available = repository != null))
    val state = mutableState.asStateFlow()
    private var generation = Any()
    private var job: Job? = null
    init { context.lifecycle.doOnDestroy { close() } }
    fun open(source: HistoryFavoriteSource, range: HistoryRange) {
        if (repository == null || !lifetime.isActive) return
        dismiss()
        mutableState.value = HistoryExportState(true, source, range)
    }
    fun start(format: HistoryExportFormat) {
        val repo = repository ?: return
        val previous = state.value
        val source = previous.source ?: return
        val range = previous.range ?: return
        if (!lifetime.isActive || previous.busy) return
        val captured = Any(); generation = captured
        mutableState.value = previous.copy(busy = true, ready = null, error = null)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repo.export(HistoryExportRequest(source, range, format))
                currentCoroutineContext().ensureActive()
                if (current(captured)) mutableState.value = when (result) {
                    is HistoryExportResult.Success -> state.value.copy(ready = result)
                    is HistoryExportResult.Failed -> state.value.copy(error = result.reason)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (current(captured)) mutableState.value = state.value.copy(error = HistoryExportFailure.STORAGE) }
            finally { if (current(captured)) { job = null; mutableState.value = state.value.copy(busy = false) } }
        }
        job = task; task.start()
    }
    fun dismiss() {
        if (!lifetime.isActive) return
        generation = Any(); val previous = job; job = null; previous?.cancel()
        mutableState.value = HistoryExportState(available = repository != null)
    }
    fun close() { dismiss(); generation = Any(); lifetime.cancel() }
    private fun current(captured: Any) = lifetime.isActive && generation === captured
}
