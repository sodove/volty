package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class BegodeTelemetryObservationTest {
    private fun frame(type: Int, fill: (ByteArray) -> Unit = {}): ByteArray = ByteArray(24).also {
        it[0] = 0x55; it[1] = 0xaa.toByte(); it[18] = type.toByte(); it[19] = 24
        for (index in 20..23) it[index] = 0x5a
        fill(it)
    }
    private fun word(frame: ByteArray, offset: Int, value: Int) {
        frame[offset] = (value shr 8).toByte(); frame[offset + 1] = value.toByte()
    }
    private fun live(speed: Int = -100, phase: Int = -300) = frame(0) {
        word(it, 2, 6000); word(it, 4, speed); word(it, 10, phase); word(it, 12, -3060)
    }
    private fun motion(current: Int = 100, temperature: Int = 20, duty: Int = 2) = frame(7) {
        word(it, 2, current); word(it, 4, 1); word(it, 6, temperature); word(it, 8, duty)
    }
    private fun odometer(metres: Int) = frame(4) {
        it[2] = (metres shr 24).toByte(); it[3] = (metres shr 16).toByte()
        it[4] = (metres shr 8).toByte(); it[5] = metres.toByte(); it[13] = 1
    }
    private fun capture(protocol: BegodeProtocol) = mutableListOf<TelemetryObservation>().also { events -> protocol.telemetryObserver = { events += it } }
    private fun TelemetryObservation.read(metric: TelemetryMetric) = readings.single { it.metric == metric }

    @Test fun frameFamiliesNeverRestampEachOthersCachedFields() {
        val protocol = BegodeProtocol(cellCount = 40); val events = capture(protocol)
        protocol.onNotification(live() + motion() + odometer(100_000))
        assertEquals(3, events.size)
        // Retraction: an earlier revision pinned PARTIAL_SOURCE here; that alternation emptied the chart overview and
        // blocked energy (plausibility spec §4). The 0x00 frame now omits CONTROLLER_POWER.
        assertEquals(setOf(TelemetryMetric.SPEED, TelemetryMetric.MOTOR_CURRENT, TelemetryMetric.ESC_TEMPERATURE,
            TelemetryMetric.INPUT_VOLTAGE), events[0].readings.map { it.metric }.toSet())
        assertEquals(setOf(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, TelemetryMetric.MOTOR_TEMPERATURE,
            TelemetryMetric.DUTY, TelemetryMetric.CONTROLLER_POWER), events[1].readings.map { it.metric }.toSet())
        assertEquals(setOf(TelemetryMetric.ODOMETER, TelemetryMetric.TRIP), events[2].readings.map { it.metric }.toSet())
        assertTrue(events.all { it.origin == TelemetryOrigin.CONTROLLER && it.localIndex == 0 })
        assertEquals(3.6, assertNotNull(events[0].read(TelemetryMetric.SPEED).value), 0.001)
        assertEquals(3.0, events[0].read(TelemetryMetric.MOTOR_CURRENT).value)
        assertEquals(1.0, events[1].read(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value)
        assertEquals(100.0, events[2].read(TelemetryMetric.ODOMETER).value)
    }

    @Test fun calibratedVoltageRequiresTopologyAndPowerCannotUsePhaseOrCachedBatteryCurrent() {
        val configured = BegodeProtocol(cellCount = 40); val events = capture(configured)
        configured.onNotification(live(phase = -999) + motion(current = 500) + live(phase = -1000))
        assertEquals(150.0, events[0].read(TelemetryMetric.INPUT_VOLTAGE).value)
        // Phase current never feeds power; the 0x07 right after a live frame joins that frame's voltage with ITS OWN current.
        // Retraction: an earlier revision pinned PARTIAL_SOURCE here; that alternation emptied the chart overview and
        // blocked energy (plausibility spec §4). The live frame omits power.
        assertTrue(events[0].readings.none { it.metric == TelemetryMetric.CONTROLLER_POWER })
        assertEquals(750.0, assertNotNull(events[1].read(TelemetryMetric.CONTROLLER_POWER).value), 0.01)
        // Retraction: an earlier revision pinned PARTIAL_SOURCE here; that alternation emptied the chart overview and
        // blocked energy (plausibility spec §4).
        assertTrue(events[2].readings.none { it.metric == TelemetryMetric.CONTROLLER_POWER })
        assertEquals(5.0, events[1].read(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value)
        assertTrue(events[2].readings.none { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT })
        val unconfigured = BegodeProtocol(); val unknown = capture(unconfigured)
        unconfigured.onNotification(live())
        assertEquals(TelemetryUnknown.UNAVAILABLE, unknown.single().read(TelemetryMetric.INPUT_VOLTAGE).unknown)
    }

    @Test fun t4ShapedSequenceRecordsPowerAsVoltageTimesCurrentOnTheMotionFrame() {
        val protocol = BegodeProtocol(cellCount = 40); val events = capture(protocol)
        protocol.onNotification(live()); protocol.onNotification(motion(current = 220))
        val power = events.last().read(TelemetryMetric.CONTROLLER_POWER)
        assertNull(power.unknown)
        assertEquals(150.0 * 2.2, assertNotNull(power.value), 0.05)
        // The live frame itself still cannot pair (its battery current is the previous 0x07's), so it says nothing.
        // Retraction: an earlier revision pinned PARTIAL_SOURCE here; that alternation emptied the chart overview and
        // blocked energy (plausibility spec §4).
        assertTrue(events.first().readings.none { it.metric == TelemetryMetric.CONTROLLER_POWER })
    }

    @Test fun theLiveFrameOmitsControllerPowerEvenWhenTheDecoderHoldsAJoinablePair() {
        // Incoherent on purpose: hasPower = true and powerW = 4200 on a 0x00 frame, with a fresh join. No producer
        // emits this, which is why it separates the contract (the live frame never speaks for power) from habit.
        val data = ControllerData().copy(hasPower = true, powerW = 4200f, speedKmh = 12f)
        val readings = observedBegodeController(0x00, data, powerJoinFresh = true)
        assertTrue(readings.isNotEmpty())
        assertTrue(readings.none { it.metric == TelemetryMetric.CONTROLLER_POWER })
        // The motion frame is the one that records it.
        assertEquals(4200.0, observedBegodeController(0x07, data, powerJoinFresh = true)
            .single { it.metric == TelemetryMetric.CONTROLLER_POWER }.value)
    }

    @Test fun motionFrameWithoutARecentLiveFrameKeepsPowerUnknown() {
        val fresh = BegodeProtocol(cellCount = 40); val alone = capture(fresh)
        fresh.onNotification(motion(current = 220))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, alone.single().read(TelemetryMetric.CONTROLLER_POWER).unknown)

        // The voltage ages in wheel frames: three motion frames after the last live frame still pair, the fourth does not.
        val aging = BegodeProtocol(cellCount = 40); val events = capture(aging)
        aging.onNotification(live()); repeat(4) { aging.onNotification(motion(current = 220)) }
        val motions = events.drop(1).map { it.read(TelemetryMetric.CONTROLLER_POWER) }
        assertTrue(motions.take(3).all { it.value != null }, "within the bound")
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, motions[3].unknown)
        aging.onNotification(live()); aging.onNotification(motion(current = 220))
        assertNotNull(events.last().read(TelemetryMetric.CONTROLLER_POWER).value, "a new live frame renews the join")

        aging.reset(); aging.onNotification(motion(current = 220))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.last().read(TelemetryMetric.CONTROLLER_POWER).unknown, "reset forgets the live frame")
    }

    @Test fun anUnconfiguredWheelHasNoVoltageSoNoPower() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        protocol.onNotification(live()); protocol.onNotification(motion(current = 220))
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, events.last().read(TelemetryMetric.CONTROLLER_POWER).unknown)
    }

    @Test fun coalescedFramesRetainIntermediateSpeedAndCurrentPeaks() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        protocol.onNotification(live(-100) + live(-500) + live(-100) + motion(100) + motion(400) + motion(100))
        assertEquals(6, events.size)
        assertEquals(listOf(1.0, 4.0, 1.0), events.drop(3).map { it.read(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value })
        assertEquals(18.0, assertNotNull(events[1].read(TelemetryMetric.SPEED).value), 0.001)
        assertEquals(3.6, assertNotNull(events[2].read(TelemetryMetric.SPEED).value), 0.001)
    }

    @Test fun sensorEvidencePreservesUnknownThenEarnedZeroAndResetClearsLatches() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        protocol.onNotification(motion(current = 0, temperature = 0, duty = 0))
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.single().read(TelemetryMetric.MOTOR_TEMPERATURE).unknown)
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.single().read(TelemetryMetric.DUTY).unknown)
        protocol.onNotification(live()); protocol.onNotification(motion(temperature = 20, duty = 5))
        protocol.onNotification(motion(temperature = 20, duty = 5)) // Task 7: the temperature latch needs two consecutive frames
        protocol.onNotification(motion(current = 0, temperature = 0, duty = 0))
        assertEquals(0.0, events.last().read(TelemetryMetric.MOTOR_TEMPERATURE).value)
        assertEquals(0.0, events.last().read(TelemetryMetric.DUTY).value)
        // Negating charge-positive zero can retain IEEE -0.0; both are earned numeric zero, not absence.
        assertEquals(0.0, assertNotNull(events.last().read(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value), 0.0)
        protocol.reset(); protocol.onNotification(motion(current = 0, temperature = 0, duty = 0))
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.last().read(TelemetryMetric.MOTOR_TEMPERATURE).unknown)
        assertEquals(TelemetryUnknown.UNAVAILABLE, events.last().read(TelemetryMetric.DUTY).unknown)
    }

    @Test fun odometerBaselineIsOnlyRefreshedByRealDistanceFrames() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        // Retraction (telemetry-plausibility Task 4): the trip is now the sum of ACCEPTED increments, and with no
        // witness (the first 0x04 preceded any live frame) a step is decided one 0x04 late. This test used to
        // assert TRIP 1.0 on the second 0x04 itself; that frame is now held until the next one confirms it.
        protocol.onNotification(odometer(100_000) + live() + motion() + odometer(101_000))
        assertEquals(0.0, events.first().read(TelemetryMetric.TRIP).value)
        assertEquals(3, events.size, "the second 0x04 is held one frame without a witness")
        protocol.onNotification(odometer(101_000))
        assertEquals(4, events.size)
        assertEquals(1.0, events.last().read(TelemetryMetric.TRIP).value)
        protocol.onNotification(frame(4)) // boot placeholder does not reset the sum or publish distance zero.
        assertEquals(4, events.size)
        protocol.reset(); protocol.onNotification(odometer(110_000))
        assertEquals(0.0, events.last().read(TelemetryMetric.TRIP).value)
    }

    @Test fun fragmentsMalformedTailAndBootPlaceholdersCannotPublishControllerFacts() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        val packet = live()
        protocol.onNotification(packet.copyOfRange(0, 20)); assertTrue(events.isEmpty())
        protocol.onNotification(packet.copyOfRange(20, 24)); assertEquals(1, events.size)
        events.clear(); protocol.onNotification(frame(0) + frame(4) + frame(7))
        protocol.onNotification(live().also { it[23] = 0 })
        assertTrue(events.isEmpty())
        protocol.onNotification(motion()); assertEquals(1, events.size)
        assertTrue(events.single().readings.none { it.metric == TelemetryMetric.ESC_TEMPERATURE || it.metric == TelemetryMetric.MOTOR_CURRENT })
    }

    @Test fun realStationaryCaptureRetainsControllerGroupsAndKeepsBegodeReadOnly() {
        val protocol = BegodeProtocol(); val events = capture(protocol)
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        assertTrue(events.any { it.origin == TelemetryOrigin.BATTERY })
        events.removeAll { it.origin != TelemetryOrigin.CONTROLLER }
        // Telemetry-plausibility Task 4: the stationary capture's odometer never moves, so R1's witness is never
        // proven and the monotone lookahead holds the LAST 0x04 until a next one that never comes: 37 of 38.
        assertEquals(113, events.size)
        assertTrue(events.all { it.origin == TelemetryOrigin.CONTROLLER && it.readings.none { reading -> reading.metric == TelemetryMetric.ERPM } })
        assertEquals(38, events.count { event -> event.readings.any { it.metric == TelemetryMetric.SPEED } })
        assertEquals(37, events.count { event -> event.readings.any { it.metric == TelemetryMetric.ODOMETER } })
        assertEquals(38, events.count { event -> event.readings.any { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT } })
        assertTrue(protocol.handshakeCommands().isEmpty()); assertTrue(protocol.pollCommands().isEmpty())
    }

    @Test fun observerFailureCannotInterruptLiveControllerDecode() {
        val protocol = BegodeProtocol(); protocol.telemetryObserver = { error("injected observer failure") }
        protocol.onNotification(live() + motion() + odometer(100_000))
        assertNotNull(protocol.latestMotion(0)); assertEquals(3, protocol.telemetryObserverFailures)
    }
}
