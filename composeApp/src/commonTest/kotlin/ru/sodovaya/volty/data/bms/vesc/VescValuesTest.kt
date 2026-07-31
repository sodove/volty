package ru.sodovaya.volty.data.bms.vesc

import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VescValuesTest {

    /**
     * Builds a COMM_GET_VALUES_SETUP payload in the pinned field order.
     *
     * The four parameters are the fields whose "this node did not measure it"
     * encoding `I` Task 7 taught this decoder — a `0` rail, a `0` battery level
     * and a `0` speed beside a turning motor. Defaults are a healthy uBox.
     */
    private fun setupPayload(
        rpm: Int = 12000,
        speedRaw: Int = 13056,
        vInRaw: Int = 782,
        battLevelRaw: Int = 840
    ): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 47                       // opcode
        i16(520)                      // temp_mos   /10  = 52.0
        i16(680)                      // temp_motor /10  = 68.0
        i32(-8250)                    // current_motor /100 = -82.5
        i32(5240)                     // current_in /100 = 52.4
        i16(760)                      // duty_now /1000 = 0.760
        i32(rpm)                      // rpm
        i32(speedRaw)                 // speed /1000 = 13.056 m/s = 47.0 km/h
        i16(vInRaw)                   // v_in /10 = 78.2
        i16(battLevelRaw)             // battery_level /1000 = 0.840
        i32(154000)                   // amp_hours /1e4 = 15.4
        i32(21000)                    // amp_hours_charged /1e4 = 2.1
        i32(9800000)                  // watt_hours /1e4 = 980.0
        i32(1200000)                  // watt_hours_charged /1e4 = 120.0
        i32(12400000)                 // tachometer /1e3 = 12400.0 m
        i32(1284600000)               // tachometer_abs /1e3 = 1284600.0 m = 1284.6 km
        i32(0)                        // position
        o += 0                        // fault_code = 0
        o += 11                       // vesc_id
        return o.toByteArray()
    }

    @Test fun setup_values_decode_every_field() {
        val d = VescValues.decodeSetupValues(setupPayload())!!
        assertTrue(abs(d.speedKmh - 47.0f) < 0.05f)
        assertEquals(SpeedSource.REPORTED, d.speedSource)
        assertTrue(d.speedKnown)
        assertTrue(abs(d.dutyPercent - 76.0f) < 0.01f)
        assertTrue(abs(d.motorCurrentA - (-82.5f)) < 0.01f)
        assertTrue(abs(d.batteryCurrentA - 52.4f) < 0.01f)
        assertTrue(abs(d.inputVoltageV - 78.2f) < 0.01f)
        assertTrue(d.hasInputVoltage)
        assertTrue(abs(d.powerW - (78.2f * 52.4f)) < 0.5f)
        assertTrue(d.hasPower)
        assertEquals(0.84f, d.batteryLevelFraction)
        assertEquals(12000f, d.eRpm)
        assertTrue(abs(d.escTempC - 52.0f) < 0.01f)
        assertTrue(abs(d.motorTempC - 68.0f) < 0.01f)
        assertTrue(d.hasMotorTemp)
        assertTrue(abs(d.odometerKm - 1284.6f) < 0.05f)
        assertTrue(abs(d.consumedAh - 15.4f) < 0.01f)
        assertTrue(abs(d.consumedWh - 980.0f) < 0.1f)
        assertTrue(abs(d.regenAh - 2.1f) < 0.01f)
        assertTrue(abs(d.regenWh - 120.0f) < 0.1f)
        assertTrue(d.faults.isEmpty())
        assertTrue(d.isConnected)
    }

    @Test fun duty_is_absolute_percent_when_braking() {
        val p = setupPayload().copyOf()
        // duty_now sits at offset 1+2+2+4+4 = 13 (after opcode + two i16 + two i32)
        val neg = -300
        p[13] = ((neg shr 8) and 0xFF).toByte(); p[14] = (neg and 0xFF).toByte()
        val d = VescValues.decodeSetupValues(p)!!
        assertTrue(abs(d.dutyPercent - 30.0f) < 0.01f)
    }

    @Test fun fault_code_maps_to_a_label() {
        val p = setupPayload().copyOf()
        p[p.size - 2] = 1                       // FAULT_CODE_OVER_VOLTAGE
        val d = VescValues.decodeSetupValues(p)!!
        assertEquals(listOf("Over voltage"), d.faults)
    }

    @Test fun missing_motor_sensor_reports_no_motor_temp() {
        val p = setupPayload().copyOf()
        val v = -2000                            // -200.0 °C sentinel of an unwired sensor
        p[3] = ((v shr 8) and 0xFF).toByte(); p[4] = (v and 0xFF).toByte()
        val d = VescValues.decodeSetupValues(p)!!
        assertTrue(!d.hasMotorTemp)
    }

    @Test fun truncated_payload_decodes_to_null_rather_than_throwing() {
        assertNull(VescValues.decodeSetupValues(setupPayload().copyOfRange(0, 12)))
    }

    /**
     * Regression pin for a length-guard off-by-2: the SETUP body (everything after the
     * opcode, through fault_code) is 55 bytes, not 53. A payload of total length 54 or 55
     * (i.e. 53 or 54 bytes after the opcode) must still decode to null rather than throwing
     * an ArrayIndexOutOfBoundsException while reading the trailing tachometer_abs/position/
     * fault_code fields. 56 bytes is exactly enough for the body (vesc_id, the 57th byte,
     * is never read by this decoder) and must decode successfully, as must the full frame.
     */
    @Test fun setup_payload_truncated_at_the_body_boundary_decodes_to_null_not_throws() {
        val full = setupPayload()
        assertEquals(57, full.size)
        assertNull(VescValues.decodeSetupValues(full.copyOfRange(0, 54)))
        assertNull(VescValues.decodeSetupValues(full.copyOfRange(0, 55)))
        assertTrue(VescValues.decodeSetupValues(full.copyOfRange(0, 56)) != null)
        assertTrue(VescValues.decodeSetupValues(full) != null)
    }

    // ---------------------------------------------------------------------------
    // `I` Task 7 — the known-flag contract gets a VESC producer that can reach it.
    //
    // Until now no VESC decoder could clear `hasInputVoltage`, `hasPower` or
    // `speedSource`, and `batteryLevelFraction` was published as a confident `0f`.
    // `G §9.3` was written up as fixed-by-flag while `BegodeProtocol` was the only
    // producer in the whole app that could ever reach the false branch.
    // ---------------------------------------------------------------------------

    @Test fun a_setup_reply_with_no_rail_reports_the_voltage_and_the_power_unmeasured() {
        val d = VescValues.decodeSetupValues(setupPayload(vInRaw = 0))!!
        assertEquals(0f, d.inputVoltageV)
        assertTrue(!d.hasInputVoltage, "0.0 V from a node that answered is an empty field, not a rail")
        assertEquals(0f, d.powerW)
        assertTrue(!d.hasPower, "power is v_in x current_in, so a phantom rail is a phantom power")
        // Every other field of the same reply is untouched — this clears two flags,
        // it does not reject the frame.
        assertTrue(abs(d.speedKmh - 47.0f) < 0.05f)
        assertTrue(abs(d.escTempC - 52.0f) < 0.01f)

        // The positive is reachable, and the boundary is `> 0`, not `>= 0`.
        assertTrue(VescValues.decodeSetupValues(setupPayload(vInRaw = 1))!!.hasInputVoltage)
        assertTrue(VescValues.decodeSetupValues(setupPayload(vInRaw = 1))!!.hasPower)
    }

    @Test fun a_plain_values_reply_with_no_rail_reports_the_voltage_and_the_power_unmeasured() {
        val healthy = VescValues.decodeValues(valuesPayload(), MotorConfig(wheelDiameterMm = 254))!!
        assertTrue(healthy.hasInputVoltage)
        assertTrue(healthy.hasPower)

        val d = VescValues.decodeValues(valuesPayload(vInRaw = 0), MotorConfig(wheelDiameterMm = 254))!!
        assertTrue(!d.hasInputVoltage)
        assertTrue(!d.hasPower)
        assertEquals(0f, d.powerW)
    }

    /**
     * `batteryLevelFraction` is the seed for a controller-derived battery, and `0f`
     * is VESC's "no battery configuration" — not a flat pack. `derivedBatteryFrom`
     * one file over already tests it with `> 0f`; publishing the `0` made it a real
     * term of `MotionAggregator`'s average, so one uBox at 84 % beside a node with
     * no configuration reported the vehicle at 42 %.
     */
    @Test fun a_setup_reply_with_no_battery_configuration_reports_no_battery_level() {
        assertNull(VescValues.decodeSetupValues(setupPayload(battLevelRaw = 0))!!.batteryLevelFraction)
        assertEquals(0.84f, VescValues.decodeSetupValues(setupPayload())!!.batteryLevelFraction)
        // The boundary: one thousandth is a configuration, and a nearly-flat pack
        // must not be mistaken for an absent one.
        assertEquals(
            0.001f,
            VescValues.decodeSetupValues(setupPayload(battLevelRaw = 1))!!.batteryLevelFraction
        )
    }

    /**
     * `speedSource` was hardcoded `REPORTED` here, which made
     * `ControllerData.speedKnown` unfalsifiable for every VESC and put a permanent,
     * believed **0 km/h** on the dashboard of any node that answers opcode 47
     * without a speed pipeline behind it.
     *
     * `rpm` is the witness and the only one on the frame: VESC's
     * `mc_interface_get_speed()` is that same `rpm` scaled by the setup's wheel
     * geometry, so a zero speed beside a turning motor is a zero SCALE.
     */
    @Test fun a_setup_reply_whose_speed_field_is_empty_beside_a_turning_motor_is_not_reported() {
        val unconfigured = VescValues.decodeSetupValues(setupPayload(rpm = 12000, speedRaw = 0))!!
        assertEquals(SpeedSource.NONE, unconfigured.speedSource)
        assertTrue(!unconfigured.speedKnown, "a 0 the geometry produced is not a measurement of 0 km/h")

        // A vehicle that is genuinely standing still keeps its speed gauge: 0 rpm and
        // 0 speed agree, so the field is a reading.
        val stationary = VescValues.decodeSetupValues(setupPayload(rpm = 0, speedRaw = 0))!!
        assertEquals(SpeedSource.REPORTED, stationary.speedSource)
        assertEquals(0f, stationary.speedKmh)

        // Any non-zero speed is a measurement whatever the rpm — including a reversing
        // one, whose speed `I` Task 10 publishes as a MAGNITUDE (the election below
        // is unaffected: it is decided on the raw field, and `abs(0) == 0`).
        assertEquals(SpeedSource.REPORTED, VescValues.decodeSetupValues(setupPayload())!!.speedSource)
        val reversing = VescValues.decodeSetupValues(setupPayload(rpm = -12000, speedRaw = -13056))!!
        assertEquals(SpeedSource.REPORTED, reversing.speedSource)
        // And a motor turning backwards with an empty speed field is still empty.
        assertEquals(
            SpeedSource.NONE,
            VescValues.decodeSetupValues(setupPayload(rpm = -12000, speedRaw = 0))!!.speedSource
        )
    }

    // ---------------------------------------------------------------------------
    // `I` Task 10 — the OTHER decoder was still signing its speed.
    //
    // Task 1 took the magnitude in `BegodeProtocol` and justified it with "so the
    // two protocols agree on what the shared field means"; both VESC sources still
    // published a signed one, so the claim was an intention. These two tests are
    // what makes it a fact. The consequence-level pin is
    // `domain/stats/VescForwardRideConsumptionTest`.
    // ---------------------------------------------------------------------------

    /**
     * A reversing controller — or one whose motor direction is configured the
     * other way round, which reports a negative speed while moving FORWARD —
     * publishes the same magnitude as one configured the other way.
     *
     * The pair of assertions is what distinguishes `abs` from a NEGATION: a
     * negating decoder answers 47.0 for the reversing sample and fails the
     * equality.
     */
    @Test fun a_setup_reply_from_a_reversing_controller_publishes_the_speed_as_a_magnitude() {
        val forward = VescValues.decodeSetupValues(setupPayload(rpm = 12000, speedRaw = 13056))!!
        val reversing = VescValues.decodeSetupValues(setupPayload(rpm = -12000, speedRaw = -13056))!!

        assertEquals(SpeedSource.REPORTED, reversing.speedSource, "precondition: it is a measurement")
        assertTrue(abs(reversing.speedKmh - 47.0f) < 0.05f, "got ${reversing.speedKmh}")
        assertEquals(
            forward.speedKmh, reversing.speedKmh, 0.001f,
            "one motion, two motor-direction conventions, one speed — which a negation would not give"
        )

        // The direction is NOT discarded, and this is why VESC needs no
        // equivalent of Begode's `signedSpeedKmh()`: the frame's own `rpm` field
        // carries it, unchanged, beside the magnitude.
        assertEquals(-12000f, reversing.eRpm, "the sign lives on eRpm and stays there")
        assertEquals(12000f, forward.eRpm)
    }

    /**
     * The eRPM-derived source takes the same magnitude, at the same place — the
     * assignment into `ControllerData.speedKmh`.
     *
     * [VescValues.derivedSpeedKmh] itself stays a FAITHFUL signed conversion,
     * asserted below: it is this decoder's signed intermediate, the counterpart
     * of `BegodeProtocol.signedSpeedKmhValue`, and taking the magnitude inside it
     * would put the convention somewhere other than the point of publication.
     */
    @Test fun plain_get_values_from_a_reversing_motor_derives_a_positive_speed() {
        val wheel = MotorConfig(polePairs = 15, wheelDiameterMm = 254, gearRatio = 1f)
        val forward = VescValues.decodeValues(valuesPayload(rpm = 10000), wheel)!!
        val reversing = VescValues.decodeValues(valuesPayload(rpm = -10000), wheel)!!

        assertEquals(SpeedSource.DERIVED, reversing.speedSource, "precondition: the wheel is configured")
        assertTrue(abs(reversing.speedKmh - 31.9f) < 0.3f, "got ${reversing.speedKmh}")
        assertEquals(forward.speedKmh, reversing.speedKmh, 0.001f, "one motion, one speed")
        assertEquals(-10000f, reversing.eRpm, "the sign lives on eRpm and stays there")

        val signed = VescValues.derivedSpeedKmh(-10000f, wheel)!!
        assertTrue(
            abs(signed - (-31.9f)) < 0.3f,
            "the conversion keeps the sign; the magnitude is taken where the value is PUBLISHED. got $signed"
        )
    }

    @Test fun wrong_opcode_is_rejected() {
        val p = setupPayload().copyOf(); p[0] = 99
        assertNull(VescValues.decodeSetupValues(p))
    }

    @Test fun derived_speed_from_erpm_and_wheel() {
        // 10000 eRPM / 15 pole pairs = 666.67 mech RPM; 254 mm wheel.
        val kmh = VescValues.derivedSpeedKmh(10000f, MotorConfig(polePairs = 15, wheelDiameterMm = 254, gearRatio = 1f))!!
        assertTrue(abs(kmh - 31.9f) < 0.3f, "got $kmh")
    }

    @Test fun derived_speed_is_null_without_a_wheel_diameter() {
        assertNull(VescValues.derivedSpeedKmh(10000f, MotorConfig(wheelDiameterMm = 0)))
    }

    /** Builds a COMM_GET_VALUES payload in the pinned field order. */
    private fun valuesPayload(rpm: Int = 10000, vInRaw: Int = 782): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 4                        // opcode
        i16(520); i16(680)            // temps
        i32(-8250); i32(5240)         // motor / input current
        i32(0); i32(0)                // id, iq
        i16(760)                      // duty
        i32(rpm)                      // rpm
        i16(vInRaw)                   // v_in
        i32(154000); i32(21000); i32(9800000); i32(1200000)   // Ah / Wh
        i32(1000); i32(2000)          // tachometer, tachometer_abs (raw counts)
        o += 0                        // fault
        return o.toByteArray()
    }

    @Test fun plain_get_values_derives_speed_and_has_no_reported_source() {
        val d = VescValues.decodeValues(valuesPayload(), MotorConfig(polePairs = 15, wheelDiameterMm = 254))!!
        assertEquals(SpeedSource.DERIVED, d.speedSource)
        assertTrue(abs(d.speedKmh - 31.9f) < 0.3f)
        assertTrue(abs(d.dutyPercent - 76.0f) < 0.01f)
        assertEquals(0f, d.odometerKm)   // raw counts are not metres — not reported
    }

    @Test fun plain_get_values_without_wheel_config_reports_speed_unknown() {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 4; i16(520); i16(680); i32(0); i32(0); i32(0); i32(0); i16(0); i32(10000); i16(782)
        i32(0); i32(0); i32(0); i32(0); i32(0); i32(0); o += 0
        val d = VescValues.decodeValues(o.toByteArray(), MotorConfig(wheelDiameterMm = 0))!!
        assertEquals(SpeedSource.NONE, d.speedSource)
        assertTrue(!d.speedKnown)
    }
}
