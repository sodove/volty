package com.volty.app.presentation.cells

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val _state = MutableStateFlow(
        compute(bmsRepository.activeData.value, bmsRepository.activeVehicle.value)
    )
    override val state: StateFlow<CellsComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch {
            bmsRepository.activeData
                .combine(bmsRepository.activeVehicle) { data, vehicle -> data to vehicle }
                .collect { (data, vehicle) ->
                    _state.value = compute(data, vehicle)
                }
        }
    }

    private fun compute(data: BmsData, vehicle: Vehicle?): CellsComponent.State {
        val volts = data.cellVoltages
        if (volts.isEmpty()) return CellsComponent.State()
        val chemistry = vehicle?.chemistry ?: Chemistry.LI_ION_NMC
        val min = volts.min()
        val max = volts.max()
        val rawRange = max - min
        val cells = volts.mapIndexed { i, v ->
            CellsComponent.Cell(
                index = i + 1,
                voltageV = v,
                rangeFraction = chemistryFraction(v, chemistry)
            )
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

/**
 * Maps a cell voltage to a [0, 1] fraction within the chemistry's operating range.
 * A fully charged Li-ion cell at 4.20V yields 1.0; a fully discharged cell at 2.80V yields 0.0.
 */
internal fun chemistryFraction(voltage: Float, chemistry: Chemistry): Float {
    val span = chemistry.defaultHighV - chemistry.defaultLowV
    if (span <= 0f) return 0f
    return ((voltage - chemistry.defaultLowV) / span).coerceIn(0f, 1f)
}
