package ru.sodovaya.volty.presentation.common

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.SectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackCellGroupingTest {

    private fun pack(
        cells: List<Float>,
        sections: List<SectionState> = emptyList()
    ) = PackState(
        pack = Pack(index = 0, label = "Battery", bmsType = BmsType.BEGODE, bmsAddress = "AA:BB"),
        data = BmsData(voltage = cells.sum(), cellVoltages = cells, isConnected = true),
        sections = sections,
        isOnline = true
    )

    @Test
    fun truncatedCellListThatDividesEvenlyRendersFlatNotGrouped() {
        // The exact case that would have shipped broken: cellVoltages is
        // truncated at the first gap, so a 40-cell branch that has received
        // only its first 20 cells while both sections are already reported
        // passes a divisibility check (20 % 2 == 0). Grouping here would
        // label cells 11-20 as section 2 when section 2 physically starts at
        // cell 21. Without authoritative boundaries the list must stay flat.
        val cells = List(20) { 3.9f }
        val groups = groupPackCells(
            pack(
                cells,
                sections = listOf(
                    SectionState(index = 0, voltage = 78f),
                    SectionState(index = 1, voltage = 78f)
                )
            )
        )
        assertEquals(1, groups.size, "must not fabricate section boundaries")
        assertNull(groups[0].section)
        assertEquals(0, groups[0].startIndex)
        assertEquals(cells, groups[0].voltages)
    }

    @Test
    fun declaredRangesPointingPastTheTruncatedListRenderFlat() {
        // Even with authoritative ranges, a list truncated below the declared
        // layout must not be grouped: only 20 of the declared 40 cells are
        // here, so section 2's cells simply have not arrived yet.
        val cells = List(20) { 3.9f }
        val groups = groupPackCells(
            pack(
                cells,
                sections = listOf(
                    SectionState(index = 0, voltage = 78f, cellRange = 0..19),
                    SectionState(index = 1, voltage = 78f, cellRange = 20..39)
                )
            )
        )
        assertEquals(1, groups.size)
        assertNull(groups[0].section)
    }

    @Test
    fun rangesWithAGapRenderFlat() {
        // Ranges must tile the cell list exactly — a hole between sections
        // means the breakdown does not describe the received cells.
        val cells = List(6) { 3.7f }
        val groups = groupPackCells(
            pack(
                cells,
                sections = listOf(
                    SectionState(index = 0, voltage = 11.1f, cellRange = 0..1),
                    SectionState(index = 1, voltage = 11.1f, cellRange = 4..5)
                )
            )
        )
        assertEquals(1, groups.size)
        assertNull(groups[0].section)
    }

    @Test
    fun oneSectionWithoutARangePoisonsTheWholeBreakdown() {
        // A partial declaration cannot be trusted either: grouping the known
        // half would misnumber everything after the unknown section.
        val cells = List(6) { 3.7f }
        val groups = groupPackCells(
            pack(
                cells,
                sections = listOf(
                    SectionState(index = 0, voltage = 11.1f, cellRange = 0..2),
                    SectionState(index = 1, voltage = 11.1f)
                )
            )
        )
        assertEquals(1, groups.size)
        assertNull(groups[0].section)
    }

    @Test
    fun authoritativeRangesTilingTheCellsExactlyDoGroup() {
        val cells = listOf(3.701f, 3.702f, 3.703f, 3.711f, 3.712f, 3.713f)
        val groups = groupPackCells(
            pack(
                cells,
                sections = listOf(
                    SectionState(index = 0, voltage = 11.106f, cellRange = 0..2),
                    SectionState(index = 1, voltage = 11.136f, cellRange = 3..5)
                )
            )
        )
        assertEquals(2, groups.size)
        assertEquals(0, groups[0].section?.index)
        assertEquals(1, groups[1].section?.index)
        assertEquals(cells.subList(0, 3), groups[0].voltages)
        assertEquals(cells.subList(3, 6), groups[1].voltages)
        // Positional indices survive: the second section starts at cell 4.
        assertEquals(0, groups[0].startIndex)
        assertEquals(3, groups[1].startIndex)
    }

    @Test
    fun noSectionsMeansOneFlatGroup() {
        val cells = List(4) { 3.7f }
        val groups = groupPackCells(pack(cells))
        assertEquals(1, groups.size)
        assertNull(groups[0].section)
        assertEquals(cells, groups[0].voltages)
    }
}
