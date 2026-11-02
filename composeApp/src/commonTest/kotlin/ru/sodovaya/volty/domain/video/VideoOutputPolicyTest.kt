package ru.sodovaya.volty.domain.video

import kotlin.test.*

class VideoOutputPolicyTest {
    @Test fun aRecoverableInterruptionKeepsTheExistingRecordedPart() {
        assertEquals(VideoOutputStatus.INTERRUPTED, videoOutputStatus(false, true, true, 20_000_000_000L))
    }

    @Test fun sourceInactiveBeforeAnyFramesCannotBeReportedAsSaved() {
        assertEquals(VideoOutputStatus.DISCARD, videoOutputStatus(false, true, true, 0L))
        assertEquals(VideoOutputStatus.DISCARD, videoOutputStatus(true, false, false, 1L))
    }

    @Test fun onlyCompleteOrDocumentedRecoverableOutputCanBeRetained() {
        assertEquals(VideoOutputStatus.COMPLETE, videoOutputStatus(true, false, true, 1L))
        assertEquals(VideoOutputStatus.DISCARD, videoOutputStatus(false, false, true, 1L))
        assertEquals(VideoOutputStatus.DISCARD, videoOutputStatus(false, true, false, 1L))
    }
}
