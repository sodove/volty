package ru.sodovaya.volty.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class GraphComparisonTest {
    private val t0 = Instant.parse("2026-08-09T00:00:00Z")

    @Test
    fun `voltage and current are paired by nearest timestamp`() {
        val pairs = pairByNearestTimestamp(
            x = listOf(GraphPoint(t0, 10f), GraphPoint(t0 + 2.seconds, 20f)),
            y = listOf(GraphPoint(t0 + 1.seconds, 80f), GraphPoint(t0 + 3.seconds, 81f)),
            maxGap = 2.seconds
        )
        assertEquals(listOf(80f, 80f), pairs.map { it.y.value })
    }

    @Test
    fun `nearest pair uses x value and preserves source timestamp`() {
        val pair = nearestPair(
            listOf(
                GraphPair(GraphPoint(t0, 10f), GraphPoint(t0, 80f)),
                GraphPair(GraphPoint(t0 + 1.seconds, 20f), GraphPoint(t0 + 1.seconds, 81f))
            ),
            xValue = 19f
        )
        assertEquals(81f, pair?.y?.value)
        assertEquals(t0 + 1.seconds, pair?.x?.timestamp)
    }

    @Test
    fun `comparison has no fabricated point when one series is absent`() {
        assertTrue(pairByNearestTimestamp(emptyList(), listOf(GraphPoint(t0, 1f)), 2.seconds).isEmpty())
    }
}
