package ru.sodovaya.volty.presentation.pack

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.repository.BmsRepository
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

    /**
     * Cells of one physical assembly (section), or the whole pack when the
     * protocol reported no section breakdown.
     */
    data class CellGroup(
        /** null when the pack has no section breakdown — render one flat list. */
        val section: SectionState?,
        /**
         * 0-based position of the first element of [voltages] within the
         * pack's cellVoltages. Cell indices are positional — the rendered
         * number is `startIndex + i + 1` — so cells keep their physical
         * numbers across section boundaries.
         */
        val startIndex: Int,
        val voltages: List<Float>
    )

    data class State(
        /** null until the observed pack index appears in the live data. */
        val pack: PackState? = null,
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
            val pack = bmsRepository.activeVehicleData.value.packs
                .firstOrNull { it.pack.index == packIndex }
            PackDetailComponent.State(
                pack = pack,
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
                _state.update { it.copy(pack = pack, groups = groupPackCells(pack)) }
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

/**
 * Splits a pack's cells into per-section groups.
 *
 * Sections are grouped only when the protocol supplied a breakdown AND the
 * cell list divides evenly across it (a Begode branch is N sections of equal
 * size, cells running consecutively — verified against the ET Max dump).
 * Anything else — no sections, or a cell list still being assembled at boot —
 * yields one flat group: a fabricated grouping would lie about which physical
 * assembly a cell sits in.
 */
internal fun groupPackCells(pack: PackState?): List<PackDetailComponent.CellGroup> {
    val cells = pack?.data?.cellVoltages ?: return emptyList()
    if (cells.isEmpty()) return emptyList()
    val sections = pack.sections.sortedBy { it.index }
    if (sections.isEmpty() || cells.size % sections.size != 0) {
        return listOf(PackDetailComponent.CellGroup(section = null, startIndex = 0, voltages = cells))
    }
    val perSection = cells.size / sections.size
    return sections.mapIndexed { i, section ->
        PackDetailComponent.CellGroup(
            section = section,
            startIndex = i * perSection,
            voltages = cells.subList(i * perSection, (i + 1) * perSection).toList()
        )
    }
}
