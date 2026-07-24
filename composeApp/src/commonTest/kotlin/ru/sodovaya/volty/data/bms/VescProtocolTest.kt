package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescPacket
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

    private fun setupFrame(speedMs: Int = 13056, vIn: Int = 782, currentIn: Int = 5240, battLevel: Int = 840): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 47; i16(520); i16(680); i32(-8250); i32(currentIn); i16(760); i32(12000); i32(speedMs)
        i16(vIn); i16(battLevel); i32(154000); i32(21000); i32(9800000); i32(1200000)
        i32(12400000); i32(1284600000); i32(0); o += 0; o += 11
        return VescPacket.frame(o.toByteArray())
    }

    @Test fun poll_asks_for_the_setup_frame() {
        val p = VescProtocol()
        assertTrue(p.handshakeCommands().isEmpty())
        val poll = p.pollCommands()
        assertEquals(1, poll.size)
        assertEquals(47, poll[0][2].toInt())          // start, len, opcode
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
