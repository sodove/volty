package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData

/**
 * Daly BMS protocol.
 *
 * BLE: RX (notify) = 0xFFF1, TX (write) = 0xFFF2
 *
 * Two response framings are accepted in parallel because real-world Daly BMS
 * units mix them:
 *
 * 1. UART-style command/response (13 bytes each):
 *      A5 01 [cmd] 08 [8 data bytes] [crc]      CRC = sum(first 12 bytes) & 0xFF
 *    Used for the polled 0x90 / 0x93 / 0x94 / 0x95 / 0x96 commands.
 *
 * 2. BLE main-status frame (large, single-shot):
 *      D2 03 [len] [<len> payload bytes] [crc16 LE]
 *    Matches syssi/esphome-daly-bms-ble. The payload carries the full pack
 *    state in one frame; we currently extract only the 64-bit alarm bitmap
 *    at payload offset 119 (bits 16..63 active per esphome's ERRORS[64]).
 *
 * UART commands:
 *   0x90 = Voltage/Current/SOC
 *   0x93 = Status (switch states, cycles, capacity)
 *   0x94 = Cell/temp counts
 *   0x95 = Cell voltages (3 per frame, multiple frames)
 *   0x96 = Temperatures
 *
 * Based on: github.com/fl4p/batmon-ha bmslib/models/daly.py and
 * github.com/syssi/esphome-daly-bms-ble components/daly_bms_ble/.
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
        buildCommand(0x96)  // Temperatures
        // Note: command 0x98 was previously polled for alarms, but no
        // authoritative reference documents its layout for BLE units. Alarms
        // are now sourced from the BLE main-status frame (`D2 03 ...`) which
        // many Daly units stream unsolicited; see decodeDalyAlarms64.
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

            // Locate the next plausible frame start: either A5 (UART response)
            // or D2 (BLE main-status). Whichever comes first wins.
            val a5Idx = buf.indexOfFirst { (it.toInt() and 0xFF) == 0xA5 }
            val d203Idx = findD203(buf)
            val startIdx = when {
                a5Idx < 0 && d203Idx < 0 -> -1
                a5Idx < 0 -> d203Idx
                d203Idx < 0 -> a5Idx
                else -> minOf(a5Idx, d203Idx)
            }
            if (startIdx < 0) {
                // Keep last byte in case the next chunk completes a D2 03 prefix.
                if (buf.size > 1) buffer.trimLeading(buf.size - 1)
                return
            }
            if (startIdx > 0) buffer.trimLeading(startIdx)

            val current = buffer.toByteArray()
            if (current.isEmpty()) return

            val first = current[0].toInt() and 0xFF
            if (first == 0xA5) {
                if (!tryParseUartFrame(current)) return
            } else {
                // 0xD2 followed by 0x03 — BLE main-status frame.
                if (!tryParseBleMainFrame(current)) return
            }
        }
    }

    /** Return index of `D2 03` in [buf], or -1 if not present. */
    private fun findD203(buf: ByteArray): Int {
        for (i in 0..buf.size - 2) {
            if ((buf[i].toInt() and 0xFF) == 0xD2 &&
                (buf[i + 1].toInt() and 0xFF) == 0x03
            ) return i
        }
        return -1
    }

    /**
     * Parse a 13-byte UART-style response. Returns true when the frame was
     * consumed (either as valid data or as a discarded bad header). Returns
     * false when more bytes are still needed to complete the frame.
     */
    private fun tryParseUartFrame(current: ByteArray): Boolean {
        if (current.size < 13) return false // need more
        val crc = checksumSum(current, 0, 12)
        if ((crc and 0xFF) != (current[12].toInt() and 0xFF)) {
            buffer.trimLeading(1) // bad start, advance and retry
            return true
        }
        val cmd = current[2].toInt() and 0xFF
        parseResponse(cmd, current)
        buffer.trimLeading(13)
        return true
    }

    /**
     * Parse a BLE main-status frame. Layout per syssi/esphome-daly-bms-ble:
     *
     *   D2 03 [len] [<len> payload bytes] [crc16 LE]
     *
     * We currently only extract the 64-bit alarm bitmap at payload offset 119;
     * voltage/cells/temps continue to come from the 13-byte UART path. CRC is
     * MODBUS-style (poly 0xA001) over D2 03 [len] [payload].
     */
    private fun tryParseBleMainFrame(current: ByteArray): Boolean {
        if (current.size < 5) return false // need: header(2) + len(1) + crc(2)
        val payloadLen = current[2].toInt() and 0xFF
        val frameLen = 3 + payloadLen + 2 // hdr(3) + payload + crc16(2)
        if (current.size < frameLen) return false

        val crcGot = current.u16LE(frameLen - 2)
        val crcExpected = crc16Modbus(current, 0, frameLen - 2)
        if (crcGot != crcExpected) {
            // Bad frame — skip past the D2 byte and retry.
            buffer.trimLeading(1)
            return true
        }

        // Payload-relative offset 119 = absolute offset 122 (3 byte header).
        val alarmAbsOffset = 3 + 119
        faults = if (frameLen - 2 >= alarmAbsOffset + 8) {
            decodeDalyAlarms64(current.u64LE(alarmAbsOffset))
        } else {
            emptyList()
        }
        mergeAndUpdate()
        buffer.trimLeading(frameLen)
        return true
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
        }
    }

    /**
     * Decode the 64-bit Daly alarm bitmap. Bit-to-name mapping is taken
     * verbatim from syssi/esphome-daly-bms-ble ERRORS[64] (see
     * daly_bms_ble.cpp). Bits 0..15 and 44..47 are reserved/empty per esphome
     * and are intentionally omitted from the table. Names are shortened to
     * keep the comma-joined fault banner readable in the UI.
     */
    internal fun decodeDalyAlarms64(flags: Long): List<String> {
        if (flags == 0L) return emptyList()
        val out = mutableListOf<String>()
        for ((bit, name) in DALY_FAULT_NAMES) {
            if (((flags ushr bit) and 1L) == 1L) out.add(name)
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

    companion object {
        // Bit position → short fault label. Source:
        // github.com/syssi/esphome-daly-bms-ble components/daly_bms_ble/
        // daly_bms_ble.cpp ERRORS[64]. Bits 0..15 and 44..47 are reserved per
        // esphome and intentionally absent. Order preserved so the decoded
        // list matches LSB-first iteration of set bits.
        private val DALY_FAULT_NAMES: List<Pair<Int, String>> = listOf(
            16 to "MOS OT (chg)",         // Charging MOS over-temperature warning
            17 to "MOS OT (dis)",         // Discharging MOS over-temperature warning
            18 to "MOS T sensor (chg)",   // Charging MOS temperature sensor failure
            19 to "MOS T sensor (dis)",   // Discharging MOS temperature sensor failure
            20 to "MOS adhesion (chg)",   // Charging MOS adhesion failure
            21 to "MOS adhesion (dis)",   // Discharging MOS adhesion failure
            22 to "MOS circuit (chg)",    // Charging MOS circuit fault
            23 to "MOS circuit (dis)",    // Discharging MOS circuit fault
            24 to "AFE chip",             // AFE acquisition chip failure
            25 to "cells offline",        // Single unit collection is offline
            26 to "T sensor",             // Single temperature sensor failure
            27 to "EEPROM",               // EEPROM storage failure
            28 to "RTC",                  // RTC clock failure
            29 to "precharge",            // Precharge failed
            30 to "comm vehicle",         // Vehicle communication failed
            31 to "comm internal",        // Internal network communication module failure
            32 to "chg OC warn",          // Warning: Charging current too high
            33 to "chg OC crit",          // Critical: Charging current too high
            34 to "dis OC warn",          // Warning: Discharging current too low (sic)
            35 to "dis OC crit",          // Critical: Discharging current too low (sic)
            36 to "SOC high warn",        // Warning: SOC too high
            37 to "SOC high crit",        // Critical: SOC too high
            38 to "SOC low warn",         // Warning: SOC too low
            39 to "SOC low crit",         // Critical: SOC too low
            40 to "cell diff warn",       // Warning: Voltage difference too high
            41 to "cell diff crit",       // Critical: Voltage difference too high
            42 to "T diff warn",          // Warning: Temperature difference too high
            43 to "T diff crit",          // Critical: Temperature difference too high
            48 to "cell OV warn",         // Warning: Cell voltage too high
            49 to "cell OV crit",         // Critical: Cell voltage too high
            50 to "cell UV warn",         // Warning: Cell voltage too low
            51 to "cell UV crit",         // Critical: Cell voltage too low
            52 to "pack OV warn",         // Warning: Total voltage too high
            53 to "pack OV crit",         // Critical: Total voltage too high
            54 to "pack UV warn",         // Warning: Total voltage too low
            55 to "pack UV crit",         // Critical: Total voltage too low
            56 to "chg OT warn",          // Warning: Charging temperature too high
            57 to "chg OT crit",          // Critical: Charging temperature too high
            58 to "chg UT warn",          // Warning: Charging temperature too low
            59 to "chg UT crit",          // Critical: Charging temperature too low
            60 to "dis OT warn",          // Warning: Discharging temperature too high
            61 to "dis OT crit",          // Critical: Discharging temperature too high
            62 to "dis UT warn",          // Warning: Discharging temperature too low
            63 to "dis UT crit"           // Critical: Discharging temperature too low
        )
    }
}
