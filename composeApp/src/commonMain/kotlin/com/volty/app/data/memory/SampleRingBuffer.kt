package com.volty.app.data.memory

import com.volty.app.domain.model.BmsData
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Time-windowed FIFO buffer of [BmsData] samples. Thread-safe via internal monitor.
 *
 * @param capacity max samples retained; oldest is evicted when full.
 */
@OptIn(ExperimentalTime::class)
class SampleRingBuffer(private val capacity: Int = 1800) {

    private val lock = Any()
    private val items = ArrayDeque<BmsData>(capacity)

    fun push(sample: BmsData) = synchronized(lock) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(sample)
    }

    fun within(window: Duration, now: Instant = Clock.System.now()): List<BmsData> = synchronized(lock) {
        val cutoff = now - window
        items.filter { it.timestamp >= cutoff }
    }

    fun clear() = synchronized(lock) { items.clear() }
}
