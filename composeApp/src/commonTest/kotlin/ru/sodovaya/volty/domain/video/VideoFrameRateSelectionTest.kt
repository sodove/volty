package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFrameRateSelectionTest {
    @Test fun offersOnlyConfirmedNormalFixedRatesWithSixtyFirst() {
        assertEquals(listOf(60, 30), videoFrameRateChoices(listOf(30 to 30, 15 to 60, 60 to 60, 120 to 120)))
    }

    @Test fun variableRangesDoNotPromiseAConstantRecordingRate() {
        assertEquals(emptyList(), videoFrameRateChoices(listOf(15 to 30, 30 to 60)))
        assertEquals(listOf(30), videoFrameRateChoices(listOf(30 to 30)))
    }
}
