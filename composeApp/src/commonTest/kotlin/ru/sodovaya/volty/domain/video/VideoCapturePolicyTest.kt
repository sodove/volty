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
    fun theVolumeKeysAreAShutterOnlyWhereThereIsOne() {
        assertEquals(VideoVolumeKey.SHUTTER, videoVolumeKey(idle, ready = true, editing = false, repeatCount = 0))
        assertEquals(VideoVolumeKey.SHUTTER, videoVolumeKey(recording, ready = true, editing = false, repeatCount = 0))
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
        assertEquals(VideoHaptic.STARTED, videoHapticFor(starting, recording, hasError = false))
        assertEquals(VideoHaptic.STOPPED, videoHapticFor(stopping, idle, hasError = false))
        assertEquals(VideoHaptic.FAILED, videoHapticFor(recording, idle, hasError = true))
        assertEquals(VideoHaptic.FAILED, videoHapticFor(starting, idle, hasError = true))
        assertNull(videoHapticFor(idle, starting, hasError = false))
        assertNull(videoHapticFor(recording, stopping, hasError = false))
        assertNull(videoHapticFor(recording, recording, hasError = false))
        assertNull(videoHapticFor(idle, idle, hasError = true))
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
    }

    @Test
    fun theSavedNoticeSaysWhatHappened() {
        assertEquals(listOf(VideoCaptureNotice.SAVED), videoCaptureNotices(VideoCaptureFacts(saved = true, savedParts = 1)))
        assertEquals(listOf(VideoCaptureNotice.SAVED_PARTS), videoCaptureNotices(VideoCaptureFacts(saved = true, savedParts = 3)))
        assertEquals(listOf(VideoCaptureNotice.INTERRUPTED), videoCaptureNotices(VideoCaptureFacts(saved = true, savedParts = 1, interrupted = true)))
        assertEquals(emptyList(), videoCaptureNotices(VideoCaptureFacts()))
    }
}
