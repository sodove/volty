package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.SpeedSource

/** Fixtures follow WheelLog's read-only NinebotAdapter (55 AA / B0 live page). */
class NinebotLegacyProtocolTest {

    @Test
    fun `legacy link is passive and uses the FFE0 FFE1 UART`() {
        val protocol = NinebotLegacyProtocol()

        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", protocol.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.notifyCharUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.writeCharUuid)
        assertTrue(protocol.handshakeCommands().isEmpty())
        assertTrue(protocol.pollCommands().isEmpty())
        assertEquals(0L, protocol.pollIntervalMs)
        assertEquals(1, protocol.packCount)
        assertNull(protocol.latestData())
    }

    @Test
    fun `B0 live page is decoded only after a complete split frame`() {
        val protocol = NinebotLegacyProtocol()
        val frame = liveFrame(
            batteryPercent = 78,
            distanceMeters = 2_660_251,
            temperatureCentiC = 3_700,
            voltageCentiV = 6_150,
            currentCentiA = 1_234,
            speedCentiKmh = 2_716
        )

        protocol.onNotification(byteArrayOf(0x12, 0x34, 0x55) + frame.copyOfRange(0, 8))
        assertNull(protocol.latestData(), "a partial frame must not publish telemetry")

        protocol.onNotification(frame.copyOfRange(8, frame.size))
        val data = requireNotNull(protocol.latestData())
        assertEquals(61.5f, data.voltage, 0.001f)
        assertEquals(12.34f, data.current, 0.001f)
        assertEquals(78f, data.soc, 0.001f)
        assertTrue(data.socKnown)
        assertEquals(61.5f * 12.34f, data.power, 0.01f)
        assertEquals(listOf(37f), data.temperatures)
        assertTrue(data.isConnected)

        val motion = requireNotNull(protocol.latestMotion(0))
        assertEquals(27.16f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(2_660.251f, motion.odometerKm, 0.001f)
        assertEquals(0f, motion.tripKm, 0.001f)
        assertTrue(motion.hasDistance)
    }

    @Test
    fun `noise concatenated frames and bad checksum resynchronise`() {
        val protocol = NinebotLegacyProtocol()
        val invalid = liveFrame(voltageCentiV = 6_100).also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte()
        }
        val valid = liveFrame(voltageCentiV = 6_200)

        protocol.onNotification(byteArrayOf(0x01, 0x02, 0x55) + invalid + valid)

        val data = requireNotNull(protocol.latestData())
        assertEquals(62f, data.voltage, 0.001f)
    }

    @Test
    fun `unsupported command and malformed live payload are ignored`() {
        val protocol = NinebotLegacyProtocol()

        protocol.onNotification(frame(parameter = 0x10, payload = ByteArray(14) { 0x31 }))
        protocol.onNotification(frame(parameter = 0xB0, payload = ByteArray(31)))
        assertNull(protocol.latestData())
    }

    @Test
    fun `reset clears buffered and published state`() {
        val protocol = NinebotLegacyProtocol()
        val frame = liveFrame()

        protocol.onNotification(frame.copyOfRange(0, 5))
        protocol.reset()
        protocol.onNotification(frame.copyOfRange(5, frame.size))
        assertNull(protocol.latestData())

        protocol.onNotification(frame)
        assertTrue(protocol.latestData() != null)
        protocol.reset()
        assertNull(protocol.latestData())
    }

    private fun liveFrame(
        batteryPercent: Int = 0,
        distanceMeters: Long = 0,
        temperatureCentiC: Int = 0,
        voltageCentiV: Int = 6_000,
        currentCentiA: Int = 0,
        speedCentiKmh: Int = 0
    ): ByteArray = ByteArray(LIVE_PAYLOAD_SIZE).apply {
        putU16(8, batteryPercent)
        putU32(14, distanceMeters)
        putU16(22, temperatureCentiC)
        putU16(24, voltageCentiV)
        putI16(26, currentCentiA)
        putU16(28, speedCentiKmh)
    }.let { frame(parameter = 0xB0, payload = it) }

    /** WheelLog's legacy frame: 55 AA, len=(parameter+payload), fields, CRC-LE. */
    private fun frame(
        source: Int = 0x01,
        destination: Int = 0x09,
        parameter: Int,
        payload: ByteArray
    ): ByteArray {
        val len = payload.size + 2
        val body = ByteArray(len + 2).apply {
            this[0] = len.toByte()
            this[1] = source.toByte()
            this[2] = destination.toByte()
            this[3] = parameter.toByte()
            payload.copyInto(this, destinationOffset = 4)
        }
        var sum = 0
        for (index in 0 until body.size) sum = (sum + body[index].u8()) and 0xffff
        val checksum = sum.inv() and 0xffff
        return byteArrayOf(
            0x55,
            0xAA.toByte(),
            *body,
            checksum.toByte(),
            (checksum ushr 8).toByte()
        )
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putI16(offset: Int, value: Int) = putU16(offset, value and 0xffff)

    private fun ByteArray.putU32(offset: Int, value: Long) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private companion object {
        const val LIVE_PAYLOAD_SIZE = 32
    }
}
