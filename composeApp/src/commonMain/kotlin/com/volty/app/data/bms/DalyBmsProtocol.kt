package com.volty.app.data.bms

import com.volty.app.domain.model.BmsData

/**
 * Daly BMS protocol.
 *
 * BLE: RX (notify) = 0xFFF1, TX (write) = 0xFFF2
 *
 * Command frame (13 bytes):
 *   A5 80 [cmd] 08 [8 data bytes, zero-padded] [crc]
 *   CRC = sum(first 12 bytes) & 0xFF
 *
 * Response frame (13 bytes):
 *   A5 01 [cmd] 08 [8 data bytes] [crc]
 *
 * Commands:
 *   0x90 = Voltage/Current/SOC
 *   0x93 = Status (switch states, cycles, capacity)
 *   0x94 = Cell/temp counts
 *   0x95 = Cell voltages (3 per frame, multiple frames)
 *   0x96 = Temperatures
 *
 * Based on: github.com/fl4p/batmon-ha bmslib/models/daly.py
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class DalyBmsProtocol : BmsProtocol() {

    override val uuids = BmsUuids(
        serviceUuid = "0000fff0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000fff1-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000fff2-0000-1000-8000-00805f9b34fb"
    )

    private val buffer = ByteArrayAccumulator()
    private var lastData: BmsData? = null

    // Partial data accumulation
    private var voltage: Float = 0f
    private var current: Float = 0f
    private var soc: Float = 0f
    private var numCells: Int = 0
    private var numTemp: Int = 0
    private var numCycles: Int = 0
    private var capacity: Float = 0f
    private var chargeEnabled: Boolean = false
    private var dischargeEnabled: Boolean = false
    private var cellVoltages: MutableList<Float> = mutableListOf()
    private var temperatures: MutableList<Float> = mutableListOf()
    private var faults: List<String> = emptyList()
    private var hasBasicData = false

    override fun handshakeCommands(): List<ByteArray> = emptyList()

    override fun pollCommands(): List<ByteArray> = listOf(
        buildCommand(0x90), // Voltage/Current/SOC
        buildCommand(0x93), // Status
        buildCommand(0x95), // Cell voltages
        buildCommand(0x96), // Temperatures
        buildCommand(0x98)  // Alarm / fault flags
    )

    override val pollIntervalMs: Long = 500L

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        tryParseAll()
    }

    override fun latestData(): BmsData? = lastData

    override fun reset() {
        buffer.reset()
        lastData = null
        hasBasicData = false
        cellVoltages.clear()
        temperatures.clear()
        faults = emptyList()
    }

    // --- Protocol implementation ---

    private fun buildCommand(cmd: Int, data: ByteArray = ByteArray(8)): ByteArray {
        val frame = ByteArray(13)
        frame[0] = 0xA5.toByte()
        frame[1] = 0x80.toByte()  // BLE address
        frame[2] = cmd.toByte()
        frame[3] = 0x08           // Data length always 8
        data.copyInto(frame, destinationOffset = 4, endIndex = minOf(data.size, 8))
        frame[12] = checksumSum(frame, 0, 12).toByte()
        return frame
    }

    private fun tryParseAll() {
        while (true) {
            val buf = buffer.toByteArray()

            // Find start marker 0xA5
            val startIdx = buf.indexOfFirst { (it.toInt() and 0xFF) == 0xA5 }
            if (startIdx < 0) {
                buffer.reset()
                return
            }
            if (startIdx > 0) buffer.trimLeading(startIdx)

            val current = buffer.toByteArray()
            if (current.size < 13) return // Each Daly frame is exactly 13 bytes

            // Verify CRC
            val crc = checksumSum(current, 0, 12)
            if ((crc and 0xFF) != (current[12].toInt() and 0xFF)) {
                buffer.trimLeading(1) // Skip bad start byte
                continue
            }

            val cmd = current[2].toInt() and 0xFF
            parseResponse(cmd, current)
            buffer.trimLeading(13)
        }
    }

    private fun parseResponse(cmd: Int, frame: ByteArray) {
        val d = 4 // Data starts at byte 4
        when (cmd) {
            0x90 -> {
                // Voltage/Current/SOC
                // Voltage: u16 BE at d+0, / 10
                voltage = frame.u16BE(d) / 10f
                // Current: u16 BE at d+4, (value - 30000) / 10
                val rawCurrent = frame.u16BE(d + 4)
                current = (rawCurrent - 30000) / 10f
                // SOC: u16 BE at d+6, / 10
                soc = frame.u16BE(d + 6) / 10f
                hasBasicData = true
                mergeAndUpdate()
            }
            0x93 -> {
                // Status: charge/discharge state, cycles, capacity
                // Byte d+1: charge MOS (bool)
                chargeEnabled = (frame[d + 1].toInt() and 0xFF) != 0
                // Byte d+2: discharge MOS (bool)
                dischargeEnabled = (frame[d + 2].toInt() and 0xFF) != 0
                // Byte d+3: cycles u8 (actually part of u16?)
                numCycles = frame.u8(d + 3)
                // Capacity: i32 BE at d+4, / 1000
                capacity = frame.u32BE(d + 4) / 1000f
                mergeAndUpdate()
            }
            0x94 -> {
                // Cell/temp count
                numCells = frame.u8(d)
                numTemp = frame.u8(d + 1)
            }
            0x95 -> {
                // Cell voltages: 3 cells per frame
                val frameNum = frame.u8(d) // 1-based frame number
                if (frameNum == 1) cellVoltages.clear()
                for (i in 0 until 3) {
                    val mv = frame.u16BE(d + 1 + i * 2)
                    if (mv in 1..5000) cellVoltages.add(mv / 1000f)
                }
                mergeAndUpdate()
            }
            0x96 -> {
                // Temperatures: up to 7 sensors per frame. Frames carry a
                // 1-based frame number so multi-frame responses (up to 8
                // sensors across several frames) accumulate correctly.
                val frameNum = frame.u8(d) // 1-based frame number
                if (frameNum == 1) temperatures.clear()
                for (i in 0 until 7) {
                    val raw = frame.u8(d + 1 + i)
                    if (raw != 0) {
                        val celsius = (raw - 40).toFloat()
                        if (celsius in -40f..150f) temperatures.add(celsius)
                    }
                }
                mergeAndUpdate()
            }
            0x98 -> {
                // 8 bytes of alarm flags, one byte per category. Each byte
                // packs up to 8 bit-flags; we surface human-readable names
                // for every set bit.
                faults = parseFaults(frame, d)
                mergeAndUpdate()
            }
        }
    }

    private fun parseFaults(frame: ByteArray, d: Int): List<String> {
        // Per batmon-ha daly.py, command 0x98 returns 8 bytes of alarm bits:
        //   byte 0: cell-voltage alarms
        //   byte 1: pack-voltage alarms
        //   byte 2: charge-temp alarms
        //   byte 3: discharge-temp alarms
        //   byte 4: charge-current alarms
        //   byte 5: discharge-current alarms
        //   byte 6: SOC alarms
        //   byte 7: misc (voltage/temp diff, MOS temp, sensor failures)
        // We decode the most-significant levels first so users see the
        // worst-case label per category instead of a noisy duplicate set.
        val labels = arrayOf(
            arrayOf("cell OV L1", "cell OV L2", "cell UV L1", "cell UV L2"),
            arrayOf("pack OV L1", "pack OV L2", "pack UV L1", "pack UV L2"),
            arrayOf("charge OT L1", "charge OT L2", "charge UT L1", "charge UT L2"),
            arrayOf("discharge OT L1", "discharge OT L2", "discharge UT L1", "discharge UT L2"),
            arrayOf("charge OC L1", "charge OC L2"),
            arrayOf("discharge OC L1", "discharge OC L2"),
            arrayOf("SOC high L1", "SOC high L2", "SOC low L1", "SOC low L2"),
            arrayOf("cell diff L1", "cell diff L2", "temp diff L1", "temp diff L2",
                    "charge MOS OT", "discharge MOS OT", "charge MOS failure", "discharge MOS failure")
        )
        val out = mutableListOf<String>()
        for (byteIdx in 0 until 8) {
            if (d + byteIdx >= frame.size) break
            val b = frame.u8(d + byteIdx)
            if (b == 0) continue
            val names = labels[byteIdx]
            for (bit in names.indices) {
                if ((b ushr bit) and 1 == 1) out.add(names[bit])
            }
        }
        return out
    }

    private fun mergeAndUpdate() {
        if (!hasBasicData) return
        lastData = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
            soc = soc,
            capacity = capacity,
            numCycles = numCycles,
            cellVoltages = cellVoltages.toList(),
            temperatures = temperatures.toList(),
            chargeEnabled = chargeEnabled,
            dischargeEnabled = dischargeEnabled,
            bmsFaults = faults,
            isConnected = true
        )
    }
}
