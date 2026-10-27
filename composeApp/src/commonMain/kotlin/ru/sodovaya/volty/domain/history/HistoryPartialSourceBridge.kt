package ru.sodovaya.volty.domain.history

/**
 * READ-time repair of one proven recording artefact: a Begode 0x00 frame used to store an explicit
 * `controller_power_w` unknown (reason PARTIAL_SOURCE) between every pair of 0x07 power values. Rides recorded
 * before Task 6 of the telemetry-plausibility plan carry that alternation, and the chart overview and the energy
 * integration both treat an unknown as a break, so those rides show a handful of isolated points and no Wh.
 *
 * The rule: an unknown with reason **PARTIAL_SOURCE** in a **controller_power_w** series, bracketed by a known value
 * on each side of the same clock generation with at most [PARTIAL_SOURCE_BRIDGE_MILLIS] between those two known
 * values, is treated as absent (it is not a segment break). Everything else still breaks: any other reason (a real
 * UNAVAILABLE/INVALID/GAP), a longer gap, a clock-generation change, an unknown with no known value after it.
 * Markers (BLE gaps, interruptions) are not touched here; callers apply them to the bridged samples as always.
 *
 * Scope: deliberately ONLY controller power. The artefact is proven for that metric from that frame; no other
 * series' PARTIAL_SOURCE is evidence of the same thing (a smart-BMS cell summary is genuinely partial on pages that
 * do not carry every cell), so bridging them would be inventing continuity. False-positive risk: a genuine
 * missing-voltage stretch shorter than 2 s between two real values is bridged; it would show as a line across a
 * sub-2 s hole, the same tolerance the integrator already uses between consecutive known samples.
 */
internal const val PARTIAL_SOURCE_BRIDGE_MILLIS = 2000L
internal const val BRIDGED_METRIC_CONTROLLER_POWER = "controller_power_w"

internal fun bridgesPartialSourceArtefact(metricId: String): Boolean = metricId == BRIDGED_METRIC_CONTROLLER_POWER

/** Streaming form (pages arrive in order). Output order equals input order; only artefact unknowns are removed. */
internal class HistoryPartialSourceBridge(private val key: HistorySeriesKey) {
    private val applies = bridgesPartialSourceArtefact(key.metricId)
    private var lastKnown: HistorySample? = null
    private val pending = ArrayList<HistorySample>()

    fun offer(sample: HistorySample): List<HistorySample> {
        if (!applies) return listOf(sample)
        val previous = lastKnown
        if (sample.value != null) {
            val out = ArrayList<HistorySample>(pending.size + 1)
            if (pending.isNotEmpty() && !(previous != null && closeEnough(previous, sample))) out += pending
            pending.clear()
            lastKnown = sample
            out += sample
            return out
        }
        if (sample.unknown == HistoryUnknownReason.PARTIAL_SOURCE && previous != null && closeEnough(previous, sample)) {
            pending += sample
            return emptyList()
        }
        val out = ArrayList<HistorySample>(pending.size + 1)
        out += pending
        pending.clear()
        lastKnown = null
        out += sample
        return out
    }

    /** What would be released at the end of the stream (trailing unknowns have no known value after them). */
    fun pendingTail(): List<HistorySample> = pending.toList()

    fun flush(): List<HistorySample> = pending.toList().also { pending.clear() }

    private fun closeEnough(a: HistorySample, b: HistorySample) =
        !clockBoundary(a.tick, b.tick) && b.tick.elapsedMillis - a.tick.elapsedMillis <= PARTIAL_SOURCE_BRIDGE_MILLIS
}

/** One-shot form over a complete single-key list. */
internal fun bridgePartialSourceArtefact(samples: List<HistorySample>): List<HistorySample> {
    val key = samples.firstOrNull()?.key ?: return samples
    if (!bridgesPartialSourceArtefact(key.metricId)) return samples
    val bridge = HistoryPartialSourceBridge(key)
    return buildList {
        samples.forEach { addAll(bridge.offer(it)) }
        addAll(bridge.flush())
    }
}
