package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs

/**
 * Read-only InMotion V2 (V9-family) telemetry.
 *
 * The wire format is the one implemented by WheelLog's `InmotionAdapterV2`
 * and replayed by Tritbool's WheelLog capture tests: `AA AA flags length
 * command payload xor`. `length` includes the command byte and the checksum
 * is XOR of bytes from `flags` through the last payload byte. Notifications
 * are allowed to split or concatenate frames; bytes before a header are noise.
 *
 * This class deliberately emits no handshake or polling writes. WheelLog has
 * read requests for V2, but this app has not yet verified that every target
 * model accepts them safely (and older InMotion variants use another dialect),
 * so passive notification decoding is the only supported operation here.
 *
 * Sources (wire and offsets, checked 2026-08-11):
 * - https://github.com/Wheellog/Wheellog.Android/blob/master/app/src/main/java/com/cooper/wheellog/utils/InmotionAdapterV2.java
 * - https://github.com/Tritbool/euc_ble_library/blob/main/euc-ble-core/src/main/kotlin/io/github/tritbool/euc/ble/protocols/InMotionProtocol.kt
 */
class InMotionProtocol : BmsProtocol(), MotionSource {

    override val uuids = BmsUuids(
        serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        notifyCharUuid = "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
        writeCharUuid = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    )

    /** No writes: this is intentionally a passive, read-only integration hook. */
    override fun handshakeCommands(): List<ByteArray> = emptyList()

    /** No writes: V2 read requests are not yet field-confirmed for this app. */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L

    private val buffer = ByteArrayAccumulator()
    private var wireEscapePending = false
    private var modelId: Int? = null
    private var data: BmsData? = null
    private var motion: ControllerData? = null
    private var tripBaselineMeters: Long? = null
    private var modelValue: String = "InMotion"
    private var serialValue: String? = null
    private var firmwareValue: String? = null

    /** Model/serial/version are optional metadata, useful to the future scanner integration. */
    val model: String get() = modelValue
    val serial: String? get() = serialValue
    val firmware: String? get() = firmwareValue

    override fun onNotification(data: ByteArray) {
        if (data.isEmpty()) return
        appendUnescaped(data)
        parseAvailable()
    }

    override fun latestData(packIndex: Int): BmsData? = if (packIndex == 0) data else null

    override val controllerCount: Int get() = 1

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun reset() {
        buffer.reset()
        wireEscapePending = false
        modelId = null
        data = null
        motion = null
        tripBaselineMeters = null
        modelValue = "InMotion"
        serialValue = null
        firmwareValue = null
    }

    private fun parseAvailable() {
        while (true) {
            val bytes = buffer.toByteArray()
            val start = findHeader(bytes)
            if (start < 0) {
                // Keep a trailing AA: it may be the first byte of a split header.
                if (bytes.size > 1) buffer.trimLeading(bytes.size - 1)
                return
            }
            if (start > 0) buffer.trimLeading(start)

            val current = buffer.toByteArray()
            if (current.size < MIN_FRAME_SIZE) return
            val flags = current[2].toInt() and 0xFF
            val length = current[3].toInt() and 0xFF
            if (flags !in VALID_FLAGS || length !in 1..MAX_LENGTH) {
                // This can be noise containing AA AA; advance one byte and try
                // the next possible header instead of dropping the whole chunk.
                buffer.trimLeading(1)
                continue
            }

            val frameSize = length + FRAME_OVERHEAD
            if (current.size < frameSize) return
            val frame = current.copyOf(frameSize)
            if (!validChecksum(frame)) {
                buffer.trimLeading(1)
                continue
            }

            parseFrame(frame)
            buffer.trimLeading(frameSize)
        }
    }

    private fun parseFrame(frame: ByteArray) {
        val length = frame[3].toInt() and 0xFF
        val command = frame[4].toInt() and 0x7F
        val payloadLength = length - 1
        if (payloadLength <= 0) return
        val payload = frame.copyOfRange(5, 5 + payloadLength)

        when (command) {
            COMMAND_MAIN_INFO -> parseMainInfo(payload)
            COMMAND_REAL_TIME_INFO -> parseRealtime(payload)
            // Total-stats pages are useful metadata, but without an outbound
            // request their layout is not needed for this passive integration.
        }
    }

