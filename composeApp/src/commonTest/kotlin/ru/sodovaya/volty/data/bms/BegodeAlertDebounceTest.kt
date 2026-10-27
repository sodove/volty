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
    fun `two consecutive frames show the alarm and two consecutive clear frames clear it`() {
        // Retraction: this test used to assert that ONE clear frame cleared the alarm at once. That was wrong, see
        // confirmAlertBits: the debounce is symmetric (controller ruling 2026-10-28).
        val p = clearThenReady()
        assertEquals(false, p.feed(0x01)!!.wheelPwmAlarm, "one set frame: not yet")
        assertEquals(true, p.feed(0x01)!!.wheelPwmAlarm, "the second consecutive set frame confirms it")
        assertEquals(true, p.feed(0x01)!!.wheelPwmAlarm)
        assertEquals(true, p.feed(0x00)!!.wheelPwmAlarm, "one clear frame is not enough")
        assertEquals(false, p.feed(0x00)!!.wheelPwmAlarm, "the second consecutive clear frame clears it")
    }

    @Test
    fun `a fault bit needs two frames to show and two to clear`() {
        val p = clearThenReady()
        assertEquals(emptyList(), p.feed(0x20)!!.faults, "Over temperature seen once is not a fault yet")
        assertEquals(listOf("Over temperature"), p.feed(0x20)!!.faults)
        assertEquals(listOf("Over temperature"), p.feed(0x00)!!.faults, "one clear frame does not end it")
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
    fun `the swallowed-bit count is a cumulative per-connection fact, 0 on a healthy wheel, and no rejection is recorded`() {
        val p = clearThenReady()
        fun count() = p.deviceFacts().single { it.key == DeviceFactKey.UNCONFIRMED_ALERT_BITS }.value
        assertEquals("0", count())
        p.feed(0x21)
        assertEquals("0", count(), "still waiting for a second frame: not swallowed yet")
        p.feed(0x01)
        assertEquals("1", count(), "the fault bit was seen once and is gone: swallowed. Bit 0 confirmed: not counted")
        p.feed(0x00); p.feed(0x00)
        assertEquals("1", count(), "the trace stays after the bits are gone (a snapshot would read 0 here)")
        p.feed(0x20); p.feed(0x00)
        assertEquals("2", count(), "it grows with every swallowed bit")
        assertEquals(0L, p.rejectedFrameCount, "a swallowed bit is not a rejected frame")
        p.reset()
        p.feed(0); p.feed(0)
        assertEquals("0", count(), "per connection: reset zeroes it")
    }

    @Test
    fun `one corrupt clear frame inside a real fault changes nothing`() {
        val p = clearThenReady()
        p.feed(0x20); p.feed(0x20)
        assertEquals(listOf("Over temperature"), p.feed(0x00)!!.faults)
        assertEquals(listOf("Over temperature"), p.feed(0x20)!!.faults)
        assertEquals(listOf("Over temperature"), p.feed(0x20)!!.faults)
        assertEquals("0", p.deviceFacts().single { it.key == DeviceFactKey.UNCONFIRMED_ALERT_BITS }.value, "a corrupt clear swallows no set bit")
    }

    private fun live(powerOn: Int) = bytes("55 AA 19 06 F7 43 00 6A 00 65 FE 5C F1 D2 08 A0 00 05 00 18 5A 5A 5A 5A")
        .also { it[8] = (powerOn shr 8).toByte(); it[9] = powerOn.toByte() }
    private fun odo(m: Long, bits: Int) = alert(bits).also {
        it[2] = (m shr 24).toByte(); it[3] = (m shr 16).toByte(); it[4] = (m shr 8).toByte(); it[5] = m.toByte()
    }

    @Test
    fun `a newly confirmed bit is published even when the odometer path publishes nothing for that frame`() {
        // The witness is TRUSTED by three in-step pairs; then a coherent odometer that contradicts the counter is held
        // back as a SUSPECT: its frame offers nothing to the gate, so only confirmAlertBits' own rebuild can show the bit.
        val p = BegodeProtocol()
        val base = 2_816_164L
        for (i in 0 until 3) { p.onNotification(live(101 + 7 * i)); p.onNotification(odo(base + 7 * i, if (i == 2) 0x20 else 0)) }
        assertEquals(emptyList(), p.latestMotion(0)!!.faults, "one frame of the bit so far")
        p.onNotification(live(101 + 21)); p.onNotification(odo(base + 21 + 1_000, 0x20)) // suspect: odometer +1007, counter +7
        assertEquals(listOf("Over temperature"), p.latestMotion(0)!!.faults)
        assertEquals(0L, p.rejectedFrameCount, "the suspect is held, not rejected")
    }

    @Test
    fun `a zero-padded 0x04 neither counts toward a bit nor breaks the run`() {
        val padded = bytes("55 AA 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 04 18 5A 5A 5A 5A")
        val p = clearThenReady()
        p.feed(0x20)
        p.onNotification(padded)
        assertEquals(listOf("Over temperature"), p.feed(0x20)!!.faults, "set, padded, set confirms")
        p.onNotification(padded)
        assertEquals(listOf("Over temperature"), p.latestMotion(0)!!.faults, "a padded frame is not a clear frame")
        p.feed(0x00)
        assertEquals(listOf("Over temperature"), p.latestMotion(0)!!.faults, "and it does not complete a pair of clears")
        assertEquals(emptyList(), p.feed(0x00)!!.faults)
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
        assertEquals(5_600L, p.feed(0x00)!!.wheelPwmAlarmSinceMillis, "one corrupt clear inside an alarm keeps its start")
        assertNull(p.feed(0x00)!!.wheelPwmAlarmSinceMillis, "confirmed clear: gone")
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
        assertEquals(listOf(AlertKind.WHEEL_PWM_ALARM), rows(0x00), "one clear frame keeps the row")
        assertEquals(emptyList(), rows(0x00))
    }
}
