package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFrameClockTest {
    @Test fun realtimeCameraKeepsCaptureTimeRatherThanRenderTime() {
        val clock = VideoFrameClock(realtime = true)
        assertEquals(900L, clock.frameMillis(900_000_000L, 1000L))
    }

    @Test fun unknownCameraUsesStableOffsetAndNeverReturnsFuture() {
        val clock = VideoFrameClock(realtime = false)
        assertEquals(1000L, clock.frameMillis(50_000_000L, 1000L))
        assertEquals(1030L, clock.frameMillis(80_000_000L, 1040L))
        assertEquals(1040L, clock.frameMillis(100_000_000L, 1040L))
    }

    @Test fun streamRestartReanchorsUnknownTimebase() {
        val clock = VideoFrameClock(realtime = false)
        clock.frameMillis(800_000_000L, 1000L)
        assertEquals(1100L, clock.frameMillis(10_000_000L, 1100L))
    }
}
