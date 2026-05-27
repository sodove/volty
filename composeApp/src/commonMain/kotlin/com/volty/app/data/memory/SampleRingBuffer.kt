package com.volty.app.data.memory

import com.volty.app.domain.model.BmsData
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Time-windowed FIFO buffer of [BmsData] samples. Thread-safe via internal monitor.
 *
 * Capacity is **time-based** rather than sample-count-based so the buffer adapts
 * to varying BMS poll rates — ANT polls at 2 Hz, JK streams at ~1 Hz, etc.
 * Samples older than [maxAge] are evicted on each push.
 *
 * A small [hardCap] guards against unbounded memory growth in case the timestamp
 * stream goes wrong.
 */
@OptIn(ExperimentalTime::class)
class SampleRingBuffer(
    private val maxAge: Duration = 4.hours,
    private val hardCap: Int = 60_000
) {

    private val lock = Any()
    private val items = ArrayDeque<BmsData>()

    fun push(sample: BmsData) = synchronized(lock) {
        val cutoff = sample.timestamp - maxAge
        while (items.isNotEmpty() && items.first().timestamp < cutoff) {
            items.removeFirst()
        }
        if (items.size >= hardCap) items.removeFirst()
        items.addLast(sample)
    }

    fun within(window: Duration, now: Instant = Clock.System.now()): List<BmsData> = synchronized(lock) {
        val cutoff = now - window
        items.filter { it.timestamp >= cutoff }
    }

    /** All retained samples (within [maxAge]) — used by the "ALL" graph window. */
    fun all(): List<BmsData> = synchronized(lock) { items.toList() }

    fun size(): Int = synchronized(lock) { items.size }

    fun clear() = synchronized(lock) { items.clear() }
}
