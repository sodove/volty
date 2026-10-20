package ru.sodovaya.volty.data.radio

import ru.sodovaya.volty.domain.radio.RADIO_FRAME_SAMPLES
import ru.sodovaya.volty.domain.radio.RadioMode
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioFrameCodecTest {

    private fun tone(frameIndex: Int): ShortArray = ShortArray(RADIO_FRAME_SAMPLES) { i ->
        val n = frameIndex * RADIO_FRAME_SAMPLES + i
        (sin(2.0 * PI * 1000.0 * n / 16000.0) * 8000.0).toInt().toShort()
    }

    private fun rms(a: ShortArray): Double =
        sqrt(a.sumOf { it.toDouble() * it.toDouble() } / a.size)

    @Test
    fun pcmRoundTripIsExact() {
        val pcm = ShortArray(RADIO_FRAME_SAMPLES) { ((it * 211) - 30000).toShort() }
        pcm[0] = Short.MIN_VALUE
        pcm[1] = Short.MAX_VALUE
        val payload = RadioFrameCodecs.encoder(RadioMode.LOW_LATENCY).encode(pcm)
        assertEquals(640, payload.size)
        // little-endian: first sample 0x8000 -> 00 80
        assertEquals(0x00.toByte(), payload[0])
        assertEquals(0x80.toByte(), payload[1])
        val out = RadioFrameCodecs.decoder(RadioMode.LOW_LATENCY).decode(payload)
        assertContentEquals(pcm, out)
    }

    @Test
    fun opusBalancedRoundTripPreservesTone() {
        val enc = RadioFrameCodecs.encoder(RadioMode.BALANCED)
        val dec = RadioFrameCodecs.decoder(RadioMode.BALANCED)
        var lastIn = ShortArray(0)
        var lastOut = ShortArray(0)
        repeat(12) { f ->
            lastIn = tone(f)
            lastOut = dec.decode(enc.encode(lastIn))
            assertEquals(RADIO_FRAME_SAMPLES, lastOut.size)
        }
        val db = 20.0 * log10(rms(lastOut) / rms(lastIn))
        assertTrue(kotlin.math.abs(db) <= 3.0, "RMS delta was $db dB")
    }

    @Test
    fun opusFrameSizeMatchesBitrate() {
        for ((mode, max) in listOf(RadioMode.BALANCED to 80, RadioMode.RELIABLE to 50)) {
            val enc = RadioFrameCodecs.encoder(mode)
            repeat(10) { f ->
                val size = enc.encode(tone(f)).size
                assertTrue(size in 1..max, "$mode frame $f was $size bytes (max $max)")
            }
        }
    }

    private fun loud(frameIndex: Int): ShortArray = ShortArray(RADIO_FRAME_SAMPLES) { i ->
        val n = frameIndex * RADIO_FRAME_SAMPLES + i
        (sin(2.0 * PI * 1000.0 * n / 16000.0) * 20000.0).toInt().toShort()
    }

    @Test
    fun decoderConcealsLostFrame() {
        for (mode in RadioMode.entries) {
            // fresh decoder must not throw
            assertEquals(RADIO_FRAME_SAMPLES, RadioFrameCodecs.decoder(mode).decode(null).size, "$mode fresh")
        }
        for (mode in listOf(RadioMode.BALANCED, RadioMode.RELIABLE)) {
            val enc = RadioFrameCodecs.encoder(mode)
            val dec = RadioFrameCodecs.decoder(mode)
            repeat(8) { dec.decode(enc.encode(loud(it))) }
            val out = dec.decode(null)
            assertEquals(RADIO_FRAME_SAMPLES, out.size, "$mode")
            assertTrue(out.any { it.toInt() != 0 }, "$mode concealment was silent")
        }
    }

    @Test
    fun garbagePayloadDecodesAsConcealment() {
        for (mode in listOf(RadioMode.BALANCED, RadioMode.RELIABLE)) {
            val enc = RadioFrameCodecs.encoder(mode)
            val dec = RadioFrameCodecs.decoder(mode)
            repeat(3) { dec.decode(enc.encode(loud(it))) }
            val valid = enc.encode(loud(3))
            val bad = listOf(
                ByteArray(40) { 0xFF.toByte() },
                valid.copyOf(valid.size / 2),
                ByteArray(0),
            )
            for (b in bad) assertEquals(RADIO_FRAME_SAMPLES, dec.decode(b).size, "$mode len ${b.size}")
        }
    }

    @Test
    fun pcmWrongLengthIsTreatedAsLost() {
        val dec = RadioFrameCodecs.decoder(RadioMode.LOW_LATENCY)
        for (n in listOf(0, 1, 639, 641, 1280)) {
            val out = dec.decode(ByteArray(n) { 0x7F })
            assertContentEquals(ShortArray(RADIO_FRAME_SAMPLES), out, "len $n")
        }
    }
    @Test
    fun modeWireRoundTrips() {
        for (m in RadioMode.entries) assertEquals(m, RadioMode.fromWire(m.wire))
        assertNull(RadioMode.fromWire(0))
        assertNull(RadioMode.fromWire(4))
        assertEquals(listOf(2, 3, 6), RadioMode.entries.map { it.jitterTargetFrames })
        assertNull(RadioMode.LOW_LATENCY.opusBitrate)
        assertEquals(24_000, RadioMode.BALANCED.opusBitrate)
        assertEquals(12_000, RadioMode.RELIABLE.opusBitrate)
        assertEquals(listOf(false, false, true), RadioMode.entries.map { it.fec })
    }
}