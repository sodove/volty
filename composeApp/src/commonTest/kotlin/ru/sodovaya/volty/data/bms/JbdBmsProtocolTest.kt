package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JbdBmsProtocolTest {

    /** Assemble a JBD response frame: DD <cmd> 00 <len> <data...> <csum_hi> <csum_lo> 77 */
    private fun jbdFrame(cmd: Int, data: ByteArray, status: Int = 0): ByteArray {
        val statusLenData = byteArrayOf(status.toByte(), data.size.toByte()) + data
        val csum = jbdChecksum(statusLenData)
        return byteArrayOf(0xDD.toByte(), cmd.toByte()) + statusLenData +
            byteArrayOf((csum shr 8).toByte(), (csum and 0xFF).toByte(), 0x77)
    }

    /**
     * JBD response checksum: 0x10000 - sum(status + len + data), low 16 bits.
     * Matches syssi/esphome-jbd-bms chksum_(raw + 2, data_len + 2).
     */
    private fun jbdChecksum(statusLenData: ByteArray): Int {
        var sum = 0
        for (b in statusLenData) sum += (b.toInt() and 0xFF)
        return (0x10000 - (sum and 0xFFFF)) and 0xFFFF
    }

    private fun mainDataPayload(
        voltageCv: Int = 5000,
        currentCa: Int = -200,
        chargeCAh: Int = 800,
        capacityCAh: Int = 1000,
        cycles: Int = 7,
        protectionFlags: Int = 0,
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
        // Protection bitmap at data offset 16 (u16 BE).
        be16(16, protectionFlags and 0xFFFF)
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
    fun `frame end detected via len field handles 0x77 inside payload`() {
        val proto = JbdBmsProtocol()
        // Force a cell payload that contains a 0x77 byte BEFORE the real terminator.
        // Cell at 0x7700 mV would be unrealistically high (>5V) so the parser sanity-filter
        // would reject it; pick a more realistic value.
        // Cell #1 = 0x0C77 = 3191 mV (3.191 V). Big-endian bytes: 0x0C 0x77.
        // The 0x77 in byte 2 of the cell payload sits at frame index 5 (header DD cmd 00 len 0C 77 ...).
        val cellsPayload = byteArrayOf(0x0C.toByte(), 0x77.toByte(), 0x0C.toByte(), 0x80.toByte())
        val frame = jbdFrame(0x04, cellsPayload)

        // Must also feed main data first so the merge logic surfaces BmsData
        proto.onNotification(jbdFrame(0x03, ByteArray(23)))
        proto.onNotification(frame)
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(2, data.cellVoltages.size)
        assertEquals(3.191f, data.cellVoltages[0], 0.001f)
        assertEquals(3.200f, data.cellVoltages[1], 0.001f)
    }

    @Test
    fun `main data then cell data assembles complete BmsData`() {
        val proto = JbdBmsProtocol()
        proto.onNotification(jbdFrame(0x03, mainDataPayload()))
        proto.onNotification(jbdFrame(0x04, cellsPayload(intArrayOf(3300, 3310))))

        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(50.00f, data.voltage, 0.001f)
        // JBD native sign: + = charging, - = discharging (matches our domain, no negation).
        // currentCa = -200 (raw) → -2.00 A = discharging / consumption.
        assertEquals(-2.00f, data.current, 0.001f)
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
        // Default payload sets no protection bits → empty fault list.
        assertTrue(data.bmsFaults.isEmpty())
    }

    @Test
    fun `positive raw current reads as charging`() {
        val proto = JbdBmsProtocol()
        // currentCa = +350 (raw) → +3.50 A. JBD native + = charging.
        proto.onNotification(jbdFrame(0x03, mainDataPayload(currentCa = 350)))
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(3.50f, data.current, 0.001f)
    }

    @Test
    fun `rejects frame with bad checksum`() {
        val proto = JbdBmsProtocol()
        val frame = jbdFrame(0x03, mainDataPayload())
        // Corrupt the checksum high byte (index size-3 is csum_hi).
        frame[frame.size - 3] = (frame[frame.size - 3].toInt() xor 0xFF).toByte()
        proto.onNotification(frame)
        assertNull(proto.latestData())
    }

    @Test
    fun `out-of-range cell voltages are filtered out`() {
        val proto = JbdBmsProtocol()
        proto.onNotification(jbdFrame(0x03, mainDataPayload()))
        // Second "cell" is implausible (0xFFFF mV ≈ 65 V) — a corrupted reading.
        // It must be dropped, not surfaced as a 65 V cell that trips cell-high alarms.
        proto.onNotification(jbdFrame(0x04, cellsPayload(intArrayOf(3300, 0xFFFF))))
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(1, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
    }

    @Test
    fun `parses protection flags into bmsFaults`() {
        val proto = JbdBmsProtocol()
        // Bit 6 = discharge OT in JBD's protection bitmap. Verify exactly that label appears.
        proto.onNotification(jbdFrame(0x03, mainDataPayload(protectionFlags = 1 shl 6)))
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(listOf("discharge OT"), data.bmsFaults)
    }
}
