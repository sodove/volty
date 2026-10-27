package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryRole
import ru.sodovaya.volty.domain.plausibility.CounterDecision
import ru.sodovaya.volty.domain.plausibility.CounterStepGate
import ru.sodovaya.volty.domain.plausibility.LedgerKind
import ru.sodovaya.volty.domain.plausibility.PlausibilityRule
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
private fun wallClockMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Begode / Gotway electric unicycle protocol.
 *
 * BLE: "serial over BLE" — service 0xFFE0, single characteristic 0xFFE1.
 *
 * The wheel streams unprompted; there is no handshake and no polling.
 * FFE1 is also Begode's COMMAND channel (light, pedal mode, tiltback), so
 * this protocol NEVER writes to it — a stray write could reconfigure a wheel
 * under its rider. Empty command lists are the requirement, not an oversight.
 *
 * Frame format (24 bytes): `55 AA` header, 18 payload bytes, frame type at
 * byte 18, `5A 5A 5A 5A` tail at bytes 20..23. There is no checksum; the tail
 * is the only integrity check, and `55 AA` legitimately occurs inside payload,
 * so a failed tail advances the scan by ONE byte, not a whole frame. At MTU 23
 * every 24-byte frame straddles two notifications — the accumulator is load-
 * bearing, not defensive.
 *
 * Frame types (byte 18):
 *   0x00 — live motherboard frame (scaled voltage, speed, the wheel's own
 *          since-power-on distance, phase current, MPU temp)
 *   0x01 — smart-BMS telemetry; byte 19 is bmsnum 0..3 (branch = bmsnum shr 1,
 *          section within the branch = bmsnum and 1)
 *   0x02 / 0x03 — cell voltages of branch 0 / branch 1, 8 cells per frame,
 *          packet index at byte 19
 *   0x04 — total odometer, wheel settings and an alert bitmap
 *          (odometer, alert bitmap and tiltback speed — see [parseOdometerFrame])
 *   0x07 — motion telemetry: battery current, motor temperature and the
 *          wheel's HARDWARE PWM (see [parseMotionFrame])
 *
 * This file used to say of 0x07: *"undocumented, ignored; WheelLog does not
 * decode it either."* **That was false**, and it cost this part its whole
 * design — the wheel's duty, the one number a EUC rider needs an alarm on, was
 * declared underivable while it was sitting in a frame we were dropping.
 * `GotwayAdapter` contains a commented-out debug dump of 0x07 near the top of
 * its parser, which is what an earlier reading found; the real decode is
 * further down the same `when` chain, next to 0x00 and 0x04. Checked against
 * the source on 2026-07-27.
 *
 * The battery is two parallel branches, each two sections in series
 * (2S2P of assemblies); this protocol reports each branch as one pack.
 *
 * A wheel is a CONTROLLER as well as a battery, over this one link: the same
 * frames carry speed, duty, mileage and two temperatures, so this class is also
 * a [MotionSource] with exactly one controller ([latestMotion]).
 *
 * Based on: WheelLog GotwayAdapter (GPL-3.0, layout only) and a real capture
 * from a Begode ET Max — see docs/superpowers/specs/2026-07-21-multi-pack-bms-design.md
 * and BegodeDumpFixture.
 *
 * @param cellCount cells in series of the wheel's pack, from the vehicle
 *   profile — the ONE thing this protocol cannot read off any frame and the
 *   only thing standing between the live frame's 67.2 V-referenced voltage and
 *   a real one. See [inputVoltageV] and [scaleLiveVoltage]. Null (the default)
 *   means the caller has no cell count to give, and then no voltage is
 *   published at all rather than a number that would read ~59 V on a 168 V
 *   wheel. Same shape as [VescProtocol]'s `motor` parameter: vehicle
 *   configuration the decoder needs in order to be honest, supplied at
 *   construction by the layer that owns the profile.
 */
