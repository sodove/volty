package ru.sodovaya.volty.domain.radio

enum class RadioRole { HUB, CLIENT }

/** One member as measured by this phone. Null means "not measured", never zero. */
data class RadioMemberStat(val name: String, val rttMillis: Long?, val lossPercent: Int?)

/**
 * What a field tester needs to attach to a result: who was hub, how many people, per-member round
 * trip and receive loss, and how much battery the radio cost. Unknown values print as `n/a`.
 */
data class RadioDiagnostics(
    val role: RadioRole,
    val mode: RadioMode?,
    val members: List<RadioMemberStat>,
    val batteryStartPercent: Int?,
    val batteryNowPercent: Int?,
    val elapsedMillis: Long,
    val load: RadioLoad? = null,
) {
    fun format(): String = buildString {
        appendLine("role=${if (role == RadioRole.HUB) "hub" else "client"}")
        appendLine("mode=${mode?.name ?: "n/a"}")
        appendLine("members=${members.size}")
        members.forEach {
            val rtt = it.rttMillis?.let { r -> "${r}ms" } ?: "n/a"
            val loss = it.lossPercent?.let { l -> "$l%" } ?: "n/a"
            appendLine("member ${it.name.filter { c -> !c.isISOControl() }} rtt=$rtt loss=$loss")
        }
        appendLine("battery start=${batteryStartPercent?.let { "$it%" } ?: "n/a"} now=${batteryNowPercent?.let { "$it%" } ?: "n/a"}")
        appendLine("elapsed=${clock(elapsedMillis)}")
        val l = load
        val late = l?.lateTicks?.toString() ?: "n/a"
        appendLine("tick max=${millis(l?.tickMaxMillis)} p95=${millis(l?.tickP95Millis)} late(>20ms)=$late")
        appendLine("dropped_encodes=${l?.droppedEncodes ?: "n/a"}")
        appendLine("rejected_inbound=${l?.rejectedInbound ?: "n/a"}")
        appendLine("decode_failures=${l?.decodeFailures ?: "n/a"}")
        val depths = l?.jitterDepthFrames.orEmpty().toSortedMap()
        appendLine(
            if (depths.isEmpty()) "jitter_frames n/a"
            else "jitter_frames " + depths.entries.joinToString(" ") { "slot${it.key}=${it.value}" },
        )
    }

    /** One decimal, no locale: `2.0ms`; `n/a` when not measured. */
    private fun millis(value: Double?): String {
        if (value == null) return "n/a"
        val tenths = kotlin.math.round(value * 10).toLong()
        return "${tenths / 10}.${tenths % 10}ms"
    }

    private fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        fun two(n: Long) = n.toString().padStart(2, '0')
        return "${two(total / 3600)}:${two(total / 60 % 60)}:${two(total % 60)}"
    }
}

/**
 * How hard the radio's audio thread is working (I6 of the final review): what a field tester needs
 * to tell "the hub phone is overloaded" from "the air is bad".
 *
 * @param tickMaxMillis longest audio tick (all receive, mix, send and playback work for one 20 ms frame).
 * @param tickP95Millis 95th percentile tick, to 0.1 ms.
 * @param lateTicks ticks that took longer than one frame (20 ms): each one delays the mic.
 * @param droppedEncodes outbound messages that could not be encoded and were dropped.
 * @param rejectedInbound datagrams that failed authentication, replay or parsing.
 * @param decodeFailures Opus payloads the decoder rejected (concealed as lost).
 * @param jitterDepthFrames frames waiting in each sender's jitter buffer right now, by slot.
 */
data class RadioLoad(
    val tickMaxMillis: Double?,
    val tickP95Millis: Double?,
    val lateTicks: Long,
    val droppedEncodes: Long,
    val rejectedInbound: Long,
    val decodeFailures: Long,
    val jitterDepthFrames: Map<Int, Int>,
)

/**
 * Aggregates audio tick durations without keeping them: a 0.1 ms histogram up to 200 ms plus an
 * overflow bucket, the exact maximum and a count of late ticks. Not thread-safe: audio thread only;
 * publish a [RadioLoad] copy for other threads.
 */
class RadioTickStats {
    private val buckets = LongArray(BUCKETS + 1) // last = overflow
    private var count = 0L
    private var maxNanos = 0L

    var lateTicks: Long = 0
        private set

    fun record(nanos: Long) {
        val n = nanos.coerceAtLeast(0)
        count++
        if (n > maxNanos) maxNanos = n
        if (n > FRAME_NANOS) lateTicks++
        // Ceiling, so a bucket's value is an upper bound: a 5.0 ms tick lands in "<= 5.0 ms".
        val index = ((n + BUCKET_NANOS - 1) / BUCKET_NANOS).coerceAtMost(BUCKETS.toLong()).toInt()
        buckets[index]++
    }

    fun maxMillis(): Double? = if (count == 0L) null else maxNanos / 1_000_000.0

    /** Upper bound of the 95th percentile, to 0.1 ms; the maximum when it lies beyond the histogram. */
    fun p95Millis(): Double? {
        if (count == 0L) return null
        val rank = (count * 95 + 99) / 100
        var seen = 0L
        for (i in 0 until BUCKETS) {
            seen += buckets[i]
            if (seen >= rank) return i / 10.0
        }
        return maxMillis()
    }

    private companion object {
        const val BUCKET_NANOS = 100_000L
        const val BUCKETS = 2_000 // 200 ms
        const val FRAME_NANOS = 20_000_000L
    }
}

/**
 * Receive loss from gaps in a stream's sequence numbers. Only a number higher than every one seen
 * so far counts as received: a repeat or a frame that arrives after a later one was already seen
 * is of no use to the jitter buffer either, so it is neither a negative loss nor a recovery.
 * Audio thread only.
 */
class RadioLossCounter {
    private var first: Long? = null
    private var highest = 0L
    private var received = 0L

    fun onSeq(seq: Long) {
        if (first == null || seq < highest - RESTART_GAP) {
            // First frame, or a sender that restarted its numbering (a rejoin): count from here.
            first = seq
            highest = seq
            received = 1
        } else if (seq > highest) {
            highest = seq
            received++
        }
    }

    /** Whole percent of expected frames that never arrived in order, or null before the first frame. */
    fun lossPercent(): Int? {
        val start = first ?: return null
        val expected = highest - start + 1
        return ((expected - received) * 100 / expected).toInt()
    }

    private companion object {
        /** Further behind than any jitter buffer could reorder (10 s of frames): the sender restarted. */
        const val RESTART_GAP = 500L
    }
}
