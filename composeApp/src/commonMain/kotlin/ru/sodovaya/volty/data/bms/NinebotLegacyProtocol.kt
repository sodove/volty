package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource

/**
 * Read-only decoder for the legacy Ninebot/Segway EUC link used by WheelLog
 * for the original One E+/S2/C family.
 *
 * Its BLE transport is the Nordic-style `0000ffe0` service with notifications
 * and writes on `0000ffe1`, and its wire frame starts `55 AA`.  The legacy
 * frame body is `length source destination parameter payload checksumLE`,
 * where `length = payload.size + 2` and the checksum is the 16-bit complement
 * of the unsigned sum from `length` through the payload.  This is the shape
 * emitted and verified by WheelLog's `NinebotAdapter.CANMessage` and
 * `NinebotUnpacker`; it is not the newer `5A A5` Protocol-2 frame handled by
 * [NinebotProtocol].
 *
 * The adapter is deliberately passive: legacy wheels normally require a
 * request before they answer, but this class never writes to FFE1.  A caller
 * that has already captured a plaintext response may feed it to
 * [onNotification].  Only the proven `parameter = 0xB0` live page is decoded;
 * its 32-byte payload fields are the offsets used by WheelLog for S2:
 * battery percent at 8, lifetime distance (metres) at 14, temperature in
 * centi-degrees at 22, voltage in centi-volts at 24, signed current in
 * centi-amps at 26, and speed in centi-km/h at 28.  Distance and speed are
 * retained only as documented wire evidence here; [BmsData] exposes the
 * battery measurements, not a fabricated controller sample.
 *
 * The Xiaomi/ES register variant of `55 AA` (command/argument framing), the
 * encrypted Ninebot variants, and all write/key-exchange pages are ignored.
 * No key exchange or decryption is attempted, so a checksum-valid encrypted
 * packet cannot accidentally become telemetry unless it also matches the
 * strict B0 page shape and plausibility checks below.
 */
class NinebotLegacyProtocol : BmsProtocol(), MotionSource {

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        // FFE1 is physically writable, but this decoder never uses it.
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    override fun handshakeCommands(): List<ByteArray> = emptyList()

    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L

    private val buffer = ByteArrayAccumulator()
    private var latest: BmsData? = null
    private var latestMotion: ControllerData? = null
    private var tripBaselineMeters: Long? = null

