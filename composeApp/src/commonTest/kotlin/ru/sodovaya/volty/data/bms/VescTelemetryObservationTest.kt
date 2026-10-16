package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class VescTelemetryObservationTest {
    private fun capture(protocol: VescProtocol, origin: TelemetryOrigin? = TelemetryOrigin.CONTROLLER) = mutableListOf<TelemetryObservation>().also { events ->
        protocol.telemetryObserver = { if (origin == null || (it.origin == origin && (origin != TelemetryOrigin.CONTROLLER || it.role == TelemetryRole.CONTRIBUTOR))) events += it }
    }
    private fun TelemetryObservation.read(metric: TelemetryMetric) = readings.single { it.metric == metric }
    private fun values(current: Int = 1000) = VescPacket.frame(VescTestFrames.valuesPayload(currentInRaw = current, vInRaw = 500))
    private fun setup(distance: Int = 100_000_000) = VescPacket.frame(VescTestFrames.setupPayload(tachAbsMRaw = distance))

    @Test fun derivedBatteryHasDistinctVirtualScopeAndKeepsEveryFreshPlainReplyPeak() {
        val protocol = VescProtocol(deriveBattery = true); val all = capture(protocol, null)
        protocol.onNotification(values(1000) + values(4000) + values(1000))
        val controllers = all.filter { it.origin == TelemetryOrigin.CONTROLLER }
        val batteries = all.filter { it.origin == TelemetryOrigin.BATTERY }
        assertEquals(3, controllers.size)
        assertEquals(3, batteries.size)
        assertTrue(batteries.all { it.role == TelemetryRole.DERIVED && it.localIndex == 0 && it.assemblyIndex == null })
        assertEquals(listOf(-500.0, -2000.0, -500.0), batteries.map { it.read(TelemetryMetric.POWER).value })
        assertEquals(listOf(-10.0, -40.0, -10.0), batteries.map { it.read(TelemetryMetric.CURRENT).value })
        assertTrue(batteries.all { b -> b.readings.none { it.metric == TelemetryMetric.SOC } })
        assertTrue(batteries.all { b -> b.readings.map { it.metric }.toSet() == setOf(
            TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT, TelemetryMetric.POWER) })
        assertEquals(listOf(500.0, 2000.0, 500.0), controllers.map { it.read(TelemetryMetric.CONTROLLER_POWER).value })
        assertEquals(-10f, assertNotNull(protocol.latestData()).current)
    }

    @Test fun setupDerivedGaugeIsFreshButBusSummedCurrentCannotBecomeOneBatteryMeasurement() {
        val protocol = VescProtocol(deriveBattery = true); val batteries = capture(protocol, TelemetryOrigin.BATTERY)
        protocol.onNotification(VescPacket.frame(VescTestFrames.setupPayload(battLevelRaw = 840, currentInRaw = 20_000)))
        val setupBattery = batteries.single()
        assertEquals(84.0, assertNotNull(setupBattery.read(TelemetryMetric.SOC).value), 0.001)
        assertEquals(78.2, assertNotNull(setupBattery.read(TelemetryMetric.VOLTAGE).value), 0.001)
        for (metric in listOf(TelemetryMetric.CURRENT, TelemetryMetric.POWER)) {
            assertTrue(setupBattery.readings.none { it.metric == metric })
        }
        protocol.onNotification(values())
        assertEquals(2, batteries.size)
        assertTrue(batteries.last().readings.none { it.metric == TelemetryMetric.SOC })
        assertEquals(-500.0, batteries.last().read(TelemetryMetric.POWER).value)
    }

    @Test fun zeroCurrentIsMeasuredWhileAbsentRailDoesNotReuseLastVoltageOrPower() {
        val protocol = VescProtocol(deriveBattery = true); val batteries = capture(protocol, TelemetryOrigin.BATTERY)
        protocol.onNotification(values(0))
        assertEquals(-0.0, batteries.single().read(TelemetryMetric.CURRENT).value)
        assertNull(batteries.single().read(TelemetryMetric.CURRENT).unknown)
        assertEquals(-0.0, batteries.single().read(TelemetryMetric.POWER).value)
        protocol.onNotification(VescPacket.frame(VescTestFrames.valuesPayload(currentInRaw = 0, vInRaw = 0)))
        assertEquals(2, batteries.size)
        for (metric in listOf(TelemetryMetric.VOLTAGE, TelemetryMetric.POWER)) {
            assertNull(batteries.last().read(metric).value)
            assertEquals(TelemetryUnknown.UNAVAILABLE, batteries.last().read(metric).unknown)
        }
    }

    @Test fun disabledDerivedBatteryAndUndecodedFramesCannotCreateVirtualObservations() {
        val physical = VescProtocol(deriveBattery = false); val physicalEvents = capture(physical, null)
        physical.onNotification(values()); physical.onNotification(setup())
        assertEquals(3, physicalEvents.size)
        assertTrue(physicalEvents.all { it.origin == TelemetryOrigin.CONTROLLER })
        assertEquals(2, physicalEvents.count { it.role == TelemetryRole.CONTRIBUTOR })
        assertEquals(1, physicalEvents.count { it.role == TelemetryRole.REPORTED_CAN })
        assertNull(physical.latestData())
        val virtual = VescProtocol(deriveBattery = true); val virtualEvents = capture(virtual, null)
        virtual.onNotification(values().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 1).toByte() })
        virtual.onNotification(VescPacket.frame(byteArrayOf(4, 0, 0)))
        virtual.reset()
        assertTrue(virtualEvents.isEmpty())
    }

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
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.single().read(TelemetryMetric.SPEED).unknown)
        assertTrue(events.single().readings.none { it.metric in setOf(TelemetryMetric.ODOMETER, TelemetryMetric.TRIP) })
        assertEquals(10_000.0, events.single().read(TelemetryMetric.ERPM).value)
        assertNotNull(events.single().read(TelemetryMetric.MOTOR_CURRENT).value)
    }

    @Test fun setupBusSummedCurrentsAreNotAttributedToOnePhysicalController() {
        val protocol = VescProtocol(); val events = capture(protocol)
        protocol.onNotification(values()); protocol.onNotification(setup())
        val event = events.last()
        for (metric in listOf(TelemetryMetric.MOTOR_CURRENT, TelemetryMetric.CONTROLLER_BATTERY_CURRENT, TelemetryMetric.CONTROLLER_POWER)) {
            assertTrue(event.readings.none { it.metric == metric })
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
        assertTrue(events.last().readings.none { it.metric == TelemetryMetric.TRIP })
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
        val protocol = VescProtocol()
        val attempted = mutableListOf<Pair<TelemetryOrigin, TelemetryRole>>()
        protocol.telemetryObserver = { attempted += it.origin to it.role; error("injected observer failure") }
        protocol.onNotification(values()); assertNotNull(protocol.latestMotion(0)); assertNotNull(protocol.latestData())
        assertEquals(listOf(TelemetryOrigin.CONTROLLER to TelemetryRole.CONTRIBUTOR,
            TelemetryOrigin.BATTERY to TelemetryRole.DERIVED), attempted)
        assertEquals(2, protocol.telemetryObserverFailures)
    }
}
