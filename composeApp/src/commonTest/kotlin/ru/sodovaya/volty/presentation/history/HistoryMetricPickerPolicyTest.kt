package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.HistorySeriesKey
import ru.sodovaya.volty.domain.history.HistorySource
import ru.sodovaya.volty.domain.history.HistorySourceKind
import kotlin.test.*

class HistoryMetricPickerPolicyTest {
    private val a = HistorySeriesKey("a", "cell_v", 0)
    private val b = a.copy(sourceId = "b")

    @Test fun searchCombinesSourceAndMetricTokensWithoutChangingIdentity() {
        val options = listOf(HistoryMetricOption(a, "Cell 1", "Battery A", true), HistoryMetricOption(b, "Cell 1", "Battery B", true))
        assertEquals(listOf(options[1]), historyMetricOptions(options, "  CELL   battery b  "))
        assertEquals(options, historyMetricOptions(options, " "))
        assertTrue(historyMetricOptions(options, "Cell 9").isEmpty())
    }

    @Test fun sameTitlesAcrossAggregateAndContributorsNeverCollapseKeys() {
        val aggregate = HistorySeriesKey("vehicle", "power_w")
        val contributor = aggregate.copy(sourceId = "pack")
        val options = listOf(HistoryMetricOption(aggregate, "Power", "Battery", true),
            HistoryMetricOption(contributor, "Power", "Battery", true))
        assertEquals(listOf(aggregate, contributor), historyMetricOptions(options, "power").map { it.key })
    }

    @Test fun selectedMissingMetadataRemainsRemovableAndOtherSourcesStayOutsideGroup() {
        val missing = a.copy(cellIndex = 8)
        assertEquals(listOf(HistoryMetricKeyOption(a, true), HistoryMetricKeyOption(missing, false)),
            historyMetricPickerKeys(listOf(a, a, b), listOf(missing, a, b), "a"))
        assertEquals(listOf(HistoryMetricKeyOption(missing, false)), historyMetricPickerKeys(emptyList(), listOf(missing), "a"))
    }

    @Test fun thousandCellsSearchesRecordedKeysAndKeepsSparseCellNumbers() {
        val options = (0 until 1000).flatMap { index -> listOf(
            HistoryMetricOption(a.copy(cellIndex = index * 2), "Cell ${index * 2 + 1}", "Battery A", true),
            HistoryMetricOption(b.copy(cellIndex = index * 2), "Cell ${index * 2 + 1}", "Battery B", true)) }
        assertEquals(listOf(b.copy(cellIndex = 1000)), historyMetricOptions(options, "Battery B cell 1001").map { it.key })
        assertTrue(historyMetricOptions(options, "Cell 1000").isEmpty())
        assertEquals(2000, historyMetricOptions(options, "").size)
    }

    @Test fun unicodeSearchPreservesUserQueryAndDisplayLabels() {
        val option = HistoryMetricOption(a, "Ячейка 1", "Батарея передняя", true)
        assertEquals(listOf(option), historyMetricOptions(listOf(option), "ПЕРЕДНЯЯ яЧеЙка"))
        assertEquals("Ячейка 1", option.title)
    }

    @Test fun reorderingClampsIndicesAndPreservesSelectionOfUnavailableKey() {
        val missing = a.copy(cellIndex = 99); val keys = listOf(a, b, missing)
        assertEquals(listOf(missing, a, b), reorderHistoryMetric(keys, missing, Int.MIN_VALUE))
        assertEquals(listOf(b, missing, a), reorderHistoryMetric(keys, a, Int.MAX_VALUE))
        assertEquals(keys, reorderHistoryMetric(keys, a.copy(sourceId = "missing"), 0))
        assertEquals(listOf(a), reorderHistoryMetric(listOf(a), a, 2))
    }

    @Test fun lazyKeysEncodeIdentityRatherThanAmbiguousLabels() {
        assertNotEquals(historyMetricItemKey(a), historyMetricItemKey(b))
        assertNotEquals(historyMetricItemKey(a), historyMetricItemKey(a.copy(cellIndex = 1)))
        assertNotEquals(historyMetricItemKey(HistorySeriesKey("a:b", "c")), historyMetricItemKey(HistorySeriesKey("a", "b:c")))
    }

    @Test fun duplicateSourceNamesAreVisiblyDistinctWithoutChangingStoredIdentity() {
        val sources = listOf(HistorySource("aggregate", HistorySourceKind.VEHICLE_BATTERY, "Pack"),
            HistorySource("a", HistorySourceKind.BATTERY, "Pack"), HistorySource("b", HistorySourceKind.BATTERY, "Pack"))
        assertEquals(mapOf("aggregate" to "Pack", "a" to "Pack · 1", "b" to "Pack · 2"), historySourceLabels(sources))
        assertEquals(listOf("aggregate", "a", "b"), sources.map { it.id })
    }
}