class BegodeProtocol(
    private val cellCount: Int? = null,
    /** Wall clock in epoch milliseconds, for [ControllerData.wheelPwmAlarmSinceMillis] only; tests replaying a dump inject the dump's time. */
    private val nowMillis: () -> Long = ::wallClockMillis,
) : BmsProtocol(), MotionSource, ProtocolFacts {

    /** The ledger stamps with the same wall clock as the PWM-alarm onset. */
    override fun ledgerNowMillis(): Long = nowMillis()

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    /** Never write to FFE1 — it is the wheel's command channel. */
    override fun handshakeCommands(): List<ByteArray> = emptyList()

    /** Never write to FFE1 — the wheel streams on its own. */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L // Not used — streaming

    /** Two parallel battery branches multiplexed over one BLE link. */
    override val packCount: Int get() = 2

    private val buffer = ByteArrayAccumulator()
    private val branches = Array(2) { BranchState() }
    private val observedCellPositions = Array(2) { mutableSetOf<Int>() }

    // Wheel-level telemetry from the live 0x00 frame. The raw voltage is on
    // Begode's 67.2 V scale and needs a nominal-voltage multiplier this
    // protocol does not know (see the design spec, "Масштабирование
    // напряжения в кадре 0x00") — using it as a pack voltage would show
    // ~59 V on a 168 V wheel. Branch voltage comes from the cells instead
    // (see [branchVoltage]); the synthetic no-BMS pack publishes voltage = 0
    // and lets the caller scale via [liveVoltageOn672ScaleV].
    private var liveVoltageRaw: Int = 0
    private var phaseCurrentA: Float = 0f
    private var boardTempC: Float = 0f

    // --- Motion, decoded but NOT published as battery data ---
    // A wheel's speed, trip and odometer belong to a controller, not to a pack:
    // BmsData is a sample of ONE battery and has no field for any of them.
    // They live on the protocol until a MotionSource surfaces them.

    /** Last decoded speed MAGNITUDE, km/h — see [speedKmh] for what "last" means. */
    private var speedKmhValue: Float = 0f

    /**
     * Last decoded speed with the frame's own SIGN kept, km/h — see
     * [signedSpeedKmh]. [speedKmhValue] is this value's magnitude.
     */
    private var signedSpeedKmhValue: Float = 0f

    /** Last decoded power-on distance in metres — see [powerOnDistanceMeters]. */
    private var powerOnMetersValue: Long = 0L

    /** True once a genuine (non-boot) live frame supplied speed and trip. */
    private var sawLiveMotion = false

    /**
     * The protocol's own receipt state for the 0x00/0x07 power join (see [observedBegodeController]): how many
     * 0x07 frames have been decoded since the last genuine live frame supplied a voltage. [NO_LIVE_FRAME] until
     * the first one (and again after [reset]). Counted in frames, not wall-clock: the frames carry no timestamps.
     */
    private var motionFramesSinceLive = NO_LIVE_FRAME

    /** Last decoded lifetime odometer in metres — see [odometerMeters]. */
    private var odometerMetersValue: Long = 0L

    /** True once any 0x04 frame arrived. */
    private var sawOdometer = false

    /**
     * Sum of the accepted odometer increments of this connection, metres: what [sessionTripKm] publishes.
     * Cleared by [reset] so a reconnect starts the trip over at 0.
     *
     * RETRACTION: this used to be `tripBaselineMeters`, the lifetime odometer at the connection's FIRST 0x04,
     * with the trip as `odometer - baseline`. That was wrong because a single torn first read (true + 65 536 m)
     * offset the trip by 65.5 km for the whole connection, and a later wheel power-cycle (odometer reset)
     * pinned it at 0 through `coerceAtLeast(0)`. The baseline is gone; see [sessionTripKm].
     */
    private var tripSumMeters: Double = 0.0

    /**
     * One 0x04 odometer reading as the [odometerGate] judges it. [powerOnM] is the power-on counter of the newest
     * genuine live frame when this 0x04 arrived (null when none had), [liveStamp] how many genuine live frames had
     * arrived by then: two points with different stamps have a live frame between them, so the counter moved
     * independently of the odometer and is a WITNESS; equal stamps mean the same stale counter, which proves nothing.
     */
    private class OdometerPoint(val meters: Long, val powerOnM: Long?, val liveStamp: Long, val frame: ByteArray, val arrivedAt: Long)

    /** Power-on counter of the newest genuine live frame; null before one (and after [reset]). */
    private var witnessPowerOnM: Long? = null
    private var liveStamp: Long = 0L

    /** Last accepted odometer increment in metres: R1's tolerance scales with it. */
    private var lastAcceptedStepM: Long = 0L

    private var odometerGate = newOdometerGate()

    /** How far R1's witness is believed on this connection; see [newOdometerGate]. Back to UNPROVEN on [reset]. */
    private enum class WitnessTrust { UNPROVEN, TRUSTED, DISTRUSTED }
    private var witnessTrust = WitnessTrust.UNPROVEN

    /** The last two 0x04 points in arrival order (offered or held back), for proof and suspicion. */
    private var arrivedLast: OdometerPoint? = null
    private var arrivedBeforeLast: OdometerPoint? = null

    /** TRUSTED-witness disagreements on a coherent odometer, held back from the gate; see [distrustWitness]. */
    private val suspects = ArrayList<OdometerPoint>()

    /** Points offered to the gate and not yet decided, and the last accepted one: a fresh gate is rebuilt from them. */
    private val pendingPoints = ArrayList<OdometerPoint>()
    private var lastAcceptedPoint: OdometerPoint? = null

    /**
     * The CONFIRMED 0x04 alert bitmap (byte 14) — see [parseOdometerFrame] and [confirmAlertBits]: a bit is in here
     * only once it was set in two consecutive 0x04 frames, and leaves it the moment a frame has it clear.
     */
    private var alertBitmap: Int = 0

    /** The raw byte 14 of the previous 0x04 frame (R12), 0 before one. */
    private var rawAlertPrevious: Int = 0

    /**
     * Per connection, how many set bits R12 swallowed: seen in one 0x04, clear in the next, never confirmed. Cumulative
     * (zeroed by [reset]) so a false positive of the rule, or a wheel flickering a bit, leaves a trace the rider can read
     * afterwards: it is 0 on a healthy wheel and a count that grows is the rule misfiring.
     */
    private var swallowedAlertBits: Long = 0L

    /**
     * When bit 0 (the wheel's PWM alarm) was FIRST set in a raw 0x04 of the current run, epoch ms; null while the
     * raw bit is clear. Published with the confirmed alarm as [ControllerData.wheelPwmAlarmSinceMillis] so the PWM
     * onset lookback is not moved one 0x04 later by the debounce (Ruling 1 of the plausibility plan).
     */
    private var pwmAlarmFirstSetAtMillis: Long? = null

    /**
     * The subset of [alertBitmap] that belongs in [ControllerData.faults] —
     * see [FAULT_BITS] for which bits those are and why the others are not.
     */
    private var faultsValue: List<String> = emptyList()

    /** Last decoded tiltback speed, or null when unset — see [tiltbackSpeed]. */
    private var tiltbackSpeedValue: Float? = null

    /** Last decoded battery current, A — see [batteryCurrentA]. */
    private var batteryCurrentAValue: Float = 0f

    /** Last decoded motor temperature, °C — see [motorTempC]. */
    private var motorTempCValue: Float = 0f

    /** True once any 0x07 frame arrived. */
    private var sawMotionFrame = false

    /**
     * The motor thermistor's own `truePWM` latch: true once the 0x07 motor
     * temperature field has been seen NON-ZERO at least once. See [motorTempC]
     * for why an unlatched field is withheld rather than published as 0 °C.
     */
    private var sawMotorTempEvidence = false

    /**
     * Consecutive genuine 0x07 frames whose motor-temperature word was non-zero (Task 7). [sawMotorTempEvidence]
     * latches only at [LATCH_CONFIRM_FRAMES]; a zero word breaks the run.
     */
    private var motorTempNonZeroRun = 0

    /** The same run for the duty word, feeding [sawTrueDuty]'s non-zero path (its live-motion path is unchanged). */
    private var dutyNonZeroRun = 0

    /** Last decoded hardware duty, percent — see [dutyPercent]. */
    private var dutyPercentValue: Float = 0f

    // --- Facts-only state: surfaced by [deviceFacts], feeds nothing else ---

    /**
     * Live-frame bytes 14..15 as the wheel sent them (signed word), from the last GENUINE live
     * frame; null until one. Never read by [dutyPercent] or any alarm — see [parseLiveFrame].
     */
    private var livePwmRawValue: Int? = null

    /** Last 0x07 duty word, raw and signed (the wheel's own number), null until a genuine 0x07. */
    private var motionDutyRawValue: Int? = null

    /**
     * The board temperature of the last GENUINE live frame. [boardTempC] itself is overwritten by
     * a later zero-padded live frame (its assignment sits outside the genuineness gate), and a
     * fact must not show that artefact (36.5 C from an all-zero payload) as a measurement.
     */
    private var boardTempFactC: Float? = null

    /** True once any framed notification was decoded; before it nothing is known, not even "no". */
    private var sawAnyFrame = false

    /** How [derivedCellCountValue] was proven; null while it is not. */
    private var seriesCellsProof: SeriesCellsProof? = null

    private enum class SeriesCellsProof(val token: String) {
        CELLS("cells"),
        BRANCH_RATIO("branch_ratio"),
    }

    /** [deviceFacts]'s cache: rebuilt only when [factsStamp] differs from [cachedStamp]. */
    private var cachedFacts: List<DeviceFact> = emptyList()
    private var cachedStamp = LongArray(FACTS_STAMP_SIZE) { Long.MAX_VALUE }
    private var factsStamp = LongArray(FACTS_STAMP_SIZE)

    /**
     * Whether this connection has proved that the 0x07 duty field is present.
     *
     * An earlier revision copied WheelLog's `truePWM` rule and required a
     * non-zero duty before publishing anything. The rider's stationary Begode
     * capture disproves that rule for this firmware: the wheel sends a valid
     * 0x07 frame with duty 0 while balancing, then rises above zero later. A
     * valid live frame followed by a motion frame is therefore enough evidence
     * to publish a genuine stationary 0 %, while an isolated synthetic stream
     * of zero-filled motion frames remains unknown.
     */
    private var sawTrueDuty = false

    /**
     * True once ANY smart-BMS frame (0x01/0x02/0x03) was decoded.
     *
     * **Retraction, 2026-09-28:** an earlier revision said the T4 likely
     * streamed only 0x00 and 0x04. The rider's 2026-09-27 capture from
     * `GotWay_59672`, configured as a Begode T4, contains repeated valid 0x01
     * and 0x07 frames as well. That model-based inference was wrong; decide
     * from frames actually observed, not the wheel name.
     *
     * Not every Begode has a smart BMS, and this flag decides between the two
     * modes: while false, pack 0 is synthesised from the live frame
     * ([liveData]); the first BMS frame retires the synthetic pack permanently
     * (until [reset]), so it can never override real branch data.
     */
    private var smartBmsSeen = false

    /**
     * The series-cell count [derivedCellCount] has PROVEN this connection, or
     * null until it has — see that accessor and [updateDerivedCellCount] for
     * how proof is decided. A constructor `val` never needed a slot like this
     * ([cellCount] just IS whatever the caller supplied), which is exactly why
     * this one is easy to miss: it must be cleared in [reset], beside
     * [sawTrueDuty] and [sawMotorTempEvidence], or a reconnect to a different
     * wheel would silently inherit the wrong series count.
     */
    private var derivedCellCountValue: Int? = null

    /**
     * True once [derivedCellCountValue] was proven from the 0x01 branch voltage against the live frame
     * (see [updateDerivedCellCountFromBranchVoltage]): that agreement is what certifies the 0x01
     * whole-pack voltage as real volts on this wheel, so history may record it.
     */
    private var branchVoltageCertified = false

    /** The voltage word of the newest GENUINE live frame (set inside the genuineness gate); R13's reference. 0 = none. */
    private var genuineLiveVoltageRaw = 0
    private var voltageRatioCandidate: Int? = null
    private var voltageRatioStreak = 0
    /** Any 0x02/0x03 page this connection: such a wheel proves its count from cells, never from the ratio. */
    private var cellPagesSeen = false
    private var branchTelemetryFrames = 0

    /**
     * The synthetic pack of a wheel without a smart BMS, rebuilt from every
     * genuine live frame: board temperature, battery current only after 0x07,
     * no cells — and `voltage = 0`, because the live-frame voltage is on the
     * 67.2 V scale and the scale factor needs a cell count this protocol must
     * not invent. Callers that know the cell count scale [liveVoltageOn672ScaleV].
     * A fresh instance per decode, same identity contract PackSampleGate
     * relies on for real branches.
     */
    private var liveData: BmsData? = null

    /**
     * The wheel's controller sample, rebuilt by [rebuildMotion] from every
     * frame that carries motion (0x00, 0x04, 0x07) and null until the first
     * one lands.
     *
     * Stored rather than assembled on demand in [latestMotion] because
     * [ru.sodovaya.volty.data.ble.MotionSampleGate] discriminates on instance
     * IDENTITY: a fresh object per read would look like a new decode on every
     * BLE notification, and a wheel that had gone quiet could never be seen to
     * go quiet. One new instance per contributing frame, the same contract
     * [VescProtocol] honours.
     *
     * Deliberately NOT rebuilt on the smart-BMS frames (0x01/0x02/0x03): they
     * carry no motion, and a new instance for each of them would push
     * duplicate motion samples through the gate at cell-frame rate.
     */
    private var motion: ControllerData? = null

    /** Per-branch decode state, assembled from 0x01 and 0x02/0x03 frames. */
    private class BranchState {
        /** True once at least one 0x01 frame for this branch was seen. */
        var sawTelemetry = false
        /**
         * Whole-pack voltage as reported in the branch's 0x01 frames, at the
         * frame's nominal 0.1 V/unit. Only a fallback for [branchVoltage] —
         * the field's real scale is ~0.1009 V/unit.
         */
        var packVoltageV = 0f
        /** Branch current (A), positive = charging. */
        var currentA = 0f
        /** Two temperatures per section, indexed [section * 2 + sensor]. */
        val sectionTemps = arrayOfNulls<Float>(4)
        /** Per-section voltage (V) — parsed for the pack-materialisation task. */
        val sectionVoltageV = FloatArray(2)
        /** Cell number -> voltage (V). Filled 8 cells per 0x02/0x03 frame. */
        val cells = mutableMapOf<Int, Float>()
        var lastData: BmsData? = null

        fun reset() {
            sawTelemetry = false
            packVoltageV = 0f
            currentA = 0f
            sectionTemps.fill(null)
            sectionVoltageV.fill(0f)
            cells.clear()
            lastData = null
        }
    }

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        tryParseAll()
    }

    override fun latestData(packIndex: Int): BmsData? {
        val branch = branches.getOrNull(packIndex) ?: return null
        branch.lastData?.let { return it }
        // No decoded branch data. A wheel without a smart BMS never produces
        // any — fall back to the pack synthesised from the live frame, but
        // ONLY while no BMS frame has ever been seen: the synthetic pack is a
        // fallback that yields the moment real frames arrive, never an
        // override. Pack 0 only — there is no evidence of a second branch.
        if (packIndex == 0 && !smartBmsSeen) return liveData
        return null
    }

    /**
     * Live-frame voltage in volts on Begode's 67.2 V reference scale, or null
     * when it must not be used: before the first genuine live frame, or as
     * soon as any smart-BMS frame proves the wheel has real branches.
     *
     * This is the ONE number the synthetic pack cannot publish honestly on
     * its own: pack volts are `this * nominal / 67.2`, and the nominal needs
     * a cell count the protocol does not know (a dumb wheel sends no cell
     * frames). The caller that knows the vehicle's cell count applies
     * [scaleLiveVoltage]; a caller that knows none must show no voltage
     * rather than a wrong one.
     */
    fun liveVoltageOn672ScaleV(): Float? =
        if (!smartBmsSeen && liveVoltageRaw > 0) liveVoltageRaw * 0.01f else null

    /**
     * Wheel speed in km/h from the live frame, or null when no genuine live
     * frame has been decoded yet (see [parseLiveFrame]'s boot gate).
     *
     * Null, never 0f: a zero here is a real measurement — a wheel standing
     * still — and a caller that cannot tell it from "not measured" would
     * publish a confident 0 km/h for a wheel that has said nothing. Absent is
     * absent (spec §7.1).
     *
     * ### A MAGNITUDE, never negative — and the magnitude is taken at DECODE
     *
     * The frame field is signed (see [SPEED_KMH_PER_UNIT]), and this used to
     * promise "NEGATIVE while the wheel rolls backwards". The first real ride
     * refuted the premise that made that promise harmless: on the rider's ET Max
     * **and** EXN, riding **forward** reads NEGATIVE and rolling backwards reads
     * positive (field report `2026-07-30-first-hardware-test` S1). So the sign is
     * not a direction anyone can act on — it is a per-firmware convention.
     *
     * The magnitude is taken here, in the decoder, for the identical reasons
     * [dutyPercent] gives one field over:
     *  - **every consumer treats speed as a non-negative quantity compared
     *    against UPPER thresholds** — `AlarmController`'s SPEED rule, the Ride
     *    dashboard's session maximum, `MotionAggregator`'s `maxOf` fold and both
     *    dial renderers, which draw it as a fraction of full scale. A negative
     *    number therefore fails SILENTLY in the worst possible direction: it nulls
     *    instant consumption (`RideMetrics`' `MIN_SPEED_KMH` guard rejects
     *    everything below 0.5), leaves the speed alarm unfireable and freezes the
     *    session peak, with no error anywhere. That is exactly what the rider saw;
     *  - **negation would not fix it.** WheelLog exposes the polarity as a
     *    per-wheel three-way preference precisely because it varies by firmware
     *    and motor wiring, and its own DEFAULT is `Math.abs`. Negating fixes these
     *    two wheels and breaks the next rider's into the same silent failure;
     *  - **in the decoder, not in [rebuildMotion]**, so the convention is a
     *    property of the decode and a second consumer of this protocol cannot
     *    acquire the field without it. That is where
     *    [ru.sodovaya.volty.data.bms.vesc.VescValues] states the same contract for
     *    duty (`abs(duty) * 100`), which is the precedent this follows.
     *
     * **The two protocols now agree about this field.** This KDoc claimed they did
     * before it was true, then retracted it: `VescValues` published a **signed**
     * speed on both of its paths, so every consequence listed above stayed live for
     * a reversing VESC. `I` Task 10 closed that half — both VESC sources take the
     * magnitude at their own assignment into `ControllerData.speedKmh` — so
     * "non-negative" is now the SHARED field's contract and not merely this
     * decoder's. The one asymmetry left is where the direction survives: VESC keeps
     * it on `ControllerData.eRpm`, which a Begode never reports, which is why only
     * this decoder needed [signedSpeedKmh].
     *
     * No rider-facing polarity preference is offered: nothing in Volty consumes
     * the direction, so the setting would exist only to let a rider break their
     * own dashboard.
     *
     * The direction is not discarded — see [signedSpeedKmh].
     */
    fun speedKmh(): Float? = if (sawLiveMotion) speedKmhValue else null

    /**
     * The same reading with the frame's own SIGN kept — negative for whichever
     * direction this particular firmware calls negative. Null on the same gate as
     * [speedKmh].
     *
     * **This exists to RETAIN the sign, not to test it.** [speedKmh] discards
     * information the frame carried, and the sign is the one part of a signed field
     * that `abs` cannot be undone from. Keeping it means a later part that wants a
     * reverse indicator starts from a decode that works, instead of having to
     * revisit this decoder to recover a bit that was thrown away for a contract it
     * was never needed by. (It is not needed to make the signedness *testable*:
     * an unsigned read of a gentle roll answers 2340 km/h through [speedKmh] too,
     * so that decode is pinned either way.)
     *
     * **Decoded but deliberately unsurfaced**, the same shape as
     * [powerOnDistanceMeters], [tiltbackSpeed] and [wheelAlerts]: it has no
     * production caller and must not acquire one by accident.
     *
     * A consumer that ever does want the direction must first answer the question
     * the field report leaves open: which sign means forward is per-firmware, so
     * a reverse indicator needs evidence, not this value alone.
     */
    internal fun signedSpeedKmh(): Float? = if (sawLiveMotion) signedSpeedKmhValue else null

    /**
     * The wheel's OWN distance counter in METRES, reset by the wheel when it is
     * powered on, from the live frame's bytes 8..9 — or null before any genuine
     * live frame, same reasoning as [speedKmh].
     *
     * **This is NOT [ControllerData.tripKm]**, and the name says so. That field
     * means "distance since this CONNECTION started" everywhere else in Volty
     * ([VescProtocol], [VescGatewayProtocol], the demo simulator), which is the
     * semantics `B1` chose and which `RideMetrics.sessionWhPerKm` — a session's
     * Wh over a session's km — depends on. This counter answers a different
     * question: it keeps running across app disconnects, so connecting mid-ride
     * at km 30 would have shown a one-second-old session reading 30.0 km. It is
     * also 16-bit, so it WRAPS at 65 535 m and the tile would silently reset to
     * 0.0 at 65.5 km. [sessionTripKm] derives the real trip from the odometer
     * instead, which disposes of both.
     *
     * Kept decoded because it is the only thing that pins bytes 8..9 against the
     * neighbouring word at 6..7 (see [parseLiveFrame]), and because a wheel's
     * own since-power-on distance is a real number a later part may want to
     * show beside the session's.
     *
     * Metres, not kilometres, because metres is what the frame carries; the
     * unit conversion is a presentation concern.
     */
    fun powerOnDistanceMeters(): Long? = if (sawLiveMotion) powerOnMetersValue else null

    /**
     * Lifetime odometer in METRES from the 0x04 frame, or null before one
     * arrives. 0 is a legitimate reading (a wheel out of its box), so it
     * cannot double as "unknown".
     *
     * This is why the 0x04 frame is decoded onto the protocol rather than into
     * [BmsData]: an odometer is not a property of a battery pack, and BmsData
     * has no field for one. A MotionSource surfaces it.
     */
    fun odometerMeters(): Long? = if (sawOdometer) odometerMetersValue else null

    /**
     * Distance in KM since this connection started — what
     * [ControllerData.tripKm] means across every protocol Volty speaks, and
     * therefore what this wheel must publish into it.
     *
     * Derived from the lifetime [odometerMeters] against a baseline taken at
     * this connection's first genuine 0x04 frame, exactly as [VescProtocol]
     * does it from `tachometer_abs`. Three things follow, and all three are the
     * reason the wheel's own counter ([powerOnDistanceMeters]) is not used:
     *  - **it is a session.** `RideMetrics.sessionWhPerKm` divides a session's
     *    consumed Wh by this; the wheel's power-on counter would make the
     *    numerator and the denominator describe different rides;
     *  - **it starts at 0.** Connecting mid-ride at km 30 reads 0.0 km, not
     *    30.0 km on a session one second old;
     *  - **it cannot wrap.** The odometer is a 32-bit metre count, so the
     *    65 535 m wrap of the 16-bit power-on field is gone.
     *
     * 0 — not null — before the first 0x04 frame: [ControllerData.tripKm] is a
     * non-nullable float and 0 is also the honest reading for a session that
     * has not moved. The odometer fills in within a second of connecting.
     *
     * RETRACTION (telemetry-plausibility Task 4). An earlier revision derived this as `odometer - baseline`
     * (baseline = the first 0x04 of the connection) with `coerceAtLeast(0)`, and said the odometer "cannot be
     * wrong". That was wrong: the wheel's 0x04 odometer is sometimes read torn (true + 65 536 m, a bit-16 flip), so
     * a torn FIRST frame offset the trip by 65.5 km for the whole connection and a later wheel odometer reset
     * pinned it at 0. The trip is now the SUM OF ACCEPTED INCREMENTS of the odometer as judged by the
     * [odometerGate] (witnessed by the live frame's power-on counter, rule R1): a needle adds nothing, a
     * confirmed re-baseline adds only its chain's internal progress, and the sum never decreases.
     */
    private fun sessionTripKm(): Float = (tripSumMeters / 1000.0).toFloat()

    /**
     * Gate for the lifetime odometer, rule R1 (witness) with the counter re-baseline.
     *
     * The witness: between two 0x04 frames with a genuine live frame between them the wheel's power-on counter
     * (live bytes 8..9, u16, metres) advances by exactly the odometer's step: `odometer - powerOn` was constant
     * in all three field dumps (44 + 105 + 118 frames). A torn 0x04 breaks that by 65 536 m, so
     * `agrees` = `|dOdo - dPowerOn| <= max(40 m, 2 x last accepted step)`, `dPowerOn` taken modulo 65 536 (the u16
     * wraps). 40 m is a witness tolerance, not a speed limit: it covers the ~100 ms between the 0x00 and 0x04
     * samples at the dumps' <= 7 m/frame with a x5 margin.
     *
     * The witness is UNAVAILABLE (and the gate falls back to "monotone, decided one 0x04 late") when no live
     * frame arrived between the two points, when the earlier point had none, or after a power-on reset of the
     * counter (`W_b < 100 && dW > 1000`: the wheel restarted its counter, which proves nothing about the odometer).
     * No metre bound is applied there (rider ruling 2026-10-27): the price is one 0x04 (~310 ms) of lag.
     *
     * False-positive risk: R1 never fired on the 267 real frames. The ET Max / EXN boards are unmeasured, so the
     * witness is only believed after it has earned it ([WitnessTrust]):
     *  - UNPROVEN (every connection starts here): the monotone lookahead judges. The witness becomes TRUSTED the
     *    first time it AGREES on a step where both the odometer and the counter moved.
     *  - TRUSTED: R1 judges as above.
     *  - DISTRUSTED: a TRUSTED witness that disagreed on [R1_DISTRUST_AFTER] consecutive 0x04 frames whose odometer
     *    steps were themselves monotone (the odometer is coherent, the witness is not) is dropped for the rest of
     *    the connection; those frames are re-decided by the monotone path and credited, and one
     *    [LedgerKind.WITNESS_DISTRUSTED] note is written ([odometerWitnessDistrusted]). A torn needle (one
     *    disagreement, then agreement) never gets here.
     * Residuals (review of Task 4): (a) while the monotone path judges (UNPROVEN, DISTRUSTED, or no live frame
     * between two 0x04), two identical torn reads in a row or a persistent forward jump ARE credited to the trip
     * in full: only a needle that returns is caught there. (b) A first 0x04 that arrives before any live frame has
     * no counter reading, so a first read torn DOWN is not caught by R1. (c) Distrust counts only CONSECUTIVE
     * disagreements; a counter that disagrees on alternate frames would still see chains that never confirm (no
     * realistic counter does this; [rejectedFrameCount] would climb).
     * The earlier revision of this task had no trust state; on a wheel whose counter does not track the odometer
     * every frame was rejected and the odometer and trip FROZE. That was wrong: a silent frozen trip is worse than
     * an unfiltered needle.
     */
    private fun newOdometerGate() = CounterStepGate<OdometerPoint>(
        value = { it.meters.toDouble() },
        agrees = { a, b ->
            val dW = witnessStep(a, b)
            if (dW != null) agreesWithWitness(a, b, dW) else b.meters >= a.meters
        },
        immediate = { a, b -> witnessStep(a, b) != null },
    )

    /**
     * The power-on counter's advance from [a] to [b] when the witness is TRUSTED and usable, else null (the gate
     * then falls back to the monotone lookahead). See [newOdometerGate] and [WitnessTrust].
     */
    private fun witnessStep(a: OdometerPoint, b: OdometerPoint): Long? =
        if (witnessTrust == WitnessTrust.TRUSTED) rawWitnessStep(a, b) else null

    /** Whether the odometer step [a] -> [b] agrees with the counter's advance [dW], within R1's tolerance. */
    private fun agreesWithWitness(a: OdometerPoint, b: OdometerPoint, dW: Long): Boolean =
        abs((b.meters - a.meters) - dW) <= max(R1_TOLERANCE_FLOOR_M, 2 * lastAcceptedStepM)

    /** Does ([a], [b]) PROVE the counter tracks the odometer: a witnessed, non-zero step on both signals that R1 agrees with. */
    private fun provesWitness(a: OdometerPoint?, b: OdometerPoint): Boolean {
        a ?: return false
        val dW = rawWitnessStep(a, b) ?: return false
        return dW > 0 && b.meters > a.meters && agreesWithWitness(a, b, dW)
    }

    /** A coherent odometer step (monotone) that a usable witness nevertheless contradicts. */
    private fun isSuspect(a: OdometerPoint, b: OdometerPoint): Boolean {
        val dW = rawWitnessStep(a, b) ?: return false
        return b.meters >= a.meters && !agreesWithWitness(a, b, dW)
    }

    /** The counter's advance regardless of trust, or null when there is no usable pair. */
    private fun rawWitnessStep(a: OdometerPoint, b: OdometerPoint): Long? {
        val wa = a.powerOnM ?: return null
        val wb = b.powerOnM ?: return null
        if (a.liveStamp == b.liveStamp) return null
        val dW = ((wb - wa) % 65_536L + 65_536L) % 65_536L
        if (wb < R1_POWER_ON_RESET_BELOW_M && dW > R1_POWER_ON_RESET_JUMP_M) return null
        return dW
    }

    private fun odometerText(p: OdometerPoint): String = "${p.meters} m"

    /**
     * BATTERY current in amperes from the 0x07 frame, or null before one
     * arrives.
     *
     * This is a different quantity from the phase current the battery path
     * already publishes (live frame, bytes 10..11): on the ET Max capture the
     * battery draws −0.67 A while the phase current reads −3.00 A, which is
     * what a balancing wheel looks like. Both are real, both are wanted
     * (`batteryCurrentA` and `motorCurrentA` in spec §2), and neither is a
     * better version of the other — so this decode leaves the battery path
     * completely alone.
     *
     * Sign follows the negation WheelLog applies, and the capture confirms the
     * negation is right for Volty's convention: negated, the battery current
     * has the SAME sign as the phase current of the same frames (both
     * negative, an idle wheel drawing down its pack). Unnegated it would claim
     * the pack was charging while the phase current said it was discharging.
     */
    fun batteryCurrentA(): Float? = if (sawMotionFrame) batteryCurrentAValue else null

    /**
     * MOTOR temperature in °C from the 0x07 frame, or null while the wheel has
     * not proved it has a motor thermistor at all.
     *
     * The ET Max reads 20 °C here against 27.5 °C on the mainboard sensor, so
     * this wheel plainly HAS one — spec §2's "wheels usually expose one board
     * temp, `hasMotorTemp = false`" does not hold for it, and
     * [ControllerData.hasMotorTemp] is set from whether this returns null
     * rather than from the spec's prose.
     *
     * **Null until the field has been seen non-zero once**
     * ([sawMotorTempEvidence]), which is the same latch [dutyPercent] applies
     * to the hardware PWM and for the same reason. Frame arrival alone is not
     * evidence of a sensor: a wheel whose motor thermistor is unwired or
     * unfitted still emits 0x07 frames, with 0 in this field, and
     * `hasMotorTemp = sawMotionFrame` would then CLAIM the sensor and arm Part
     * F's MOTOR_TEMP alert against a constant zero — spec `D §7.1` one level
     * up from the ESC-temperature case, an alarm shown as armed that can never
     * fire.
     *
     * **A range gate cannot decide this and none is used.** 0 °C is a real
     * winter reading, so any threshold either rejects genuine cold or accepts a
     * dead sensor; there is no value that separates them. A LATCH separates
     * them over time instead, and the asymmetry of the two mistakes is what
     * makes it the right trade:
     *  - **cost** — a wheel genuinely reading **exactly** 0 °C reports no motor
     *    sensor until the reading steps off zero. The window is narrow: the
     *    field is SIGNED and *any* non-zero value latches, so -1 °C latches
     *    instantly and only the single value 0 withholds, clearing on the first
     *    integer step in either direction. And a motor-overheat alarm is
     *    precisely the alarm that cannot matter at freezing point;
     *  - **benefit** — a stuck-at-zero sensor is caught forever, instead of
     *    arming an alarm that can never sound for the life of the wheel.
     * Once latched the flag stays latched, so a later genuine 0 °C IS published
     * and the alert stays armed: this withholds an unproven sensor, it does not
     * filter readings.
     *
     * **What the rider sees during that window, which is more than the alarm.**
     * [ControllerData.hasMotorTemp] is read by four places, not one:
     * `ClassicDialSpecs.kt:538-543`, `RideDashboardScreen.kt:367-368` and
     * `SecondaryGaugeMapper.kt:90-92` all render `—`/UNKNOWN and drop the
     * severity band, so the motor-temperature gauge **blanks**; and the alerts
     * screen greys MOTOR_TEMP with *"This controller has no motor temperature
     * sensor"* — a claim about hardware, made for what may be a transient. That
     * is still the better failure than a gauge and an alarm confidently tracking
     * a constant zero, but it is a visible one and worth knowing before someone
     * files it as a bug.
     *
     * What it does NOT catch is a sensor stuck at some other constant (a shorted
     * probe reading -1, say). Only the stuck-at-zero case has evidence behind it
     * — an unwired field reads 0 — and inventing a variance test for the rest
     * would be guessing about hardware nobody here has seen.
     */
    fun motorTempC(): Float? =
        if (sawMotionFrame && sawMotorTempEvidence) motorTempCValue else null

    /**
     * The wheel's own alarm bitmap from the 0x04 frame, as rider-readable
     * labels — **every** set bit, including the ones [ControllerData.faults]
     * deliberately does not carry (see [FAULT_BITS]). Empty when the wheel is
     * reporting nothing and equally empty before the first 0x04 frame; the two
     * are not distinguished because no caller acts differently on them.
     *
     * **Decoded but unsurfaced**, like [tiltbackSpeed]: the four non-fault bits
     * have no home on [ControllerData] and no consumer, and this accessor is
     * where they are readable without inventing one.
     *
     * `internal`, and it has no production caller today: its job is to make
     * "decoded, then filtered" a checkable claim rather than a comment, so the
     * tests that assert the excluded bits are still decoded have something to
     * assert against. It is not part of this class's public surface.
     */
    internal fun wheelAlerts(): List<String> = alertLabels(alertBitmap)

    /**
     * The wheel's configured **tiltback speed**, from the 0x04 frame bytes
     * 10..11, or null when it is unset.
     *
     * **Deliberately unitless in the name.** The same 0x04 frame carries an
     * **in-miles** flag in its settings word (bytes 6..7, bit 0), and this
     * field is the wheel's own setting expressed in whatever unit the wheel is
     * configured for — so a `Kmh` suffix would be an unearned unit, and this
     * accessor used to carry one. The flag is **not decoded**: with nothing
     * consuming the value it would buy no verifiable behaviour, and the capture
     * cannot exercise it (that wheel has the flag clear *and* the field unset).
     * **Anything that surfaces this number must decode that flag first.**
     *
     * WheelLog treats a value ≥ 100 as unset, and the ET Max capture reads
     * exactly 200 in all 38 of its 0x04 frames — so on the only wheel we have,
     * this is unset and the decode is pinned by synthetic frames. 0 is treated
     * as unset too: a tiltback at zero speed would mean a wheel that tilts back
     * standing still.
     *
     * **Decoded but NOT surfaced on [ControllerData], deliberately**, and the
     * unit above is only the third reason:
     *  - there is **no field for it**. [ControllerData] is shared with VESC,
     *    Kelly and FarDriver, and "the speed at which the controller starts
     *    pushing back" is not a concept the other three have. Adding a field to
     *    a shared type for one protocol's setting is not this task's call;
     *  - there is **no consumer**. No dashboard tile, gauge or alert reads a
     *    tiltback speed today, so a field would be written and never read.
     *
     * `internal` for the same reason as [wheelAlerts]: no production caller, and
     * it exists so the decode is testable rather than merely asserted in a
     * comment. If a later part wants it on the dashboard, it starts from a
     * decode that already works and a written-down list of what is still open.
     */
    internal fun tiltbackSpeed(): Float? = tiltbackSpeedValue

    /**
     * The wheel's HARDWARE duty in percent (0..100), or null while the wheel
     * has not proved it reports one.
     *
     * Duty is the safety number for a EUC — Part F's headline alarm fires on
     * it — and spec §7.2 requires it to be real or absent, never a
     * placeholder. This is the real one: the wheel's own firmware measurement,
     * not a derivation. The capture reads 2 % on a stationary balancing wheel,
     * which is exactly what a wheel holding itself upright spends.
     *
     * A non-zero field proves duty immediately. For this wheel's stationary
     * firmware, a valid live frame followed by a 0x07 frame also proves that a
     * zero is a real balancing measurement; this is why the first stationary
     * sample can be configured without moving the wheel. An isolated stream
     * of zero-filled motion frames remains null because it has no session
     * context to distinguish an absent field.
     *
     * ### The 0..100 above is enforced, not merely promised
     *
     * The frame field is SIGNED and unbounded, and the only capture we have
     * reads a constant `0x0002` — so nothing in it pins the sign, in either
     * direction. Every consumer of [ControllerData.dutyPercent] treats it as a
     * non-negative magnitude compared against UPPER thresholds (`DutyBands`,
     * `AlarmController`, `MotionAggregator`'s `maxOf` fold, both dial
     * renderers), and a wheel reporting negative hardware PWM under regen would
     * grade level 0, fill the dial backwards and leave the ШИМ alarm silent at
     * the exact moment a EUC's duty peaks.
     *
     * **So the magnitude is taken at DECODE** — `abs`, then clamped to
     * [DUTY_MAX_PERCENT] — in [parseMotionFrame], not folded into
     * [rebuildMotion]. That is where [ru.sodovaya.volty.data.bms.vesc.VescValues]
     * establishes the same contract for VESC (`abs(duty) * 100`), and this
     * field's meaning is cross-protocol: one convention, stated once per
     * decoder, is what stops the two disagreeing about what "duty" is. The
     * latch reads the RAW value first, so a wheel that only ever reports
     * negative PWM still proves it reports duty.
     *
     * The clamp's failure direction is deliberate: an out-of-range positive
     * (garbage — this format has no checksum) becomes 100 %, which raises the
     * alarm rather than suppressing it. Duty only ever escalates upwards, so
     * over-firing is the safe error and under-firing is the dangerous one.
     *
     * No derivation is offered when the wheel reports nothing: WheelLog's
     * `calculatePwm()` fallback needs rider-configured rotation-speed,
     * rotation-voltage and power-factor constants that Volty does not have,
     * and a guess in this field is worse than an honest absence.
     *
     * **Retraction (2026-10-27): "no derivation is offered" is superseded for the one wheel that proved
     * it.** A T4 reports no PWM at all (bytes 14..15 of its live frame stay `0x0080` from 0 to 81 km/h; the
     * 0x07 duty field stays 0). For a wheel proven never to report duty, the app now offers a per-wheel
     * ESTIMATE ("~PWM", `domain/pwm`): calibrated on that wheel by a free-spin wizard or by the wheel's own
     * alarm onsets - never WheelLog's rider-typed constants, never a constant per model. It is a SEPARATE
     * value: this field and [ControllerData.hasDuty] stay exactly what the wheel measured, and an estimate
     * is never written into them.
     */
    fun dutyPercent(): Float? = if (sawTrueDuty) dutyPercentValue else null

    // --- MotionSource: the wheel as a controller ---

    /** A wheel is one motor on one board. */
    override val controllerCount: Int get() = 1

    /**
     * The wheel's controller sample, or null before any frame carrying motion
     * has been decoded. See [rebuildMotion] for what every field means and for
     * the three places where [ControllerData]'s non-nullable floats cannot say
     * "not measured".
     */
    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    /**
     * Assemble the controller sample from whatever the wheel has said so far.
     *
     * The nullable accessors above are the truth; this maps them onto
     * [ControllerData], whose floats are not nullable. Where a field has an
     * agreed "absent" encoding it is used; where it has none, the choice is
     * stated here because it is not obvious and it is safety-relevant.
     *
     * **Sign.** The accessors above speak the BATTERY convention this file has
     * always used for [BmsData.current] (positive = charging); [ControllerData]
     * speaks VESC's (positive = drawing from the pack, see
     * `VescProtocol.synthesiseBattery`, which negates in the other direction).
     * Both currents are therefore negated here, and the capture is what makes
     * that safe to do in one step: battery and phase current are BOTH negative
     * in the same frames of an idle wheel, so one convention covers both.
     *
     * **Temperatures.** [escTempC] is the mainboard MPU reading of the live
     * frame and [motorTempC] the 0x07 motor thermistor — genuinely two
     * sensors, 27.5 °C against 20 °C in the same seconds. Either may be absent
     * (no live frame yet, no 0x07 frame yet), and absence is written as
     * [NO_TEMP_SENSOR_C], never 0f: [ControllerData.hasEscTemp] is computed as
     * `escTempC > -50f`, so a 0f default would CLAIM a sensor that has not
     * reported and arm Part F's ESC_TEMP alert against a constant zero
     * (`D §7.1`). [ControllerData.hasMotorTemp] is a stored flag and is set
     * from what this wheel actually sends — `true` for the ET Max, against
     * `D §2`'s prose that wheels expose only a board temperature.
     *
     * **Duty in the not-yet-known window.** [dutyPercent] is null only until
     * the hardware PWM field is evidenced by a non-zero value or by a valid
     * stationary 0x07 frame in an established live session. That window is NOT
     * the same statement as 0 %. [ControllerData.dutyPercent] still cannot say
     * it — of the representable values this publishes **0f**, deliberately not
     * a negative sentinel — so the statement travels beside it, on
     * [ControllerData.hasDuty], which Part D Task 4 added for exactly this and
     * which `MotionAlertAvailability`'s DUTY branch reads as its observed
     * layer. Why the number itself stays 0:
     *  - every consumer treats duty as a non-negative 0..100 magnitude compared
     *    against UPPER thresholds — `DutyBands.level`, `AlarmController`,
     *    `MotionAggregator`'s `maxOf` fold, and the Ride gauges, which render it
     *    as "N %" and as a fraction of 100. A sentinel below zero would show a
     *    rider a negative duty and a negative gauge fill, which is a visible
     *    lie, and would buy no alarm safety that 0 does not already have;
     *  - 0 is alarm-NEUTRAL: duty only ever escalates upwards, so an unknown
     *    published as 0 can never RAISE a false alarm. It can only fail to
     *    raise one — and in this window there is by construction no duty
     *    measurement to raise it from;
     *  - `D §7.2` prescribes exactly this value for an absent duty. What it
     *    forbids is an APPROXIMATION in the field (the live frame's fallback
     *    PWM, a constant 16.9 % on a wheel that never moved); see
     *    [parseLiveFrame], which refuses it.
     *
     * An earlier revision recorded that choosing 0 made the latch INVISIBLE
     * here —
     * `dutyPercent() ?: 0f` and the raw `dutyPercentValue` were the same number
     * at every instant, so no test of this class could tell the latch from its
     * absence, and the residual risk was a firmware that never fills the PWM
     * field leaving duty at 0 forever while `reportsDuty[BEGODE] = true` kept
     * the ШИМ alert armed against that constant. **[hasDuty] closed both for
     * the old non-zero-only rule.** The stationary capture is the retraction:
     * this wheel does report a meaningful 0 % and must expose it before motion.
     * It is the same latch, published where a caller can act on it, and
     * `availabilityFor` now answers DUTY `Unavailable` on a sample that carries
     * it false instead of arming an alarm that could never fire. On the ET Max
     * the latch closes inside the first 0x07 frame — a balancing wheel spends
     * 2 % standing still — while the replay begins with several genuine 0 %
     * samples — so the guard remains for firmware nobody here has seen.
     *
     * **Absent distances.** [odometerKm] and [tripKm] read 0 before the frames
     * that carry them arrive. 0 is also a real reading (a wheel out of its box,
     * a trip that has not started), so this one genuinely cannot be
     * distinguished — [ControllerData] has no encoding for it and inventing a
     * negative distance would be worse. Both fields fill in within a second of
     * connecting.
     *
     * **[tripKm] is the SESSION distance**, the sum of the accepted odometer increments (see [sessionTripKm]; an
     * earlier revision took it against a first-0x04 baseline, retracted there) — the same thing
     * [VescProtocol] publishes and the meaning `RideMetrics.sessionWhPerKm`
     * requires. The wheel's own since-power-on counter is decoded and kept, but
     * it is not this field: see [sessionTripKm] and [powerOnDistanceMeters].
     *
     * **Faults** come from the 0x04 alert bitmap, filtered to the bits that are
     * actually faults — see [FAULT_BITS], which is where the filter is argued.
     * [eRpm] stays 0 — a Begode reports no eRPM at all, and the wheel reports
     * GROUND speed directly, so [SpeedSource] is REPORTED and no `MotorConfig`
     * is involved.
     */
    private fun rebuildMotion(dutyFresh: Boolean = false) {
        val speed = speedKmh()
        val batteryCurrent = batteryCurrentA()
        val motorTemp = motorTempC()
        val voltage = inputVoltageOrNull()
        // Negated into the controller convention — see the KDoc on sign.
        val controllerBatteryCurrent = if (batteryCurrent != null) -batteryCurrent else 0f
        motion = ControllerData(
            speedKmh = speed ?: 0f,
            // REPORTED only for a sample that HAS a speed. A wheel that has
            // sent a 0x07 but no live frame has reported no speed yet, and
            // NONE + 0f is how VescValues.decodeValues says the same thing.
            speedSource = if (speed != null) SpeedSource.REPORTED else SpeedSource.NONE,
            dutyPercent = dutyPercent() ?: DUTY_NOT_YET_REPORTED_PERCENT,
            // The latch, finally said out loud. `dutyPercent` above cannot
            // carry it (0 is both "not yet reported" and a real 0 %), so this
            // flag is what stops Part F arming the ШИМ alarm against a
            // placeholder — see [ControllerData.hasDuty] and the KDoc below.
            hasDuty = dutyPercent() != null,
            // True only when this rebuild follows a 0x07 frame; a live/odometer rebuild republishes the old duty.
            dutyFresh = dutyFresh,
            // The live frame's PHASE current (bytes 10..11) — a different
            // quantity from the battery current, and both are wanted (D §6.3).
            // Read from [phaseCurrentA]/[boardTempC], which [parseLiveFrame]
            // owns and [parseMotionFrame] must never write: with this sample
            // built straight after a 0x07 frame, such a write is now OBSERVABLE
            // as a motion→motion leak, where before it was a dead store.
            motorCurrentA = if (sawLiveMotion) -phaseCurrentA else 0f,
            batteryCurrentA = controllerBatteryCurrent,
            inputVoltageV = voltage ?: 0f,
            // The same latch shape as hasDuty, one field over (G §9.3): a
            // missing cell count leaves no honest voltage, 0 V is what the
            // non-nullable field has to carry, and this is what stops the fold
            // averaging that 0 into a mixed vehicle's real rail.
            hasInputVoltage = voltage != null,
            // Voltage-derived, so it is exactly as unknown as the voltage: 0 W
            // whenever the cell count is missing. Spec D §2's `powerW =
            // voltage x current`. G §9: the dashboard used to render that 0 as
            // a confident "0.0 kW", so it now travels with a flag too.
            powerW = (voltage ?: 0f) * controllerBatteryCurrent,
            hasPower = voltage != null,
            escTempC = if (sawLiveMotion) boardTempC else NO_TEMP_SENSOR_C,
            motorTempC = motorTemp ?: NO_TEMP_SENSOR_C,
            hasMotorTemp = motorTemp != null,
            odometerKm = (odometerMeters() ?: 0L) / 1000f,
            // The SESSION delta, not the wheel's own power-on counter — see
            // [sessionTripKm] and [powerOnDistanceMeters].
            tripKm = sessionTripKm(),
            // A Begode's frames carry no energy counters at all — consumedAh,
            // consumedWh, regenAh and regenWh stay at their 0f defaults not
            // because nothing has been consumed but because nothing is
            // reported. Without this flag `RideMetrics.sessionWhPerKm` turns
            // that 0 into a well-formed "0.0 Wh/km" the instant the trip
            // counter moves, for the whole ride (G §9.1).
            hasEnergyCounters = false,
            faults = faultsValue,
            // Bit 0 of the 0x04 alert byte is the wheel's own PWM alarm (see [alertLabels]). Null until a
            // 0x04 frame has been seen: before it the bit is unknown, which is not the same as false.
            wheelPwmAlarm = if (sawOdometer) (alertBitmap and PWM_ALARM_BIT) != 0 else null,
            wheelPwmAlarmSinceMillis = if (sawOdometer && (alertBitmap and PWM_ALARM_BIT) != 0) pwmAlarmFirstSetAtMillis else null,
            isConnected = true
        )
    }

    /**
     * The series-cell count PROVEN by branch 0's own decoded cells this
     * connection, or null until the evidence is both COMPLETE and
     * self-consistent with the live frame — see [updateDerivedCellCount] for
     * how and why. The ET Max has a smart BMS whose two branches are
     * **parallel** (see the class KDoc), so branch 0's series-cell count IS
     * the pack's, with no vehicle profile involved at all — this is what
     * lets [inputVoltageOrNull] show a rail voltage on a wheel the rider never
     * configured, which is the whole point of this part: a wheel streaming
     * everything needed to know its own rail voltage was showing no power at
     * all because only a human-entered cell count was ever consulted.
     *
     * `internal`: [inputVoltageOrNull] is the one production caller, and this
     * exists so the derivation is independently testable rather than only
     * observable through the voltage it feeds.
     */
    internal fun derivedCellCount(): Int? = derivedCellCountValue

    /**
     * Learn [derivedCellCount] from branch 0's decoded cells, the moment a
     * candidate count is CONFIRMED and a second, independent check confirms
     * this wheel's live frame even follows the reference convention the
     * resulting voltage will be scaled by.
     *
     * **Gate 1 — count confirmation, [isCellCountConfirmed].**
     * [contiguousCells] legitimately returns a TRUNCATED run while packets are
     * still arriving — 8 of 40 mid-stream, or a half-filled last packet
     * leaving 36 of 40 — and `cells.size` alone would derive a wrong count and
     * scale the live frame to a confidently wrong voltage. That is the single
     * most likely way to get this task wrong, so nothing here trusts a raw
     * list size: [isCellCountConfirmed] requires `cellSum / frameVoltage` to
     * sit within an ASYMMETRIC band around **1.0** — tight
     * ([CELL_COUNT_LOWER_TOLERANCE]) below, so even ONE missing cell falls
     * outside it, and deliberately loose ([CELL_COUNT_UPPER_TOLERANCE]) above,
     * because nothing realistic pushes a genuine sum meaningfully above the
     * frame field. See that function's KDoc for the measured numbers and for
     * why the two edges are not the same size, and for why this is a
     * DIFFERENT, tighter judgement from [isCellSumComplete] — reusing that one
     * here was the bug an earlier version of this file had.
     *
     * **Gate 2 — the live-frame convention, NOT a second vote on the count.**
     * This gate used to be documented as "a second witness for the count that
     * is actually right", which review caught as false: writing the compare
     * out algebraically (`count * avgCellV / liveVoltageV` against
     * `count * FULL_CELL_V / LIVE_VOLTAGE_REFERENCE_V`) shows `count` is a
     * common factor on both sides of the comparison and cancels out
     * completely — this gate is mathematically INCAPABLE of preferring one
     * candidate count over another. A verified example: a 36-of-40 shortfall
     * disagrees with the live frame by the same ~0.85 % a genuinely complete
     * 40-cell reading does, because both are really testing the same thing —
     * whether the AVERAGE cell voltage, scaled by [LIVE_VOLTAGE_REFERENCE_V]
     * and divided by the live reading, comes back to [FULL_CELL_V] — and that
     * quantity does not depend on how many cells went into the average.
     *
     * What this gate DOES catch, and why it stays: gate 1 only ever compares
     * the 0x01 frame's OWN voltage field against the cells, and never looks at
     * the live frame at all — so a wheel whose live-frame reading does not
     * follow the 67.2 V / 16 S-equivalent convention [scaleLiveVoltage]
     * assumes would sail through gate 1 and then be scaled wrongly forever.
     * This gate is what refuses THAT wheel — a real, distinct failure mode,
     * just not a count witness.
     *
     * Proved once per connection and then left alone (the `if (... != null)
     * return` guard): a pack's series-cell count cannot change mid-ride, and
     * re-deriving on every later cell frame would only open a window for
     * transient noise to flip it. [reset] clears it for the next connection,
     * which may face a different wheel.
     */
    /**
     * The series count of a wheel that sends 0x01 branch frames but no cell pages (a Begode T4 with no
     * smart-BMS cells, measured 2026-10-23): its 0x01 frame carries the real pack voltage at 0.1 V,
     * the live frame the same voltage on the 67.2 V reference, so `16 × branch / live` IS the count
     * (90.8 / 60.53 × 16 = 24.0 on the rider's capture). Accepted only as a whole number within
     * [BRANCH_RATIO_TOLERANCE], in a plausible range, and on [BRANCH_RATIO_STREAK] agreeing frames in a
     * row: the two frames are not simultaneous, and under load their voltages can differ. A wheel whose
     * 0x01 voltage follows some other convention never yields a whole number and stays unproven.
     */
    private fun updateDerivedCellCountFromBranchVoltage(branchVoltageV: Float) {
        if (derivedCellCountValue != null) return
        // Only a wheel with no cell pages at all: an ET Max sends 0x01 before its first page, and its
        // count stays the cells' to prove (unchanged behaviour, pinned by its capture).
        if (cellPagesSeen || branchTelemetryFrames < BRANCH_RATIO_MIN_FRAMES) return
        if (liveVoltageRaw <= 0 || branchVoltageV <= 0f) return
        val ratio = 16f * branchVoltageV / (liveVoltageRaw * 0.01f)
        val count = kotlin.math.round(ratio).toInt()
        if (abs(ratio - count) > BRANCH_RATIO_TOLERANCE || count !in BRANCH_RATIO_CELLS) {
            voltageRatioCandidate = null; voltageRatioStreak = 0
            return
        }
        if (count == voltageRatioCandidate) voltageRatioStreak++ else { voltageRatioCandidate = count; voltageRatioStreak = 1 }
        if (voltageRatioStreak < BRANCH_RATIO_STREAK) return
        derivedCellCountValue = count
        seriesCellsProof = SeriesCellsProof.BRANCH_RATIO
        branchVoltageCertified = true
    }

    private fun updateDerivedCellCount(cells: List<Float>) {
        if (derivedCellCountValue != null) return
        // Hard-coded to branch 0 regardless of whose cells [cells] holds —
        // correct ONLY because `rebuild` gates every call into this function
        // to packIndex == 0 (see rebuild's KDoc). If that gate is ever
        // loosened, this line silently mixes another branch's cells with
        // branch 0's frame voltage.
        val frameVoltage = branches[0].packVoltageV
        // No separate `frameVoltage <= 0f` guard, unlike an earlier version of
        // this function: [isCellCountConfirmed]'s ratio is `cellSum /
        // frameVoltage`, and frameVoltage == 0f (before any telemetry, or a
        // zero-padded boot 0x01 frame — see parseBmsTelemetry's
        // isBootPlaceholder) makes that division +Infinity (cellSum > 0) or
        // NaN (cellSum == 0 too). Both fail the range check inside
        // [isCellCountConfirmed] by construction — Infinity is never `<=` a
        // finite upper edge, and NaN is never `>=` or `<=` anything — so the
        // empty/boot case is already refused without a separate early return.
        // Verified, not merely reasoned: removing an explicit guard here was
        // swept and the suite stayed green.
        //
        // Genuinely redundant with gate 2 below for the same reason, verified
        // rather than assumed: `cellSum / 0f` in Kotlin is +Infinity (IEEE
        // 754), not NaN, and `abs(Infinity - x) > y` is always true for
        // finite x, y — so gate 2 already refuses a missing live frame on its
        // own. Kept anyway: a reader should not have to re-derive that float
        // semantics to see that "no live frame yet" is refused.
        if (liveVoltageRaw <= 0) return
        val cellSum = cells.sum()
        if (!isCellCountConfirmed(cellSum, frameVoltage)) return
        val count = cells.size
        // Unreachable given isCellCountConfirmed just passed: an empty list
        // sums to 0f, and 0f / frameVoltage (> 0f, else the ratio above would
        // have been Infinity/NaN and already refused) is 0f — which fails
        // [isCellCountConfirmed]'s LOWER edge outright. Kept for defensive
        // clarity rather than relying on that chain.
        if (count <= 0) return
        val liveVoltageV = liveVoltageRaw * 0.01f
        val avgCellV = cellSum / count
        // The reduced, count-free form of the comparison the KDoc above
        // derives — written this way so the code states what it actually
        // tests (average cell voltage against the live frame's convention),
        // rather than dressing up a count-independent check as if it varied
        // with count.
        val impliedFullCellV = avgCellV * LIVE_VOLTAGE_REFERENCE_V / liveVoltageV
        if (abs(impliedFullCellV - FULL_CELL_V) > FULL_CELL_V * LIVE_FRAME_CONVENTION_TOLERANCE) return
        derivedCellCountValue = count
        seriesCellsProof = SeriesCellsProof.CELLS
    }

    /**
     * The wheel's rail voltage in real volts, or **null** meaning UNKNOWN —
     * published as `inputVoltageV = 0f` with `hasInputVoltage = false`, which is
     * `G §9`'s contract for a field whose type cannot carry an absence.
     *
     * The live frame reports voltage against Begode's 67.2 V reference (every
     * wheel pretends to be 16S), and turning that into volts needs the pack's
     * cell count. **Precedence, in this order:**
     *  1. **[derivedCellCount]** — proven from the wheel's OWN smart-BMS
     *     cells, when it has them (see [updateDerivedCellCount]). This is new:
     *     an ET Max reports everything needed to know its rail voltage with no
     *     vehicle profile at all, and the rider who found this wheel showing
     *     no power at all had exactly this data streaming past unused.
     *     WheelLog does the same thing (`GotwayAdapter.java:212`, gated on
     *     `getAutoVoltage() = pref && smartBmsCells > 0`, defaulting on).
     *  2. **[cellCount]**, the vehicle profile's count — today's only path,
     *     and still the fallback for a wheel with no smart BMS (a dumb wheel
     *     sends no cell frames, so [derivedCellCount] never proves anything).
     *  3. **Unknown.** With neither, the honest answer is to publish nothing:
     *     the raw reading would render as 58.92 V on a 168 V wheel, and a
     *     wrong voltage is worse than none because `powerW` is built from it
     *     and the dashboard shows both.
     *
     * **This is a NEW rule, not [smartBmsSeen] reversed.** That gate
     * (`liveVoltageOn672ScaleV()`, `retireSyntheticPack()`) exists to stop the
     * synthetic no-BMS *pack* from overriding real branch data once real
     * frames prove the wheel has them — the opposite direction from here.
     * This function has never taken that gate, because a controller's input
     * voltage is the wheel's own measurement of its rail and stays valid
     * whether or not a smart BMS is also reporting cells; what changes with
     * [derivedCellCount] is only WHICH cell count scales that measurement,
     * preferring the wheel's own proof over a profile that could be stale,
     * wrong, or simply never entered.
     *
     * **Known discontinuity, accepted rather than hidden.** The 0x01 frame's
     * pack-voltage field is ~0.1009 V/unit, not the 0.1 [parseBmsTelemetry]
     * decodes it at (measured on the ET Max capture; see [branchVoltage]).
     * [updateDerivedCellCount]'s gate 2 compares the cell sum against the LIVE
     * frame, not that field, but the live frame's own 67.2 V reference carries
     * a similar-sized error: on the ET Max capture the scaled live reading
     * settles at ~147.2 V against ~148.4 V of directly-summed cells, ~0.85 %
     * low. WheelLog inherits the same error and does not correct it either.
     * Acceptable versus absent — the rider's alternative was 0 W, not a
     * cleaner 147.2 V.
     */
    private fun inputVoltageOrNull(): Float? {
        if (!sawLiveMotion) return null
        val cells = derivedCellCount() ?: cellCount ?: return null
        if (cells <= 0) return null
        return scaleLiveVoltage(liveVoltageRaw * 0.01f, cells)
    }

    /**
     * The two physical assemblies of branch [packIndex], wired in series
     * (bmsnum's low bit — see [parseBmsTelemetry]).
     *
     * Reported only once BOTH assemblies' voltages have arrived in genuine
     * (non-boot) 0x01 frames: those voltages are the evidence everything else
     * is anchored to, and half a breakdown would be a guess. Boot-placeholder
     * frames leave [BranchState.sectionVoltageV] at 0, so a booting wheel
     * reports no sections rather than 0.0 V assemblies.
     *
     * [SectionState.cellRange] is filled ONLY when a split of the branch's
     * contiguous cell list is verified against the assembly voltages — see
     * [verifiedSplitCellCount]. The UI refuses ranges derived from list
     * arithmetic (`groupPackCells`), and this producer honours that contract:
     * no verified split — null ranges, and the dashboard degrades to a flat
     * cell list instead of mislabeling cells of the second assembly.
     */
    override fun sections(packIndex: Int): List<SectionState> {
        val branch = branches.getOrNull(packIndex) ?: return emptyList()
        val v0 = branch.sectionVoltageV[0]
        val v1 = branch.sectionVoltageV[1]
        if (v0 <= 0f || v1 <= 0f) return emptyList()
        val cells = contiguousCells(branch.cells)
        val split = verifiedSplitCellCount(cells, v0, v1)
        return listOf(
            SectionState(
                index = 0,
                voltage = v0,
                temperatures = listOfNotNull(branch.sectionTemps[0], branch.sectionTemps[1]),
                cellRange = split?.let { 0 until it }
            ),
            SectionState(
                index = 1,
                voltage = v1,
                temperatures = listOfNotNull(branch.sectionTemps[2], branch.sectionTemps[3]),
                cellRange = split?.let { it until cells.size }
            )
        )
    }

    /**
     * The number of leading cells that provably belong to the first assembly,
     * or null when no split of [cells] is confirmed by the reported voltages.
     *
     * The boundary is FOUND AND VERIFIED, never assumed: the split point is
     * wherever the running cell sum matches the first assembly's reported
     * voltage while the remainder matches the second's, both within
     * [SECTION_SPLIT_TOLERANCE_V]. A truncated cell list fails naturally —
     * its remainder cannot reach the second assembly's voltage — as does a
     * wheel whose cells are not two series assemblies in frame order. This is
     * what makes the feature model-agnostic: a 24-cell T4 branch resolves to
     * 12 + 12 by the same search, with no per-model layout hard-coded.
     *
     * The match must also be UNIQUE. With sane cells uniqueness is automatic
     * (moving the boundary by one cell shifts both sums by a whole cell
     * voltage, far beyond the tolerance), so a second fitting split means the
     * cell values are too degenerate to carry evidence — and no range beats a
     * coin flip.
     */
    private fun verifiedSplitCellCount(cells: List<Float>, v0: Float, v1: Float): Int? {
        if (cells.size < 2) return null
        val total = cells.sum()
        var match: Int? = null
        var prefix = 0f
        for (k in 1 until cells.size) {
            prefix += cells[k - 1]
            val fits = abs(prefix - v0) <= SECTION_SPLIT_TOLERANCE_V &&
                abs(total - prefix - v1) <= SECTION_SPLIT_TOLERANCE_V
            if (!fits) continue
            if (match != null) return null // Two fitting splits — no evidence either way.
            match = k
        }
        return match
    }

    /**
     * What this wheel has said, as keys and raw values - see [DeviceFactKey]. A fact is listed
     * only once the wire has said it: a wheel that has sent nothing reports an empty list.
     *
     * Per fact: odometer and power-on distance in km (exact metres, three decimals); tiltback
     * only when set (the wheel's "unset" marker is not a speed); alerts only when a bit is up
     * (the labels of [wheelAlerts], comma-joined); board temperature of the last genuine live
     * frame; `LIVE_PWM_RAW` as the raw integer plus, at `index = 1`, raw x 0.1 as percent;
     * `MOTION_DUTY_RAW` the signed 0x07 word as sent (the shared duty is its magnitude);
     * `SERIES_CELLS_PROOF` is `cells` (branch 0's own cell pages summed against the frame),
     * `branch_ratio` (the 0x01 branch voltage against the live frame, no cell pages), or `none`;
     * `SERIES_CELLS` the proven count, absent while the proof is `none` (a profile's configured
     * count is not a proof); `REJECTED_FRAMES` / `ODOMETER_REBASED` the per-connection ledger counts (0 on a healthy
     * wheel; listed once a 0x04 was seen), `REJECTED_LAST_RULE` the [PlausibilityRule.token] of this connection's newest
     * rejection and `REJECTED_LAST_DETAIL` its numbers ("reference / witness"), `ODOMETER_WITNESS` = `distrusted` only
     * when the power-on counter stopped tracking the odometer (Ruling 4); `SMART_BMS_SEEN` `true`/`false`; per decoded branch
     * `BRANCH_VOLTAGE_V` / `BRANCH_CURRENT_A` at `index = branch + 1` (current: + = charging).
     *
     * **Cached.** This is read after every notification, so the list is rebuilt only when the
     * rounded values that would be listed differ from the ones it was built from; an unchanged
     * wheel returns the very same instance. Not thread-safe, like the rest of this decoder.
     */
    override fun deviceFacts(): List<DeviceFact> {
        val s = factsStamp
        fun tenths(v: Float?): Long = if (v == null) Long.MIN_VALUE else kotlin.math.round(v * 10f).toLong()
        s[0] = if (sawOdometer) odometerMetersValue else Long.MIN_VALUE
        s[1] = if (sawLiveMotion) powerOnMetersValue else Long.MIN_VALUE
        s[2] = tiltbackSpeedValue?.toLong() ?: Long.MIN_VALUE
        s[3] = if (sawOdometer) alertBitmap.toLong() else Long.MIN_VALUE
        s[4] = tenths(boardTempFactC)
        s[5] = livePwmRawValue?.toLong() ?: Long.MIN_VALUE
        s[6] = motionDutyRawValue?.toLong() ?: Long.MIN_VALUE
        s[7] = seriesCellsProof?.ordinal?.toLong() ?: Long.MIN_VALUE
        s[8] = derivedCellCountValue?.toLong() ?: Long.MIN_VALUE
        s[9] = if (smartBmsSeen) 1L else 0L
        s[10] = if (sawAnyFrame) 1L else 0L
        for (i in 0 until 2) {
            val data = branches[i].lastData
            s[11 + i * 2] = tenths(data?.voltage)
            s[12 + i * 2] = tenths(data?.current)
        }
        s[15] = rejectedFrameCount
        s[16] = rebaseCount
        s[17] = if (odometerWitnessDistrusted) 1L else 0L
        s[18] = if (sawOdometer) swallowedAlertBits else Long.MIN_VALUE
        if (s.contentEquals(cachedStamp)) return cachedFacts
        cachedFacts = buildFacts()
        factsStamp = cachedStamp
        cachedStamp = s
        return cachedFacts
    }

    private fun buildFacts(): List<DeviceFact> = buildList {
        if (sawOdometer) add(DeviceFact(DeviceFactKey.ODOMETER_KM, kmText(odometerMetersValue), "km"))
        if (sawLiveMotion) add(DeviceFact(DeviceFactKey.POWER_ON_DISTANCE_KM, kmText(powerOnMetersValue), "km"))
        tiltbackSpeedValue?.let { add(DeviceFact(DeviceFactKey.TILTBACK_SPEED_KMH, factNumber(it, 0), "km/h")) }
        if (sawOdometer) {
            val labels = alertLabels(alertBitmap)
            if (labels.isNotEmpty()) add(DeviceFact(DeviceFactKey.WHEEL_ALERTS, labels.joinToString(", ")))
            // R12: set bits swallowed this connection (seen once, never confirmed); 0 on a healthy wheel, so a count
            // that grows is either a wheel flickering a bit or a false positive of this rule.
            add(DeviceFact(DeviceFactKey.UNCONFIRMED_ALERT_BITS, swallowedAlertBits.toString()))
        }
        boardTempFactC?.let { add(DeviceFact(DeviceFactKey.BOARD_TEMP_C, factNumber(it, 1), "\u00B0C")) }
        livePwmRawValue?.let {
            add(DeviceFact(DeviceFactKey.LIVE_PWM_RAW, it.toString()))
            add(DeviceFact(DeviceFactKey.LIVE_PWM_RAW, factNumber(it * 0.1f, 1), "%", index = 1))
        }
        motionDutyRawValue?.let { add(DeviceFact(DeviceFactKey.MOTION_DUTY_RAW, it.toString())) }
        if (sawAnyFrame) {
            add(DeviceFact(DeviceFactKey.SERIES_CELLS_PROOF, seriesCellsProof?.token ?: "none"))
            derivedCellCountValue?.let { add(DeviceFact(DeviceFactKey.SERIES_CELLS, it.toString())) }
            add(DeviceFact(DeviceFactKey.SMART_BMS_SEEN, smartBmsSeen.toString()))
        }
        // The plausibility ledger: listed once a 0x04 has been seen (or a rule already held something back, which is
        // itself evidence), and 0 on a healthy wheel, so a counter that grows is visibly a false positive.
        if (sawOdometer || rejectedFrameCount > 0 || rebaseCount > 0) {
            add(DeviceFact(DeviceFactKey.REJECTED_FRAMES, rejectedFrameCount.toString()))
            lastRejectedFrame?.let { last ->
                add(DeviceFact(DeviceFactKey.REJECTED_LAST_RULE, last.rule.token))
                listOfNotNull(last.reference, last.witness).takeIf { it.isNotEmpty() }
                    ?.let { add(DeviceFact(DeviceFactKey.REJECTED_LAST_DETAIL, it.joinToString(" / "))) }
            }
            add(DeviceFact(DeviceFactKey.ODOMETER_REBASED, rebaseCount.toString()))
            if (odometerWitnessDistrusted) add(DeviceFact(DeviceFactKey.ODOMETER_WITNESS, "distrusted"))
        }
        for (i in 0 until 2) {
            val data = branches[i].lastData ?: continue
            add(DeviceFact(DeviceFactKey.BRANCH_VOLTAGE_V, factNumber(data.voltage, 1), "V", index = i + 1))
            add(DeviceFact(DeviceFactKey.BRANCH_CURRENT_A, factNumber(data.current, 1), "A", index = i + 1))
        }
    }

    /** Exact kilometres from metres, three decimals ("8565.341"). */
    private fun kmText(meters: Long): String =
        (meters / 1000).toString() + "." + (meters % 1000).toString().padStart(3, '0')

    override fun reset() {
        buffer.reset()
        branches.forEach { it.reset() }
        observedCellPositions.forEach { it.clear() }
        liveVoltageRaw = 0
        phaseCurrentA = 0f
        boardTempC = 0f
        // Motion is per-connection state too: a reconnect may face a different
        // wheel, and the previous one's speed and mileage must not survive it.
        speedKmhValue = 0f
        // The signed half goes with it. Both are gated behind [sawLiveMotion],
        // cleared below, so neither clear is observable through any accessor
        // today — they are here because a value that outlives its connection is
        // one careless future read away from being the previous wheel's, and
        // this field's whole point is to be read later.
        signedSpeedKmhValue = 0f
        powerOnMetersValue = 0L
        sawLiveMotion = false
        motionFramesSinceLive = NO_LIVE_FRAME
        odometerMetersValue = 0L
        sawOdometer = false
        // The trip is per-CONNECTION by definition: a reconnect starts a new
        // session and must start its trip at 0, not carry the last one's sum.
        // The gate, the witness and the ledger counters go with it; the ledger's
        // ring does not (it is evidence, see [rejectedFrames]).
        tripSumMeters = 0.0
        odometerGate = newOdometerGate()
        witnessTrust = WitnessTrust.UNPROVEN
        arrivedLast = null
        arrivedBeforeLast = null
        suspects.clear()
        pendingPoints.clear()
        lastAcceptedPoint = null
        witnessPowerOnM = null
        liveStamp = 0L
        lastAcceptedStepM = 0L
        resetLedger()
        // A reconnect may face a healthy wheel: the previous one's alerts must
        // not survive it, or a cleared fault would keep firing.
        alertBitmap = 0
        rawAlertPrevious = 0
        swallowedAlertBits = 0L
        pwmAlarmFirstSetAtMillis = null
        faultsValue = emptyList()
        tiltbackSpeedValue = null
        batteryCurrentAValue = 0f
        motorTempCValue = 0f
        sawMotionFrame = false
        // Per-wheel evidence, exactly like the duty latch: a wheel whose motor
        // thermistor is unwired must not inherit the previous wheel's proof.
        sawMotorTempEvidence = false
        motorTempNonZeroRun = 0
        dutyNonZeroRun = 0
        dutyPercentValue = 0f
        // The duty latch is per-wheel evidence: a wheel that never proved it
        // reports duty must not inherit the previous wheel's proof.
        sawTrueDuty = false
        // Per-wheel evidence, exactly like the two latches above: a reconnect
        // may face a physically different wheel, and its series-cell count
        // must not be inherited from the last one — that would silently
        // mis-scale the new wheel's live frame instead of re-deriving it (or
        // honestly reporting no voltage until it proves itself again).
        derivedCellCountValue = null
        seriesCellsProof = null
        livePwmRawValue = null
        motionDutyRawValue = null
        boardTempFactC = null
        sawAnyFrame = false
        branchVoltageCertified = false
        genuineLiveVoltageRaw = 0
        voltageRatioCandidate = null
        voltageRatioStreak = 0
        cellPagesSeen = false
        branchTelemetryFrames = 0
        // A reconnect may face a different wheel: a protocol stuck in
        // "smart BMS seen" would leave a dumb wheel dataless again.
        smartBmsSeen = false
        liveData = null
        // The controller sample is assembled from all of the above, so it must
        // go with them: a stale instance would keep being handed to
        // MotionSampleGate as the previous wheel's last known motion.
        motion = null
    }

    // --- Protocol implementation ---

    private fun tryParseAll() {
        while (true) {
            val buf = buffer.toByteArray()

            val startIdx = findHeader(buf)
            if (startIdx < 0) {
                // No header — keep the last byte in case a 55 AA pair is split
                // across notifications.
                if (buf.size > 1) buffer.trimLeading(buf.size - 1)
                return
            }
            if (startIdx > 0) buffer.trimLeading(startIdx)

            val current = buffer.toByteArray()
            if (current.size < FRAME_SIZE) return // Need more data

            if (!hasTail(current)) {
                // 55 AA occurs inside payload too; a bad tail means this was a
                // false header. Advance ONE byte — skipping a whole frame here
                // would eat the real header that follows.
                buffer.trimLeading(1)
                continue
            }

            noteLedgerFrame()
            parseFrame(current)
            buffer.trimLeading(FRAME_SIZE)
        }
    }

    private fun findHeader(data: ByteArray): Int {
        for (i in 0..data.size - 2) {
            if ((data[i].toInt() and 0xFF) == 0x55 &&
                (data[i + 1].toInt() and 0xFF) == 0xAA
            ) return i
        }
        return -1
    }

    private fun hasTail(frame: ByteArray): Boolean {
        for (i in TAIL_OFFSET until FRAME_SIZE) {
            if ((frame[i].toInt() and 0xFF) != 0x5A) return false
        }
        return true
    }

    /**
     * Whether every payload byte (2..17) of [frame] is zero — the wheel's
     * BOOT-PLACEHOLDER shape, and the one genuineness gate this format can
     * support.
     *
     * `parseLiveFrame` has had a gate since Task 2 (`liveVoltageRaw > 0`) and
     * `parseBmsTelemetry` has had one since before this part (all-zero
     * temperatures *and* section voltage); 0x04 and 0x07 had none, and the
     * asymmetry was undocumented rather than reasoned. This closes it with the
     * discriminator that fits a frame in which every individual field has a
     * legitimate zero: an odometer of 0 is a wheel out of its box, an alert
     * byte of 0 is a healthy wheel, 0 °C is a real winter reading and 0 % duty
     * is what an unimplemented field looks like. **All of them at once is not a
     * reading**, and it is the shape a booting wheel actually sends.
     *
     * Cost of a false positive: a frame that genuinely carried nothing but
     * zeros is skipped, which leaves every field at exactly the value it would
     * have published anyway (the latches already withhold a zero temperature
     * and a zero duty, and both distance counters default to 0). So this is
     * free in the one direction and closes the boot case in the other.
     *
     * **What it deliberately does NOT catch: corruption.** This format has no
     * checksum — the `5A5A5A5A` tail is the whole integrity check — and garbled
     * bytes are non-zero by nature, so no gate here can tell a corrupted 0x07
     * from a real one. A corrupted frame can therefore still close
     * [sawTrueDuty] / [sawMotorTempEvidence] for the connection. That residual
     * risk is stated rather than papered over: closing it needs a checksum the
     * wheel does not send, or a plausibility model of hardware nobody here has
     * measured.
     */
    private fun isZeroPaddedPayload(frame: ByteArray): Boolean {
        for (i in PAYLOAD_FIRST..PAYLOAD_LAST) {
            if (frame[i].toInt() != 0) return false
        }
        return true
    }

    private fun parseFrame(frame: ByteArray) {
        sawAnyFrame = true
        when (frame.u8(18)) {
            0x00 -> parseLiveFrame(frame)
            0x01 -> parseBmsTelemetry(frame)
            0x02 -> parseCells(frame, branch = 0)
            0x03 -> parseCells(frame, branch = 1)
            0x04 -> parseOdometerFrame(frame)
            0x07 -> parseMotionFrame(frame)
        }
    }

    /**
     * Live motherboard frame — the wheel's whole moving picture in 24 bytes:
     *
     * ```
     * 55 aa | 17 04 | 00 00 | 00 3d | 00 00 | fe b6 | f4 06 | 00 a9 | 00 01 | 00 | 18 | 5a5a5a5a
     *  hdr    volt    speed     ?      trip   current  temp   pwm-fb    ?    type
     * ```
     *
     * (a real frame of the ET Max capture, see BegodeDumpFixture)
     *
     * - bytes 2..3 BE: voltage on the 67.2 V scale (NOT pack volts — see
     *   [liveVoltageRaw]). `0x1704` = 5892 → 58.92 V for a wheel whose cells
     *   independently sum to 148.4 V, i.e. the documented 168 / 67.2 factor.
     * - bytes 4..5 signed BE: speed, see [SPEED_KMH_PER_UNIT]. Read as signed,
     *   published as a MAGNITUDE — the sign is a per-firmware convention, not a
     *   direction (see [speedKmh] and [signedSpeedKmh]).
     * - bytes 6..7: **not decoded**, and not part of the trip. `0x003d` = 61
     *   in every frame of the capture. If bytes 6..9 were one 32-bit trip
     *   counter, as an out-of-date comment in WheelLog's own source claims,
     *   this wheel's trip would read 61 × 65536 m = 3 998 km — on a wheel
     *   whose LIFETIME odometer is 8 565 km and whose trip resets at power-on.
     *   WheelLog's parser reads only 8..9, and the capture agrees with the
     *   parser rather than with the comment.
     * - bytes 8..9 unsigned BE: the wheel's own since-power-on distance in
     *   metres, see [powerOnDistanceMeters] — which is NOT
     *   [ControllerData.tripKm]; that one is a session delta off the odometer.
     * - bytes 10..11 signed BE: PHASE current in 0.01 A. `0xfeb6` = −330 →
     *   −3.30 A. The wheel's BATTERY current is a different number and lives
     *   in the 0x07 frame — see [batteryCurrentA].
     * - bytes 12..13 signed BE: raw MPU6050 die temperature, converted with
     *   WheelLog's `raw / 340 + 36.53` formula. `0xf406` → 27.5 °C.
     * - bytes 14..15: WheelLog's FALLBACK hardware PWM (percent = raw × 0.1),
     *   used only until a 0x07 frame proves a real one. **Deliberately not
     *   decoded here.** It reads `0x00a9` = 169 in all 38 live frames of the
     *   capture — a constant 16.9 % duty on a wheel that never moved — while
     *   the 0x07 field of the same seconds reads 2 %. On this wheel the
     *   fallback is not a duty at all, and duty must be real or absent
     *   (spec §7.2). See [dutyPercent].
     *   **Surfaced, still not decoded as duty:** the device-facts screen keeps the word RAW
     *   (`LIVE_PWM_RAW`: the integer, and the integer x 0.1 as percent) so a rider with an older
     *   wheel can watch whether it moves with load. It is stored in [livePwmRawValue], which
     *   [dutyPercent], [rebuildMotion] and every alarm never read.
     * - bytes 16..17: unknown, `0x0001` throughout the capture.
     */
    private fun parseLiveFrame(frame: ByteArray) {
        liveVoltageRaw = frame.u16BE(2)
        val speedRaw = frame.i16BE(4)
        val powerOnMeters = frame.u16BE(8).toLong()
        phaseCurrentA = frame.i16BE(10) * 0.01f
        boardTempC = frame.i16BE(12) / 340f + 36.53f
        // Motion takes the same genuineness gate as the synthetic pack below:
        // a zero-padded boot frame would otherwise publish "0.00 km/h, 0 m"
        // as measurement. Independent of [smartBmsSeen] — motion belongs to the
        // wheel, and a wheel with a smart BMS still moves.
        if (liveVoltageRaw > 0) {
            signedSpeedKmhValue = speedRaw * SPEED_KMH_PER_UNIT
            // MAGNITUDE, for the same reason and in the same place as
            // [dutyPercent]'s: every consumer of [ControllerData.speedKmh]
            // treats it as a non-negative quantity compared against UPPER
            // thresholds, and the first hardware test found this field signing
            // FORWARD negative on two wheels.
            //
            // Taking it HERE rather than in [rebuildMotion] is what makes the
            // convention a property of the decoder, so a second consumer cannot
            // acquire this field without it. `VescValues` takes it in the same
            // place, at each of its two assignments into
            // `ControllerData.speedKmh` (`I` Task 10) — so the two protocols now
            // agree about the shared field, which this comment used to say they
            // did not.
            //
            // The signed value is kept above rather than discarded — see
            // [signedSpeedKmh].
            speedKmhValue = abs(signedSpeedKmhValue)
            powerOnMetersValue = powerOnMeters
            // The newest genuine counter: the witness that judges the next 0x04's odometer (R1).
            witnessPowerOnM = powerOnMeters
            genuineLiveVoltageRaw = liveVoltageRaw
            liveStamp++
            // Bytes 14..15, RAW and only for [deviceFacts]: not [dutyPercentValue], not [motion].
            // On the ET Max capture this word is a constant 0x00a9 (16.9 %) on a standing wheel,
            // so it is not a duty there; whether an older wheel's tracks load is exactly what the
            // facts screen is for. Same genuineness gate as the rest of the frame.
            livePwmRawValue = frame.i16BE(14)
            boardTempFactC = boardTempC
            sawLiveMotion = true
            // Rebuilt INSIDE the gate, not after it on `sawLiveMotion`. Those
            // two differ only mid-stream, and that is exactly where it matters:
            // once the first genuine frame has latched the flag, a LATER
            // zero-padded frame — a boot placeholder after a reconnect, or a
            // garbled one, this format having no checksum and only a
            // `5A5A5A5A` tail to catch corruption — still assigns
            // [phaseCurrentA] and [boardTempC] above. Rebuilding on it would
            // publish `0/340 + 36.53` = 36.53 C as an ESC temperature and 0 A
            // as a motor current, momentarily masking a genuinely hot board.
            // The battery half is already protected the same way (the
            // synthetic pack below is built inside its own `liveVoltageRaw > 0`
            // test); this puts motion on equal footing. Spec D §7.1.
            rebuildMotion()
            motionFramesSinceLive = 0
            observeControllerFrame(0x00)
        }
        // While no smart-BMS frame has arrived, this frame IS the battery
        // telemetry: synthesise pack 0 from it so a wheel without a smart BMS
        // connects at all instead of staying null forever. Gated on a genuine
        // voltage — a boot-zero live frame would synthesise a 0.00 V pack
        // with the temperature formula's raw-zero artefact (36.53 C).
        if (!smartBmsSeen && liveVoltageRaw > 0) {
            rebuildLiveData()
        }
    }

    /**
     * Rebuild the synthetic no-BMS pack without confusing phase current for
     * battery current. The live 0x00 field is a controller-only phase reading;
     * only 0x07's battery-current field may populate the battery sample.
     * Pack power stays unknown here because the live voltage still needs the
     * profile-aware scaling performed downstream.
     */
    private fun rebuildLiveData() {
        if (smartBmsSeen || liveVoltageRaw <= 0) return
        val batteryCurrent = batteryCurrentA()
        liveData = BmsData(
            // Unscaled on purpose — see [liveVoltageOn672ScaleV]. Power is
            // voltage-derived and equally unknowable here.
            voltage = 0f,
            current = batteryCurrent ?: 0f,
            hasCurrent = batteryCurrent != null,
            power = 0f,
            hasPower = false,
            // No fuel gauge and no cells: soc = 0 here means "unknown", not
            // "empty", and downstream must not alarm on it. The estimator
            // flips this back to true the moment a voltage estimate is honest.
            socKnown = false,
            cellVoltages = emptyList(),
            temperatures = listOf(boardTempC),
            // Same rationale as [rebuild]: a streaming wheel is not cut off,
            // and false renders alarming red OFF badges.
            chargeEnabled = true,
            dischargeEnabled = true,
            isConnected = true
        )
    }

    /**
     * Lifetime odometer (0x04): u32 big-endian at bytes 2..5, metres — the
     * word order WheelLog's parser uses. The capture reads `00 82 b2 5d` =
     * 8 565 341 m = 8 565 km, constant across all 13 seconds, as a lifetime
     * counter of a stationary wheel must be; a word-swapped read of the same
     * bytes gives 2.99 million km, which is absurd rather than merely wrong.
     * Neither check is a measurement — a wheel whose displayed odometer is
     * known would confirm it outright.
     *
     * The rest of the frame, all of it read off WheelLog's `GotwayAdapter`:
     * ```
     * 55 aa | 00 82 b2 5d | 28 00 | 1c 1e | 00 c8 | 00 | 01 | 00 | 00 | 00 12 | 04 | 18 | 5a5a5a5a
     *  hdr    odometer(m)   sett'gs  poweroff tiltbk  ?   led  alrt light   ?    type
     * ```
     * (a real frame of the ET Max capture)
     *
     * - bytes 6..7: settings word — pedal mode at bits 13..14, speed-alarm mode
     *   at 10..11, roll angle at 7..8, an **in-miles** flag at bit 0.
     *   **Not decoded**: every field of it is a wheel SETTING, and Volty never
     *   writes to FFE1, so it could only ever be displayed. The in-miles flag is
     *   the one that matters and it matters to [tiltbackSpeed], which is
     *   itself unsurfaced.
     * - bytes 8..9: the power-off timer. Not decoded — a setting, no consumer.
     * - bytes 10..11 signed BE: **tiltback speed**, see [tiltbackSpeed].
     * - byte 13: LED mode. Not decoded — a setting, no consumer.
     * - byte 14: the **alert bitmap**, see [alertLabels] and [FAULT_BITS].
     *   Reads `0x00` in all 38 of the capture's 0x04 frames — a healthy wheel,
     *   which means the capture cannot exercise any fault and every fault test
     *   is necessarily synthetic.
     * - byte 15: light mode (low two bits). Not decoded — a setting.
     */
    private fun parseOdometerFrame(frame: ByteArray) {
        // Same genuineness gate as [parseLiveFrame]'s and [parseBmsTelemetry]'s,
        // and it was missing here — see [isZeroPaddedPayload] for the
        // discriminator and for what it can and cannot catch. Without it a
        // zero-padded boot 0x04 mid-stream republished the wheel's lifetime
        // mileage as 0.0 km and reset the trip baseline under a rider.
        if (isZeroPaddedPayload(frame)) return
        // R12 sees the alert byte of EVERY non-padded 0x04 on arrival, whatever the odometer gate later decides
        // about that frame (Ruling 5: delays must not stack; only odometer and trip wait for the gate).
        confirmAlertBits(frame.u8(14))
        val point = OdometerPoint(frame.u32BE(2), witnessPowerOnM, liveStamp, frame.copyOf(), currentLedgerFrame)
        if (witnessTrust == WitnessTrust.UNPROVEN && (provesWitness(arrivedLast, point) || provesWitness(arrivedBeforeLast, point))) {
            witnessTrust = WitnessTrust.TRUSTED
            // Points the gate is still holding were judged by the monotone path: re-judge them under R1. Without
            // this a first 0x04 torn DOWN (-65 536 m) lets the held next frame be accepted against it, crediting
            // +65.5 km to the trip (review I1; an earlier revision of this round had exactly that hole).
            rebuildGateFromUndecided()
        }
        val previous = arrivedLast
        arrivedBeforeLast = arrivedLast
        arrivedLast = point
        if (witnessTrust == WitnessTrust.TRUSTED && previous != null && isSuspect(previous, point)) {
            // Coherent odometer, contradicting witness. Held back (not yet offered) so that, if this turns out to
            // be a distrusted witness, those frames were never rejected; a needle is flushed on the next frame.
            suspects += point
            if (suspects.size >= R1_DISTRUST_AFTER) distrustWitness(point, previous)
            return
        }
        flushSuspects()
        offerOdometerPoint(point)
    }

    /**
     * R12, the fault-bit debounce (kind 2, temporal confirmation), SYMMETRIC: a bit changes the confirmed bitmap
     * [alertBitmap] (and so [ControllerData.faults] and [ControllerData.wheelPwmAlarm]) only when this 0x04 and the
     * previous one AGREE: both set confirms it, both clear clears it, a disagreement leaves it as it was.
     * Begode only: KingSong and Veteran publish no `faults`.
     *
     * Retraction: the brief (and the first revision of this function) cleared a confirmed bit AT ONCE. That was wrong
     * because the frames have no checksum: one corrupt "clear" frame in the middle of a real fault re-armed
     * CONTROLLER_FAULT (it re-arms on an empty list), and the next set frame pushed a second CRITICAL notification.
     * Controller ruling 2026-10-28: a confirmed bit clears after two consecutive clear frames too.
     *
     * Why: one corrupt byte 14 would otherwise raise a CRITICAL push and a banner row, and a bit-0 blip would teach
     * the PWM learner a false onset. Cost and false-positive analysis: a genuine fault shows one 0x04 (~310 ms) late
     * and clears ~310 ms later than it ended (harmless: the notification is once per episode, the row lingers a
     * frame); a genuine fault that lasts exactly one frame is never shown, and a genuine one-frame CLEAR inside a
     * fault is ignored; the wheel's own beep is unaffected. Swallowed set bits are counted in [swallowedAlertBits].
     * Nothing in PwmEstimator or the wizard depends on the alarm clearing at once: both learn only from a RISING edge.
     *
     * Not a rejected frame: nothing enters the ledger. A frame the odometer gate later REJECTS (a torn read) may
     * already have counted toward this; acceptable, since two frames are required anyway. A zero-padded 0x04 is not a
     * genuine frame and is skipped before this: it neither counts nor breaks a run (set, padded, set confirms).
     *
     * PWM onset (Ruling 1): bit 0's rising edge calibrates the PWM estimate, and the onset is the sample before
     * the edge (OnsetLookback), erring HIGH. Confirming one 0x04 later would publish the edge later and the lookback
     * would then pick a faster sample, a LOW estimate. So the time the bit was first set raw is kept in
     * [pwmAlarmFirstSetAtMillis] (cleared only when bit 0 is confirmed clear, so a corrupt clear inside an alarm keeps
     * it) and published beside the confirmed alarm; the lookback measures from there.
     */
    private fun confirmAlertBits(raw: Int) {
        val before = alertBitmap
        val bothSet = raw and rawAlertPrevious
        val bothClear = (raw or rawAlertPrevious).inv()
        alertBitmap = (bothSet or (before and bothClear.inv())) and 0xFF
        // Set bits that were seen last frame, are gone now and never made it into the confirmed bitmap.
        swallowedAlertBits += Integer.bitCount(rawAlertPrevious and raw.inv() and alertBitmap.inv() and 0xFF)
        if ((raw and PWM_ALARM_BIT) != 0) { if (pwmAlarmFirstSetAtMillis == null) pwmAlarmFirstSetAtMillis = nowMillis() }
        else if ((alertBitmap and PWM_ALARM_BIT) == 0) pwmAlarmFirstSetAtMillis = null
        rawAlertPrevious = raw
        faultsValue = alertLabels(alertBitmap and FAULT_BITS)
        // A 0x04 the odometer gate is still holding has not published its sample; one already published must not
        // wait for the gate to show a newly confirmed (or cleared) bit.
        if (sawOdometer && alertBitmap != before) rebuildMotion()
    }

    private fun flushSuspects() {
        if (suspects.isEmpty()) return
        val held = suspects.toList()
        suspects.clear()
        held.forEach { offerOdometerPoint(it) }
    }

    private fun distrustWitness(tipping: OdometerPoint, beforeRun: OdometerPoint) {
        witnessTrust = WitnessTrust.DISTRUSTED
        val run = suspects.toList()
        suspects.clear()
        // A fresh gate, now judging by the monotone path alone. It starts from the last accepted point, then the
        // points the old gate had not yet decided, then the run: nothing is re-published, and nothing the run
        // contains was ever recorded as rejected.
        rebuildGateFromUndecided()
        run.forEach { offerOdometerPoint(it) }
        recordLedgerNote(LedgerKind.WITNESS_DISTRUSTED, 0x04, PlausibilityRule.R1_ODOMETER_WITNESS, tipping.frame,
            odometerText(beforeRun), rawWitnessStep(beforeRun, tipping)?.let { "+$it m" }, tipping.arrivedAt)
    }

    /**
     * A fresh gate seeded with the last accepted point (its seed decision is discarded: nothing is re-published),
     * then every point the old gate had not yet decided, re-offered in order under the CURRENT trust.
     */
    private fun rebuildGateFromUndecided() {
        odometerGate = newOdometerGate()
        lastAcceptedPoint?.let { odometerGate.offer(it) }
        val undecided = pendingPoints.toList()
        pendingPoints.clear()
        undecided.forEach { offerOdometerPoint(it) }
    }

    /** Whether R1's witness was dropped for this connection; Task 8 surfaces it. */
    val odometerWitnessDistrusted: Boolean get() = witnessTrust == WitnessTrust.DISTRUSTED

    private fun offerOdometerPoint(point: OdometerPoint) {
        pendingPoints += point
        // A frame the gate has not accepted writes NO state: not the odometer, not the alert byte, no observation.
        // Decisions can lag (a held point is decided by the next one) and arrive in order.
        for (decision in odometerGate.offer(point)) {
            val at = pendingPoints.indexOfFirst { it === decision.point }
            if (at >= 0) repeat(at + 1) { pendingPoints.removeAt(0) }
            when (decision) {
                is CounterDecision.Accepted -> {
                    // A witnessed step may be a few metres negative inside the tolerance; the trip never decreases.
                    tripSumMeters += max(0.0, decision.incrementM)
                    // Clamped: a monotone-path acceptance can carry a huge step (a jump, a needle that was
                    // credited) and must not blind R1's tolerance (2 x this) on the next frame (review M2).
                    lastAcceptedStepM = decision.incrementM.toLong().coerceIn(0L, R1_STEP_CLAMP_M)
                    lastAcceptedPoint = decision.point
                    applyOdometerFrame(decision.point)
                }
                is CounterDecision.Rebased -> {
                    tripSumMeters += max(0.0, decision.creditedM)
                    lastAcceptedPoint = decision.point
                    applyOdometerFrame(decision.point)
                    recordRebase(0x04, PlausibilityRule.R1_ODOMETER_WITNESS, decision.point.frame,
                        "jump ${decision.jumpM.toLong()} m", null, decision.point.arrivedAt)
                }
                is CounterDecision.Rejected -> {
                    val witnessM = rawWitnessStep(decision.reference, decision.point)
                    recordRejection(0x04, PlausibilityRule.R1_ODOMETER_WITNESS, decision.point.frame,
                        odometerText(decision.reference), witnessM?.let { "+$it m" }, decision.point.arrivedAt)
                }
            }
        }
    }

    /** Publish an odometer frame the gate accepted: everything [parseOdometerFrame] used to do unconditionally. */
    private fun applyOdometerFrame(point: OdometerPoint) {
        val frame = point.frame
        odometerMetersValue = point.meters
        val tiltbackRaw = frame.i16BE(10)
        // WheelLog's own rule: >= 100 is the wheel's "unset" marker rather than
        // a speed. 0 is unset too — a wheel that tilts back at a standstill is
        // not a configuration, it is a field nobody filled in.
        tiltbackSpeedValue =
            if (tiltbackRaw in 1 until TILTBACK_UNSET_AT) tiltbackRaw.toFloat() else null
        // The alert byte is NOT taken from this frame: [confirmAlertBits] already judged it on arrival (R12).
        sawOdometer = true
        rebuildMotion()
        observeControllerFrame(0x04)
    }

    /**
     * Motion telemetry (0x07) — the frame this protocol used to drop:
     *
     * ```
     * 55 aa | 00 43 | 00 01 | 00 14 | 00 02 | 00 00 00 00 00 00 00 00 | 07 | 18 | 5a5a5a5a
     *  hdr   bat cur    ?     mot t    duty            zero            type
     * ```
     *
     * (a real frame of the ET Max capture)
     *
     * - bytes 2..3 signed BE: battery current, 0.01 A, **negated** — see
     *   [batteryCurrentA]. `0x0043` = 67 → −0.67 A.
     * - bytes 4..5: unknown, `0x0001` throughout the capture.
     * - bytes 6..7 signed BE: motor temperature in whole °C — see
     *   [motorTempC]. `0x0014` = 20 °C.
     * - bytes 8..9 signed BE: hardware duty in whole percent — see
     *   [dutyPercent]. `0x0002` = 2 %.
     * - bytes 10..17: zero throughout the capture.
     *
     * The scales are WheelLog's, read off its source rather than guessed: it
     * stores current in 0.01 A, temperature in 0.01 °C and PWM as
     * `output / 10000` of full scale, and multiplies this frame's three fields
     * by 1, 100 and 100 respectively on the way in.
     */
    private fun parseMotionFrame(frame: ByteArray) {
        // The same gate [parseOdometerFrame] and [parseLiveFrame] take. It is
        // worth being explicit about what it buys HERE, because this frame owns
        // two one-way latches: [sawMotorTempEvidence] and [sawTrueDuty] never
        // reopen until [reset], so a single frame that gets past this is
        // permanent for the connection. See [isZeroPaddedPayload].
        if (isZeroPaddedPayload(frame)) return
        batteryCurrentAValue = -frame.i16BE(2) * BATTERY_CURRENT_A_PER_UNIT
        val motorTempRaw = frame.i16BE(6)
        // The thermistor's counterpart of the duty latch below: one non-zero
        // reading is what proves a sensor is wired at all. See [motorTempC] for
        // why frame arrival is not evidence and why no range gate can replace
        // this.
        //
        // **Two consecutive frames, not one (Task 7).** An earlier revision latched on any single non-zero word;
        // that was wrong because this format has no checksum, so one corrupt 0x07 (temperature 130 between two
        // zero frames) decided the latch for the whole connection. The run must now reach [LATCH_CONFIRM_FRAMES]
        // genuine 0x07 frames. Cost: one frame (~310 ms) before a real sensor shows. False-positive risk: a real
        // sensor that reads exactly 0 C on alternate frames never latches; it shows as the sensor staying hidden.
        motorTempNonZeroRun = if (motorTempRaw != 0) motorTempNonZeroRun + 1 else 0
        if (motorTempNonZeroRun >= LATCH_CONFIRM_FRAMES) sawMotorTempEvidence = true
        motorTempCValue = motorTempRaw.toFloat()
        val dutyRaw = frame.i16BE(8)
        // A non-zero value proves the field by itself. For the stationary
        // Begode capture, however, the first valid 0x07 frames carry a real
        // balancing duty of 0 %; once a genuine live frame has established the
        // connection, that zero is a measurement rather than an absent field.
        // Keep the isolated all-zero synthetic stream unknown (there is no
        // session context to distinguish an unimplemented field there).
        //
        // **Retraction (Task 7):** the non-zero path used to latch on one frame ("a non-zero value proves the field
        // by itself"). That was wrong for the same reason as the temperature latch above: one corrupt frame closed
        // it for the connection. It now needs [LATCH_CONFIRM_FRAMES] consecutive non-zero duty words. The
        // [sawLiveMotion] path is unchanged: a zero is a measurement once a genuine live frame exists.
        dutyNonZeroRun = if (dutyRaw != 0) dutyNonZeroRun + 1 else 0
        if (dutyNonZeroRun >= LATCH_CONFIRM_FRAMES || sawLiveMotion) sawTrueDuty = true
        // MAGNITUDE, clamped to the 0..100 [dutyPercent]'s contract promises —
        // the same statement VescValues makes with `abs(duty) * 100`, made in
        // the decoder rather than in [rebuildMotion] so the two protocols agree
        // on what the shared field means. See [dutyPercent] for the negative
        // case (a silent ШИМ alarm) and for why the upper clamp fails loud.
        dutyPercentValue = abs(dutyRaw).toFloat().coerceAtMost(DUTY_MAX_PERCENT)
        motionDutyRawValue = dutyRaw
        sawMotionFrame = true
        // NOTE: this frame must never write [phaseCurrentA] or [boardTempC].
        // That used to be a dead store — [parseLiveFrame] reassigns both from
        // its own bytes before the only read — but the controller sample below
        // reads them right here, so a leak would now be observable, and it is
        // pinned by a test.
        rebuildMotion(dutyFresh = true)
        rebuildLiveData()
        if (motionFramesSinceLive < NO_LIVE_FRAME) motionFramesSinceLive++
        observeControllerFrame(0x07)
    }

    private fun observeControllerFrame(type: Int) {
        val decoded = motion ?: return
        val powerJoinFresh = motionFramesSinceLive in 1..POWER_JOIN_MAX_MOTION_FRAMES
        observeTelemetry(TelemetryOrigin.CONTROLLER, 0) { observedBegodeController(type, decoded, powerJoinFresh) }
    }

    /**
     * **R13 (witness).** Once the series count is certified from the branch ratio ([branchVoltageCertified]), a 0x01
     * whose pack voltage differs from the live frame's voltage scaled by `count / 16` by more than [R13_TOLERANCE]
     * is rejected whole: no branch state, SoC or observation comes from it. The live 0x00 frame is never touched
     * (live display stays raw). A corrupt 0x01 voltage would otherwise become a SoC needle on a T4.
     *
     * Measured: 0x01 V / live raw is 0.01500 on all 268 pairs of the three dumps (spread 0.01 %, including the
     * 0 to 81 km/h sag in dump 3), so 3 % is a x300 margin. Not applied before certification (the scale is
     * unproven then), when no genuine live voltage exists, or to a zero-padded boot payload (handled elsewhere).
     *
     * False-positive risk: a wheel whose two boards sample far apart in time under heavy sag. It would show as
     * the rejection counter growing on a healthy wheel. Read-only; nothing is sent.
     */
    private fun rejectedByPackVoltageWitness(frame: ByteArray): Boolean {
        if (!branchVoltageCertified || genuineLiveVoltageRaw <= 0 || isZeroPaddedPayload(frame)) return false
        // Only against a reference of THIS frame cycle (final review M1): the measured 0.01 % spread is for a pair about
        // 3 ms apart, but a lost 0x00 leaves the reference 600 to 900 ms old, and a T4 sagging several volts under a hard
        // pull would then reject a correct 0x01. An earlier revision judged against a live voltage of any age; that was
        // wrong because the witness only holds for a simultaneous pair. A stale reference skips the check (nothing rejected).
        if (motionFramesSinceLive > R13_MAX_MOTION_FRAMES_SINCE_LIVE) return false
        val count = derivedCellCountValue ?: return false
        val expectedV = genuineLiveVoltageRaw * 0.01f * count / 16f
        val packV = frame.u16BE(6) * 0.1f
        if (abs(packV - expectedV) <= expectedV * R13_TOLERANCE) return false
        recordRejection(0x01, PlausibilityRule.R13_PACK_VOLTAGE_WITNESS, frame,
            "${(expectedV * 10).toInt() / 10.0} V", "${(packV * 10).toInt() / 10.0} V")
        return true
    }

    /**
     * Smart-BMS telemetry (0x01). Byte 19 is bmsnum 0..3: bit 1 selects the
     * branch, bit 0 the section within it — confirmed against the ET Max
     * capture (semiVoltage 74.1 V at even bmsnum, 74.2 V at odd; both branches
     * report the same 147.2 V pack voltage because they are parallel).
     */
    private fun parseBmsTelemetry(frame: ByteArray) {
        val bmsnum = frame.u8(19)
        if (bmsnum > 3) return
        if (rejectedByPackVoltageWitness(frame)) return
        retireSyntheticPack()
        val branch = branches[bmsnum shr 1]
        val section = bmsnum and 1

        branch.sawTelemetry = true
        if (bmsnum shr 1 == 0) branchTelemetryFrames++
        branch.packVoltageV = frame.u16BE(6) * 0.1f
        // NEGATED, and the negation is the fix from the 2026-08-01 field test:
        // on a physically charging pack this read as discharge and vice versa.
        // The wheel's smart-BMS branch frame is discharge-positive, the same
        // way the MAIN frame's battery current is — `D §4`'s field table says
        // "negated, then stored" for bytes 2..3, and this branch decode simply
        // never got the same treatment. One convention, two frames, and only
        // one of them had been measured.
        //
        // Negated HERE rather than in `rebuild`, so that the power computed
        // from it downstream cannot disagree with the sign of its own current.
        branch.currentA = -(frame.i16BE(8) * 0.1f)

        // Two temperature sensors per section, degrees Celsius directly.
        val t1 = frame.i16BE(10)
        val t2 = frame.i16BE(12)
        val sectionVoltageRaw = frame.i16BE(14)

        // A booting BMS zero-pads the telemetry payload: both temperatures AND
        // the section voltage read 0 while the pack voltage is already real
        // (the fixture opens with ~5 s of such frames). A genuine cold reading
        // cannot look like this — a live 20S section never sits at 0.0 V — so
        // the discriminator is the all-zero payload, not the temperature value:
        // 0 C with a non-zero section voltage is accepted as real winter data.
        // On a boot frame keep the previous known values (or none) instead of
        // publishing zeros that would feed the dashboard and alert thresholds.
        val isBootPlaceholder = t1 == 0 && t2 == 0 && sectionVoltageRaw == 0
        if (!isBootPlaceholder) {
            // The sanity range guards against garbage spikes.
            if (t1 in TEMP_SANITY) branch.sectionTemps[section * 2] = t1.toFloat()
            if (t2 in TEMP_SANITY) branch.sectionTemps[section * 2 + 1] = t2.toFloat()
            branch.sectionVoltageV[section] = sectionVoltageRaw * 0.1f
        }

        rebuild(branch, bmsnum shr 1)
        observeTelemetry(TelemetryOrigin.BATTERY, bmsnum shr 1) {
            observedBegodeBranch(frame, isBootPlaceholder, voltageCertified = branchVoltageCertified)
        }
        observeTelemetry(TelemetryOrigin.BATTERY, bmsnum shr 1, TelemetryRole.ASSEMBLY, section) {
            observedBegodeAssembly(frame, isBootPlaceholder, TEMP_SANITY)
        }
    }

    /**
     * Cell voltages (0x02 = branch 0, 0x03 = branch 1). Byte 19 is the packet
     * index; each frame carries 8 cells at bytes 2..17, big-endian millivolts.
     * Cell number = packetIndex * 8 + i.
     */
    private fun parseCells(frame: ByteArray, branch: Int) {
        retireSyntheticPack()
        cellPagesSeen = true
        val state = branches[branch]
        val packetIndex = frame.u8(19)
        for (i in 0 until 8) {
            val mv = frame.u16BE(2 + i * 2)
            // Zero slots (BMS still booting, or fewer cells than the packet
            // grid) are skipped rather than stored.
            if (mv in 1..5000) {
                state.cells[packetIndex * 8 + i] = mv / 1000f
            }
        }
        rebuild(state, branch)
        val expectedCount = (derivedCellCountValue ?: cellCount)?.takeIf { it > 0 }
        val seen = observedCellPositions[branch]
        observeTelemetry(TelemetryOrigin.BATTERY, branch) { observedBegodeCells(frame, expectedCount, seen) }
        for (offset in 0 until 8) {
            val index = packetIndex * 8 + offset
            if ((expectedCount == null || index < expectedCount) && frame.u16BE(2 + offset * 2) > 0) seen += index
        }
    }

    /**
     * Any smart-BMS frame proves this wheel has real branches: drop the
     * synthetic no-BMS pack permanently (until [reset]). Real branch data may
     * still be a few frames away — 0x02 can precede the first 0x01 — and
     * that gap honestly reports NO pack rather than a synthetic one
     * contradicting the cells in flight.
     */
    private fun retireSyntheticPack() {
        smartBmsSeen = true
        liveData = null
    }

    /**
     * Rebuild the branch's [BmsData] from accumulated state. Gated on the 0x01
     * frame: without it there is no voltage. A wheel without a smart BMS never
     * sends one — such a wheel is served by the synthetic live-frame pack
     * instead (see [liveData]).
     *
     * [packIndex] is used for exactly one thing beyond the branch state
     * itself: branch 0 is the one [updateDerivedCellCount] learns from — the
     * two branches are parallel, so branch 0's series-cell count is the
     * pack's, but branch 1's is not evidence of anything beyond itself and
     * must not be consulted. See [derivedCellCount].
     *
     * **A newly-proven count reaches [ControllerData.inputVoltageV] one
     * motion-bearing frame LATE.** This function does not call
     * [rebuildMotion] — only [parseLiveFrame], [parseOdometerFrame] and
     * [parseMotionFrame] do — so the cell/telemetry frame that proves the
     * count updates [derivedCellCountValue] but publishes nothing new on
     * [motion] itself; the next 0x00/0x04/0x07 frame is what surfaces it.
     * Harmless on real hardware, which streams live frames continuously, but
     * worth knowing before reading a test that expects the voltage the
     * INSTANT the proving cell frame lands.
     */
    private fun rebuild(branch: BranchState, packIndex: Int) {
        if (!branch.sawTelemetry) return
        val cells = contiguousCells(branch.cells)
        val voltage = branchVoltage(branch, cells)
        val current = branch.currentA
        if (packIndex == 0) {
            if (cells.isEmpty()) updateDerivedCellCountFromBranchVoltage(branch.packVoltageV)
            else updateDerivedCellCount(cells)
        }
        branch.lastData = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
            // The 0x01 frame has voltage but no fuel gauge. Cells are the
            // first evidence from which a branch's charge can be estimated,
            // but a partial page is not enough. The strict count gate is the
            // same evidence used for derived cell count; the profile count is
            // preferred when available, and the frame-voltage ratio is the
            // fallback before this connection has proved its own count.
            socKnown = isCellSetComplete(branch, cells),
            cellVoltages = cells,
            temperatures = branch.sectionTemps.filterNotNull(),
            // A Begode reports no charge/discharge MOSFET state at all, and a
            // wheel that is streaming telemetry is by definition not cut off.
            // Leaving the defaults (false) rendered alarming red "OFF" badges
            // on a healthy wheel. True is an approximation — the truthful
            // model would be a nullable "unknown" state in BmsData.
            chargeEnabled = true,
            dischargeEnabled = true,
            isConnected = true
        )
    }

    /** True only when the branch has supplied its expected full cell set. */
    private fun isCellSetComplete(branch: BranchState, cells: List<Float>): Boolean {
        if (cells.isEmpty()) return false
        val expectedCount = derivedCellCountValue ?: cellCount
        return if (expectedCount != null) {
            expectedCount > 0 && cells.size == expectedCount
        } else {
            branch.packVoltageV > 0f && isCellCountConfirmed(cells.sum(), branch.packVoltageV)
        }
    }

    /**
     * The branch's voltage: the SUM OF ITS CELLS whenever the full cell set is
     * available, the 0x01 frame's pack-voltage field while cells are still
     * arriving.
     *
     * The frame field is NOT in the 0.1 V units the rest of that frame uses:
     * across the 86 samples of the ET Max capture, cellSum / raw is a constant
     * 0.1009 (spread 0.09 %), while the section-voltage field in the SAME frame
     * is exactly 0.1 V per unit. On the wheel this rendered as "162.00 V" on a
     * tile that simultaneously read "4.09 V/cell" — 40 x 4.09 = 163.6 V, the
     * two numbers contradicting each other. No corrected scale factor is
     * hard-coded here: the cells are the ground truth and are already decoded.
     * Because power is voltage x current, this moves power too.
     *
     * "Full cell set" cannot be asserted from the packet grid — the wheel never
     * announces how many cells a branch has, and packets arrive out of order —
     * so completeness is judged against the frame field itself: the true sum is
     * ~0.9 % ABOVE it, whereas one missing 8-cell packet out of five puts the
     * partial sum ~20 % BELOW. [CELL_SUM_COMPLETE_RATIO] sits in that gap.
     */
    private fun branchVoltage(branch: BranchState, cells: List<Float>): Float {
        val frameVoltage = branch.packVoltageV
        if (cells.isEmpty()) return frameVoltage
        val cellSum = cells.sum()
        if (frameVoltage <= 0f) return cellSum
        return if (isCellSumComplete(cellSum, frameVoltage)) cellSum else frameVoltage
    }

    /**
     * Cells from physical cell 0 up to the first gap, so a list index always
     * equals the physical cell number. Cell packets arrive out of order and a
     * missing middle packet must not compact the map around the gap — that
     * would show physical cell 32 at list index 16, and the dashboard renders
     * this list positionally. A shorter but honest list beats a full but
     * shuffled one; the tail appears as soon as the missing packet lands.
     */
    private fun contiguousCells(cells: Map<Int, Float>): List<Float> {
        val run = ArrayList<Float>(cells.size)
        while (true) {
            run.add(cells[run.size] ?: return run)
        }
    }

    companion object {
        /** [motionFramesSinceLive] before any genuine live frame (and after a reset). */
        private const val NO_LIVE_FRAME = Int.MAX_VALUE

        /**
         * The staleness bound of the 0x07 power join, in 0x07 frames since the live frame that supplied the voltage.
         * The brief's bound is 1 000 ms of wheel-frame time; the frames carry no timestamps, so it is counted in
         * frames at the ~300 ms the wheel spaces its frames by (an assumption from the captures, not a measurement
         * of every firmware): 3 x 300 ms = 900 ms stays inside the second.
         */
        private const val POWER_JOIN_MAX_MOTION_FRAMES = 3

        /**
         * R13's freshness bound, in 0x07 frames since the live frame that supplied the reference voltage. A 0x01 follows
         * its 0x00 by about 3 ms, so 0 is the only healthy case: any 0x07 in between means this cycle's 0x00 was lost and
         * the reference is a cycle (~310 ms) old or more, so the witness is skipped. An earlier revision tolerated one
         * 0x07 (bound 1); that let exactly the one-lost-0x00 case through, which is the case the bound exists for.
         */
        private const val R13_MAX_MOTION_FRAMES_SINCE_LIVE = 0

        /** Slots of the cache stamp in [deviceFacts]: eleven scalars, two per branch, three of the plausibility ledger and R12's unconfirmed-bit count. */
        private const val FACTS_STAMP_SIZE = 19
        private const val FRAME_SIZE = 24
        private const val TAIL_OFFSET = 20

        /** First payload byte of a frame — bytes 0..1 are the `55 AA` header. */
        private const val PAYLOAD_FIRST = 2

        /** Last payload byte — 18 is the frame type and 19 its subtype/index. */
        private const val PAYLOAD_LAST = 17

        /**
         * The reference the live frame's voltage is expressed against: Begode
         * reports every wheel as if it were a 16S one, full at 16 x 4.2 =
         * 67.2 V. Confirmed on the ET Max capture: raw 5892 (58.92 V) for a
         * pack whose 0x01 frames and cell sums independently read ~147-148 V,
         * a factor of exactly 168 / 67.2 = 2.5 (WheelLog's getScaledVoltage).
         */
        private const val LIVE_VOLTAGE_REFERENCE_V = 67.2f

        /** Full-charge volts per Li-ion cell — the term the 67.2 V reference is built from. */
        private const val FULL_CELL_V = 4.2f

        /**
         * Scale a [liveVoltageOn672ScaleV] reading to real pack volts for a
         * wheel with [cellCount] cells in series: `v * (cellCount * 4.2) /
         * 67.2`. Static so it is callable with a count from EITHER source this
         * file now has — [inputVoltageOrNull] calls it with [derivedCellCount]
         * or the vehicle profile's [BegodeProtocol.cellCount], and
         * `KableBmsRepository` calls it directly with a cell count read off a
         * stored [ru.sodovaya.volty.domain.model.Pack] for the battery path,
         * which has no protocol instance to call an instance method on.
         */
        fun scaleLiveVoltage(voltageOn672ScaleV: Float, cellCount: Int): Float =
            voltageOn672ScaleV * (cellCount * FULL_CELL_V / LIVE_VOLTAGE_REFERENCE_V)

        /**
         * Km/h per unit of the live frame's signed speed field (bytes 4..5) —
         * the raw unit is cm/s.
         *
         * Taken from WheelLog's source, not guessed: `GotwayAdapter` multiplies
         * this field by 3.6 and rounds it into a speed field whose unit is
         * hundredths of km/h — its riding-speed constant is 200 units annotated
         * as 2 km/h, and a commented-out debug line puts 5000 in the same field
         * for 50 km/h — i.e. `km/h = raw * 3.6 / 100`. The frame-layout note at
         * the foot of that same file says it in words too, describing bytes
         * 4..5 as a fixed-point big-endian speed of `3.6 * value / 100` km/h.
         * Two independent statements in one file agreeing on 0.036.
         *
         * **Corroborated by the machine itself, and no longer to be refined.**
         * This used to record that no capture of a moving wheel existed — bytes
         * 4..5 read `00 00` in all 38 live frames of the ET Max capture, 13
         * seconds of a wheel standing still — so the tests could pin only the
         * field's OFFSET and SIGNEDNESS synthetically. The first hardware ride
         * closed the part of that gap that mattered: asked about the magnitude
         * specifically, the rider judged it *"плюс-минус верная скорость"*
         * against how fast the wheel was visibly turning, on **two** wheels
         * (field report `2026-07-30-first-hardware-test` §1 S1).
         *
         * That is not a measurement — "about right by eye" cannot separate 0.036
         * from 0.034 — but it rules out both failure modes that could have
         * shipped, and the report's §1 declares **no further work justified on
         * this constant**: a GPS comparison would refine a number already inside
         * its useful tolerance. What the same ride DID find is that the field's
         * sign is not a direction — see [speedKmh], which now publishes the
         * magnitude.
         *
         * (History, because it nearly shipped: this constant was 0.01 for one
         * commit — "hundredths of km/h" from memory rather than from source —
         * which reads 3.6x LOW, and a correction to 0.36 was proposed, which
         * reads 10x HIGH. The source settles it at 0.036, and the ride agrees
         * with the source: 3.6x low or 10x high would both have been obvious
         * against a visibly spinning wheel.)
         */
        private const val SPEED_KMH_PER_UNIT = 0.036f

        /**
         * Amperes per unit of the 0x07 frame's signed battery-current field.
         * WheelLog stores current in hundredths of an amp and passes this
         * field through unscaled apart from a sign flip — see
         * [batteryCurrentA].
         */
        private const val BATTERY_CURRENT_A_PER_UNIT = 0.01f

        /**
         * What [ControllerData.escTempC] / [ControllerData.motorTempC] carry
         * when the wheel has not reported that sensor yet.
         *
         * Any value at or below −50 °C would do —
         * [ControllerData.hasEscTemp] is `escTempC > -50f`, VESC's "no sensor
         * wired" reading generalised — but −100 is unmistakably a sentinel
         * rather than a cold morning, and leaves the boundary itself free.
         * **Never 0f**: that would claim a sensor and arm Part F's ESC_TEMP
         * alert against a constant zero (`D §7.1`).
         */
        private const val NO_TEMP_SENSOR_C = -100f

        /**
         * What [ControllerData.dutyPercent] carries while the hardware PWM
         * field has not yet proved itself (see [dutyPercent]'s latch and
         * [rebuildMotion] for why this is 0 and not a negative sentinel).
         * Named so the choice is greppable rather than a bare literal.
         */
        private const val DUTY_NOT_YET_REPORTED_PERCENT = 0f

        /**
         * The top of [dutyPercent]'s stated 0..100 range, and the clamp
         * [parseMotionFrame] applies after taking the field's magnitude.
         *
         * 100 % is full modulation — a wheel physically cannot exceed it — so
         * anything above is garbage on a frame format with no checksum. Clamping
         * rather than rejecting keeps the failure LOUD: the ШИМ alarm fires at
         * maximum instead of the value being dropped and the dial holding a
         * stale, comfortable number.
         */
        private const val DUTY_MAX_PERCENT = 100f

        /**
         * The value of the 0x04 tiltback field at and above which the wheel
         * means "unset" rather than a speed — WheelLog's own threshold, which
         * zeroes its tiltback field once the decoded value reaches 100. The ET
         * Max capture reads 200 in every 0x04 frame. See [tiltbackSpeed].
         */
        private const val TILTBACK_UNSET_AT = 100

        private const val R1_TOLERANCE_FLOOR_M = 40L
        private const val R1_POWER_ON_RESET_BELOW_M = 100L
        private const val R1_POWER_ON_RESET_JUMP_M = 1000L
        private const val R1_DISTRUST_AFTER = 3
        private const val R1_STEP_CLAMP_M = 100L

        /**
         * Rider-readable labels for every bit of the 0x04 alert byte, in bit
         * order, or an empty list when nothing is set.
         *
         * Bit assignment from WheelLog's `GotwayAdapter` (GPL-3.0, layout
         * only), which builds the same list as a space-separated string:
         * bit 0 a general wheel alarm — routed to its own `setWheelAlarm` flag
         * there, with its only proposed label ("HighPower") commented out, so
         * the CAUSE is not actually documented and this names only the fact;
         * bit 1 Speed2, bit 2 Speed1, bit 3 LowVoltage, bit 4 OverVoltage,
         * bit 5 OverTemperature, bit 6 errHallSensors, bit 7 TransportMode.
         *
         * The wording follows [ru.sodovaya.volty.data.bms.vesc.VescFaults] —
         * sentence-case English phrases ("Over voltage", "Over temp FET"), not
         * screaming enum names and not bit indices — because that is the
         * register [ControllerData.faults] already speaks, it is what
         * `MotionAggregator` prefixes with a controller label
         * (`"ESC0: Over voltage"`) on a multi-controller vehicle, and it is
         * what `AlertEngine` drops verbatim into a notification a rider reads.
         * "Over voltage" is deliberately the SAME string VESC produces for the
         * same condition.
         *
         * **"Over temperature" is deliberately vaguer than VESC's.** That
         * register distinguishes "Over temp FET" from "Over temp motor"; this
         * frame carries ONE bit and does not say which sensor tripped. Naming
         * a sensor here would be a guess about the rider's hardware, so the
         * label stops where the evidence does — this is correct, not an
         * oversight waiting to be tidied into one of VESC's two strings.
         *
         * **This is the whole byte. [FAULT_BITS] is the part that is a fault.**
         *
         * ### Retraction: bit 0 used to be labelled "Wheel alarm"
         *
         * An earlier revision named bit 0 "Wheel alarm" and said we did not know what it meant. Wrong
         * about the meaning: the rider's two T4 free-spin dumps (2026-10-26/27) showed it switching on at
         * 68.4 km/h at 96.8 V, off again at 67.0, flickering around the threshold, with the settings word
         * `0x2800` (alarm mode 2, which WheelLog's adapter labels "80% PWM only"). It is the wheel's own
         * high-power alarm (WheelLog's "HighPower" -> `setWheelAlarm`), i.e. the wheel saying its PWM
         * crossed the alarm threshold. Hence "PWM alarm", and [ControllerData.wheelPwmAlarm].
         */
        private fun alertLabels(bits: Int): List<String> {
            if (bits == 0) return emptyList()
            return buildList {
                if (bits and PWM_ALARM_BIT != 0) add("PWM alarm")
                if (bits and 0x02 != 0) add("Speed alarm 2")
                if (bits and 0x04 != 0) add("Speed alarm 1")
                if (bits and 0x08 != 0) add("Low voltage")
                if (bits and 0x10 != 0) add("Over voltage")
                if (bits and 0x20 != 0) add("Over temperature")
                if (bits and 0x40 != 0) add("Hall sensor error")
                if (bits and 0x80 != 0) add("Transport mode")
            }
        }

        /**
         * The bits of the alert byte that are **faults**: low voltage (3), over
         * voltage (4), over temperature (5), hall-sensor error (6). Only these
         * reach [ControllerData.faults]; [wheelAlerts] still reports all eight.
         *
         * The four excluded bits are not faults, and putting them in the list
         * would do two concrete harms — both in `AlertEngine`, whose ONLY use
         * (there is no UI for controller faults; `FaultsBanner` renders the
         * battery's `bmsFaults`)
         * of [ControllerData.faults] is `triggered = faults.isNotEmpty()` on a
         * CRITICAL `CONTROLLER_FAULT` notification:
         *
         *  - **bits 1 and 2, the speed alarms, are ordinary riding.** They are
         *    the beeps the rider themselves configured (the same 0x04 frame
         *    carries the speed-alarm mode at bytes 6..7), and they engage and
         *    release on every brisk stretch. Each engagement would post a
         *    CRITICAL notification and each release would re-arm it — dozens
         *    per ride. `AlertEngine`'s own KDoc refuses exactly this for duty
         *    and speed ("one notification per excursion would be dozens of
         *    notifications per ride"), and routing the wheel's speed alarm
         *    through `faults` would reintroduce it by the side door. Volty
         *    already serves speed live and graded through `AlarmController`.
         *  - **bit 7, transport mode, is a MODE and it persists.** The wheel is
         *    being carried, not failing. `AlertEngine.fire` disarms a kind after
         *    firing and re-arms it only when `faults` goes EMPTY, so a wheel
         *    left in transport mode would hold the list non-empty indefinitely
         *    and the next genuine fault would raise nothing at all.
         *  - **bit 0, the wheel's PWM alarm** (see the retraction on [alertLabels]; this bullet
         *    used to call it "the general wheel alarm", whose meaning nobody knew), **says nothing a
         *    rider can act on as a fault** - it is published separately, as
         *    [ControllerData.wheelPwmAlarm]. WheelLog routes it to its own flag and leaves its only
         *    proposed label ("HighPower") commented out, so we do not know what
         *    it means. A CRITICAL notification reading *"Wheel alarm on ET Max"*
         *    teaches the rider nothing and cannot be acted on — that, not any
         *    claim about its timing, is why it is excluded. **Whether it
         *    persists is unknown**, and this comment used to assert that it
         *    latched; it does not have the evidence to.
         *
         * Any bit that is arguable is INCLUDED: low voltage means stop, and
         * telling the rider so is the point.
         *
         * ### What this filter does NOT fix
         *
         * **The masking harm above survives, through a bit that is included.**
         * Low voltage (3) is just as persistent as transport mode: a pack below
         * the wheel's threshold stays below it for the rest of the ride. Rider
         * trips low voltage at km 30 — one notification, armed goes false; at
         * km 34 the motor over-temps, `faults` becomes
         * `["Low voltage", "Over temperature"]`, still non-empty, nothing
         * re-arms, and **the rider is never told**. Excluding the four bits
         * MITIGATES this class; it does not close it, and no choice of
         * `FAULT_BITS` can, because the persistent-fault case is a real one.
         *
         * The fix is not here. `AlertEngine.fire` re-arms on the list being
         * empty; it should re-arm on the fault SET CHANGING, so a new fault
         * appearing beside an old one is a new event. That is Part F's
         * machinery and is written up as **`F §16`** ("A persistent fault
         * silences every later one") — recorded here too because this is where a
         * reader will be looking when they wonder why a second fault went
         * unannounced.
         */
        private const val FAULT_BITS = 0x78

        /** Bit 0 of the 0x04 alert byte: the wheel's own PWM (high-power) alarm. */
        private const val PWM_ALARM_BIT = 0x01

        /** Plausible battery temperature range, degrees Celsius. */
        private val TEMP_SANITY = -39..150

        /**
         * A cell sum this close to the 0x01 pack-voltage field means every cell
         * packet has landed — see [branchVoltage]. The real sum runs ~0.9 %
         * above the field; a single missing packet drops it ~20 % below.
         */
        private const val CELL_SUM_COMPLETE_RATIO = 0.9f

        /**
         * Whether [cellSum] is close enough to [frameVoltage] to trust as
         * [branchVoltage]'s PUBLISHED voltage in place of the 0x01 frame's own
         * field — a one-sided, LOOSE cutoff, correct for that decision and
         * wrong for any other.
         *
         * The real sum runs ~0.9 % ABOVE the frame field once a branch is
         * genuinely complete; one missing 8-cell packet out of five puts a
         * partial sum ~20 % BELOW it. [CELL_SUM_COMPLETE_RATIO] sits in that
         * gap, and for `branchVoltage`'s question — publish the cell sum, or
         * fall back to the frame field? — that gap is wide enough: both
         * candidates are still close to the true voltage, so picking either
         * one is a small error.
         *
         * **NOT the right test for confirming a cell COUNT, and must not be
         * reused for one — this function used to be [updateDerivedCellCount]'s
         * completeness gate, and that was a real defect caught in review.** On
         * the ET Max capture's own numbers a 36-of-40 run (a half-filled last
         * cell packet) sums to 90.76 % of the frame field — ABOVE this
         * function's 90 % cutoff — and would have LATCHED 36S, publishing
         * ~132.5 V for a 147.2 V rail with `hasInputVoltage = true`: a
         * confidently wrong voltage, worse than the absence this task set out
         * to remove. See [isCellCountConfirmed] for the tight band a count
         * decision needs instead, including
         * `KableBmsRepository.maybePersistCellCount`'s (Task 3).
         *
         * `private`: nothing outside `branchVoltage` should reach for this
         * one, and leaving it `internal` is exactly what invited the mix-up
         * above.
         */
        private fun isCellSumComplete(cellSum: Float, frameVoltage: Float): Boolean =
            cellSum >= frameVoltage * CELL_SUM_COMPLETE_RATIO

        /**
         * Whether [cellSum] is close enough to [frameVoltage] to CONFIRM a
         * candidate cell COUNT for [derivedCellCount] — a TIGHT band around
         * ratio 1.0, not [isCellSumComplete]'s loose one-sided cutoff.
         *
         * **The band is deliberately ASYMMETRIC, and review corrected an
         * earlier version of this KDoc that got the margins wrong** (it
         * claimed "roughly double the margin either side", which was neither
         * the right number nor, on reflection, the right shape for the band
         * to have at all).
         *
         * The arithmetic a reader can check the edges against — all measured
         * on the ET Max capture, branch 0, `cellSum / frameVoltage`:
         *  - complete (40 of 40): ratio **1.00851** (0.85 % ABOVE 1.0);
         *  - one cell short (39 of 40): ratio **0.98328** (1.67 % below 1.0);
         *  - two cells short (38 of 40): ratio **0.95806** (4.19 % below);
         *  - one packet short, 8 cells (32 of 40): ratio **0.80671** (19.33 %
         *    below).
         *
         * **Lower edge, [CELL_COUNT_LOWER_TOLERANCE] (1 %), stays TIGHT.** A
         * truncated run only ever LOWERS the sum — cells that have not
         * arrived cannot contribute — so the complete reading's 0.85 %
         * deviation and even a single missing cell's 1.67 % sit either side
         * of a 1 % cutoff with real but modest margin (0.15 pp above the
         * complete reading, 0.67 pp below the smallest real shortfall). A
         * truncated set is exactly what must be refused, and nothing
         * realistic pushes the sum low enough to threaten a false accept.
         *
         * **Upper edge, [CELL_COUNT_UPPER_TOLERANCE] (5 %), stays LOOSE, on
         * purpose.** A high ratio is not evidence of a wrong count at all —
         * "extra" cells cannot be counted, so nothing pushes a genuine sum
         * meaningfully above the frame field except the SAME kind of scale
         * discrepancy [branchVoltage] already documents (0.1009 V/unit
         * measured against the 0.1 the frame nominally uses). A wheel whose
         * own discrepancy runs a little larger than the one ET Max happens to
         * have would still be a genuinely complete, correctly-counted branch —
         * and the failure of over-tightening this edge is NOT symmetric with
         * the failure of under-tightening the lower one: too loose BELOW lets
         * a truncated set through as a CONFIDENT WRONG voltage (the defect
         * this task exists to fix); too TIGHT above silently returns a
         * perfectly good wheel to NO voltage at all — the exact bug this
         * whole part exists to remove, arriving quietly on hardware nobody
         * here has measured. 5 % gives that wheel real room while still
         * refusing a sum wildly inconsistent with its own frame field (see
         * `aCellSumWellAboveTheFrameFieldRefusesToDeriveACellCount`).
         *
         * **Not for a caller outside this class to reuse — the ruling that
         * closed this task's review was the opposite of an earlier version of
         * this note.** `KableBmsRepository.maybePersistCellCount` does not
         * recompute this ratio from a published `BmsData`: for a Begode wheel
         * it asks [derivedCellCount] on the live protocol instance directly,
         * because [branchVoltage] has already substituted the cell sum into
         * `BmsData.voltage` once [isCellSumComplete]'s LOOSE cutoff clears —
         * so a ratio recomputed from that published sample collapses to
         * `isCellSumComplete` no matter what threshold is used, and would
         * confirm the same stuck 36-of-40 branch this function's own KDoc
         * above describes as a real, caught defect. Only code inside this
         * class ever sees the raw, un-substituted frame field this band
         * needs. `private`, not `internal`: nothing outside this class reads
         * it today, and nothing outside this class safely can.
         */
        private fun isCellCountConfirmed(cellSum: Float, frameVoltage: Float): Boolean {
            val ratio = cellSum / frameVoltage
            return ratio >= 1f - CELL_COUNT_LOWER_TOLERANCE && ratio <= 1f + CELL_COUNT_UPPER_TOLERANCE
        }

        /**
         * Lower half-width of [isCellCountConfirmed]'s band — see that
         * function's KDoc for why this edge is tight and the measured numbers
         * it sits between.
         */
        private const val CELL_COUNT_LOWER_TOLERANCE = 0.01f

        /**
         * Upper half-width of [isCellCountConfirmed]'s band — see that
         * function's KDoc for why this edge is deliberately loose rather than
         * symmetric with [CELL_COUNT_LOWER_TOLERANCE].
         */
        private const val CELL_COUNT_UPPER_TOLERANCE = 0.05f

        /**
         * How far the average cell voltage, scaled by [LIVE_VOLTAGE_REFERENCE_V]
         * and divided by the live reading, may sit from [FULL_CELL_V] — as a
         * FRACTION of [FULL_CELL_V] — and still count as this wheel's live
         * frame following the 67.2 V / 16 S-equivalent reference convention
         * [scaleLiveVoltage] assumes.
         *
         * **Not a count check — see [updateDerivedCellCount]'s gate 2 KDoc.**
         * This used to be named and documented as a "cross-check" that voted
         * on the count alongside [isCellCountConfirmed]; review caught that
         * `count` cancels out of the comparison algebraically, so it cannot
         * distinguish a right count from a wrong one — verified: a 36-of-40
         * shortfall disagrees with the live frame by the same ~0.85 % a
         * genuinely complete 40-cell reading does. What it DOES catch is a
         * wheel whose live-frame reading does not follow the convention at
         * all, which [isCellCountConfirmed] cannot see (it never looks at the
         * live frame).
         *
         * Measured disagreement on the ET Max capture is ~0.85 % (the known
         * discontinuity [inputVoltageOrNull] documents: the live frame's 67.2 V
         * reference and the directly-summed cells are two independent
         * measurements of the same rail). 5 % gives roughly six times that
         * headroom for wheels not yet seen, the same order of magnitude
         * [SECTION_SPLIT_TOLERANCE_V] uses over ITS observed disagreement.
         */
        private const val LIVE_FRAME_CONVENTION_TOLERANCE = 0.05f

        /** Consecutive genuine 0x07 frames a one-way latch needs (Task 7). */
        private const val LATCH_CONFIRM_FRAMES = 2

        /** R13: how far a 0x01 pack voltage may sit from the scaled live voltage (fraction). Measured spread 0.01 %. */
        private const val R13_TOLERANCE = 0.03f

        /** A quarter of one series cell: 1 % at 24S, still far from the neighbouring whole counts. */
        private const val BRANCH_RATIO_TOLERANCE = 0.25f
        private const val BRANCH_RATIO_STREAK = 3
        private const val BRANCH_RATIO_MIN_FRAMES = 4
        private val BRANCH_RATIO_CELLS = 16..48

        /**
         * How far a candidate section's cell sum may sit from the assembly
         * voltage its 0x01 frame reported and still count as the same number.
         *
         * The observed disagreement on the ET Max capture is at most 0.09 V
         * (74.19 V of summed cells against a reported 74.1): the field's
         * 0.1 V quantisation, plus 1 mV cell quantisation, plus two
         * independent measurement paths. 0.5 V gives five times that headroom
         * for wheels not yet seen, while staying far below the ~2 V minimum a
         * single misplaced cell would shift both sums by — so the tolerance
         * can never make a wrong split pass while the right one fails, and
         * [verifiedSplitCellCount]'s uniqueness check backstops even that.
         */
        private const val SECTION_SPLIT_TOLERANCE_V = 0.5f
    }
}
