package ru.sodovaya.volty.data.bms.vesc

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.SpeedUnknownReason
import kotlin.math.PI
import kotlin.math.abs

/**
 * Decoders for the two VESC telemetry frames, pinned against VESC Tool's
 * `commands.cpp`. SETUP (opcode 47) is what we normally poll: it is the only
 * one carrying a controller-computed ground speed and battery level. Plain
 * GET_VALUES (opcode 4) is the fallback for setups that do not answer SETUP;
 * its speed must then be derived from eRPM (see 01-linking §2).
 *
 * ## The published speed is a MAGNITUDE, and both sources take it
 *
 * `I` Task 10. Both frames sign their speed — SETUP's `speed` field is
 * `mc_interface_get_speed()`, plain GET_VALUES' figure is derived from a signed
 * `rpm` — and a controller whose motor direction is configured the other way
 * round reports NEGATIVE while riding **forward**. That is the same
 * firmware-variance case the first hardware ride found on the rider's two
 * Begodes (field report `2026-07-30-first-hardware-test` S1), and it fails
 * SILENTLY in the worst direction, because every consumer of
 * [ControllerData.speedKmh] treats it as a non-negative quantity compared
 * against UPPER thresholds:
 *
 *  - `RideMetrics.instantWhPerKm` nulls consumption below `MIN_SPEED_KMH = 0.5`,
 *    which every negative speed is — and unlike a Begode this protocol keeps
 *    energy counters, so the gauge falls back to the session average and shows
 *    the rider a *different confident number* rather than a blank;
 *  - `MotionAggregator` does NOT attenuate it. Its speed fold is a `maxOf`, but
 *    over `d.measuring { it.speedKnown }` — so a negative contributor that is the
 *    only measuring one (a single-VESC vehicle, i.e. most of them) is the max,
 *    and it reaches the aggregate unchanged. Nothing downstream is protected by
 *    the fold; the guarantee has to be here;
 *  - the Ride dashboard's session peak cannot advance;
 *  - the SPEED alarm is graded against upper thresholds and cannot fire.
 *
 * **Not a negation.** Which sign means forward is per-setup, so negating fixes
 * one configuration and breaks the opposite one into the same silent failure.
 * VESC Tool itself makes the polarity a PREFERENCE rather than a fact
 * (`VescInterface::speedGaugeUseNegativeValues`, default true, read by
 * `mobile/RtDataSetup.qml`) — but it only draws a needle, and a needle has none
 * of the consumers listed above.
 *
 * **Taken at the two assignments into [ControllerData.speedKmh]**, one per
 * decoder, which is the same place and the same rule as
 * `BegodeProtocol.parseLiveFrame`. So the two protocols now genuinely agree
 * about what the shared field means, which Task 1 claimed before it was true.
 *
 * **The direction is not destroyed PER CONTROLLER, and that is why no signed
 * accessor was added.** Begode needed `signedSpeedKmh()` because no other field
 * on its frame carried the sign; here [ControllerData.eRpm] does, unchanged, on
 * BOTH sources and on every path that publishes a controller sample — including
 * [ru.sodovaya.volty.data.bms.VescGatewayProtocol]'s SETUP overlay, which copies
 * the speed but never the eRPM beside it (and which publishes nothing at all
 * without a per-unit decode to fold it into). [derivedSpeedKmh] stays a faithful
 * signed conversion for the same reason.
 *
 * **The qualifier is load-bearing: it does NOT survive the fold.**
 * `MotionAggregator`'s `eRpm` fold is an unfiltered `maxOf`, so a controller
 * reversing at -12 000 eRPM beside anything reporting `0` — every Begode does —
 * gives a vehicle-level `eRpm` of `0`. Nothing reads it for direction today, and
 * `I` Task 10 deliberately left the fold alone; but anyone building a reverse
 * indicator must take it from a CONTROLLER sample, not from the aggregate. The
 * same warning sits at the fold itself.
 */
object VescValues {

    const val OPCODE_GET_VALUES: Int = 4
    const val OPCODE_GET_VALUES_SETUP: Int = 47

    /** A motor temperature this low means "no sensor wired", not a cold motor. */
    private const val NO_MOTOR_SENSOR_BELOW_C = -50f

