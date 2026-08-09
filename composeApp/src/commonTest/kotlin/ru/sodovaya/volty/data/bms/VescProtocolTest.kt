package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescSetupConfig
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VescProtocolTest {

    private fun setupFrame(
        speedMs: Int = 13056,
        vIn: Int = 782,
        currentIn: Int = 5240,
        battLevel: Int = 840,
        tachM: Int = 12400000,
        tachAbsM: Int = 1284600000
    ): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 47; i16(520); i16(680); i32(-8250); i32(currentIn); i16(760); i32(12000); i32(speedMs)
        i16(vIn); i16(battLevel); i32(154000); i32(21000); i32(9800000); i32(1200000)
        i32(tachM); i32(tachAbsM); i32(0); o += 0; o += 11
        return VescPacket.frame(o.toByteArray())
    }

    private fun setupConfigFrame(
        poles: Int = 30,
        gear: Float = 1f,
        diameterM: Float = .6f
    ): ByteArray {
        val o = mutableListOf<Byte>()
        fun f32(v: Float) {
            val bits = v.toBits()
            o += (bits ushr 24).toByte(); o += (bits ushr 16).toByte()
            o += (bits ushr 8).toByte(); o += bits.toByte()
        }
        o += VescSetupConfig.OPCODE_GET_MCCONF_TEMP.toByte()
        repeat(3) { f32(0f) }
        f32(100_000f)
        repeat(3) { f32(0f) }
        f32(10_000f)
        f32(0f)
        f32(60f)
        o += poles.toByte()
        f32(gear)
        f32(diameterM)
        return VescPacket.frame(o.toByteArray())
    }

    private fun valuesFrame(): ByteArray = VescPacket.frame(VescTestFrames.valuesPayload())

    private fun VescProtocol.issuedOpcodes(): List<Int> =
        pollCommands().map { it[2].toInt() }

    @Test fun poll_asks_for_the_setup_frame() {
        val p = VescProtocol()
        val handshake = p.handshakeCommands()
        assertEquals(1, handshake.size)
        assertEquals(VescSetupConfig.OPCODE_GET_MCCONF_TEMP, handshake[0][2].toInt())
        val poll = p.pollCommands()
        assertEquals(listOf(47, 4), poll.map { it[2].toInt() }) // start, len, opcode
    }

    @Test fun `a plain VESC answering only GET_VALUES produces motion and settles on opcode 4`() {
        val p = VescProtocol(motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254))

        assertEquals(listOf(47, 4), p.issuedOpcodes())
        p.onNotification(valuesFrame())

        assertNotNull(p.latestMotion(0), "the opcode-4 reply must become a motion sample")
        assertEquals(listOf(4), p.issuedOpcodes())
    }

    @Test fun `a plain VESC answering only SETUP keeps opcode 47 telemetry`() {
        val p = VescProtocol()

        assertEquals(listOf(47, 4), p.issuedOpcodes())
        p.onNotification(setupFrame())

        assertEquals(SpeedSource.REPORTED, p.latestMotion(0)?.speedSource)
        assertEquals(listOf(47), p.issuedOpcodes())
    }

    @Test fun `a VESC answering both values opcodes settles on richer SETUP and stops asking opcode 4`() {
        val p = VescProtocol(motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254))

        assertEquals(listOf(47, 4), p.issuedOpcodes())
        p.onNotification(valuesFrame())
        p.onNotification(setupFrame())

        assertEquals(SpeedSource.REPORTED, p.latestMotion(0)?.speedSource)
        assertEquals(listOf(47), p.issuedOpcodes())
        assertEquals(listOf(47), p.issuedOpcodes(), "opcode 4 must stay gone after SETUP proves available")
    }

    @Test fun `a silent VESC receives a bounded probe instead of permanently alternating opcodes`() {
        val p = VescProtocol()

        val issued = List(5) { p.issuedOpcodes() }

        assertEquals(listOf(47, 4), issued[0])
        assertEquals(listOf(47, 4), issued[1])
        assertEquals(listOf(47, 4), issued[2])
        assertEquals(listOf(47), issued[3])
        assertEquals(listOf(47), issued[4])
    }

    @Test fun `reset restores the bounded values-opcode probe for the next connection`() {
        val p = VescProtocol()
        p.pollCommands()
        p.onNotification(setupFrame())
        assertEquals(listOf(47), p.issuedOpcodes(), "premise: SETUP was selected")

        p.reset()

        assertEquals(listOf(47, 4), p.issuedOpcodes())
    }

    @Test fun configuration_reply_is_available_once_decoded() {
        val p = VescProtocol()
        assertNull(p.latestControllerConfig(0))
        p.onNotification(setupConfigFrame())
        val config = assertNotNull(p.latestControllerConfig(0))
        assertEquals(15, config.motorConfig?.polePairs)
        assertEquals(600, config.motorConfig?.wheelDiameterMm)
        assertEquals(1f, config.motorConfig?.gearRatio)
    }

    @Test fun configuration_is_cleared_on_reset_and_requested_again_after_reconnect() {
        val p = VescProtocol()
        assertEquals(1, p.handshakeCommands().size)
        p.onNotification(setupConfigFrame())
        assertNotNull(p.latestControllerConfig(0))
        p.reset()
        assertNull(p.latestControllerConfig(0))
        assertEquals(1, p.handshakeCommands().size)
    }

    @Test fun notification_produces_motion() {
        val p = VescProtocol()
        assertNull(p.latestMotion(0))
        p.onNotification(setupFrame())
        val m = p.latestMotion(0)!!
        assertTrue(abs(m.speedKmh - 47.0f) < 0.05f)
        assertEquals(SpeedSource.REPORTED, m.speedSource)
        assertEquals(1, p.controllerCount)
    }

    // --- tripKm: distance since this connection started, not the ESC's since-boot tachometer ---

    @Test fun first_frame_of_a_connection_starts_the_trip_at_zero() {
        val p = VescProtocol()
        p.onNotification(setupFrame(tachAbsM = 1284600000))
        assertEquals(0f, p.latestMotion(0)!!.tripKm)
    }

    @Test fun trip_is_distance_travelled_since_the_first_frame_not_the_raw_counter() {
        val p = VescProtocol()
        p.onNotification(setupFrame(tachAbsM = 1284600000))         // session baseline: 1284.6 km on the ESC's odometer
        p.onNotification(setupFrame(tachAbsM = 1289600000))         // ESC odometer advanced 5.0 km
        val m = p.latestMotion(0)!!
        assertTrue(abs(m.tripKm - 5.0f) < 0.01f, "expected ~5.0 km travelled this session, got ${m.tripKm}")
        // and NOT the raw absolute odometer reading itself:
        assertTrue(abs(m.tripKm - 1289.6f) > 1f)
    }

    @Test fun reversing_the_vehicle_does_not_make_trip_negative() {
        val p = VescProtocol()
        p.onNotification(setupFrame(tachM = 12400000, tachAbsM = 1284600000))
        // The vehicle backs up: the signed tachometer moves backwards, but the
        // absolute counter that tripKm is derived from only ever adds distance.
        p.onNotification(setupFrame(tachM = 12000000, tachAbsM = 1284700000))
        val m = p.latestMotion(0)!!
        assertTrue(m.tripKm >= 0f, "trip must never go negative on reverse, got ${m.tripKm}")
        assertTrue(abs(m.tripKm - 0.1f) < 0.01f, "expected 0.1 km of (reverse) travel, got ${m.tripKm}")
    }

    @Test fun reset_starts_a_new_trip_session_at_zero() {
        val p = VescProtocol()
        p.onNotification(setupFrame(tachAbsM = 1284600000))
        p.onNotification(setupFrame(tachAbsM = 1289600000))
        assertTrue(p.latestMotion(0)!!.tripKm > 0f)

        p.reset()

        // Same (still-advancing) ESC odometer reading as before reset, but this is the
        // first frame of a brand-new connection, so the session must start over at 0.
        p.onNotification(setupFrame(tachAbsM = 1289600000))
        assertEquals(0f, p.latestMotion(0)!!.tripKm, "a new connection must start a fresh session at 0")
    }

    @Test fun a_frame_split_across_chunks_still_decodes() {
        val p = VescProtocol()
        setupFrame().toList().chunked(20).forEach { p.onNotification(it.toByteArray()) }
        assertNotNull(p.latestMotion(0))
    }

    @Test fun each_decode_is_a_new_instance_so_the_motion_gate_lets_it_through() {
        val p = VescProtocol()
        p.onNotification(setupFrame())
        val first = p.latestMotion(0)
        assertSame(first, p.latestMotion(0))          // cached between frames
        p.onNotification(setupFrame())
        assertTrue(first !== p.latestMotion(0))       // new frame ⇒ new instance
    }

    @Test fun derived_battery_uses_the_controller_battery_level() {
        val p = VescProtocol(deriveBattery = true)
        assertEquals(1, p.packCount)
        p.onNotification(setupFrame())
        val b = p.latestData(0)!!
        assertTrue(abs(b.voltage - 78.2f) < 0.01f)
        assertTrue(abs(b.soc - 84.0f) < 0.01f)
        assertTrue(b.socKnown)
        // VESC input current is positive while DISCHARGING; BmsData is + = charging.
        assertTrue(b.current < 0f, "discharge must read negative, got ${b.current}")
        assertTrue(b.power < 0f)
        assertTrue(b.cellVoltages.isEmpty())
        assertTrue(b.isConnected)
    }

    @Test fun regen_flips_the_derived_battery_current_positive() {
        val p = VescProtocol(deriveBattery = true)
        p.onNotification(setupFrame(currentIn = -1200))
        assertTrue(p.latestData(0)!!.current > 0f)
    }

    @Test fun without_a_battery_level_soc_is_left_unknown_for_the_estimator() {
        // battLevel = 0 is what a VESC with no battery config reports.
        val p = VescProtocol(deriveBattery = true)
        p.onNotification(setupFrame(battLevel = 0))
        val b = p.latestData(0)!!
        assertEquals(0f, b.soc)
        assertTrue(!b.socKnown, "unknown SoC must be flagged so VoltageSocEstimator can fill it in")
    }

    @Test fun derive_battery_off_means_no_pack() {
        val p = VescProtocol(deriveBattery = false)
        assertEquals(0, p.packCount)
        p.onNotification(setupFrame())
        assertNull(p.latestData(0))
        assertNotNull(p.latestMotion(0))
    }

    @Test fun reset_clears_decoded_state() {
        val p = VescProtocol()
        p.onNotification(setupFrame())
        p.reset()
        assertNull(p.latestMotion(0))
    }

    @Test fun get_values_fallback_mode_polls_opcode_4() {
        val p = VescProtocol(useSetupFrame = false, motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254))
        assertEquals(4, p.pollCommands()[0][2].toInt())
    }
}
