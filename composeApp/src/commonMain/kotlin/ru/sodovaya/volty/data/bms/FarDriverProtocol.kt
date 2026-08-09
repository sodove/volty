package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.SpeedUnknownReason
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Read-only FarDriver telemetry over the controller's FFE0/FFEC link.
 *
 * The controller emits a stream of fixed sixteen-byte frames; it does not
 * require a request to be written before telemetry becomes available. Both
 * the newer register frames and the older command frames are accepted because
 * the two formats are present in controller firmware found in the field. No
 * command is sent by this decoder: the same characteristic is also the
 * controller's configuration channel, and a telemetry monitor must not risk a
 * write with side effects.
 */
class FarDriverProtocol(
    private val deriveBattery: Boolean = true,
    private val motor: MotorConfig = MotorConfig()
) : BmsProtocol(), MotionSource {

    companion object {
        const val FARDRIVER_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val FARDRIVER_CHARACTERISTIC = "0000ffec-0000-1000-8000-00805f9b34fb"

        private const val FRAME_SIZE = 16
        private const val PAYLOAD_END = 14
        private const val NEW_FRAME_MASK = 0xC0
        private const val NEW_FRAME_MARKER = 0x80
        private const val NEW_CRC_INITIAL = 0x7F3C
        private const val NO_TEMP_SENSOR_C = -100f

        /** The original app's lookup table, indexed by the seven-bit frame id. */
        private val REGISTER_ADDRESSES = intArrayOf(
            226, 232, 238, 0, 6, 12, 18, 226, 232, 238,
            24, 30, 36, 42, 226, 232, 238, 48, 93, 99,
            105, 226, 232, 238, 124, 130, 136, 142, 226, 232,
            238, 148, 154, 160, 166, 226, 232, 238, 172, 178,
            184, 190, 226, 232, 238, 196, 202, 208, 226, 232,
            238, 214, 220, 244, 250
        )

        private val NEW_FAULTS = arrayOf(
            "Motor Hall Error",
            "Throttle Error",
            "Current Protect Restart",
            "Phase Current Surge Protect",
            "Voltage Alarm",
            "Alarm Protect",
            "Motor Temperature Protect",
            "Controller Temperature Protect",
            "Phase Current Overflow Protect",
            "Phase Zero Error",
            "Phase Short Error",
            "Line Current Zero Error",
            "MOSFET High Side Error",
            "MOSFET Low Side Error",
            "MOE Current Protect"
        )
    }

    override val uuids = BmsUuids(
        serviceUuid = FARDRIVER_SERVICE,
        notifyCharUuid = FARDRIVER_CHARACTERISTIC,
        writeCharUuid = FARDRIVER_CHARACTERISTIC
    )

    private val receiveBuffer = ByteArrayAccumulator()

    @Volatile private var motion: ControllerData? = null
    @Volatile private var battery: BmsData? = null

    private var voltageV: Float? = null
    private var batteryCurrentA: Float? = null
    private var phaseACurrentA: Float? = null
    private var phaseCCurrentA: Float? = null
    private var mechanicalRpm: Float? = null
    private var escTemperatureC: Float? = null
    private var motorTemperatureC: Float? = null
    private var batteryLevelFraction: Float? = null
    private var faultByteA: Int? = null
    private var faultByteB: Int? = null

    override val controllerCount: Int get() = 1
    override val packCount: Int get() = if (deriveBattery) 1 else 0

    /** FarDriver broadcasts telemetry; polling would turn a safe monitor into a writer. */
    override val pollIntervalMs: Long = 0L

    override fun handshakeCommands(): List<ByteArray> = emptyList()

    override fun pollCommands(): List<ByteArray> = emptyList()

    override fun onNotification(data: ByteArray) {
        if (data.isEmpty()) return
        receiveBuffer.append(data)
        while (receiveBuffer.size >= FRAME_SIZE) {
            val buffered = receiveBuffer.toByteArray()
            val start = buffered.indexOfFirst { it == 0xAA.toByte() }
            if (start < 0) {
                receiveBuffer.reset()
                return
            }
            if (start > 0) {
                receiveBuffer.trimLeading(start)
                continue
            }

            val frame = buffered.copyOfRange(0, FRAME_SIZE)
            val valid = if (isNewFrame(frame)) {
                validateNewChecksum(frame)
            } else {
                validateLegacyChecksum(frame)
            }
            if (!valid) {
                // A bad candidate may contain a second start byte in its
                // payload. Drop only the first byte so the next candidate can
                // establish a fresh boundary.
                receiveBuffer.trimLeading(1)
                continue
            }

            receiveBuffer.trimLeading(FRAME_SIZE)
            if (isNewFrame(frame)) decodeNewFrame(frame) else decodeLegacyFrame(frame)
            publish()
        }
    }

    private fun isNewFrame(frame: ByteArray): Boolean =
        ((frame[1].toInt() and 0xFF) and NEW_FRAME_MASK) == NEW_FRAME_MARKER

    private fun validateNewChecksum(frame: ByteArray): Boolean {
        var crc = NEW_CRC_INITIAL
        for (index in 0 until PAYLOAD_END) {
            crc = crc xor (frame[index].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return (frame[14].toInt() and 0xFF) == (crc and 0xFF) &&
            (frame[15].toInt() and 0xFF) == ((crc ushr 8) and 0xFF)
    }

    private fun validateLegacyChecksum(frame: ByteArray): Boolean {
        val sum = (0 until PAYLOAD_END).sumOf { frame[it].toInt() and 0xFF }
        return (frame[14].toInt() and 0xFF) == ((sum ushr 8) and 0xFF) &&
            (frame[15].toInt() and 0xFF) == (sum and 0xFF)
    }

    private fun decodeNewFrame(frame: ByteArray) {
        val id = frame[1].toInt() and 0x7F
        val address = REGISTER_ADDRESSES.getOrNull(id) ?: return
        when (address) {
            226 -> {
                mechanicalRpm = u16(frame, 8).toShort().toFloat()
                faultByteA = frame[4].toInt() and 0xFF
                faultByteB = frame[5].toInt() and 0xFF
            }
            232 -> {
                voltageV = u16(frame, 2) / 10f
                batteryCurrentA = i16(frame, 6) / 4f
            }
            238 -> {
                phaseACurrentA = phaseCurrent(u24(frame, 6))
                phaseCCurrentA = phaseCurrent(u24(frame, 9))
            }
            214 -> {
                escTemperatureC = i16(frame, 12).toFloat()
            }
            244 -> {
                motorTemperatureC = i16(frame, 2).toFloat()
                batteryLevelFraction = (frame[5].toInt() and 0xFF) / 100f
            }
        }
    }

    private fun decodeLegacyFrame(frame: ByteArray) {
        when (frame[1].toInt() and 0xFF) {
            0 -> mechanicalRpm = u16(frame, 6).toShort().toFloat()
            1 -> {
                voltageV = u16(frame, 2) / 10f
                batteryCurrentA = i16(frame, 4) / 4f
            }
            2 -> {
                phaseACurrentA = phaseCurrent(u24(frame, 2))
                phaseCCurrentA = phaseCurrent(u24(frame, 9))
            }
            15 -> {
                faultByteA = u16(frame, 4) ushr 8
                faultByteB = u16(frame, 8) and 0xFF
            }
        }
    }

    private fun publish() {
        val speed = mechanicalRpm?.let(::derivedSpeedKmh)
        val hasVoltage = voltageV != null
        val hasCurrent = batteryCurrentA != null
        val hasPower = hasVoltage && hasCurrent
        val voltage = voltageV ?: 0f
        val current = batteryCurrentA ?: 0f
        val power = if (hasPower) voltage * current else 0f
        val motionSnapshot = ControllerData(
            speedKmh = speed?.let(::abs) ?: 0f,
            speedSource = if (speed != null) SpeedSource.DERIVED else SpeedSource.NONE,
            speedUnknownReason = when {
                speed != null -> null
                mechanicalRpm != null -> SpeedUnknownReason.NO_WHEEL_GEOMETRY
                else -> SpeedUnknownReason.FIRMWARE_DID_NOT_REPORT
            },
            dutyPercent = 0f,
            hasDuty = false,
            motorCurrentA = phaseACurrentA ?: 0f,
            batteryCurrentA = current,
            hasBatteryCurrent = hasCurrent,
            inputVoltageV = voltage,
            hasInputVoltage = hasVoltage,
            powerW = power,
            hasPower = hasPower,
            eRpm = mechanicalRpm ?: 0f,
            escTempC = escTemperatureC ?: NO_TEMP_SENSOR_C,
            motorTempC = motorTemperatureC ?: NO_TEMP_SENSOR_C,
            hasMotorTemp = motorTemperatureC != null,
            odometerKm = 0f,
            tripKm = 0f,
            hasDistance = false,
            consumedAh = 0f,
            consumedWh = 0f,
            regenAh = 0f,
            regenWh = 0f,
            hasEnergyCounters = false,
            faults = faults(),
            batteryLevelFraction = batteryLevelFraction,
            isConnected = true
        )
        motion = motionSnapshot
        if (deriveBattery && hasVoltage && hasCurrent) {
            // FarDriver's byte in register 244 is the controller's VCU SOC,
            // not a verified smart-BMS gauge. Keep it on motion telemetry and
            // let the vehicle-level estimator decide whether pack SoC can be
            // inferred from voltage and chemistry.
            battery = derivedBatteryFrom(motionSnapshot).copy(soc = 0f, socKnown = false)
        }
    }

    private fun faults(): List<String> {
        val first = faultByteA ?: return emptyList()
        val second = faultByteB ?: 0
        return buildList {
            NEW_FAULTS.forEachIndexed { index, label ->
                val set = if (index < 8) {
                    first and (1 shl index) != 0
                } else {
                    second and (1 shl (index - 8)) != 0
                }
                if (set) add(label)
            }
        }
    }

    private fun derivedSpeedKmh(rpm: Float): Float? {
        if (motor.wheelDiameterMm <= 0 || motor.gearRatio <= 0f) return null
        val circumferenceKm = (PI * motor.wheelDiameterMm / 1_000_000.0).toFloat()
        return rpm * circumferenceKm * 60f / motor.gearRatio
    }

    private fun phaseCurrent(raw: Int): Float = 1.953125f * sqrt(raw.toFloat())

    private fun u16(frame: ByteArray, offset: Int): Int =
        ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF)

    private fun i16(frame: ByteArray, offset: Int): Int = u16(frame, offset).toShort().toInt()

    private fun u24(frame: ByteArray, offset: Int): Int =
        ((frame[offset].toInt() and 0xFF) shl 16) or
            ((frame[offset + 1].toInt() and 0xFF) shl 8) or
            (frame[offset + 2].toInt() and 0xFF)

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun latestData(packIndex: Int): BmsData? =
        if (deriveBattery && packIndex == 0) battery else null

    override fun reset() {
        receiveBuffer.reset()
        motion = null
        battery = null
        voltageV = null
        batteryCurrentA = null
        phaseACurrentA = null
        phaseCCurrentA = null
        mechanicalRpm = null
        escTemperatureC = null
        motorTemperatureC = null
        batteryLevelFraction = null
        faultByteA = null
        faultByteB = null
    }
}
