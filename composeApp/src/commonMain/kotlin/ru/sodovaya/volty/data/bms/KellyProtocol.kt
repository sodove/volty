package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import ru.sodovaya.volty.data.controller.kelly.ErrorCodes
import ru.sodovaya.volty.data.controller.kelly.EtsCommand
import ru.sodovaya.volty.data.controller.kelly.EtsPacketBuilder
import ru.sodovaya.volty.data.controller.kelly.MonitorDefinitions
import ru.sodovaya.volty.data.controller.kelly.ParamType
import ru.sodovaya.volty.data.controller.kelly.ParameterCodec
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.SpeedUnknownReason
import kotlin.math.PI
import kotlin.math.abs

/**
 * Read-only Kelly KLS monitor over the ETS UART protocol.
 *
 * Monitor commands are a transaction, rather than a burst: one command is on
 * the wire at a time and the next starts only after its matching, checksummed
 * response has arrived. A late or malformed response therefore cannot become
 * a fragment of a later monitor snapshot.
 */
class KellyProtocol(
    private val deriveBattery: Boolean = false,
    private val motor: MotorConfig = MotorConfig(),
    private val replyTimeoutMs: Long = DEFAULT_REPLY_TIMEOUT_MS
) : BmsProtocol(), MotionSource, SerialPollSource {

    companion object {
        const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

        private const val DEFAULT_REPLY_TIMEOUT_MS = 400L
        private const val NO_TEMP_SENSOR_C = -100f
    }

    override val uuids = BmsUuids(
        serviceUuid = NUS_SERVICE,
        notifyCharUuid = NUS_NOTIFY,
        writeCharUuid = NUS_WRITE
    )

    private val receiveBuffer = ByteArrayAccumulator()
    private val monitorPackets = arrayOfNulls<ByteArray>(MonitorDefinitions.MONITOR_COMMANDS.size)

    @Volatile private var motion: ControllerData? = null
    @Volatile private var battery: BmsData? = null
    @Volatile private var versionHandshakeSent = false
    @Volatile private var pending: Pending? = null

    override val controllerCount: Int get() = 1
    override val packCount: Int get() = if (deriveBattery) 1 else 0

    /** A completed monitor snapshot every roughly quarter second is plenty for a live speedometer. */
    override val pollIntervalMs: Long = 250L

    override fun handshakeCommands(): List<ByteArray> {
        if (versionHandshakeSent) return emptyList()
        versionHandshakeSent = true
        return listOf(EtsPacketBuilder.buildTxPacket(EtsCommand.CODE_VERSION))
    }

    /**
     * Kept as an inspectable description of the monitor cycle. The session
     * drives this [SerialPollSource] via [runPollLoop], not its burst path.
     */
    override fun pollCommands(): List<ByteArray> = MonitorDefinitions.MONITOR_COMMANDS.map {
        EtsPacketBuilder.buildTxPacket(it)
    }

    override suspend fun runPollLoop(send: suspend (ByteArray) -> Unit) {
        while (currentCoroutineContext().isActive) {
            var complete = true
            for (command in MonitorDefinitions.MONITOR_COMMANDS) {
                if (!requestMonitor(command, send)) {
                    complete = false
                    break
                }
            }
            if (!complete) clearMonitorCycle()
            delay(pollIntervalMs)
        }
    }

    private suspend fun requestMonitor(command: Byte, send: suspend (ByteArray) -> Unit): Boolean {
        val request = Pending(command, CompletableDeferred())
        pending = request
        return try {
            withTimeoutOrNull(replyTimeoutMs) {
                send(EtsPacketBuilder.buildTxPacket(command))
                request.waiter.await()
            } != null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        } finally {
            if (pending === request) pending = null
        }
    }

    /**
     * Reassemble ETS packets from arbitrary BLE notification chunks. ETS has
     * no start delimiter, so an impossible length discards one byte and lets a
     * subsequent complete packet establish the next boundary.
     */
    override fun onNotification(data: ByteArray) {
        // A response has meaning only while the serial loop has an armed
        // request. Do not retain a fragment received between cycles: the next
        // request must not complete it with its own trailing bytes.
        if (pending == null) {
            receiveBuffer.reset()
            return
        }
        receiveBuffer.append(data)
        while (receiveBuffer.size >= 2) {
            val buffered = receiveBuffer.toByteArray()
            val length = buffered[1].toInt() and 0xFF
            if (length > EtsPacketBuilder.MAX_DATA_LENGTH) {
                receiveBuffer.trimLeading(1)
                rejectMonitorCycle()
                continue
            }
            val packetSize = length + 3
            if (receiveBuffer.size < packetSize) return
            val raw = buffered.copyOfRange(0, packetSize)
            receiveBuffer.trimLeading(packetSize)
            if (acceptResponse(raw)) {
                // A response belongs to exactly one armed request. Any bytes
                // coalesced after it arrived while that request was still
                // armed have no request identity once it is resolved, so they
                // must not survive to the next request.
                receiveBuffer.reset()
                return
            }
        }
    }

    /** True only when [raw] was accepted and resolved the armed request. */
    private fun acceptResponse(raw: ByteArray): Boolean {
        val expected = pending?.command ?: return false
        val packet = EtsPacketBuilder.parseRxResponse(raw, expected).getOrElse {
            rejectMonitorCycle()
            return false
        }
        if (packet.dataLength != EtsPacketBuilder.MAX_DATA_LENGTH) {
            rejectMonitorCycle()
            return false
        }

        val index = MonitorDefinitions.MONITOR_COMMANDS.indexOf(expected)
        if (index < 0 || index != nextMonitorIndex()) {
            rejectMonitorCycle()
            return false
        }
        monitorPackets[index] = packet.data

        val current = pending
        if (current?.command == expected) {
            pending = null
            current.waiter.complete(Unit)
        }
        if (monitorPackets.all { it != null }) publishMonitor()
        return true
    }

    private fun nextMonitorIndex(): Int = monitorPackets.indexOfFirst { it == null }

    private fun rejectMonitorCycle() {
        // A mismatch invalidates the response boundary too. Dropping unread
        // bytes prevents a malformed/stale packet tail reaching the next
        // request's accumulator.
        receiveBuffer.reset()
        clearMonitorCycle()
    }

    private fun clearMonitorCycle() {
        monitorPackets.fill(null)
    }

    private fun publishMonitor() {
        val data = IntArray(EtsPacketBuilder.MAX_DATA_LENGTH * monitorPackets.size)
        monitorPackets.forEachIndexed { index, packet ->
            packet!!.forEachIndexed { byteIndex, value ->
                data[index * EtsPacketBuilder.MAX_DATA_LENGTH + byteIndex] = value.toInt() and 0xFF
            }
        }
        val decoded = decodeMonitor(data)
        motion = decoded
        if (deriveBattery) {
            // This deliberately leaves socKnown=false: KableBmsRepository applies
            // VoltageSocEstimator with the owning vehicle's chemistry, exactly as
            // it does for the VESC-derived pack path.
            battery = derivedBatteryFrom(decoded)
        }
        clearMonitorCycle()
    }

    private fun decodeMonitor(data: IntArray): ControllerData {
        val voltage = numeric(data, "B+ Volt")
        val motorTemp = temperature(data, "Motor Temp")
        val escTemp = temperature(data, "Controller Temp")
        // KLS calls this Motor Speed and the ETS monitor documents RPM. It is
        // mechanical RPM, despite the shared ControllerData field's historic
        // eRPM name, so pole pairs deliberately do not participate below.
        val mechanicalRpm = numeric(data, "Motor Speed")
        val phaseCurrent = numeric(data, "Phase Current")
        val errors = numeric(data, "Error Status")
        val speed = derivedSpeedKmh(mechanicalRpm)
        return ControllerData(
            speedKmh = speed?.let(::abs) ?: 0f,
            speedSource = if (speed != null) SpeedSource.DERIVED else SpeedSource.NONE,
            speedUnknownReason = if (speed != null) null else SpeedUnknownReason.NO_WHEEL_GEOMETRY,
            dutyPercent = 0f,
            hasDuty = false,
            motorCurrentA = phaseCurrent,
            batteryCurrentA = 0f,
            inputVoltageV = voltage,
            hasInputVoltage = voltage > 0f,
            powerW = 0f,
            hasPower = false,
            eRpm = mechanicalRpm,
            escTempC = escTemp ?: NO_TEMP_SENSOR_C,
            motorTempC = motorTemp ?: NO_TEMP_SENSOR_C,
            hasMotorTemp = motorTemp != null,
            odometerKm = 0f,
            tripKm = 0f,
            consumedAh = 0f,
            consumedWh = 0f,
            regenAh = 0f,
            regenWh = 0f,
            hasEnergyCounters = false,
            faults = ErrorCodes.decode(errors.toInt()),
            isConnected = true
        )
    }

    private fun numeric(data: IntArray, name: String): Float {
        val parameter = MonitorDefinitions.PARAMETERS.first { it.name == name }
        return ParameterCodec.readParam(data, parameter.offset, parameter.size, parameter.position, parameter.type)
            .toInt(parameter.type)
            .toFloat()
    }

    private fun temperature(data: IntArray, name: String): Float? {
        val parameter = MonitorDefinitions.PARAMETERS.first { it.name == name }
        val value = numeric(data, name)
        return value.takeIf { it >= parameter.minValue && it <= parameter.maxValue }
    }

    private fun String.toInt(type: ParamType): Int = when (type) {
        ParamType.HEX -> toInt(16)
        else -> toInt()
    }

    private fun derivedSpeedKmh(mechanicalRpm: Float): Float? {
        if (motor.wheelDiameterMm <= 0 || motor.gearRatio <= 0f) return null
        val circumferenceKm = (PI * motor.wheelDiameterMm / 1_000_000.0).toFloat()
        return mechanicalRpm * circumferenceKm * 60f * motor.gearRatio
    }

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun latestData(packIndex: Int): BmsData? =
        if (deriveBattery && packIndex == 0) battery else null

    override fun reset() {
        receiveBuffer.reset()
        clearMonitorCycle()
        motion = null
        battery = null
        versionHandshakeSent = false
        pending?.waiter?.cancel()
        pending = null
    }

    private class Pending(val command: Byte, val waiter: CompletableDeferred<Unit>)
}
