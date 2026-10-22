package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryUnknown

/** Exercise the real recorder adapter as well as the decoder's observer output. */
internal fun assertHistoryAccepts(observations: List<ru.sodovaya.volty.domain.model.TelemetryObservation>) {
    for (observation in observations) {
        val source = ru.sodovaya.volty.domain.history.HistorySource("source",
            ru.sodovaya.volty.data.history.historySourceKindForTelemetry(observation.origin, observation.role), "Device")
        val samples = ru.sodovaya.volty.data.history.historyTelemetrySamples(source,
            ru.sodovaya.volty.domain.history.HistoryTick(1000, 100, 1), observation)
        assertEquals(observation.readings.size, samples.size)
    }
}

class FreshTelemetryReadingsTest {

    @Test
    fun controllerMapsOnlyExplicitlySelectedFields() {
        val readings = freshControllerReadings(
            ControllerData(
                speedKmh = 17.5f,
                speedSource = SpeedSource.REPORTED,
                powerW = 4200f,
                hasPower = true
            ),
            setOf(TelemetryMetric.SPEED)
        )

        assertEquals(listOf(TelemetryMetric.SPEED), readings.map { it.metric })
        assertEquals(17.5, readings.single().value)
    }

    @Test
    fun selectedControllerValuesKeepUnavailableAndInvalidReasons() {
        val readings = freshControllerReadings(
            ControllerData(
                speedKmh = Float.NaN,
                speedSource = SpeedSource.REPORTED,
                inputVoltageV = 0f,
                hasInputVoltage = false
            ),
            setOf(TelemetryMetric.INPUT_VOLTAGE, TelemetryMetric.SPEED)
        ).associateBy { it.metric }

        assertNull(readings.getValue(TelemetryMetric.INPUT_VOLTAGE).value)
        assertEquals(TelemetryUnknown.UNAVAILABLE, readings.getValue(TelemetryMetric.INPUT_VOLTAGE).unknown)
        assertNull(readings.getValue(TelemetryMetric.SPEED).value)
        assertEquals(TelemetryUnknown.INVALID_VALUE, readings.getValue(TelemetryMetric.SPEED).unknown)
    }

    @Test
    fun selectedBatteryScalarDoesNotRestampCachedSocPowerOrCells() {
        val readings = freshBatteryReadings(
            BmsData(
                voltage = 58f,
                soc = 73f,
                power = 900f,
                cellVoltages = listOf(3.8f, 3.9f)
            ),
            setOf(TelemetryMetric.VOLTAGE)
        )

        assertEquals(listOf(TelemetryMetric.VOLTAGE), readings.map { it.metric })
        assertEquals(58.0, readings.single().value)
    }

    @Test
    fun freshCellsPreserveIndicesAcceptZeroAndReportDeltaInMillivolts() {
        val readings = freshBatteryReadings(
            BmsData(cellVoltages = listOf(0f, 4.2f)),
            setOf(TelemetryMetric.CELL, TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX,
                TelemetryMetric.CELL_DELTA)
        )
        val cells = readings.filter { it.metric == TelemetryMetric.CELL }

        assertEquals(listOf(0, 1), cells.map { it.cellIndex })
        assertEquals(0.0, cells[0].value)
        assertNull(cells[0].unknown)
        assertEquals(4.2, assertNotNull(cells[1].value), 0.001)
        assertEquals(0.0, assertNotNull(readings.single { it.metric == TelemetryMetric.CELL_MIN }.value), 0.001)
        assertEquals(4.2, assertNotNull(readings.single { it.metric == TelemetryMetric.CELL_MAX }.value), 0.001)
        assertEquals(4200.0, assertNotNull(readings.single { it.metric == TelemetryMetric.CELL_DELTA }.value), 0.001)
    }

    @Test
    fun mapperRejectsFieldsFromTheOtherOrigin() {
        assertFailsWith<IllegalArgumentException> {
            freshControllerReadings(ControllerData(), setOf(TelemetryMetric.SOC))
        }
        assertFailsWith<IllegalArgumentException> {
            freshBatteryReadings(BmsData(), setOf(TelemetryMetric.SPEED))
        }
    }
}
