package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData

/**
 * JK BMS protocol (JK02).
 *
 * BLE: single service 0xFFE0, single characteristic 0xFFE1 (notify + write).
 *
 * Command frame (20 bytes):
 *   AA 55 90 EB [addr] [len] [13 value bytes, zero-padded] [crc]
 *   CRC = sum(all_bytes) & 0xFF
 *
 * Response frame (~300 bytes, starts with 55 AA EB 90):
 *   Byte 4: response type (0x01=settings, 0x02=cell data, 0x03=device info)
 *   CRC at byte 299
 *
 * After sending 0x96 (query state), BMS streams type 0x02 messages continuously.
 *
 * Based on: github.com/fl4p/batmon-ha bmslib/models/jikong.py
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class JkBmsProtocol(
    /** Number of cells expected. 0 = auto-detect from settings response. */
    private val maxCells: Int = 24
) : BmsProtocol() {

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    // Internal buffer for accumulating notification chunks
    private val buffer = ByteArrayAccumulator()
    private var numCells: Int = maxCells
    private var fwOffset: Int = 0 // 0 for 24s firmware, 32 for 32s firmware
    private var lastData: BmsData? = null

    // Switch states from settings response (type 0x01)
    private var chargeSwitch: Boolean = false
    private var dischargeSwitch: Boolean = false

    override fun handshakeCommands(): List<ByteArray> = listOf(
        buildCommand(0x97),  // Query device info
        buildCommand(0x96)   // Query state → starts continuous streaming
    )

    override fun pollCommands(): List<ByteArray> = emptyList() // JK streams after 0x96

    override val pollIntervalMs: Long = 0L // Not used — streaming

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        tryParseAll()
    }

    override fun latestData(): BmsData? = lastData

    override fun reset() {
        buffer.reset()
        lastData = null
        numCells = maxCells
        fwOffset = 0
    }

    // --- Protocol implementation ---

    private fun buildCommand(address: Int, value: ByteArray = ByteArray(0)): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55
        frame[2] = 0x90.toByte()
        frame[3] = 0xEB.toByte()
        frame[4] = address.toByte()
        frame[5] = value.size.toByte()
        value.copyInto(frame, destinationOffset = 6)
        // bytes 6+value.size .. 18 already zero
        frame[19] = checksumSum(frame, 0, 19).toByte()
        return frame
    }

    private fun tryParseAll() {
        // Look for response headers in the buffer
        while (true) {
            val buf = buffer.toByteArray()
            val headerIdx = findHeader(buf)
            if (headerIdx < 0) {
                // No header found — keep last 4 bytes in case header is split
                if (buf.size > 4) buffer.trimLeading(buf.size - 4)
                return
            }
            // Skip any bytes before the header
            if (headerIdx > 0) buffer.trimLeading(headerIdx)

            val current = buffer.toByteArray()
            if (current.size < 300) return // Need more data

            // Verify CRC
            val crc = checksumSum(current, 0, 299)
            if ((crc and 0xFF) != (current[299].toInt() and 0xFF)) {
                // Bad CRC — skip this header and look for next
                buffer.trimLeading(4)
                continue
            }

            val responseType = current[4].toInt() and 0xFF
            parseResponse(responseType, current)

            // Consume this message
            val frameLen = if (current.size >= 320) 320 else 300
            buffer.trimLeading(frameLen.coerceAtMost(current.size))
        }
    }

    private fun findHeader(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if ((data[i].toInt() and 0xFF) == 0x55 &&
                (data[i + 1].toInt() and 0xFF) == 0xAA &&
                (data[i + 2].toInt() and 0xFF) == 0xEB &&
                (data[i + 3].toInt() and 0xFF) == 0x90
            ) return i
        }
        return -1
    }

    private fun parseResponse(type: Int, buf: ByteArray) {
        when (type) {
            0x01 -> parseSettings(buf)
            0x02 -> parseCellData(buf)
            0x03 -> { /* Device info — could extract model/version if needed */ }
        }
    }

    private fun parseSettings(buf: ByteArray) {
        if (buf.size < 300) return
        // num_cells at offset 114
        val nc = buf.u8(114)
        if (nc in 1..32) numCells = nc

        // Switch states
        chargeSwitch = buf.u8(118) != 0
        dischargeSwitch = buf.u8(122) != 0
    }

    private fun parseCellData(buf: ByteArray) {
        if (buf.size < 170 + fwOffset) return

        // Cell voltages starting at byte 6, 2 bytes each (little-endian, millivolts)
        val cells = mutableListOf<Float>()
        for (i in 0 until numCells) {
            val offset = 6 + i * 2
            if (offset + 1 >= buf.size) break
            val mv = buf.u16LE(offset)
            if (mv in 1..5000) { // Sanity check
                cells.add(mv / 1000f)
            }
        }

        val o = fwOffset // firmware offset for 32s

        // Main values
        val voltage = buf.u32LE(118 + o) * 0.001f
        val current = -(buf.i32LE(126 + o) * 0.001f) // Negated per batmon-ha

        // Temperatures (value / 10.0, -2000 = NaN/not connected).
        // T1/T2 are cell-block temps. JK02 firmware variants also expose a MOS
        // temperature later in the frame; we keep a conservative two-temp parse
        // here so that the bytes reused for the fault-flag word are not also
        // surfaced as a temperature.
        val temps = mutableListOf<Float>()
        val t1 = buf.i16LE(130 + o)
        if (t1 != -2000 && t1 in -400..1500) temps.add(t1 / 10f)
        val t2 = buf.i16LE(132 + o)
        if (t2 != -2000 && t2 in -400..1500) temps.add(t2 / 10f)

        val soc = buf.u8(141 + o).toFloat()
        val charge = buf.u32LE(142 + o) * 0.001f     // Remaining Ah
        val capacity = buf.u32LE(146 + o) * 0.001f    // Full capacity Ah
        val numCycles = buf.u32LE(150 + o).toInt()

        // Errors bitmask: u16 BIG-ENDIAN at data[136..137] for JK02_24S
        // firmware, data[166..167] for JK02_32S (134 + 32-byte fwOffset).
        // Source: syssi/esphome-jk-bms-ble, decode_jk02_cell_info_().
        // Frame may be shorter than the expected offset on truncated reads —
        // return empty list rather than crash.
        val faultBase = 136 + o
        val faults = if (buf.size >= faultBase + 2) {
            decodeJkFaults(buf.u16BE(faultBase))
        } else {
            emptyList()
        }

        lastData = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
            soc = soc,
            charge = charge,
            capacity = capacity,
            numCycles = numCycles,
            cellVoltages = cells,
            temperatures = temps,
            chargeEnabled = chargeSwitch,
            dischargeEnabled = dischargeSwitch,
            bmsFaults = faults,
            isConnected = true
        )
    }

    /**
     * Decode the 16-bit JK02 errors bitmask. Bit-to-name mapping is taken verbatim
     * from syssi/esphome-jk-bms-ble `error_bits_to_string_` (see jk_bms_ble.cpp),
     * shortened to fit the comma-joined fault banner in the UI.
     */
    private fun decodeJkFaults(flags: Int): List<String> {
        if (flags == 0) return emptyList()
        val out = mutableListOf<String>()
        for (i in JK_FAULT_NAMES.indices) {
            if ((flags ushr i) and 1 == 1) out.add(JK_FAULT_NAMES[i])
        }
        return out
    }

    companion object {
        // Bit 0 → "Charge Overtemperature", bit 15 → "Charge short circuit".
        // Order matches the esphome ERRORS table; names are shortened for the
        // single-line banner.
        private val JK_FAULT_NAMES = listOf(
            "charge OT",            // bit 0  Charge Overtemperature
            "charge UT",            // bit 1  Charge Undertemperature
            "coproc error",         // bit 2  Coprocessor communication error
            "cell UV",              // bit 3  Cell Undervoltage
            "pack UV",              // bit 4  Battery pack undervoltage
            "discharge OC",         // bit 5  Discharge overcurrent
            "discharge SC",         // bit 6  Discharge short circuit
            "discharge OT",         // bit 7  Discharge overtemperature
            "wire R",               // bit 8  Wire resistance
            "MOS OT",               // bit 9  Mosfet overtemperature
            "cell count mismatch",  // bit 10 Cell count is not equal to settings
            "current sensor",       // bit 11 Current sensor anomaly
            "cell OV",              // bit 12 Cell Overvoltage
            "pack OV",              // bit 13 Battery pack overvoltage
            "charge OC",            // bit 14 Charge overcurrent protection
            "charge SC"             // bit 15 Charge short circuit
        )
    }
}
