package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class VescTelemetryObservationTest {
    private fun capture(protocol: VescProtocol) = mutableListOf<TelemetryObservation>().also { events ->
        protocol.telemetryObserver = { events += it }
    }
    private fun TelemetryObservation.read(metric: TelemetryMetric) = readings.single { it.metric == metric }
    private fun values(current: Int = 1000) = VescPacket.frame(VescTestFrames.valuesPayload(currentInRaw = current, vInRaw = 500))
    private fun setup(distance: Int = 100_000_000) = VescPacket.frame(VescTestFrames.setupPayload(tachAbsMRaw = distance))

    @Test fun everyPlainValuesReplyKeepsIntermediatePeaksAndPhysicalControllerScope() {
        val protocol = VescProtocol(deriveBattery = true, motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254)); val events = capture(protocol)
        protocol.onNotification(values(1000) + values(4000) + values(1000))
        assertEquals(3, events.size)
        assertTrue(events.all { it.origin == TelemetryOrigin.CONTROLLER && it.localIndex == 0 })
        assertEquals(listOf(500.0, 2000.0, 500.0), events.map { it.read(TelemetryMetric.CONTROLLER_POWER).value })
        assertEquals(listOf(10.0, 40.0, 10.0), events.map { it.read(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value })
        assertEquals(31.9, assertNotNull(events.first().read(TelemetryMetric.SPEED).value), 0.3)
        assertEquals(52.0, events.first().read(TelemetryMetric.ESC_TEMPERATURE).value)
        assertEquals(68.0, events.first().read(TelemetryMetric.MOTOR_TEMPERATURE).value)
        assertEquals(-10f, assertNotNull(protocol.latestData()).current)
        assertTrue(events.all { event -> event.readings.none { it.metric.origin == TelemetryOrigin.BATTERY } })
    }

    @Test fun plainCountsAndMissingGeometryDoNotBecomeEarnedDistanceOrSpeed() {
        val protocol = VescProtocol(motor = MotorConfig(wheelDiameterMm = 0)); val events = capture(protocol)
        protocol.onNotification(values())
        for (metric in listOf(TelemetryMetric.SPEED, TelemetryMetric.ODOMETER, TelemetryMetric.TRIP)) {
            assertEquals(TelemetryUnknown.UNAVAILABLE, events.single().read(metric).unknown)
        }
        assertEquals(10_000.0, events.single().read(TelemetryMetric.ERPM).value)
        assertNotNull(events.single().read(TelemetryMetric.MOTOR_CURRENT).value)
    }

    @Test fun setupBusSummedCurrentsAreNotAttributedToOnePhysicalController() {
        val protocol = VescProtocol(); val events = capture(protocol)
        protocol.onNotification(values()); protocol.onNotification(setup())
        val event = events.last()
        for (metric in listOf(TelemetryMetric.MOTOR_CURRENT, TelemetryMetric.CONTROLLER_BATTERY_CURRENT, TelemetryMetric.CONTROLLER_POWER)) {
            assertNull(event.read(metric).value)
            assertEquals(TelemetryUnknown.PARTIAL_SOURCE, event.read(metric).unknown)
        }
        assertNotNull(event.read(TelemetryMetric.SPEED).value)
        assertNotNull(event.read(TelemetryMetric.DUTY).value)
        assertEquals(100.0, event.read(TelemetryMetric.ODOMETER).value)
    }

    @Test fun plainReplyCannotSeedSetupDistanceBaselineOrRestampSetupDistance() {
        val protocol = VescProtocol(); val events = capture(protocol)
        protocol.onNotification(values()); protocol.onNotification(setup(100_000_000))
        assertEquals(0.0, events.last().read(TelemetryMetric.TRIP).value)
        protocol.onNotification(values())
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.last().read(TelemetryMetric.TRIP).unknown)
        protocol.onNotification(setup(101_000_000))
        assertEquals(1.0, events.last().read(TelemetryMetric.TRIP).value)
        protocol.reset(); protocol.onNotification(setup(110_000_000))
        assertEquals(0.0, events.last().read(TelemetryMetric.TRIP).value)
    }

    @Test fun unavailableRailAndTemperatureSentinelsKeepTypedAbsenceWhileZeroDutyIsKnown() {
        val protocol = VescProtocol(); val events = capture(protocol)
        protocol.onNotification(VescPacket.frame(VescTestFrames.valuesPayload(vInRaw = 0, tempMosRaw = -1000, tempMotorRaw = -1000, dutyRaw = 0)))
        val event = events.single()
        for (metric in listOf(TelemetryMetric.INPUT_VOLTAGE, TelemetryMetric.CONTROLLER_POWER, TelemetryMetric.ESC_TEMPERATURE, TelemetryMetric.MOTOR_TEMPERATURE))
            assertEquals(TelemetryUnknown.UNAVAILABLE, event.read(metric).unknown)
        assertEquals(0.0, event.read(TelemetryMetric.DUTY).value)
    }

    @Test fun fragmentsBadCrcShortPayloadAndUnknownOpcodesProduceNoObservations() {
        val protocol = VescProtocol(); val events = capture(protocol)
        val frame = values()
        protocol.onNotification(frame.copyOfRange(0, 8)); assertTrue(events.isEmpty())
        protocol.onNotification(frame.copyOfRange(8, frame.size)); assertEquals(1, events.size)
        events.clear(); protocol.onNotification(values().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 1).toByte() })
        protocol.onNotification(VescPacket.frame(byteArrayOf(4, 0, 0)))
        protocol.onNotification(VescPacket.frame(byteArrayOf(99, 0, 0)))
        assertTrue(events.isEmpty())
    }

    @Test fun observerFailureDoesNotBreakMotionOrDerivedBattery() {
        val protocol = VescProtocol(); protocol.telemetryObserver = { error("injected observer failure") }
        protocol.onNotification(values()); assertNotNull(protocol.latestMotion(0)); assertNotNull(protocol.latestData())
        assertEquals(1, protocol.telemetryObserverFailures)
    }
}
