package ru.sodovaya.volty.domain.location

/** Keeps an Android last-known fix's age visible to consumers of the repository. */
object LocationTimestampPolicy {
    fun capturedAtForLastKnown(lastKnownEpochMillis: Long, nowEpochMillis: Long): Long =
        lastKnownEpochMillis.coerceAtMost(nowEpochMillis)
}
