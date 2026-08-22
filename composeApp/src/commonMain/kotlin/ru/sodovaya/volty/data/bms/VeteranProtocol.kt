package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs

/**
 * Passive Veteran/Leaperkim telemetry (Sherman, Abrams, Sherman S, Patton,
 * Lynx, Sherman L, Patton S, Oryx and Lynx S).
 *
 * The implementation follows the byte stream in WheelLog's
 * `VeteranAdapter.java` and the independent Leaperkim decoder in
 * Tritbool/euc_ble_library.  The only framing variant implemented here is the
 * one those sources agree on:
 *
 * `DC 5A 5C length payload [CRC32]`
 *
 * `length + 4` is the complete frame size (three-byte preamble, length byte,
 * and the length bytes themselves).  Frames with `length > 38` carry a
 * big-endian CRC32 over bytes `0 until length`; older frames have no checksum.
 * No byte-escaping scheme is described by either source; header-like bytes in
 * the payload are therefore ordinary data and are ignored until the declared
 * frame length has been consumed.
 * Notifications may split or concatenate frames, and arbitrary bytes before a
 * preamble are discarded.  Veteran's FFE1 characteristic is also its command
 * channel, so this adapter intentionally never writes to it.
 *
 * Limitations are deliberate.  The controller's current field is phase
 * current, not battery current; it therefore reaches [ControllerData]'s
 * `motorCurrentA` only.  Battery current and power remain unavailable until a
 * smart-BMS page supplies them.  Smart-BMS pages carry cell voltages,
 * temperatures and current for two packs. A reported page-2 SoC is retained
 * when present; otherwise pack SoC stays unknown. Controller-only SoC is the
 * model voltage curve used by WheelLog, and is marked known only for the model
 * revisions listed above. The Begode
 * `Master` is intentionally not classified here: the available sources do not
 * prove that its BLE frames use this Veteran layout.
 *
 * Sources checked 2026-08-11:
 * - https://github.com/Wheellog/Wheellog.Android/blob/master/app/src/main/java/com/cooper/wheellog/utils/VeteranAdapter.java
 * - https://github.com/Tritbool/euc_ble_library/blob/main/euc-ble-core/src/main/kotlin/io/github/tritbool/euc/ble/protocols/LeaperkimProtocol.kt
 */
class VeteranProtocol : BmsProtocol(), MotionSource {

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    /** FFE1 is the wheel command channel; never write from telemetry setup. */
    override fun handshakeCommands(): List<ByteArray> = emptyList()

    /** Veteran wheels stream unsolicited frames; no polling write is safe here. */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L

    /** Lynx-family wheels multiplex two smart BMS packs on one BLE link. */
    override val packCount: Int get() = 2

    private val buffer = ByteArrayAccumulator()
    private val bms = Array(2) { BmsState() }
    private var mainData: BmsData? = null
    private var motion: ControllerData? = null
    private var smartBmsSeen = false
    private var majorVersion: Int? = null
    private var versionRaw: Int = 0
    private var hardwareCodeValue: String? = null
    private var reportedSoc: Float? = null
    private var tripBaselineMeters: Long? = null

    /** Model name derived from the firmware revision, or generic before a frame. */
    val model: String get() = modelName(majorVersion)

    /** Firmware revision as WheelLog formats it (`MMM.m.pp`). */
    val firmwareVersion: String?
        get() = versionRaw.takeIf { it > 0 }?.let(::formatVersion)

    /** Four-digit hardware profile decoded from the 24-bit version field. */
    val hardwareCode: String? get() = hardwareCodeValue

    override fun onNotification(data: ByteArray) {
        if (data.isEmpty()) return
        buffer.append(data)
        parseAvailable()
    }

    override fun latestData(packIndex: Int): BmsData? {
        if (packIndex !in bms.indices) return null
        if (!smartBmsSeen) return if (packIndex == 0) mainData else null
        val expected = bms[packIndex].reportedCellCount ?: expectedCellCount(majorVersion, hardwareCodeValue) ?: return null
        return bms[packIndex].toData(expected, reportedSoc)
    }

    override val controllerCount: Int get() = 1

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun reset() {
        buffer.reset()
        bms.forEach(BmsState::reset)
        mainData = null
        motion = null
        smartBmsSeen = false
        majorVersion = null
        versionRaw = 0
        hardwareCodeValue = null
        reportedSoc = null
        tripBaselineMeters = null
    }

