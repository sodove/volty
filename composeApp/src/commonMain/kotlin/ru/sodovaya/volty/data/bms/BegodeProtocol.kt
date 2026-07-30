package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs

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
    private val cellCount: Int? = null
) : BmsProtocol(), MotionSource {

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

    /** Last decoded lifetime odometer in metres — see [odometerMeters]. */
    private var odometerMetersValue: Long = 0L

    /** True once any 0x04 frame arrived. */
    private var sawOdometer = false

    /**
     * Session baseline for [ControllerData.tripKm]: the lifetime odometer
     * reading at the FIRST genuine 0x04 frame of this connection. Null until
     * that frame arrives, and cleared by [reset] so a reconnect starts the trip
     * over at 0.
     *
     * The same shape [VescProtocol] uses, and deliberately so — see
     * [sessionTripKm] for why the wheel's own power-on counter cannot be
     * `tripKm` even though it is decoded.
     */
    private var tripBaselineMeters: Long? = null

    /** Last decoded 0x04 alert bitmap (byte 14) — see [parseOdometerFrame]. */
    private var alertBitmap: Int = 0

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

    /** Last decoded hardware duty, percent — see [dutyPercent]. */
    private var dutyPercentValue: Float = 0f

    /**
     * WheelLog's `truePWM` latch: true once the 0x07 duty field has been seen
     * NON-ZERO at least once. Until then the wheel has not proved it reports a
     * hardware duty at all, and [dutyPercent] withholds the value instead of
     * publishing a zero that may just be an unimplemented field.
     */
    private var sawTrueDuty = false

    /**
     * True once ANY smart-BMS frame (0x01/0x02/0x03) was decoded. Not every
     * Begode has a smart BMS — the T4 and older wheels likely stream only
     * 0x00 and 0x04 — and this flag is what decides between the two modes:
     * while false, pack 0 is synthesised from the live frame ([liveData]);
     * the first BMS frame retires the synthetic pack permanently (until
     * [reset]), so it can never override real branch data.
     */
    private var smartBmsSeen = false

    /**
     * The synthetic pack of a wheel without a smart BMS, rebuilt from every
     * genuine live frame: phase current, board temperature, no cells — and
     * `voltage = 0`, because the live-frame voltage is on the 67.2 V scale
     * and the scale factor needs a cell count this protocol must not invent.
     * Callers that know the cell count scale [liveVoltageOn672ScaleV].
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
     *  - **one convention, stated once per decoder**, is what stops this protocol
     *    and VESC's disagreeing about what `ControllerData.speedKmh` means.
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
     * **Decoded but deliberately unsurfaced**, the same shape as
     * [powerOnDistanceMeters], [tiltbackSpeed] and [wheelAlerts]: it has no
     * production caller and must not acquire one by accident. It exists so that
     * the signedness of bytes 4..5 stays a *testable* decode rather than a claim
     * in a comment (an unsigned read of a gentle roll answers 2340 km/h in either
     * accessor), and so a later part that wants a reverse indicator starts from a
     * decode that works instead of from a field whose sign was thrown away.
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
     * `coerceAtLeast(0)` for the same reason VESC's does: the baseline is a
     * lifetime counter that only accumulates, so a negative delta would mean
     * the wheel contradicted itself, and a negative distance helps nobody.
     */
    private fun sessionTripKm(): Float {
        val odometer = odometerMeters() ?: return 0f
        val baseline = tripBaselineMeters ?: return 0f
        return (odometer - baseline).coerceAtLeast(0L) / 1000f
    }

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
     * **Null until the field has been seen non-zero once** ([sawTrueDuty],
     * WheelLog's `truePWM` latch). A frame full of zeros cannot distinguish "0
     * % duty" from "this firmware does not fill the field in", and arming the
     * ШИМ alarm against a constant zero is precisely the silent failure §7.2
     * describes. After the latch, later zeros ARE published — by then the
     * field has proved itself.
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
     * **Duty in the not-yet-known window.** [dutyPercent] is null until the
     * hardware PWM field has been seen non-zero once, and that window is NOT
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
     * Task 2 recorded that choosing 0 made the latch INVISIBLE here —
     * `dutyPercent() ?: 0f` and the raw `dutyPercentValue` were the same number
     * at every instant, so no test of this class could tell the latch from its
     * absence, and the residual risk was a firmware that never fills the PWM
     * field leaving duty at 0 forever while `reportsDuty[BEGODE] = true` kept
     * the ШИМ alert armed against that constant. **[hasDuty] closes both.** It
     * is the same latch, published where a caller can act on it, and
     * `availabilityFor` now answers DUTY `Unavailable` on a sample that carries
     * it false instead of arming an alarm that could never fire. On the ET Max
     * the latch closes inside the first 0x07 frame — a balancing wheel spends
     * 2 % standing still — so this window is a fraction of a second in
     * practice, and the guard is for the firmware nobody here has seen.
     *
     * **Absent distances.** [odometerKm] and [tripKm] read 0 before the frames
     * that carry them arrive. 0 is also a real reading (a wheel out of its box,
     * a trip that has not started), so this one genuinely cannot be
     * distinguished — [ControllerData] has no encoding for it and inventing a
     * negative distance would be worse. Both fields fill in within a second of
     * connecting.
     *
     * **[tripKm] is the SESSION delta**, derived from the odometer against a
     * baseline taken at this connection's first 0x04 frame — the same thing
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
    private fun rebuildMotion() {
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
            isConnected = true
        )
    }

    /**
     * The wheel's rail voltage in real volts, or **null** meaning UNKNOWN —
     * published as `inputVoltageV = 0f` with `hasInputVoltage = false`, which is
     * `G §9`'s contract for a field whose type cannot carry an absence.
     *
     * The live frame reports voltage against Begode's 67.2 V reference (every
     * wheel pretends to be 16S), and turning that into volts needs the pack's
     * cell count — 40S x 4.2 V = 168 V is the x2.5 an ET Max needs. WheelLog
     * does not derive this either: it makes the rider pick a multiplier from a
     * list (1.0 / 1.25 / 1.5 / 1.738 / 2.0 / 2.25 / 2.5). Volty does better by
     * taking it from the cell count the vehicle profile already holds — but
     * only when the caller supplied one ([cellCount]). With no cell count the
     * honest answer is to publish nothing: the raw reading would render as
     * 58.92 V on a 168 V wheel, and a wrong voltage is worse than none because
     * `powerW` is built from it and the dashboard shows both.
     *
     * Unlike [liveVoltageOn672ScaleV] this does NOT stop at the smart-BMS
     * hand-over. That gate exists to stop the synthetic PACK from overriding
     * real branch data; a controller's input voltage is the wheel's own
     * measurement of its rail and stays valid whether or not a smart BMS is
     * also reporting cells.
     */
    private fun inputVoltageOrNull(): Float? {
        if (!sawLiveMotion) return null
        val cells = cellCount ?: return null
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

    override fun reset() {
        buffer.reset()
        branches.forEach { it.reset() }
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
        odometerMetersValue = 0L
        sawOdometer = false
        // The trip is per-CONNECTION by definition: a reconnect starts a new
        // session and must start its trip at 0, not carry the last one's
        // baseline (which would make the new session open at the old one's
        // distance). Same reason VescProtocol.reset clears its baseline.
        tripBaselineMeters = null
        // A reconnect may face a healthy wheel: the previous one's alerts must
        // not survive it, or a cleared fault would keep firing.
        alertBitmap = 0
        faultsValue = emptyList()
        tiltbackSpeedValue = null
        batteryCurrentAValue = 0f
        motorTempCValue = 0f
        sawMotionFrame = false
        // Per-wheel evidence, exactly like the duty latch: a wheel whose motor
        // thermistor is unwired must not inherit the previous wheel's proof.
        sawMotorTempEvidence = false
        dutyPercentValue = 0f
        // The duty latch is per-wheel evidence: a wheel that never proved it
        // reports duty must not inherit the previous wheel's proof.
        sawTrueDuty = false
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
            // FORWARD negative on two wheels. One convention, stated once per
            // decoder, is what stops two protocols disagreeing about what the
            // shared field means. The signed value is kept above rather than
            // discarded — see [signedSpeedKmh].
            speedKmhValue = abs(signedSpeedKmhValue)
            powerOnMetersValue = powerOnMeters
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
        }
        // While no smart-BMS frame has arrived, this frame IS the battery
        // telemetry: synthesise pack 0 from it so a wheel without a smart BMS
        // connects at all instead of staying null forever. Gated on a genuine
        // voltage — a boot-zero live frame would synthesise a 0.00 V pack
        // with the temperature formula's raw-zero artefact (36.53 C).
        if (!smartBmsSeen && liveVoltageRaw > 0) {
            liveData = BmsData(
                // Unscaled on purpose — see [liveVoltageOn672ScaleV]. Power
                // is voltage-derived and equally unknowable here.
                voltage = 0f,
                current = phaseCurrentA,
                power = 0f,
                // No fuel gauge and no cells: soc = 0 here means "unknown",
                // not "empty", and downstream must not alarm on it. The
                // estimator flips this back to true the moment the vehicle
                // profile's cell count makes a voltage estimate possible.
                socKnown = false,
                cellVoltages = emptyList(),
                temperatures = listOf(boardTempC),
                // Same rationale as [rebuild]: a streaming wheel is not cut
                // off, and false renders alarming red OFF badges.
                chargeEnabled = true,
                dischargeEnabled = true,
                isConnected = true
            )
        }
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
        odometerMetersValue = frame.u32BE(2)
        val tiltbackRaw = frame.i16BE(10)
        // WheelLog's own rule: >= 100 is the wheel's "unset" marker rather than
        // a speed. 0 is unset too — a wheel that tilts back at a standstill is
        // not a configuration, it is a field nobody filled in.
        tiltbackSpeedValue =
            if (tiltbackRaw in 1 until TILTBACK_UNSET_AT) tiltbackRaw.toFloat() else null
        alertBitmap = frame.u8(14)
        faultsValue = alertLabels(alertBitmap and FAULT_BITS)
        sawOdometer = true
        // The trip's session baseline: the FIRST genuine odometer reading of
        // this connection, so `tripKm` counts from where the rider connected
        // rather than from where the wheel was last switched on. See
        // [sessionTripKm].
        if (tripBaselineMeters == null) tripBaselineMeters = odometerMetersValue
        rebuildMotion()
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
        if (motorTempRaw != 0) sawMotorTempEvidence = true
        motorTempCValue = motorTempRaw.toFloat()
        val dutyRaw = frame.i16BE(8)
        // WheelLog's truePWM latch: one non-zero reading is what proves the
        // firmware fills this field in at all. Before that a zero is not a
        // measurement of zero duty. Read off the RAW value, ahead of the
        // magnitude below, so a wheel that only ever reports negative PWM still
        // proves it reports duty.
        if (dutyRaw != 0) sawTrueDuty = true
        // MAGNITUDE, clamped to the 0..100 [dutyPercent]'s contract promises —
        // the same statement VescValues makes with `abs(duty) * 100`, made in
        // the decoder rather than in [rebuildMotion] so the two protocols agree
        // on what the shared field means. See [dutyPercent] for the negative
        // case (a silent ШИМ alarm) and for why the upper clamp fails loud.
        dutyPercentValue = abs(dutyRaw).toFloat().coerceAtMost(DUTY_MAX_PERCENT)
        sawMotionFrame = true
        // NOTE: this frame must never write [phaseCurrentA] or [boardTempC].
        // That used to be a dead store — [parseLiveFrame] reassigns both from
        // its own bytes before the only read — but the controller sample below
        // reads them right here, so a leak would now be observable, and it is
        // pinned by a test.
        rebuildMotion()
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
        retireSyntheticPack()
        val branch = branches[bmsnum shr 1]
        val section = bmsnum and 1

        branch.sawTelemetry = true
        branch.packVoltageV = frame.u16BE(6) * 0.1f
        branch.currentA = frame.i16BE(8) * 0.1f

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

        rebuild(branch)
    }

    /**
     * Cell voltages (0x02 = branch 0, 0x03 = branch 1). Byte 19 is the packet
     * index; each frame carries 8 cells at bytes 2..17, big-endian millivolts.
     * Cell number = packetIndex * 8 + i.
     */
    private fun parseCells(frame: ByteArray, branch: Int) {
        retireSyntheticPack()
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
        rebuild(state)
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
     */
    private fun rebuild(branch: BranchState) {
        if (!branch.sawTelemetry) return
        val cells = contiguousCells(branch.cells)
        val voltage = branchVoltage(branch, cells)
        val current = branch.currentA
        branch.lastData = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
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
        return if (cellSum >= frameVoltage * CELL_SUM_COMPLETE_RATIO) cellSum else frameVoltage
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
         * 67.2`. Static because the protocol itself never has a cell count to
         * call it with — the caller supplies one from the vehicle profile
         * (user-set, or auto-filled from a prior smart-BMS connect).
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
         */
        private fun alertLabels(bits: Int): List<String> {
            if (bits == 0) return emptyList()
            return buildList {
                if (bits and 0x01 != 0) add("Wheel alarm")
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
         *  - **bit 0, the general wheel alarm, says nothing a rider can act
         *    on.** WheelLog routes it to its own flag and leaves its only
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

        /** Plausible battery temperature range, degrees Celsius. */
        private val TEMP_SANITY = -39..150

        /**
         * A cell sum this close to the 0x01 pack-voltage field means every cell
         * packet has landed — see [branchVoltage]. The real sum runs ~0.9 %
         * above the field; a single missing packet drops it ~20 % below.
         */
        private const val CELL_SUM_COMPLETE_RATIO = 0.9f

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
