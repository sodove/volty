package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.SpeedSource

class VeteranProtocolTest {

    @Test
    fun `Veteran stream is read only and uses the Nordic UART UUIDs`() {
        val protocol = VeteranProtocol()

        assertEquals(emptyList(), protocol.handshakeCommands())
        assertEquals(emptyList(), protocol.pollCommands())
        assertEquals(0L, protocol.pollIntervalMs)
        assertEquals(2, protocol.packCount)
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", protocol.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.notifyCharUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.writeCharUuid)
    }

    @Test
    fun `valid controller frame is reassembled across noise and split notifications`() {
        val protocol = VeteranProtocol()
        val frame = controllerFrame(
            voltageRaw = 12_525,
            speedRaw = -321,
            distanceRaw = 54_321,
            totalDistanceRaw = 65_432,
            phaseCurrentRaw = -250,
            temperatureRaw = 3_500,
            versionRaw = 5_001,
            pwmRaw = 7_850
        )

        protocol.onNotification(byteArrayOf(0x13, 0x37, 0xDC.toByte()) + frame.copyOfRange(0, 11))
        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
        protocol.onNotification(frame.copyOfRange(11, frame.size))

        val battery = assertNotNull(protocol.latestData())
        assertEquals(125.25f, battery.voltage, 0.001f)
        assertFalse(battery.hasCurrent, "bytes 16..17 are phase current, not BMS current")
        assertFalse(battery.hasPower, "Veteran's controller frame has no battery-current field")
        assertTrue(battery.socKnown, "the WheelLog model voltage curve is proven for Lynx")
        assertEquals("Lynx", protocol.model)
        assertEquals("005.0.01", protocol.firmwareVersion)

        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(32.1f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(78.5f, motion.dutyPercent, 0.001f)
        assertTrue(motion.hasDuty)
        assertEquals(-25f, motion.motorCurrentA, 0.001f)
        assertFalse(motion.hasBatteryCurrent)
        assertEquals(125.25f, motion.inputVoltageV, 0.001f)
        assertTrue(motion.hasInputVoltage)
        assertFalse(motion.hasPower)
        assertEquals(65.432f, motion.odometerKm, 0.001f)
        assertEquals(0f, motion.tripKm, 0.001f)
        assertTrue(motion.hasDistance)
        assertEquals(35f, motion.escTempC, 0.001f)
        assertFalse(motion.hasMotorTemp)
    }

    @Test
    fun `bad legacy shape is skipped and following valid frame is recovered`() {
        val protocol = VeteranProtocol()
        val invalid = controllerFrame(versionRaw = 5_001).also { it[22] = 1 }
        val valid = controllerFrame(voltageRaw = 12_400, versionRaw = 5_001)

        protocol.onNotification(invalid + valid)

        assertEquals(124f, assertNotNull(protocol.latestData()).voltage, 0.001f)
    }

    @Test
    fun `header-like bytes inside payload do not trigger a false resynchronisation`() {
        val protocol = VeteranProtocol()
        val frame = controllerFrame(len = 71, voltageRaw = 12_350, versionRaw = 4_001).also {
            it[40] = 0xDC.toByte(); it[41] = 0x5A; it[42] = 0x5C
            appendCrc(it, 71)
        }

        protocol.onNotification(frame)

        assertEquals(123.5f, assertNotNull(protocol.latestData()).voltage, 0.001f)
    }

    @Test
    fun `long frame requires CRC32 and a corrupted frame does not replace old sample`() {
        val protocol = VeteranProtocol()
        val valid = controllerFrame(len = 71, voltageRaw = 12_350, versionRaw = 4_001)
        protocol.onNotification(valid)
        assertEquals(123.5f, assertNotNull(protocol.latestData()).voltage, 0.001f)

        val corrupted = valid.copyOf().also { it[12] = (it[12].toInt() xor 0x01).toByte() }
        protocol.onNotification(corrupted)

        assertEquals(123.5f, assertNotNull(protocol.latestData()).voltage, 0.001f)
    }

    @Test
    fun `smart BMS pages expose two complete packs with cells temperatures and current`() {
        val protocol = VeteranProtocol()
        protocol.onNotification(controllerFrame(versionRaw = 5_001))

        protocol.onNotification(bmsFrame(packet = 0, current1Raw = 123, current2Raw = -234))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4_100 + it }))
        protocol.onNotification(bmsFrame(packet = 2, cells = IntArray(15) { 4_120 + it }))
        protocol.onNotification(
            bmsFrame(
                packet = 3,
                cells = IntArray(6) { 4_140 + it },
                temperatures = IntArray(6) { 2_500 + it * 10 }
            )
        )
        protocol.onNotification(bmsFrame(packet = 4, current1Raw = 123, current2Raw = -234))
        protocol.onNotification(bmsFrame(packet = 5, cells = IntArray(15) { 4_100 + it }))
        protocol.onNotification(bmsFrame(packet = 6, cells = IntArray(15) { 4_120 + it }))
        protocol.onNotification(
            bmsFrame(
                packet = 7,
                cells = IntArray(6) { 4_140 + it },
                temperatures = IntArray(6) { 2_500 + it * 10 }
            )
        )

        val first = assertNotNull(protocol.latestData(0))
        val second = assertNotNull(protocol.latestData(1))
        assertEquals(36, first.cellVoltages.size)
        assertEquals(36, second.cellVoltages.size)
        assertEquals(148.365f, first.voltage, 0.001f)
        assertEquals(1.23f, first.current, 0.001f)
        assertTrue(first.hasCurrent)
        assertFalse(first.hasPower)
        assertEquals(listOf(25f, 25.1f, 25.2f, 25.3f, 25.4f, 25.5f), first.temperatures)
        assertEquals(-2.34f, second.current, 0.001f)
    }

    @Test
    fun `partial smart BMS pages never publish a fabricated pack voltage`() {
        val protocol = VeteranProtocol()
        protocol.onNotification(controllerFrame(versionRaw = 5_001))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4_100 }))
        protocol.onNotification(bmsFrame(packet = 2, cells = IntArray(15) { 4_100 }))

        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun `main page does not make an incomplete smart pack replace main telemetry`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(LYNX_S_PAGE_0.hexBytes())

        assertNotNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun `short Patton page does not read CRC trailer as reported values`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(PATTON_PAGE_2.hexBytes())

        val battery = assertNotNull(protocol.latestData(0))
        assertEquals(123.72f, battery.voltage, 0.001f)
        assertFalse(battery.hasCurrent)
        assertFalse(battery.hasPower)
        assertEquals(0.94f, battery.soc, 0.01f)
    }

    @Test
    fun `24 bit version bytes identify the Nosfet 5010 profile`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(controllerFrame(versionRaw = 501_008))

        assertEquals("Nosfet Apex", protocol.model)
        assertEquals("501.0.08", protocol.firmwareVersion)
    }

    @Test
    fun `reported page 2 SoC remains known after complete cell pages`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(LYNX_S_PAGE_2.hexBytes())
        protocol.onNotification(
            bmsFrame(
                packet = 1,
                versionRaw = 9_004,
                cells = IntArray(15) { 4_100 + it }
            )
        )
        protocol.onNotification(
            bmsFrame(
                packet = 3,
                versionRaw = 9_004,
                cells = IntArray(6) { 4_140 + it },
                temperatures = IntArray(6) { 2_500 + it * 10 }
            )
        )

        val battery = assertNotNull(protocol.latestData(0))
        assertEquals(0.78f, battery.soc, 0.001f)
        assertTrue(battery.socKnown)
    }

    @Test
    fun `reported cell count is read from payload rather than CRC`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(
            bmsFrame(
                packet = 1,
                reportedCellCount = 30,
                cells = IntArray(15) { 4_100 }
            )
        )
        protocol.onNotification(
            bmsFrame(
                packet = 2,
                cells = IntArray(15) { 4_100 }
            )
        )

        val battery = assertNotNull(protocol.latestData(0))
        assertEquals(30, battery.cellVoltages.size)
    }

    @Test
    fun `reset clears the frame accumulator metadata packs and motion`() {
        val protocol = VeteranProtocol()
        val frame = controllerFrame(versionRaw = 5_001)
        protocol.onNotification(frame.copyOfRange(0, 10))
        protocol.reset()
        protocol.onNotification(frame.copyOfRange(10, frame.size))

        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
        assertEquals("Leaperkim", protocol.model)
        assertNull(protocol.firmwareVersion)
    }

    private companion object {
        fun controllerFrame(
            len: Int = 32,
            voltageRaw: Int = 12_000,
            speedRaw: Int = 0,
            distanceRaw: Int = 0,
            totalDistanceRaw: Int = 0,
            phaseCurrentRaw: Int = 0,
            temperatureRaw: Int = 2_500,
            versionRaw: Int = 5_001,
            pwmRaw: Int = 0
        ): ByteArray = ByteArray(len + 4).apply {
            this[0] = 0xDC.toByte(); this[1] = 0x5A; this[2] = 0x5C; this[3] = len.toByte()
            putBe(4, voltageRaw); putBe(6, speedRaw); putReverseBe(8, distanceRaw); putReverseBe(12, totalDistanceRaw)
            putBe(16, phaseCurrentRaw); putBe(18, temperatureRaw)
            putBe(20, 0); putBe(22, 0); putBe(24, 0); putBe(26, 0)
            this[28] = (versionRaw ushr 8).toByte(); this[29] = versionRaw.toByte(); this[30] = (versionRaw ushr 16).toByte()
            putBe(32, 0)
            if (size >= 36) putBe(34, pwmRaw)
            if (len > 38) appendCrc(this, len)
        }

        fun bmsFrame(
            packet: Int,
            cells: IntArray = intArrayOf(),
            temperatures: IntArray = intArrayOf(),
            current1Raw: Int = 0,
            current2Raw: Int = 0,
            versionRaw: Int = 5_001,
            reportedCellCount: Int? = null
        ): ByteArray = controllerFrame(len = 83, versionRaw = versionRaw).apply {
            this[46] = packet.toByte()
            if (packet == 0 || packet == 4) {
                putBe(69, current1Raw); putBe(71, current2Raw)
            } else if (packet == 1 || packet == 5) {
                reportedCellCount?.let { this[52] = it.toByte() }
                cells.forEachIndexed { i, value -> putBe(53 + i * 2, value) }
            } else if (packet == 2 || packet == 6) {
                cells.forEachIndexed { i, value -> putBe(53 + i * 2, value) }
            } else if (packet == 3 || packet == 7) {
                cells.forEachIndexed { i, value -> putBe(59 + i * 2, value) }
                temperatures.forEachIndexed { i, value -> putBe(47 + i * 2, value) }
            }
            appendCrc(this, 83)
        }

        fun ByteArray.putBe(offset: Int, value: Int) {
            this[offset] = (value shr 8).toByte(); this[offset + 1] = value.toByte()
        }

        fun ByteArray.putReverseBe(offset: Int, value: Int) {
            this[offset] = (value shr 8).toByte(); this[offset + 1] = value.toByte()
            this[offset + 2] = (value shr 24).toByte(); this[offset + 3] = (value shr 16).toByte()
        }

        fun appendCrc(frame: ByteArray, len: Int) {
            var crc = 0xFFFF_FFFFL
            for (i in 0 until len) {
                crc = crc xor (frame[i].toInt() and 0xFF).toLong()
                repeat(8) { crc = if ((crc and 1L) != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
            }
            crc = crc.inv() and 0xFFFF_FFFFL
            frame[len] = (crc ushr 24).toByte(); frame[len + 1] = (crc ushr 16).toByte()
            frame[len + 2] = (crc ushr 8).toByte(); frame[len + 3] = crc.toByte()
        }
    }
}

private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private const val PATTON_PAGE_2 =
    "DC5A5C3230540000EE48001BEE48001B00000DDC0D4D000007D003200FB1000219D00000006F0000808080808080022D010033AA33DD"

private const val LYNX_S_PAGE_0 =
    "DC5A5C4937190000DF6C00007042000300070C8E0379000003C002EE232C00780006007E80C800008080808080800000000BFFFFFFFFFF3211FF430AEF0CDE022A003300000002000501A0CA44"

private const val LYNX_S_PAGE_2 =
    "DC5A5C53371F0000DF6C00007042000300060C8E0378000003C002EE232C00780006007780C80000808080808080022801004E80800F520F530F4B0F550F550F550F550F540F4F0F550F560F560F550F560F52FFCECC8B"