    /**
     * A `v_in` at or below this means "this node does not measure the rail",
     * not a dead pack — the producer half of [ControllerData.hasInputVoltage],
     * which until now **no VESC decoder could ever clear** (`G §9.3` was written
     * up as fixed-by-flag while `BegodeProtocol` was the only producer in the
     * app that could reach the false branch).
     *
     * Exactly `0`, not a plausibility band. A powered VESC reads its own rail
     * through a divider that cannot report a true zero while the board is alive
     * enough to answer an opcode, so `0.0 V` in a decoded reply is the
     * unpopulated field of a node that answers the frame without measuring it —
     * a VESC Express bridge is the shape one firmware update away (class KDoc of
     * [ru.sodovaya.volty.data.bms.VescGatewayProtocol]). Any wider band would
     * start rejecting genuinely low readings, and "a rail below N volts is not a
     * rail" is a claim about packs we have not seen.
     *
     * [ControllerData.hasPower] takes the same test rather than a separate one:
     * `powerW` here IS `v_in × current_in`, so a phantom rail makes the power a
     * phantom `0 W` by construction. The two flags still fold differently
     * downstream, which is why they remain two fields.
     *
     * ## Why [ControllerData.hasDuty] and [ControllerData.hasEnergyCounters] are NOT cleared here
     *
     * `I` Task 7's brief asked for all four flags to become assignable. Two of
     * them are, above. The other two are left at their `true` default **because
     * these frames carry no witness that could clear them**, and a flag set from
     * a guess is worse than a flag left honest. Stated here rather than omitted,
     * so a later reader does not read the asymmetry as an oversight.
     *
     * The asymmetry is not arbitrary. `v_in` is a MEASUREMENT OF AN EXTERNAL
     * QUANTITY through an ADC: a node can answer the frame without having the
     * sensor behind it, and a powered board reporting a true `0.0 V` rail is
     * physically impossible — so `0` is decidable evidence of absence. The other
     * two fields are not measurements of anything outside the firmware:
     *
     *  - **`duty_now` is the controller's own commanded state variable.** Any
     *    firmware that runs a motor and answers opcode 4 or 47 necessarily has
     *    one, so there is nothing to "not have", and `0.000` is a genuine
     *    reading — a coasting or idle wheel, which is most of a ride. There is no
     *    `duty == 0 && x` conjunction that separates the two: a freewheeling
     *    motor at high `rpm` with zero duty is exactly what descending a hill
     *    looks like. Contrast [ControllerData.hasMotorTemp] just above, which IS
     *    clearable — but only on ONE of its two paths, and the distinction
     *    matters because this contrast is what makes the rule falsifiable:
     *
     *      - a **failed reading** (an unwired NTC divider reading 0 on the ADC,
     *        so the conversion divides by zero) produces NaN/inf/out-of-range,
     *        and `mc_interface.c:2303-2305` clamps that to `-100.0` — below
     *        [NO_MOTOR_SENSOR_BELOW_C], so it IS a sentinel on the wire and
     *        [hasMotorTemp] catches it. That is the path the rule predicts and
     *        it holds;
     *      - `TEMP_SENSOR_DISABLED` — the NORMAL setting on a motor with no
     *        thermistor — takes `mc_interface.c:2295-2296`,
     *        `temp_motor = motor->m_temp_override`, which is zero-initialised
     *        and only ever written by an LBM script (`:1788`). So the wire
     *        carries **`0.0 °C`**, and this decoder reads it as a real 0 °C with
     *        `hasMotorTemp = true`.
     *
     *    **That second path is a producer-side absence we cannot detect at
     *    all** — the same debt as the `escTempC` phantom recorded on
     *    [ControllerData.hasEscTemp], and for the same reason: an unmeasured
     *    quantity published as a plausible number, with nothing on the frame to
     *    say so. It is not fixed here and it is not fixable from these bytes.
     *  - **the four `amp_hours`/`watt_hours` counters are RAM totals kept from
     *    boot.** `0 Ah` on a VESC that booted a minute ago is a measurement, and
     *    it is the same four zeros a hypothetical firmware keeping no counters
     *    would send. The one witness worth testing — "all four are exactly 0
     *    while the odometer is not" — fails in **both** directions, and the
     *    firmware is the other way round from what an earlier revision of this
     *    comment claimed:
     *
     *      - **false positives.** `mc_interface.c:2015` integrates all four
     *        counters only `if (fabsf(current_filtered) > 1.0)`, while the
     *        distance behind them accumulates from raw rotor-angle steps with no
     *        current condition at all (`mcpwm_foc.c:3799-3811`). A vehicle
     *        pushed or rolled after a fresh boot therefore shows four zero
     *        counters beside a genuinely non-zero odometer, on ONE node, with no
     *        CAN neighbour involved;
     *      - **false negatives.** The counters ARE CAN-summed — that is exactly
     *        what `mc_interface_get_setup_values()` does
     *        (`mc_interface.c:1655-1679`, `ah_tot`/`wh_tot` accumulate every
     *        live `can_status_msg_2`/`_3`) — so a counter-less node sharing a
     *        bus with a real VESC reports NON-ZERO counters and the witness
     *        misses the case it exists to detect.
     *
     *    The distances are **not** setup-wide, which is where the earlier claim
     *    inverted the firmware: the SETUP frame's two distance fields are
     *    `mc_interface_get_distance()` / `_abs()` (`commands.c:853,856`), and
     *    those read this node's OWN tachometer scaled by its OWN wheel config
     *    (`mc_interface.c:1624-1643`). `mc_interface_get_setup_values()` never
     *    touches a tachometer.
     *
     * **Coupling them to `v_in` was considered and rejected.** A node that
     * answers with a wholly zeroed struct has a phantom duty as surely as a
     * phantom rail — but the fields are independent on the wire, and a
     * controller whose rail divider is unfitted while its motor runs is a
     * shape nobody has ruled out. `hasDuty` folds with `any`, so clearing it
     * would change nothing on a multi-controller vehicle and would blank a
     * REAL ШИМ alarm on a single-controller one: "a worse bug than the
     * unmeasured-duty one the flag exists for", in the words of the fold that
     * already refuses this trade
     * ([ru.sodovaya.volty.domain.stats.MotionAggregator]).
     */
    private const val NO_RAIL_AT_OR_BELOW_V = 0f

