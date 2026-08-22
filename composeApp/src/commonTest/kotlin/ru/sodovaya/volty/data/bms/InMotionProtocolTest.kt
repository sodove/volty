package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertFalse(bms.hasPower)
        assertEquals(0f, bms.power)

        assertEquals(0f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(1.95f, motion.dutyPercent, 0.001f)
        assertEquals(77.42f, motion.inputVoltageV, 0.001f)
        assertFalse(motion.hasPower)
        assertEquals(0f, motion.powerW)
        assertEquals(0f, motion.tripKm, 0.001f)
    }

    @Test
    fun parsesV11ShortAndLongLayoutsFromTheModelAwareOffsets() {
        val shortProtocol = InMotionProtocol()
        shortProtocol.onNotification(hex("aaaa110882010206010201009c"))
        val shortData = ByteArray(44).apply {
            putInt16Le(0, 7_363)
            putInt16Le(2, -150)
            putInt16Le(4, 1_234)
            putInt16Le(8, 300)
            putInt16Le(12, 123)
            this[16] = (0x80 or 66).toByte()
            this[17] = celsius(35)
            putInt16Le(22, 150)
            putInt16Le(26, -50)
            putInt16Le(28, 5_000)
            putInt16Le(30, 3_000)
            putInt16Le(36, 4_200)
        }
        shortProtocol.onNotification(buildFrame(0x14, 0x04, shortData))

        val shortBms = assertNotNull(shortProtocol.latestData())
        val shortMotion = assertNotNull(shortProtocol.latestMotion(0))
        assertEquals(73.63f, shortBms.voltage, 0.001f)
        assertEquals(-1.5f, shortBms.current, 0.001f)
        assertEquals(300f, shortBms.power, 0.001f)
        assertEquals(12.34f, shortMotion.speedKmh, 0.001f)
        assertEquals(42f, shortMotion.dutyPercent, 0.001f)
        assertEquals(0f, shortMotion.tripKm, 0.001f)
        shortData.putInt16Le(12, 223)
        shortProtocol.onNotification(buildFrame(0x14, 0x04, shortData))
        assertEquals(1f, assertNotNull(shortProtocol.latestMotion(0)).tripKm, 0.001f)

        val longProtocol = InMotionProtocol()
        longProtocol.onNotification(hex("aaaa110882010206010201009c"))
        val longData = ByteArray(62).apply {
            putInt16Le(0, 7_363)
            putInt16Le(2, -150)
            putInt16Le(4, 1_234)
            putInt16Le(6, 250)
            putInt16Le(8, 4_200)
            putInt16Le(10, 300)
            putInt16Le(12, 280)
            putInt16Le(16, 150)
            putInt16Le(18, 100)
            putInt16Le(20, -50)
            putInt16Le(26, 123)
            putInt16Le(28, 6_550)
            putInt16Le(30, 2_500)
            putInt16Le(34, 5_000)
            putInt16Le(36, 3_000)
            this[42] = celsius(35)
            this[43] = celsius(40)
            this[44] = celsius(25)
            this[45] = celsius(30)
            this[46] = celsius(45)
            this[47] = celsius(33)
        }
        longProtocol.onNotification(buildFrame(0x14, 0x04, longData))

        val longBms = assertNotNull(longProtocol.latestData())
        val longMotion = assertNotNull(longProtocol.latestMotion(0))
        assertEquals(73.63f, longBms.voltage, 0.001f)
        assertEquals(-1.5f, longBms.current, 0.001f)
        assertEquals(300f, longBms.power, 0.001f)
        assertEquals(12.34f, longMotion.speedKmh, 0.001f)
        assertEquals(42f, longMotion.dutyPercent, 0.001f)
        assertEquals(0f, longMotion.tripKm, 0.001f)
        longData.putInt16Le(26, 223)
        longProtocol.onNotification(buildFrame(0x14, 0x04, longData))
        assertEquals(1f, assertNotNull(longProtocol.latestMotion(0)).tripKm, 0.001f)
        assertEquals(35f, longMotion.escTempC, 0.001f)
        assertTrue(longMotion.hasMotorTemp)
        assertEquals(listOf(25f), longBms.temperatures)
    }

    @Test
    fun routesAllV12ModelIdsToTheV12Layout() {
        val modelIds = listOf(71, 72, 73, 111)
        modelIds.forEach { modelId ->
            val protocol = InMotionProtocol()
            protocol.onNotification(modelFrame(modelId))
            val data = ByteArray(47).apply {
                putInt16Le(0, 7_542)
                putInt16Le(2, -150)
                putInt16Le(4, -1_234)
                putInt16Le(8, 4_200)
                putInt16Le(10, 321)
                putInt16Le(22, 123)
                putInt16Le(24, 7_650)
                this[40] = celsius(30)
            }
            protocol.onNotification(buildFrame(0x14, 0x04, data))

            val bms = assertNotNull(protocol.latestData(), "model $modelId")
            val motion = assertNotNull(protocol.latestMotion(0), "model $modelId")
            assertEquals(75.42f, bms.voltage, 0.001f, "model $modelId")
            assertEquals(-1.5f, bms.current, 0.001f, "model $modelId")
            assertEquals(321f, bms.power, 0.001f, "model $modelId")
            assertEquals(12.34f, motion.speedKmh, 0.001f, "model $modelId")
            assertEquals(42f, motion.dutyPercent, 0.001f, "model $modelId")
            assertEquals(0f, motion.tripKm, 0.001f, "model $modelId")
            assertEquals(30f, motion.escTempC, 0.001f, "model $modelId")
            data.putInt16Le(22, 223)
            protocol.onNotification(buildFrame(0x14, 0x04, data))
            assertEquals(1f, assertNotNull(protocol.latestMotion(0)).tripKm, 0.001f, "model $modelId")
        }
    }

    @Test
    fun routesV13AndV14ModelIdsToTheSharedLongLayout() {
        val modelIds = listOf(81, 82, 91, 92)
        modelIds.forEach { modelId ->
            val protocol = InMotionProtocol()
            protocol.onNotification(modelFrame(modelId))
            val data = ByteArray(76).apply {
                putInt16Le(0, 12_600)
                putInt16Le(2, 450)
                putInt16Le(8, 4_200)
                putInt16Le(14, 3_500)
                putInt16Le(16, 567)
                putInt16Le(34, 8_000)
                putInt16Le(28, 123)
                this[60] = celsius(35)
                this[65] = celsius(36)
                this[66] = celsius(37)
            }
            protocol.onNotification(buildFrame(0x14, 0x04, data))

            val bms = assertNotNull(protocol.latestData(), "model $modelId")
            val motion = assertNotNull(protocol.latestMotion(0), "model $modelId")
            assertEquals(126f, bms.voltage, 0.001f, "model $modelId")
            assertEquals(4.5f, bms.current, 0.001f, "model $modelId")
            assertEquals(567f, bms.power, 0.001f, "model $modelId")
            assertEquals(42f, motion.speedKmh, 0.001f, "model $modelId")
            assertEquals(35f, motion.dutyPercent, 0.001f, "model $modelId")
            assertEquals(0f, motion.tripKm, 0.001f, "model $modelId")
            assertEquals(listOf(35f), bms.temperatures, "model $modelId")
            data.putInt16Le(28, 223)
            protocol.onNotification(buildFrame(0x14, 0x04, data))
            assertEquals(1f, assertNotNull(protocol.latestMotion(0)).tripKm, 0.001f, "model $modelId")
        }
    }

    @Test
    fun rejectsPayloadsThatFallBetweenModelSpecificLayoutGuards() {
        val v11 = InMotionProtocol()
        v11.onNotification(modelFrame(61))
        v11.onNotification(buildFrame(0x14, 0x04, ByteArray(51)))
        assertNull(v11.latestData())
        assertNull(v11.latestMotion(0))

        listOf(71 to 46, 81 to 54, 91 to 54, 131 to 53).forEach { (modelId, length) ->
            val protocol = InMotionProtocol()
            protocol.onNotification(modelFrame(modelId))
            protocol.onNotification(buildFrame(0x14, 0x04, ByteArray(length)))
            assertNull(protocol.latestData(), "model $modelId")
            assertNull(protocol.latestMotion(0), "model $modelId")
        }
    }

    @Test
    fun reassemblesTheExactEscapedP6WireFixtureWithNoiseAndSplitEscape() {
        val protocol = InMotionProtocol()
        protocol.onNotification(hex("aaaa11088201020d0101010094"))
        val wire = hex(
            "AAAA1457847759ECFF00000000000000006001B401000000006700" +
                "B3FF640000000000D5002D01B824A524983A983A983A401F401F" +
                "E02EE02E50C300000000CDCE00CFB0CCCDD0B052000400000000490000" +
                "000000000000000000B6",
        )
        val escape = wire.indexOfFirst { (it.toInt() and 0xFF) == 0xA5 }
        protocol.onNotification(byteArrayOf(0x01, 0x02, 0xAA.toByte()) + wire.copyOfRange(0, escape + 1))
        assertNull(protocol.latestData())

        protocol.onNotification(wire.copyOfRange(escape + 1, wire.size) + byteArrayOf(0x7F, 0x00))
        val bms = assertNotNull(protocol.latestData())
        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(229.03f, bms.voltage, 0.001f)
        assertEquals(94f, bms.soc * 100f, 0.001f)
        assertEquals(0f, motion.speedKmh, 0.001f)
        assertEquals(4.36f, motion.dutyPercent, 0.001f)
    }

    @Test
    fun noiseA5AndDoubleAaImmediatelyBeforeAValidHeaderDoesNotLoseTheFrame() {
        val streams = listOf(
            byteArrayOf(0xA5.toByte()) + V9_REALTIME,
            byteArrayOf(0xA5.toByte(), 0xAA.toByte(), 0xAA.toByte()) + V9_REALTIME,
        )

        streams.forEach { stream ->
            for (split in 0..stream.size) {
                val protocol = InMotionProtocol()
                protocol.onNotification(stream.copyOfRange(0, split))
                protocol.onNotification(stream.copyOfRange(split, stream.size))

                assertNotNull(protocol.latestData(), "noise prefix split at $split")
                assertNotNull(protocol.latestMotion(0), "noise prefix split at $split")
            }
        }
    }

    @Test
    fun escapedA5AndAaRemainDecodableAcrossPayloadPositions() {
        val stored = hex(
            "AAAA1457847759ECFF00000000000000006001B401000000006700" +
                "B3FF640000000000D5002D01B824A524983A983A983A401F401F" +
                "E02EE02E50C300000000CDCE00CFB0CCCDD0B052000400000000490000" +
                "000000000000000000B6",
        )
        var checked = 0

        for (offset in 5 until stored.lastIndex) {
            for (marker in listOf(0xA5, 0xAA)) {
                val mutated = stored.copyOf().also {
                    it[offset] = marker.toByte()
                    it[it.lastIndex] = v2Checksum(it)
                }
                val protocol = InMotionProtocol()
                protocol.onNotification(hex("aaaa11088201020d0101010094"))
                protocol.onNotification(escapedWire(mutated))

                assertNotNull(protocol.latestData(), "escaped $marker at offset $offset")
                assertNotNull(protocol.latestMotion(0), "escaped $marker at offset $offset")
                checked += 1
            }
        }

        assertTrue(checked > 100)
    }

    @Test
    fun zeroSensorBytesStayUnknownAndDoNotBecomeMinus176C() {
        val protocol = InMotionProtocol()
        protocol.onNotification(modelFrame(81))
        val data = ByteArray(76).apply {
            putInt16Le(0, 12_600)
            putInt16Le(2, 450)
            putInt16Le(8, 4_200)
            putInt16Le(14, 3_500)
            putInt16Le(16, 567)
            putInt16Le(34, 8_000)
            // All model-specific sensor bytes remain zero: the source uses zero for absent.
        }
        protocol.onNotification(buildFrame(0x14, 0x04, data))

        val bms = assertNotNull(protocol.latestData())
        val motion = assertNotNull(protocol.latestMotion(0))
        assertTrue(motion.escTempC < -50f)
        assertTrue(motion.escTempC != -176f)
        assertFalse(motion.hasMotorTemp)
        assertTrue(bms.temperatures.isEmpty())
    }

    @Test
    fun outOfRangeDutyStaysUnknownInsteadOfBecomingZeroPercent() {
        val protocol = InMotionProtocol()
        protocol.onNotification(modelFrame(81))
        val data = ByteArray(76).apply {
            putInt16Le(0, 12_600)
            putInt16Le(2, 450)
            putInt16Le(8, 4_200)
            putInt16Le(14, 30_000)
            putInt16Le(16, 567)
            putInt16Le(34, 8_000)
        }
        protocol.onNotification(buildFrame(0x14, 0x04, data))

        val motion = assertNotNull(protocol.latestMotion(0))
        assertFalse(motion.hasDuty)
        assertEquals(0f, motion.dutyPercent)
    }

    @Test
    fun directP6BmsResponseRemainsDeferredBecauseVoltyHasOneGenericPack() {
        val protocol = InMotionProtocol()
        protocol.onNotification(hex("AAAA11088201020D0101010094"))
        protocol.onNotification(hex("AAAA141185CE580000000001001B020000000000000E"))

        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
    }

    @Test
    fun allInMotionCommandListsRemainEmpty() {
        val protocol = InMotionProtocol()

        assertTrue(protocol.handshakeCommands().isEmpty())
        assertTrue(protocol.pollCommands().isEmpty())
        assertEquals(0L, protocol.pollIntervalMs)
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

        fun modelFrame(modelId: Int): ByteArray = buildFrame(
            0x11,
            0x02,
            byteArrayOf(1, 2, (modelId / 10).toByte(), (modelId % 10).toByte()),
        )

        fun buildFrame(flag: Int, command: Int, data: ByteArray): ByteArray {
            val commandByte = (command or 0x80).toByte()
            val body = byteArrayOf(commandByte) + data
            val payload = byteArrayOf(flag.toByte(), body.size.toByte()) + body
            val checksum = payload.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }.toByte()
            return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + payload + byteArrayOf(checksum)
        }

        fun celsius(value: Int): Byte = (value - 80 + 256).toByte()

        fun escapedWire(frame: ByteArray): ByteArray {
            val result = ArrayList<Byte>(frame.size)
            result += 0xAA.toByte()
            result += 0xAA.toByte()
            for (index in 2 until frame.size) {
                val value = frame[index]
                if (value == 0xA5.toByte() || value == 0xAA.toByte()) result += 0xA5.toByte()
                result += value
            }
            return result.toByteArray()
        }

        fun v2Checksum(frame: ByteArray): Byte =
            (2 until frame.lastIndex).fold(0) { value, index ->
                value xor (frame[index].toInt() and 0xFF)
            }.toByte()

        fun ByteArray.putInt16Le(offset: Int, value: Int) {
            this[offset] = (value and 0xFF).toByte()
            this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }
}
