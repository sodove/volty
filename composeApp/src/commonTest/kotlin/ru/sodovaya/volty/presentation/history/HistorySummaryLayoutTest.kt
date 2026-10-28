package ru.sodovaya.volty.presentation.history

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.presentation.history.HistoryTotalKind.*

class HistorySummaryLayoutTest {
    private fun t(kind: HistoryTotalKind) = HistoryTotal(kind, 1.0)

    // The order historyRideTotals produces today.
    private val produced = listOf(DISTANCE_KM, DURATION_MILLIS, MOVING_MILLIS, AVERAGE_KMH, MAX_KMH, ENERGY_WH, WH_PER_KM,
        BATTERY_USED_PERCENT, MAX_POWER_W, MAX_DUTY_PERCENT, CONTROLLER_TEMP_C).map(::t)

    @Test fun theCollapsedRowCarriesTheHeadlineNumbersAndNotMovingTime() {
        assertEquals(listOf(DISTANCE_KM, DURATION_MILLIS, AVERAGE_KMH, MAX_KMH, MAX_DUTY_PERCENT, WH_PER_KM, BATTERY_USED_PERCENT),
            historyCollapsedTotals(produced).map { it.kind })
    }

    @Test fun theTopSpeedIsInTheCollapsedRowEvenWhenEarlierTilesExist() {
        // An earlier revision took the first four tiles, so the top speed only showed once the panel was expanded.
        assert(MAX_KMH in historyCollapsedTotals(produced).map { it.kind })
    }

    @Test fun theExpandedViewKeepsEveryTileOnceWithMovingAfterTheHeadline() {
        val expanded = historyExpandedTotals(produced).map { it.kind }
        assertEquals(produced.map { it.kind }.toSet(), expanded.toSet())
        assertEquals(produced.size, expanded.size)
        assertEquals(MOVING_MILLIS, expanded[historyCollapsedTotals(produced).size])
    }

    @Test fun missingTilesAreSimplyAbsent() {
        val sparse = listOf(DURATION_MILLIS, MAX_KMH).map(::t)
        assertEquals(listOf(DURATION_MILLIS, MAX_KMH), historyCollapsedTotals(sparse).map { it.kind })
        assertEquals(listOf(DURATION_MILLIS, MAX_KMH), historyExpandedTotals(sparse).map { it.kind })
    }
}
