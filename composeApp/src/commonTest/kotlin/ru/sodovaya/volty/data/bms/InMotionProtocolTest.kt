package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.SpeedSource

class InMotionProtocolTest {

    @Test
    fun splitV9RealtimeFramePublishesOnlyAfterCompleteValidatedFrame() {
        val protocol = InMotionProtocol()
        val frame = V9_REALTIME

        protocol.onNotification(frame.copyOfRange(0, 17))
        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))

        protocol.onNotification(frame.copyOfRange(17, 61))
        assertNull(protocol.latestData())

        protocol.onNotification(frame.copyOfRange(61, frame.size))
        val bms = assertNotNull(protocol.latestData())
        val motion = assertNotNull(protocol.latestMotion(0))

        assertEquals(77.42f, bms.voltage, 0.001f)
        assertEquals(0.12f, bms.current, 0.001f)
        assertEquals(0.58495f, bms.soc, 0.0001f)
        assertEquals(29f, bms.temperatures[0])
        assertEquals(25f, bms.temperatures[1])
        assertEquals(77.42f * 0.12f, bms.power, 0.001f)

        assertEquals(0f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(1.95f, motion.dutyPercent, 0.001f)
        assertEquals(77.42f, motion.inputVoltageV, 0.001f)
        assertTrue(motion.hasPower)
        assertEquals(0f, motion.tripKm, 0.001f)
    }

    @Test
    fun noiseAndConcatenatedFramesResynchroniseWithoutPublishingNoise() {
        val protocol = InMotionProtocol()
        val noise = byteArrayOf(0x01, 0x02, 0xAA.toByte())
        protocol.onNotification(noise + V9_REALTIME.copyOfRange(0, 12))
        assertNull(protocol.latestData())

        protocol.onNotification(V9_REALTIME.copyOfRange(12, V9_REALTIME.size) + byteArrayOf(0x7F, 0x00))
        assertNotNull(protocol.latestData())
    }

    @Test
    fun badChecksumIsIgnoredAndNextValidFrameIsAccepted() {
        val protocol = InMotionProtocol()
        val invalid = V9_REALTIME.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }

        protocol.onNotification(invalid)
        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))

        protocol.onNotification(V9_REALTIME)
        assertNotNull(protocol.latestData())
    }

    @Test
    fun truncatedRealtimePayloadDoesNotPublishPartialValues() {
        val protocol = InMotionProtocol()
        val truncated = V9_REALTIME.copyOfRange(0, V9_REALTIME.size - 8)

        protocol.onNotification(truncated)

        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
    }

    private companion object {
        /** Authentic V9 V2 realtime frame from Tritbool/euc_ble_library's WheelLog fixture. */
        val V9_REALTIME = hex(
            "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f"
        )

        fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
