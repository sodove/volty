package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.sodovaya.volty.data.controller.kelly.EtsChecksum
import ru.sodovaya.volty.data.controller.kelly.EtsCommand
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.AlertUnavailableReason
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.armedRules
import ru.sodovaya.volty.domain.alert.availabilityFor
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.SpeedUnknownReason
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.presentation.ride.CleanMetricMapper
import ru.sodovaya.volty.presentation.ride.ClassicDialSpecs
import ru.sodovaya.volty.presentation.ride.RideDistanceMapper
import ru.sodovaya.volty.presentation.ride.SecondaryGaugeMapper
import ru.sodovaya.volty.presentation.ride.UNKNOWN_READOUT
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.PackAggregator
import ru.sodovaya.volty.presentation.common.BmsMetricMapper
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class KellyProtocolTest {

    @Test
    fun `handshake is a one-shot CODE_VERSION and monitor poll is framed in ETS order`() {
        val protocol = KellyProtocol()

        assertContentEquals(byteArrayOf(0x11, 0x00, 0x11), protocol.handshakeCommands().single())
        assertTrue(protocol.handshakeCommands().isEmpty())
        assertEquals(listOf(0x3A, 0x3B, 0x3C), protocol.pollCommands().map { it[0].toInt() and 0xFF })
        assertTrue(protocol.pollCommands().all { it[1] == 0.toByte() && it[2] == it[0] })
    }

    @Test
    fun `three matching monitor replies publish all supported controller data honestly`() = runTest {
        val protocol = KellyProtocol(motor = MotorConfig(wheelDiameterMm = 500, gearRatio = 2f))
        val harness = PollHarness(protocol, backgroundScope)
        try {
            assertEquals(0, protocol.packCount)
            assertNull(protocol.latestData(0))
            harness.completeCycle(monitorData())

            val motion = requireNotNull(protocol.latestMotion(0))
            assertEquals(1, protocol.controllerCount)
            assertEquals(72f, motion.inputVoltageV)
            assertTrue(motion.hasInputVoltage)
            assertEquals(52f, motion.motorTempC)
            assertTrue(motion.hasMotorTemp)
            assertEquals(44f, motion.escTempC)
            assertEquals(1_500f, motion.eRpm)
            assertEquals(300f, motion.motorCurrentA)
            assertEquals(SpeedSource.DERIVED, motion.speedSource)
            assertEquals(null, motion.speedUnknownReason)
            assertEquals(282.74335f, motion.speedKmh, 0.01f)
            assertEquals(listOf("Over Volt", "Motor OverTemp Err"), motion.faults)
            assertFalse(motion.hasDuty)
            assertEquals(0f, motion.dutyPercent)
            assertEquals(0f, motion.batteryCurrentA)
            assertFalse(motion.hasPower)
            assertEquals(0f, motion.powerW)
            assertFalse(motion.hasEnergyCounters)
            assertEquals(0f, motion.odometerKm)
            assertEquals(0f, motion.tripKm)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `monitor response is not published until all three armed command replies arrive`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            val monitor = monitorData()
            harness.respond(EtsCommand.USER_MONITOR1, monitor, 0)
            harness.respond(EtsCommand.USER_MONITOR2, monitor, 16)
            assertNull(protocol.latestMotion(0))

            harness.respond(EtsCommand.USER_MONITOR3, monitor, 32)

            assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `unsolicited monitor packets cannot create a controller sample`() {
        val protocol = KellyProtocol()

        monitorPackets(monitorData()).forEach(protocol::onNotification)

        assertNull(protocol.latestMotion(0))
    }

    @Test
    fun `a monitor reply split across BLE notifications is reassembled while its request is armed`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            val monitor = monitorData()
            harness.expect(EtsCommand.USER_MONITOR1)
            val first = monitorResponse(EtsCommand.USER_MONITOR1, monitor, 0)
            protocol.onNotification(first.copyOfRange(0, 7))
            protocol.onNotification(first.copyOfRange(7, first.size))
            harness.respond(EtsCommand.USER_MONITOR2, monitor, 16)
            harness.respond(EtsCommand.USER_MONITOR3, monitor, 32)

            assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `coalesced stale tail after a valid reply cannot contaminate the next armed response`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            val staleMonitor = monitorData().also {
                it[18] = 0
                it[19] = 1
                it[20] = 0
                it[21] = 2
            }
            val stale = monitorResponse(EtsCommand.USER_MONITOR2, staleMonitor, 16)
            harness.expect(EtsCommand.USER_MONITOR1)
            protocol.onNotification(
                monitorResponse(EtsCommand.USER_MONITOR1, monitorData(), 0) + stale.copyOfRange(0, 7)
            )

            val fresh = monitorData()
            harness.respond(EtsCommand.USER_MONITOR2, fresh, 16)
            harness.respond(EtsCommand.USER_MONITOR3, fresh, 32)

            assertEquals(1_500f, protocol.latestMotion(0)?.eRpm)
            assertEquals(300f, protocol.latestMotion(0)?.motorCurrentA)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `serial poll waits for the first monitor response before writing the second`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            val monitor = monitorData()
            harness.expect(EtsCommand.USER_MONITOR1)
            harness.assertNoNextWrite()
            protocol.onNotification(monitorResponse(EtsCommand.USER_MONITOR1, monitor, 0))
            harness.respond(EtsCommand.USER_MONITOR2, monitor, 16)
            harness.respond(EtsCommand.USER_MONITOR3, monitor, 32)

            assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `wrong command rejects the armed partial monitor cycle instead of mixing it with a later cycle`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            val monitor = monitorData()
            harness.respond(EtsCommand.USER_MONITOR1, monitor, 0)
            harness.expect(EtsCommand.USER_MONITOR2)
            protocol.onNotification(monitorResponse(EtsCommand.USER_MONITOR3, monitor, 32))
            assertNull(protocol.latestMotion(0))

            // The unmatched request times out; only a new, fully armed cycle
            // may publish after that boundary.
            harness.completeCycle(monitor)
            assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `bad checksum rejects the armed partial monitor cycle`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            val monitor = monitorData()
            harness.respond(EtsCommand.USER_MONITOR1, monitor, 0)
            harness.expect(EtsCommand.USER_MONITOR2)
            protocol.onNotification(monitorResponse(EtsCommand.USER_MONITOR2, monitor, 16).also { it[it.lastIndex] = 0 })
            assertNull(protocol.latestMotion(0))

            harness.completeCycle(monitor)
            assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `stale fragment after a cycle is discarded before the next request`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            harness.completeCycle(monitorData())
            val stale = monitorResponse(EtsCommand.USER_MONITOR1, monitorData(voltage = 7), 0)
            protocol.onNotification(stale.copyOfRange(0, 7))

            val fresh = monitorData(voltage = 73)
            harness.respond(EtsCommand.USER_MONITOR1, fresh, 0)
            harness.respond(EtsCommand.USER_MONITOR2, fresh, 16)
            harness.respond(EtsCommand.USER_MONITOR3, fresh, 32)

            assertEquals(73f, protocol.latestMotion(0)?.inputVoltageV)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `unknown wheel geometry keeps speed unavailable but retains mechanical RPM`() = runTest {
        val protocol = KellyProtocol(motor = MotorConfig(wheelDiameterMm = 0, gearRatio = 2f))
        val harness = PollHarness(protocol, backgroundScope)
        try {
            harness.completeCycle(monitorData())

            val motion = requireNotNull(protocol.latestMotion(0))
            assertEquals(1_500f, motion.eRpm)
            assertEquals(0f, motion.speedKmh)
            assertEquals(SpeedSource.NONE, motion.speedSource)
            assertEquals(SpeedUnknownReason.NO_WHEEL_GEOMETRY, motion.speedUnknownReason)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `out of range temperature bytes do not claim either temperature sensor exists`() = runTest {
        val protocol = KellyProtocol()
        val harness = PollHarness(protocol, backgroundScope)
        try {
            val monitor = monitorData().also {
                it[10] = 0xFF
                it[11] = 0xFF
            }
            harness.completeCycle(monitor)

            val motion = requireNotNull(protocol.latestMotion(0))
            assertFalse(motion.hasMotorTemp)
            assertTrue(motion.escTempC < -50f)
            assertFalse(motion.hasEscTemp)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `derived pack is opt-in and is cleared by reset with controller state`() = runTest {
        val protocol = KellyProtocol(deriveBattery = true)
        val harness = PollHarness(protocol, backgroundScope)
        try {
            assertEquals(1, protocol.packCount)
            harness.completeCycle(monitorData())
            assertEquals(72f, protocol.latestData(0)?.voltage)
            val derived = requireNotNull(protocol.latestData(0))
            assertFalse(derived.socKnown)
            assertFalse(derived.hasCurrent, "Kelly supplied phase current, not battery current")
            assertFalse(derived.hasPower, "Kelly supplied no input-power measurement")
            assertNull(BmsMetricMapper.currentValue(derived))
            assertNull(BmsMetricMapper.powerValue(derived))
            val aggregate = PackAggregator.aggregate(
                listOf(
                    PackState(
                        Pack(0, "Kelly", BmsType.VESC_BMS, "AA:BB"),
                        derived,
                        isOnline = true
                    )
                ),
                PackTopology.PARALLEL
            )
            assertFalse(aggregate.hasCurrent)
            assertFalse(aggregate.hasPower)
            assertNull(BmsMetricMapper.currentValue(aggregate))
            assertNull(BmsMetricMapper.powerValue(aggregate))

            protocol.reset()

            assertNull(protocol.latestMotion(0))
            assertNull(protocol.latestData(0))
            assertContentEquals(byteArrayOf(0x11, 0x00, 0x11), protocol.handshakeCommands().single())
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `Kelly monitor output arms only measured alerts and never renders missing battery current as zero`() = runTest {
        val protocol = KellyProtocol(motor = MotorConfig(wheelDiameterMm = 500, gearRatio = 2f))
        val harness = PollHarness(protocol, backgroundScope)
        try {
            harness.completeCycle(monitorData())
            val motion = requireNotNull(protocol.latestMotion(0))
            val kelly = Vehicle(
                id = "kelly", name = "KLS", iconKey = "scooter", packs = emptyList(),
                controllers = listOf(Controller(0, "KLS", ControllerType.KELLY, "AA:BB")),
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = Clock.System.now()
            )

            val availability = availabilityFor(kelly, motion)
            assertEquals(
                AlertAvailability.Unavailable(
                    AlertUnavailableReason.ControllerReportsNoDuty(ControllerType.KELLY)
                ),
                availability[MotionAlertKind.DUTY]
            )
            assertEquals(AlertAvailability.Available, availability[MotionAlertKind.SPEED])
            assertEquals(AlertAvailability.Available, availability[MotionAlertKind.MOTOR_TEMP])
            assertEquals(AlertAvailability.Available, availability[MotionAlertKind.ESC_TEMP])
            assertEquals(
                listOf(MotionAlertKind.SPEED, MotionAlertKind.MOTOR_TEMP, MotionAlertKind.ESC_TEMP),
                armedRules(
                    kelly,
                    motion,
                    MotionAlertKind.entries.map { AlertRule(it, listOf(AlertLevel(1f))) }
                ).rules.map { it.kind }
            )

            // The phase-current measurement survives. Battery current is absent on the wire,
            // so it must not become the confident 0 A that the old mapper rendered.
            assertEquals(300f, motion.motorCurrentA)
            assertFalse(motion.hasBatteryCurrent)
            assertFalse(motion.hasDistance)
            assertEquals(UNKNOWN_READOUT, CleanMetricMapper.powerValue(motion))
            assertEquals(UNKNOWN_READOUT, CleanMetricMapper.powerSub(motion))
            assertEquals(
                UNKNOWN_READOUT,
                SecondaryGaugeMapper.map(
                    SecondaryGauge.CURRENT, motion, BmsData(), UnitSystem.METRIC
                ).value
            )
            assertEquals(
                UNKNOWN_READOUT,
                ClassicDialSpecs.build(motion, BmsData(), UnitSystem.METRIC, maxSpeedKmh = 70f)
                    .single { it.slot == VescClusterSlot.CURRENT }
                    .valueTextOverride
            )
            assertNull(MotionReadings.batteryCurrentA(motion))
            assertNull(MotionReadings.odometerKm(motion))
            assertNull(MotionReadings.tripKm(motion))
            assertEquals(UNKNOWN_READOUT, RideDistanceMapper.odometerValue(motion, UnitSystem.METRIC))
            assertEquals(UNKNOWN_READOUT, RideDistanceMapper.tripValue(motion, UnitSystem.METRIC))
        } finally {
            harness.stop()
        }
    }

    private fun monitorData(voltage: Int = 72): IntArray = IntArray(48).also { data ->
        data[9] = voltage
        data[10] = 52
        data[11] = 44
        data[16] = 0x40
        data[17] = 0x02
        data[18] = 0x05
        data[19] = 0xDC
        data[20] = 0x01
        data[21] = 0x2C
    }

    private fun monitorPackets(monitor: IntArray): List<ByteArray> = listOf(
        monitorResponse(EtsCommand.USER_MONITOR1, monitor, 0),
        monitorResponse(EtsCommand.USER_MONITOR2, monitor, 16),
        monitorResponse(EtsCommand.USER_MONITOR3, monitor, 32)
    )

    private fun monitorResponse(command: Byte, monitor: IntArray, offset: Int): ByteArray =
        ByteArray(19).also { packet ->
            packet[0] = command
            packet[1] = 16
            repeat(16) { packet[it + 2] = monitor[offset + it].toByte() }
            packet[18] = EtsChecksum.calculate(packet, 0, 18)
        }

    private inner class PollHarness(private val protocol: KellyProtocol, scope: CoroutineScope) {
        private val writes = Channel<ByteArray>(UNLIMITED)
        private val job = scope.launch { protocol.runPollLoop { writes.send(it.copyOf()) } }

        suspend fun expect(command: Byte) {
            val write = withTimeout(1_000L) { writes.receive() }
            assertEquals(command, write[0], "expected ETS command 0x${command.toUByte().toString(16)}")
        }

        suspend fun respond(command: Byte, monitor: IntArray, offset: Int) {
            expect(command)
            // Kept at the protocol boundary: tests feed the same callback
            // ConnectionSession uses after the corresponding write is armed.
            protocol.onNotification(monitorResponse(command, monitor, offset))
        }

        suspend fun completeCycle(monitor: IntArray) {
            respond(EtsCommand.USER_MONITOR1, monitor, 0)
            respond(EtsCommand.USER_MONITOR2, monitor, 16)
            respond(EtsCommand.USER_MONITOR3, monitor, 32)
        }

        fun assertNoNextWrite() {
            assertTrue(writes.tryReceive().isFailure, "a second monitor request was written before the first reply")
        }

        suspend fun stop() {
            job.cancelAndJoin()
            writes.cancel()
        }
    }

}
