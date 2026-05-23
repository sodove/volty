package com.volty.app.data.bms

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
        cycles: Long = 42
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
        writeU32LE(frame, 126, (-currentMa).toLong() and 0xFFFFFFFFL)
        writeI16LE(frame, 130, -2000)
        writeI16LE(frame, 132, -2000)
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
        // Writer encodes (-currentMa) = 5000 at byte 126 (u32 LE).
        // Parser reads i32LE = 5000, multiplies by 0.001 = 5.0, then negates per
        // batmon-ha -> -5.000 A. (Note: original plan comment claimed +5.000 but
        // the writer+parser arithmetic actually yields -5.000.)
        assertEquals(-5.000f, data.current, 0.001f)
        assertEquals(75f, data.soc)
        assertEquals(9.000f, data.charge, 0.001f)
        assertEquals(12.000f, data.capacity, 0.001f)
        assertEquals(42, data.numCycles)
        assertTrue(data.isConnected)
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
}
