package ru.sodovaya.volty.domain.voice

import kotlin.test.*

class SpeechGateTest {
    private val size = 480
    private fun frame(value: Int) = ShortArray(size) { value.toShort() }
    /** Feeds frames 0..[count) whose samples equal (index + 1) * 100; returns the outputs in order. */
    private fun run(gate: SpeechGate, count: Int, speech: (Int) -> Float): List<ShortArray> =
        (0 until count).map { i -> frame((i + 1) * 100).also { gate.process(it, speech(i)) } }

    @Test fun silenceAndNoiseNeverOpen() {
        val out = run(SpeechGate(threshold = 0.5f), 20) { 0.2f }
        assertTrue(out.all { f -> f.all { it == 0.toShort() } }, "below the threshold everything is muted")
    }

    @Test fun theOutputIsTwoFramesBehindTheInput() {
        val out = run(SpeechGate(threshold = 0.5f), 10) { 1f }
        // Output k carries input k - 2 (value (k - 1) * 100); the first two are the empty pre-roll.
        assertEquals(0, out[1].last().toInt())
        assertEquals(400, out[5].last().toInt())
    }

    @Test fun speechOpensTheGateTwentyMillisecondsBeforeItWasDetected() {
        val out = run(SpeechGate(threshold = 0.5f), 12) { if (it == 6) 0.9f else 0f }
        // Detected at input 6 → the output written then is input 4: the first consonant is kept.
        assertEquals(0, out[5].last().toInt(), "input 3 stays muted")
        assertEquals(500, out[6].last().toInt(), "input 4 is open by the end of its frame")
        assertEquals(0, out[6].first().toInt(), "and ramps in rather than stepping")
    }

    @Test fun theGateHoldsThreeHundredMillisecondsAfterTheLastSpeech() {
        val out = run(SpeechGate(threshold = 0.5f), 50) { if (it == 6) 0.9f else 0f }
        // Inputs 4..36 (pre-roll 2 + the speech frame + 30 hold frames) are open; outputs lag by 2.
        assertEquals(3700, out[38].last().toInt(), "input 36 is still open")
        assertEquals(0, out[40].last().toInt(), "input 38 has closed")
    }

    @Test fun openingNeverStepsFasterThanTheRamp() {
        val gate = SpeechGate(threshold = 0.5f)
        // A steady signal while the gate is closed, then speech: the only change is the gate opening.
        val out = (0 until 10).map { i -> frame(10_000).also { f -> gate.process(f, if (i >= 5) 1f else 0f) } }
        val samples = out.flatMap { it.toList() }.map { it.toInt() }
        val largestStep = samples.zipWithNext { a, b -> b - a }.max()
        assertTrue(largestStep <= 10_000 / 240 + 1, "a 5 ms ramp, no click: step $largestStep")
    }

    @Test fun aFrameOfTheWrongSizeIsLeftUntouched() {
        val gate = SpeechGate(threshold = 0.5f)
        val odd = ShortArray(441) { 1234 }
        gate.process(odd, 0f)
        assertTrue(odd.all { it == 1234.toShort() }, "only 10 ms at 48 kHz is gated; anything else passes")
    }
}
