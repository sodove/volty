package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VideoBufferedInterpolationTest {
    @Test fun bufferedFrameUsesAnAlreadyReceivedUpperSampleWithoutShiftingTelemetry() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 0.0))
        timeline.offer(speed(250, 100.0))
        assertEquals(40.0, timeline.interpolatedValuesAt(100, availableAtMillis = 300)[VideoMetric.SPEED])
        assertEquals(0.0, timeline.interpolatedValuesAt(100, availableAtMillis = 200)[VideoMetric.SPEED])
    }

    @Test fun repeatedFoldsDoNotMoveTheMeasurementTimeForward() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 0.0, measured = 0))
        timeline.offer(speed(100, 0.0, measured = 0))
        timeline.offer(speed(200, 0.0, measured = 0))
        timeline.offer(speed(250, 100.0, measured = 250))
        assertEquals(40.0, timeline.interpolatedValuesAt(100, 300)[VideoMetric.SPEED])
    }

    @Test fun hdFrameHasLookaheadForTheNextBegodeSample() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 0.0))
        timeline.offer(speed(350, 65.8, measured = 329))

        val frameMillis = 25L
        val lookaheadMillis = videoFrameBufferDepth(1280, 720, 60) * 1_000L / 60
        val value = timeline.interpolatedValuesAt(frameMillis, frameMillis + lookaheadMillis)
        assertEquals(5.0, value.getValue(VideoMetric.SPEED), absoluteTolerance = 0.0001)
    }

    @Test fun futureDisconnectDoesNotEraseAHistoricalFrameButUnknownAtTheFrameDoes() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 0.0))
        timeline.offer(speed(200, 20.0))
        timeline.offer(speed(250, null))
        assertEquals(10.0, timeline.interpolatedValuesAt(100, 300)[VideoMetric.SPEED])
        assertFalse(VideoMetric.SPEED in timeline.interpolatedValuesAt(260, 300))
    }

    @Test fun doesNotBridgeAnUnknownEndpointOrBorrowFromAnotherVehicle() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 0.0))
        timeline.offer(speed(100, null))
        timeline.offer(speed(200, 100.0))
        assertEquals(0.0, timeline.interpolatedValuesAt(50, 250)[VideoMetric.SPEED])
        assertFalse(VideoMetric.SPEED in timeline.interpolatedValuesAt(150, 250))
        timeline.offer(speed(300, 200.0).copy(vehicleId = "other"))
        assertFalse(VideoMetric.SPEED in timeline.interpolatedValuesAt(100, 350))
    }

    @Test fun adaptivePreviewDelayCoversSparseSourceCadenceAndStaysBounded() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 0.0))
        timeline.offer(speed(250, 100.0))
        assertEquals(270L, timeline.interpolationDelayMillis())
        timeline.offer(speed(579, 200.0))
        assertEquals(349L, timeline.interpolationDelayMillis())
        timeline.offer(speed(1_100, 300.0))
        assertEquals(380L, timeline.interpolationDelayMillis())
    }

    @Test fun pastTheNewestMeasurementTheLastValueHoldsOnlyWhileFresh() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 10.0))
        timeline.offer(speed(200, 30.0))

        assertEquals(30.0, timeline.interpolatedValuesAt(300, 550)[VideoMetric.SPEED])
        assertFalse(VideoMetric.SPEED in timeline.interpolatedValuesAt(2_300, 2_600))
    }

    @Test fun previewPresentationTimeNeverMovesBackwardWhenTheDelayIncreases() {
        val clock = VideoPresentationClock()
        assertEquals(920L, clock.frameMillis(1000, 80))
        assertEquals(920L, clock.frameMillis(1016, 270))
        assertEquals(930L, clock.frameMillis(1200, 270))
        clock.reset()
        assertEquals(620L, clock.frameMillis(1000, 380))
    }

    @Test fun aRecentFoldCannotRefreshAnOldMeasurementEvenWithoutAnExplicitExpiry() {
        val timeline = VideoTimeline()
        timeline.offer(speed(5_000, 80.0, measured = 0))
        assertFalse(VideoMetric.SPEED in timeline.interpolatedValuesAt(5_000, 5_000))
    }

    @Test fun unknownAfterCaptureHoldsTheLastCausalValueWithoutBorrowingAcrossTheGap() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 0.0))
        timeline.offer(speed(200, 20.0))
        timeline.offer(speed(250, null, measured = 0)) // Backdated placeholder must not erase history.
        timeline.offer(speed(400, 40.0))
        assertEquals(20.0, timeline.interpolatedValuesAt(220, 450)[VideoMetric.SPEED])
        assertFalse(VideoMetric.SPEED in timeline.interpolatedValuesAt(300, 450))
    }

    private fun speed(time: Long, value: Double?, measured: Long = time) = VideoSnapshot(
        time, "wheel", mapOf(VideoMetric.SPEED to VideoReading(value)),
        sampledAtMillis = mapOf(VideoMetric.SPEED to measured),
    )
}
