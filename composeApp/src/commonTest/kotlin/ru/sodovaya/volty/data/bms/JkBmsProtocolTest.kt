package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JkBmsProtocolTest {

    private fun synthesizeCellDataFrame(
        numCells: Int = 4,
        cellVoltagesMv: IntArray = intArrayOf(3300, 3301, 3302, 3303),
        packVoltageMv: Long = 13200,
        currentMa: Int = -5000,
        socPercent: Int = 75,
        remainingAhMilli: Long = 9000,
        capacityAhMilli: Long = 12000,
        cycles: Long = 42,
        faultFlags: Int = 0
    ): ByteArray {
        val frame = ByteArray(320)
        frame[0] = 0x55.toByte(); frame[1] = 0xAA.toByte()
        frame[2] = 0xEB.toByte(); frame[3] = 0x90.toByte()
        frame[4] = 0x02
        for (i in 0 until numCells) {
            val mv = cellVoltagesMv[i]
            frame[6 + i * 2] = (mv and 0xFF).toByte()
            frame[6 + i * 2 + 1] = ((mv ushr 8) and 0xFF).toByte()
        }
        writeU32LE(frame, 118, packVoltageMv)
        writeU32LE(frame, 126, currentMa.toLong() and 0xFFFFFFFFL)
        writeI16LE(frame, 130, -2000)
        writeI16LE(frame, 132, -2000)
        writeI16LE(frame, 134, -2000) // MOS temp: not connected
        // Errors bitmask at offset 136 (u16 LITTLE-endian, per esphome-jk-bms
        // jk_get_16bit) for the JK02_24S firmware. (fwOffset = 0 by default.)
        frame[136] = (faultFlags and 0xFF).toByte()
        frame[137] = ((faultFlags ushr 8) and 0xFF).toByte()
        frame[141] = socPercent.toByte()
        writeU32LE(frame, 142, remainingAhMilli)
        writeU32LE(frame, 146, capacityAhMilli)
        writeU32LE(frame, 150, cycles)
        frame[299] = checksumSum(frame, 0, 299).toByte()
        return frame
    }

    private fun writeU32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeI16LE(buf: ByteArray, offset: Int, value: Int) {
        val v = value and 0xFFFF
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    @Test
    fun `uuids match JK BMS spec`() {
        val p = JkBmsProtocol()
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", p.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", p.uuids.notifyCharUuid)
        assertEquals(p.uuids.notifyCharUuid, p.uuids.writeCharUuid)
    }

    @Test
    fun `handshake sends 0x97 then 0x96`() {
        val cmds = JkBmsProtocol().handshakeCommands()
        assertEquals(2, cmds.size)
        assertEquals(0x97.toByte(), cmds[0][4])
        assertEquals(0x96.toByte(), cmds[1][4])
    }

    @Test
    fun `poll commands are empty - streaming protocol`() {
        assertTrue(JkBmsProtocol().pollCommands().isEmpty())
    }

    @Test
    fun `parses valid cell-data frame`() {
        val proto = JkBmsProtocol(maxCells = 4)
        proto.onNotification(synthesizeCellDataFrame())
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(4, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
        assertEquals(3.303f, data.cellVoltages[3], 0.001f)
        assertEquals(13.200f, data.voltage, 0.001f)
        // JK native sign: + = charging, - = discharging (matches our domain, no negation).
        // Raw currentMa = -5000 on the wire → -5.000 A = discharging / consumption.
        assertEquals(-5.000f, data.current, 0.001f)
        assertEquals(75f, data.soc)
        assertEquals(9.000f, data.charge, 0.001f)
        assertEquals(12.000f, data.capacity, 0.001f)
        assertEquals(42, data.numCycles)
        assertTrue(data.isConnected)
    }

    @Test
    fun `positive raw current reads as charging`() {
        val proto = JkBmsProtocol(maxCells = 4)
        // +8000 mA on the wire = charging. Domain convention: + = charging.
        proto.onNotification(synthesizeCellDataFrame(currentMa = 8000))
        assertEquals(8.000f, proto.latestData()!!.current, 0.001f)
    }

    @Test
    fun `rejects frame with bad CRC`() {
        val proto = JkBmsProtocol(maxCells = 4)
        val frame = synthesizeCellDataFrame()
        frame[299] = (frame[299].toInt() + 1).toByte()
        proto.onNotification(frame)
        assertNull(proto.latestData())
    }

    @Test
    fun `reset clears state`() {
        val proto = JkBmsProtocol(maxCells = 4)
        proto.onNotification(synthesizeCellDataFrame())
        assertNotNull(proto.latestData())
        proto.reset()
        assertNull(proto.latestData())
    }

    @Test
    fun `parses fault flags from offset 136 little-endian`() {
        val proto = JkBmsProtocol(maxCells = 4)
        // Per esphome-jk-bms-ble:
        //   bit 12 = "Cell Overvoltage" → shortened to "cell OV"
        //   bit 3  = "Cell Undervoltage" → shortened to "cell UV"
        // Verify a single-bit set and a two-bit set decode in the right order.
        proto.onNotification(synthesizeCellDataFrame(faultFlags = 1 shl 12))
        assertEquals(listOf("cell OV"), proto.latestData()!!.bmsFaults)

        val proto2 = JkBmsProtocol(maxCells = 4)
        proto2.onNotification(synthesizeCellDataFrame(faultFlags = (1 shl 3) or (1 shl 12)))
        // Bits are decoded LSB-first, so bit 3 comes before bit 12.
        assertEquals(listOf("cell UV", "cell OV"), proto2.latestData()!!.bmsFaults)
    }

    @Test
    fun `no fault flags yields empty list`() {
        val proto = JkBmsProtocol(maxCells = 4)
        proto.onNotification(synthesizeCellDataFrame())
        val data = proto.latestData()
        assertNotNull(data)
        assertTrue(data.bmsFaults.isEmpty())
    }

    // ----- JK02_32S (firmware >= 11) -----

    /** Device info frame (type 0x03) carrying the given software version at offset 30. */
    private fun synthesizeDeviceInfoFrame(swVersion: String): ByteArray {
        val frame = ByteArray(300)
        frame[0] = 0x55.toByte(); frame[1] = 0xAA.toByte()
        frame[2] = 0xEB.toByte(); frame[3] = 0x90.toByte()
        frame[4] = 0x03
        "JK-TEST".encodeToByteArray().copyInto(frame, 6)   // vendor id (16 bytes)
        "11.A".encodeToByteArray().copyInto(frame, 22)     // hardware version (8 bytes)
        swVersion.encodeToByteArray().copyInto(frame, 30)  // software version (8 bytes)
        frame[299] = checksumSum(frame, 0, 299).toByte()
        return frame
    }

    /** Cell-data frame in the JK02_32S layout: all main fields shifted +32. */
    private fun synthesize32sCellDataFrame(
        cellVoltagesMv: IntArray = intArrayOf(3300, 3301, 3302, 3303),
        packVoltageMv: Long = 13200,
        currentMa: Int = -5000,
        socPercent: Int = 75,
        faultFlags: Int = 0
    ): ByteArray {
        val o = 32
        val frame = ByteArray(300)
        frame[0] = 0x55.toByte(); frame[1] = 0xAA.toByte()
        frame[2] = 0xEB.toByte(); frame[3] = 0x90.toByte()
        frame[4] = 0x02
        for (i in cellVoltagesMv.indices) {
            writeI16LE(frame, 6 + i * 2, cellVoltagesMv[i])
        }
        writeU32LE(frame, 118 + o, packVoltageMv)
        writeU32LE(frame, 126 + o, currentMa.toLong() and 0xFFFFFFFFL)
        writeI16LE(frame, 130 + o, 215)   // T1 = 21.5 °C
        writeI16LE(frame, 132 + o, -2000) // T2: not connected
        // Errors bitmask: u32 LE at 134 + o = 166
        writeU32LE(frame, 134 + o, faultFlags.toLong() and 0xFFFFFFFFL)
        frame[141 + o] = socPercent.toByte()
        writeU32LE(frame, 142 + o, 9000)
        writeU32LE(frame, 146 + o, 12000)
        writeU32LE(frame, 150 + o, 42)
        // Extra sensors T5/T4/T3 at 222/224/226 + o: not connected
        writeI16LE(frame, 222 + o, -2000)
        writeI16LE(frame, 224 + o, -2000)
        writeI16LE(frame, 226 + o, -2000)
        frame[299] = checksumSum(frame, 0, 299).toByte()
        return frame
    }

    @Test
    fun `firmware 11+ device info switches to 32S offsets`() {
        val proto = JkBmsProtocol()
        proto.onNotification(synthesizeDeviceInfoFrame("11.26"))
        proto.onNotification(synthesize32sCellDataFrame(faultFlags = 1 shl 12))
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(4, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
        assertEquals(13.200f, data.voltage, 0.001f)
        assertEquals(-5.000f, data.current, 0.001f)
        assertEquals(75f, data.soc)
        assertEquals(9.000f, data.charge, 0.001f)
        assertEquals(12.000f, data.capacity, 0.001f)
        assertEquals(42, data.numCycles)
        assertEquals(listOf(21.5f), data.temperatures)
        assertEquals(listOf("cell OV"), data.bmsFaults)
    }

    @Test
    fun `firmware below 11 keeps 24S offsets`() {
        val proto = JkBmsProtocol(maxCells = 4)
        proto.onNotification(synthesizeDeviceInfoFrame("10.07"))
        proto.onNotification(synthesizeCellDataFrame())
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(13.200f, data.voltage, 0.001f)
        assertEquals(75f, data.soc)
    }
}
