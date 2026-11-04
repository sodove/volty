package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoTimelineInterpolationTest {
    private val continuousMetrics = VideoMetric.entries.filter {
        it != VideoMetric.ELAPSED && it != VideoMetric.DISTANCE
    }

    @Test
    fun interpolatesEveryContinuousMetricAtTheMidpointAndKeepsNegativeSigns() {
        val before = continuousMetrics.mapIndexed { index, metric ->
            metric to VideoReading(if (metric == VideoMetric.BATTERY_CURRENT) -20.0 else 10.0 + index)
        }.toMap() + mapOf(
            VideoMetric.ELAPSED to VideoReading(10.0),
            VideoMetric.DISTANCE to VideoReading(2.0),
        )
        val after = before.mapValues { (metric, reading) ->
            when (metric) {
                VideoMetric.ELAPSED -> VideoReading(20.0)
                VideoMetric.DISTANCE -> VideoReading(4.0)
                VideoMetric.BATTERY_CURRENT -> VideoReading(-40.0)
                else -> VideoReading(reading.value!! + 20.0)
            }
        }
        val timeline = VideoTimeline()
        timeline.offer(snapshot(100, before))
        timeline.offer(snapshot(140, after))

        val values = timeline.interpolatedValues(frameMillis = 200)

        continuousMetrics.forEachIndexed { index, metric ->
            val expected = if (metric == VideoMetric.BATTERY_CURRENT) -30.0 else 20.0 + index
            assertEquals(expected, values.getValue(metric), 0.000001, metric.name)
        }
        assertFalse(VideoMetric.ELAPSED in values)
        assertFalse(VideoMetric.DISTANCE in values)
        assertEquals(-30.0, values[VideoMetric.BATTERY_CURRENT])
    }

    @Test
    fun usesTheEightyMillisecondDelayAndHoldsNewestKnownValueWithoutAnUpperBracket() {
        val timeline = VideoTimeline()
        timeline.offer(speed(100, 10.0))
        timeline.offer(speed(140, 50.0))

        assertEquals(10.0, timeline.interpolatedValues(frameMillis = 180)[VideoMetric.SPEED])
        assertEquals(30.0, timeline.interpolatedValues(frameMillis = 200)[VideoMetric.SPEED])
        assertEquals(50.0, timeline.interpolatedValues(frameMillis = 300)[VideoMetric.SPEED])
    }

    @Test
    fun usesIrregularSnapshotSpacingAndDoesNotInterpolateAcrossAGapOverTwoSeconds() {
        val timeline = VideoTimeline()
        timeline.offer(speed(100, 10.0))
        timeline.offer(speed(250, 40.0))

        assertEquals(25.0, timeline.interpolatedValues(frameMillis = 255, delayMillis = 80)[VideoMetric.SPEED])
        assertEquals(40.0, timeline.interpolatedValues(frameMillis = 400)[VideoMetric.SPEED])

        val wideGap = VideoTimeline(maxAgeMillis = 5_000)
        wideGap.offer(speed(100, 10.0))
        wideGap.offer(speed(2_201, 40.0))
        assertEquals(10.0, wideGap.interpolatedValues(frameMillis = 2_300, delayMillis = 800)[VideoMetric.SPEED])

        val staleEndpoint = VideoTimeline()
        staleEndpoint.offer(speed(100, 10.0))
        staleEndpoint.offer(speed(2_201, 40.0))
        assertFalse(VideoMetric.SPEED in staleEndpoint.interpolatedValues(frameMillis = 2_300, delayMillis = 800))
    }

    @Test
    fun anUnknownLatestRawSampleClearsBufferedValuesImmediately() {
        val timeline = VideoTimeline()
        timeline.offer(speed(0, 10.0))
        timeline.offer(speed(80, 20.0))
        timeline.offer(speed(120, null))

        assertTrue(timeline.interpolatedValues(frameMillis = 160).isEmpty())

        val nonfinite = VideoTimeline()
        nonfinite.offer(speed(0, 10.0))
        nonfinite.offer(speed(80, 20.0))
        nonfinite.offer(speed(120, Double.NaN))
        assertTrue(nonfinite.interpolatedValues(frameMillis = 160).isEmpty())
    }

    @Test
    fun absentOrNonfiniteEndpointsDoNotCreateAnInterpolatedValue() {
        val missingEndpoint = VideoTimeline()
        missingEndpoint.offer(speed(100, null))
        missingEndpoint.offer(speed(200, 30.0))
        assertFalse(VideoMetric.SPEED in missingEndpoint.interpolatedValues(frameMillis = 200, delayMillis = 80))

        val nonfiniteEndpoint = VideoTimeline()
        nonfiniteEndpoint.offer(speed(100, Double.NaN))
        nonfiniteEndpoint.offer(speed(200, 30.0))
        assertFalse(VideoMetric.SPEED in nonfiniteEndpoint.interpolatedValues(frameMillis = 200, delayMillis = 80))

        val absent = VideoTimeline()
        absent.offer(VideoSnapshot(100, "v", emptyMap()))
        absent.offer(VideoSnapshot(200, "v", emptyMap()))
        assertTrue(absent.interpolatedValues(frameMillis = 200).isEmpty())
    }

    @Test
    fun anUnknownOrNonfiniteUpperBracketDoesNotCarryAnOlderValueAcrossReentry() {
        val missingUpper = VideoTimeline()
        missingUpper.offer(speed(100, 10.0))
        missingUpper.offer(speed(140, null))
        missingUpper.offer(speed(200, 30.0))
        assertFalse(VideoMetric.SPEED in missingUpper.interpolatedValues(frameMillis = 200, delayMillis = 80))

        val nonfiniteUpper = VideoTimeline()
        nonfiniteUpper.offer(speed(100, 10.0))
        nonfiniteUpper.offer(speed(140, Double.NaN))
        nonfiniteUpper.offer(speed(200, 30.0))
        assertFalse(VideoMetric.SPEED in nonfiniteUpper.interpolatedValues(frameMillis = 200, delayMillis = 80))
    }

    @Test
    fun theLastOfferedDuplicateAtTheLatestCausalTimeDefinesRawPresence() {
        val timeline = VideoTimeline()
        timeline.offer(speed(100, 10.0))
        timeline.offer(speed(100, null))

        assertEquals(VideoReading(null), timeline.sample(100)?.readings?.get(VideoMetric.SPEED))
        assertTrue(timeline.interpolatedValues(180).isEmpty())
    }

    @Test
    fun theLastOfferedDuplicateUpperTimestampDefinesTheInterpolationEndpoint() {
        val timeline = VideoTimeline()
        timeline.offer(speed(100, 10.0))
        timeline.offer(speed(140, null))
        timeline.offer(speed(140, 30.0))

        assertEquals(VideoReading(30.0), timeline.sample(200)?.readings?.get(VideoMetric.SPEED))
        assertEquals(20.0, timeline.interpolatedValues(frameMillis = 200)[VideoMetric.SPEED])
    }

    @Test
    fun endpointExpiryIsCheckedAtTheRealFrameTimeRatherThanTheDelayedTarget() {
        val timeline = VideoTimeline()
        timeline.offer(speed(100, 10.0, expiry = 150))
        timeline.offer(speed(200, 30.0))

        // The delayed target is 140 (before expiry), but the lower endpoint has expired by real frame time 220.
        assertFalse(VideoMetric.SPEED in timeline.interpolatedValues(frameMillis = 220, delayMillis = 80))
    }

    @Test
    fun doesNotUseTheFirstSampleBeforeItsTimestamp() {
        val timeline = VideoTimeline()
        timeline.offer(speed(100, 50.0))

        assertTrue(timeline.interpolatedValues(frameMillis = 179).isEmpty())
        assertEquals(50.0, timeline.interpolatedValues(frameMillis = 180)[VideoMetric.SPEED])
    }

    @Test
    fun anExpiredRawTimelineReturnsNoInterpolatedValues() {
        val timeline = VideoTimeline(maxAgeMillis = 50)
        timeline.offer(speed(100, 10.0))
        timeline.offer(speed(140, 20.0))

        assertTrue(timeline.interpolatedValues(frameMillis = 200).isEmpty())
    }

    @Test
    fun aVehicleSwitchAndClearNeverBridgeOldSamples() {
        val timeline = VideoTimeline()
        timeline.offer(speed(100, 10.0, vehicle = "old"))
        timeline.offer(speed(150, 20.0, vehicle = "old"))
        timeline.offer(speed(200, 100.0, vehicle = "new"))
        timeline.offer(speed(240, 140.0, vehicle = "new"))

        assertEquals(120.0, timeline.interpolatedValues(frameMillis = 300)[VideoMetric.SPEED])
        timeline.clear()
        assertTrue(timeline.interpolatedValues(frameMillis = 300).isEmpty())
    }

    private fun snapshot(
        timeMillis: Long,
        readings: Map<VideoMetric, VideoReading>,
        vehicle: String = "v",
        expiry: Map<VideoMetric, Long> = emptyMap(),
    ) = VideoSnapshot(timeMillis, vehicle, readings, expiresAtMillis = expiry)

    private fun speed(
        timeMillis: Long,
        value: Double?,
        vehicle: String = "v",
        expiry: Long? = null,
    ) = snapshot(
        timeMillis,
        mapOf(VideoMetric.SPEED to VideoReading(value)),
        vehicle,
        expiry?.let { mapOf(VideoMetric.SPEED to it) } ?: emptyMap(),
    )
}
