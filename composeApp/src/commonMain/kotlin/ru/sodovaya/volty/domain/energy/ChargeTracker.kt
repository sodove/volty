package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.model.Chemistry
import kotlin.math.abs

/** One reading for [ChargeTracker]. [dischargeA] is battery current, discharge-positive, null = not measured. */
data class ChargeInput(
    val atMillis: Long,
    val packV: Float,
    val cellCount: Int,
    val dischargeA: Float?,
    val resistanceOhm: Float?,
)

/** [usableAh] is the capacity between [lowV] and [highV]; null = no capacity known. */
data class ChargeConfig(
    val chemistry: Chemistry,
    val lowV: Float,
    val highV: Float,
    val usableAh: Float?,
)

/**
 * A previously shown charge. [atMillis] is informational: the tracker's clock is only
 * [ChargeInput.atMillis], so the two are not comparable and it is never used in arithmetic.
 */
data class PersistedCharge(val percent: Float, val atMillis: Long)

/**
 * A state of charge that moves only as charge leaves the pack.
 *
 * The internal state is a counter in percent: each step subtracts the integrated battery current,
 * and a slow correction pulls it toward the SoC of the load-compensated voltage (fast at rest, where
 * the terminal voltage is honest). The displayed value follows the counter but is rate-limited, so
 * nothing the voltage does in a second can move the readout by more than a fraction of a point.
 * This replaces the linear live-voltage map that showed 60 % -> 15 % under a hard pull (T4, 2026-10-24).
 *
 * In fallback (no capacity, or current not measured) the counter is the low-passed
 * compensated-voltage SoC instead, so switching between the two modes is seamless.
 *
 * Pure: time comes only from [ChargeInput.atMillis]; no coroutines, no I/O.
 */
