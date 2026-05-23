package com.volty.app.presentation.cells

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.repository.BmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface CellsComponent {
    val state: StateFlow<State>
    fun onBack()

    data class Cell(val index: Int, val voltageV: Float, val rangeFraction: Float)

    data class State(
        val cells: List<Cell> = emptyList(),
        val maxIdx: Int = -1,
        val minIdx: Int = -1,
        val avgV: Float = 0f,
        val deltaMv: Int = 0
    )
}

class DefaultCellsComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val onBackRequested: () -> Unit
) : CellsComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(compute(bmsRepository.activeData.value))
    override val state: StateFlow<CellsComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch {
            bmsRepository.activeData.collect { data ->
                _state.value = compute(data)
            }
        }
    }

    private fun compute(data: BmsData): CellsComponent.State {
        val volts = data.cellVoltages
        if (volts.isEmpty()) return CellsComponent.State()
        val min = volts.min()
        val max = volts.max()
        val rawRange = max - min
        val balanced = rawRange < 0.01f   // spread < 10 mV considered balanced
        val range = rawRange.takeIf { it > 0f } ?: 1f
        val cells = volts.mapIndexed { i, v ->
            val frac = if (balanced) 0.5f else ((v - min) / range).coerceIn(0f, 1f)
            CellsComponent.Cell(index = i + 1, voltageV = v, rangeFraction = frac)
        }
        return CellsComponent.State(
            cells = cells,
            maxIdx = volts.indexOf(max),
            minIdx = volts.indexOf(min),
            avgV = volts.average().toFloat(),
            deltaMv = (rawRange * 1000f).toInt()
        )
    }

    override fun onBack() { onBackRequested() }
}