    // Per-field widths, named so the body-length sums below are self-documenting
    // and cannot silently drift out of sync with the fields actually read.
    private const val I8_BYTES = 1
    private const val I16_BYTES = 2
    private const val I32_BYTES = 4

    /**
     * Bytes read after the opcode in [decodeSetupValues]:
     * temp_mos(i16) + temp_motor(i16) + current_motor(i32) + current_in(i32) +
     * duty_now(i16) + rpm(i32) + speed(i32) + v_in(i16) + battery_level(i16) +
     * amp_hours(i32) + amp_hours_charged(i32) + watt_hours(i32) +
     * watt_hours_charged(i32) + tachometer(i32) + tachometer_abs(i32) +
     * position(i32) + fault_code(i8) = 55.
     */
    private const val SETUP_BODY_BYTES =
        I16_BYTES + I16_BYTES +
            I32_BYTES + I32_BYTES +
            I16_BYTES +
            I32_BYTES +
            I32_BYTES +
            I16_BYTES + I16_BYTES +
            I32_BYTES + I32_BYTES +
            I32_BYTES + I32_BYTES +
            I32_BYTES + I32_BYTES +
            I32_BYTES +
            I8_BYTES

    /**
     * Bytes read after the opcode in [decodeValues]:
     * temp_mos(i16) + temp_motor(i16) + current_motor(i32) + current_in(i32) +
     * id(i32) + iq(i32) + duty_now(i16) + rpm(i32) + v_in(i16) +
     * amp_hours(i32) + amp_hours_charged(i32) + watt_hours(i32) +
     * watt_hours_charged(i32) + tachometer(i32) + tachometer_abs(i32) +
     * fault_code(i8) = 53.
     */
    private const val VALUES_BODY_BYTES =
        I16_BYTES + I16_BYTES +
            I32_BYTES + I32_BYTES +
            I32_BYTES + I32_BYTES +
            I16_BYTES +
            I32_BYTES +
            I16_BYTES +
            I32_BYTES + I32_BYTES +
            I32_BYTES + I32_BYTES +
            I32_BYTES + I32_BYTES +
            I8_BYTES

