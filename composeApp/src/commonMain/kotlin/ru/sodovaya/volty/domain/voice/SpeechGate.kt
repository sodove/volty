package ru.sodovaya.volty.domain.voice

/**
 * Passes the microphone only while someone is speaking; the sound itself is never altered.
 *
 * A denoiser that rewrites the voice is what makes it "robotic"; here RNNoise only answers "is this
 * frame speech?" and the gate mutes the frames that are not — a zipper, a breath, a knock while the
 * rider is silent. Three things keep speech whole:
 * - a [lookaheadFrames] delay (20 ms), so the frames just before detection — the first consonant —
 *   are still opened;
 * - a [holdFrames] hang-over (300 ms) after the last speech frame, so word endings are not cut;
 * - a [rampSamples] fade (5 ms) on every open and close, so the gate never clicks.
 *
 * Works on 10 ms frames of mono 48 kHz PCM16 ([frameSize] samples); any other frame passes untouched.
 * One instance per capture stream; never allocates after construction. Not thread-safe.
 */
class SpeechGate(
    private val threshold: Float,
    private val frameSize: Int = 480,
    private val lookaheadFrames: Int = 2,
    private val holdFrames: Int = 30,
    private val rampSamples: Int = 240,
) {
    init { require(frameSize > 0 && lookaheadFrames >= 0 && holdFrames >= 0 && rampSamples > 0) }

    private val ring = Array(lookaheadFrames + 1) { ShortArray(frameSize) }
    private var head = 0
    private var holdLeft = 0
    private var gain = 0f
    private val step = 1f / rampSamples

    /** In place: [frame] receives the input from [lookaheadFrames] calls ago, gated. */
    fun process(frame: ShortArray, speechProbability: Float) {
        if (frame.size != frameSize) return
        frame.copyInto(ring[head])
        // Detection at this frame opens the delayed output from lookaheadFrames back through holdFrames ahead.
        if (speechProbability >= threshold) holdLeft = holdFrames + lookaheadFrames + 1
        val target = if (holdLeft > 0) 1f else 0f
        val delayed = ring[(head + 1) % ring.size]
        for (i in 0 until frameSize) {
            frame[i] = (delayed[i] * gain).let { if (it >= 0f) it + 0.5f else it - 0.5f }.toInt().toShort()
            gain = if (gain < target) minOf(target, gain + step) else maxOf(target, gain - step)
        }
        if (holdLeft > 0) holdLeft--
        head = (head + 1) % ring.size
    }
}
