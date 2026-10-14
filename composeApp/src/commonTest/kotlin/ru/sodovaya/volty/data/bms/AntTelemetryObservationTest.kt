package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class AntTelemetryObservationTest {
    private fun status(
        cells: List<Int> = listOf(4000, 3900),
        temperatures: List<Int> = listOf(-5, 0),
        mos: Int = 12,
        voltage: Int = 5000,
        current: Int = 200,
        declaredCells: Int = cells.size,
        declaredTemps: Int = temperatures.size,
        payloadEnd: Int? = null,
        code: Int = 0x11,
    ): ByteArray {
        val scalar = 34 + cells.size * 2 + temperatures.size * 2 + 4
        val end = payloadEnd ?: scalar + 24
        val frame = ByteArray(end + 4)
        frame[0] = 0x7e; frame[1] = 0xa1.toByte(); frame[2] = code.toByte(); frame[5] = (end - 6).toByte()
        frame[8] = declaredTemps.toByte(); frame[9] = declaredCells.toByte()
        fun word(offset: Int, value: Int) {
            if (offset + 1 < end) { frame[offset] = value.toByte(); frame[offset + 1] = (value shr 8).toByte() }
        }
        cells.forEachIndexed { index, value -> word(34 + index * 2, value) }
        temperatures.forEachIndexed { index, value -> word(34 + cells.size * 2 + index * 2, value) }
        word(scalar - 4, mos); word(scalar - 2, 150) // balancer is deliberately hotter; dashboard excludes it.
        word(scalar, voltage); word(scalar + 2, current); word(scalar + 4, 80)
        val crc = crc16Modbus(frame, 1, end)
        frame[end] = crc.toByte(); frame[end + 1] = (crc shr 8).toByte(); frame[end + 2] = 0xaa.toByte(); frame[end + 3] = 0x55
        return frame
    }
    private fun capture(protocol: AntBmsProtocol) = mutableListOf<TelemetryObservation>().also { result ->
        protocol.telemetryObserver = { result += it }
    }
    private fun TelemetryObservation.read(metric: TelemetryMetric) = readings.single { it.metric == metric }

    @Test fun completeStatusReportsCanonicalSignsRawCellPositionsAndHottestPresentSensor() {
        val protocol = AntBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(status(cells = listOf(4000, 0, 6000), temperatures = listOf(-5, -40), current = 200))
        val event = observed.single()
        assertEquals(TelemetryOrigin.BATTERY, event.origin); assertEquals(0, event.localIndex)
        assertEquals(listOf(4.0, 0.0, 6.0), event.readings.filter { it.metric.indexed }.map { it.value })
        assertEquals(listOf(0, 1, 2), event.readings.filter { it.metric.indexed }.map { it.cellIndex })
        assertEquals(-20.0, event.read(TelemetryMetric.CURRENT).value)
        assertEquals(-1000.0, event.read(TelemetryMetric.POWER).value)
        assertEquals(0.0, event.read(TelemetryMetric.CELL_MIN).value)
        assertEquals(6000.0, event.read(TelemetryMetric.CELL_DELTA).value)
        assertEquals(12.0, event.read(TelemetryMetric.BATTERY_TEMPERATURE).value)
        assertNotNull(protocol.latestData())
    }

    @Test fun payloadBoundaryCannotBorrowChecksumOrFollowingFrameForCurrentAndPower() {
        val protocol = AntBmsProtocol(); val observed = capture(protocol)
        // scalar offset 46: voltage fits, current begins exactly where CRC begins.
        protocol.onNotification(status(payloadEnd = 48) + status(current = -100))
        assertEquals(2, observed.size)
        val first = observed.first()
        assertEquals(50.0, first.read(TelemetryMetric.VOLTAGE).value)
        for (metric in listOf(TelemetryMetric.CURRENT, TelemetryMetric.POWER, TelemetryMetric.SOC)) {
            assertNull(first.read(metric).value); assertEquals(TelemetryUnknown.PARTIAL_SOURCE, first.read(metric).unknown)
        }
        assertEquals(10.0, observed.last().read(TelemetryMetric.CURRENT).value)
    }

    @Test fun truncatedDeclaredCellBlockRetainsOnlyCoveredPairsAndInvalidatesSummaries() {
        val protocol = AntBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(status(declaredCells = 8, payloadEnd = 46))
        val event = observed.single()
        val cells = event.readings.filter { it.metric.indexed }
        assertEquals((0..7).toList(), cells.map { it.cellIndex })
        assertTrue(cells.take(6).all { it.value != null })
        assertTrue(cells.drop(6).all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.read(TelemetryMetric.CELL_MIN).unknown)
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.read(TelemetryMetric.VOLTAGE).unknown)
    }

    @Test fun temperatureAbsenceInvalidityAndZeroAreDifferent() {
        val protocol = AntBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(status(temperatures = listOf(-40, -40), mos = -40))
        assertEquals(TelemetryUnknown.UNAVAILABLE, observed.last().read(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
        protocol.onNotification(status(temperatures = listOf(0, -40), mos = -40))
        assertEquals(0.0, observed.last().read(TelemetryMetric.BATTERY_TEMPERATURE).value)
        protocol.onNotification(status(temperatures = listOf(200, 25), mos = 12))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, observed.last().read(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
        protocol.onNotification(status(declaredTemps = 255))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, observed.last().read(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, observed.last().read(TelemetryMetric.CURRENT).unknown)
    }

    @Test fun smallerDeclaredLayoutRetiresOldSeriesAndResetClearsEvidence() {
        val protocol = AntBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(status(cells = listOf(4000, 3900, 3800)))
        protocol.onNotification(status(cells = listOf(4000)))
        val cells = observed.last().readings.filter { it.metric.indexed }
        assertEquals(listOf(4.0, null, null), cells.map { it.value })
        assertTrue(cells.drop(1).all { it.unknown == TelemetryUnknown.UNAVAILABLE })
        assertEquals(4.0, observed.last().read(TelemetryMetric.CELL_MIN).value)
        protocol.reset(); protocol.onNotification(status(cells = listOf(4000)))
        assertEquals(1, observed.last().readings.count { it.metric.indexed })
    }

    @Test fun everyValidatedStatusIsObservedWhileFragmentsCrcFailuresAndInfoAreExcluded() {
        val protocol = AntBmsProtocol(); val observed = capture(protocol)
        val complete = status()
        protocol.onNotification(complete.copyOfRange(0, 7)); assertTrue(observed.isEmpty())
        protocol.onNotification(complete.copyOfRange(7, complete.size)); assertEquals(1, observed.size)
        observed.clear(); protocol.onNotification(status(current = 100) + status(current = 200) + status(current = 200))
        assertEquals(listOf(-500.0, -1000.0, -1000.0), observed.map { it.read(TelemetryMetric.POWER).value })
        observed.clear(); protocol.onNotification(status().also { it[34] = (it[34].toInt() xor 1).toByte() })
        protocol.onNotification(status(code = 0x12)); assertTrue(observed.isEmpty())
    }

    @Test fun observerFailureCannotInterruptLiveDecoder() {
        val protocol = AntBmsProtocol(); protocol.telemetryObserver = { error("injected observer failure") }
        protocol.onNotification(status()); assertNotNull(protocol.latestData())
        assertEquals(1, protocol.telemetryObserverFailures)
    }
}
