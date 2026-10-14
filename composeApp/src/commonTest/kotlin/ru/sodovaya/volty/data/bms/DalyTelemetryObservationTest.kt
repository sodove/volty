package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class DalyTelemetryObservationTest {
    private fun frame(command: Int, payload: ByteArray, address: Int = 1, length: Int = 8): ByteArray {
        require(payload.size == 8)
        val frame = byteArrayOf(0xa5.toByte(), address.toByte(), command.toByte(), length.toByte()) + payload + byteArrayOf(0)
        frame[12] = checksumSum(frame, 0, 12).toByte()
        return frame
    }
    private fun word(payload: ByteArray, offset: Int, value: Int) {
        payload[offset] = (value shr 8).toByte(); payload[offset + 1] = value.toByte()
    }
    private fun basic(current: Int = 30100, voltage: Int = 500) = frame(0x90, ByteArray(8).also {
        word(it, 0, voltage); word(it, 4, current); word(it, 6, 800)
    })
    private fun counts(cells: Int, temperatures: Int = 2) = frame(0x94, byteArrayOf(cells.toByte(), temperatures.toByte(), 0, 0, 0, 0, 0, 0))
    private fun cells(number: Int, vararg values: Int) = frame(0x95, ByteArray(8).also {
        it[0] = number.toByte(); values.forEachIndexed { index, value -> word(it, 1 + index * 2, value) }
    })
    private fun temperatures(number: Int, vararg raw: Int) = frame(0x96, ByteArray(8).also {
        it[0] = number.toByte(); raw.forEachIndexed { index, value -> it[index + 1] = value.toByte() }
    })
    private fun capture(protocol: DalyBmsProtocol) = mutableListOf<TelemetryObservation>().also { events -> protocol.telemetryObserver = { events += it } }
    private fun TelemetryObservation.read(metric: TelemetryMetric) = readings.single { it.metric == metric }

    @Test fun basicRepliesUseChargePositiveSignAndRetainIntermediatePeaks() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(basic(30100) + basic(30400) + basic(29900))
        assertEquals(3, events.size)
        assertEquals(listOf(-10.0, -40.0, 10.0), events.map { it.read(TelemetryMetric.CURRENT).value })
        assertEquals(listOf(-500.0, -2000.0, 500.0), events.map { it.read(TelemetryMetric.POWER).value })
        assertEquals(10f, assertNotNull(protocol.latestData()).current)
        assertTrue(events.all { it.origin == TelemetryOrigin.BATTERY && it.localIndex == 0 && it.readings.size == 4 })
    }

    @Test fun statusAndAlarmRepliesDoNotRestampCachedMeasurements() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(basic()); assertEquals(1, events.size); events.clear()
        protocol.onNotification(frame(0x93, ByteArray(8)))
        val alarm = byteArrayOf(0xd2.toByte(), 3, 127) + ByteArray(127)
        val crc = crc16Modbus(alarm)
        protocol.onNotification(alarm + byteArrayOf(crc.toByte(), (crc shr 8).toByte()))
        assertTrue(events.isEmpty()); assertNotNull(protocol.latestData())
    }

    @Test fun fragmentsKeepPhysicalCellIndicesRawZeroAndExcludePadding() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(counts(4)); events.clear()
        protocol.onNotification(cells(2, 6000, 0, 0))
        val indexed = events.single().readings.filter { it.metric.indexed }
        assertEquals(listOf(3), indexed.map { it.cellIndex }); assertEquals(listOf(6.0), indexed.map { it.value })
        assertNull(protocol.latestData()) // cell facts are valid even before basic pack data arrives.
        protocol.onNotification(cells(1, 4000, 0, 3900))
        assertEquals(listOf(0, 1, 2), events.last().readings.filter { it.metric.indexed }.map { it.cellIndex })
        assertEquals(listOf(4.0, 0.0, 3.9), events.last().readings.filter { it.metric.indexed }.map { it.value })
        assertTrue(events.all { it.read(TelemetryMetric.CELL_MIN).unknown == TelemetryUnknown.PARTIAL_SOURCE })
    }

    @Test fun evenCompleteFragmentSequenceDoesNotCertifyOneWholePackSnapshot() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(counts(4)); events.clear()
        protocol.onNotification(cells(1, 4000, 3900, 3800) + cells(2, 3700, 0, 0))
        assertEquals(2, events.size)
        assertTrue(events.all { it.read(TelemetryMetric.CELL_MIN).unknown == TelemetryUnknown.PARTIAL_SOURCE })
        protocol.onNotification(cells(1, 4100, 4000, 3900) + cells(2, 3700, 0, 0))
        assertTrue(events.all { it.read(TelemetryMetric.CELL_MAX).value == null })
    }

    @Test fun oneFrameDeclaredPackEarnsSummariesWithoutCachedGroups() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(counts(2)); events.clear()
        protocol.onNotification(cells(1, 0, 6000, 9999))
        val event = events.single()
        assertEquals(0.0, event.read(TelemetryMetric.CELL_MIN).value)
        assertEquals(6.0, event.read(TelemetryMetric.CELL_MAX).value)
        assertEquals(6000.0, event.read(TelemetryMetric.CELL_DELTA).value)
        assertEquals(2, event.readings.count { it.metric.indexed })
        assertTrue(event.readings.none { it.metric == TelemetryMetric.POWER || it.metric == TelemetryMetric.BATTERY_TEMPERATURE })
    }

    @Test fun unknownDeclarationCannotDistinguishZeroCellFromPaddingAndResetRemovesLayout() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(cells(1, 4000, 0, 0))
        assertTrue(events.single().readings.none { it.metric.indexed })
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.single().read(TelemetryMetric.CELL_MIN).unknown)
        protocol.onNotification(counts(1)); protocol.onNotification(cells(1, 0, 0, 0))
        assertEquals(0.0, events.last().read(TelemetryMetric.CELL_MIN).value)
        protocol.reset(); protocol.onNotification(cells(1, 0, 0, 0))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.last().read(TelemetryMetric.CELL_MIN).unknown)
        protocol.onNotification(counts(0)); protocol.onNotification(cells(1, 0, 0, 0))
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.last().read(TelemetryMetric.CELL_MIN).unknown)
    }

    @Test fun smallerDeclarationRetiresPriorIndicesAndInvalidatesSummary() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(counts(4)); protocol.onNotification(cells(2, 4000, 0, 0)); events.clear()
        protocol.onNotification(counts(1))
        val indexed = events.single().readings.filter { it.metric.indexed }
        assertEquals(listOf(1, 2, 3), indexed.map { it.cellIndex })
        assertTrue(indexed.all { it.value == null && it.unknown == TelemetryUnknown.UNAVAILABLE })
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.single().read(TelemetryMetric.CELL_MIN).unknown)
    }

    @Test fun temperatureZeroEncodingIsAnEarnedMinusFortyAndPaddingIsExcluded() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(counts(1, 2)); events.clear()
        protocol.onNotification(temperatures(1, 0, 40, 190, 190, 190, 190, 190))
        assertEquals(0.0, events.single().read(TelemetryMetric.BATTERY_TEMPERATURE).value)
        protocol.onNotification(temperatures(1, 0, 0))
        assertEquals(-40.0, events.last().read(TelemetryMetric.BATTERY_TEMPERATURE).value)
        protocol.onNotification(temperatures(1, 40, 255))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.last().read(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
    }

    @Test fun multiFrameOrUndeclaredTemperatureMaximumStaysPartial() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(temperatures(1, 65)); assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.single().read(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
        protocol.onNotification(counts(1, 8)); events.clear()
        protocol.onNotification(temperatures(1, 65, 66, 67, 68, 69, 70, 71) + temperatures(2, 72))
        assertEquals(2, events.size)
        assertTrue(events.all { it.read(TelemetryMetric.BATTERY_TEMPERATURE).unknown == TelemetryUnknown.PARTIAL_SOURCE })
        protocol.onNotification(counts(1, 0)); protocol.onNotification(temperatures(1, 40))
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.last().read(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
    }

    @Test fun invalidFrameNumbersNeverCreateNegativeOrOutOfLayoutIndices() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        protocol.onNotification(counts(2)); events.clear()
        protocol.onNotification(cells(0, 4000, 3900, 3800) + cells(2, 4000, 3900, 3800))
        assertEquals(2, events.size)
        assertTrue(events.all { event -> event.readings.none { it.metric.indexed } })
        assertTrue(events.all { it.read(TelemetryMetric.CELL_MIN).unknown == TelemetryUnknown.PARTIAL_SOURCE })
    }

    @Test fun fragmentsChecksumFailuresEchoesAndInvalidLengthCannotEarnObservations() {
        val protocol = DalyBmsProtocol(); val events = capture(protocol)
        val packet = basic()
        protocol.onNotification(packet.copyOfRange(0, 5)); assertTrue(events.isEmpty())
        protocol.onNotification(packet.copyOfRange(5, packet.size)); assertEquals(1, events.size)
        events.clear(); protocol.onNotification(basic().also { it[12] = (it[12].toInt() xor 1).toByte() })
        protocol.onNotification(frame(0x90, ByteArray(8), address = 0x80))
        protocol.onNotification(frame(0x90, ByteArray(8), length = 7))
        assertTrue(events.isEmpty())
    }

    @Test fun observerFailureCannotBreakLiveDecodeOrCanonicalSign() {
        val protocol = DalyBmsProtocol(); protocol.telemetryObserver = { error("injected observer failure") }
        protocol.onNotification(basic()); assertEquals(-10f, assertNotNull(protocol.latestData()).current)
        assertEquals(1, protocol.telemetryObserverFailures)
    }

    @Test fun declarationTracksWithoutListenerAndBadHeadersCannotReplaceIt() {
        val protocol = DalyBmsProtocol(); protocol.onNotification(counts(2))
        val events = capture(protocol)
        val oneCell = byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0)
        protocol.onNotification(frame(0x94, oneCell, address = 0x80))
        protocol.onNotification(frame(0x94, oneCell, address = 2))
        protocol.onNotification(frame(0x94, oneCell, length = 7))
        assertTrue(events.isEmpty())
        protocol.onNotification(cells(1, 4000, 3900, 0))
        assertEquals(2, events.single().readings.count { it.metric.indexed })
        assertEquals(3.9, events.single().read(TelemetryMetric.CELL_MIN).value)
        protocol.onNotification(counts(2)); assertEquals(1, events.size)
        protocol.onNotification(basic(current = 30000, voltage = 0))
        assertEquals(0.0, events.last().read(TelemetryMetric.VOLTAGE).value)
        assertEquals(0.0, events.last().read(TelemetryMetric.CURRENT).value)
        assertEquals(0.0, events.last().read(TelemetryMetric.POWER).value)
    }
}
