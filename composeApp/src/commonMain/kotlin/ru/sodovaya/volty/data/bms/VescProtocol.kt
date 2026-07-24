package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescFrameAccumulator
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig

/**
 * VESC-based controllers (incl. uBox) over the Nordic UART Service.
 *
 * Both a [BmsProtocol] and a [MotionSource]: motion is the point, and the
 * battery side is optional — a VESC with no smart BMS still knows its pack
 * voltage, so it can back a single DERIVED pack ([deriveBattery]). When a real
 * BMS covers the pack, the composer turns that off and `packCount` is 0.
 *
 * Polling asks for COMM_GET_VALUES_SETUP: it is the only frame carrying a
 * controller-computed ground speed and battery level. [useSetupFrame] = false
 * falls back to plain COMM_GET_VALUES, whose speed is derived from eRPM + [motor].
 */
class VescProtocol(
    private val deriveBattery: Boolean = true,
    private val motor: MotorConfig = MotorConfig(),
    private val useSetupFrame: Boolean = true
) : BmsProtocol(), MotionSource {

    companion object {
        const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_WRITE   = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_NOTIFY  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    }

    override val uuids = BmsUuids(
        serviceUuid = NUS_SERVICE, notifyCharUuid = NUS_NOTIFY, writeCharUuid = NUS_WRITE
    )

    private val accumulator = VescFrameAccumulator()

    @Volatile private var motion: ControllerData? = null
    @Volatile private var battery: BmsData? = null

    override fun handshakeCommands(): List<ByteArray> = emptyList()

    override fun pollCommands(): List<ByteArray> = listOf(
        VescPacket.frame(byteArrayOf(
            (if (useSetupFrame) VescValues.OPCODE_GET_VALUES_SETUP else VescValues.OPCODE_GET_VALUES).toByte()
        ))
    )

    /** ~6.7 Hz: fast enough for a live speedo, gentle enough on a BLE link. */
    override val pollIntervalMs: Long = 150L

    override val controllerCount: Int get() = 1
    override val packCount: Int get() = if (deriveBattery) 1 else 0

    override fun onNotification(data: ByteArray) {
        for (payload in accumulator.append(data)) {
            val decoded = if (useSetupFrame) VescValues.decodeSetupValues(payload)
                          else VescValues.decodeValues(payload, motor)
            if (decoded != null) {
                motion = decoded
                if (deriveBattery) battery = deriveBattery(decoded)
            }
        }
    }

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun latestData(packIndex: Int): BmsData? =
        if (packIndex == 0) battery else null

    override fun reset() {
        accumulator.reset()
        motion = null
        battery = null
    }

    /**
     * Synthesise the pack the controller can see. Sign is the one real trap:
     * VESC input current is POSITIVE while discharging, [BmsData.current] is
     * "+ = charging" — so it is negated here, and the power with it.
     *
     * `batteryLevelFraction` is the controller's own gauge (computed from its
     * configured battery cutoffs). When it is absent or zero the VESC has no
     * battery configuration, so the SoC is left unknown (`socKnown = false`)
     * and VoltageSocEstimator fills it in downstream from the vehicle's
     * chemistry — the same path a dumb Begode takes.
     */
    private fun deriveBattery(m: ControllerData): BmsData {
        val level = m.batteryLevelFraction
        val known = level != null && level > 0f
        return BmsData(
            voltage = m.inputVoltageV,
            current = -m.batteryCurrentA,
            power = -m.powerW,
            soc = if (known) level!! * 100f else 0f,
            socKnown = known,
            // No cells and no pack thermistor: a controller measures neither.
            // The ESC temperature is motion telemetry and stays out of the
            // battery's temperature list, so it can never trip a battery
            // over-temperature alert.
            cellVoltages = emptyList(),
            temperatures = emptyList(),
            chargeEnabled = true,
            dischargeEnabled = true,
            isConnected = true,
            timestamp = m.timestamp
        )
    }
}
