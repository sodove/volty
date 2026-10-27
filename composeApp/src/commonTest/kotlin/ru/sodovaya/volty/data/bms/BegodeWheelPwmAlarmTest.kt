package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.stats.MotionAggregator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The wheel's own PWM alarm (0x04 alert byte, bit 0) as data. */
class BegodeWheelPwmAlarmTest {

    // The rider's T4 dump: the 0x04 frame whose alert byte (14) reads 0x01.
    private val alarmOnHex = "55AA002ADCAD28001C2000C800000100001204185A5A5A5A"

    private fun frame(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** The same frame with byte 14 (the alert byte) replaced. */
    private fun withAlert(bits: Int): ByteArray = frame(alarmOnHex).also { it[14] = bits.toByte() }

    /** A live 0x00 frame (real dump-3 bytes) whose power-on counter is 0, matching [withAlert]'s constant odometer. */
    private fun liveFrame(): ByteArray = frame("55AA1906F743006A0000FE5CF1D208A000050018" + "5A5A5A5A")

    /** A 0x07 motion frame (battery current 16, duty 2): motion exists, no 0x04 frame has been seen. */
    private fun motionOnly(): ByteArray = frame("55AA00100001000A000200000000000000000718" + "5A5A5A5A")

    @Test
    fun `the rider's 0x04 frame has bit 0 set and publishes the alarm`() {
        val p = BegodeProtocol()
        p.onNotification(frame(alarmOnHex))
        assertEquals(true, assertNotNull(p.latestMotion(0)).wheelPwmAlarm)
    }

    @Test
    fun `null before any 0x04 frame`() {
        val p = BegodeProtocol()
        assertNull(p.latestMotion(0))
        p.onNotification(motionOnly())
        val m = assertNotNull(p.latestMotion(0), "the 0x07 frame carries motion")
        assertNull(m.wheelPwmAlarm, "no 0x04 frame yet: the bit is unknown, not false")
    }

    @Test
    fun `true exactly while bit 0 is set`() {
        val p = BegodeProtocol()
        // Telemetry-plausibility Task 4: every 0x04 follows a live frame, as on a real wheel; without one the
        // odometer has no witness and the frame publishes one 0x04 late.
        fun alerted(bits: Int) { p.onNotification(liveFrame()); p.onNotification(withAlert(bits)) }
        alerted(0x00)
        assertEquals(false, p.latestMotion(0)!!.wheelPwmAlarm)
        alerted(0x01)
        assertEquals(true, p.latestMotion(0)!!.wheelPwmAlarm)
        alerted(0x02)
        assertEquals(false, p.latestMotion(0)!!.wheelPwmAlarm, "another bit is not this alarm")
        alerted(0xFF)
        assertEquals(true, p.latestMotion(0)!!.wheelPwmAlarm)
        alerted(0x00)
        assertEquals(false, p.latestMotion(0)!!.wheelPwmAlarm)
    }

    @Test
    fun `a reset forgets that a 0x04 frame was seen`() {
        val p = BegodeProtocol()
        p.onNotification(withAlert(0x01))
        p.reset()
        p.onNotification(motionOnly())
        assertNull(p.latestMotion(0)!!.wheelPwmAlarm)
    }

    @Test
    fun `bit 0 is labelled as the PWM alarm`() {
        val p = BegodeProtocol()
        p.onNotification(withAlert(0x01))
        assertEquals(listOf("PWM alarm"), p.wheelAlerts())
    }

    @Test
    fun `the default is null so other protocols leave it null`() {
        assertNull(ControllerData().wheelPwmAlarm)
    }

    @Test
    fun `the aggregate carries the alarm when any controller raises it`() {
        fun ctrl(i: Int) = Controller(i, "c$i", ControllerType.VESC, "A$i")
        fun agg(vararg alarms: Boolean?) = MotionAggregator.aggregate(
            alarms.mapIndexed { i, a -> ControllerState(ctrl(i), ControllerData(wheelPwmAlarm = a), isOnline = true) }
        ).wheelPwmAlarm
        assertNull(agg(null))
        assertNull(agg(null, null))
        assertEquals(false, agg(false, null))
        assertEquals(true, agg(true, null))
        assertEquals(true, agg(false, true))
    }
}
