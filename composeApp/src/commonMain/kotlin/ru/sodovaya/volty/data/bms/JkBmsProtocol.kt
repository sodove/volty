package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryUnknown
import ru.sodovaya.volty.domain.model.TelemetryMetric

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
 * Device facts: the software and hardware version strings of the 0x03 device-info reply (ASCII,
 * shown verbatim) and the cell count the 0x01 settings reply declares. The vendor id (offset 6)
 * is not surfaced. The constructor parameter maxCells is the app expectation, not a BMS word, so
 * it is never a fact. Voltage, current, SOC, capacity, cycles, temperatures, switches and the
 * error word already reach the common battery section; no SOH, balancer or MOS state is decoded.
 *
 * Based on: github.com/fl4p/batmon-ha bmslib/models/jikong.py
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class JkBmsProtocol(
    /** Number of cells expected. 0 = auto-detect from settings response. */
    private val maxCells: Int = 24
) : BmsProtocol(), ProtocolFacts {

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
    // Defaults size the old live decoder; only device/settings replies qualify history.
    private var historyLayout: Int? = null
    private var historyDeclaredCells: Int? = null
    private var historyCells: Set<Int> = emptySet()
    private var historyHadStatus = false

    // Facts-only state: what the device-info and settings replies said, null until they did.
    private var softwareVersion: String? = null
    private var hardwareVersion: String? = null
    private var declaredCells: Int? = null
    private val factsCache = SmartBmsFactsCache()

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

    override fun latestData(packIndex: Int): BmsData? = lastData

    override fun reset() {
        buffer.reset()
        lastData = null
        numCells = maxCells
        fwOffset = 0
        historyLayout = null
        historyDeclaredCells = null
        historyCells = emptySet()
        historyHadStatus = false
        softwareVersion = null
        hardwareVersion = null
        declaredCells = null
        factsCache.reset()
    }

    /** The version strings and declared cell count seen so far; empty before any such reply. */
    override fun deviceFacts(): List<DeviceFact> =
        factsCache.facts(softwareVersion, hardwareVersion, declaredCells, declaredTempSensors = null)

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

            // Consume exactly the 300-byte frame we just validated. Trimming
            // more (the old `>= 320 → 320` heuristic) ate the first 20 bytes of
            // a back-to-back follow-up frame, silently dropping it. If a device
            // pads its frames, the leftover bytes have no 55 AA EB 90 header and
            // are discarded by the resync path above.
            buffer.trimLeading(300.coerceAtMost(current.size))
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
            0x03 -> parseDeviceInfo(buf)
        }
    }

    /**
     * Device info (response type 0x03). Used to detect the frame layout:
     * software major version >= 11 means the JK02_32S protocol, whose cell-data
     * fields sit 32 bytes later (per fl4p/batmon-ha jikong.py
     * `is_new_11fw_32s = int(sw_version.split('.')[0]) >= 11` and
     * syssi/esphome-jk-bms JK02_32S offset = 16 * 2).
     *
     * Layout per esphome decode_device_info_: vendor id at 6 (16 bytes),
     * hardware version at 22 (8 bytes), software version at 30 (8 bytes),
     * ASCII, zero-padded.
     */
    private fun parseDeviceInfo(buf: ByteArray) {
        if (buf.size < 38) return
        val sw = buf.decodeToString(30, 38).takeWhile { it != '\u0000' }
        softwareVersion = sw.ifEmpty { null }
        hardwareVersion = buf.decodeToString(22, 30).takeWhile { it != '\u0000' }.ifEmpty { null }
        val major = sw.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()
        val layout = major?.takeIf { it > 0 }?.let { if (it >= 11) 32 else 0 }
        val changed = historyLayout != layout
        historyLayout = layout
        if (changed && historyHadStatus) {
            // A previous revision invalidated only totals here. A downgrade
            // can be the final reply before disconnect: slots outside the
            // shared 24-word prefix must lose their values immediately.
            // This means unobservable, not a claim that cells were removed.
            val unobservable = if (layout == 32) emptySet() else historyCells.filter { it >= 24 }.toSet()
            invalidateHistory(unobservable)
        }
        if (major == null) return
        fwOffset = if (major >= 11) 32 else 0
    }

    private fun parseSettings(buf: ByteArray) {
        if (buf.size < 300) return
        // num_cells at offset 114
        val nc = buf.u8(114)
        if (nc in 1..32) numCells = nc
        declaredCells = nc.takeIf { it in 1..32 }

        // Switch states
        chargeSwitch = buf.u8(118) != 0
        dischargeSwitch = buf.u8(122) != 0
        // Settings declares a u32, not a low-byte guess (legacy UI stays unchanged).
        val count = buf.u32LE(114).takeIf { it in 1L..32L }?.toInt()
        val retired = if (count != null) historyCells.filter { it >= count }.toSet() else emptySet()
        val changed = historyDeclaredCells != count || retired.isNotEmpty()
        historyDeclaredCells = count
        historyCells = historyCells - retired
        if (changed && historyHadStatus) invalidateHistory(retired)
    }

    private fun invalidateHistory(retired: Set<Int>) {
        observeTelemetry(TelemetryOrigin.BATTERY, 0) {
            jkTelemetryScalars.map { TelemetryReading(it, null, TelemetryUnknown.PARTIAL_SOURCE) } +
                retired.sorted().map { TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.UNAVAILABLE, it) }
        }
    }

    private fun parseCellData(buf: ByteArray) {
        if (buf.size < 170 + fwOffset) return

        // Cell voltages starting at byte 6, 2 bytes each (little-endian,
        // millivolts). The 32S frame reserves 32 slots; unpopulated slots read
        // 0 and are dropped by the sanity filter, so scanning all of them is
        // safe even when fewer cells are connected.
        val cellSlots = if (fwOffset > 0) 32 else numCells
        val cells = mutableListOf<Float>()
        for (i in 0 until cellSlots) {
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
        // JK native sign already matches our domain (+ = charging, - = discharging),
        // so do NOT negate. Confirmed against syssi/esphome-jk-bms (positive current
        // = charging). batmon-ha negates only because its BmsSample uses the
        // opposite (+ = discharge) convention.
        val current = buf.i32LE(126 + o) * 0.001f

        // Temperatures (value / 10.0, -2000 = NaN/not connected), offsets per
        // syssi/esphome-jk-bms decode_jk02_cell_info_:
        //   T1/T2 at 130/132+offset (both protocols)
        //   MOS temp at 134 (JK02_24S only — on 32S those bytes are the fault word)
        //   T5/T4/T3 at 222/224/226+offset (JK02_32S only)
        val temps = mutableListOf<Float>()
        fun addTemp(offset: Int) {
            if (offset + 1 >= buf.size) return
            val raw = buf.i16LE(offset)
            if (raw != -2000 && raw in -400..1500) temps.add(raw / 10f)
        }
        addTemp(130 + o)
        addTemp(132 + o)
        if (o == 0) {
            addTemp(134) // MOS temperature (24S layout)
        } else {
            addTemp(226 + o)
            addTemp(224 + o)
            addTemp(222 + o)
        }

        val soc = buf.u8(141 + o).toFloat()
        val charge = buf.u32LE(142 + o) * 0.001f     // Remaining Ah
        val capacity = buf.u32LE(146 + o) * 0.001f    // Full capacity Ah
        val numCycles = buf.u32LE(150 + o).toInt()

        // Errors bitmask, little-endian (per syssi/esphome-jk-bms helpers
        // jk_get_16bit/jk_get_32bit, both LE):
        //   JK02_24S: u16 LE at data[136]
        //   JK02_32S: u32 LE at data[134 + 32] = data[166]
        // Frame may be shorter than the expected offset on truncated reads —
        // return empty list rather than crash.
        val faults = if (o > 0) {
            val faultBase = 134 + o
            if (buf.size >= faultBase + 4) decodeJkFaults(buf.u32LE(faultBase).toInt()) else emptyList()
        } else {
            if (buf.size >= 138) decodeJkFaults(buf.u16LE(136)) else emptyList()
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
        val layout = historyLayout
        val declared = historyDeclaredCells
        val previous = historyCells
        val indices = jkHistoryCellIndices(buf, layout, declared, previous)
        historyCells = indices
        historyHadStatus = true
        observeTelemetry(TelemetryOrigin.BATTERY, 0) { observedJkStatus(buf, layout, declared, previous, indices) }
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
