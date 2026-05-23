package com.volty.app.data.memory

import com.volty.app.domain.model.BmsData
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Time-windowed FIFO buffer of [BmsData] samples. Thread-safe (synchronized).
 *
 * @param capacity max samples retained; oldest is evicted when full.
 */
@OptIn(ExperimentalTime::class)
class SampleRingBuffer(private val capacity: Int = 1800) {

    private val items = ArrayDeque<BmsData>(capacity)

    @Synchronized
    fun push(sample: BmsData) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(sample)
    }

    @Synchronized
    fun within(window: Duration, now: Instant = Clock.System.now()): List<BmsData> {
        val cutoff = now - window
        return items.filter { it.timestamp >= cutoff }
    }

    @Synchronized
    fun clear() { items.clear() }
}
