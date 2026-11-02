package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoTimelineTest {
    @Test
    fun sampleUsesLatestCausalSnapshotAndExpiresItAfterMaxAge() {
        val timeline = VideoTimeline(maxAgeMillis = 2_000L)
        val older = snapshot(timeMillis = 100L, vehicleId = "bike", speed = 10.0)
        val newer = snapshot(timeMillis = 300L, vehicleId = "bike", speed = 30.0)
        timeline.offer(older)
        timeline.offer(newer)

        assertEquals(older, timeline.sample(frameTimeMillis = 250L))
        assertEquals(newer, timeline.sample(frameTimeMillis = 2_300L))
        assertNull(timeline.sample(frameTimeMillis = 2_301L))
    }

    @Test
    fun futureSnapshotIsNeverSelected() {
        val timeline = VideoTimeline()
        timeline.offer(snapshot(timeMillis = 500L, vehicleId = "bike", speed = 30.0))

        assertNull(timeline.sample(frameTimeMillis = 499L))
    }

    @Test
    fun offeringAnotherVehicleClearsPriorVehicleSamples() {
        val timeline = VideoTimeline()
        val oldVehicle = snapshot(timeMillis = 100L, vehicleId = "bike", speed = 30.0)
        val newVehicle = snapshot(timeMillis = 200L, vehicleId = "scooter", speed = 20.0)
        timeline.offer(oldVehicle)
        timeline.offer(newVehicle)

        assertNull(timeline.sample(frameTimeMillis = 150L))
        assertEquals(newVehicle, timeline.sample(frameTimeMillis = 200L))
    }

    @Test
    fun timelineRetainsOnlyItsConfiguredCapacity() {
        val timeline = VideoTimeline(maxAgeMillis = 100L, capacity = 2)
        timeline.offer(snapshot(timeMillis = 10L, vehicleId = "bike", speed = 10.0))
        timeline.offer(snapshot(timeMillis = 20L, vehicleId = "bike", speed = 20.0))
        val newest = snapshot(timeMillis = 30L, vehicleId = "bike", speed = 30.0)
        timeline.offer(newest)

        assertEquals(newest, timeline.sample(frameTimeMillis = 30L))
        assertNull(timeline.sample(frameTimeMillis = 10L))
    }

    @Test
    fun clearRemovesEverySample() {
        val timeline = VideoTimeline()
        timeline.offer(snapshot(timeMillis = 10L, vehicleId = "bike", speed = 10.0))

        timeline.clear()

        assertNull(timeline.sample(frameTimeMillis = 10L))
    }

    @Test
    fun sourceExpiryKeepsEachReadingUnknownAfterItsDeadlineAndRemovesEstimateMarkers() {
        val snapshot = VideoSnapshot(
            timeMillis = 1_000L,
            vehicleId = "bike",
            readings = mapOf(
                VideoMetric.SPEED to VideoReading(value = 42.0, estimated = true, atCeiling = true),
                VideoMetric.SOC to VideoReading(value = 80.0),
            ),
            expiresAtMillis = mapOf(
                VideoMetric.SPEED to 1_050L,
                VideoMetric.SOC to 2_000L,
            ),
        )
        val timeline = VideoTimeline()
        timeline.offer(snapshot)

        assertEquals(snapshot, timeline.sample(frameTimeMillis = 1_050L))
        val afterSpeedExpiry = timeline.sample(frameTimeMillis = 1_100L)
        assertEquals(VideoReading(null), afterSpeedExpiry?.readings?.get(VideoMetric.SPEED))
        assertEquals(VideoReading(80.0), afterSpeedExpiry?.readings?.get(VideoMetric.SOC))
    }

    private fun snapshot(timeMillis: Long, vehicleId: String, speed: Double) = VideoSnapshot(
        timeMillis = timeMillis,
        vehicleId = vehicleId,
        readings = mapOf(VideoMetric.SPEED to VideoReading(value = speed)),
    )
}
