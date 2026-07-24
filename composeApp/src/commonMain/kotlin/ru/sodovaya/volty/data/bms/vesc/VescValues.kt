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

    fun decodeSetupValues(payload: ByteArray): ControllerData? {
        val r = VescReader(payload)
        if (!r.has(1) || r.u8() != OPCODE_GET_VALUES_SETUP) return null
        // temps(2+2) currents(4+4) duty(2) rpm(4) speed(4) vin(2) batt(2)
        // ah(4) ahc(4) wh(4) whc(4) tach(4) tachAbs(4) pos(4) fault(1) = 53
        if (!r.has(53)) return null
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
            speedSource = SpeedSource.REPORTED,
            dutyPercent = abs(duty) * 100f,
            motorCurrentA = currentMotor,
            batteryCurrentA = currentIn,
            inputVoltageV = vIn,
            powerW = vIn * currentIn,
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
            batteryLevelFraction = battLevel
        )
    }

    fun decodeValues(payload: ByteArray, motor: MotorConfig): ControllerData? {
        val r = VescReader(payload)
        if (!r.has(1) || r.u8() != OPCODE_GET_VALUES) return null
        // temps(4) currents(8) id/iq(8) duty(2) rpm(4) vin(2) ah/wh(16) tach(8) fault(1) = 53
        if (!r.has(53)) return null
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
            powerW = vIn * currentIn,
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
