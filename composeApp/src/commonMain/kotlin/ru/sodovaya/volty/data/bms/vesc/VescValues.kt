package ru.sodovaya.volty.data.bms.vesc

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.PI
import kotlin.math.abs

/**
 * Decoders for the two VESC telemetry frames, pinned against VESC Tool's
 * `commands.cpp`. SETUP (opcode 47) is what we normally poll: it is the only
 * one carrying a controller-computed ground speed and battery level. Plain
 * GET_VALUES (opcode 4) is the fallback for setups that do not answer SETUP;
 * its speed must then be derived from eRPM (see 01-linking §2).
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
     *    clearable for the same reason `v_in` is — a thermistor is an external
     *    sensor and its absence has a sentinel on the wire.
     *  - **the four `amp_hours`/`watt_hours` counters are RAM totals kept from
     *    boot.** `0 Ah` on a VESC that booted a minute ago is a measurement, and
     *    it is the same four zeros a hypothetical firmware keeping no counters
     *    would send. The one witness worth testing — "all four are exactly 0
     *    while the odometer is not" — does not hold either: on the SETUP frame
     *    the tachometer is SETUP-WIDE (already summed across CAN by
     *    `mc_interface_get_setup_values`), so a freshly-booted node legitimately
     *    reports 0 counters beside a neighbour's non-zero distance; on
     *    `GET_VALUES` the tachometer is raw counts this decoder does not publish
     *    at all.
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
            speedKmh = speedMs * 3.6f,
            speedSource = reportedSpeedSource(speedMs, rpm),
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
            speedKmh = derived ?: 0f,
            speedSource = if (derived != null) SpeedSource.DERIVED else SpeedSource.NONE,
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
     */
    private fun reportedSpeedSource(speedMs: Float, rpm: Float): SpeedSource =
        if (speedMs != 0f || rpm == 0f) SpeedSource.REPORTED else SpeedSource.NONE

    /**
     * eRPM → ground speed. Mechanical RPM = eRPM / polePairs; wheel RPM =
     * mechanical / gearRatio (motor revolutions per wheel revolution). Null when
     * the wheel is unconfigured — an unknown speed must read as unknown, never 0.
     */
    fun derivedSpeedKmh(eRpm: Float, motor: MotorConfig): Float? {
        if (motor.wheelDiameterMm <= 0 || motor.polePairs <= 0 || motor.gearRatio <= 0f) return null
        val wheelRpm = eRpm / motor.polePairs / motor.gearRatio
        val circumferenceKm = (PI * motor.wheelDiameterMm / 1_000_000.0).toFloat()
        return wheelRpm * circumferenceKm * 60f
    }
}
