package ru.sodovaya.volty.domain.voice

/**
 * When to dial a dropped channel again. Pure: time is handed in, nothing here sleeps.
 *
 * [failureCount] is the number of consecutive failures so far (the first failure is 1). The last delay repeats
 * until [budgetMillis] has passed since [nextDelay]'s `firstFailureAtMillis`; after that the answer is null,
 * "give up and show the failure", so a switched-off network is not redialled for ever.
 */
class VoiceRejoinPolicy(
    private val delaysMillis: List<Long> = listOf(2_000, 4_000, 8_000, 15_000, 30_000),
    private val budgetMillis: Long = 10 * 60_000,
) {
    init { require(delaysMillis.isNotEmpty() && delaysMillis.all { it > 0 } && budgetMillis > 0) }

    fun nextDelay(failureCount: Int, firstFailureAtMillis: Long, nowMillis: Long): Long? {
        if (nowMillis - firstFailureAtMillis >= budgetMillis) return null
        return delaysMillis[(failureCount - 1).coerceIn(0, delaysMillis.lastIndex)]
    }

    /** Stateless: the caller owns the count. Kept so a successful connection has one named place to say "start over". */
    fun reset() = Unit
}
