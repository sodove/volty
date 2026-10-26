package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [BegodeProtocol]'s [ProtocolFacts]: what the wheel has said, as stable keys and raw values.
 * The real captures are [BegodeDumpFixture] (ET Max, cell pages, 40S) and [BegodeT4Fixture]
 * (T4, 0x01 branch frames and no cell pages). Frames the captures do not carry (tiltback, alerts)
 * are synthetic, and say so.
 */
class BegodeFactsTest {
    private fun fedWithEtMax() = BegodeProtocol().also { p -> BegodeDumpFixture.chunks().forEach { p.onNotification(it) } }
    private fun fedWithT4() = BegodeProtocol().also { p -> BegodeT4Fixture.notifications.forEach { p.onNotification(it) } }

    private fun List<DeviceFact>.one(key: DeviceFactKey, index: Int? = null): DeviceFact? =
        singleOrNull { it.key == key && it.index == index }

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16)
        return byteArrayOf(0x55, 0xAA.toByte()) + payload + byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    private fun liveFrame(voltageRaw: Int, tempRaw: Int = 0, pwmRaw: Int = 0): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[10] = (tempRaw shr 8).toByte(); p[11] = tempRaw.toByte()
        p[12] = (pwmRaw shr 8).toByte(); p[13] = pwmRaw.toByte()
        return frame(0x00, 24, p)
    }

    private fun motionFrame(dutyRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[2] = 0x00; p[3] = 0x01
        p[4] = 0x00; p[5] = 0x14
        p[6] = (dutyRaw shr 8).toByte(); p[7] = dutyRaw.toByte()
        return frame(0x07, 24, p)
    }

    private fun odometerFrame(meters: Long, tiltbackRaw: Int = 0, alertBitmap: Int = 0): ByteArray {
        val p = ByteArray(16)
        p[0] = (meters shr 24).toByte(); p[1] = (meters shr 16).toByte(); p[2] = (meters shr 8).toByte(); p[3] = meters.toByte()
        p[8] = (tiltbackRaw shr 8).toByte(); p[9] = tiltbackRaw.toByte()
        p[12] = alertBitmap.toByte()
        return frame(0x04, 24, p)
    }

    @Test
    fun aWheelThatHasSaidNothingHasNoFacts() {
        assertTrue(BegodeProtocol().deviceFacts().isEmpty())
    }

    @Test
    fun theEtMaxCaptureCarriesItsFacts() {
        val facts = fedWithEtMax().deviceFacts()
        assertEquals(DeviceFact(DeviceFactKey.ODOMETER_KM, "8565.341", "km"), facts.one(DeviceFactKey.ODOMETER_KM))
        assertEquals(DeviceFact(DeviceFactKey.POWER_ON_DISTANCE_KM, "0.000", "km"), facts.one(DeviceFactKey.POWER_ON_DISTANCE_KM))
        // The 0x07 duty field of the capture: 0x0002.
        assertEquals(DeviceFact(DeviceFactKey.MOTION_DUTY_RAW, "2"), facts.one(DeviceFactKey.MOTION_DUTY_RAW))
        // Bytes 14..15 of every live frame: 0x00a9 = 169 -> 16.9 %, on a wheel that never moved.
        assertEquals(DeviceFact(DeviceFactKey.LIVE_PWM_RAW, "169"), facts.one(DeviceFactKey.LIVE_PWM_RAW))
        assertEquals(DeviceFact(DeviceFactKey.LIVE_PWM_RAW, "16.9", "%", index = 1), facts.one(DeviceFactKey.LIVE_PWM_RAW, 1))
        assertEquals("true", facts.one(DeviceFactKey.SMART_BMS_SEEN)?.value)
        assertEquals("40", facts.one(DeviceFactKey.SERIES_CELLS)?.value)
        assertEquals("cells", facts.one(DeviceFactKey.SERIES_CELLS_PROOF)?.value)
        // The tiltback field reads 200 (>= 100 is the wheel's "unset" marker) and the alert byte is 0:
        // nothing to say, so nothing is said.
        assertNull(facts.one(DeviceFactKey.TILTBACK_SPEED_KMH))
        assertNull(facts.one(DeviceFactKey.WHEEL_ALERTS))
        val board = assertNotNull(facts.one(DeviceFactKey.BOARD_TEMP_C))
        assertEquals("°C", board.unit)
        assertTrue(board.value.toFloat() in 27f..28f, "the capture's MPU reads about 27.5: ${board.value}")
    }

    @Test
    fun theEtMaxBranchesAreListedByIndexFromOne() {
        val facts = fedWithEtMax().deviceFacts()
        val v1 = assertNotNull(facts.one(DeviceFactKey.BRANCH_VOLTAGE_V, 1))
        val v2 = assertNotNull(facts.one(DeviceFactKey.BRANCH_VOLTAGE_V, 2))
        assertEquals("V", v1.unit)
        assertTrue(v1.value.toFloat() in 146f..150f, "cells sum to ~148 V: ${v1.value}")
        assertTrue(v2.value.toFloat() in 146f..150f, v2.value)
        assertEquals("A", assertNotNull(facts.one(DeviceFactKey.BRANCH_CURRENT_A, 1)).unit)
        assertNotNull(facts.one(DeviceFactKey.BRANCH_CURRENT_A, 2))
        assertNull(facts.one(DeviceFactKey.BRANCH_VOLTAGE_V, 3))
    }

    @Test
    fun theT4CaptureProvesItsCountFromTheBranchRatio() {
        val facts = fedWithT4().deviceFacts()
        assertEquals("24", facts.one(DeviceFactKey.SERIES_CELLS)?.value)
        assertEquals("branch_ratio", facts.one(DeviceFactKey.SERIES_CELLS_PROOF)?.value)
        assertEquals("true", facts.one(DeviceFactKey.SMART_BMS_SEEN)?.value)
        // Bytes 14..15 of the T4's live frame: 0x0080 = 128, again a constant that is not a duty.
        assertEquals("128", facts.one(DeviceFactKey.LIVE_PWM_RAW)?.value)
        assertEquals("12.8", facts.one(DeviceFactKey.LIVE_PWM_RAW, 1)?.value)
    }

    @Test
    fun aLiveFrameAloneProvesNothingAboutTheSeriesCount() {
        val p = BegodeProtocol()
        p.onNotification(liveFrame(voltageRaw = 5892, pwmRaw = 169))
        val facts = p.deviceFacts()
        assertNull(facts.one(DeviceFactKey.SERIES_CELLS))
        assertEquals("none", facts.one(DeviceFactKey.SERIES_CELLS_PROOF)?.value)
        assertEquals("false", facts.one(DeviceFactKey.SMART_BMS_SEEN)?.value)
    }

    @Test
    fun theLivePwmIsSurfacedRawAndNeverBecomesDuty() {
        val p = BegodeProtocol()
        p.onNotification(liveFrame(voltageRaw = 5892, pwmRaw = 169))
        assertEquals("169", p.deviceFacts().one(DeviceFactKey.LIVE_PWM_RAW)?.value)
        assertNull(p.dutyPercent(), "bytes 14..15 are a candidate, not a duty: duty stays absent without a 0x07 frame")
        p.onNotification(motionFrame(dutyRaw = 37))
        assertEquals(37f, p.dutyPercent())
        assertEquals("37", p.deviceFacts().one(DeviceFactKey.MOTION_DUTY_RAW)?.value)
        assertEquals("169", p.deviceFacts().one(DeviceFactKey.LIVE_PWM_RAW)?.value)
    }

    @Test
    fun aBootPlaceholderLiveFrameSurfacesNoPwm() {
        // Deliberately incoherent: a zero voltage (the boot placeholder) beside a non-zero PWM word.
        val p = BegodeProtocol()
        p.onNotification(liveFrame(voltageRaw = 0, pwmRaw = 169, tempRaw = 0x0100))
        assertNull(p.deviceFacts().one(DeviceFactKey.LIVE_PWM_RAW))
        assertNull(p.deviceFacts().one(DeviceFactKey.BOARD_TEMP_C))
    }

    @Test
    fun aNegativeDutyWordIsKeptRaw() {
        val p = BegodeProtocol()
        p.onNotification(liveFrame(voltageRaw = 5892))
        p.onNotification(motionFrame(dutyRaw = 0xFFF6)) // -10 as a signed word
        assertEquals("-10", p.deviceFacts().one(DeviceFactKey.MOTION_DUTY_RAW)?.value)
        assertEquals(10f, p.dutyPercent(), "the shared duty is the magnitude; the fact is what the wheel sent")
    }

    @Test
    fun tiltbackAndAlertsComeFromTheOdometerFrame() {
        // Synthetic: the capture's tiltback is unset and its alert byte is 0.
        val p = BegodeProtocol()
        p.onNotification(odometerFrame(meters = 1_234_567, tiltbackRaw = 40, alertBitmap = 0x08 or 0x20))
        val facts = p.deviceFacts()
        assertEquals(DeviceFact(DeviceFactKey.TILTBACK_SPEED_KMH, "40", "km/h"), facts.one(DeviceFactKey.TILTBACK_SPEED_KMH))
        assertEquals("Low voltage, Over temperature", facts.one(DeviceFactKey.WHEEL_ALERTS)?.value)
        assertEquals("1234.567", facts.one(DeviceFactKey.ODOMETER_KM)?.value)
    }

    @Test
    fun theBoardTemperatureIsTheMpuFormula() {
        val p = BegodeProtocol()
        p.onNotification(liveFrame(voltageRaw = 5892, tempRaw = 0xF406)) // -3066 / 340 + 36.53 = 27.51
        assertEquals(DeviceFact(DeviceFactKey.BOARD_TEMP_C, "27.5", "°C"), p.deviceFacts().one(DeviceFactKey.BOARD_TEMP_C))
    }

    @Test
    fun theListIsCachedAndRebuiltOnlyWhenAFactChanged() {
        val p = fedWithEtMax()
        val first = p.deviceFacts()
        assertSame(first, p.deviceFacts(), "no frame between the reads")
        // A frame carrying the very same values rebuilds nothing.
        p.onNotification(motionFrame(dutyRaw = 2))
        assertSame(first, p.deviceFacts(), "0x07 with the duty it already had")
        p.onNotification(motionFrame(dutyRaw = 9))
        val changed = p.deviceFacts()
        assertNotSame(first, changed)
        assertEquals("9", changed.one(DeviceFactKey.MOTION_DUTY_RAW)?.value)
        assertSame(changed, p.deviceFacts())
    }

    @Test
    fun resetForgetsEverything() {
        val p = fedWithEtMax()
        p.reset()
        assertTrue(p.deviceFacts().isEmpty())
    }
}