    private fun parseAvailable() {
        while (true) {
            val bytes = buffer.toByteArray()
            val start = findHeader(bytes)
            if (start < 0) {
                // Keep a possible split prefix (`DC` or `DC 5A`).
                if (bytes.size > HEADER_SIZE - 1) buffer.trimLeading(bytes.size - (HEADER_SIZE - 1))
                return
            }
            if (start > 0) buffer.trimLeading(start)

            val candidate = buffer.toByteArray()
            if (candidate.size < LENGTH_HEADER_SIZE) return
            val length = candidate[3].u8()
            if (length !in MIN_LENGTH..MAX_LENGTH) {
                // A false preamble in noise must not consume a following one.
                buffer.trimLeading(1)
                continue
            }
            val frameSize = length + FRAME_SIZE_ADJUSTMENT
            if (candidate.size < frameSize) return

            val frame = candidate.copyOf(frameSize)
            val hasCrc = length > CRC_THRESHOLD
            if (!hasValidShape(frame) || (hasCrc && !hasValidCrc(frame, length))) {
                buffer.trimLeading(1)
                continue
            }

            // All offsets below describe the payload. A CRC-valid frame's last
            // four bytes are not telemetry, even when a short model happens to
            // put an otherwise plausible field offset inside that trailer.
            parseFrame(if (hasCrc) frame.copyOf(length) else frame)
            buffer.trimLeading(frameSize)
        }
    }

    private fun findHeader(bytes: ByteArray): Int {
        for (i in 0..bytes.size - HEADER_SIZE) {
            if (bytes[i].u8() == 0xDC && bytes[i + 1].u8() == 0x5A && bytes[i + 2].u8() == 0x5C) {
                return i
            }
        }
        return -1
    }

    private fun hasValidShape(frame: ByteArray): Boolean {
        if (frame.size < BASE_FRAME_SIZE) return false
        // These high bytes are stable markers checked by WheelLog's unpacker.
        if (frame[22].u8() != 0) return false
        if (frame[23].u8() and 0xFE != 0) return false
        if (frame[30].u8() != 0 && frame[30].u8() != 7) return false
        return true
    }

    private fun hasValidCrc(frame: ByteArray, length: Int): Boolean {
        if (frame.size < length + CRC_SIZE) return false
        val provided = frame.u32BE(length)
        return crc32(frame, length) == provided
    }

    private fun parseFrame(frame: ByteArray) {
        val rawVersion = frame.versionAt(28)
        if (rawVersion != null && rawVersion > 0) {
            versionRaw = rawVersion
            hardwareCodeValue = rawVersion.toString().padStart(6, '0').take(4)
            majorVersion = if (rawVersion >= 100_000) rawVersion / 100_000 else rawVersion / 1000
        }

        val major = majorVersion ?: return
        if (major >= SMART_BMS_MIN_MAJOR && frame.size > BMS_PACKET_OFFSET) {
            parseSmartBms(frame)
        }
        parseController(frame, major)
    }

    private fun parseController(frame: ByteArray, major: Int) {
        val voltage = frame.u16BE(4) / 100f
        val speedRaw = frame.i16BE(6)
        val speed = abs(speedRaw / SPEED_RAW_PER_KMH)
        val totalDistanceMeters = frame.reverseBeInt(12)
        val phaseCurrent = frame.i16BE(16) / CURRENT_RAW_PER_AMP
        val temperature = frame.i16BE(18) / TEMPERATURE_RAW_PER_C
        if (voltage !in 20f..200f || speed !in 0f..120f || temperature !in -50f..130f) return

        val soc = reportedSoc ?: estimateSoc(voltage * 100f, major)
        mainData = BmsData(
            voltage = voltage,
            // The controller field is phase current, not battery current.
            current = 0f,
            hasCurrent = false,
            power = 0f,
            hasPower = false,
            soc = soc ?: 0f,
            socKnown = soc != null,
            temperatures = listOf(temperature),
            isConnected = true
        )

        val baseline = tripBaselineMeters ?: totalDistanceMeters.also { tripBaselineMeters = it }
        val tripKm = if (totalDistanceMeters >= baseline) {
            (totalDistanceMeters - baseline) / METERS_PER_KM
        } else {
            // Counter reset/reboot: begin a fresh connection baseline.
            tripBaselineMeters = totalDistanceMeters
            0f
        }
        val dutyKnown = major >= HARDWARE_PWM_MIN_MAJOR
        val duty = if (dutyKnown) (frame.u16BE(34) / DUTY_RAW_PER_PERCENT).coerceIn(0f, 100f) else 0f
        motion = ControllerData(
            speedKmh = speed,
            speedSource = SpeedSource.REPORTED,
            dutyPercent = duty,
            hasDuty = dutyKnown,
            motorCurrentA = phaseCurrent,
            batteryCurrentA = 0f,
            hasBatteryCurrent = false,
            inputVoltageV = voltage,
            hasInputVoltage = true,
            powerW = 0f,
            hasPower = false,
            escTempC = temperature,
            motorTempC = 0f,
            hasMotorTemp = false,
            odometerKm = totalDistanceMeters / METERS_PER_KM,
            tripKm = tripKm,
            hasDistance = true,
            hasEnergyCounters = false,
            isConnected = true
        )
    }

