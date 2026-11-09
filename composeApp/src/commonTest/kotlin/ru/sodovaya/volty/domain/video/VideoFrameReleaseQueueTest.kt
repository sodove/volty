package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoFrameReleaseQueueTest {
    @Test fun flushOrdersUniqueCaptureTimestamps() {
        val queue = VideoFrameReleaseQueue()
        queue.offer(20)
        queue.offer(10)
        queue.offer(20)
        assertEquals(listOf(10L, 20L), queue.flush())
        assertTrue(queue.flush().isEmpty())
    }

    @Test fun captureMetadataStaysBoundedEvenWhenNoTexturesAreRendered() {
        val queue = VideoFrameReleaseQueue()
        (1L..80L).forEach(queue::offer)
        assertEquals((17L..80L).toList(), queue.flush())
    }

    @Test fun automaticReleaseRetiresOlderCaptureMetadata() {
        val queue = VideoFrameReleaseQueue()
        queue.offer(10)
        queue.offer(20)
        queue.offer(30)
        queue.rendered(20)
        queue.offer(15)
        assertEquals(listOf(30L), queue.flush())
    }

    @Test fun memoryBudgetBoundsUhdWhileHdCanKeepThreeHundredMillisAtSixtyFps() {
        assertEquals(18, videoFrameBufferDepth(1280, 720, 60))
        assertEquals(9, videoFrameBufferDepth(1280, 720, 30))
        val uhd = videoFrameBufferDepth(3840, 2160, 60)
        assertTrue(uhd * 3840L * 2160L * 4L <= 192L * 1024 * 1024)
        assertTrue(uhd < 18)
    }
}
