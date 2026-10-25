package ru.sodovaya.volty.domain.alert

import kotlin.math.abs

/**
 * Confirms a temperature reading before anything is allowed to act on it.
 *
 * **Why it exists (field report, 2026-10-25).** A friend's Begode raised
 * "controller 130 °C" once, walking away from the wheel at the edge of BLE
 * range. Begode frames carry no checksum (a `55 AA` header and a `5A5A5A5A`
 * tail, nothing in between), so a corrupted frame decodes into a perfectly
 * plausible-looking number. An earlier revision of both alert paths fired on the
 * *first* sample over a threshold; that was wrong for a link that cannot tell a
 * damaged frame from a good one, and this gate replaces it (rider-approved
 * decision 1).
 *
 * **Contract.** [accept] takes the latest raw reading and returns the value the
 * caller should evaluate, or `null` meaning "not confirmed: hold your previous
 * decision" — neither trigger nor recover. A value is confirmed once it is the
 * [CONFIRMATIONS]th consecutive sample that agrees with its predecessors:
 *
 *  - a sample within [MAX_JUMP_C] of the last accepted one is *accepted*;
 *  - a sample further away is a *jump*: discarded, it neither confirms anything
 *    nor resets the accepted run, and the next sample is compared with the last
 *    accepted one again — which is what lets a single corrupt frame between two
 *    good ones vanish without a trace;
 *  - [CONFIRMATIONS] consecutive jumps that agree within [MAX_JUMP_C] of each
 *    other are accepted together as real, so a genuine fast rise is delayed by
 *    three samples and never locked out;
 *  - a gate that has accepted nothing yet (fresh, or after [reset]) treats
 *    every sample as a jump, so a single frame cannot be accepted on its own
 *    either.
 *
 * **Triggering and releasing use different edges of the window** (fix round 1:
 * "latest confirmed value" let a sub-20 C glitch that crossed a threshold fire
 * alone). See [Confirmed]: engage on [Confirmed.min], release on
 * [Confirmed.max], so three consecutive samples are needed either way and one
 * stray sample can neither raise an alert nor re-arm one.
 *
 * A non-finite reading is rejected outright and touches no state.
 *
 * Deterministic and clock-free, like [AlarmController]: the same sequence from a
 * fresh gate always gives the same answers. Not thread-safe; one gate per
 * (vehicle, kind) per consumer, driven from one collector.
 */
class TemperatureGate {

    private var lastAccepted: Float? = null

    /** The last [CONFIRMATIONS] accepted samples, oldest first. */
    private val window = ArrayList<Float>(CONFIRMATIONS)

    /** The current run of mutually agreeing jumps, oldest first. */
    private val pending = ArrayList<Float>(CONFIRMATIONS)

    /**
     * What a confirmed reading looks like: the last [CONFIRMATIONS] accepted
     * samples. [min] is what every one of them is at least (use it to *engage*:
     * all three must be over a level) and [max] what none exceeds (use it to
     * *release*: all three must be under). [latest] is the newest, for display.
     */
    class Confirmed(val min: Float, val max: Float, val latest: Float)

    /** [confirm]'s latest value only; kept for callers that need nothing more. */
    fun accept(value: Float): Float? = confirm(value)?.latest

    fun confirm(value: Float): Confirmed? {
        if (!value.isFinite()) return null
        val last = lastAccepted
        if (last != null && abs(value - last) <= MAX_JUMP_C) {
            pending.clear()
            lastAccepted = value
            window.add(value)
            if (window.size > CONFIRMATIONS) window.removeAt(0)
            return confirmedOrNull()
        }
        // A jump (or the very first samples): it must be corroborated by its own
        // successors, not by the accepted history.
        if (pending.isNotEmpty() && (maxOf(pending.max(), value) - minOf(pending.min(), value)) > MAX_JUMP_C) {
            pending.clear()
        }
        pending.add(value)
        if (pending.size < CONFIRMATIONS) return null
        window.clear()
        window.addAll(pending)
        pending.clear()
        lastAccepted = value
        return confirmedOrNull()
    }

    private fun confirmedOrNull(): Confirmed? =
        if (window.size < CONFIRMATIONS) null else Confirmed(window.min(), window.max(), window.last())

    /** Forget everything; the next samples must confirm themselves from scratch. */
    fun reset() {
        lastAccepted = null
        window.clear()
        pending.clear()
    }

    companion object {
        /** Consecutive agreeing samples needed before a reading is believed. */
        const val CONFIRMATIONS = 3

        /** The largest step between neighbouring samples that is still believable, in °C. */
        const val MAX_JUMP_C = 20f
    }
}
