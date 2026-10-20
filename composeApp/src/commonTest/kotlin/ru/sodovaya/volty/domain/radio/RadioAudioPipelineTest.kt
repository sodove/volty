package ru.sodovaya.volty.domain.radio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioAudioPipelineTest {

    private fun dbfsToRms(db: Double): Double = 32768.0 * 10.0.pow(db / 20.0)

    /** Uniform-ish noise scaled so its RMS is [db] dBFS (uniform in [-a,a] has rms a/sqrt3). */
    private fun noiseFrame(random: Random, db: Double): ShortArray {
        val a = dbfsToRms(db) * 1.7320508
        return ShortArray(RADIO_FRAME_SAMPLES) { ((random.nextDouble() * 2 - 1) * a).toInt().toShort() }
    }

    private fun toneFrame(db: Double): ShortArray {
        val amp = dbfsToRms(db) * 1.4142136
        return ShortArray(RADIO_FRAME_SAMPLES) { (amp * sin(2 * PI * 440.0 * it / 16000.0)).toInt().toShort() }
    }

    private fun frameOf(value: Int, marker: Int = value) =
        ShortArray(RADIO_FRAME_SAMPLES) { value.toShort() }.also { it[0] = marker.toShort() }

    // ---- gate ----

    @Test
    fun silenceNeverOpens() {
        val gate = RadioVoiceGate()
        repeat(300) { assertFalse(gate.process(ShortArray(RADIO_FRAME_SAMPLES))) }
    }

    @Test
    fun speechAboveNoiseOpens() {
        val gate = RadioVoiceGate()
        val random = Random(1)
        repeat(100) { gate.process(noiseFrame(random, -60.0)) }
        assertTrue(gate.process(toneFrame(-30.0)))
    }

    @Test
    fun steadyWindNoiseClosesAfterAdaptation() {
        val gate = RadioVoiceGate()
        val random = Random(2)
        val results = List(300) { gate.process(noiseFrame(random, -35.0)) }
        assertTrue(results.drop(100).none { it }, "must be closed after the first 2 s")
        assertTrue(results.drop(50).none { it })
    }

    @Test
    fun hangoverKeepsOpen15Frames() {
        val gate = RadioVoiceGate()
        val random = Random(3)
        repeat(100) { gate.process(noiseFrame(random, -60.0)) }
        assertTrue(gate.process(toneFrame(-30.0)))
        repeat(15) { assertTrue(gate.process(noiseFrame(random, -60.0)), "hangover frame $it") }
        assertFalse(gate.process(noiseFrame(random, -60.0)))
    }

    // ---- jitter buffer ----

    @Test
    fun waitsForTargetBeforePlaying() {
        val buffer = RadioJitterBuffer(targetFrames = 3)
        buffer.push(0, frameOf(1))
        buffer.push(1, frameOf(1))
        assertNull(buffer.pop())
        buffer.push(2, frameOf(1))
        assertNotNull(buffer.pop())
    }

    @Test
    fun reordersOutOfOrderFrames() {
        val buffer = RadioJitterBuffer(targetFrames = 3)
        buffer.push(2, frameOf(100, 2))
        buffer.push(0, frameOf(100, 0))
        buffer.push(1, frameOf(100, 1))
        assertEquals(listOf(0, 1, 2), List(3) { buffer.pop()!![0].toInt() })
    }

    @Test
    fun concealsSingleLoss() {
        val buffer = RadioJitterBuffer(targetFrames = 2)
        buffer.push(0, frameOf(1000))
        buffer.push(1, frameOf(1000))
        buffer.pop(); buffer.pop()
        buffer.push(3, frameOf(1000, 3))
        buffer.push(4, frameOf(1000, 4))
        val concealed = assertNotNull(buffer.pop())
        assertEquals(500, concealed[5].toInt())
        assertEquals(3, buffer.pop()!![0].toInt())
        assertEquals(4, buffer.pop()!![0].toInt())
    }

    @Test
    fun returnsNullOnLongGap() {
        val buffer = RadioJitterBuffer(targetFrames = 2)
        buffer.push(0, frameOf(1000))
        buffer.push(1, frameOf(1000))
        buffer.pop(); buffer.pop()
        buffer.push(6, frameOf(1000, 6))
        buffer.push(7, frameOf(1000, 7))
        assertNull(buffer.pop())
        assertEquals(6, buffer.pop()!![0].toInt())
    }

    @Test
    fun dropsLateFrame() {
        val buffer = RadioJitterBuffer(targetFrames = 2)
        buffer.push(5, frameOf(1, 5))
        buffer.push(6, frameOf(1, 6))
        assertEquals(5, buffer.pop()!![0].toInt())
        buffer.push(4, frameOf(1, 4))
        buffer.push(5, frameOf(1, 5))
        assertEquals(6, buffer.pop()!![0].toInt())
        assertNull(buffer.pop())
    }

    @Test
    fun capsAtMaxFrames() {
        val buffer = RadioJitterBuffer(targetFrames = 3, maxFrames = 10)
        repeat(15) { buffer.push(it.toLong(), frameOf(1, it)) }
        assertEquals(5, buffer.pop()!![0].toInt())
        var count = 1
        while (buffer.pop() != null) count++
        assertEquals(10, count)
    }

    // ---- mixer ----

    @Test
    fun mixOfOneIsIdentityBelowKnee() {
        val frame = ShortArray(RADIO_FRAME_SAMPLES) { (it * 70 - 11000).toShort() }
        assertContentEquals(frame, RadioMixer.mix(listOf(frame)))
    }

    @Test
    fun sumsTwoQuietFrames() {
        val mixed = RadioMixer.mix(listOf(frameOf(1000), frameOf(-300)))
        assertTrue(mixed.all { it.toInt() == 700 })
        assertEquals(RADIO_FRAME_SAMPLES, mixed.size)
    }

    @Test
    fun neverOverflows() {
        val loud = ShortArray(RADIO_FRAME_SAMPLES) { Short.MAX_VALUE }
        val mixed = RadioMixer.mix(List(9) { loud })
        assertTrue(mixed.all { it <= Short.MAX_VALUE && it > 24576 })
        val negative = RadioMixer.mix(List(9) { ShortArray(RADIO_FRAME_SAMPLES) { Short.MIN_VALUE } })
        assertTrue(negative.all { it >= Short.MIN_VALUE && it < -24576 })
    }
}
