package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs

/**
 * Read-only decoder for the unencrypted Ninebot One Z / Z-series link.
 *
 * The legacy Ninebot frame is `5A A5 length source destination command index
 * payload checksumLE`. `length` counts payload bytes only and the checksum is
 * the inverted unsigned-byte sum of everything from `length` through the end
 * of the payload. This is the Protocol-2 frame documented by
 * NootNooot/segway-ninebot-ble and the frame verified by WheelLog's
 * `NinebotZAdapter.CANMessage`.
 *
 * Only BMS read evidence is emitted. BMS life (`index = 0x30`) fields are
 * documented in the Ninebot ES Communication Protocol: percentage at offset
 * 4 (0..100), current at 6 (10 mA), voltage at 8 (10 mV), and two temperature
 * bytes at 10/11 (0..119 maps to -20..99 °C). Cell registers (`0x40..0x4F`)
 * are sixteen millivolt values. The BMS status bits used for charge direction
 * are documented in that same protocol (0x20 = discharging, 0x40 = charging).
 *
 * The class deliberately sends no BLE writes. Current Segway firmware often
 * uses Encryption2/3 (AES-CTR); without the authentication key those frames
 * cannot be decoded, and this decoder drops them instead of guessing values.
 * A caller that has already obtained read responses may feed their plaintext
 * notifications through [onNotification].
 *
 * Sources:
 *  - WheelLog/Wheellog.Android `NinebotZAdapter.java` (frame parser and BMS
 *    offsets/scales), GPL-3.0.
 *  - ScooterHacking, *Ninebot ES Communication Protocol*, pp. 14-15 and 17
 *    (BMS register units, status bits, inverted-sum checksum).
 *  - NootNooot/segway-ninebot-ble, “Frame Formats” and “BLE Transport”
 *    (Protocol-2 framing, Nordic UART UUIDs, and fragmentation).
 */
class NinebotProtocol : BmsProtocol(), MotionSource {

    override val uuids = BmsUuids(
        serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        notifyCharUuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
        writeCharUuid = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    )

    /** Authentication and register reads are intentionally not issued here. */
    override fun handshakeCommands(): List<ByteArray> = emptyList()

    /** No writes: notifications are decoded only when another layer supplied them. */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L

    /** The One Z can expose two independent smart BMS boards. */
    override val packCount: Int get() = 2

