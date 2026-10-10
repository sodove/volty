package ru.sodovaya.volty.data.voice

import kotlin.test.*

class VoicePlaybackBufferTest {
    @Test fun partialNativeWriteKeepsExactTailAcrossCallbacks() {
        val buffer = VoicePlaybackBuffer(16, 2)
        buffer.append(byteArrayOf(1,2,3,4,5,6))
        val issued = mutableListOf<List<Byte>>()
        buffer.drain { pcm, count -> issued += pcm.take(count); 2 }
        assertEquals(listOf(listOf<Byte>(1,2,3,4,5,6)), issued)
        assertEquals(4, buffer.size)
        buffer.append(byteArrayOf(7,8))
        buffer.drain { pcm, count -> issued += pcm.take(count); count }
        assertEquals(listOf<Byte>(3,4,5,6,7,8), issued.last())
        assertEquals(0, buffer.size)
    }
    @Test fun fullNativeBufferDoesNotDiscardVoice() {
        val buffer = VoicePlaybackBuffer(8, 2)
        buffer.append(byteArrayOf(1,2,3,4))
        assertEquals(0, buffer.drain { _, _ -> 0 })
        assertEquals(4, buffer.size)
        buffer.drain { pcm, count -> assertContentEquals(byteArrayOf(1,2,3,4), pcm.copyOf(count)); count }
    }
    @Test fun overflowDropsOldestAlignedSamplesRatherThanGrowingLatency() {
        val buffer = VoicePlaybackBuffer(8, 2)
        buffer.append(byteArrayOf(1,2,3,4,5,6))
        buffer.append(byteArrayOf(7,8,9,10))
        assertEquals(8, buffer.size)
        buffer.drain { pcm, count -> assertContentEquals(byteArrayOf(3,4,5,6,7,8,9,10), pcm.copyOf(count)); count }
    }
    @Test fun muteOrRerouteClearsPreviouslyQueuedSamples() {
        val buffer = VoicePlaybackBuffer(8, 2)
        buffer.append(byteArrayOf(1,2)); buffer.clear()
        buffer.append(byteArrayOf(3,4))
        assertEquals(2, buffer.drain { pcm, count -> assertContentEquals(byteArrayOf(3,4), pcm.copyOf(count)); count })
        assertEquals(0, buffer.size)
    }
}
