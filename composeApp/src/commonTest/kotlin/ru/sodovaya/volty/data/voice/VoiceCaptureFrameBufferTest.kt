package ru.sodovaya.volty.data.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCaptureFrameBufferTest {
    @Test fun unevenHardwareReadsSurviveCallbackUnderflowWithoutLosingPCM() {
        val buffer = VoiceCaptureFrameBuffer(4)
        buffer.begin(1); buffer.append(byteArrayOf(1, 2, 99), 2, 7)
        assertNull(buffer.complete())
        buffer.begin(1); buffer.append(byteArrayOf(3, 4), 2, 7)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), buffer.complete())
        buffer.consume(); assertNull(buffer.complete())
    }
    @Test fun partialPreMuteOrPreSwitchPCMIsDiscardedByTheNewRevision() {
        val buffer = VoiceCaptureFrameBuffer(4)
        buffer.begin(1); buffer.append(byteArrayOf(1, 2), 2, 7)
        buffer.begin(3); buffer.append(byteArrayOf(3, 4, 5, 6), 4, 7)
        assertContentEquals(byteArrayOf(3, 4, 5, 6), buffer.complete())
    }
    @Test fun nativeReroutingCannotMixTwoMicrophonesIntoOneFrame() {
        val buffer = VoiceCaptureFrameBuffer(4)
        buffer.begin(1); buffer.append(byteArrayOf(1, 2), 2, 7)
        buffer.append(byteArrayOf(3, 4), 2, 9)
        assertNull(buffer.complete()); assertEquals(9, buffer.device)
        buffer.append(byteArrayOf(5, 6), 2, 9)
        assertContentEquals(byteArrayOf(3, 4, 5, 6), buffer.complete())
    }
}
