package ru.sodovaya.volty.presentation.graph

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class GraphTelemetryTest {
    private val t0 = Instant.parse("2026-08-09T00:00:00Z")
    private val t1 = t0 + 1.seconds
    private val t2 = t0 + 2.seconds

    @Test
    fun `battery power omits an unearned value even when numeric field is nonzero`() {
        val data = BmsData(power = 4200f, hasPower = false, timestamp = t0)
        assertNull(GraphTelemetryMapper.battery(data, GraphMetric.POWER))
    }

    @Test
    fun `cell delta is max minus min in millivolts`() {
        val data = BmsData(cellVoltages = listOf(4.01f, 4.17f, 4.08f), timestamp = t0)
        assertEquals(160f, GraphTelemetryMapper.battery(data, GraphMetric.CELL_DELTA_MV)!!.value, 0.01f)
    }

    @Test
    fun `zero remains an earned current sample`() {
        val data = BmsData(current = 0f, hasCurrent = true, timestamp = t0)
        assertEquals(0f, GraphTelemetryMapper.battery(data, GraphMetric.CURRENT)!!.value)
    }

    @Test
    fun `nearest point ties choose the earlier timestamp`() {
        val point = nearestPoint(
            listOf(GraphPoint(t0, 1f), GraphPoint(t2, 2f)),
            t1
        )
        assertEquals(t0, point?.timestamp)
    }

    @Test
    fun `xy pairing drops samples beyond maximum gap`() {
        val pairs = pairByNearestTimestamp(
            x = listOf(GraphPoint(t0, 10f)),
            y = listOf(GraphPoint(t0 + 30.seconds, 60f)),
            maxGap = 2.seconds
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `series keeps sample timestamps`() {
        val series = GraphTelemetryMapper.batterySeries(
            listOf(
                BmsData(voltage = 80f, timestamp = t0),
                BmsData(voltage = 81f, timestamp = t2)
            ),
            GraphMetric.VOLTAGE
        )
        assertEquals(listOf(t0, t2), series.points.map { it.timestamp })
    }
}
