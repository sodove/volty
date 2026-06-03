package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData

/**
 * ANT BMS protocol.
 *
 * BLE: service 0xFFE0, single characteristic 0xFFE1 (notify + write).
 *
 * Command frame (10 bytes):
 *   7E A1 [func] [addr_lo] [addr_hi] [value] [crc_lo] [crc_hi] AA 55
 *   CRC-16/MODBUS over bytes[1:6] (A1, func, addr_lo, addr_hi, value)
 *
 * Response frame (variable length):
 *   7E A1 [resp_code] XX XX [data_len] [data...] [crc_lo] [crc_hi] AA 55
 *
 * Commands:
 *   Status:     func=0x01, addr=0x0000, value=0xBE → response code 0x11
 *   DeviceInfo: func=0x02, addr=0x026C, value=0x20 → response code 0x12
 *
 * Based on: github.com/fl4p/batmon-ha bmslib/models/ant.py
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class AntBmsProtocol : BmsProtocol() {

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    private val buffer = ByteArrayAccumulator()
    private var lastData: BmsData? = null

    override fun handshakeCommands(): List<ByteArray> = listOf(
        buildCommand(FUNC_STATUS, 0x0000, 0xBE)
    )

    override fun pollCommands(): List<ByteArray> = listOf(
        buildCommand(FUNC_STATUS, 0x0000, 0xBE)
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
    }

    // --- Protocol implementation ---

    private fun buildCommand(func: Int, addr: Int, value: Int): ByteArray {
        val payload = byteArrayOf(
            0xA1.toByte(),
            func.toByte(),
            (addr and 0xFF).toByte(),
            (addr shr 8 and 0xFF).toByte(),
            value.toByte()
        )
        val crc = crc16Modbus(payload)
        return byteArrayOf(
            0x7E,
            *payload,
            (crc and 0xFF).toByte(),
            (crc shr 8 and 0xFF).toByte(),
            0xAA.toByte(),
            0x55
        )
    }

    private fun tryParseAll() {
        while (true) {
            val buf = buffer.toByteArray()

            // Find frame start: 7E A1
            val startIdx = findFrameStart(buf)
            if (startIdx < 0) {
                if (buf.size > 2) buffer.trimLeading(buf.size - 2)
                return
            }
            if (startIdx > 0) buffer.trimLeading(startIdx)

            val current = buffer.toByteArray()
            if (current.size < 10) return // Minimum frame size

            // Data length at byte 5
            val dataLen = current.u8(5)
            val frameLen = 6 + dataLen + 4  // header(6) + data + crc(2) + trailer(2)
            if (current.size < frameLen) return // Need more data

            // Verify trailer: AA 55
            if ((current[frameLen - 2].toInt() and 0xFF) != 0xAA ||
                (current[frameLen - 1].toInt() and 0xFF) != 0x55
            ) {
                buffer.trimLeading(2) // Skip bad header
                continue
            }

            // Verify CRC-16 over bytes[1 : frameLen-4]
            val crcExpected = current.u16LE(frameLen - 4)
            val crcComputed = crc16Modbus(current, 1, frameLen - 4)
            if (crcExpected != crcComputed) {
                buffer.trimLeading(2)
                continue
            }

            val respCode = current.u8(2)
            parseResponse(respCode, current, frameLen)
            buffer.trimLeading(frameLen)
        }
    }

    private fun findFrameStart(data: ByteArray): Int {
        for (i in 0..data.size - 2) {
            if ((data[i].toInt() and 0xFF) == 0x7E &&
                (data[i + 1].toInt() and 0xFF) == 0xA1
            ) return i
        }
        return -1
    }

    private fun parseResponse(respCode: Int, buf: ByteArray, frameLen: Int) {
        when (respCode) {
            0x11 -> parseStatus(buf, frameLen)
            0x12 -> { /* Device info — model/version extraction if needed */ }
        }
    }

    private fun parseStatus(buf: ByteArray, frameLen: Int) {
        if (frameLen < 50) return

        val numTemp = buf.u8(8)
        val numCells = buf.u8(9)

        // Cell voltages start at offset 34, 2 bytes each (LE, millivolts)
        val cells = mutableListOf<Float>()
        for (i in 0 until numCells) {
            val off = 34 + i * 2
            if (off + 1 >= frameLen) break
            val mv = buf.u16LE(off)
            if (mv in 1..5000) cells.add(mv / 1000f)
        }

        var pos = 34 + numCells * 2

        // Temperatures: u16 LE each, 65496 = NaN. ANT supports an arbitrary
        // sensor count declared in byte 8; we read all of them.
        val temps = mutableListOf<Float>()
        for (i in 0 until numTemp) {
            if (pos + 1 >= frameLen) break
            val raw = buf.u16LE(pos)
            pos += 2
            if (raw != 65496) {
                // ANT BMS raw u16 values are degrees Celsius directly.
                val celsius = raw.toFloat()
                if (celsius in -40f..150f) temps.add(celsius)
            }
        }

        // MOS temperature
        if (pos + 1 < frameLen) {
            val mosTemp = buf.u16LE(pos)
            pos += 2
            if (mosTemp != 65496) {
                val celsius = mosTemp.toFloat()
                if (celsius in -40f..150f) temps.add(celsius)
            }
        }

        pos += 2 // Skip balancer temp

        // Total voltage: u16 LE * 0.01
        val voltage = if (pos + 1 < frameLen) buf.u16LE(pos) * 0.01f else 0f
        pos += 2

        // Current: i16 LE * 0.1
        // ANT native sign convention is opposite to ours: their +current means discharge.
        // Negate so that our domain model's '+ = charging' convention holds.
        val current = if (pos + 1 < frameLen) -(buf.i16LE(pos) * 0.1f) else 0f
        pos += 2

        // SOC: u16 LE
        val soc = if (pos + 1 < frameLen) buf.u16LE(pos).toFloat() else 0f
        pos += 2

        pos += 2 // Skip SOH

        // ANT MOS state bytes are u8 enums (NOT booleans). Values 0=Off, 1=On,
        // 2..N = protection/fault reasons. Decoded from the syssi esphome-ant-bms
        // reference (components/ant_bms_ble/ant_bms_ble.cpp). The official ANT
        // app's ProtectInfo / WarningInfo strings (UnitOverVProtect, BatteryFull,
        // ChargeMosOn, ...) are projections of these enums, not separate
        // bitmasks — confirmed by checking batmon-ha, juamiso, syssi sources.
        val dischargeStatus = if (pos < frameLen) buf.u8(pos) else 0
        pos += 1
        val chargeStatus = if (pos < frameLen) buf.u8(pos) else 0
        pos += 1

        pos += 2 // Skip balancer state + reserved

        // Capacity: u32 LE * 0.000001 (Ah)
        val capacity = if (pos + 3 < frameLen) buf.u32LE(pos) * 0.000001f else 0f
        pos += 4
        // Remaining charge: u32 LE * 0.000001 (Ah)
        val charge = if (pos + 3 < frameLen) buf.u32LE(pos) * 0.000001f else 0f
        pos += 4

        val chargeEnabled = chargeStatus == 1
        val dischargeEnabled = dischargeStatus == 1

        // Emit a fault entry whenever the MOS status enum says anything other
        // than Off (0) or On (1). Each non-trivial enum value is a protection
        // or anomaly reason worth surfacing to the user.
        val faults = buildList {
            chargeMosFaultName(chargeStatus)?.let { add("charge: $it") }
            dischargeMosFaultName(dischargeStatus)?.let { add("discharge: $it") }
        }

        lastData = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
            soc = soc,
            charge = charge,
            capacity = capacity,
            cellVoltages = cells,
            temperatures = temps,
            chargeEnabled = chargeEnabled,
            dischargeEnabled = dischargeEnabled,
            bmsFaults = faults,
            isConnected = true
        )
    }

    /** Charge MOS status enum (21 values). null = state is uninteresting (Off/On). */
    private fun chargeMosFaultName(code: Int): String? = when (code) {
        0, 1 -> null
        2 -> "overcharge"
        3 -> "OC protect"
        4 -> "battery full"
        5 -> "pack OV"
        6 -> "pack OT"
        7 -> "MOS OT"
        8 -> "current anomaly"
        9 -> "balance wire dropped"
        10 -> "PCB OT"
        12 -> "open failed"
        13 -> "MOS abnormal"
        14 -> null // "waiting" — transient, not a fault
        15 -> "manually off"
        16 -> "2-level OV"
        17 -> "low temp protect"
        18 -> "V diff exceeded"
        20 -> "self-test error"
        else -> null
    }

    /** Discharge MOS status enum (20 values). null = state is uninteresting (Off/On). */
    private fun dischargeMosFaultName(code: Int): String? = when (code) {
        0, 1 -> null
        2 -> "overdischarge"
        3 -> "OC protect"
        4 -> "2A exceeded"
        5 -> "pack UV"
        6 -> "pack OT"
        7 -> "MOS OT"
        8 -> "current anomaly"
        9 -> "balance wire dropped"
        10 -> "PCB OT"
        11 -> null // "charge MOS on" — informational
        12 -> "short circuit"
        13 -> "MOS abnormal"
        14 -> "open failed"
        15 -> "manually off"
        16 -> "2-level UV"
        17 -> "low temp protect"
        18 -> "V diff exceeded"
        19 -> "self-test error"
        else -> null
    }

    companion object {
        private const val FUNC_STATUS = 0x01
    }
}
