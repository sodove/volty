package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalibrationMuteTest {
    private var now = 1_000L
    private val mute = CalibrationMute(nowMs = { now })

    @Test
    fun `off until begun, on after begin, off after end`() {
        assertFalse(mute.isActive())
        mute.begin()
        assertTrue(mute.isActive())
        mute.end()
        assertFalse(mute.isActive())
    }

    @Test
    fun `a signal nobody ends clears itself at the time box`() {
        mute.begin()
        now += CalibrationMute.TIME_BOX_MS - 1
        assertTrue(mute.isActive(), "one millisecond before the box runs out")
        now += 1
        assertFalse(mute.isActive(), "a wizard that died without end() cannot hold the alarms off")
    }

    @Test
    fun `a heartbeat renews the time box`() {
        mute.begin()
        now += CalibrationMute.TIME_BOX_MS - 10
        mute.begin()
        now += CalibrationMute.TIME_BOX_MS - 10
        assertTrue(mute.isActive())
        now += 20
        assertFalse(mute.isActive())
    }

    @Test
    fun `the time box is three minutes`() {
        assertTrue(CalibrationMute.TIME_BOX_MS == 180_000L)
    }
}