    fun decodeSetupValues(payload: ByteArray): ControllerData? {
        val r = VescReader(payload)
        if (!r.has(1) || r.u8() != OPCODE_GET_VALUES_SETUP) return null
        if (!r.has(SETUP_BODY_BYTES)) return null
        val tempMos = r.d16(10f)
        val tempMotor = r.d16(10f)
        val currentMotor = r.d32(100f)
        val currentIn = r.d32(100f)
        val duty = r.d16(1000f)
        val rpm = r.d32(1f)
        val speedMs = r.d32(1000f)
        val vIn = r.d16(10f)
        val battLevel = r.d16(1000f)
        val ampHours = r.d32(1e4f)
        val ampHoursChg = r.d32(1e4f)
        val wattHours = r.d32(1e4f)
        val wattHoursChg = r.d32(1e4f)
        val tachM = r.d32(1000f)
        val tachAbsM = r.d32(1000f)
        r.d32(1e6f)                                   // position — unused
        val fault = r.i8()
        return ControllerData(
            // MAGNITUDE — see this object's KDoc for why, and why not a
            // negation. The source election below is deliberately decided on the
            // RAW field: `abs` cannot change "is it zero", so which side of it
            // the election sits on is inert, and the raw value is what
            // [reportedSpeedSource]'s witness rule is written about.
            speedKmh = abs(speedMs) * 3.6f,
            speedSource = reportedSpeedSource(speedMs, rpm).first,
            speedUnknownReason = reportedSpeedSource(speedMs, rpm).second,
            dutyPercent = abs(duty) * 100f,
            motorCurrentA = currentMotor,
            batteryCurrentA = currentIn,
            inputVoltageV = vIn,
            hasInputVoltage = vIn > NO_RAIL_AT_OR_BELOW_V,
            powerW = vIn * currentIn,
            hasPower = vIn > NO_RAIL_AT_OR_BELOW_V,
            eRpm = rpm,
            escTempC = tempMos,
            motorTempC = tempMotor,
            hasMotorTemp = tempMotor > NO_MOTOR_SENSOR_BELOW_C,
            odometerKm = tachAbsM / 1000f,
            tripKm = tachM / 1000f,
            consumedAh = ampHours,
            consumedWh = wattHours,
            regenAh = ampHoursChg,
            regenWh = wattHoursChg,
            faults = listOfNotNull(VescFaults.label(fault)),
            isConnected = true,
            // `0` is "this node has no battery configuration", not a flat pack —
            // the same test `derivedBatteryFrom` (VescProtocol.kt) already
            // applies one file over. Publishing the 0 made it a real term of
            // `MotionAggregator`'s average: one uBox at 84 % beside a node with
            // no configuration reported the vehicle at 42 %.
            batteryLevelFraction = battLevel.takeIf { it > 0f }
        )
    }

    fun decodeValues(payload: ByteArray, motor: MotorConfig): ControllerData? {
        val r = VescReader(payload)
        if (!r.has(1) || r.u8() != OPCODE_GET_VALUES) return null
        if (!r.has(VALUES_BODY_BYTES)) return null
        val tempMos = r.d16(10f)
        val tempMotor = r.d16(10f)
        val currentMotor = r.d32(100f)
        val currentIn = r.d32(100f)
        r.d32(100f); r.d32(100f)                      // id, iq — unused
        val duty = r.d16(1000f)
        val rpm = r.d32(1f)
        val vIn = r.d16(10f)
        val ampHours = r.d32(1e4f)
        val ampHoursChg = r.d32(1e4f)
        val wattHours = r.d32(1e4f)
        val wattHoursChg = r.d32(1e4f)
        r.i32(); r.i32()                              // tachometer counts — NOT metres; not reported
        val fault = r.i8()
        val derived = derivedSpeedKmh(rpm, motor)
        return ControllerData(
            // MAGNITUDE — see this object's KDoc. [derivedSpeedKmh] itself stays
            // a faithful signed conversion; the magnitude is taken HERE, at the
            // assignment into the shared field, exactly as it is in
            // [decodeSetupValues] and in `BegodeProtocol.parseLiveFrame`.
            speedKmh = derived?.let { abs(it) } ?: 0f,
            speedSource = if (derived != null) SpeedSource.DERIVED else SpeedSource.NONE,
            speedUnknownReason = if (derived != null) null else SpeedUnknownReason.NO_WHEEL_GEOMETRY,
            dutyPercent = abs(duty) * 100f,
            motorCurrentA = currentMotor,
            batteryCurrentA = currentIn,
            inputVoltageV = vIn,
            hasInputVoltage = vIn > NO_RAIL_AT_OR_BELOW_V,
            powerW = vIn * currentIn,
            hasPower = vIn > NO_RAIL_AT_OR_BELOW_V,
            eRpm = rpm,
            escTempC = tempMos,
            motorTempC = tempMotor,
            hasMotorTemp = tempMotor > NO_MOTOR_SENSOR_BELOW_C,
            consumedAh = ampHours,
            consumedWh = wattHours,
            regenAh = ampHoursChg,
            regenWh = wattHoursChg,
            faults = listOfNotNull(VescFaults.label(fault)),
            isConnected = true
        )
    }

