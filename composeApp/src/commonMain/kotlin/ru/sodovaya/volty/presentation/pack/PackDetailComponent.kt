package ru.sodovaya.volty.presentation.pack

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.presentation.common.CellGroup
import ru.sodovaya.volty.presentation.common.groupPackCells
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface PackDetailComponent {
    val state: StateFlow<State>
    fun onBack()

    data class State(
        /** null until the observed pack index appears in the live data. */
        val pack: PackState? = null,
        /**
         * Total packs of the vehicle, not just the observed one — drives the
         * title: a lone pack keeps its own name, branches get positional
         * labels (see packLabelFor).
         */
        val packCount: Int = 1,
        val chemistry: Chemistry = Chemistry.LI_ION_NMC,
        val groups: List<CellGroup> = emptyList()
    )
}

class DefaultPackDetailComponent(
    componentContext: ComponentContext,
    private val packIndex: Int,
    private val bmsRepository: BmsRepository,
    private val onBackRequested: () -> Unit
) : PackDetailComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(
        run {
            val packs = bmsRepository.activeVehicleData.value.packs
            val pack = packs.firstOrNull { it.pack.index == packIndex }
            PackDetailComponent.State(
                pack = pack,
                packCount = packs.size,
                chemistry = bmsRepository.activeVehicle.value?.chemistry ?: Chemistry.LI_ION_NMC,
                groups = groupPackCells(pack)
            )
        }
    )
    override val state: StateFlow<PackDetailComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }

        scope.launch {
            bmsRepository.activeVehicleData.collect { vd ->
                val pack = vd.packs.firstOrNull { it.pack.index == packIndex }
                _state.update {
                    it.copy(pack = pack, packCount = vd.packs.size, groups = groupPackCells(pack))
                }
            }
        }

        scope.launch {
            bmsRepository.activeVehicle.collect { v ->
                _state.update { it.copy(chemistry = v?.chemistry ?: Chemistry.LI_ION_NMC) }
            }
        }
    }

    override fun onBack() { onBackRequested() }
}
