package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.SectionState
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
 *   0x00 — live motherboard frame (scaled voltage, speed, trip distance,
 *          phase current, MPU temp)
 *   0x01 — smart-BMS telemetry; byte 19 is bmsnum 0..3 (branch = bmsnum shr 1,
 *          section within the branch = bmsnum and 1)
 *   0x02 / 0x03 — cell voltages of branch 0 / branch 1, 8 cells per frame,
 *          packet index at byte 19
 *   0x04 — total odometer, wheel settings and an alert bitmap
 *          (only the odometer is decoded — see [parseOdometerFrame])
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
 * Based on: WheelLog GotwayAdapter (GPL-3.0, layout only) and a real capture
 * from a Begode ET Max — see docs/superpowers/specs/2026-07-21-multi-pack-bms-design.md
 * and BegodeDumpFixture.
 */
class BegodeProtocol : BmsProtocol() {

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

    /** Last decoded speed, km/h — see [speedKmh] for what "last" means. */
    private var speedKmhValue: Float = 0f

    /** Last decoded trip distance in metres — see [tripDistanceMeters]. */
    private var tripMetersValue: Long = 0L

    /** True once a genuine (non-boot) live frame supplied speed and trip. */
    private var sawLiveMotion = false

    /** Last decoded lifetime odometer in metres — see [odometerMeters]. */
    private var odometerMetersValue: Long = 0L

    /** True once any 0x04 frame arrived. */
    private var sawOdometer = false

    /** Last decoded battery current, A — see [batteryCurrentA]. */
    private var batteryCurrentAValue: Float = 0f

    /** Last decoded motor temperature, °C — see [motorTempC]. */
    private var motorTempCValue: Float = 0f

    /** True once any 0x07 frame arrived. */
    private var sawMotionFrame = false

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
     * NEGATIVE while the wheel rolls backwards: the field is signed (see
     * [SPEED_KMH_PER_UNIT]). Callers that want a magnitude take abs; a caller
     * that wants direction has it.
     */
    fun speedKmh(): Float? = if (sawLiveMotion) speedKmhValue else null

