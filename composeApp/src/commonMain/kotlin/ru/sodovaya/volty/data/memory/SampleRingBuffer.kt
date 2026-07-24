package ru.sodovaya.volty.data.memory

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Time-windowed FIFO buffer of samples of any type [T]. Thread-safe via
 * internal monitor.
 *
 * Capacity is **time-based** rather than sample-count-based so the buffer adapts
 * to varying poll rates — ANT polls at 2 Hz, JK streams at ~1 Hz, etc.
 * Samples older than [maxAge] are evicted on each push. The sample's own
 * timestamp is read through [timestampOf], so the same buffer holds battery
 * samples ([ru.sodovaya.volty.domain.model.BmsData]) or motion samples
 * ([ru.sodovaya.volty.domain.model.ControllerData]) without knowing their shape.
 *
 * A small [hardCap] guards against unbounded memory growth in case the timestamp
 * stream goes wrong.
 */
@OptIn(ExperimentalTime::class)
class SampleRingBuffer<T>(
    private val maxAge: Duration = 4.hours,
    private val hardCap: Int = 60_000,
    private val timestampOf: (T) -> Instant
) {

    private val lock = Any()
    private val items = ArrayDeque<T>()

    fun push(sample: T) = synchronized(lock) {
        val cutoff = timestampOf(sample) - maxAge
        while (items.isNotEmpty() && timestampOf(items.first()) < cutoff) {
            items.removeFirst()
        }
        if (items.size >= hardCap) items.removeFirst()
        items.addLast(sample)
    }

    fun within(window: Duration, now: Instant = Clock.System.now()): List<T> = synchronized(lock) {
        val cutoff = now - window
        items.filter { timestampOf(it) >= cutoff }
    }

    /** All retained samples (within [maxAge]) — used by the "ALL" graph window. */
    fun all(): List<T> = synchronized(lock) { items.toList() }

    fun size(): Int = synchronized(lock) { items.size }

    fun clear() = synchronized(lock) { items.clear() }
}
