package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class BegodeBatteryObservationTest {
    private fun frame(type: Int, subtype: Int, fill: (ByteArray) -> Unit = {}) = ByteArray(24).also {
        it[0] = 0x55; it[1] = 0xaa.toByte(); it[18] = type.toByte(); it[19] = subtype.toByte()
        for (index in 20..23) it[index] = 0x5a
        fill(it)
    }
    private fun word(frame: ByteArray, offset: Int, value: Int) { frame[offset] = (value shr 8).toByte(); frame[offset + 1] = value.toByte() }
    private fun telemetry(section: Int, current: Int = 10, boot: Boolean = false) = frame(1, section) {
        word(it, 6, 1500); word(it, 8, current)
        if (!boot) { word(it, 10, 25); word(it, 12, 26); word(it, 14, 750) }
    }
    private fun cells(branch: Int, page: Int, values: List<Int>) = frame(2 + branch, page) {
        require(values.size == 8); values.forEachIndexed { index, value -> word(it, 2 + index * 2, value) }
    }
    private fun capture(protocol: BegodeProtocol) = mutableListOf<TelemetryObservation>().also { events ->
        protocol.telemetryObserver = { if (it.role == TelemetryRole.CONTRIBUTOR) events += it }
    }
    private fun TelemetryObservation.read(metric: TelemetryMetric) = readings.single { it.metric == metric }

    @Test fun branchCurrentsAreFreshChargePositiveAndNeverReuseCellDerivedVoltage() {
        val protocol = BegodeProtocol(cellCount = 8); val events = capture(protocol)
        protocol.onNotification(cells(0, 0, List(8) { 4000 }))
        events.clear(); protocol.onNotification(telemetry(0, 10) + telemetry(2, -20) + telemetry(1, 30))
        assertEquals(listOf(0, 1, 0), events.map { it.localIndex })
        assertEquals(listOf(-1.0, 2.0, -3.0), events.map { it.read(TelemetryMetric.CURRENT).value })
        assertTrue(events.all { event -> event.origin == TelemetryOrigin.BATTERY && event.readings.none { it.metric.indexed } })
        for (event in events) {
            assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.read(TelemetryMetric.VOLTAGE).unknown)
            assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.read(TelemetryMetric.POWER).unknown)
            assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.read(TelemetryMetric.BATTERY_TEMPERATURE).unknown)
            assertEquals(TelemetryUnknown.UNAVAILABLE, event.read(TelemetryMetric.SOC).unknown)
        }
    }

    @Test fun bootTelemetryIsAbsentAndRealZeroCurrentIsEarned() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        protocol.onNotification(telemetry(0, 0, boot = true))
        // Retraction: this asserted CURRENT = UNAVAILABLE on the boot frame. A placeholder frame makes no claim about the
        // current, so it is omitted (no observation), exactly as the controller-power fix (Task 6) omits an unpaired 0x00.
        assertTrue(events.single().readings.none { it.metric == TelemetryMetric.CURRENT || it.metric == TelemetryMetric.POWER })
        protocol.onNotification(telemetry(0, 0))
        assertEquals(0.0, assertNotNull(events.last().read(TelemetryMetric.CURRENT).value), 0.0)
        protocol.onNotification(telemetry(0, 0, boot = true))
        assertTrue(events.last().readings.none { it.metric == TelemetryMetric.CURRENT })
    }

    @Test fun outOfOrderPagesKeepPhysicalIndicesAndBranchIdentityBeforeBasicData() {
        val protocol = BegodeProtocol(cellCount = 40); val events = capture(protocol)
        protocol.onNotification(cells(1, 3, List(8) { 3900 }) + cells(0, 1, List(8) { 4000 }))
        assertNull(protocol.latestData(0)); assertNull(protocol.latestData(1))
        assertEquals(listOf(1, 0), events.map { it.localIndex })
        assertEquals((24..31).toList(), events.first().readings.filter { it.metric.indexed }.map { it.cellIndex })
        assertEquals((8..15).toList(), events.last().readings.filter { it.metric.indexed }.map { it.cellIndex })
        assertTrue(events.all { it.read(TelemetryMetric.CELL_MIN).unknown == TelemetryUnknown.PARTIAL_SOURCE })
    }

    @Test fun declaredPositionsKeepRawZeroButExcludePadding() {
        val protocol = BegodeProtocol(cellCount = 10); val events = capture(protocol)
        protocol.onNotification(cells(0, 1, listOf(0, 6000, 4000, 4000, 4000, 4000, 4000, 4000)))
        val indexed = events.single().readings.filter { it.metric.indexed }
        assertEquals(listOf(8, 9), indexed.map { it.cellIndex }); assertEquals(listOf(0.0, 6.0), indexed.map { it.value })
        assertTrue(indexed.all { it.unknown == null })
    }

    @Test fun undeclaredZeroPaddingDoesNotCreateCellsAndPreviouslySeenCellsAreInvalidated() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        protocol.onNotification(cells(0, 0, listOf(4000, 3900, 0, 0, 0, 0, 0, 0)))
        assertEquals(listOf(0, 1), events.single().readings.filter { it.metric.indexed }.map { it.cellIndex })
        protocol.onNotification(cells(0, 0, List(8) { 0 }))
        val indexed = events.last().readings.filter { it.metric.indexed }
        assertEquals(listOf(0, 1), indexed.map { it.cellIndex })
        assertTrue(indexed.all { it.unknown == TelemetryUnknown.UNAVAILABLE })
        protocol.reset(); protocol.onNotification(cells(0, 0, List(8) { 0 }))
        assertTrue(events.last().readings.none { it.metric.indexed })
    }

    @Test fun wholeDeclaredPackInOneNonBootPageCanEarnExtrema() {
        val protocol = BegodeProtocol(cellCount = 2); val events = capture(protocol)
        protocol.onNotification(cells(0, 0, listOf(4000, 0, 9999, 9999, 9999, 9999, 9999, 9999)))
        assertEquals(0.0, events.single().read(TelemetryMetric.CELL_MIN).value)
        assertEquals(4.0, events.single().read(TelemetryMetric.CELL_MAX).value)
        assertEquals(4000.0, events.single().read(TelemetryMetric.CELL_DELTA).value)
        protocol.onNotification(cells(0, 0, List(8) { 0 }))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.last().read(TelemetryMetric.CELL_MIN).unknown)
        assertTrue(events.last().readings.filter { it.metric.indexed }.all { it.unknown == TelemetryUnknown.UNAVAILABLE })
    }

    @Test fun invalidBranchAndMalformedOrFragmentedPacketsDoNotMintFacts() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        protocol.onNotification(telemetry(4)); assertTrue(events.isEmpty())
        val packet = telemetry(0)
        protocol.onNotification(packet.copyOfRange(0, 20)); assertTrue(events.isEmpty())
        protocol.onNotification(packet.copyOfRange(20, 24)); assertEquals(1, events.size)
        events.clear(); protocol.onNotification(cells(0, 0, List(8) { 4000 }).also { it[23] = 0 })
        assertTrue(events.isEmpty())
    }

    @Test fun observerFailuresCannotBreakSmartBatteryDecode() {
        val protocol = BegodeProtocol(); protocol.telemetryObserver = { error("injected observer failure") }
        protocol.onNotification(telemetry(0) + cells(0, 0, List(8) { 4000 }))
        assertNotNull(protocol.latestData(0)); assertEquals(3, protocol.telemetryObserverFailures)
    }

    @Test fun realStationaryCaptureSeparatesBootRepliesAndPhysicalBatteryPages() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        val battery = events.filter { it.origin == TelemetryOrigin.BATTERY }
        assertEquals(76, battery.size)
        assertEquals(setOf(0, 1), battery.map { it.localIndex }.toSet())
        val telemetry = battery.filter { event -> event.readings.any { it.metric == TelemetryMetric.VOLTAGE } }
        val pages = battery.filter { event -> event.readings.any { it.metric == TelemetryMetric.CELL_MIN } }
        assertEquals(38, telemetry.size); assertEquals(38, pages.size)
        // Retraction: the boot placeholder used to record CURRENT as UNAVAILABLE and POWER as PARTIAL_SOURCE; it records neither.
        assertTrue(telemetry.first().readings.none { it.metric == TelemetryMetric.CURRENT || it.metric == TelemetryMetric.POWER })
        assertTrue(telemetry.any { event -> event.readings.any { it.metric == TelemetryMetric.CURRENT && it.value != null } })
        assertTrue(telemetry.all { it.read(TelemetryMetric.VOLTAGE).unknown == TelemetryUnknown.PARTIAL_SOURCE })
        val cells = pages.flatMap { event -> event.readings.filter { it.metric.indexed } }
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.all { it.cellIndex in 0..39 })
        assertTrue(cells.any { it.cellIndex == 39 && it.value != null })
        assertTrue(cells.filter { it.value != null }.all { assertNotNull(it.value) in 3.69..3.73 })
    }

    @Test fun aWheelWithoutCellPagesProvesItsCountFromBranchVersusLiveVoltage() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        // Twice through, as a wheel streams continuously: the count is proven on the 6th 0x01 frame,
        // and the rail voltage surfaces on the next live frame after it.
        repeat(2) { BegodeT4Fixture.notifications.forEach { protocol.onNotification(it) } }
        // 16 x 90.8 / 60.53 = 24.0: the rider's T4 is 24S, and nothing in its profile said so.
        assertEquals(24, protocol.derivedCellCount())
        val rail = events.filter { it.origin == TelemetryOrigin.CONTROLLER }
            .mapNotNull { e -> e.readings.firstOrNull { it.metric == TelemetryMetric.INPUT_VOLTAGE } }
        assertEquals(90.7, assertNotNull(rail.last().value), 0.15)
        val branch = events.filter { it.origin == TelemetryOrigin.BATTERY && it.localIndex == 0 }
            .map { it.read(TelemetryMetric.VOLTAGE) }
        // Unproven at first (the old rule), then the certified pack voltage itself.
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, branch.first().unknown)
        assertEquals(90.75, assertNotNull(branch.last().value), 0.1)
    }

    @Test fun aRatioThatIsNotAWholeSeriesCountProvesNothing() {
        // Same capture with the 0x01 pack voltage nudged off any whole count (908 -> 935 = 24.7S).
        val protocol = BegodeProtocol()
        BegodeT4Fixture.notifications.forEach { frame ->
            val copy = frame.copyOf()
            for (k in 0 until copy.size - 1) if (copy[k] == 0x03.toByte() && copy[k + 1] == 0x8C.toByte()) { copy[k + 1] = 0xA7.toByte() }
            protocol.onNotification(copy)
        }
        assertNull(protocol.derivedCellCount())
    }
}
