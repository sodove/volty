package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescFrameAccumulator
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescSetupConfig
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
 * Polling probes both values opcodes at connection start, then stays with the
 * first valid reply. SETUP wins if both reply because it carries controller-
 * computed ground speed and battery level. [useSetupFrame] = false preserves
 * the explicit legacy mode that polls only plain COMM_GET_VALUES, whose speed
 * is derived from eRPM + [motor].
 */
class VescProtocol(
    private val deriveBattery: Boolean = true,
    private val motor: MotorConfig = MotorConfig(),
    private val useSetupFrame: Boolean = true
) : BmsProtocol(), MotionSource, ControllerConfigSource, CanBusScanner {

    companion object {
        const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_WRITE   = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_NOTIFY  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

        /** A silent endpoint gets a short compatibility probe, then the historic SETUP request. */
        private const val VALUE_OPCODE_PROBE_POLLS = 3
    }

    override val uuids = BmsUuids(
        serviceUuid = NUS_SERVICE, notifyCharUuid = NUS_NOTIFY, writeCharUuid = NUS_WRITE
    )

    private val accumulator = VescFrameAccumulator()

    @Volatile private var motion: ControllerData? = null
    @Volatile private var battery: BmsData? = null
    @Volatile private var setupConfig: VescSetupConfig? = null
    @Volatile private var configHandshakeSent = false
    @Volatile private var selectedValuesOpcode: Int? =
        if (useSetupFrame) null else VescValues.OPCODE_GET_VALUES
    @Volatile private var valueOpcodeProbePolls = 0

    /**
     * Session baseline for [ControllerData.tripKm]: the absolute odometer reading
     * ([ControllerData.odometerKm], i.e. `tachometer_abs`) at the first decoded frame
     * of this connection. Null until that first frame arrives, and cleared by [reset]
     * so a new connection starts its trip over at 0.
     *
     * Deliberately based on the ABSOLUTE counter rather than the signed `tachometer`:
     * the absolute counter only ever accumulates, so a reversing vehicle adds to the
     * trip instead of subtracting from it, and the delta can never go negative.
     */
    @Volatile private var tripBaselineKm: Float? = null

    /**
     * The one outstanding [scanCanBus], armed while its `PING_CAN` is on the
     * wire and cleared by that function's `finally` — the single owner of the
     * disarm. Null the rest of the time, which is what lets the NEXT scan reach
     * the wire instead of joining a request whose window has expired.
     */
    @Volatile private var canScan: CompletableDeferred<List<Int>?>? = null

    override fun handshakeCommands(): List<ByteArray> {
        if (configHandshakeSent) return emptyList()
        configHandshakeSent = true
        return listOf(VescPacket.frame(byteArrayOf(VescSetupConfig.OPCODE_GET_MCCONF_TEMP.toByte())))
    }

    override fun pollCommands(): List<ByteArray> {
        val opcodes = when (val selected = selectedValuesOpcode) {
            null -> if (valueOpcodeProbePolls++ < VALUE_OPCODE_PROBE_POLLS) {
                listOf(VescValues.OPCODE_GET_VALUES_SETUP, VescValues.OPCODE_GET_VALUES)
            } else {
                // Silence supplies no evidence for either opcode. Resume the
                // historic request rather than permanently halving poll rate.
                listOf(VescValues.OPCODE_GET_VALUES_SETUP)
            }
            else -> listOf(selected)
        }
        return opcodes.map { opcode -> VescPacket.frame(byteArrayOf(opcode.toByte())) }
    }

    /** ~6.7 Hz: fast enough for a live speedo, gentle enough on a BLE link. */
    override val pollIntervalMs: Long = 150L

    override val controllerCount: Int get() = 1
    override val packCount: Int get() = if (deriveBattery) 1 else 0

    /**
     * One-shot CAN scan on a **plain** VESC link — the head unit before anybody
     * has told volty there is anything behind it.
     *
     * This is the case `G §3`'s fourth flow actually starts in: a rider who has
     * just picked their head unit owns a one-controller vehicle, which is not a
     * [ru.sodovaya.volty.data.ble.LinkSpec.isGatewayLink] and therefore speaks
     * this protocol, not [VescGatewayProtocol]. Refusing to scan here would
     * make CAN discovery reachable only once the rider had already typed the
     * CAN ids it exists to find.
     *
     * Sending straight from the caller's coroutine is safe **here and only
     * here**: this protocol's polling is a fire-and-forget burst with no
     * request/reply state to race (`ConnectionSession`'s non-serial branch), and
     * `PING_CAN` is answered by the endpoint itself rather than forwarded, so
     * none of `C §10.1`'s single-forward-in-flight reasoning applies.
     * [VescGatewayProtocol] has that state and overrides accordingly.
     */
    override suspend fun scanCanBus(send: suspend (ByteArray) -> Unit): List<Int>? {
        // Single-flight, exactly as [VescGatewayProtocol] is: the firmware
        // silently discards a second `PING_CAN` inside the ~2.5 s window
        // (`C §10.2`), so a second caller joins this one rather than putting a
        // request on the wire that can only ever be dropped.
        val existing = canScan
        if (existing != null) return withTimeoutOrNull(CanBusScanner.REPLY_TIMEOUT_MS) { existing.await() }
        val waiter = CompletableDeferred<List<Int>?>()
        canScan = waiter
        return try {
            send(VescPacket.frame(byteArrayOf(VescCan.OPCODE_PING_CAN.toByte())))
            withTimeoutOrNull(CanBusScanner.REPLY_TIMEOUT_MS) { waiter.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The write failed — a link dropping mid-scan. Reported as silence,
            // which is what the rider is told either way.
            null
        } finally {
            // **The disarm is what lets a NEXT scan happen at all**, now that a
            // second caller joins an armed one: a request left armed after its
            // window expired would have every later scan join a dead deferred
            // and wait the window out again, never putting a `PING_CAN` on the
            // wire. (On the success path `onNotification` has already nulled it
            // and this is a no-op.)
            //
            // The identity check guards the interleaving the single-flight above
            // still allows: another caller arming between this scan being
            // answered and this line running would otherwise have ITS arm
            // cleared here and its reply dropped — a scan that put a `PING_CAN`
            // on a healthy bus and reported silence.
            //
            // It IS reachable from a test, contrary to what this comment first
            // claimed: `reset()` answers a scan without going through
            // `onNotification`, and an `UNDISPATCHED` second caller arms before
            // the first resumes. See `a disarm must not clear a scan somebody
            // else armed`.
            if (canScan === waiter) canScan = null
        }
    }

    override fun onNotification(data: ByteArray) {
        for (payload in accumulator.append(data)) {
            // Checked before the value decoders because it is a different
            // question, not a fallback: `PING_CAN`'s reply carries opcode 62,
            // which both decoders reject anyway, so the ordering is for the
            // reader rather than for correctness.
            val scan = canScan
            if (scan != null) {
                val ids = VescCan.parsePingCan(payload)
                if (ids != null) {
                    // Answer only. Disarming belongs to [scanCanBus]'s `finally`
                    // and to it alone: a second owner here was provably
                    // indistinguishable from its absence (this task's sweep,
                    // mutant V8), and two owners of one field is how they come
                    // to disagree.
                    scan.complete(ids)
                    // A short-circuit, not a guard: a payload `parsePingCan`
                    // accepted begins with opcode 62, which both value decoders
                    // below reject on their own first byte. Kept for the reader;
                    // reported as an equivalent mutant rather than pretended to
                    // be load-bearing.
                    continue
                }
            }
            VescSetupConfig.decode(payload)?.let {
                setupConfig = it
                continue
            }
            val decoded = when (payload.firstOrNull()?.toInt()) {
                VescValues.OPCODE_GET_VALUES_SETUP -> VescValues.decodeSetupValues(payload)?.also {
                    // SETUP carries the richer telemetry, so a late SETUP reply
                    // upgrades an opcode-4 selection but never the reverse.
                    // Explicit legacy mode is the exception: callers that set
                    // useSetupFrame=false asked for opcode 4 only, even if an
                    // old or unsolicited SETUP reply later crosses the link.
                    if (useSetupFrame) selectedValuesOpcode = VescValues.OPCODE_GET_VALUES_SETUP
                }
                VescValues.OPCODE_GET_VALUES -> VescValues.decodeValues(payload, motor)?.also {
                    if (selectedValuesOpcode != VescValues.OPCODE_GET_VALUES_SETUP) {
                        selectedValuesOpcode = VescValues.OPCODE_GET_VALUES
                    }
                }
                else -> null
            }
            if (decoded != null) {
                val baseline = tripBaselineKm ?: decoded.odometerKm.also { tripBaselineKm = it }
                val withSessionTrip = decoded.copy(tripKm = (decoded.odometerKm - baseline).coerceAtLeast(0f))
                motion = withSessionTrip
                if (deriveBattery) battery = derivedBatteryFrom(withSessionTrip)
            }
        }
    }

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override val controllerConfigCount: Int get() = 1

    override fun latestControllerConfig(controllerIndex: Int): VescSetupConfig? =
        if (controllerIndex == 0) setupConfig else null

    override fun latestData(packIndex: Int): BmsData? =
        if (packIndex == 0) battery else null

    override fun reset() {
        accumulator.reset()
        motion = null
        battery = null
        setupConfig = null
        configHandshakeSent = false
        selectedValuesOpcode = if (useSetupFrame) null else VescValues.OPCODE_GET_VALUES
        valueOpcodeProbePolls = 0
        tripBaselineKm = null
        // Answered with null — silence — rather than dropped: a scan on a
        // session being torn down can never be answered, and
        // [CanBusScanner.REPLY_TIMEOUT_MS] is a long time to make a rider watch
        // a spinner for a reply that cannot arrive. Never an empty list, which
        // would claim the bus is empty.
        canScan?.complete(null)
        canScan = null
    }

}

/**
 * Synthesise the pack a controller can see, from that controller's own
 * telemetry. Sign is the one real trap: VESC input current is POSITIVE while
 * discharging, [BmsData.current] is "+ = charging" — so it is negated here, and
 * the power with it.
 *
 * `batteryLevelFraction` is the controller's own gauge (computed from its
 * configured battery cutoffs). When it is absent or zero the VESC has no
 * battery configuration, so the SoC is left unknown (`socKnown = false`)
 * and VoltageSocEstimator fills it in downstream from the vehicle's
 * chemistry — the same path a dumb Begode takes.
 *
 * **Top-level and shared, not a private method**, since `I` Task 5: a VESC link
 * that is a gateway ([VescGatewayProtocol]) derives a battery too, and the
 * sign convention, the `socKnown` rule and the deliberately empty cell /
 * temperature lists must be ONE statement rather than two that can drift.
 * [VescGatewayProtocol] folds its several controllers into one [ControllerData]
 * and calls this with the result — so the fold is the only thing that differs
 * between the two callers, which is exactly what does differ.
 */
internal fun derivedBatteryFrom(m: ControllerData): BmsData {
    val level = m.batteryLevelFraction
    val known = level != null && level > 0f
    return BmsData(
        voltage = m.inputVoltageV,
        current = -m.batteryCurrentA,
        power = -m.powerW,
        soc = if (known) level * 100f else 0f,
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
