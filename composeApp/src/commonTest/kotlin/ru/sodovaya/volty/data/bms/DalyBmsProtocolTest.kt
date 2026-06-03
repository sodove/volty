package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DalyBmsProtocolTest {

    /**
     * Build a Daly response frame matching Kelly's parser:
     *   A5 01 [cmd] 08 [8 data bytes] [crc]
     * where crc = sum(first 12 bytes) & 0xFF.
     *
     * `data` is exactly 8 bytes (parser reads from byte offset 4).
     */
    private fun dalyFrame(cmd: Int, data: ByteArray): ByteArray {
        require(data.size == 8) { "Daly data payload must be 8 bytes" }
        val frame = ByteArray(13)
        frame[0] = 0xA5.toByte()
        frame[1] = 0x01           // BMS-as-source address
        frame[2] = cmd.toByte()
        frame[3] = 0x08
        data.copyInto(frame, destinationOffset = 4)
        frame[12] = checksumSum(frame, 0, 12).toByte()
        return frame
    }

    private fun putU16BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putU32BE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    /**
     * 0x90 voltage frame payload (8 bytes, offsets from data start):
     *   [0..1]  voltage u16BE (decivolts, /10)
     *   [2..3]  unused (often "highest cell voltage" or 0)
     *   [4..5]  current u16BE, (raw - 30000) / 10
     *   [6..7]  SOC u16BE (/10 -> percent)
     */
    private fun voltageFramePayload(
        voltageRaw: Int = 5000,    // 500.0 V
        currentRaw: Int = 30123,   // (30123 - 30000) / 10 = 12.3 A
        socRaw: Int = 875          // 87.5 %
    ): ByteArray {
        val payload = ByteArray(8)
        putU16BE(payload, 0, voltageRaw)
        putU16BE(payload, 4, currentRaw)
        putU16BE(payload, 6, socRaw)
        return payload
    }

    /**
     * 0x93 status frame payload (8 bytes, offsets from data start):
     *   [0]     unused
     *   [1]     chargeMOS (u8, non-zero = enabled)
     *   [2]     dischargeMOS (u8, non-zero = enabled)
     *   [3]     cycles (u8)
     *   [4..7]  capacity u32BE (milliamp-hours, /1000)
     */
    private fun statusFramePayload(
        chargeMos: Int = 1,
        dischargeMos: Int = 1,
        cycles: Int = 42,
        capacityRaw: Long = 100_000L   // / 1000 = 100.0 Ah
    ): ByteArray {
        val payload = ByteArray(8)
        payload[1] = chargeMos.toByte()
        payload[2] = dischargeMos.toByte()
        payload[3] = cycles.toByte()
        putU32BE(payload, 4, capacityRaw)
        return payload
    }

    /**
     * 0x95 cell-voltage frame payload (8 bytes):
     *   [0]     frame number (1-based)
     *   [1..2]  cell N+0 mv (u16BE)
     *   [3..4]  cell N+1 mv (u16BE)
     *   [5..6]  cell N+2 mv (u16BE)
     *   [7]     unused
     * Parser ignores cells outside 1..5000 mv.
     */
    private fun cellFramePayload(frameNum: Int, cellsMv: IntArray): ByteArray {
        require(cellsMv.size == 3) { "Daly 0x95 carries exactly 3 cells per frame" }
        val payload = ByteArray(8)
        payload[0] = frameNum.toByte()
        putU16BE(payload, 1, cellsMv[0])
        putU16BE(payload, 3, cellsMv[1])
        putU16BE(payload, 5, cellsMv[2])
        return payload
    }

    /**
     * 0x96 temperature frame payload (8 bytes):
     *   [0]     frame number (1-based)
     *   [1..7]  up to 7 temperature sensors (u8 each, value = raw - 40 in Celsius)
     * Parser drops sensors where raw == 0.
     */
    private fun tempFramePayload(frameNum: Int, temps: IntArray): ByteArray {
        require(temps.size <= 7) { "Daly 0x96 carries up to 7 temps per frame" }
        val payload = ByteArray(8)
        payload[0] = frameNum.toByte()
        for (i in temps.indices) payload[1 + i] = temps[i].toByte()
        return payload
    }

    @Test
    fun `uuids match Daly spec`() {
        val p = DalyBmsProtocol()
        assertEquals("0000fff0-0000-1000-8000-00805f9b34fb", p.uuids.serviceUuid)
        assertEquals("0000fff1-0000-1000-8000-00805f9b34fb", p.uuids.notifyCharUuid)
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", p.uuids.writeCharUuid)
    }

    @Test
    fun `poll commands include voltage status cells and temps`() {
        val cmds = DalyBmsProtocol().pollCommands()
        // 0x98 is no longer polled — alarms now come from the BLE main-status
        // frame (D2 03 ...) per syssi/esphome-daly-bms-ble.
        assertEquals(4, cmds.size)
        // Each command frame: A5 80 [cmd] 08 ... ... [crc]
        // cmd byte lives at index 2.
        assertEquals(0x90.toByte(), cmds[0][2])
        assertEquals(0x93.toByte(), cmds[1][2])
        assertEquals(0x95.toByte(), cmds[2][2])
        assertEquals(0x96.toByte(), cmds[3][2])

        // Sanity: header + length byte
        for (cmd in cmds) {
            assertEquals(13, cmd.size)
            assertEquals(0xA5.toByte(), cmd[0])
            assertEquals(0x80.toByte(), cmd[1])
            assertEquals(0x08.toByte(), cmd[3])
            // CRC must match
            val expected = checksumSum(cmd, 0, 12).toByte()
            assertEquals(expected, cmd[12])
        }
    }

    @Test
    fun `voltage frame parses pack voltage current and SOC`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        val data = proto.latestData()
        assertNotNull(data)
        // 5000 / 10 = 500.0 V
        assertEquals(500.0f, data.voltage, 0.001f)
        // (31230 - 30000) / 10 = 12.3 A
        assertEquals(12.3f, data.current, 0.001f)
        // 875 / 10 = 87.5 %
        assertEquals(87.5f, data.soc, 0.01f)
        // power = voltage * current
        assertEquals(500.0f * 12.3f, data.power, 0.1f)
        assertTrue(data.isConnected)
    }

    @Test
    fun `status frame parses SOC cycles and capacity after voltage frame`() {
        val proto = DalyBmsProtocol()
        // mergeAndUpdate() gates on hasBasicData, so feed 0x90 first.
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        proto.onNotification(
            dalyFrame(
                0x93,
                statusFramePayload(
                    chargeMos = 1,
                    dischargeMos = 0,
                    cycles = 99,
                    capacityRaw = 250_500L
                )
            )
        )
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(99, data.numCycles)
        // 250500 / 1000 = 250.5
        assertEquals(250.5f, data.capacity, 0.001f)
        assertTrue(data.chargeEnabled)
        assertEquals(false, data.dischargeEnabled)
    }

    @Test
    fun `cells frame populates cellVoltages from a single 3-cell batch`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        proto.onNotification(
            dalyFrame(0x95, cellFramePayload(frameNum = 1, cellsMv = intArrayOf(3300, 3310, 3320)))
        )
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(3, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
        assertEquals(3.310f, data.cellVoltages[1], 0.001f)
        assertEquals(3.320f, data.cellVoltages[2], 0.001f)
    }

    @Test
    fun `cells frame accumulates across batches and clears on frame 1`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        proto.onNotification(
            dalyFrame(0x95, cellFramePayload(frameNum = 1, cellsMv = intArrayOf(3300, 3310, 3320)))
        )
        proto.onNotification(
            dalyFrame(0x95, cellFramePayload(frameNum = 2, cellsMv = intArrayOf(3330, 3340, 3350)))
        )
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(6, data.cellVoltages.size)
        assertEquals(3.350f, data.cellVoltages[5], 0.001f)

        // A new frame#1 should clear the previous list.
        proto.onNotification(
            dalyFrame(0x95, cellFramePayload(frameNum = 1, cellsMv = intArrayOf(3200, 3210, 3220)))
        )
        val reset = proto.latestData()
        assertNotNull(reset)
        assertEquals(3, reset.cellVoltages.size)
        assertEquals(3.200f, reset.cellVoltages[0], 0.001f)
    }

    /**
     * Build a BLE main-status frame (`D2 03 [len] [payload] [crc16 LE]`) carrying
     * the supplied 64-bit alarm bitmap at payload offset 119. The rest of the
     * payload is zero-padded — only the alarm window matters for the fault
     * decoder. CRC is MODBUS-style (poly 0xA001) over D2 03 LEN ... payload.
     */
    private fun bleMainStatusFrame(alarms64: Long, payloadLen: Int = 128): ByteArray {
        require(payloadLen >= 119 + 8) {
            "payload must be at least 127 bytes to fit alarm bitmap at offset 119"
        }
        val frame = ByteArray(3 + payloadLen + 2)
        frame[0] = 0xD2.toByte()
        frame[1] = 0x03
        frame[2] = payloadLen.toByte()
        // Write alarms64 as little-endian 8 bytes at absolute offset 3 + 119.
        val alarmAbs = 3 + 119
        for (i in 0 until 8) {
            frame[alarmAbs + i] = ((alarms64 ushr (i * 8)) and 0xFF).toByte()
        }
        val crc = crc16Modbus(frame, 0, frame.size - 2)
        frame[frame.size - 2] = (crc and 0xFF).toByte()
        frame[frame.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        return frame
    }

    @Test
    fun `BLE main status frame populates bmsFaults from 64-bit alarm bitmap`() {
        val proto = DalyBmsProtocol()
        // Feed the basic 0x90 frame so the merge gate (hasBasicData) opens.
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        // Bit 16 = "MOS OT (chg)" per esphome-daly-bms-ble ERRORS[64].
        proto.onNotification(bleMainStatusFrame(alarms64 = 1L shl 16))
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(listOf("MOS OT (chg)"), data.bmsFaults)
    }

    @Test
    fun `BLE main status frame decodes multiple alarm bits in ascending order`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        // Bit 17 = "MOS OT (dis)", bit 48 = "cell OV warn".
        proto.onNotification(
            bleMainStatusFrame(alarms64 = (1L shl 17) or (1L shl 48))
        )
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(listOf("MOS OT (dis)", "cell OV warn"), data.bmsFaults)
    }

    @Test
    fun `BLE main status frame with zero alarms yields empty bmsFaults`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        proto.onNotification(bleMainStatusFrame(alarms64 = 0L))
        val data = proto.latestData()
        assertNotNull(data)
        assertTrue(data.bmsFaults.isEmpty())
    }

    @Test
    fun `default basic frames produce empty bmsFaults`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        val data = proto.latestData()
        assertNotNull(data)
        assertTrue(data.bmsFaults.isEmpty())
    }

    @Test
    fun `temps frame populates temperatures with -40 offset and drops zero sensors`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        // Raw 65 => 25C, 67 => 27C, 70 => 30C; zeros are dropped.
        proto.onNotification(
            dalyFrame(0x96, tempFramePayload(frameNum = 1, temps = intArrayOf(65, 67, 70, 0, 0, 0, 0)))
        )
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(3, data.temperatures.size)
        assertEquals(25f, data.temperatures[0], 0.1f)
        assertEquals(27f, data.temperatures[1], 0.1f)
        assertEquals(30f, data.temperatures[2], 0.1f)
    }

    @Test
    fun `rejects frame with bad checksum`() {
        val proto = DalyBmsProtocol()
        val frame = dalyFrame(0x90, voltageFramePayload())
        // Corrupt the CRC byte at the end.
        frame[12] = (frame[12].toInt() xor 0xFF).toByte()
        proto.onNotification(frame)
        assertNull(proto.latestData())
    }

    @Test
    fun `reset clears state including accumulated cells and temps`() {
        val proto = DalyBmsProtocol()
        proto.onNotification(dalyFrame(0x90, voltageFramePayload()))
        proto.onNotification(
            dalyFrame(0x95, cellFramePayload(frameNum = 1, cellsMv = intArrayOf(3300, 3310, 3320)))
        )
        proto.onNotification(
            dalyFrame(0x96, tempFramePayload(frameNum = 1, temps = intArrayOf(65, 67, 0, 0, 0, 0, 0)))
        )
        assertNotNull(proto.latestData())

        proto.reset()
        assertNull(proto.latestData())

        // After reset, a fresh cells frame (without a new 0x90) must NOT emit data,
        // because hasBasicData was cleared and mergeAndUpdate gates on it.
        proto.onNotification(
            dalyFrame(0x95, cellFramePayload(frameNum = 1, cellsMv = intArrayOf(3300, 3310, 3320)))
        )
        assertNull(proto.latestData())
    }
}
