package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.HistoryBatch

enum class HistoryOffer { ACCEPTED, RETIRED, OVERFLOW }
interface HistoryIngress { fun offer(generation: Long, batch: HistoryBatch): HistoryOffer }

/** Conservative retained-memory estimate; oversized packets are rejected before payload copying. */
internal fun HistoryBatch.retainedBytes(): Long {
    var bytes = 128L + rideId.length.toLong() * 4
    samples.forEach { bytes += 256L + it.key.sourceId.length.toLong() * 4 + it.key.metricId.length.toLong() * 4 }
    bytes += gps.size.toLong() * 192 + markers.size.toLong() * 128
    return bytes
}
internal val HistoryBatch.eventCount: Long get() = samples.size.toLong() + gps.size + markers.size
