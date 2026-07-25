package ru.sodovaya.volty.data.bms.vesc

import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VescValuesTest {

    /** Builds a COMM_GET_VALUES_SETUP payload in the pinned field order. */
    private fun setupPayload(): ByteArray {
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
        i32(12000)                    // rpm
        i32(13056)                    // speed /1000 = 13.056 m/s = 47.0 km/h
        i16(782)                      // v_in /10 = 78.2
        i16(840)                      // battery_level /1000 = 0.840
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
        assertTrue(abs(d.powerW - (78.2f * 52.4f)) < 0.5f)
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

    @Test fun plain_get_values_derives_speed_and_has_no_reported_source() {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 4                        // opcode
        i16(520); i16(680)            // temps
        i32(-8250); i32(5240)         // motor / input current
        i32(0); i32(0)                // id, iq
        i16(760)                      // duty
        i32(10000)                    // rpm
        i16(782)                      // v_in
        i32(154000); i32(21000); i32(9800000); i32(1200000)   // Ah / Wh
        i32(1000); i32(2000)          // tachometer, tachometer_abs (raw counts)
        o += 0                        // fault
        val d = VescValues.decodeValues(o.toByteArray(), MotorConfig(polePairs = 15, wheelDiameterMm = 254))!!
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