    /**
     * Whether a SETUP reply's `speed` field is a MEASUREMENT — `REPORTED` — or
     * an unconfigured node's empty field, which must read as [SpeedSource.NONE]
     * rather than as a confident 0 km/h.
     *
     * This used to be hardcoded `REPORTED`, which made
     * [ControllerData.speedKnown] unfalsifiable for every VESC and put a
     * permanent, believed **0 km/h** on the dashboard of any node that answers
     * opcode 47 without a speed pipeline behind it.
     *
     * **`rpm` is the witness, and it is the only one on the frame.** VESC's
     * `mc_interface_get_speed()` is a linear function of the same `rpm` this
     * reply also carries, scaled by the setup's wheel diameter, gear ratio and
     * pole count. So:
     *
     *  - `speed != 0` — a measurement, whatever the geometry is;
     *  - `speed == 0` **and** `rpm == 0` — the motor is not turning, and 0 km/h
     *    is then a genuine reading. A stationary vehicle must keep its speed
     *    gauge, not blank it;
     *  - `speed == 0` **while `rpm != 0`** — the only combination the physics
     *    forbids. The scale factor is zero, i.e. the setup has no wheel
     *    configured, and the field is empty rather than low.
     *
     * Per-frame and stateless, unlike `BegodeProtocol`'s `truePWM` latch: this
     * one needs no evidence to accumulate because the disproof is on the same
     * frame as the claim.
     *
     * **Unaffected by `I` Task 10's magnitude**, and the argument is one line:
     * every test here is `== 0f` / `!= 0f`, and `abs` preserves both. The caller
     * passes the RAW field so this stays true by construction rather than by the
     * caller remembering to.
     */
    private fun reportedSpeedSource(speedMs: Float, rpm: Float): Pair<SpeedSource, SpeedUnknownReason?> =
        if (speedMs != 0f || rpm == 0f) {
            SpeedSource.REPORTED to null
        } else {
            SpeedSource.NONE to SpeedUnknownReason.FIRMWARE_DID_NOT_REPORT
        }

    /**
     * eRPM → ground speed. Mechanical RPM = eRPM / polePairs; wheel RPM =
     * mechanical / gearRatio (motor revolutions per wheel revolution). Null when
     * the wheel is unconfigured — an unknown speed must read as unknown, never 0.
     *
     * **SIGNED**, deliberately: this is a unit conversion, and it inherits
     * [eRpm]'s sign untouched. This decoder's published speed is a magnitude
     * (see the object KDoc), but the `abs` belongs at the assignment into
     * [ControllerData.speedKmh] rather than in here — **one convention, stated
     * at the point of publication**, so it holds for the SETUP path too, which
     * never calls this and would otherwise need the rule stated a second way.
     */
    fun derivedSpeedKmh(eRpm: Float, motor: MotorConfig): Float? {
        if (motor.wheelDiameterMm <= 0 || motor.polePairs <= 0 || motor.gearRatio <= 0f) return null
        val wheelRpm = eRpm / motor.polePairs / motor.gearRatio
        val circumferenceKm = (PI * motor.wheelDiameterMm / 1_000_000.0).toFloat()
        return wheelRpm * circumferenceKm * 60f
    }
}