    override val controllerCount: Int get() = 1

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        parseBufferedFrames()
    }

    override fun latestData(packIndex: Int): BmsData? =
        if (packIndex == 0) latest else null

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) latestMotion else null

    override fun reset() {
        buffer.reset()
        latest = null
        latestMotion = null
        tripBaselineMeters = null
    }

    private fun parseBufferedFrames() {
        while (true) {
            val bytes = buffer.toByteArray()
            val start = findHeader(bytes)
            if (start < 0) {
                // Retain a possible first 55 from a split 55 AA header.
                if (bytes.size > 1) buffer.trimLeading(bytes.size - 1)
                return
            }
            if (start > 0) buffer.trimLeading(start)

            val current = buffer.toByteArray()
            if (current.size < MIN_FRAME_SIZE) return
            val bodyLength = current[LENGTH_OFFSET].u8()
            if (bodyLength !in MIN_BODY_LENGTH..MAX_BODY_LENGTH) {
                // A bogus length must not pin the accumulator indefinitely.
                buffer.trimLeading(1)
                continue
            }

            val frameLength = bodyLength + FRAME_OVERHEAD
            if (current.size < frameLength) return
            if (!isChecksumValid(current, frameLength)) {
                // A valid 55 AA sequence may occur inside a corrupt candidate;
                // advance one byte so it can be considered on the next pass.
                buffer.trimLeading(1)
                continue
            }

            val payloadLength = bodyLength - 2
            val parameter = current[PARAMETER_OFFSET].u8()
            val payload = current.copyOfRange(
                PAYLOAD_OFFSET,
                PAYLOAD_OFFSET + payloadLength
            )
            if (parameter == PARAM_LIVE_DATA && payloadLength == LIVE_PAYLOAD_SIZE) {
                parseLivePage(payload)
            }
            buffer.trimLeading(frameLength)
        }
    }

    private fun parseLivePage(payload: ByteArray) {
        val batteryPercent = payload.u16Le(BATTERY_OFFSET)
        val distanceMeters = payload.u32Le(DISTANCE_OFFSET)
        val speedKmh = payload.u16Le(SPEED_OFFSET) / 100f
        val voltage = payload.u16Le(VOLTAGE_OFFSET) / 100f
        val current = payload.i16Le(CURRENT_OFFSET) / 100f
        val temperature = payload.u16Le(TEMPERATURE_OFFSET) / 100f

        // These guards reject truncated/random/encrypted pages while keeping
        // the ranges broad enough for every legacy EUC supported by WheelLog.
        if (batteryPercent !in 0..100) return
        if (voltage !in MIN_VOLTAGE_V..MAX_VOLTAGE_V) return
        if (current !in MIN_CURRENT_A..MAX_CURRENT_A) return
        if (temperature !in MIN_TEMPERATURE_C..MAX_TEMPERATURE_C) return

        latest = BmsData(
            voltage = voltage,
            current = current,
            hasCurrent = true,
            power = voltage * current,
            hasPower = true,
            soc = batteryPercent.toFloat(),
            socKnown = true,
            temperatures = listOf(temperature),
            isConnected = true
        )

        val baseline = tripBaselineMeters ?: distanceMeters.also { tripBaselineMeters = it }
        val tripKm = if (distanceMeters >= baseline) {
            (distanceMeters - baseline) / 1000f
        } else {
            // A reboot/reset can move a lifetime counter backwards; do not
            // turn that into a negative session distance.
            tripBaselineMeters = distanceMeters
            0f
        }
        latestMotion = ControllerData(
            speedKmh = speedKmh,
            speedSource = SpeedSource.REPORTED,
            hasDuty = false,
            batteryCurrentA = current,
            hasBatteryCurrent = true,
            inputVoltageV = voltage,
            hasInputVoltage = true,
            powerW = voltage * current,
            hasPower = true,
            escTempC = temperature,
            hasMotorTemp = false,
            odometerKm = distanceMeters / 1000f,
            tripKm = tripKm,
            hasDistance = true,
            hasEnergyCounters = false,
            isConnected = true
        )
    }

    private fun findHeader(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if (bytes[index].u8() == HEADER_1 && bytes[index + 1].u8() == HEADER_2) {
                return index
            }
        }
        return -1
    }

    private fun isChecksumValid(frame: ByteArray, frameLength: Int): Boolean {
        val expected = frame.u16Le(frameLength - CHECKSUM_SIZE)
        var sum = 0
        for (index in LENGTH_OFFSET until frameLength - CHECKSUM_SIZE) {
            sum = (sum + frame[index].u8()) and 0xffff
        }
        return expected == (sum.inv() and 0xffff)
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private fun ByteArray.u16Le(offset: Int): Int =
        this[offset].u8() or (this[offset + 1].u8() shl 8)

    private fun ByteArray.i16Le(offset: Int): Int {
        val unsigned = u16Le(offset)
        return if (unsigned and 0x8000 != 0) unsigned - 0x10000 else unsigned
    }

    private fun ByteArray.u32Le(offset: Int): Long =
        this[offset].u8().toLong() or
            (this[offset + 1].u8().toLong() shl 8) or
            (this[offset + 2].u8().toLong() shl 16) or
            (this[offset + 3].u8().toLong() shl 24)

    private companion object {
        const val HEADER_1 = 0x55
        const val HEADER_2 = 0xAA

        const val LENGTH_OFFSET = 2
        const val PARAMETER_OFFSET = 5
        const val PAYLOAD_OFFSET = 6
        const val CHECKSUM_SIZE = 2

        // Header (2) + body (length) + checksum (2).
        const val FRAME_OVERHEAD = 6
        const val MIN_BODY_LENGTH = 2
        const val MAX_BODY_LENGTH = 64
        const val MIN_FRAME_SIZE = FRAME_OVERHEAD + MIN_BODY_LENGTH

        const val PARAM_LIVE_DATA = 0xB0
        const val LIVE_PAYLOAD_SIZE = 32
        const val BATTERY_OFFSET = 8
        const val DISTANCE_OFFSET = 14
        const val TEMPERATURE_OFFSET = 22
        const val VOLTAGE_OFFSET = 24
        const val CURRENT_OFFSET = 26
        const val SPEED_OFFSET = 28

        const val MIN_VOLTAGE_V = 10f
        const val MAX_VOLTAGE_V = 200f
        const val MIN_CURRENT_A = -200f
        const val MAX_CURRENT_A = 200f
        const val MIN_TEMPERATURE_C = -40f
        const val MAX_TEMPERATURE_C = 150f
    }
}
