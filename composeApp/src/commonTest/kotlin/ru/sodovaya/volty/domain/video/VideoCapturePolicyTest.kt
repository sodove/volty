package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoCapturePolicyTest {
    private val idle = VideoRecordingPhase.IDLE
    private val starting = VideoRecordingPhase.STARTING
    private val recording = VideoRecordingPhase.RECORDING
    private val stopping = VideoRecordingPhase.STOPPING

    // --- layout ---

    @Test
    fun idleThePreviewOverlayIsFittedToTheFrameTheDisplayShows() {
        // The emulator's 1280x720 sensor crop, turned 90° for a portrait display: a 9:16 box.
        assertEquals(720f / 1280f, videoPreviewAspect(1280, 720, 90)!!, 1e-6f)
        assertEquals(1280f / 720f, videoPreviewAspect(1280, 720, 0)!!, 1e-6f)
        assertEquals(720f / 1280f, videoPreviewAspect(1280, 720, -270)!!, 1e-6f)
        assertNull(videoPreviewAspect(0, 720, 90), "no crop yet")
        // Idle, the preview follows the display: a report measured in the other orientation is turned.
        assertEquals(9f / 16f, videoPreviewDisplayedAspect(null, windowLandscape = false), 1e-6f)
        assertEquals(16f / 9f, videoPreviewDisplayedAspect(null, windowLandscape = true), 1e-6f)
        assertEquals(16f / 9f, videoPreviewDisplayedAspect(9f / 16f, windowLandscape = true), 1e-6f)
        assertEquals(3f / 4f, videoPreviewDisplayedAspect(3f / 4f, windowLandscape = false), 1e-6f)
        assertEquals(9f / 16f, videoPreviewDisplayedAspect(Float.NaN, windowLandscape = false), 1e-6f)
        // The preview overlay never repaints faster than the file's own overlay.
        assertEquals(1000L / MAX_RECORDED_REPAINT_HZ, VIDEO_PREVIEW_OVERLAY_MILLIS)
    }

    @Test
    fun aRotationMidRecordingKeepsTheOverlayOnTheFrozenPicture() {
        // Start in portrait, rotate to landscape while recording (review I2).
        var frozen: Boolean? = null
        frozen = videoFrozenLandscape(idle, frozen, windowLandscape = false)
        assertNull(frozen, "idle follows the window")
        frozen = videoFrozenLandscape(starting, frozen, windowLandscape = false)
        assertEquals(false, frozen)
        frozen = videoFrozenLandscape(recording, frozen, windowLandscape = true)
        assertEquals(false, frozen, "the window turned, the picture did not")
        // The preview still shows the 9:16 portrait picture, so the overlay box (and with it the portrait layout) stays.
        assertEquals(9f / 16f, videoPreviewDisplayedAspect(9f / 16f, windowLandscape = true, frozenLandscape = frozen), 1e-6f)
        assertEquals(9f / 16f, videoPreviewDisplayedAspect(null, windowLandscape = true, frozenLandscape = frozen), 1e-6f)
        frozen = videoFrozenLandscape(stopping, frozen, windowLandscape = true)
        assertEquals(false, frozen)
        frozen = videoFrozenLandscape(idle, frozen, windowLandscape = true)
        assertNull(frozen, "after the stop the overlay follows the window again")
        assertEquals(16f / 9f, videoPreviewDisplayedAspect(9f / 16f, windowLandscape = true, frozenLandscape = frozen), 1e-6f)
    }

    @Test
    fun thePreviewOverlayIsDrawnOnlyOverAPicture() {
        assertTrue(videoPreviewOverlayShown(ready = true, cameraFailed = false, reportedAspect = 0.5625f))
        assertFalse(videoPreviewOverlayShown(ready = false, cameraFailed = false, reportedAspect = 0.5625f))
        assertFalse(videoPreviewOverlayShown(ready = true, cameraFailed = true, reportedAspect = 0.5625f))
        assertFalse(videoPreviewOverlayShown(ready = true, cameraFailed = false, reportedAspect = null), "no frame yet")
    }

    @Test
    fun theRailKeepsAShortLinkTextAndShortensALongOne() {
        assertEquals("T4 · 0.2 с", videoRailLinkText("T4 · 0.2 с", "0.2 с"))
        assertEquals("0.2 с", videoRailLinkText("Demo wheel · 0.2 с", "0.2 с"))
        // Exactly at the limit stays whole; one more character switches to the short form.
        assertEquals("123456789012", videoRailLinkText("123456789012", "s"))
        assertEquals("s", videoRailLinkText("1234567890123", "s"))
    }

    @Test
    fun theOverlayButtonCaptionFitsTheCircle() {
        assertEquals("HUD", videoPresetButtonLabel("HUD"))
        assertEquals("Минимум", videoPresetButtonLabel("Минимум"))
        assertEquals("Верхний", videoPresetButtonLabel("Верхний ряд"))
        assertEquals("Мой", videoPresetButtonLabel("Мой пресет для зимы"))
        assertEquals("Длинно…", videoPresetButtonLabel("Длинноеназвание"))
        assertEquals("HUD", videoPresetButtonLabel("  HUD  "))
        for (name in listOf("Верхний ряд", "Длинноеназвание", "Копия: HUD с картой")) {
            assertTrue(videoPresetButtonLabel(name).length <= VIDEO_PRESET_BUTTON_CHARS, name)
        }
    }

    @Test
    fun theRailAppearsOnlyInAWiderThanTallWindow() {
        assertTrue(videoCaptureIsLandscape(844f, 390f))
        assertFalse(videoCaptureIsLandscape(390f, 844f))
        assertFalse(videoCaptureIsLandscape(600f, 600f))
    }

    // --- shutter ---

    @Test
    fun theShutterStartsOnlyAReadyIdleCameraAndStopsOnlyARunningRecording() {
        assertEquals(VideoShutterAction.START, videoShutterAction(idle, ready = true))
        assertEquals(VideoShutterAction.NONE, videoShutterAction(idle, ready = false))
        assertEquals(VideoShutterAction.STOP, videoShutterAction(recording, ready = true))
        assertEquals(VideoShutterAction.STOP, videoShutterAction(recording, ready = false))
        assertEquals(VideoShutterAction.NONE, videoShutterAction(starting, ready = true))
        assertEquals(VideoShutterAction.NONE, videoShutterAction(stopping, ready = true))
    }

    @Test
    fun aDoubleTapIsOnePress() {
        val guard = VideoShutterGuard(800)
        assertTrue(guard.allow(10_000))
        assertFalse(guard.allow(10_200))
        assertFalse(guard.allow(10_799))
        assertTrue(guard.allow(10_800))
    }

    @Test
    fun theVolumeKeysOnlyEverStartARecording() {
        assertEquals(VideoVolumeKey.SHUTTER, videoVolumeKey(idle, ready = true, editing = false, repeatCount = 0))
        // A clamp on the phone's long edges must not be able to end the ride's video.
        assertEquals(VideoVolumeKey.CONSUME, videoVolumeKey(recording, ready = true, editing = false, repeatCount = 0))
        assertEquals(VideoVolumeKey.CONSUME, videoVolumeKey(recording, ready = false, editing = false, repeatCount = 0))
        assertEquals(VideoVolumeKey.PASS, videoVolumeKey(idle, ready = false, editing = false, repeatCount = 0))
        assertEquals(VideoVolumeKey.PASS, videoVolumeKey(idle, ready = true, editing = true, repeatCount = 0))
    }

    @Test
    fun aHeldKeyAndABusyRecorderSwallowThePress() {
        assertEquals(VideoVolumeKey.CONSUME, videoVolumeKey(recording, ready = true, editing = false, repeatCount = 3))
        assertEquals(VideoVolumeKey.CONSUME, videoVolumeKey(starting, ready = true, editing = false, repeatCount = 0))
        assertEquals(VideoVolumeKey.CONSUME, videoVolumeKey(stopping, ready = false, editing = false, repeatCount = 0))
    }

    @Test
    fun hapticsFollowTheRecorderNotThePress() {
        val complete = VideoStopOutcome.COMPLETE
        val failed = VideoStopOutcome.FAILED
        assertEquals(VideoHaptic.STARTED, videoHapticFor(starting, recording, null))
        assertEquals(VideoHaptic.STOPPED, videoHapticFor(stopping, idle, complete))
        assertEquals(VideoHaptic.FAILED, videoHapticFor(recording, idle, failed))
        assertEquals(VideoHaptic.FAILED, videoHapticFor(starting, idle, failed))
        assertNull(videoHapticFor(idle, starting, null))
        assertNull(videoHapticFor(recording, stopping, null))
        assertNull(videoHapticFor(recording, recording, null))
        assertNull(videoHapticFor(idle, idle, failed))
    }

    @Test
    fun aStopTheRiderDidNotAskForFeelsLikeAFailure() {
        assertEquals(VideoHaptic.FAILED, videoHapticFor(recording, idle, VideoStopOutcome.INTERRUPTED))
        assertEquals(VideoHaptic.FAILED, videoHapticFor(stopping, idle, VideoStopOutcome.INTERRUPTED))
    }

    @Test
    fun aCameraFailureOutranksAGoodFile() {
        assertEquals(VideoStopOutcome.FAILED, videoStopOutcome(cameraFailed = true, discarded = false, interrupted = false, stoppedForStorage = false))
        assertEquals(VideoStopOutcome.FAILED, videoStopOutcome(cameraFailed = false, discarded = true, interrupted = true, stoppedForStorage = false))
        assertEquals(VideoStopOutcome.INTERRUPTED, videoStopOutcome(cameraFailed = false, discarded = false, interrupted = true, stoppedForStorage = false))
        assertEquals(VideoStopOutcome.INTERRUPTED, videoStopOutcome(cameraFailed = false, discarded = false, interrupted = false, stoppedForStorage = true))
        assertEquals(VideoStopOutcome.COMPLETE, videoStopOutcome(cameraFailed = false, discarded = false, interrupted = false, stoppedForStorage = false))
    }

    @Test
    fun lowStoragePulsesOnceWhenItTurnsLowDuringARecording() {
        assertTrue(videoStorageAlert(wasLow = false, isLow = true, recording = true))
        assertFalse(videoStorageAlert(wasLow = true, isLow = true, recording = true))
        assertFalse(videoStorageAlert(wasLow = false, isLow = true, recording = false))
        assertFalse(videoStorageAlert(wasLow = true, isLow = false, recording = true))
    }

    @Test
    fun theAppStopsTheFileWithAMinuteInHand() {
        val bits = 8_000_000L // one MB/s
        assertFalse(videoStorageMustStop(60_000_000L, bits))
        assertTrue(videoStorageMustStop(59_999_999L, bits))
        assertFalse(videoStorageMustStop(null, bits))
        assertFalse(videoStorageMustStop(-1, bits))
        assertFalse(videoStorageMustStop(10, 0))
    }

    @Test
    fun theOpeningEditorBlocksTheShutterButNeverAStop() {
        // The race: the overlay is off for the editor's still, the chrome is still on screen.
        assertEquals(VideoShutterAction.NONE, videoShutterAction(idle, ready = true, blocked = true))
        assertEquals(VideoShutterAction.STOP, videoShutterAction(recording, ready = true, blocked = true))
        assertEquals(VideoVolumeKey.PASS, videoVolumeKey(idle, ready = true, editing = true, repeatCount = 0))
    }

    @Test
    fun recordedRepaintsAreCappedAtFifteenAndThePreviewIsNot() {
        val limiter = OverlayRepaintLimiter()
        val frames = (0 until 60).map { it * 33L / 1 }
        val drawn = frames.count { limiter.allow(it, limited = true) }
        assertTrue(drawn <= 15 * 2 + 1, "60 frames over ~2 s drew $drawn")
        assertTrue(drawn >= 14, "a change is held back, never dropped: $drawn")

        val free = OverlayRepaintLimiter()
        assertEquals(60, frames.count { free.allow(it, limited = false) })
    }

    @Test
    fun theCapNeverStarvesAndAResetAllowsAtOnce() {
        val limiter = OverlayRepaintLimiter(66)
        assertTrue(limiter.allow(1_000, true))
        assertFalse(limiter.allow(1_033, true))
        assertTrue(limiter.allow(1_066, true))
        limiter.reset()
        assertTrue(limiter.allow(1_070, true))
        // A clock that steps back is not a reason to stop drawing.
        assertTrue(limiter.allow(500, true))
    }

    @Test
    fun theBigClockKeepsItsHoursAndRunsPastADay() {
        assertEquals("00:12:48", videoRecordingClock(768))
        assertEquals("25:00:01", videoRecordingClock(90_001))
        assertEquals("00:00:00", videoRecordingClock(-1))
    }

    // --- back ---

    @Test
    fun backDismissesTheConfirmationBeforeAnythingElse() {
        assertEquals(VideoBackAction.DISMISS_CONFIRM, videoBackAction(editing = false, phase = recording, confirming = true))
        assertEquals(VideoBackAction.CONFIRM_STOP, videoBackAction(editing = false, phase = recording))
        assertEquals(VideoBackAction.CLOSE_SCREEN, videoBackAction(editing = false, phase = idle))
    }

    // --- storage ---

    @Test
    fun theWarningStartsBelowTenMinutes() {
        val bits = 8_000_000L // one MB/s decimal
        assertFalse(videoStorageStatus(600_000_000L, bits)!!.low)
        assertEquals(10, videoStorageStatus(600_000_000L, bits)!!.minutesLeft)
        assertTrue(videoStorageStatus(599_000_000L, bits)!!.low)
        assertEquals(9, videoStorageStatus(599_000_000L, bits)!!.minutesLeft)
    }

    @Test
    fun anUnreadableDiskSaysNothing() {
        assertNull(videoStorageStatus(null, 1_000))
        assertNull(videoStorageStatus(-1, 1_000))
        assertNull(videoStorageStatus(1_000, 0))
    }

    @Test
    fun aRunningRecordingMeasuresItsOwnRate() {
        assertEquals(17_000_000, videoBitsPerSecond(1080, audio = false, recordedBytes = 0, recordedNanos = 0))
        assertEquals(17_156_000, videoBitsPerSecond(1080, audio = true, recordedBytes = 0, recordedNanos = 0))
        // Too early to trust.
        assertEquals(45_000_000, videoBitsPerSecond(2160, audio = false, recordedBytes = 1_000_000, recordedNanos = 9_000_000_000))
        assertEquals(10_000_000, videoNominalBitsPerSecond(720))
        assertEquals(4_000_000, videoNominalBitsPerSecond(480))
        assertEquals(8_000_000, videoBitsPerSecond(1080, audio = true, recordedBytes = 10_000_000, recordedNanos = 10_000_000_000))
    }

    @Test
    fun sizesPrintLikeTheMock() {
        val gb = 1024L * 1024 * 1024
        assertEquals(VideoStorageSize("38", true), videoStorageSize(38 * gb))
        assertEquals(VideoStorageSize("1.2", true), videoStorageSize(gb + gb / 4))
        assertEquals(VideoStorageSize("640", false), videoStorageSize(640L * 1024 * 1024))
    }

    // --- link ---

    private fun sample(time: Long, value: Double? = 1.0, expiry: Map<VideoMetric, Long> = emptyMap(), vehicle: String = "v") =
        VideoSnapshot(time, vehicle, mapOf(VideoMetric.SPEED to VideoReading(value)), expiresAtMillis = expiry)

    @Test
    fun theChipSeparatesNoVehicleFromWaitingFromLiveFromLost() {
        val monitor = VideoLinkMonitor()
        assertEquals(VideoLinkStatus.NoVehicle, monitor.status(0))
        monitor.onSample(null)
        assertEquals(VideoLinkStatus.NoVehicle, monitor.status(1_000))
        monitor.onSample(sample(1_000, value = null))
        assertEquals(VideoLinkStatus.Waiting, monitor.status(1_100))
        monitor.onSample(sample(1_200))
        assertEquals(VideoLinkStatus.Live(200), monitor.status(1_400))
        assertEquals(VideoLinkStatus.Live(2_000), monitor.status(3_200))
        assertEquals(VideoLinkStatus.Lost(2_001), monitor.status(3_201))
    }

    @Test
    fun aDisconnectIsLostAtOnceAndADeadSnapshotDoesNotRefreshTheAge() {
        val monitor = VideoLinkMonitor()
        monitor.onSample(sample(1_000))
        monitor.onSample(sample(1_500, value = Double.NaN))
        assertEquals(VideoLinkStatus.Live(500), monitor.status(1_500))
        monitor.onSample(null)
        assertEquals(VideoLinkStatus.Lost(600), monitor.status(1_600))
    }

    @Test
    fun aReEmittedSnapshotIsAsOldAsItsSource() {
        val monitor = VideoLinkMonitor()
        // Received at 5000, but its speed expires at 4000: the source is from 2000, not from 5000.
        monitor.onSample(sample(5_000, expiry = mapOf(VideoMetric.SPEED to 4_000L)))
        assertEquals(VideoLinkStatus.Lost(3_000), monitor.status(5_000))
    }

    @Test
    fun aHeldDutySaysNothingAboutTheLink() {
        val monitor = VideoLinkMonitor()
        monitor.onSample(sample(5_000, expiry = mapOf(VideoMetric.SPEED to 4_000L, VideoMetric.DUTY to 9_000L)))
        assertEquals(VideoLinkStatus.Lost(3_000), monitor.status(5_000))
    }

    @Test
    fun anotherVehicleStartsAgain() {
        val monitor = VideoLinkMonitor()
        monitor.onSample(sample(1_000))
        monitor.onSample(sample(9_000, value = null, vehicle = "other"))
        assertEquals(VideoLinkStatus.Waiting, monitor.status(9_000))
    }

    @Test
    fun theAgeHasOneDecimal() {
        assertEquals("0.2", videoAgeSeconds(200))
        assertEquals("0.0", videoAgeSeconds(-5))
        assertEquals("1.5", videoAgeSeconds(1_490))
    }

    // --- fps ---

    @Test
    fun theFrameRateIsMeasuredOverAWholeSecond() {
        val meter = VideoFrameRateMeter()
        val results = (0..30).map { meter.onFrame(it * 33_333_334L) }
        assertEquals(listOf(30), results.filterNotNull())
        assertNull(VideoFrameRateMeter().onFrame(0))
    }

    // --- notices ---

    @Test
    fun aSavedFileNeverHidesAFailure() {
        val all = VideoCaptureFacts(error = true, overlayFailed = true, clockMismatch = true, saved = true)
        assertEquals(listOf(VideoCaptureNotice.ERROR, VideoCaptureNotice.OVERLAY_FAILED), videoCaptureNotices(all))
        val storage = VideoCaptureFacts(stoppedForStorage = true, saved = true, interrupted = true)
        assertEquals(listOf(VideoCaptureNotice.STORAGE_STOPPED, VideoCaptureNotice.INTERRUPTED), videoCaptureNotices(storage))
    }

    @Test
    fun theSavedNoticeSaysWhatHappened() {
        assertEquals(listOf(VideoCaptureNotice.SAVED), videoCaptureNotices(VideoCaptureFacts(saved = true, savedParts = 1)))
        assertEquals(listOf(VideoCaptureNotice.SAVED_PARTS), videoCaptureNotices(VideoCaptureFacts(saved = true, savedParts = 3)))
        assertEquals(listOf(VideoCaptureNotice.INTERRUPTED), videoCaptureNotices(VideoCaptureFacts(saved = true, savedParts = 1, interrupted = true)))
        assertEquals(emptyList(), videoCaptureNotices(VideoCaptureFacts()))
    }
}
