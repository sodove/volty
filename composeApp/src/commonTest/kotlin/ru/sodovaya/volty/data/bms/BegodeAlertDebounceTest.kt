package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.alert.ActiveAlertTracker
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DeviceFactKey
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.usecase.AlertKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * R12 (telemetry-plausibility Task 9): a newly SET bit of the 0x04 alert byte becomes visible (in `faults` and
 * `wheelPwmAlarm`) only when set in two consecutive 0x04 frames; a clearing bit clears at once. Begode only.
 *
 * The frames are the rider's T4 0x04 with bit 0 set, and the same frame with byte 14 edited. The odometer never moves
 * here, so R1's witness stays UNPROVEN and the odometer gate HOLDS every 0x04 one frame (Task 4): that is on purpose,
 * it is the case Ruling 5 is about (the alert byte must not wait for the gate). Everything asserted is what was
 * published. Whether the banner row looks right is judged on a device.
 */
@OptIn(ExperimentalTime::class)
class BegodeAlertDebounceTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val alarmFrame = bytes("55 AA 00 2A DC AD 28 00 1C 20 00 C8 00 00 01 00 00 12 04 18 5A 5A 5A 5A")
    private fun alert(bits: Int) = alarmFrame.copyOf().also { it[14] = bits.toByte() }

    /** Feeds one 0x04 and returns the published motion (null before any). */
    private fun BegodeProtocol.feed(bits: Int): ControllerData? { onNotification(alert(bits)); return latestMotion(0) }

    private fun clearThenReady(): BegodeProtocol = BegodeProtocol().also { it.feed(0); it.feed(0) }

    @Test
    fun `a one-frame bit 0 blip between clear frames never shows the alarm`() {
        val p = BegodeProtocol()
        val seen = listOf(0, 0, 0x01, 0, 0).map { p.feed(it)?.wheelPwmAlarm }
        assertTrue(seen.none { it == true }, "the alarm was true in $seen")
        assertEquals(false, seen.last())
    }

    @Test
    fun `two consecutive frames show the alarm and the next clear frame clears it at once`() {
        val p = clearThenReady()
        assertEquals(false, p.feed(0x01)!!.wheelPwmAlarm, "one set frame: not yet")
        assertEquals(true, p.feed(0x01)!!.wheelPwmAlarm, "the second consecutive set frame confirms it")
        assertEquals(true, p.feed(0x01)!!.wheelPwmAlarm)
        assertEquals(false, p.feed(0x00)!!.wheelPwmAlarm, "a clearing bit clears with the very frame that clears it")
    }

    @Test
    fun `a fault bit needs two frames and clears at once`() {
        val p = clearThenReady()
        assertEquals(emptyList(), p.feed(0x20)!!.faults, "Over temperature seen once is not a fault yet")
        assertEquals(listOf("Over temperature"), p.feed(0x20)!!.faults)
        assertEquals(emptyList(), p.feed(0x00)!!.faults)
        // a blip of a fault bit
        assertEquals(emptyList(), p.feed(0x20)!!.faults)
        assertEquals(emptyList(), p.feed(0x00)!!.faults)
        assertEquals(emptyList(), p.feed(0x00)!!.faults)
    }

    @Test
    fun `on a wheel whose odometer witness is unproven the fault shows after the second frame, not the third`() {
        // Ruling 5: since Task 4 the odometer gate HOLDS each 0x04 until the next one arrives (standing still gives
        // it no witness). The alert byte of a held 0x04 is evidence of the bit all the same: delays must not stack.
        val p = clearThenReady()
        p.feed(0x20)
        val second = p.feed(0x20)
        assertEquals(listOf("Over temperature"), second!!.faults, "visible after the second frame")
    }

    @Test
    fun `from the very first 0x04 of a connection two frames confirm, while the odometer gate still holds the first`() {
        val p = BegodeProtocol()
        assertNull(p.feed(0x20)?.faults?.takeIf { it.isNotEmpty() })
        assertEquals(listOf("Over temperature"), p.feed(0x20)!!.faults)
    }

    @Test
    fun `a bit that is set in two frames not in a row is confirmed by neither`() {
        val p = clearThenReady()
        val seen = listOf(0x20, 0x00, 0x20, 0x00).map { p.feed(it)!!.faults }
        assertTrue(seen.all { it.isEmpty() }, "faults were $seen")
    }

    @Test
    fun `the unconfirmed count is a fact, 0 on a healthy wheel, and no rejection is recorded`() {
        val p = clearThenReady()
        fun count() = p.deviceFacts().single { it.key == DeviceFactKey.UNCONFIRMED_ALERT_BITS }.value
        assertEquals("0", count())
        p.feed(0x21)
        assertEquals("2", count(), "two bits waiting for a second frame")
        p.feed(0x01)
        assertEquals("0", count(), "bit 0 confirmed, the fault bit cleared: nothing waits")
        p.feed(0x00)
        p.feed(0x00)
        assertEquals("0", count())
        assertEquals(0L, p.rejectedFrameCount, "an unconfirmed bit is not a rejected frame")
    }

    @Test
    fun `a reset forgets the previous frame, so a bit set before it needs two frames again`() {
        val p = clearThenReady()
        p.feed(0x20)
        p.reset()
        p.feed(0x20)
        assertEquals(emptyList(), p.feed(0x00)!!.faults)
        assertEquals(emptyList(), p.feed(0x20)!!.faults)
    }

    @Test
    fun `the alarm is published with the time its bit was first set, which is the first raw frame`() {
        var t = 5_000L
        val p = BegodeProtocol(nowMillis = { t })
        p.feed(0); t += 300; p.feed(0)
        t += 300; p.feed(0x01)
        t += 300
        val confirmed = p.feed(0x01)!!
        assertEquals(true, confirmed.wheelPwmAlarm)
        assertEquals(5_600L, confirmed.wheelPwmAlarmSinceMillis, "the first set frame, not the confirming one")
        t += 300
        assertEquals(5_600L, p.feed(0x01)!!.wheelPwmAlarmSinceMillis, "stable while the bit stays set")
        assertNull(p.feed(0x00)!!.wheelPwmAlarmSinceMillis)
    }

    // ---- the banner, with the real decoder's output --------------------------------------------------------------

    private fun vehicle() = Vehicle(
        id = "v1", name = "Wheel", iconKey = "unicycle", packs = emptyList(),
        controllers = listOf(Controller(index = 0, label = "Main", controllerType = ControllerType.BEGODE, address = "AA")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now(),
    )

    @Test
    fun `the banner row of the wheel's own PWM alarm needs the confirmed bit`() {
        val p = clearThenReady()
        val tracker = ActiveAlertTracker()
        val v = vehicle()
        fun rows(bits: Int) = tracker.updateMotion(assertNotNull(p.feed(bits)), v).map { it.kind }
        assertEquals(emptyList(), rows(0x01), "one frame: no row")
        assertEquals(listOf(AlertKind.WHEEL_PWM_ALARM), rows(0x01))
        assertEquals(emptyList(), rows(0x00))
    }
}
