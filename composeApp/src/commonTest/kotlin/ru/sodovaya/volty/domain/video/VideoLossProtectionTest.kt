package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoLossProtectionTest {
    // --- Back ---

    @Test
    fun backNeverEndsALiveRecordingWithoutAsking() {
        assertEquals(VideoBackAction.CONFIRM_STOP, videoBackAction(editing = false, phase = VideoRecordingPhase.RECORDING))
        assertEquals(VideoBackAction.CONFIRM_STOP, videoBackAction(editing = false, phase = VideoRecordingPhase.STARTING))
        assertEquals(VideoBackAction.CLOSE_SCREEN, videoBackAction(editing = false, phase = VideoRecordingPhase.IDLE))
        assertEquals(VideoBackAction.IGNORE, videoBackAction(editing = false, phase = VideoRecordingPhase.STOPPING))
        assertEquals(VideoBackAction.CLOSE_EDITOR, videoBackAction(editing = true, phase = VideoRecordingPhase.IDLE))
        // Deliberately incoherent: "editing" while recording is impossible in the UI, and must still not end it.
        assertEquals(VideoBackAction.CLOSE_EDITOR, videoBackAction(editing = true, phase = VideoRecordingPhase.RECORDING))
    }

    // --- Draw guard ---

    @Test
    fun aThrowingDrawIsSwallowedCountedAndSurfacedExactlyOnce() {
        val surfaced = mutableListOf<Throwable>()
        val guard = OverlayDrawGuard { surfaced += it }

        assertTrue(guard.attempt { })
        assertFalse(guard.attempt { error("lockCanvas failed") })
        assertFalse(guard.attempt { throw IllegalArgumentException("again") })
        assertTrue(guard.attempt { })
        assertFalse(guard.attempt { error("third") })

        assertEquals(3, guard.failures)
        assertEquals(1, surfaced.size, "the rider is told once, not thirty times a second")
        assertEquals("lockCanvas failed", surfaced.single().message)
    }

    // --- Redraw gate ---

    private val geometry = OverlayGeometry(1920, 1080, 0, false, 0, 0, 1920, 1080)
    private val strings = VideoOverlayStrings()
    private val hud = OverlayTemplates.hud()

    private fun snapshot(vararg readings: Pair<VideoMetric, Double?>, demo: Boolean = false) = VideoSnapshot(
        timeMillis = 0, vehicleId = "v",
        readings = VideoMetric.entries.associateWith { VideoReading(null) } + readings.associate { it.first to VideoReading(it.second) },
        demo = demo,
    )

    private fun key(snapshot: VideoSnapshot?, geometry: OverlayGeometry = this.geometry, clock: String? = null) =
        overlayDrawKey(hud, snapshot, strings, UnitSystem.METRIC, geometry, clock)

    @Test
    fun theOverlayIsRedrawnOnlyWhenWhatItShowsChanges() {
        val gate = OverlayDrawGate()
        val a = snapshot(VideoMetric.VOLTAGE to 84.01, VideoMetric.SPEED to 20.0)

        assertTrue(gate.shouldDraw(key(a)), "first frame must draw")
        repeat(5) { assertFalse(gate.shouldDraw(key(a)), "identical frame $it") }
        // A third-decimal drift that rounds to the same text paints the same pixels.
        assertFalse(gate.shouldDraw(key(snapshot(VideoMetric.VOLTAGE to 84.03, VideoMetric.SPEED to 20.0))))
        assertTrue(gate.shouldDraw(key(snapshot(VideoMetric.VOLTAGE to 85.0, VideoMetric.SPEED to 20.0))))
        assertEquals(2, gate.drawn)
        assertEquals(6, gate.skipped)
    }

    @Test
    fun aRuleColourChangeRedrawsEvenWhenTheRoundedTextDoesNot() {
        val gate = OverlayDrawGate()
        // SOC 30.4 and 29.6 both print "30" (29.6 rounds up), but the low-charge rule (< 30) flips amber.
        assertTrue(gate.shouldDraw(key(snapshot(VideoMetric.SOC to 30.4))))
        assertTrue(gate.shouldDraw(key(snapshot(VideoMetric.SOC to 29.6))))
    }

    @Test
    fun anArcSweepStepRedrawsEvenWhenTheRoundedTextDoesNot() {
        val gate = OverlayDrawGate()
        // 20.2 and 20.4 km/h both print "20" on a 0..100 dial, 0.2 km/h is 1.08 degrees: the arc moved.
        assertTrue(gate.shouldDraw(key(snapshot(VideoMetric.SPEED to 20.0))))
        assertTrue(gate.shouldDraw(key(snapshot(VideoMetric.SPEED to 20.4))))
        assertFalse(gate.shouldDraw(key(snapshot(VideoMetric.SPEED to 20.41))))
    }

    @Test
    fun geometryClockDemoAndStringChangesAllForceARedraw() {
        val gate = OverlayDrawGate()
        val s = snapshot(VideoMetric.SPEED to 20.0)
        assertTrue(gate.shouldDraw(key(s)))
        assertTrue(gate.shouldDraw(key(s, geometry.copy(rotationDegrees = 90))))
        assertTrue(gate.shouldDraw(key(s, geometry.copy(rotationDegrees = 90, mirrored = true))))
        assertTrue(gate.shouldDraw(key(s, geometry.copy(rotationDegrees = 90, mirrored = true, cropRight = 1000))))
        assertTrue(gate.shouldDraw(key(s, geometry.copy(rotationDegrees = 90, mirrored = true, cropRight = 1000), clock = "12:00")))
        assertFalse(gate.shouldDraw(key(s, geometry.copy(rotationDegrees = 90, mirrored = true, cropRight = 1000), clock = "12:00")))
        assertTrue(gate.shouldDraw(key(s, geometry.copy(rotationDegrees = 90, mirrored = true, cropRight = 1000), clock = "12:01")))

        val demo = snapshot(VideoMetric.SPEED to 20.0, demo = true)
        assertNotEquals(key(s), key(demo))
        val other = overlayDrawKey(hud, s, VideoOverlayStrings(kmh = "км/ч"), UnitSystem.METRIC, geometry)
        assertNotEquals(key(s), other)
        assertNotEquals(key(s), overlayDrawKey(hud, s, strings, UnitSystem.IMPERIAL, geometry))
        assertNotEquals(key(s), overlayDrawKey(OverlayTemplates.all().first { it.id == "minimal" }, s, strings, UnitSystem.METRIC, geometry))
    }

    @Test
    fun anInvalidatedGateDrawsAgainAndTheNoTelemetryFrameIsAKeyToo() {
        val gate = OverlayDrawGate()
        assertTrue(gate.shouldDraw(key(null)))
        assertFalse(gate.shouldDraw(key(null)))
        gate.invalidate()
        assertTrue(gate.shouldDraw(key(null)))
    }

    // --- Segments ---

    @Test
    fun rotationNeedsAPolicyAnElapsedSegmentAndNoRotationInFlight() {
        val five = VideoSegmentPolicy(5)
        val fiveMinutes = 5L * 60 * 1_000_000_000
        assertFalse(five.shouldRotate(fiveMinutes - 1, rotationInFlight = false))
        assertTrue(five.shouldRotate(fiveMinutes, rotationInFlight = false))
        assertFalse(five.shouldRotate(fiveMinutes * 3, rotationInFlight = true), "never queue a second rotation")
        assertFalse(VideoSegmentPolicy(0).shouldRotate(Long.MAX_VALUE, rotationInFlight = false))
        assertEquals(VideoSegmentPolicy.DEFAULT_SEGMENT_MINUTES, VideoSegmentPolicy.sanitized(7))
        assertEquals(10, VideoSegmentPolicy.sanitized(10))
    }

    @Test
    fun segmentFilesAreNamedInOrderAndAnUnsplitRideKeepsItsPlainName() {
        assertEquals("Volty_X", videoSegmentName("Volty_X", 1, segmented = false))
        assertEquals("Volty_X_part01", videoSegmentName("Volty_X", 1, segmented = true))
        assertEquals("Volty_X_part12", videoSegmentName("Volty_X", 12, segmented = true))
    }

    @Test
    fun theLedgerTotalsFinishedSegmentsPlusTheCurrentOne() {
        val ledger = VideoSegmentLedger()
        assertEquals(1, ledger.partIndex)
        assertEquals(40L, ledger.totalNanos(40))
        ledger.segmentFinished(300)
        assertEquals(2, ledger.nextPart())
        assertEquals(340L, ledger.totalNanos(40))
        ledger.segmentFinished(-5)
        assertEquals(340L, ledger.totalNanos(40))
        ledger.reset()
        assertEquals(1, ledger.partIndex)
        assertEquals(0L, ledger.totalNanos(0))
    }

    @Test
    fun theGateKeepsTheCameraThroughARotationOverlap() {
        val issued = mutableListOf<String>()
        val gate = VideoFinalizationGate({ issued += "stop" }, { issued += "release" })
        gate.recordingStarted()
        gate.segmentStarted() // the next file starts while the previous finalizes
        gate.close()
        assertEquals(listOf("stop"), issued)
        gate.finalized() // the old segment
        assertEquals(listOf("stop"), issued, "the second recording still owns the camera")
        gate.finalized()
        assertEquals(listOf("stop", "release"), issued)
        assertFailsWith<IllegalStateException> { gate.segmentStarted() }
    }

    @Test
    fun aSecondSegmentCannotBeStartedWithoutARecordingOrOnTopOfTwo() {
        val gate = VideoFinalizationGate({ }, { })
        assertFailsWith<IllegalStateException> { gate.segmentStarted() }
        gate.recordingStarted()
        gate.segmentStarted()
        assertFailsWith<IllegalStateException> { gate.segmentStarted() }
    }

    // --- Orientation ---

    @Test
    fun theFileRotationFollowsHowThePhoneIsHeldNotWhatTheDisplayDoes() {
        val policy = VideoOrientationPolicy()
        assertEquals(VideoOrientationPolicy.ROTATION_0, policy.onSensorDegrees(3))
        // A phone clamped landscape on a handlebar, auto-rotate off: the sensor says 90 or 270.
        assertEquals(VideoOrientationPolicy.ROTATION_270, policy.onSensorDegrees(88))
        assertEquals(VideoOrientationPolicy.ROTATION_90, policy.onSensorDegrees(272))
        // The 180 degree flip that fires no configuration change.
        assertEquals(VideoOrientationPolicy.ROTATION_180, policy.onSensorDegrees(179))
    }

    @Test
    fun hysteresisStopsAPhoneNearAFortyFiveDegreeBoundaryFromFlapping() {
        val policy = VideoOrientationPolicy(hysteresisDegrees = 10)
        policy.onSensorDegrees(0)
        assertNull(policy.onSensorDegrees(50), "inside the 45 + 10 band")
        assertNull(policy.onSensorDegrees(54))
        assertEquals(VideoOrientationPolicy.ROTATION_270, policy.onSensorDegrees(56))
        assertNull(policy.onSensorDegrees(44), "back inside the band of the new rotation")
        assertNull(policy.onSensorDegrees(36))
        assertEquals(VideoOrientationPolicy.ROTATION_0, policy.onSensorDegrees(34))
    }

    @Test
    fun wraparoundAndUnknownAngles() {
        val policy = VideoOrientationPolicy()
        assertEquals(VideoOrientationPolicy.ROTATION_0, policy.onSensorDegrees(359))
        assertNull(policy.onSensorDegrees(5))
        assertNull(policy.onSensorDegrees(-1), "flat on a table: keep what we had")
        assertNull(policy.onSensorDegrees(360))
        assertEquals(VideoOrientationPolicy.ROTATION_0, policy.rotation)
    }

    // --- Timebase ---

    @Test
    fun aRealtimeClaimedCameraStampingUptimeIsCaughtAndRepairedNotBlankedForTheWholeVideo() {
        val clock = VideoFrameClock(realtime = true)
        val slept = 3_600_000L // an hour of deep sleep: elapsed realtime ran on, uptime did not
        var now = 10_000_000L
        var lastFrame = 0L
        repeat(VideoFrameClock.CONFIRM_FRAMES + 20) {
            now += 33
            val camera = now - slept
            lastFrame = clock.frameMillis(camera * 1_000_000L, now)
        }
        assertTrue(clock.timebaseMismatch)
        // After the repair a frame time sits next to the receipt time, so a sample 40 ms old is causal and fresh.
        assertTrue(now - lastFrame < 200L, "frame time must track the receipt clock, was ${now - lastFrame} ms behind")
    }

    @Test
    fun anHonestRealtimeCameraUnderLoadIsNeverFlagged() {
        val clock = VideoFrameClock(realtime = true)
        var now = 10_000_000L
        repeat(500) { i ->
            now += 33
            // Delivery latency wobbling between 20 ms and a bad 1.2 s hiccup every 50 frames.
            val latency = if (i % 50 == 0) 1_200L else 20L
            assertEquals(now - latency, clock.frameMillis((now - latency) * 1_000_000L, now))
        }
        assertFalse(clock.timebaseMismatch)
    }

    @Test
    fun aFewImplausibleFramesAreNotEnoughToChangeTheTimebase() {
        val clock = VideoFrameClock(realtime = true)
        val now = 10_000_000L
        repeat(VideoFrameClock.CONFIRM_FRAMES - 1) { clock.frameMillis((now - 60_000) * 1_000_000L, now) }
        clock.frameMillis((now - 20) * 1_000_000L, now) // one plausible frame resets the run
        repeat(VideoFrameClock.CONFIRM_FRAMES - 1) { clock.frameMillis((now - 60_000) * 1_000_000L, now) }
        assertFalse(clock.timebaseMismatch)
    }

    // --- Pending sweep ---

    @Test
    fun aCrashedRecordingsRowIsPublishedAndAnEmptyOneIsRemoved() {
        val now = 1_000_000_000L
        val old = now - 3_600_000L
        assertEquals(PendingVideoAction.PUBLISH, pendingVideoAction(PendingVideoRow(1, 90_000_000, old), now))
        assertEquals(PendingVideoAction.DELETE_EMPTY, pendingVideoAction(PendingVideoRow(2, 0, old), now))
        assertEquals(PendingVideoAction.DELETE_EMPTY, pendingVideoAction(PendingVideoRow(3, 300, old), now), "a header with no samples")
        assertEquals(PendingVideoAction.PUBLISH, pendingVideoAction(PendingVideoRow(4, EMPTY_BELOW_BYTES, old), now))
    }

    @Test
    fun aRowTouchedJustNowIsLeftAloneWhateverItsSize() {
        val now = 1_000_000_000L
        assertEquals(PendingVideoAction.LEAVE, pendingVideoAction(PendingVideoRow(1, 90_000_000, now - 1_000), now))
        assertEquals(PendingVideoAction.LEAVE, pendingVideoAction(PendingVideoRow(2, 0, now), now))
        // A modification time in the future (a stepped clock) is not "fresh forever".
        assertEquals(PendingVideoAction.PUBLISH, pendingVideoAction(PendingVideoRow(3, 90_000_000, now + 10_000_000), now))
    }
}
