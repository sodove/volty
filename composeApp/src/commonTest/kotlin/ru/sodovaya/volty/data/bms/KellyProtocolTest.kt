package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.controller.kelly.EtsChecksum
import ru.sodovaya.volty.data.controller.kelly.EtsCommand
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.SpeedUnknownReason
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KellyProtocolTest {

    @Test
    fun `handshake is a one-shot CODE_VERSION and monitor poll is framed in ETS order`() {
        val protocol = KellyProtocol()

        assertContentEquals(byteArrayOf(0x11, 0x00, 0x11), protocol.handshakeCommands().single())
        assertTrue(protocol.handshakeCommands().isEmpty())
        assertEquals(
            listOf(0x3A, 0x3B, 0x3C),
            protocol.pollCommands().map { it[0].toInt() and 0xFF }
        )
        assertTrue(protocol.pollCommands().all { it[1] == 0.toByte() && it[2] == it[0] })
    }

    @Test
    fun `three matching monitor packets publish all supported controller data honestly`() {
        val protocol = KellyProtocol(
            motor = MotorConfig(wheelDiameterMm = 500, gearRatio = 2f)
        )
        val monitor = monitorData()

        assertEquals(0, protocol.packCount)
        assertNull(protocol.latestData(0))

        monitorPackets(monitor).forEach(protocol::onNotification)

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
    }

    @Test
    fun `monitor response is not published until all three command-matched packets arrive`() {
        val protocol = KellyProtocol()
        val packets = monitorPackets(monitorData())

        protocol.onNotification(packets[0])
        protocol.onNotification(packets[1])
        assertNull(protocol.latestMotion(0))

        protocol.onNotification(packets[2])

        assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
    }

    @Test
    fun `a monitor response split across BLE notifications is reassembled before it is matched`() {
        val protocol = KellyProtocol()
        val packets = monitorPackets(monitorData())

        protocol.onNotification(packets[0].copyOfRange(0, 7))
        protocol.onNotification(packets[0].copyOfRange(7, packets[0].size))
        protocol.onNotification(packets[1])
        protocol.onNotification(packets[2])

        assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
    }

    @Test
    fun `serial poll waits for the first monitor response before writing the second`() = runTest {
        val protocol = KellyProtocol()
        val monitor = monitorData()
        val firstWrite = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val thirdWrite = CompletableDeferred<Unit>()
        val sent = mutableListOf<ByteArray>()
        val job = backgroundScope.launch {
            protocol.runPollLoop { request ->
                sent += request
                when (sent.size) {
                    1 -> {
                        firstWrite.complete(Unit)
                        releaseFirstWrite.await()
                    }
                    2 -> protocol.onNotification(monitorResponse(EtsCommand.USER_MONITOR2, monitor, 16))
                    3 -> {
                        protocol.onNotification(monitorResponse(EtsCommand.USER_MONITOR3, monitor, 32))
                        thirdWrite.complete(Unit)
                    }
                }
            }
        }

        firstWrite.await()
        assertEquals(1, sent.size)
        protocol.onNotification(monitorResponse(EtsCommand.USER_MONITOR1, monitor, 0))
        releaseFirstWrite.complete(Unit)
        thirdWrite.await()
        job.cancelAndJoin()

        assertEquals(listOf(0x3A, 0x3B, 0x3C), sent.take(3).map { it[0].toInt() and 0xFF })
        assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
    }

    @Test
    fun `wrong command rejects the partial monitor batch instead of mixing it with later packets`() {
        val protocol = KellyProtocol()
        val packets = monitorPackets(monitorData())

        protocol.onNotification(packets[0])
        protocol.onNotification(packets[2]) // expected USER_MONITOR2, received USER_MONITOR3
        protocol.onNotification(packets[1])
        protocol.onNotification(packets[2])

        assertNull(protocol.latestMotion(0))

        monitorPackets(monitorData()).forEach(protocol::onNotification)
        assertEquals(72f, protocol.latestMotion(0)?.inputVoltageV)
    }

    @Test
    fun `bad checksum rejects the partial monitor batch`() {
        val protocol = KellyProtocol()
        val packets = monitorPackets(monitorData()).toMutableList()
        packets[1] = packets[1].copyOf().also { it[it.lastIndex] = 0 }

        packets.forEach(protocol::onNotification)

        assertNull(protocol.latestMotion(0))
    }

    @Test
    fun `unknown wheel geometry keeps speed unavailable but retains mechanical RPM`() {
        val protocol = KellyProtocol(motor = MotorConfig(wheelDiameterMm = 0, gearRatio = 2f))

        monitorPackets(monitorData()).forEach(protocol::onNotification)

        val motion = requireNotNull(protocol.latestMotion(0))
        assertEquals(1_500f, motion.eRpm)
        assertEquals(0f, motion.speedKmh)
        assertEquals(SpeedSource.NONE, motion.speedSource)
        assertEquals(SpeedUnknownReason.NO_WHEEL_GEOMETRY, motion.speedUnknownReason)
    }

    @Test
    fun `out of range temperature bytes do not claim either temperature sensor exists`() {
        val protocol = KellyProtocol()
        val monitor = monitorData().also {
            it[10] = 0xFF
            it[11] = 0xFF
        }

        monitorPackets(monitor).forEach(protocol::onNotification)

        val motion = requireNotNull(protocol.latestMotion(0))
        assertFalse(motion.hasMotorTemp)
        assertTrue(motion.escTempC < -50f)
        assertFalse(motion.hasEscTemp)
    }

    @Test
    fun `derived pack is opt-in and is cleared by reset with controller state`() {
        val protocol = KellyProtocol(deriveBattery = true)
        assertEquals(1, protocol.packCount)
        monitorPackets(monitorData()).forEach(protocol::onNotification)
        assertEquals(72f, protocol.latestData(0)?.voltage)
        assertFalse(requireNotNull(protocol.latestData(0)).socKnown)

        protocol.reset()

        assertNull(protocol.latestMotion(0))
        assertNull(protocol.latestData(0))
        assertContentEquals(byteArrayOf(0x11, 0x00, 0x11), protocol.handshakeCommands().single())
    }

    private fun monitorData(): IntArray = IntArray(48).also { data ->
        data[9] = 72
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
}
