package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistorySummaryPolicyTest {
    private val power = HistorySeriesKey("pack", "power_w")
    private val unavailable = HistorySummaryValue(null, SummaryQuality.UNAVAILABLE)

    @Test fun missingEnergyDoesNotBecomeZeroOrHideEarnedExtrema() {
        val result = historyMetricSummary(power, HistoryStats(-4200.0, -100.0, null, true))
        assertEquals(unavailable, result.energy)
        assertEquals(HistorySummaryValue(100.0, SummaryQuality.EXACT), result.minimum)
        assertEquals(HistorySummaryValue(4200.0, SummaryQuality.EXACT), result.maximum)
    }

    @Test fun displaySignReversesExtremaButConsumptionEnergyIsAlreadyCanonical() {
        val result = historyMetricSummary(power, HistoryStats(-500.0, 200.0, -3.0, true))
        assertEquals(HistorySummaryValue(-200.0, SummaryQuality.EXACT), result.minimum)
        assertEquals(HistorySummaryValue(500.0, SummaryQuality.EXACT), result.maximum)
        assertEquals(HistorySummaryValue(-3.0, SummaryQuality.EXACT), result.energy)
        assertEquals(HistoryUnit.WATT, result.unit)
    }

    @Test fun partialEvidenceRetainsValuesWithPartialLabelsIndependently() {
        val result = historyMetricSummary(power, HistoryStats(null, 100.0, 12.0, false))
        assertEquals(HistorySummaryValue(-100.0, SummaryQuality.PARTIAL), result.minimum)
        assertEquals(unavailable, result.maximum)
        assertEquals(HistorySummaryValue(12.0, SummaryQuality.PARTIAL), result.energy)
    }

    @Test fun realZeroIsEarnedAndNoEvidenceIsUnavailableEvenIfCompleteFlagIsTrue() {
        val zero = historyMetricSummary(power, HistoryStats(0.0, 0.0, 0.0, true))
        assertEquals(0.0, zero.energy.value)
        assertEquals(SummaryQuality.EXACT, zero.energy.quality)
        val absent = historyMetricSummary(power, HistoryStats(null, null, null, true))
        assertEquals(unavailable, absent.minimum); assertEquals(unavailable, absent.maximum)
        assertEquals(unavailable, absent.energy)
        assertEquals(unavailable, historyMetricSummary(power, null).energy)
    }

    @Test fun nonPowerMetricCannotClaimEnergyFromIncoherentReadModel() {
        val result = historyMetricSummary(HistorySeriesKey("pack", "voltage_v"), HistoryStats(60.0, 84.0, 4200.0, true))
        assertEquals(unavailable, result.energy)
        assertEquals(HistorySummaryValue(60.0, SummaryQuality.EXACT), result.minimum)
        assertEquals(HistorySummaryValue(84.0, SummaryQuality.EXACT), result.maximum)
        assertEquals(HistoryUnit.VOLT, result.unit)
    }

    @Test fun unknownMetricOrWrongCellShapeNeverLeaksNumbers() {
        for (key in listOf(HistorySeriesKey("pack", "future"), HistorySeriesKey("pack", "cell_v"),
            HistorySeriesKey("pack", "voltage_v", 0))) {
            val result = historyMetricSummary(key, HistoryStats(4.0, 5.0, 10.0, true))
            assertEquals(unavailable, result.minimum); assertEquals(unavailable, result.maximum)
            assertEquals(unavailable, result.energy); assertNull(result.unit)
        }
        assertEquals(HistorySummaryValue(4.0, SummaryQuality.EXACT),
            historyMetricSummary(HistorySeriesKey("pack", "cell_v", 7), HistoryStats(4.0, 5.0, null, true)).minimum)
    }

    @Test fun controllerPowerKeepsNativeDisplayAndEarnedEnergy() {
        val result = historyMetricSummary(HistorySeriesKey("controller", "controller_power_w"), HistoryStats(-80.0, 5000.0, 25.0, false))
        assertEquals(HistorySummaryValue(-80.0, SummaryQuality.PARTIAL), result.minimum)
        assertEquals(HistorySummaryValue(5000.0, SummaryQuality.PARTIAL), result.maximum)
        assertEquals(HistorySummaryValue(25.0, SummaryQuality.PARTIAL), result.energy)
    }
}
