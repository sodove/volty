package ru.sodovaya.volty.domain.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.sodovaya.volty.domain.model.AutoVolumeSettings

class AutoVolumeLogicTest {
    private fun logic() = AutoVolumeLogic(AutoVolumeSettings(), volumeSteps = 20)

    @Test fun curveClampsBelowAndAboveRange() {
        assertEquals(30, logic().targetPercent(0))
        assertEquals(80, logic().targetPercent(60))
    }

    @Test fun curveIsLinearBetweenSpeedBounds() {
        assertEquals(54, logic().targetPercent(17))
    }

    @Test fun firstSampleIsAppliedAndQuantized() {
        assertEquals(8, logic().onSpeed(10)?.step)
    }

    @Test fun deadbandDoesNotFightSmallSpeedChanges() {
        val l = logic()
        l.onSpeed(10)
        assertNull(l.onSpeed(11))
        assertNull(l.onSpeed(9))
    }

    @Test fun changedSpeedWithSameDeviceStepDoesNothing() {
        val l = AutoVolumeLogic(
            AutoVolumeSettings(minSpeedKmh = 5, maxSpeedKmh = 55),
            volumeSteps = 20
        )
        assertEquals(8, l.onSpeed(13)?.step)
        assertNull(l.onSpeed(17))
    }

    @Test fun resetAllowsTheNextSampleToApply() {
        val l = logic()
        l.onSpeed(20)
        l.reset()
        assertEquals(12, l.onSpeed(20)?.step)
    }
}