class ChargeTracker(
    config: ChargeConfig,
    private val persisted: PersistedCharge? = null,
) {
    /**
     * The settings in force. Replaceable while running so a capacity learned mid-ride (or typed by the
     * rider) takes effect without discarding the counter; the next step simply divides by the new value.
     */
    var config: ChargeConfig = config

    /** Counter in percent (in fallback, the low-passed voltage SoC); null until seeded. */
    var counterPercent: Float? = null
        private set

    /** Ah drawn since construction, discharge-positive (regen subtracts). */
    var integratedAh: Double = 0.0
        private set

    /** Total time in steps that were skipped (dt >= [GAP_SKIP_MS]). */
    var gapMillis: Long = 0L
        private set

    /** Compensated-voltage SoC while the rest condition holds, else null: the capacity learner's endpoints. */
    var restVoltageSocPercent: Float? = null
        private set

    private var displayed: Float? = null
    private var lastAt: Long? = null
    private var restSince: Long? = null
    private var firstSeed = true

    private class SeedSample(val at: Long, val compPackV: Float, val soc: Float)

    private val seedWindow = ArrayList<SeedSample>()

    /** Feeds one reading; returns the displayed SoC percent, or null until seeded. */
    fun update(input: ChargeInput): Float? {
        val prev = lastAt
        if (prev != null && input.atMillis <= prev) return displayed // time went backwards or stood still
        val dt = if (prev == null) 0L else input.atMillis - prev
        lastAt = input.atMillis
        if (input.cellCount <= 0) return displayed

        val current = input.dischargeA
        val comp = if (current != null) current * (input.resistanceOhm ?: 0f) else 0f
        val compPackV = input.packV + comp
        val cellV = compPackV / input.cellCount
        val vSoc = OcvCurve.stretchedSocPercent(config.chemistry, cellV, config.lowV, config.highV)

        if (dt > RESEED_GAP_MS) {
            gapMillis += dt
            counterPercent = null
            displayed = null
            restSince = null
            restVoltageSocPercent = null
            seedWindow.clear()
        } else if (dt >= GAP_SKIP_MS) {
            gapMillis += dt
            restSince = null
            restVoltageSocPercent = null
            if (counterPercent != null) return displayed // not integrated, not corrected
        }

        if (counterPercent == null) {
            trySeed(input.atMillis, compPackV, vSoc)
            trackRest(input.atMillis, current)
            return displayed
        }

        val dtS = dt / 1000f
        val rest = trackRest(input.atMillis, current)
        restVoltageSocPercent = if (rest) vSoc else null

        if (current != null) integratedAh += current.toDouble() * dt / 3_600_000.0

        var counter = counterPercent!!
        val usable = config.usableAh
        if (usable != null && usable > 0f && current != null) {
            counter -= current * (dt / 3_600_000f) / usable * 100f
            counter = counter.coerceIn(0f, 100f)
            val motionTau = MOTION_TAU_S * (if (config.chemistry == Chemistry.LIFEPO4) 3f else 1f)
            val tau = if (rest) REST_TAU_S else motionTau
            counter += (vSoc - counter) * (dtS / tau).coerceAtMost(1f)
        } else {
            counter += (vSoc - counter) * (dtS / NO_COUNTER_TAU_S).coerceAtMost(1f)
        }
        counter = counter.coerceIn(0f, 100f)

        var shown = displayed ?: counter
        if (rest && cellV >= config.highV - FULL_SNAP_MARGIN_V) {
            shown = 100f
            counter = 100f
        } else {
            val delta = counter - shown
            shown += delta.coerceIn(-MAX_DOWN_PCT_PER_S * dtS, MAX_UP_PCT_PER_S * dtS)
        }
        counterPercent = counter
        displayed = shown.coerceIn(0f, 100f)
        return displayed
    }

    /** Maintains the rest timer; true once |I| < [REST_A] has held for [REST_MS]. Unmeasured current is never rest. */
    private fun trackRest(at: Long, current: Float?): Boolean {
        if (current == null || abs(current) >= REST_A) {
            restSince = null
            return false
        }
        val since = restSince ?: at.also { restSince = it }
        return at - since >= REST_MS
    }

    private fun trySeed(at: Long, compPackV: Float, soc: Float) {
        seedWindow.add(SeedSample(at, compPackV, soc))
        while (seedWindow.size > 1 && spread() > SEED_SPREAD) seedWindow.removeAt(0)
        if (seedWindow.size < SEED_MIN_SAMPLES) return
        if (seedWindow.last().at - seedWindow.first().at < SEED_STABLE_MS) return
        val seed = seedWindow.last().soc
        val p = if (firstSeed) persisted else null // a reseed after a long gap never trusts an old persisted value
        val start = if (p != null && abs(p.percent - seed) <= SEED_TOLERANCE_PCT) p.percent else seed
        firstSeed = false
        counterPercent = start.coerceIn(0f, 100f)
        displayed = counterPercent
        seedWindow.clear()
    }

    private fun spread(): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in seedWindow) {
            if (s.compPackV < lo) lo = s.compPackV
            if (s.compPackV > hi) hi = s.compPackV
        }
        return if (hi <= 0f) 0f else (hi - lo) / hi
    }

    companion object {
        /** A step this long after the previous one is not integrated: the link dropped and the current in between is unknown (head-unit rule). */
        const val GAP_SKIP_MS = 2_000L

        /** A silence this long may hide a ride or a charge: forget the counter and seed again from voltage. */
        const val RESEED_GAP_MS = 30_000L

        /** Voltage must hold still this long before it is believed as a seed (not an unearned first frame). */
        const val SEED_STABLE_MS = 3_000L

        /** Fewer samples than this cannot show stability. */
        const val SEED_MIN_SAMPLES = 3

        /** Max (high-low)/high of compensated pack voltage across the seed window. */
        const val SEED_SPREAD = 0.01f

        /** A persisted charge this close to the voltage seed is trusted over it (it carries what voltage cannot). */
        const val SEED_TOLERANCE_PCT = 10f

        /** Below this current the pack counts as resting. */
        const val REST_A = 1.5f

        /** Current must stay small this long before the terminal voltage is honest. */
        const val REST_MS = 5_000L

        /** In motion the voltage is sag-polluted, so it nudges the counter only over many minutes. */
        const val MOTION_TAU_S = 1_200f

        /** At rest the voltage is true, so the counter converges within a few minutes. */
        const val REST_TAU_S = 60f

        /** Voltage-only mode needs enough smoothing to ignore a pull but still track a real change. */
        const val NO_COUNTER_TAU_S = 20f

        /** A fast fall would repeat the sag collapse the counter exists to prevent. */
        const val MAX_DOWN_PCT_PER_S = 0.5f

        /** Upward moves come only from regen or rest correction, never fast. */
        const val MAX_UP_PCT_PER_S = 0.2f

        /** At rest within this of the full bound the pack is full; show 100 rather than creep. */
        const val FULL_SNAP_MARGIN_V = 0.01f
    }
}
