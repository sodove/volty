package ru.sodovaya.volty.domain.plausibility

/** What a [CounterStepGate] decided about one offered point. */
sealed interface CounterDecision<P> {
    val point: P

    /** [point] is real progress; [incrementM] metres are added to the sum (0 for the first point and a standing counter). */
    data class Accepted<P>(override val point: P, val incrementM: Double) : CounterDecision<P>

    /** [point] is a needle (or a chain that never confirmed). It adds nothing; [reference] is the last accepted point it was judged against. */
    data class Rejected<P>(override val point: P, val reference: P) : CounterDecision<P>

    /** A confirmed new baseline at [point] (the chain's last point). The [jumpM] step added 0; [creditedM] is the chain's internal progress. */
    data class Rebased<P>(override val point: P, val jumpM: Double, val creditedM: Double) : CounterDecision<P>
}

/**
 * One rule for every counter (ride distance, a lifetime odometer): is a step progress, a needle, or a reset?
 * It carries no speed limit and no metre limit (rider ruling 2026-10-27): only a witness that [agrees] and
 * temporal confirmation.
 *
 * Let A be the last accepted point.
 *  - The first point becomes A with increment 0.
 *  - A point x that [agrees] with A is accepted at once if [immediate] holds (a witness is present), otherwise held
 *    provisionally and decided by the next point y: if x agrees with y, x is accepted; else if y agrees with A,
 *    x is a needle and is rejected; else x is accepted and y starts a chain.
 *  - A point that does not agree with A starts or extends a pending chain. A later point agreeing with A discards
 *    the chain (all rejected). [confirmations] points, each agreeing with its predecessor, re-baseline: the jump
 *    to the first chain point adds 0, the chain's internal increments are credited, the last point becomes A.
 *
 * Rules are decided one point late (a held point), so decisions for earlier points are returned from a later
 * [offer]; [finish] settles what is still open at the end of a finite series. A live caller never calls it.
 *
 * False-positive analysis: with a monotone predicate, `agrees = { a, b -> value(b) >= value(a) }` (a lifetime counter does not run
 * backwards except at a reset; there is no built-in default, each caller supplies it) every step of the three field dumps is monotone, so nothing fires; a test replays the dump-(3)
 * odometer. A false positive would show as a counter that stops growing on a healthy wheel. Three identical torn
 * reads in a row would re-baseline to the needle, but the jump contributes 0: only the published odometer is wrong
 * until the next re-baseline back. That is why "sum of increments" is safe where "last minus first" is not.
 *
 * Not thread-safe; owned by one collector. Call [finish] once, at the end of a finite series; do not offer after it.
 */
class CounterStepGate<P>(
    private val value: (P) -> Double,
    private val agrees: (from: P, to: P) -> Boolean,
    private val immediate: (from: P, to: P) -> Boolean = { _, _ -> false },
    private val confirmations: Int = 3,
) {
    private var anchor: P? = null
    private var held: P? = null
    private val chain = ArrayList<P>()

    /** Sum of every accepted increment and credited chain increment, in metres. */
    var acceptedSumM: Double = 0.0
        private set

    fun offer(point: P): List<CounterDecision<P>> {
        val out = ArrayList<CounterDecision<P>>(2)
        val a = anchor
        if (a == null) {
            anchor = point
            out += CounterDecision.Accepted(point, 0.0)
            return out
        }
        val x = held
        if (x != null) {
            held = null
            if (agrees(x, point)) {
                accept(x, a, out)
            } else if (agrees(a, point)) {
                out += CounterDecision.Rejected(x, a)
            } else {
                accept(x, a, out)
            }
        }
        evaluate(point, out)
        return out
    }

    fun finish(): List<CounterDecision<P>> {
        val out = ArrayList<CounterDecision<P>>(2)
        val x = held
        val a = anchor
        if (x != null && a != null) {
            held = null
            accept(x, a, out)
        }
        rejectChain(out)
        return out
    }

    private fun accept(p: P, from: P, out: MutableList<CounterDecision<P>>) {
        val inc = value(p) - value(from)
        acceptedSumM += inc
        anchor = p
        out += CounterDecision.Accepted(p, inc)
    }

    private fun rejectChain(out: MutableList<CounterDecision<P>>) {
        val a = anchor ?: return
        chain.forEach { out += CounterDecision.Rejected(it, a) }
        chain.clear()
    }

    private fun evaluate(p: P, out: MutableList<CounterDecision<P>>) {
        val a = checkNotNull(anchor)
        if (agrees(a, p)) {
            rejectChain(out)
            if (immediate(a, p)) accept(p, a, out) else held = p
            return
        }
        if (chain.isNotEmpty() && !agrees(chain.last(), p)) rejectChain(out) // chain broke; p starts a new one
        chain += p
        if (chain.size >= confirmations) {
            var credited = 0.0
            for (i in 1 until chain.size) credited += value(chain[i]) - value(chain[i - 1])
            val jump = value(chain.first()) - value(a)
            val last = chain.last()
            acceptedSumM += credited
            anchor = last
            chain.clear()
            out += CounterDecision.Rebased(last, jump, credited)
        }
    }
}