    private fun parseSmartBms(frame: ByteArray) {
        val packet = frame[BMS_PACKET_OFFSET].u8()
        if (packet !in 0..7) return
        val bmsIndex = if (packet < 4) 0 else 1
        val state = bms[bmsIndex]
        if (packet in SMART_BMS_PAGES) smartBmsSeen = true

        if (packet == 2 && frame.size > REPORTED_SOC_OFFSET) {
            val percent = frame[REPORTED_SOC_OFFSET].u8()
            if (percent != UNREPORTED && percent <= 100) {
                reportedSoc = percent / PERCENT_PER_UNIT
            }
        }

        when (packet) {
            0, 4 -> {
                if (frame.size <= BMS_CURRENT_2_OFFSET + 1) return
                // WheelLog stores these as signed hundredths of an ampere.
                val current = if (bmsIndex == 0) frame.i16BE(BMS_CURRENT_1_OFFSET) else frame.i16BE(BMS_CURRENT_2_OFFSET)
                state.currentA = current / BMS_CURRENT_RAW_PER_AMP
            }

            1, 5 -> {
                if (frame.size > REPORTED_CELL_COUNT_OFFSET) {
                    val count = frame[REPORTED_CELL_COUNT_OFFSET].u8()
                    if (count in 1..MAX_BMS_CELLS) state.reportedCellCount = count
                }
                parseCells(frame, state, firstCell = 0, offset = 53, count = 15)
            }
            2, 6 -> parseCells(frame, state, firstCell = 15, offset = 53, count = 15)
            3, 7 -> {
                parseCells(frame, state, firstCell = 30, offset = 59, count = 12)
                state.temperatures.clear()
                repeat(6) { i ->
                    val offset = 47 + i * 2
                    if (offset + 1 >= frame.size) return@repeat
                    val temp = frame.i16BE(offset) / TEMPERATURE_RAW_PER_C
                    if (temp in -50f..150f) state.temperatures += temp
                }
            }
        }
    }

    private fun parseCells(frame: ByteArray, state: BmsState, firstCell: Int, offset: Int, count: Int) {
        repeat(count) { i ->
            val index = firstCell + i
            if (index !in state.cells.indices || offset + i * 2 + 1 >= frame.size) return@repeat
            val value = frame.u16BE(offset + i * 2)
            if (value in MIN_CELL_MV..MAX_CELL_MV) state.cells[index] = value / MILLIVOLTS_PER_VOLT
        }
    }

    private class BmsState {
        val cells = arrayOfNulls<Float>(MAX_BMS_CELLS)
        var currentA: Float? = null
        var reportedCellCount: Int? = null
        val temperatures = mutableListOf<Float>()

        fun reset() {
            cells.fill(null)
            currentA = null
            reportedCellCount = null
            temperatures.clear()
        }

        fun toData(expectedCells: Int, reportedSoc: Float?): BmsData? {
            if (cells.take(expectedCells).any { it == null }) return null
            val completeCells = cells.take(expectedCells).map { it!! }
            return BmsData(
                // No pack-voltage field exists in these pages. A sum is only
                // published after every expected cell has arrived.
                voltage = completeCells.sum(),
                current = currentA ?: 0f,
                hasCurrent = currentA != null,
                power = 0f,
                hasPower = false,
                soc = reportedSoc ?: 0f,
                socKnown = reportedSoc != null,
                cellVoltages = completeCells,
                temperatures = temperatures.toList(),
                isConnected = true
            )
        }
    }

