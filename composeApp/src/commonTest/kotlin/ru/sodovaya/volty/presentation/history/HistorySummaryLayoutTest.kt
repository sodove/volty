package ru.sodovaya.volty.presentation.history

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.presentation.history.HistoryTotalKind.*

class HistorySummaryLayoutTest {
    private fun t(kind: HistoryTotalKind) = HistoryTotal(kind, 1.0)

    // The order historyRideTotals produces today.
    private val produced = listOf(DISTANCE_KM, DURATION_MILLIS, MOVING_MILLIS, AVERAGE_KMH, MAX_KMH, ENERGY_WH, WH_PER_KM,
        BATTERY_USED_PERCENT, MAX_POWER_W, MAX_DUTY_PERCENT, CONTROLLER_TEMP_C).map(::t)

    @Test fun theSummaryKeepsEveryTileWithHeadlineNumbersFirst() {
        assertEquals(listOf(DISTANCE_KM, DURATION_MILLIS, AVERAGE_KMH, MAX_KMH, MAX_DUTY_PERCENT, WH_PER_KM,
            BATTERY_USED_PERCENT, MOVING_MILLIS, ENERGY_WH, MAX_POWER_W, CONTROLLER_TEMP_C),
            historySummaryTotals(produced).map { it.kind })
    }

    @Test fun missingTilesAreSimplyAbsent() {
        val sparse = listOf(DURATION_MILLIS, MAX_KMH).map(::t)
        assertEquals(sparse, historySummaryTotals(sparse))
    }

    @Test fun aShortRideKeepsPowerAndControllerTemperatureInTheStrip() {
        val recorded = listOf(DISTANCE_KM, DURATION_MILLIS, MAX_KMH, BATTERY_USED_PERCENT, MAX_POWER_W,
            MAX_DUTY_PERCENT, CONTROLLER_TEMP_C).map(::t)
        assertEquals(listOf(DISTANCE_KM, DURATION_MILLIS, MAX_KMH, MAX_DUTY_PERCENT, BATTERY_USED_PERCENT,
            MAX_POWER_W, CONTROLLER_TEMP_C), historySummaryTotals(recorded).map { it.kind })
    }
}
