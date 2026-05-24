package com.volty.app.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntBmsProtocolTest {

    /**
     * Synthesize an ANT status response frame matching Kelly's parser:
     *   7E A1 11 XX XX [dataLen] [data...] [crc_lo] [crc_hi] AA 55
     * with CRC-16/MODBUS computed over bytes [1 : frameLen-4]
     * (i.e. A1 respCode XX XX dataLen data...).
     *
     * Layout inside data (offsets from frame start):
     *   8       numTemp (u8)
     *   9       numCells (u8)
     *   34..    cells (u16 LE millivolts, numCells of them)
     *   then    temps   (u16 LE celsius,   numTemp of them; 65496 = NaN)
     *   then    MOS temp (u16 LE; 65496 = NaN)
     *   +2      balancer temp (skipped)
     *   +2      total voltage  (u16 LE * 0.01)
     *   +2      current        (i16 LE * 0.1)
     *   +2      SOC            (u16 LE)
     *   +2      SOH            (skipped)
     *   +1      discharge enabled (u8 == 1)
     *   +1      charge enabled    (u8 == 1)
     *   +2      balancer state + reserved (skipped)
     *   +4      capacity       (u32 LE * 0.000001)
     *   +4      remaining Ah   (u32 LE * 0.000001)
     */
    private fun synthesizeStatusFrame(
        numCells: Int = 4,
        numTemp: Int = 2,
        cellVoltagesMv: IntArray = intArrayOf(3300, 3301, 3302, 3303),
        tempsCelsius: IntArray = intArrayOf(25, 27),
        mosTempCelsius: Int = 30,
        balancerTempRaw: Int = 0,
        voltageRaw: Int = 5050,     // 50.50 V
        currentRaw: Int = -123,     // -12.3 A
        soc: Int = 80,
        sohRaw: Int = 100,
        dischargeEnabled: Int = 1,
        chargeEnabled: Int = 1,
        balancerStateRaw: Int = 0,
        capacityRaw: Long = 100_000_000L,  // 100.0 Ah
        chargeRaw: Long = 80_000_000L,     // 80.0 Ah
        alarmFlags: Int = 0                // 24-bit packed
    ): ByteArray {
        // Compute dataLen so the data extends through the alarm bytes that sit
        // immediately after the remaining-charge u32.
        // pos after cells = 34 + numCells*2
        // pos after temps = + numTemp*2
        // pos after mos    = + 2
        // pos after balancer skip = + 2
        // pos after V/I/SOC/SOH = + 8
        // pos after switches = + 2
        // pos after balancer skip = + 2
        // pos after capacity+charge u32s = + 8
        // pos after alarm bytes = + 3
        val cellsEnd = 34 + numCells * 2
        val tempsEnd = cellsEnd + numTemp * 2
        val mosEnd = tempsEnd + 2
        val balancerEnd = mosEnd + 2
        val voltageEnd = balancerEnd + 2
        val currentEnd = voltageEnd + 2
        val socEnd = currentEnd + 2
        val sohEnd = socEnd + 2
        val switchesEnd = sohEnd + 2
        val balancer2End = switchesEnd + 2
        val capEnd = balancer2End + 4
        val chargeEnd = capEnd + 4
        val alarmEnd = chargeEnd + 3
        val dataEndOffset = alarmEnd
        val dataLen = dataEndOffset - 6
        val frameLen = 6 + dataLen + 4

        val frame = ByteArray(frameLen)
        frame[0] = 0x7E
        frame[1] = 0xA1.toByte()
        frame[2] = 0x11        // status response code
        frame[3] = 0x00        // reserved
        frame[4] = 0x00        // reserved
        frame[5] = dataLen.toByte()
        frame[8] = numTemp.toByte()
        frame[9] = numCells.toByte()

        // Cells (u16 LE millivolts)
        for (i in 0 until numCells) {
            putU16LE(frame, 34 + i * 2, cellVoltagesMv[i])
        }

        // Temps (u16 LE celsius)
        for (i in 0 until numTemp) {
            putU16LE(frame, cellsEnd + i * 2, tempsCelsius[i])
        }

        // MOS temp
        putU16LE(frame, tempsEnd, mosTempCelsius)

        // Balancer temp (skipped by parser)
        putU16LE(frame, mosEnd, balancerTempRaw)

        // Voltage (u16 LE)
        putU16LE(frame, balancerEnd, voltageRaw)

        // Current (i16 LE)
        putI16LE(frame, voltageEnd, currentRaw)

        // SOC (u16 LE)
        putU16LE(frame, currentEnd, soc)

        // SOH (skipped by parser)
        putU16LE(frame, socEnd, sohRaw)

        // Discharge enabled (u8), then charge enabled (u8)
        frame[sohEnd] = dischargeEnabled.toByte()
        frame[sohEnd + 1] = chargeEnabled.toByte()

        // Balancer state + reserved (skipped)
        putU16LE(frame, switchesEnd, balancerStateRaw)

        // Capacity (u32 LE)
        putU32LE(frame, balancer2End, capacityRaw)

        // Remaining charge (u32 LE)
        putU32LE(frame, capEnd, chargeRaw)

        // 3-byte alarm bitmap (LSB first)
        frame[chargeEnd] = (alarmFlags and 0xFF).toByte()
        frame[chargeEnd + 1] = ((alarmFlags ushr 8) and 0xFF).toByte()
        frame[chargeEnd + 2] = ((alarmFlags ushr 16) and 0xFF).toByte()

        // CRC over [1 : frameLen-4]
        val crc = crc16Modbus(frame, 1, frameLen - 4)
        frame[frameLen - 4] = (crc and 0xFF).toByte()
        frame[frameLen - 3] = ((crc ushr 8) and 0xFF).toByte()
        frame[frameLen - 2] = 0xAA.toByte()
        frame[frameLen - 1] = 0x55

        return frame
    }

    private fun putU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putI16LE(buf: ByteArray, offset: Int, value: Int) {
        val v = value and 0xFFFF
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    @Test
    fun `uuids match ANT spec`() {
        val p = AntBmsProtocol()
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", p.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", p.uuids.notifyCharUuid)
        assertEquals(p.uuids.notifyCharUuid, p.uuids.writeCharUuid)
    }

    @Test
    fun `poll commands present and first command starts with 7E A1`() {
        val cmds = AntBmsProtocol().pollCommands()
        assertTrue(cmds.isNotEmpty())
        val first = cmds[0]
        assertEquals(0x7E.toByte(), first[0])
        assertEquals(0xA1.toByte(), first[1])
        // Status command: func=0x01
        assertEquals(0x01.toByte(), first[2])
        // Trailer
        assertEquals(0xAA.toByte(), first[first.size - 2])
        assertEquals(0x55.toByte(), first[first.size - 1])
    }

    @Test
    fun `parses status frame given valid CRC`() {
        val proto = AntBmsProtocol()
        val frame = synthesizeStatusFrame()
        proto.onNotification(frame)
        val data = proto.latestData()
        assertNotNull(data)

        // voltage: 5050 * 0.01 = 50.50 V
        assertEquals(50.50f, data.voltage, 0.001f)
        // current: raw -123 * 0.1 = -12.3, negated to +12.3 A
        // (ANT native sign is opposite to ours; parser negates so + = charging in our domain)
        assertEquals(12.30f, data.current, 0.001f)
        assertEquals(80f, data.soc)
        assertTrue(data.chargeEnabled)
        assertTrue(data.dischargeEnabled)

        assertEquals(4, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
        assertEquals(3.303f, data.cellVoltages[3], 0.001f)

        // 2 cell-block temps + 1 MOS temp (balancer temp is skipped, not added)
        assertEquals(3, data.temperatures.size)
        assertEquals(25f, data.temperatures[0], 0.1f)
        assertEquals(27f, data.temperatures[1], 0.1f)
        assertEquals(30f, data.temperatures[2], 0.1f) // MOS

        // capacity = 100_000_000 * 0.000001 = 100.0 Ah
        assertEquals(100.0f, data.capacity, 0.001f)
        // remaining charge = 80_000_000 * 0.000001 = 80.0 Ah
        assertEquals(80.0f, data.charge, 0.001f)

        // power = voltage * current
        assertEquals(50.50f * 12.30f, data.power, 0.01f)

        assertTrue(data.isConnected)
    }

    @Test
    fun `rejects frame with bad CRC`() {
        val proto = AntBmsProtocol()
        val frame = synthesizeStatusFrame()
        // Corrupt the CRC bytes (at frameLen-4 and frameLen-3, i.e. just before AA 55)
        frame[frame.size - 4] = (frame[frame.size - 4].toInt() xor 0xFF).toByte()
        proto.onNotification(frame)
        assertNull(proto.latestData())
    }

    @Test
    fun `reset clears state`() {
        val proto = AntBmsProtocol()
        proto.onNotification(synthesizeStatusFrame())
        assertNotNull(proto.latestData())
        proto.reset()
        assertNull(proto.latestData())
    }

    @Test
    fun `bmsFaults is empty until ANT alarm layout is reversed`() {
        // ANT v3 splits ProtectInfo/WarningInfo into separate structures whose
        // exact offsets are unknown; until we have ground-truth from real
        // frames, the parser must not invent faults. See AntBmsProtocol.kt.
        val proto = AntBmsProtocol()
        proto.onNotification(synthesizeStatusFrame(alarmFlags = 0xFFFFFF))
        val data = proto.latestData()
        assertNotNull(data)
        assertTrue(data.bmsFaults.isEmpty())
    }
}