    private fun parseMainInfo(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            0x01 -> if (payload.size >= 4) {
                val series = payload[2].toInt() and 0xFF
                val type = payload[3].toInt() and 0xFF
                val nextModelId = series * 10 + type
                if (modelId != nextModelId) tripBaselineMeters = null
                modelId = nextModelId
                modelValue = modelName(series, type)
            }

            0x02 -> if (payload.size >= 17) {
                serialValue = payload.copyOfRange(1, 17)
                    .decodeToString()
                    .trim('\u0000')
                    .ifEmpty { null }
            }

            0x06 -> if (payload.size >= 24) {
                val drv3 = payload.u16LE(2)
                val drv2 = payload[4].toInt() and 0xFF
                val drv1 = payload[5].toInt() and 0xFF
                val main3 = payload.u16LE(11)
                val main2 = payload[13].toInt() and 0xFF
                val main1 = payload[14].toInt() and 0xFF
                val ble3 = payload.u16LE(20)
                val ble2 = payload[22].toInt() and 0xFF
                val ble1 = payload[23].toInt() and 0xFF
                firmwareValue =
                    "Main:$main1.$main2.$main3 Drv:$drv1.$drv2.$drv3 BLE:$ble1.$ble2.$ble3"
            }
        }
    }

    private fun parseRealtime(payload: ByteArray) {
        val values = when (modelId) {
            MODEL_V11 -> when {
                payload.size < MIN_V11_SHORT_PAYLOAD -> null
                payload.size < V11_LONG_LAYOUT_MIN_PAYLOAD -> payload.toV11ShortValues()
                payload.size >= MIN_V11_LONG_PAYLOAD -> payload.toV11LongValues()
                else -> null
            }

            in V12_MODELS -> payload.takeIf { it.size >= MIN_V12_PAYLOAD }?.toV12Values()
            in V13_MODELS -> payload.takeIf { it.size >= MIN_V13_PAYLOAD }?.toV14FamilyValues()
            in V14_MODELS -> payload.takeIf { it.size >= MIN_V14_PAYLOAD }?.toV14FamilyValues()
            MODEL_P6 -> payload.takeIf { it.size >= MIN_P6_PAYLOAD }?.toV14FamilyValues()
            null -> payload.takeIf { it.size >= MIN_LEGACY_PAYLOAD }?.toLegacyValues()
            else -> null
        } ?: return

        val distanceMeters = values.distanceMeters
        val baseline = tripBaselineMeters ?: distanceMeters.also { tripBaselineMeters = it }
        val tripKm = if (distanceMeters >= baseline) {
            (distanceMeters - baseline) / 1000f
        } else {
            // A reboot can reset the wheel's since-power-on counter. Do not
            // publish a negative session distance; start a new baseline.
            tripBaselineMeters = distanceMeters
            0f
        }
        val power = values.powerW
        val current = values.currentA

        data = BmsData(
            voltage = values.voltageV,
            current = current ?: 0f,
            hasCurrent = current != null,
            power = power ?: 0f,
            hasPower = power != null,
            soc = values.soc ?: 0f,
            socKnown = values.soc != null,
            temperatures = values.batteryTemperatures,
            isConnected = true,
        )

        motion = ControllerData(
            speedKmh = values.speedKmh,
            speedSource = SpeedSource.REPORTED,
            dutyPercent = values.dutyPercent ?: 0f,
            hasDuty = values.dutyPercent != null,
            batteryCurrentA = current ?: 0f,
            hasBatteryCurrent = current != null,
            inputVoltageV = values.voltageV,
            hasInputVoltage = true,
            powerW = power ?: 0f,
            hasPower = power != null,
            escTempC = values.escTemperatureC ?: NO_TEMP_SENSOR_C,
            motorTempC = values.motorTemperatureC ?: NO_TEMP_SENSOR_C,
            hasMotorTemp = values.motorTemperatureC != null,
            tripKm = tripKm,
            hasDistance = true,
            hasEnergyCounters = false,
            isConnected = true,
        )
    }

    private fun appendUnescaped(chunk: ByteArray) {
        var index = 0
        while (index < chunk.size) {
            val value = chunk[index].toInt() and 0xFF
            if (wireEscapePending) {
                if (value == ESCAPE_MARKER || value == HEADER_0) {
                    buffer.append(byteArrayOf(value.toByte()))
                    wireEscapePending = false
                    index += 1
                } else {
                    // A5 is also legal noise. Preserve it and process the
                    // current byte normally rather than swallowing either one.
                    buffer.append(byteArrayOf(ESCAPE_MARKER.toByte()))
                    wireEscapePending = false
                }
            } else if (value == ESCAPE_MARKER) {
                wireEscapePending = true
                index += 1
            } else {
                buffer.append(byteArrayOf(value.toByte()))
                index += 1
            }
        }
    }

    private data class RealtimeValues(
        val voltageV: Float,
        val currentA: Float?,
        val speedKmh: Float,
        val dutyPercent: Float?,
        val powerW: Float?,
        val soc: Float?,
        val distanceMeters: Long,
        val escTemperatureC: Float?,
        val motorTemperatureC: Float?,
        val batteryTemperatures: List<Float>,
    )

    private fun ByteArray.toV11ShortValues() = RealtimeValues(
        voltageV = u16LE(0) / 100f,
        currentA = i16LE(2) / 100f,
        speedKmh = abs(i16LE(4) / 100f),
        dutyPercent = pwmPercent(36),
        powerW = i16LE(8).toFloat(),
        soc = (u8(16) and 0x7F) / 100f,
        distanceMeters = u16LE(12).toLong() * 10L,
        escTemperatureC = presentTemperature(17),
        motorTemperatureC = null,
        batteryTemperatures = emptyList(),
    )

    private fun ByteArray.toV11LongValues() = RealtimeValues(
        voltageV = u16LE(0) / 100f,
        currentA = i16LE(2) / 100f,
        speedKmh = abs(i16LE(4) / 100f),
        dutyPercent = pwmPercent(8),
        powerW = i16LE(10).toFloat(),
        soc = u16LE(28) / 10_000f,
        distanceMeters = u16LE(26).toLong() * 10L,
        escTemperatureC = presentTemperature(42),
        motorTemperatureC = presentV11MotorTemperature(43),
        batteryTemperatures = listOfNotNull(presentTemperature(44)),
    )

    private fun ByteArray.toV12Values() = RealtimeValues(
        voltageV = u16LE(0) / 100f,
        currentA = i16LE(2) / 100f,
        speedKmh = abs(i16LE(4) / 100f),
        dutyPercent = pwmPercent(8),
        powerW = i16LE(10).toFloat(),
        soc = u16LE(24) / 10_000f,
        distanceMeters = u16LE(22).toLong() * 10L,
        escTemperatureC = presentTemperature(40),
        motorTemperatureC = null,
        batteryTemperatures = emptyList(),
    )

    private fun ByteArray.toV14FamilyValues() = RealtimeValues(
        voltageV = u16LE(0) / 100f,
        currentA = i16LE(2) / 100f,
        speedKmh = abs(i16LE(8) / 100f),
        dutyPercent = pwmPercent(14),
        powerW = i16LE(16).toFloat(),
        soc = u16LE(34) / 10_000f,
        distanceMeters = u16LE(28).toLong() * 10L,
        escTemperatureC = presentTemperature(58),
        motorTemperatureC = presentTemperature(59),
        batteryTemperatures = listOfNotNull(presentTemperature(60)),
    )

    private fun ByteArray.toLegacyValues() = RealtimeValues(
        voltageV = u16LE(0) / 100f,
        currentA = i16LE(2) / 100f,
        speedKmh = abs(i16LE(8) / 100f),
        dutyPercent = pwmPercent(14),
        powerW = null,
        // WheelLog reports each battery as hundredths of a percent and then
        // averages the two packs: `(b1 + b2) / 200` is percent, therefore
        // divide by 100 once more for BmsData's 0..1 SoC contract.
        soc = ((u16LE(34) + u16LE(36)) / 20_000f).coerceIn(0f, 1f),
        distanceMeters = u16LE(28).toLong() * 10L,
        escTemperatureC = presentTemperature(58),
        motorTemperatureC = null,
        batteryTemperatures = listOfNotNull(presentTemperature(58), presentTemperature(59)),
    )

    private fun findHeader(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if ((bytes[index].toInt() and 0xFF) == HEADER_0 &&
                (bytes[index + 1].toInt() and 0xFF) == HEADER_1
            ) return index
        }
        return -1
    }

    private fun validChecksum(frame: ByteArray): Boolean {
        var xor = 0
        for (index in 2 until frame.lastIndex) {
            xor = xor xor (frame[index].toInt() and 0xFF)
        }
        return xor == (frame.last().toInt() and 0xFF)
    }

    private fun modelName(series: Int, type: Int): String = when {
        series == 6 && type == 1 -> "InMotion V11"
        series == 6 && type == 2 -> "InMotion V11Y"
        series == 7 && type == 1 -> "InMotion V12 HS"
        series == 7 && type == 2 -> "InMotion V12 HT"
        series == 7 && type == 3 -> "InMotion V12 PRO"
        series == 8 && type == 1 -> "InMotion V13"
        series == 8 && type == 2 -> "InMotion V13 PRO"
        series == 9 && type == 1 -> "InMotion V14 50GB"
        series == 9 && type == 2 -> "InMotion V14 50S"
        series == 11 && type == 1 -> "InMotion V12S"
        series == 12 && type == 1 -> "InMotion V9"
        series == 13 && type == 1 -> "InMotion P6"
        else -> "InMotion $series.$type"
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.pwmPercent(offset: Int): Float? =
        (abs(i16LE(offset) / 100f)).takeIf { it <= 200f }?.coerceIn(0f, 100f)

    private fun ByteArray.presentTemperature(offset: Int): Float? {
        if (offset !in indices) return null
        val raw = u8(offset)
        return if (raw == 0) null else raw + 80 - 256f
    }

    private fun ByteArray.presentV11MotorTemperature(offset: Int): Float? {
        if (offset !in indices) return null
        val raw = u8(offset)
        return if (raw == 0 || raw == V11_NO_MOTOR_SENSOR) null else raw + 80 - 256f
    }

    private companion object {
        const val HEADER_0 = 0xAA
        const val HEADER_1 = 0xAA
        const val FRAME_OVERHEAD = 5 // two-byte header + flags/len/command + checksum
        const val MIN_FRAME_SIZE = 6 // header + flags + len + command + checksum
        const val MAX_LENGTH = 240
        const val MIN_LEGACY_PAYLOAD = 78
        const val MIN_V11_SHORT_PAYLOAD = 38
        const val V11_LONG_LAYOUT_MIN_PAYLOAD = 51
        const val MIN_V11_LONG_PAYLOAD = 58
        const val MIN_V12_PAYLOAD = 47
        const val MIN_V13_PAYLOAD = 55
        const val MIN_V14_PAYLOAD = 55
        const val MIN_P6_PAYLOAD = 54
        const val NO_TEMP_SENSOR_C = -100f
        const val V11_NO_MOTOR_SENSOR = 0xB0
        const val ESCAPE_MARKER = 0xA5
        val VALID_FLAGS = setOf(0x11, 0x14, 0x16)
        const val MODEL_V11 = 61
        const val MODEL_P6 = 131
        val V14_MODELS = setOf(91, 92)
        val V13_MODELS = setOf(81, 82)
        val V12_MODELS = setOf(71, 72, 73, 111)
        const val COMMAND_MAIN_INFO = 0x02
        const val COMMAND_REAL_TIME_INFO = 0x04
    }
}
