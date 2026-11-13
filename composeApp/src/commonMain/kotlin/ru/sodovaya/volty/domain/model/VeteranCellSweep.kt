package ru.sodovaya.volty.domain.model

/** A Leaperkim BMS sends its cells in increasing pages of up to 15 values. */
internal class VeteranCellSweep {
    private var pending: Pending? = null
    private var reported = false

    data class Page(val first: Int, val count: Int, val minimum: Double?, val maximum: Double?)
    data class Summary(val minimum: Double, val maximum: Double) {
        val deltaMv: Double get() = (maximum - minimum) * 1000
    }

    sealed interface Result {
        data object Partial : Result
        data class Complete(val summary: Summary) : Result
    }

    private data class Pending(val expected: Int, val next: Int, val receivedAt: Long, val summary: Summary)

    fun offer(page: Page, expected: Int?, elapsedMillis: Long): Result? {
        val previous = pending
        pending = null
        val minimum = page.minimum
        val maximum = page.maximum
        if (expected == null || expected !in 1..42 || page.first !in 0 until expected ||
            page.count != minOf(15, expected - page.first) || minimum == null || maximum == null) {
            return Result.Partial
        }
        val next = page.first + page.count
        val sweep = if (page.first == 0) {
            Pending(expected, next, elapsedMillis, Summary(minimum, maximum))
        } else {
            if (previous == null || previous.expected != expected || previous.next != page.first ||
                elapsedMillis - previous.receivedAt !in 0..MAX_PAGE_GAP_MILLIS) return Result.Partial
            previous.copy(next = next, receivedAt = elapsedMillis, summary = Summary(
                minOf(minimum, previous.summary.minimum), maxOf(maximum, previous.summary.maximum)))
        }
        if (next == expected) {
            reported = true
            return Result.Complete(sweep.summary)
        }
        pending = sweep
        return Result.Partial.takeIf { !reported || (page.first == 0 && previous != null) }
    }

    fun reset() {
        pending = null
        reported = false
    }

    fun startCycle(): Boolean = (pending != null).also { pending = null }

    private companion object {
        // Patton S pages in the field were 50–450 ms apart, with over a second between sweeps.
        private const val MAX_PAGE_GAP_MILLIS = 750L
    }
}
