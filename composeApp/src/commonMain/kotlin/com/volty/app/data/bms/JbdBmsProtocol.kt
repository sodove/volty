package com.volty.app.data.bms

import com.volty.app.domain.model.BmsData

/**
 * JBD / Xiaoxiang BMS protocol.
 *
 * BLE: RX (notify) = 0xFF01, TX (write) = 0xFF02
 *
 * Command frame (7 bytes):
 *   DD A5 [cmd] 00 [csum_hi] [csum_lo] 77
 *   Checksum: 0xFFFF - (cmd - 1) → split into 2 bytes
 *
 * Response: starts with DD [cmd], ends with 77
 *   DD [cmd] 00 [len] [data...] [csum_hi] [csum_lo] 77
 *
 * Commands:
 *   0x03 = Main data (voltage, current, SOC, temperatures, switch states)
 *   0x04 = Cell voltages
 *
 * Based on: github.com/fl4p/batmon-ha bmslib/models/jbd.py
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class JbdBmsProtocol : BmsProtocol() {

    override val uuids = BmsUuids(
        serviceUuid = "0000ff00-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ff01-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ff02-0000-1000-8000-00805f9b34fb"
    )

    private val buffer = ByteArrayAccumulator()
    private var lastData: BmsData? = null

    // Partial state — main data and cell data parsed separately
    private var mainVoltage: Float = 0f
    private var mainCurrent: Float = 0f
    private var mainSoc: Float = 0f
    private var mainCharge: Float = 0f
    private var mainCapacity: Float = 0f
    private var mainNumCycles: Int = 0
    private var mainTemperatures: List<Float> = emptyList()
    private var mainChargeEnabled: Boolean = false
    private var mainDischargeEnabled: Boolean = false
    private var mainFaults: List<String> = emptyList()
    private var cellVoltages: List<Float> = emptyList()
    private var hasMainData = false
    private var hasCellData = false

    override fun handshakeCommands(): List<ByteArray> = emptyList()

    override fun pollCommands(): List<ByteArray> = listOf(
        buildCommand(0x03), // Main data
        buildCommand(0x04)  // Cell voltages
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
        hasMainData = false
        hasCellData = false
    }

    // --- Protocol implementation ---

    private fun buildCommand(cmd: Int): ByteArray {
        val csum = 0xFFFF - (cmd - 1)
        return byteArrayOf(
            0xDD.toByte(),
            0xA5.toByte(),
            cmd.toByte(),
            0x00,
            (csum shr 8).toByte(),
            (csum and 0xFF).toByte(),
            0x77
        )
    }

    private fun tryParseAll() {
        while (true) {
            val buf = buffer.toByteArray()

            // Find start marker 0xDD
            val startIdx = buf.indexOfFirst { (it.toInt() and 0xFF) == 0xDD }
            if (startIdx < 0) {
                buffer.reset()
                return
            }
            if (startIdx > 0) buffer.trimLeading(startIdx)

            val current = buffer.toByteArray()
            // Need at least: DD cmd status len ... csum_hi csum_lo 77 = 7 bytes minimum
            if (current.size < 7) return

            // Declared data length at byte 3
            val dataLen = current[3].toInt() and 0xFF
            // Total expected frame length: header(4) + data(dataLen) + checksum(2) + terminator(1)
            val expectedLen = 4 + dataLen + 3
            if (current.size < expectedLen) return // wait for more bytes

            // Validate end terminator at expected position
            if ((current[expectedLen - 1].toInt() and 0xFF) != 0x77) {
                // Bad frame — advance past this DD and try again
                buffer.trimLeading(1)
                continue
            }

            val frame = current.copyOfRange(0, expectedLen)
            val cmd = frame[1].toInt() and 0xFF
            parseResponse(cmd, frame)

            buffer.trimLeading(expectedLen)
        }
    }

    private fun parseResponse(cmd: Int, frame: ByteArray) {
        if (frame.size < 7) return
        val dataLen = frame[3].toInt() and 0xFF
        if (frame.size < 4 + dataLen + 3) return // Incomplete

        when (cmd) {
            0x03 -> parseMainData(frame)
            0x04 -> parseCellVoltages(frame)
        }
    }

    private fun parseMainData(frame: ByteArray) {
        if (frame.size < 27) return
        val d = 4 // Data starts at byte 4

        // Voltage: u16 BE / 100 (volts)
        mainVoltage = frame.u16BE(d) / 100f
        // Current: i16 BE / 100, negated (positive = discharge)
        mainCurrent = -(frame.i16BE(d + 2) / 100f)
        // Remaining charge: u16 BE / 100 (Ah)
        mainCharge = frame.u16BE(d + 4) / 100f
        // Full capacity: u16 BE / 100 (Ah)
        mainCapacity = frame.u16BE(d + 6) / 100f
        // Num cycles: u16 BE
        mainNumCycles = frame.u16BE(d + 8)
        // SOC: byte at offset 19 from data start
        mainSoc = (frame[d + 19].toInt() and 0xFF).toFloat()

        // Protection / alarm flags: u16 BE at offset 16 from data start
        // (frame byte 20). Each bit is a specific protection condition.
        mainFaults = parseFaults(frame.u16BE(d + 16))

        // MOS state: byte at offset 20
        //   0 = both off, 1 = charge on, 2 = discharge on, 3 = both on
        val mosState = frame[d + 20].toInt() and 0xFF
        mainChargeEnabled = (mosState and 0x01) != 0
        mainDischargeEnabled = (mosState and 0x02) != 0

        // Number of temperature sensors: byte at offset 22
        val numTemp = frame[d + 22].toInt() and 0xFF

        // Temperatures starting at offset 23, 2 bytes each.
        // Format: Kelvin * 10, subtract 2731 then / 10 for Celsius.
        // JBD reports as many sensors as numTemp; we trust that count but
        // clamp to plausible range so a glitched frame doesn't surface garbage.
        val temps = mutableListOf<Float>()
        for (i in 0 until numTemp) {
            val off = d + 23 + i * 2
            if (off + 1 >= frame.size) break
            val raw = frame.u16BE(off)
            val celsius = (raw - 2731) / 10f
            if (celsius in -40f..150f) temps.add(celsius)
        }
        mainTemperatures = temps

        hasMainData = true
        mergeAndUpdate()
    }

    private fun parseCellVoltages(frame: ByteArray) {
        if (frame.size < 6) return
        val dataLen = frame[3].toInt() and 0xFF
        val numCells = dataLen / 2
        val cells = mutableListOf<Float>()
        for (i in 0 until numCells) {
            val off = 4 + i * 2
            if (off + 1 >= frame.size) break
            val mv = frame.u16BE(off)
            cells.add(mv / 1000f)
        }
        cellVoltages = cells
        hasCellData = true
        mergeAndUpdate()
    }

    private fun mergeAndUpdate() {
        if (!hasMainData) return
        lastData = BmsData(
            voltage = mainVoltage,
            current = mainCurrent,
            power = mainVoltage * mainCurrent,
            soc = mainSoc,
            charge = mainCharge,
            capacity = mainCapacity,
            numCycles = mainNumCycles,
            cellVoltages = cellVoltages,
            temperatures = mainTemperatures,
            chargeEnabled = mainChargeEnabled,
            dischargeEnabled = mainDischargeEnabled,
            bmsFaults = mainFaults,
            isConnected = true
        )
    }

    private fun parseFaults(flags: Int): List<String> {
        if (flags == 0) return emptyList()
        val names = listOf(
            "cell OV",          // bit 0
            "cell UV",          // bit 1
            "pack OV",          // bit 2
            "pack UV",          // bit 3
            "charge OT",        // bit 4
            "charge UT",        // bit 5
            "discharge OT",     // bit 6
            "discharge UT",     // bit 7
            "charge OC",        // bit 8
            "discharge OC",     // bit 9
            "short circuit",    // bit 10
            "front-end fault",  // bit 11
            "software lock"     // bit 12
        )
        val out = mutableListOf<String>()
        for (i in names.indices) {
            if ((flags ushr i) and 1 == 1) out.add(names[i])
        }
        return out
    }
}
