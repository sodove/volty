package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class BegodeAssemblyObservationTest {
    private fun word(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 8).toByte(); bytes[offset + 1] = value.toByte()
    }
    private fun telemetry(address: Int, first: Int = 25, second: Int = 26, voltage: Int = 750): ByteArray =
        ByteArray(24).also {
            it[0] = 0x55; it[1] = 0xaa.toByte(); it[18] = 1; it[19] = address.toByte()
            for (index in 20..23) it[index] = 0x5a
            word(it, 6, 1500); word(it, 8, 10)
            word(it, 10, first); word(it, 12, second); word(it, 14, voltage)
        }
    private fun capture(p: BegodeProtocol) = mutableListOf<TelemetryObservation>().also { events ->
        p.telemetryObserver = { if (it.role == TelemetryRole.ASSEMBLY) events += it }
    }
    private fun TelemetryObservation.temperature() = readings.single { it.metric == TelemetryMetric.BATTERY_TEMPERATURE }

    @Test fun acceptedFramesKeepEachAssemblyAddressAndNeverReuseOtherAssemblySensors() {
        val p = BegodeProtocol(); val events = capture(p)
        p.onNotification(telemetry(0, 1, 2) + telemetry(1, 100, 99) + telemetry(2, -10, -20) + telemetry(3, 5, 6))
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), events.map { it.localIndex to it.assemblyIndex })
        assertEquals(listOf(2.0, 100.0, -10.0, 6.0), events.map { it.temperature().value })
        assertTrue(events.all { it.origin == TelemetryOrigin.BATTERY && it.readings.size == 2 })
        assertTrue(events.all { it.readings.single { row -> row.metric == TelemetryMetric.VOLTAGE }.value == 75.0 })
        p.onNotification(telemetry(0, 0, 0))
        assertEquals(0.0, events.last().temperature().value)
    }

    @Test fun bootInvalidatesAssemblyTemperatureButRealWinterZeroIsKnown() {
        val p = BegodeProtocol(); val events = capture(p)
        p.onNotification(telemetry(0, 0, 0, 0))
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.single().temperature().unknown)
        p.onNotification(telemetry(0, 0, 0, 750))
        assertEquals(0.0, events.last().temperature().value)
        p.onNotification(telemetry(0, 0, 0, 0))
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.last().temperature().unknown)
    }

    @Test fun reportedAssemblyVoltageUsesItsOwnFieldAndNeverTheNominalBranchOrCachedCells() {
        val p = BegodeProtocol(); val events = capture(p)
        p.onNotification(telemetry(0, voltage = 745) + telemetry(1, voltage = 0) + telemetry(2, voltage = -1))
        val values = events.map { it.readings.single { row -> row.metric == TelemetryMetric.VOLTAGE } }
        assertEquals(74.5, values[0].value) // Whole-branch field on this fixture is deliberately 150 V.
        assertEquals(0.0, values[1].value) // A live non-boot frame really reports zero here.
        assertEquals(TelemetryUnknown.INVALID_VALUE, values[2].unknown)
        p.onNotification(telemetry(0, 0, 0, 0))
        assertEquals(TelemetryUnknown.UNAVAILABLE,
            events.last().readings.single { it.metric == TelemetryMetric.VOLTAGE }.unknown)
    }

    @Test fun anInvalidActiveSensorCannotYieldAnUnderreportedAssemblyMaximum() {
        val p = BegodeProtocol(); val events = capture(p)
        p.onNotification(telemetry(0, 25, 170))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.single().temperature().unknown)
        assertNull(events.single().temperature().value)
        p.onNotification(telemetry(0, -39, -30))
        assertEquals(-30.0, events.last().temperature().value)
        p.onNotification(telemetry(0, -40, 10))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.last().temperature().unknown)
    }

    @Test fun onlyCompletedAcceptedFramesEmitAndBegodeRemainsReadOnly() {
        val p = BegodeProtocol(); val events = capture(p); val packet = telemetry(2)
        p.onNotification(packet.copyOfRange(0, 20)); assertTrue(events.isEmpty())
        p.onNotification(packet.copyOfRange(20, 24)); assertEquals(1, events.size)
        events.clear()
        p.onNotification(telemetry(9) + telemetry(0).also { it[23] = 0 })
        assertTrue(events.isEmpty())
        assertTrue(p.handshakeCommands().isEmpty()); assertTrue(p.pollCommands().isEmpty())
        p.reset(); p.onNotification(telemetry(1, 10, 11))
        assertEquals(11.0, events.single().temperature().value)
    }

    @Test fun realEtMaxCaptureKeepsAssemblyFactsSeparateFromWholeBranchSummaries() {
        val p = BegodeProtocol(); val events = capture(p)
        BegodeDumpFixture.chunks().forEach { p.onNotification(it) }
        assertEquals(38, events.size)
        assertEquals(setOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), events.map { it.localIndex to it.assemblyIndex }.toSet())
        assertTrue(events.any { it.temperature().unknown == TelemetryUnknown.UNAVAILABLE })
        assertTrue(events.any { it.temperature().value != null })
        assertTrue(events.mapNotNull { event -> event.readings.single { it.metric == TelemetryMetric.VOLTAGE }.value }.isNotEmpty())
        assertTrue(events.flatMap { it.readings }.none { it.metric == TelemetryMetric.CURRENT || it.metric == TelemetryMetric.CELL })
    }
}