    /**
     * Trip distance in METRES since the wheel was powered on, from the live
     * frame, or null before any genuine live frame — same reasoning as
     * [speedKmh], and 0 m is a perfectly ordinary trip value.
     *
     * Metres, not kilometres, because metres is what the frame carries; the
     * unit conversion is a presentation concern. The field is 16-bit, so it
     * WRAPS at 65 535 m — a rider who passes 65.5 km on one charge sees the
     * trip restart. That is the wheel's behaviour, not a decode artefact, and
     * inventing a wider counter here would be inventing data.
     */
    fun tripDistanceMeters(): Long? = if (sawLiveMotion) tripMetersValue else null

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
     * MOTOR temperature in °C from the 0x07 frame, or null before one arrives.
     *
     * The ET Max reads 20 °C here against 27.5 °C on the mainboard sensor, so
     * this wheel plainly HAS a motor thermistor — spec §2's "wheels usually
     * expose one board temp, `hasMotorTemp = false`" does not hold for it, and
     * Task 2 should set that flag from whether this returns null rather than
     * from the spec's prose.
     */
    fun motorTempC(): Float? = if (sawMotionFrame) motorTempCValue else null

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
     * No derivation is offered when the wheel reports nothing: WheelLog's
     * `calculatePwm()` fallback needs rider-configured rotation-speed,
     * rotation-voltage and power-factor constants that Volty does not have,
     * and a guess in this field is worse than an honest absence.
     */
    fun dutyPercent(): Float? = if (sawTrueDuty) dutyPercentValue else null

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
        tripMetersValue = 0L
        sawLiveMotion = false
        odometerMetersValue = 0L
        sawOdometer = false
        batteryCurrentAValue = 0f
        motorTempCValue = 0f
        sawMotionFrame = false
        dutyPercentValue = 0f
        // The duty latch is per-wheel evidence: a wheel that never proved it
        // reports duty must not inherit the previous wheel's proof.
        sawTrueDuty = false
        // A reconnect may face a different wheel: a protocol stuck in
        // "smart BMS seen" would leave a dumb wheel dataless again.
        smartBmsSeen = false
        liveData = null
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
     * - bytes 4..5 signed BE: speed, see [SPEED_KMH_PER_UNIT].
     * - bytes 6..7: **not decoded**, and not part of the trip. `0x003d` = 61
     *   in every frame of the capture. If bytes 6..9 were one 32-bit trip
     *   counter, as an out-of-date comment in WheelLog's own source claims,
     *   this wheel's trip would read 61 × 65536 m = 3 998 km — on a wheel
     *   whose LIFETIME odometer is 8 565 km and whose trip resets at power-on.
     *   WheelLog's parser reads only 8..9, and the capture agrees with the
     *   parser rather than with the comment.
     * - bytes 8..9 unsigned BE: trip distance in metres, see
     *   [tripDistanceMeters].
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
        val tripMeters = frame.u16BE(8).toLong()
        phaseCurrentA = frame.i16BE(10) * 0.01f
        boardTempC = frame.i16BE(12) / 340f + 36.53f
        // Motion takes the same genuineness gate as the synthetic pack below:
        // a zero-padded boot frame would otherwise publish "0.00 km/h, 0 m"
        // as measurement. Independent of [smartBmsSeen] — motion belongs to the
        // wheel, and a wheel with a smart BMS still moves.
        if (liveVoltageRaw > 0) {
            speedKmhValue = speedRaw * SPEED_KMH_PER_UNIT
            tripMetersValue = tripMeters
            sawLiveMotion = true
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
     * The REST of this frame is left alone on purpose, and it is not empty:
     * bytes 6..7 pack pedal mode, speed alarms, roll angle and an in-miles
     * flag; 8..9 the power-off timer; 10..11 the tiltback speed (the capture
     * reads 200, which WheelLog treats as "unset"); byte 14 is an alert bitmap
     * — speed alarm x2, low voltage, over voltage, over temperature,
     * hall-sensor error, transport mode — and reads 0 here, i.e. no faults.
     * That bitmap is a real `ControllerData.faults` source for a later task;
     * it is recorded rather than decoded so that task starts from a fact.
     */
    private fun parseOdometerFrame(frame: ByteArray) {
        odometerMetersValue = frame.u32BE(2)
        sawOdometer = true
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
        batteryCurrentAValue = -frame.i16BE(2) * BATTERY_CURRENT_A_PER_UNIT
        motorTempCValue = frame.i16BE(6).toFloat()
        val dutyRaw = frame.i16BE(8)
        // WheelLog's truePWM latch: one non-zero reading is what proves the
        // firmware fills this field in at all. Before that a zero is not a
        // measurement of zero duty.
        if (dutyRaw != 0) sawTrueDuty = true
        dutyPercentValue = dutyRaw.toFloat()
        sawMotionFrame = true
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
         * Taken from WheelLog's source, not guessed: `GotwayAdapter` converts
         * this field as `round(raw * 3.6)` into a speed field whose unit is
         * hundredths of km/h (`RIDING_SPEED = 200 // 2km/h`, and a debug line
         * setting the same field to `50_00` for 50 km/h), i.e.
         * `km/h = raw * 3.6 / 100`. WheelLog's own frame-layout comment states
         * the same thing in words: *"BE speed, fixed point, 3.6 * value / 100
         * km/h"*. Two independent statements in one file agreeing on 0.036.
         *
         * **Still unconfirmed by any capture of THIS wheel**, and that gap
         * cannot be closed here: bytes 4..5 read `00 00` in all 38 live frames
         * of the ET Max capture, which is 13 seconds of a wheel standing
         * still. So the tests pin the field's OFFSET and SIGNEDNESS with
         * synthetic frames and the scale against WheelLog's documented one;
         * what they cannot do is catch a firmware that disagrees with
         * WheelLog.
         *
         * **What would close it:** one `:dumper` capture of a moving wheel
         * beside a known reference speed (GPS, or the wheel's own app).
         *
         * (History, because it nearly shipped: this constant was 0.01 for one
         * commit — "hundredths of km/h" from memory rather than from source —
         * which reads 3.6x LOW, and a correction to 0.36 was proposed, which
         * reads 10x HIGH. The source settles it at 0.036.)
         */
        private const val SPEED_KMH_PER_UNIT = 0.036f

        /**
         * Amperes per unit of the 0x07 frame's signed battery-current field.
         * WheelLog stores current in hundredths of an amp and passes this
         * field through unscaled apart from a sign flip — see
         * [batteryCurrentA].
         */
        private const val BATTERY_CURRENT_A_PER_UNIT = 0.01f

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
