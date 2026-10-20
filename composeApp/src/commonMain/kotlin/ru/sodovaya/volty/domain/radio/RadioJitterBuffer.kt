package ru.sodovaya.volty.domain.radio

/**
 * Per-sender reorder buffer. Plays once [targetFrames] are buffered, drops late frames, caps at
 * [maxFrames] (oldest dropped), conceals exactly one missing frame by repeating the previous
 * one at half amplitude, and returns null for anything longer (then resyncs to what arrived).
 *
 * Once drained it re-primes: the next talk burst waits for [targetFrames] again and starts clean.
 * Late frames are rejected against a high-water mark of the highest seq ever played, which the
 * drain never resets; a sender restart uses a new session id and therefore a new buffer, so a
 * never-reset mark is correct.
 */
class RadioJitterBuffer(private val targetFrames: Int = 3, private val maxFrames: Int = 10) {
    private val frames = sortedMapOf<Long, ShortArray>()
    private var started = false
    private var lastSeq: Long? = null
    private var highWater: Long? = null
    private var lastFrame: ShortArray? = null

    fun push(seq: Long, frame: ShortArray) {
        val played = highWater
        if (played != null && seq <= played) return
        if (frames.containsKey(seq)) return
        frames[seq] = frame
        while (frames.size > maxFrames) frames.remove(frames.firstKey())
    }

    /** Null = nothing to play. */
    fun pop(): ShortArray? {
        if (!started) {
            if (frames.size < targetFrames) return null
            started = true
        }
        if (frames.isEmpty()) {
            // Drained: the next talk burst must prime to targetFrames again and start clean.
            started = false
            lastSeq = null
            lastFrame = null
            return null
        }
        val last = lastSeq
        val oldest = frames.firstKey()
        if (last == null || oldest == last + 1) return take(oldest)
        if (oldest == last + 2) {
            val previous = lastFrame
            lastSeq = last + 1
            highWater = last + 1
            if (previous == null) return null
            val concealed = ShortArray(previous.size) { (previous[it] / 2).toShort() }
            lastFrame = concealed
            return concealed
        }
        // Longer gap: report silence once, then resume from what is actually buffered.
        // Everything before [oldest] is declared lost and must never play late.
        lastSeq = oldest - 1
        highWater = maxOf(highWater ?: (oldest - 1), oldest - 1)
        return null
    }

    private fun take(seq: Long): ShortArray {
        val frame = frames.remove(seq)!!
        lastSeq = seq
        highWater = seq
        lastFrame = frame
        return frame
    }
}
