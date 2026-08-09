package ru.sodovaya.volty.presentation.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `tap timestamp snaps to the nearest real timeline point`() {
        val timeline = listOf(t0, t0 + 2.seconds, t0 + 5.seconds)

        assertEquals(t0 + 2.seconds, nearestTimelineTimestamp(timeline, 0.45f))
        assertEquals(t0, nearestTimelineTimestamp(timeline, -1f))
        assertNull(nearestTimelineTimestamp(emptyList(), 0.5f))
    }

    @Test
    fun `timeline ticks expose the beginning middle and end`() {
        val timeline = listOf(t0, t0 + 1.seconds, t0 + 2.seconds, t0 + 3.seconds)

        assertEquals(
            listOf(t0, t0 + 2.seconds, t0 + 3.seconds),
            timelineTicks(timeline)
        )
        assertEquals(listOf(t0), timelineTicks(listOf(t0)))
        assertEquals(emptyList(), timelineTicks(emptyList()))
    }

    @Test
    fun `plot extrema preserve measured minimum and maximum`() {
        val extrema = plotExtrema(
            listOf(
                GraphPoint(t0, 12f),
                GraphPoint(t0 + 1.seconds, -4f),
                GraphPoint(t0 + 2.seconds, 7f)
            )
        )

        assertEquals(PlotExtrema(min = -4f, max = 12f), extrema)
        assertNull(plotExtrema(emptyList()))
    }
}
