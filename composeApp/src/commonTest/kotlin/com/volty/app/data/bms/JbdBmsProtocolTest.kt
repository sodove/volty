package com.volty.app.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JbdBmsProtocolTest {

    /** Assemble a JBD response frame: DD <cmd> 00 <len> <data...> <csum_hi> <csum_lo> 77 */
    private fun jbdFrame(cmd: Int, data: ByteArray): ByteArray {
        val payload = byteArrayOf(0x00, data.size.toByte()) + data
        // Kelly's parser doesn't validate the checksum bytes — placeholders OK.
        return byteArrayOf(0xDD.toByte(), cmd.toByte()) + payload + byteArrayOf(0x00, 0x00, 0x77)
    }

    private fun mainDataPayload(
        voltageCv: Int = 5000,
        currentCa: Int = -200,
        chargeCAh: Int = 800,
        capacityCAh: Int = 1000,
        cycles: Int = 7,
        mosState: Int = 0x03,
        numTemp: Int = 2,
        temps: List<Int> = listOf(2981, 2991)
    ): ByteArray {
        val payload = ByteArray(23 + numTemp * 2)
        fun be16(off: Int, v: Int) {
            payload[off] = ((v shr 8) and 0xFF).toByte()
            payload[off + 1] = (v and 0xFF).toByte()
        }
        be16(0, voltageCv)
        be16(2, currentCa and 0xFFFF)
        be16(4, chargeCAh)
        be16(6, capacityCAh)
        be16(8, cycles)
        payload[19] = 80
        payload[20] = mosState.toByte()
        payload[22] = numTemp.toByte()
        for (i in 0 until numTemp) be16(23 + i * 2, temps[i])
        return payload
    }

    private fun cellsPayload(cellsMv: IntArray): ByteArray {
        val out = ByteArray(cellsMv.size * 2)
        for ((i, mv) in cellsMv.withIndex()) {
            out[i * 2] = ((mv shr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (mv and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `uuids match JBD spec`() {
        val p = JbdBmsProtocol()
        assertEquals("0000ff00-0000-1000-8000-00805f9b34fb", p.uuids.serviceUuid)
        assertEquals("0000ff01-0000-1000-8000-00805f9b34fb", p.uuids.notifyCharUuid)
        assertEquals("0000ff02-0000-1000-8000-00805f9b34fb", p.uuids.writeCharUuid)
    }

    @Test
    fun `poll commands include 0x03 and 0x04`() {
        val cmds = JbdBmsProtocol().pollCommands()
        assertEquals(2, cmds.size)
        assertEquals(0x03.toByte(), cmds[0][2])
        assertEquals(0x04.toByte(), cmds[1][2])
    }

    @Test
    fun `main data then cell data assembles complete BmsData`() {
        val proto = JbdBmsProtocol()
        proto.onNotification(jbdFrame(0x03, mainDataPayload()))
        proto.onNotification(jbdFrame(0x04, cellsPayload(intArrayOf(3300, 3310))))

        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(50.00f, data.voltage, 0.001f)
        // currentCa = -200 (raw), parser does -(i16BE(...) / 100) = -(-2.00) = +2.00
        assertEquals(2.00f, data.current, 0.001f)
        assertEquals(8.00f, data.charge, 0.001f)
        assertEquals(10.00f, data.capacity, 0.001f)
        assertEquals(7, data.numCycles)
        assertEquals(80f, data.soc)
        assertTrue(data.chargeEnabled)
        assertTrue(data.dischargeEnabled)
        assertEquals(2, data.temperatures.size)
        // (2981 - 2731) / 10 = 25.0
        assertEquals(25.0f, data.temperatures[0], 0.1f)
        assertEquals(2, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
    }
}
