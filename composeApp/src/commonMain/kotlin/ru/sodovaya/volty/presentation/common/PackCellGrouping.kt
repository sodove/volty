package ru.sodovaya.volty.presentation.common

import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.SectionState

/**
 * Cells of one physical assembly (section), or the whole pack when no
 * trustworthy section breakdown exists. Shared by the pack detail screen
 * (full cell grid per section) and the dashboard branch card (per-section
 * mini bars) so both make the same grouping decision.
 */
data class CellGroup(
    /** null when the cells could not be attributed to a section — render one flat list. */
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

/**
 * Splits a pack's cells into per-section groups, refusing to guess.
 *
 * Cells are grouped ONLY when the protocol declared, for every section, the
 * exact cell range it physically covers ([SectionState.cellRange]) and those
 * ranges tile the received cell list precisely: contiguous, in order,
 * starting at cell 0 and ending at the last received cell. Anything less
 * yields one flat group with no section attribution.
 *
 * Divisibility (`cells.size % sections.size == 0`) was rejected as the
 * grouping condition: [ru.sodovaya.volty.domain.model.BmsData.cellVoltages]
 * is deliberately truncated at the first gap, so a 40-cell branch that has
 * received only its first 20 cells while both sections are already reported
 * divides evenly — and grouping would label cells 11-20 as the second
 * section when the second section physically begins at cell 21. Silently and
 * convincingly wrong.
 *
 * Contract for a future sections producer (none exists today, so grouping is
 * currently always off — intentionally): populate [SectionState.cellRange]
 * for EVERY section of the pack from what the protocol actually knows about
 * the frame layout, never from list arithmetic over the received cells.
 */
fun groupPackCells(pack: PackState?): List<CellGroup> {
    val cells = pack?.data?.cellVoltages ?: return emptyList()
    if (cells.isEmpty()) return emptyList()
    val flat = listOf(CellGroup(section = null, startIndex = 0, voltages = cells))

    val sections = pack.sections.sortedBy { it.index }
    if (sections.isEmpty()) return flat
    // Every section must carry an authoritative range; one unknown poisons
    // the whole breakdown (a partial grouping would misnumber the rest).
    val ranges = sections.map { it.cellRange ?: return flat }
    // The ranges must tile the received cells exactly. A range pointing past
    // the received list means the list is still truncated: showing the cells
    // we do have as grouped would attribute them to the wrong assembly.
    var expectedStart = 0
    for (range in ranges) {
        if (range.first != expectedStart || range.last < range.first) return flat
        expectedStart = range.last + 1
    }
    if (expectedStart != cells.size) return flat

    return sections.map { section ->
        val range = section.cellRange!!
        CellGroup(
            section = section,
            startIndex = range.first,
            voltages = cells.subList(range.first, range.last + 1).toList()
        )
    }
}
