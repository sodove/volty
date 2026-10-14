package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class JbdTelemetryObservationTest {
    private fun frame(command: Int, data: ByteArray, status: Int = 0): ByteArray {
        val body = byteArrayOf(status.toByte(), data.size.toByte()) + data
        val checksum = (0x10000 - body.sumOf { it.toInt() and 255 }) and 65535
        return byteArrayOf(0xdd.toByte(), command.toByte()) + body +
            byteArrayOf((checksum shr 8).toByte(), checksum.toByte(), 0x77)
    }
    private fun main(current: Int = -200, temperatures: List<Int> = listOf(2981), declaredTemps: Int = temperatures.size, declaredCells: Int = 2): ByteArray {
        val data = ByteArray(23 + temperatures.size * 2)
        fun word(offset: Int, value: Int) { data[offset] = (value shr 8).toByte(); data[offset + 1] = value.toByte() }
        word(0, 5000); word(2, current); data[19] = 80; data[21] = declaredCells.toByte(); data[22] = declaredTemps.toByte()
        temperatures.forEachIndexed { index, value -> word(23 + index * 2, value) }
        return frame(3, data)
    }
    private fun cells(vararg millivolts: Int) = frame(4, millivolts.flatMap {
        listOf((it shr 8).toByte(), it.toByte())
    }.toByteArray())
    private fun capture(protocol: JbdBmsProtocol): MutableList<TelemetryObservation> = mutableListOf<TelemetryObservation>().also { result ->
        protocol.telemetryObserver = { result += it }
    }

    @Test fun mainAndCellsDoNotRestampEachOthersCachedFields() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(main())
        assertEquals(1, observed.size)
        assertEquals(setOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT, TelemetryMetric.POWER,
            TelemetryMetric.SOC, TelemetryMetric.BATTERY_TEMPERATURE), observed.single().readings.map { it.metric }.toSet())
        observed.clear(); protocol.onNotification(cells(4000, 3900))
        assertTrue(observed.single().readings.all { it.metric in setOf(TelemetryMetric.CELL, TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX, TelemetryMetric.CELL_DELTA) })
        assertEquals(-2f, assertNotNull(protocol.latestData()).current)
        observed.clear(); protocol.onNotification(main())
        assertEquals(5, observed.single().readings.size)
        assertEquals(listOf(4f, 3.9f), assertNotNull(protocol.latestData()).cellVoltages)
    }

    @Test fun rawCellValuesKeepPhysicalIndicesEvenWhenDashboardWouldFilterThem() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(main(declaredCells = 4)); observed.clear()
        protocol.onNotification(cells(4000, 0, 6000, 3900))
        val readings = observed.single().readings
        val indexed = readings.filter { it.metric == TelemetryMetric.CELL }
        assertEquals(listOf(0, 1, 2, 3), indexed.map { it.cellIndex })
        assertEquals(listOf(4.0, 0.0, 6.0, 3.9), indexed.map { it.value })
        assertTrue(indexed.all { it.unknown == null })
        assertEquals(0.0, readings.single { it.metric == TelemetryMetric.CELL_MIN }.value)
        assertEquals(6.0, readings.single { it.metric == TelemetryMetric.CELL_MAX }.value)
    }

    @Test fun multipleFramesWithinOneNotificationRetainIntermediatePeakAndEqualMeasurements() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(main(-400) + main(-200) + main(-200))
        assertEquals(3, observed.size)
        assertEquals(listOf(-200.0, -100.0, -100.0), observed.map { event -> event.readings.single { it.metric == TelemetryMetric.POWER }.value })
        assertEquals(-100f, assertNotNull(protocol.latestData()).power)
    }

    @Test fun incompleteOrCorruptFrameCannotProduceObservationAndResetKeepsListener() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        val complete = main()
        protocol.onNotification(complete.copyOfRange(0, 7)); assertTrue(observed.isEmpty())
        protocol.onNotification(complete.copyOfRange(7, complete.size)); assertEquals(1, observed.size)
        observed.clear(); val corrupt = main().also { it[4] = (it[4].toInt() xor 1).toByte() }
        protocol.onNotification(corrupt); assertTrue(observed.isEmpty())
        protocol.reset(); assertNull(protocol.latestData())
        protocol.onNotification(cells(4000)); assertEquals(1, observed.size)
        assertTrue(observed.single().readings.none { it.metric == TelemetryMetric.POWER })
    }

    @Test fun missingTemperatureBytesCannotTreatChecksumAsSensorAndZeroIsRealMeasurement() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(main(temperatures = listOf(2731), declaredTemps = 2))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, observed.single().readings.single { it.metric == TelemetryMetric.BATTERY_TEMPERATURE }.unknown)
        observed.clear(); protocol.onNotification(main(temperatures = listOf(2731)))
        assertEquals(0.0, observed.single().readings.single { it.metric == TelemetryMetric.BATTERY_TEMPERATURE }.value)
    }

    @Test fun failingHistoryObserverCannotBreakLiveDecodeAndFailureIsCounted() {
        val protocol = JbdBmsProtocol()
        protocol.telemetryObserver = { error("injected observer failure") }
        protocol.onNotification(main())
        assertNotNull(protocol.latestData())
        assertEquals(1, protocol.telemetryObserverFailures)
    }

    @Test fun errorStatusCannotBecomeNewMeasurementsAndBreaksAffectedSeries() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(main()); assertEquals(1, observed.size)
        val before = protocol.latestData(); observed.clear()
        protocol.onNotification(frame(3, ByteArray(23), status = 0x80))
        assertSame(before, protocol.latestData())
        assertEquals(5, observed.single().readings.size)
        assertTrue(observed.single().readings.all { it.value == null && it.unknown == TelemetryUnknown.UNAVAILABLE })
        observed.clear(); protocol.onNotification(cells(4000, 3900)); assertEquals(1, observed.size)
        observed.clear(); protocol.onNotification(frame(4, byteArrayOf(), status = 0x80))
        val indexed = observed.single().readings.filter { it.metric == TelemetryMetric.CELL }
        assertEquals(listOf(0, 1), indexed.map { it.cellIndex })
        assertTrue(indexed.all { it.unknown == TelemetryUnknown.UNAVAILABLE })
    }

    @Test fun oddCellPayloadKeepsOnlyCompleteRawValuesAndMarksSummariesPartial() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(frame(4, byteArrayOf(0x0f, 0xa0.toByte(), 0x0f)))
        val readings = observed.single().readings
        assertEquals(listOf(4.0), readings.filter { it.metric == TelemetryMetric.CELL }.map { it.value })
        assertTrue(readings.filter { !it.metric.indexed }.all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
    }

    @Test fun smallerCellLayoutRetiresOldIndicesInsteadOfLeavingThemFresh() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(cells(4000, 3900, 3800)); assertEquals(3, observed.single().readings.count { it.metric == TelemetryMetric.CELL })
        observed.clear(); protocol.onNotification(cells(4000))
        val indexed = observed.single().readings.filter { it.metric == TelemetryMetric.CELL }
        assertEquals(listOf(0, 1, 2), indexed.map { it.cellIndex })
        assertEquals(listOf(4.0, null, null), indexed.map { it.value })
        assertTrue(indexed.drop(1).all { it.unknown == TelemetryUnknown.UNAVAILABLE })
    }

    @Test fun wholePackSummariesRequireDeclaredLayoutAndExactCoverage() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        fun summaries() = observed.last().readings.filter { !it.metric.indexed }
        protocol.onNotification(cells(4000, 3900))
        assertTrue(summaries().all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
        protocol.onNotification(main(declaredCells = 3))
        protocol.onNotification(cells(4000, 3900))
        val declaredOnlyMissing = observed.last().readings.filter { it.metric.indexed }
        assertEquals(listOf(0, 1, 2), declaredOnlyMissing.map { it.cellIndex })
        assertNull(declaredOnlyMissing.last().value)
        assertEquals(TelemetryUnknown.UNAVAILABLE, declaredOnlyMissing.last().unknown)
        assertTrue(summaries().all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
        protocol.onNotification(cells(4000, 3900, 3800))
        assertEquals(3.8, summaries().single { it.metric == TelemetryMetric.CELL_MIN }.value)
        protocol.onNotification(cells(4000))
        assertTrue(summaries().all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
        protocol.onNotification(cells(4000))
        assertEquals(listOf(0, 1, 2), observed.last().readings.filter { it.metric.indexed }.map { it.cellIndex })
        assertTrue(summaries().all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
        protocol.onNotification(main(declaredCells = 1))
        protocol.onNotification(cells(4000))
        assertEquals(4.0, summaries().single { it.metric == TelemetryMetric.CELL_MIN }.value)
        protocol.onNotification(cells(4000, 3900))
        assertTrue(summaries().all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
    }

    @Test fun resetAndZeroDeclarationRemoveLayoutEvidenceButKeepRawCells() {
        val protocol = JbdBmsProtocol(); val observed = capture(protocol)
        protocol.onNotification(main(declaredCells = 1)); protocol.onNotification(cells(0))
        assertEquals(0.0, observed.last().readings.single { it.metric == TelemetryMetric.CELL_MIN }.value)
        protocol.reset(); protocol.onNotification(cells(0))
        assertEquals(0.0, observed.last().readings.single { it.metric == TelemetryMetric.CELL }.value)
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, observed.last().readings.single { it.metric == TelemetryMetric.CELL_MIN }.unknown)
        protocol.onNotification(main(declaredCells = 0)); protocol.onNotification(cells(0))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, observed.last().readings.single { it.metric == TelemetryMetric.CELL_MIN }.unknown)
    }
}