    private fun estimateSoc(voltageRaw: Float, major: Int): Float? {
        val percent = when (major) {
            0, 1, 2, 3 -> when {
                voltageRaw > 10_020f -> 100f
                voltageRaw > 8_160f -> (voltageRaw - 8_070f) / 19.5f
                voltageRaw > 7_935f -> (voltageRaw - 7_935f) / 48.75f
                else -> 0f
            }

            4, 7 -> when {
                voltageRaw > 12_525f -> 100f
                voltageRaw > 10_200f -> (voltageRaw - 9_975f) / 25.5f
                voltageRaw > 9_600f -> (voltageRaw - 9_600f) / 67.5f
                else -> 0f
            }

            5, 6, 9 -> when {
                voltageRaw > 15_030f -> 100f
                voltageRaw > 12_240f -> (voltageRaw - 11_970f) / 30.6f
                voltageRaw > 11_520f -> (voltageRaw - 11_520f) / 81f
                else -> 0f
            }

            8 -> when {
                voltageRaw > 17_535f -> 100f
                voltageRaw > 14_280f -> (voltageRaw - 14_123f) / 34.125f
                voltageRaw > 13_886f -> (voltageRaw - 13_886f) / 85.3125f
                else -> 0f
            }

            else -> return null
        }
        return (percent / 100f).coerceIn(0f, 1f)
    }

    private fun modelName(major: Int?): String = when (hardwareCodeValue) {
        "0040" -> "Patton"
        "0050" -> "Lynx"
        "0060" -> "Sherman L"
        "0070" -> "Patton S"
        "0080" -> "Oryx"
        "0090" -> "Lynx S"
        "5010" -> "Nosfet Apex"
        else -> when (major) {
            0, 1 -> "Sherman"
            2 -> "Abrams"
            3 -> "Sherman S"
            4 -> "Patton"
            5 -> "Lynx"
            6 -> "Sherman L"
            7 -> "Patton S"
            8 -> "Oryx"
            9 -> "Lynx S"
            else -> "Leaperkim"
        }
    }

    private fun expectedCellCount(major: Int?, hardwareCode: String?): Int? = when (hardwareCode) {
        "0040" -> 30
        "0050", "0060", "0090", "5010", "5030" -> 36
        else -> when (major) {
            4, 7 -> 30
            5, 6, 9 -> 36
            8 -> 42
            else -> null
        }
    }

    private fun formatVersion(raw: Int): String =
        "%03d.%01d.%02d".format(raw / 1000, (raw % 1000) / 100, raw % 100)

    private fun crc32(frame: ByteArray, endExclusive: Int): Long {
        var crc = 0xFFFF_FFFFL
        for (i in 0 until endExclusive) {
            crc = crc xor frame[i].u8().toLong()
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor CRC32_POLYNOMIAL else crc ushr 1
            }
        }
        return crc.inv() and 0xFFFF_FFFFL
    }

    private fun ByteArray.reverseBeInt(offset: Int): Long =
        (u16BE(offset).toLong() or (u16BE(offset + 2).toLong() shl 16)) and 0xFFFF_FFFFL

    private fun ByteArray.versionAt(offset: Int): Int? {
        if (offset + 2 >= size) return null
        return (this[offset + 2].u8() shl 16) or
            (this[offset].u8() shl 8) or
            this[offset + 1].u8()
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private companion object {
        const val HEADER_SIZE = 3
        const val LENGTH_HEADER_SIZE = 4
        const val FRAME_SIZE_ADJUSTMENT = 4
        const val BASE_FRAME_SIZE = 36
        const val MIN_LENGTH = BASE_FRAME_SIZE - FRAME_SIZE_ADJUSTMENT
        const val MAX_LENGTH = 240
        const val CRC_THRESHOLD = 38
        const val CRC_SIZE = 4
        const val CRC32_POLYNOMIAL = 0xEDB88320L
        const val BMS_PACKET_OFFSET = 46
        const val BMS_CURRENT_1_OFFSET = 69
        const val BMS_CURRENT_2_OFFSET = 71
        const val SMART_BMS_MIN_MAJOR = 5
        val SMART_BMS_PAGES = setOf(1, 2, 3, 5, 6, 7)
        const val REPORTED_SOC_OFFSET = 50
        const val REPORTED_CELL_COUNT_OFFSET = 52
        const val UNREPORTED = 0x80
        const val PERCENT_PER_UNIT = 100f
        const val HARDWARE_PWM_MIN_MAJOR = 2
        const val MAX_BMS_CELLS = 42
        const val MIN_CELL_MV = 1_500
        const val MAX_CELL_MV = 5_000
        const val MILLIVOLTS_PER_VOLT = 1_000f
        const val SPEED_RAW_PER_KMH = 10f
        const val CURRENT_RAW_PER_AMP = 10f
        const val BMS_CURRENT_RAW_PER_AMP = 100f
        const val TEMPERATURE_RAW_PER_C = 100f
        const val DUTY_RAW_PER_PERCENT = 100f
        const val METERS_PER_KM = 1_000f
    }
}
