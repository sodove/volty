package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.history.*

enum class HistoryPresetError { READ, SAVE, DELETE }
data class HistoryPresetsState(
    val available: Boolean = false,
    val presets: List<HistoryMetricPreset> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: HistoryPresetError? = null,
)

/** Global saved layouts, independent of the currently selected ride. A late save
 * may finish in storage, but cannot apply metrics to a different selection. */
class HistoryPresetsComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    private val store: HistoryMetricPresetStore?) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryPresetsState(available = store != null))
    val state = mutableState.asStateFlow()
    init { componentContext.lifecycle.doOnDestroy { close() } }
    fun refresh() = request(HistoryPresetError.READ) { read() }
    fun save(preset: HistoryMetricPreset) {
        val captured = preset.copy(metrics = preset.metrics.toList())
        request(HistoryPresetError.SAVE) { save(captured) }
    }
    fun delete(id: String) = request(HistoryPresetError.DELETE) { delete(id) }

    private fun request(error: HistoryPresetError, operation: suspend HistoryMetricPresetStore.() -> List<HistoryMetricPreset>) {
        val target = store ?: return
        if (!lifetime.isActive || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        if (!lifetime.isActive) return
        scope.launch {
            try {
                val next = target.operation().map { it.copy(metrics = it.metrics.toList()) }
                currentCoroutineContext().ensureActive()
                if (lifetime.isActive) mutableState.value = mutableState.value.copy(presets = next, loaded = true, busy = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isActive) mutableState.value = mutableState.value.copy(busy = false, error = error)
            }
        }
    }
    fun close() { lifetime.cancel() }
}
