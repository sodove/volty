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
    }

    private fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        fun two(n: Long) = n.toString().padStart(2, '0')
        return "${two(total / 3600)}:${two(total / 60 % 60)}:${two(total % 60)}"
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
