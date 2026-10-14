package ru.sodovaya.volty.domain.model

import kotlin.test.*

class TelemetryObservationTest {
    @Test fun rolesCannotInventAssemblyAddressesOrControllerBatterySections() {
        val rows = listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, 0.0))
        val section = TelemetryObservation(TelemetryOrigin.BATTERY, 0, rows, TelemetryRole.ASSEMBLY, 1)
        assertEquals(1, section.assemblyIndex)
        assertEquals(TelemetryRole.ASSEMBLY, section.role)
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.BATTERY, 0, rows, TelemetryRole.ASSEMBLY) }
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.BATTERY, 0, rows, TelemetryRole.ASSEMBLY, -1) }
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.BATTERY, 0, rows, assemblyIndex = 0) }
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.CONTROLLER, 0, emptyList(), TelemetryRole.ASSEMBLY, 0) }
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.CONTROLLER, 0, emptyList(), TelemetryRole.DERIVED) }
    }
    @Test fun absenceAndInvalidNumbersCannotTurnIntoEarnedZero() {
        assertEquals(TelemetryReading(TelemetryMetric.POWER, null, TelemetryUnknown.UNAVAILABLE),
            TelemetryReading.measured(TelemetryMetric.POWER, 4200.0, known = false))
        assertEquals(TelemetryUnknown.INVALID_VALUE, TelemetryReading.measured(TelemetryMetric.POWER, Double.NaN).unknown)
        assertEquals(0.0, TelemetryReading.measured(TelemetryMetric.POWER, 0.0).value)
        assertFailsWith<IllegalArgumentException> { TelemetryReading(TelemetryMetric.POWER, null) }
        assertFailsWith<IllegalArgumentException> { TelemetryReading(TelemetryMetric.POWER, Double.POSITIVE_INFINITY) }
    }

    @Test fun physicalIndexAndOriginCannotBeFabricatedOrDuplicated() {
        assertFailsWith<IllegalArgumentException> { TelemetryReading.measured(TelemetryMetric.CELL, 3.8) }
        assertFailsWith<IllegalArgumentException> { TelemetryReading.measured(TelemetryMetric.POWER, 10.0, cellIndex = 0) }
        assertFailsWith<IllegalArgumentException> { TelemetryReading.measured(TelemetryMetric.CELL, 3.8, cellIndex = -1) }
        val cell = TelemetryReading.measured(TelemetryMetric.CELL, 3.8, cellIndex = 0)
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.BATTERY, -1, listOf(cell)) }
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.CONTROLLER, 0, listOf(cell)) }
        assertFailsWith<IllegalArgumentException> { TelemetryObservation(TelemetryOrigin.BATTERY, 0, listOf(cell, cell)) }
    }

    @Test fun packetCopiesFieldsAndRetainsEqualSubsequentMeasurements() {
        val zero = TelemetryReading.measured(TelemetryMetric.CURRENT, 0.0)
        val mutable = mutableListOf(zero)
        val observation = TelemetryObservation(TelemetryOrigin.BATTERY, 0, mutable)
        mutable.clear()
        assertEquals(listOf(zero), observation.readings)
        val next = TelemetryObservation(TelemetryOrigin.BATTERY, 0, listOf(zero))
        assertEquals(observation.readings, next.readings)
    }
}
