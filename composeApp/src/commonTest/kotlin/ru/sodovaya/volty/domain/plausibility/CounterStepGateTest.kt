package ru.sodovaya.volty.domain.plausibility

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CounterStepGateTest {
    private data class Pt(val t: Int, val m: Double, val w: Double = 0.0)

    private fun monotone() = CounterStepGate<Pt>(value = { it.m }, agrees = { a, b -> b.m >= a.m })

    private fun witnessGate() = CounterStepGate<Pt>(
        value = { it.m },
        agrees = { a, b -> abs((b.m - a.m) - (b.w - a.w)) <= 40.0 },
    )

    private fun run(gate: CounterStepGate<Pt>, values: List<Double>): List<CounterDecision<Pt>> {
        val out = mutableListOf<CounterDecision<Pt>>()
        values.forEachIndexed { i, v -> out += gate.offer(Pt(i, v)) }
        out += gate.finish()
        return out
    }

    private fun List<CounterDecision<Pt>>.rejectedValues() =
        filterIsInstance<CounterDecision.Rejected<Pt>>().map { it.point.m }

    @Test
    fun torn_needle_plus_65536_is_rejected_and_adds_nothing() {
        val gate = monotone()
        val d = run(gate, listOf(2816164.0, 2816171.0, 2816171.0 + 65536.0, 2816178.0, 2816185.0))
        assertEquals(listOf(2881707.0), d.rejectedValues())
        assertEquals(1, d.count { it is CounterDecision.Rejected })
        assertEquals(21.0, gate.acceptedSumM)
    }

    @Test
    fun dip_needle_minus_65536_is_rejected() {
        val gate = monotone()
        val d = run(gate, listOf(2816164.0, 2750635.0, 2816171.0))
        assertEquals(listOf(2750635.0), d.rejectedValues())
        assertEquals(7.0, gate.acceptedSumM)
    }

    @Test
    fun persistent_reset_rebases_after_three_and_adds_zero_for_the_jump() {
        val gate = monotone()
        val d = run(gate, listOf(2999990.0, 2650000.0, 2650007.0, 2650014.0, 2650021.0, 2650028.0))
        assertTrue(d.rejectedValues().isEmpty())
        val rebased = d.filterIsInstance<CounterDecision.Rebased<Pt>>().single()
        assertEquals(2650014.0, rebased.point.m)
        assertEquals(-349990.0, rebased.jumpM)
        assertEquals(14.0, rebased.creditedM)
        assertEquals(14.0 + 7.0 + 7.0, gate.acceptedSumM)
    }

    @Test
    fun sum_after_rebase_is_visible_before_finish() {
        val gate = monotone()
        val vs = listOf(2999990.0, 2650000.0, 2650007.0, 2650014.0, 2650021.0, 2650028.0)
        vs.forEachIndexed { i, v -> gate.offer(Pt(i, v)) }
        // 2650028 is still held provisionally; 2650021 has been accepted.
        assertEquals(21.0, gate.acceptedSumM)
    }

    @Test
    fun persistent_forward_jump_with_witness_rebases_and_credits_only_internal_steps() {
        val gate = witnessGate()
        val out = mutableListOf<CounterDecision<Pt>>()
        out += gate.offer(Pt(0, 2999990.0, 100.0))
        out += gate.offer(Pt(1, 3350000.0, 107.0))
        out += gate.offer(Pt(2, 3350007.0, 114.0))
        out += gate.offer(Pt(3, 3350014.0, 121.0))
        out += gate.finish()
        val rebased = out.filterIsInstance<CounterDecision.Rebased<Pt>>().single()
        assertEquals(350010.0, rebased.jumpM)
        assertEquals(14.0, rebased.creditedM)
        assertEquals(14.0, gate.acceptedSumM)
        assertTrue(out.none { it is CounterDecision.Rejected })
    }

    @Test
    fun chain_broken_at_length_two_rejects_both_points() {
        val gate = monotone()
        val d = run(gate, listOf(2999990.0, 2650000.0, 2650007.0, 3000000.0, 3000007.0))
        assertEquals(listOf(2650000.0, 2650007.0), d.rejectedValues())
        assertTrue(d.none { it is CounterDecision.Rebased })
        assertEquals(10.0 + 7.0, gate.acceptedSumM)
    }

    @Test
    fun standing_wheel_is_accepted_with_zero_increment() {
        val gate = monotone()
        val d = run(gate, listOf(5.0, 5.0, 5.0, 5.0))
        assertEquals(4, d.size)
        assertTrue(d.all { it is CounterDecision.Accepted && it.incrementM == 0.0 })
        assertEquals(0.0, gate.acceptedSumM)
    }

    @Test
    fun immediate_witness_accepts_without_lookahead() {
        val gate = CounterStepGate<Pt>(
            value = { it.m }, agrees = { a, b -> b.m >= a.m }, immediate = { _, _ -> true },
        )
        gate.offer(Pt(0, 10.0))
        val d = gate.offer(Pt(1, 17.0))
        assertEquals(listOf(CounterDecision.Accepted(Pt(1, 17.0), 7.0)), d)
    }

    @Test
    fun finish_accepts_a_held_point() {
        val gate = monotone()
        gate.offer(Pt(0, 100.0))
        assertTrue(gate.offer(Pt(1, 107.0)).isEmpty())
        val d = gate.finish()
        assertEquals(listOf(CounterDecision.Accepted(Pt(1, 107.0), 7.0)), d)
        assertEquals(7.0, gate.acceptedSumM)
    }

    @Test
    fun finish_rejects_a_chain_of_two() {
        val gate = monotone()
        gate.offer(Pt(0, 3000.0))
        gate.offer(Pt(1, 1000.0))
        gate.offer(Pt(2, 1007.0))
        val d = gate.finish()
        assertEquals(listOf(1000.0, 1007.0), d.rejectedValues())
        assertEquals(0.0, gate.acceptedSumM)
    }

    /** Every step of the real dump-(3) odometer is monotone, so the gate never fires on it. */
    @Test
    fun dump3_odometer_series_has_no_rejection_and_sums_to_174() {
        val gate = monotone()
        val d = run(gate, DUMP3_ODOMETER_M)
        assertEquals(DUMP3_ODOMETER_M.size, d.size)
        assertTrue(d.all { it is CounterDecision.Accepted })
        assertEquals(174.0, gate.acceptedSumM)
    }

    private fun decisions(gate: CounterStepGate<Pt>, values: List<Double>) = run(gate, values)

    @Test
    fun provisional_third_outcome_accepts_x_and_y_starts_a_chain() {
        // 107 agrees with A=100; 50 disagrees with both 107 and 100: 107 is accepted, 50 starts a chain.
        val gate = monotone()
        val d = decisions(gate, listOf(100.0, 107.0, 50.0, 57.0, 64.0))
        assertEquals(
            listOf(
                CounterDecision.Accepted(Pt(0, 100.0), 0.0),
                CounterDecision.Accepted(Pt(1, 107.0), 7.0),
                CounterDecision.Rebased(Pt(4, 64.0), -57.0, 14.0),
            ),
            d,
        )
        assertEquals(21.0, gate.acceptedSumM)
    }

    @Test
    fun chain_discarded_by_a_point_agreeing_with_anchor_which_is_then_held_and_accepted() {
        val gate = monotone()
        val d = decisions(gate, listOf(5000.0, 100.0, 101.0, 5007.0, 5014.0))
        assertEquals(
            listOf(
                CounterDecision.Accepted(Pt(0, 5000.0), 0.0),
                CounterDecision.Rejected(Pt(1, 100.0), Pt(0, 5000.0)),
                CounterDecision.Rejected(Pt(2, 101.0), Pt(0, 5000.0)),
                CounterDecision.Accepted(Pt(3, 5007.0), 7.0),
                CounterDecision.Accepted(Pt(4, 5014.0), 7.0),
            ),
            d,
        )
        assertEquals(14.0, gate.acceptedSumM)
    }

    @Test
    fun needle_right_after_a_rebase_is_judged_against_the_new_baseline() {
        val gate = monotone()
        val d = decisions(
            gate,
            listOf(2999990.0, 2650000.0, 2650007.0, 2650014.0, 2715550.0, 2650021.0, 2650028.0),
        )
        assertEquals(listOf(2715550.0), d.rejectedValues())
        assertEquals(1, d.count { it is CounterDecision.Rejected })
        assertEquals(1, d.count { it is CounterDecision.Rebased })
        assertEquals(14.0 + 7.0 + 7.0, gate.acceptedSumM)
    }

    @Test
    fun immediate_does_not_bypass_chain_discard_and_keeps_order() {
        val gate = CounterStepGate<Pt>(
            value = { it.m }, agrees = { a, b -> b.m >= a.m }, immediate = { _, _ -> true },
        )
        val d = decisions(gate, listOf(5000.0, 100.0, 101.0, 5007.0))
        assertEquals(
            listOf(
                CounterDecision.Accepted(Pt(0, 5000.0), 0.0),
                CounterDecision.Rejected(Pt(1, 100.0), Pt(0, 5000.0)),
                CounterDecision.Rejected(Pt(2, 101.0), Pt(0, 5000.0)),
                CounterDecision.Accepted(Pt(3, 5007.0), 7.0),
            ),
            d,
        )
    }

    @Test
    fun immediate_with_a_disagreeing_point_still_starts_a_chain_and_rebases() {
        val gate = CounterStepGate<Pt>(
            value = { it.m }, agrees = { a, b -> b.m >= a.m }, immediate = { _, _ -> true },
        )
        val d = decisions(gate, listOf(5000.0, 100.0, 107.0, 114.0))
        assertEquals(CounterDecision.Rebased(Pt(3, 114.0), -4900.0, 14.0), d.last())
        assertTrue(d.none { it is CounterDecision.Rejected })
        assertEquals(14.0, gate.acceptedSumM)
    }

    @Test
    fun witness_rebase_then_following_steps_are_credited_against_the_new_anchor() {
        val gate = witnessGate()
        val out = mutableListOf<CounterDecision<Pt>>()
        listOf(
            Pt(0, 2999990.0, 100.0), Pt(1, 3350000.0, 107.0), Pt(2, 3350007.0, 114.0),
            Pt(3, 3350014.0, 121.0), Pt(4, 3350021.0, 128.0), Pt(5, 3350028.0, 135.0),
        ).forEach { out += gate.offer(it) }
        out += gate.finish()
        assertEquals(1, out.count { it is CounterDecision.Rebased })
        assertTrue(out.none { it is CounterDecision.Rejected })
        assertEquals(28.0, gate.acceptedSumM)
    }

    private companion object {
        // Type-0x04 frames (u32 BE, bytes 2..5), volty-ble-dump (3).txt, in order; metres.
        val DUMP3_ODOMETER_M: List<Double> = listOf(
        2816071.0, 2816071.0, 2816071.0, 2816071.0, 2816071.0, 2816071.0, 2816071.0, 2816072.0,
        2816072.0, 2816073.0, 2816073.0, 2816074.0, 2816074.0, 2816074.0, 2816074.0, 2816075.0,
        2816075.0, 2816075.0, 2816076.0, 2816076.0, 2816076.0, 2816077.0, 2816077.0, 2816077.0,
        2816077.0, 2816077.0, 2816077.0, 2816077.0, 2816077.0, 2816077.0, 2816077.0, 2816077.0,
        2816077.0, 2816078.0, 2816078.0, 2816078.0, 2816078.0, 2816078.0, 2816078.0, 2816078.0,
        2816079.0, 2816079.0, 2816079.0, 2816079.0, 2816079.0, 2816079.0, 2816079.0, 2816079.0,
        2816079.0, 2816079.0, 2816079.0, 2816079.0, 2816079.0, 2816079.0, 2816080.0, 2816081.0,
        2816083.0, 2816085.0, 2816087.0, 2816090.0, 2816093.0, 2816096.0, 2816099.0, 2816103.0,
        2816106.0, 2816111.0, 2816115.0, 2816119.0, 2816123.0, 2816128.0, 2816133.0, 2816139.0,
        2816145.0, 2816151.0, 2816157.0, 2816164.0, 2816171.0, 2816178.0, 2816185.0, 2816192.0,
        2816199.0, 2816206.0, 2816212.0, 2816219.0, 2816226.0, 2816233.0, 2816239.0, 2816243.0,
        2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0,
        2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0,
        2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0,
        2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0, 2816245.0,
        )
    }
}
