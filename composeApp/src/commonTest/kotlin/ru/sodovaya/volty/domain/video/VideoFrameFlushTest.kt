package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoFrameFlushTest {
    @Test fun aLateOldestTextureIsRetriedBeforeAnyNewerTimestampIsIssued() {
        val issued = mutableListOf<Long>()
        val flush = VideoFrameFlush(listOf(10, 20), 100)
        assertFalse(flush.drain(110) { issued += it; false })
        assertEquals(listOf(10L), issued)
        assertTrue(flush.drain(120) { issued += it; true })
        assertEquals(listOf(10L, 10L, 20L), issued)
    }

    @Test fun aTextureThatNeverArrivesCannotBlockRecorderStopIndefinitely() {
        val issued = mutableListOf<Long>()
        val flush = VideoFrameFlush(listOf(10, 20), 100)
        assertFalse(flush.drain(179) { false })
        assertTrue(flush.drain(180) { issued += it; it == 20L })
        assertEquals(listOf(10L, 20L), issued)
    }

    @Test fun aConcurrentStopCanExtendTheFlushWithoutOvertakingTheFirstRequest() {
        val flush = VideoFrameFlush(listOf(10, 20), 100)
        assertFalse(flush.drain(110) { false })
        flush.append(listOf(20, 30))
        val issued = mutableListOf<Long>()
        assertTrue(flush.drain(120) { issued += it; true })
        assertEquals(listOf(10L, 20L, 30L), issued)
    }
}
