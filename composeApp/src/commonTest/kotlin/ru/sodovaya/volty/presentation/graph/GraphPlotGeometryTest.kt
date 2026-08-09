package ru.sodovaya.volty.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class GraphPlotGeometryTest {
    private val t0 = Instant.parse("2026-08-09T00:00:00Z")

    @Test
    fun `merged timeline contains every timestamp once in chronological order`() {
        val timeline = mergedPlotTimeline(
            listOf(
                GraphSeries(GraphMetric.POWER, listOf(GraphPoint(t0 + 2.seconds, 2f))),
                GraphSeries(
                    GraphMetric.VOLTAGE,
                    listOf(GraphPoint(t0, 80f), GraphPoint(t0 + 2.seconds, 81f))
                )
            )
        )
        assertEquals(listOf(t0, t0 + 2.seconds), timeline)
    }

    @Test
    fun `plot range expands constant series without dividing by zero`() {
        val range = plotRange(
            listOf(GraphPoint(t0, 5f), GraphPoint(t0 + 1.seconds, 5f)),
            minimumSpan = 2f
        )
        assertEquals(4f, range?.min)
        assertEquals(6f, range?.max)
        assertEquals(0.5f, plotFraction(5f, requireNotNull(range)), 0.001f)
    }
}
