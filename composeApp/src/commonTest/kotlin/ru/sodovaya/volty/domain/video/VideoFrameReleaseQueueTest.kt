package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoFrameReleaseQueueTest {
    @Test fun waitsForAnUpperSampleThenReleasesInTimestampOrder() {
        val queue = VideoFrameReleaseQueue()
        queue.offer(10, 100)
        queue.offer(20, 116)
        assertEquals(null, queue.next(150) { true })
        assertEquals(null, queue.next(190) { false })
        assertEquals(10L, queue.next(200) { it == 10L })
        assertEquals(null, queue.next(200) { false })
        assertEquals(20L, queue.next(210) { true })
    }

    @Test fun aMissingUpperSampleCannotBlockForeverAndFlushPreservesOrder() {
        val queue = VideoFrameReleaseQueue()
        queue.offer(10, 0)
        queue.offer(20, 16)
        assertEquals(10L, queue.next(300) { false })
        assertEquals(listOf(20L), queue.flush())
        assertTrue(queue.flush().isEmpty())
    }

    @Test fun retriesTheOldestUnavailableTextureAndAcknowledgesAutomaticOverflow() {
        val queue = VideoFrameReleaseQueue()
        queue.offer(10, 0)
        queue.offer(20, 16)
        assertEquals(10L, queue.next(100) { true })
        queue.retry(10, 0)
        assertEquals(10L, queue.next(110) { true })
        queue.rendered(20)
        assertTrue(queue.flush().isEmpty())
        queue.offer(15, 32)
        assertTrue(queue.flush().isEmpty())
    }

    @Test fun memoryBudgetBoundsUhdWhileHdCanKeepThreeHundredMillisAtSixtyFps() {
        assertEquals(22, videoFrameBufferDepth(1280, 720, 60))
        val uhd = videoFrameBufferDepth(3840, 2160, 60)
        assertTrue(uhd * 3840L * 2160L * 4L <= 192L * 1024 * 1024)
        assertTrue(uhd < 22)
    }
}