    private val buffer = ByteArrayAccumulator()
    private val packs = Array(2) { PackState() }
    private var motion: ControllerData? = null

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        parseFrames()
    }

    override fun latestData(packIndex: Int): BmsData? =
        packs.getOrNull(packIndex)?.snapshot()

    override val controllerCount: Int get() = 1

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun reset() {
        buffer.reset()
        packs.forEach(PackState::clear)
        motion = null
    }

    private fun parseFrames() {
        while (true) {
            val bytes = buffer.toByteArray()
            val start = findHeader(bytes)
            if (start < 0) {
                // Keep a possible first sync byte for the next notification.
                if (bytes.size > 1) buffer.trimLeading(bytes.size - 1)
                return
            }
            if (start > 0) buffer.trimLeading(start)

            val current = buffer.toByteArray()
            if (current.size < HEADER_SIZE + 1) return
            val payloadLength = current[2].u8()
            if (payloadLength > MAX_PAYLOAD) {
                buffer.trimLeading(2)
                continue
            }
            val frameLength = payloadLength + FRAME_OVERHEAD
            if (current.size < frameLength) return

            if (!isChecksumValid(current, frameLength)) {
                // A sync sequence can legally occur inside payload; advance by
                // one byte so a valid nested frame is not skipped.
                buffer.trimLeading(1)
                continue
            }

            val source = current[3].u8()
            val destination = current[4].u8()
            val command = current[5].u8()
            val parameter = current[6].u8()
            val payload = current.copyOfRange(7, 7 + payloadLength)
            parsePayload(source, destination, command, parameter, payload)
            buffer.trimLeading(frameLength)
        }
    }

    private fun parsePayload(
        source: Int,
        destination: Int,
        command: Int,
        parameter: Int,
        payload: ByteArray
    ) {
        // 0x04 is the read-ack used by WheelLog. Accept 0x01 as well for
        // captures that contain a device's unencrypted read response verbatim.
        if (command != CMD_READ_ACK && command != CMD_READ) return

        // Replies originate from BMS1/BMS2 and are addressed to the app (0x3E).
        // Some firmware revisions leave destination at zero, so only source is
        // used to select a pack.
        if (destination != APP && destination != 0) return
        when (source) {
            BMS1, BMS2 -> {
                val state = packs[if (source == BMS1) 0 else 1]
                when (parameter) {
                    REG_BMS_LIFE -> parseLife(state, payload)
                    REG_BMS_CELLS -> parseCells(state, payload)
                }
            }
            CONTROLLER -> if (parameter == REG_LIVE_DATA) parseLive(payload)
        }
    }

    /** Controller live page offsets are documented by WheelLog's NinebotZ adapter. */
    private fun parseLive(data: ByteArray) {
        if (data.size < LIVE_MIN_SIZE) return
        val voltage = data.u16Le(24) / 100f
        if (voltage !in 20f..100f) return
        val speed = abs(data.i16Le(10) / 100f)
        val distanceKm = data.u32Le(14) / 1000f
        val boardTemperature = data.i16Le(22) / 10f
        if (boardTemperature !in -40f..120f) return
        motion = ControllerData(
            speedKmh = speed,
            speedSource = SpeedSource.REPORTED,
            dutyPercent = 0f,
            hasDuty = false,
            batteryCurrentA = 0f,
            hasBatteryCurrent = false,
            inputVoltageV = voltage,
            hasInputVoltage = true,
            powerW = 0f,
            hasPower = false,
            escTempC = boardTemperature,
            hasMotorTemp = false,
            odometerKm = distanceKm,
            tripKm = 0f,
            hasDistance = true,
            hasEnergyCounters = false,
            isConnected = true
        )
    }

    private fun parseLife(state: PackState, data: ByteArray) {
        if (data.size < LIFE_MIN_SIZE) return
        val status = data.u16Le(0)
        val percentage = data.u16Le(4)
        val rawCurrent = data.i16Le(6) / 100f
        val voltage = data.u16Le(8) / 100f

        // The wire's sign has no published charge/discharge convention. The
        // status bits are the only evidence that can safely orient it for the
        // shared BmsData (+ = charging) convention. If neither bit is set,
        // preserve the signed wire value rather than inventing a direction.
        val current = when {
            status and STATUS_CHARGING != 0 && status and STATUS_DISCHARGING == 0 ->
                kotlin.math.abs(rawCurrent)
            status and STATUS_DISCHARGING != 0 && status and STATUS_CHARGING == 0 ->
                -kotlin.math.abs(rawCurrent)
            else -> rawCurrent
        }

        state.status = status
        state.voltage = voltage
        state.current = current
        state.soc = percentage.toFloat().takeIf { it in 0f..100f }
        state.temperatures = buildList {
            data[10].u8().temperatureOrNull()?.let(::add)
            data[11].u8().temperatureOrNull()?.let(::add)
        }
        state.lifeSeen = true
    }

    private fun parseCells(state: PackState, data: ByteArray) {
        val count = minOf(data.size / 2, MAX_CELLS)
        state.cells = buildList {
            for (index in 0 until count) {
                val millivolts = data.u16Le(index * 2)
                // Zero is the documented unused tail for 14/15-cell packs.
                if (millivolts !in 1..MAX_CELL_MV) continue
                add(millivolts / 1000f)
            }
        }
    }

    private fun findHeader(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if (bytes[index].u8() == SYNC_1 && bytes[index + 1].u8() == SYNC_2) return index
        }
        return -1
    }

    private fun isChecksumValid(frame: ByteArray, frameLength: Int): Boolean {
        val expectedWire = frame.u16Le(frameLength - 2)
        var sum = 0
        for (index in 2 until frameLength - 2) {
            sum = (sum + frame[index].u8()) and 0xffff
        }
        val inverted16 = sum.inv() and 0xffff
        // WheelLog uses the full 16-bit complement; the published Protocol-2
        // document masks the checksum to 15 bits. Accept both encodings.
        return expectedWire == inverted16 || expectedWire == (inverted16 and 0x7fff)
    }

    private class PackState {
        var lifeSeen = false
        var status = 0
        var voltage = 0f
        var current = 0f
        var soc: Float? = null
        var temperatures: List<Float> = emptyList()
        var cells: List<Float> = emptyList()

        fun snapshot(): BmsData? {
            if (!lifeSeen) return null
            val socValue = soc ?: 0f
            return BmsData(
                voltage = voltage,
                current = current,
                power = voltage * current,
                soc = socValue,
                socKnown = soc != null,
                cellVoltages = cells,
                temperatures = temperatures,
                chargeEnabled = status and STATUS_CHARGING != 0,
                dischargeEnabled = status and STATUS_DISCHARGING != 0,
                isConnected = true
            )
        }

        fun clear() {
            lifeSeen = false
            status = 0
            voltage = 0f
            current = 0f
            soc = null
            temperatures = emptyList()
            cells = emptyList()
        }
    }

    private fun Int.temperatureOrNull(): Float? =
        takeIf { it in 0..119 }?.let { it - 20f }

    private fun Byte.u8(): Int = toInt() and 0xff

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].u8() or (this[offset + 1].u8() shl 8))

    private fun ByteArray.i16Le(offset: Int): Int {
        val value = u16Le(offset)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private fun ByteArray.u32Le(offset: Int): Long =
        u16Le(offset).toLong() or (u16Le(offset + 2).toLong() shl 16)

    private companion object {
        const val SYNC_1 = 0x5A
        const val SYNC_2 = 0xA5
        const val HEADER_SIZE = 2
        const val FRAME_OVERHEAD = 9 // header + length + 4 header fields + CRC
        const val MAX_PAYLOAD = 255
        const val MAX_CELLS = 16
        const val MAX_CELL_MV = 5000
        const val LIFE_MIN_SIZE = 12
        const val CMD_READ = 0x01
        const val CMD_READ_ACK = 0x04
        const val BMS1 = 0x11
        const val BMS2 = 0x12
        const val CONTROLLER = 0x14
        const val APP = 0x3e
        const val REG_BMS_LIFE = 0x30
        const val REG_BMS_CELLS = 0x40
        const val REG_LIVE_DATA = 0xB0
        const val LIVE_MIN_SIZE = 30
        const val STATUS_DISCHARGING = 0x0020
        const val STATUS_CHARGING = 0x0040
    }
}
