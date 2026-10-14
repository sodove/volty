package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class JkTelemetryObservationTest {
    private fun word(frame: ByteArray, offset: Int, value: Long, size: Int = 2) {
        repeat(size) { frame[offset + it] = (value ushr (it * 8)).toByte() }
    }
    private fun frame(type: Int, fill: (ByteArray) -> Unit = {}): ByteArray {
        val result = ByteArray(300)
        byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte(), type.toByte()).copyInto(result)
        fill(result)
        result[299] = checksumSum(result, 0, 299).toByte()
        return result
    }
    private fun info(version: String) = frame(3) { version.encodeToByteArray().copyInto(it, 30) }
    private fun settings(count: Long) = frame(1) { word(it, 114, count, 4) }
    private fun status(offset: Int = 0, cells: IntArray = intArrayOf(3300, 3301, 3302, 3303),
        mask: Long = 15, voltageMv: Long = 13200, currentMa: Long = -5000, soc: Int = 75,
        absentTemps: Int = 0x3F, temperatures: IntArray = intArrayOf(-2000, -2000, -2000, -2000, -2000, -2000),
        faults: Long = 0): ByteArray = frame(2) { result ->
        cells.forEachIndexed { i, mv -> word(result, 6 + i * 2, mv.toLong()) }
        word(result, 54 + offset / 2, mask, 4)
        word(result, 118 + offset, voltageMv, 4)
        word(result, 126 + offset, currentMa, 4)
        word(result, 130 + offset, temperatures[1].toLong())
        word(result, 132 + offset, temperatures[2].toLong())
        word(result, if (offset == 0) 134 else 112 + offset, temperatures[0].toLong())
        if (offset != 0) {
            word(result, 226 + offset, temperatures[3].toLong())
            word(result, 224 + offset, temperatures[4].toLong())
            word(result, 222 + offset, temperatures[5].toLong())
        }
        word(result, if (offset == 0) 136 else 134 + offset, faults, if (offset == 0) 2 else 4)
        result[141 + offset] = soc.toByte()
        result[182 + offset] = absentTemps.toByte()
    }
    private fun prepare(p: JkBmsProtocol, count: Long = 4, version: String = "10.12") {
        p.onNotification(settings(count)); p.onNotification(info(version))
    }
    private fun TelemetryObservation.reading(metric: TelemetryMetric) = readings.single { it.metric == metric }
    private fun TelemetryObservation.cells() = readings.filter { it.metric == TelemetryMetric.CELL }

    @Test fun unknownLayoutCannotCertifyScalarOffsetsOrDefaultTwentyFourCellCoverage() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        p.onNotification(status(cells = intArrayOf(3300, 0)))
        val event = events.single()
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.reading(TelemetryMetric.CURRENT).unknown)
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.reading(TelemetryMetric.CELL_MIN).unknown)
        assertEquals(listOf(0), event.cells().map { it.cellIndex })
        assertEquals(3.3, event.cells().single().value)
    }

    @Test fun known24sAndDeclaredCellsPreservePhysicalZeroHighCellAndScalarZero() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        prepare(p)
        assertTrue(events.isEmpty())
        p.onNotification(status(cells = intArrayOf(3300, 0, 6000, 3320), voltageMv = 0, currentMa = 0, soc = 0))
        val event = events.single()
        assertEquals(listOf(0, 1, 2, 3), event.cells().map { it.cellIndex })
        assertEquals(listOf(3.3, 0.0, 6.0, 3.32), event.cells().map { it.value })
        for (metric in listOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT, TelemetryMetric.POWER, TelemetryMetric.SOC))
            assertEquals(0.0, event.reading(metric).value)
        assertEquals(0.0, event.reading(TelemetryMetric.CELL_MIN).value)
        assertEquals(6.0, event.reading(TelemetryMetric.CELL_MAX).value)
        assertEquals(6000.0, event.reading(TelemetryMetric.CELL_DELTA).value)
        assertEquals(TelemetryUnknown.UNAVAILABLE, event.reading(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
    }

    @Test fun coalescedFramesKeepEveryCanonicalCurrentPeakAndMetadataDoesNotRestampNumbers() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        prepare(p)
        p.onNotification(status(currentMa = 1000) + status(currentMa = 99000) + status(currentMa = -1000))
        assertEquals(listOf(1.0, 99.0, -1.0), events.map { it.reading(TelemetryMetric.CURRENT).value })
        assertEquals(-13.2, events.last().reading(TelemetryMetric.POWER).value)
        p.onNotification(settings(4)); p.onNotification(info("10.13"))
        assertEquals(3, events.size)
        p.onNotification(settings(2))
        val retired = events.last()
        assertTrue(retired.readings.all { it.value == null })
        assertEquals(listOf(2, 3), retired.cells().map { it.cellIndex })
        assertEquals(-1f, assertNotNull(p.latestData()).current)
    }

    @Test fun shifted32sHasExactlyThirtyTwoIndexedSlotsAndNoFollowingFrameMetadata() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        prepare(p, 32, "11.17")
        val cells = IntArray(32) { 3300 }; cells[31] = 3690
        p.onNotification(status(offset = 32, cells = cells, mask = 0xFFFFFFFFL) + info("11.18"))
        val event = events.single()
        assertEquals((0..31).toList(), event.cells().map { it.cellIndex })
        assertEquals(3.69, event.cells().last().value)
        assertEquals(-5.0, event.reading(TelemetryMetric.CURRENT).value)
        assertEquals(3.69, event.reading(TelemetryMetric.CELL_MAX).value)
    }

    @Test fun layoutDowngradeInvalidatesUnobservableHighSlotsBeforeAnotherStatus() {
        for (version in listOf("10.12", "unknown")) {
            val p = JkBmsProtocol()
            val events = mutableListOf<TelemetryObservation>()
            p.telemetryObserver = { events += it }
            prepare(p, 32, "11.17")
            p.onNotification(status(offset = 32, cells = IntArray(32) { 3300 }, mask = 0xFFFFFFFFL))
            assertEquals(3.3, events.single().cells().last().value)
            events.clear()
            p.onNotification(info(version))
            val invalidation = events.single()
            assertTrue(invalidation.readings.all { it.value == null })
            assertEquals((24..31).toList(), invalidation.cells().map { it.cellIndex })
            assertTrue(invalidation.cells().all { it.unknown == TelemetryUnknown.UNAVAILABLE })
        }
    }

    @Test fun absentAndInvalidTemperatureCannotMakeAnUnderreportedMaximum() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        prepare(p)
        p.onNotification(status(absentTemps = 1 shl 2, temperatures = intArrayOf(100, 0, 500, -2000, -2000, -2000)))
        assertEquals(10.0, events.last().reading(TelemetryMetric.BATTERY_TEMPERATURE).value)
        p.onNotification(status(absentTemps = 0))
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.last().reading(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
        p.onNotification(status(absentTemps = 0, temperatures = intArrayOf(-2000, 200, 1700, -2000, -2000, -2000)))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.last().reading(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
    }

    @Test fun shiftedTemperatureIncludesMos144AndFiveSensorsButNeverFault166() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        prepare(p, 4, "11.17")
        val temperatures = intArrayOf(900, 300, 200, 400, 500, 600)
        p.onNotification(status(offset = 32, absentTemps = 0, temperatures = temperatures, faults = 0x7FFF))
        assertEquals(90.0, events.last().reading(TelemetryMetric.BATTERY_TEMPERATURE).value)
        p.onNotification(status(offset = 32, absentTemps = 1, temperatures = temperatures, faults = 0x7FFF))
        assertEquals(60.0, events.last().reading(TelemetryMetric.BATTERY_TEMPERATURE).value)
    }

    @Test fun maskControlsUnavailableSlotsAndSettingsShrinkRetiresPhysicalPositions() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        prepare(p)
        p.onNotification(status(mask = 5, cells = intArrayOf(3300, 3500, 3400, 3600)))
        val partial = events.single()
        assertEquals(TelemetryUnknown.UNAVAILABLE, partial.cells().single { it.cellIndex == 1 }.unknown)
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, partial.reading(TelemetryMetric.CELL_MAX).unknown)
        p.onNotification(settings(2))
        assertEquals(listOf(2, 3), events.last().cells().map { it.cellIndex })
        p.onNotification(status(mask = 3, cells = intArrayOf(3330, 3340, 6000, 6000)))
        assertEquals(listOf(0, 1), events.last().cells().map { it.cellIndex })
        assertEquals(3.34, events.last().reading(TelemetryMetric.CELL_MAX).value)
        val count = events.size
        p.onNotification(settings(2)); assertEquals(count, events.size)
    }

    @Test fun metadataWorksWithoutObserverAndResetClearsQualificationWithCallbackFailuresContained() {
        val p = JkBmsProtocol(); prepare(p)
        val savedMetadata = mutableListOf<TelemetryObservation>()
        p.telemetryObserver = { savedMetadata += it }
        p.onNotification(status(currentMa = 2000))
        assertEquals(2.0, savedMetadata.single().reading(TelemetryMetric.CURRENT).value)
        assertEquals(3.303, savedMetadata.single().reading(TelemetryMetric.CELL_MAX).value)
        p.telemetryObserver = { error("observer failure") }
        p.onNotification(status(currentMa = 2000))
        assertEquals(1L, p.telemetryObserverFailures)
        assertEquals(2f, assertNotNull(p.latestData()).current)
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        p.reset()
        p.onNotification(status())
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.single().reading(TelemetryMetric.CURRENT).unknown)
        assertEquals(4, events.single().cells().size)
    }

    @Test fun invalidSocAndNonzeroHighSettingsBytesCannotCertifyACompleteLayout() {
        val p = JkBmsProtocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        prepare(p, 260)
        p.onNotification(status(soc = 130))
        assertEquals(TelemetryUnknown.INVALID_VALUE, events.single().reading(TelemetryMetric.SOC).unknown)
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.single().reading(TelemetryMetric.CELL_MAX).unknown)
    }
}
