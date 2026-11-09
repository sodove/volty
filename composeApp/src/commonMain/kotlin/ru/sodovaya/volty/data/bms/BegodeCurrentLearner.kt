package ru.sodovaya.volty.data.bms

import kotlin.math.abs
import kotlin.math.sqrt

internal data class BegodeCurrentEvidence(
    val sign: Int? = null,
    val offsetA: Float? = null,
    val bmsSign: Int? = null,
)

/**
 * Session-only evidence for a Begode link. Raw controller and raw BMS signs can differ by
 * firmware. Sag and recovery can anchor the controller directly or each BMS branch on its
 * own paired voltage and current readings. Align the slower BMS windows to the raw controller
 * before transferring a confirmed direction between channels. Stable low-load windows then
 * measure an additive correction. The caller owns persistence and manual overrides.
 */
internal class BegodeCurrentLearner {
    private data class ControllerSample(
        val atMillis: Long,
        val moving: Boolean,
        val tripKm: Float,
    )

    private data class BranchSample(val atMillis: Long, val amps: Float, val volts: Float)
    private data class RailVote(val atMillis: Long, val sign: Int, val sagging: Boolean)

    private var previous: ControllerSample? = null
    private val controllerWitness = PolarityWitness(MIN_RAIL_STEP_A, MIN_RAIL_STEP_V, MAX_SAMPLE_GAP_MS)
    private val branchWitnesses = Array(2) { PolarityWitness(MIN_BRANCH_STEP_A, MIN_BRANCH_STEP_V, BMS_MAX_AGE_MS) }
    private val branches = arrayOfNulls<BranchSample>(2)
    private var smartWitness: Int? = null
    private var signedFor: Int? = null
    private var window: CurrentWindow? = null
    private val windows = ArrayDeque<WindowMean>()
    private var relativeBmsSign: Int? = null
    private var pendingOffset: Float? = null

    fun reset() {
        previous = null
        controllerWitness.reset()
        branchWitnesses.forEach { it.reset() }
        branches.fill(null)
        smartWitness = null
        signedFor = null
        resetBmsComparison()
    }

    fun onBmsSample(branch: Int, atMillis: Long, rawDischargeA: Float, packV: Float) {
        if (branch !in branches.indices || !rawDischargeA.isFinite() || !packV.isFinite() || packV <= 0f) return
        if (branches[branch]?.let { atMillis < it.atMillis } == true) return
        val sample = BranchSample(atMillis, rawDischargeA, packV)
        branches[branch] = sample
        val recentMotion = previous?.let { it.moving && atMillis - it.atMillis in 0..MAX_SAMPLE_GAP_MS } == true
        branchWitnesses[branch].observe(sample, recentMotion)
    }

    fun onControllerSample(
        atMillis: Long,
        rawA: Float,
        railV: Float,
        speedKmh: Float,
        tripKm: Float,
        effectiveSign: Int?,
        learnOffset: Boolean,
    ): BegodeCurrentEvidence {
        if (!rawA.isFinite() || !railV.isFinite() || railV <= 0f || !speedKmh.isFinite() || !tripKm.isFinite()) {
            return BegodeCurrentEvidence()
        }
        val sample = ControllerSample(atMillis, abs(speedKmh) >= MOVING_KMH, tripKm)
        val last = previous
        if (last != null) {
            if (atMillis == last.atMillis) return BegodeCurrentEvidence()
            if (atMillis < last.atMillis || tripKm + TRIP_RESET_TOLERANCE_KM < last.tripKm) reset()
            else if (atMillis - last.atMillis > MAX_SAMPLE_GAP_MS) {
                controllerWitness.clearReference()
                clearBmsWindows()
            }
        }
        previous = sample
        if (effectiveSign == null) {
            controllerWitness.observe(BranchSample(atMillis, rawA, railV), sample.moving)
        }
        if (!sample.moving) branchWitnesses.forEach { it.clearReference() }
        if (effectiveSign != null && signedFor != null && effectiveSign != signedFor) resetBmsComparison()
        val completedWindow = collectBmsWindow(atMillis, rawA)
        val automatic = if (effectiveSign == null) agreedControllerSign() else null
        val sign = effectiveSign ?: automatic
        signedFor = sign
        if (sign == null) {
            pendingOffset = null
            return BegodeCurrentEvidence()
        }

        val bmsSign = relativeBmsSign?.times(sign)
        if (!learnOffset) pendingOffset = null
        else if (completedWindow && bmsSign != null && pendingOffset == null) {
            pendingOffset = measureOffset(sign, bmsSign)
        }
        // The caller deduplicates against persisted settings. Keep evidence available
        // when its first write was coalesced with another write or failed.
        return BegodeCurrentEvidence(automatic, pendingOffset, bmsSign)
    }

    private fun agreedControllerSign(): Int? {
        val railSign = controllerWitness.sign
        val smartSign = smartWitness
        if (railSign != null && smartSign != null && railSign != smartSign) return null
        return railSign ?: smartSign
    }

    private fun collectBmsWindow(time: Long, rawA: Float): Boolean {
        val a = branches[0]
        val b = branches[1]
        if (a == null || b == null || time - a.atMillis !in 0..BMS_MAX_AGE_MS ||
            time - b.atMillis !in 0..BMS_MAX_AGE_MS ||
            abs(a.volts - b.volts) > maxOf(a.volts, b.volts) * BMS_RAIL_TOLERANCE
        ) {
            clearBmsWindows()
            return false
        }
        // Correlation alone is relative. Transfer it to the controller only when both
        // branches have independently witnessed which sign caused their own voltage sag.
        val physicalBmsSign = branchWitnesses[0].sign?.takeIf { it == branchWitnesses[1].sign }
        val active = window
        if (active == null || time - active.startMillis < BMS_WINDOW_MS) {
            val collecting = active ?: CurrentWindow(time).also { window = it }
            collecting.add(rawA, a.amps, b.amps)
            return false
        }
        window = CurrentWindow(time).also { it.add(rawA, a.amps, b.amps) }
        if (active.count < MIN_SAMPLES_IN_BMS_WINDOW) {
            windows.clear()
            return false
        }
        windows.addLast(active.mean())
        if (windows.size > MAX_BMS_WINDOWS) windows.removeFirst()
        if (relativeBmsSign == null) relativeBmsSign = compareBmsPolarities()
        if (physicalBmsSign != null && smartWitness == null) smartWitness = relativeBmsSign?.times(physicalBmsSign)
        return true
    }

    private fun compareBmsPolarities(): Int? {
        if (windows.size < MIN_BMS_WINDOWS + 1) return null
        // Compare simultaneous means and means with the controller one window earlier.
        // The BMS typically arrives about four seconds behind the controller on ET Max.
        val signs = (0..1).mapNotNull { lag ->
            val pairs = (lag until windows.size).map { index -> windows[index - lag] to windows[index] }
            val first = correlationSign(pairs) { it.firstBranch }
            val second = correlationSign(pairs) { it.secondBranch }
            first.takeIf { it == second }
        }.distinct()
        return signs.singleOrNull()
    }

    private fun correlationSign(
        pairs: List<Pair<WindowMean, WindowMean>>,
        branch: (WindowMean) -> Float,
    ): Int? {
        val xMean = pairs.map { it.first.controller }.average()
        val yMean = pairs.map { branch(it.second) }.average()
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for ((controller, bms) in pairs) {
            val x = controller.controller - xMean
            val y = branch(bms) - yMean
            xx += x * x
            yy += y * y
            xy += x * y
        }
        if (xx / pairs.size < MIN_CONTROLLER_VARIANCE || yy / pairs.size < MIN_BRANCH_VARIANCE) return null
        val correlation = xy / sqrt(xx * yy)
        return when {
            correlation >= MIN_BMS_CORRELATION -> 1
            correlation <= -MIN_BMS_CORRELATION -> -1
            else -> null
        }
    }

    private fun measureOffset(controllerSign: Int, bmsSign: Int): Float? {
        val quiet = windows.takeLast(OFFSET_WINDOWS)
        // Three quiet windows outlast the BMS lag. The measured branch sum may be nonzero;
        // using it preserves real auxiliary draw and avoids equating a stopped wheel with 0 A.
        if (quiet.size < OFFSET_WINDOWS || quiet.any { !it.isQuiet() }) return null
        val offsets = quiet.map { it.bmsTotal * bmsSign - it.controller * controllerSign }
        if (offsets.max() - offsets.min() > MAX_OFFSET_SPREAD_A) return null
        val offset = offsets.average().toFloat()
        return offset.takeIf { abs(it) <= MAX_OFFSET_A }
    }

    private fun resetBmsComparison() {
        relativeBmsSign = null
        pendingOffset = null
        clearBmsWindows()
    }

    private fun clearBmsWindows() {
        window = null
        windows.clear()
    }

    private class PolarityWitness(
        private val minStepA: Float,
        private val minStepV: Float,
        private val maxPairAgeMs: Long,
    ) {
        private var reference: BranchSample? = null
        private val votes = ArrayDeque<RailVote>()
        var sign: Int? = null
            private set

        fun reset() {
            reference = null
            votes.clear()
            sign = null
        }

        fun clearReference() {
            reference = null
        }

        fun observe(sample: BranchSample, moving: Boolean) {
            if (sign != null) return
            if (!moving) {
                clearReference()
                return
            }
            val before = reference
            if (before == null) {
                reference = sample
                return
            }
            val age = sample.atMillis - before.atMillis
            if (age < RAIL_PAIR_MS) return
            // Pairs share at most one endpoint. A single spike and its recovery yield at most two votes.
            reference = sample
            val deltaA = sample.amps - before.amps
            val deltaV = sample.volts - before.volts
            if (age > maxPairAgeMs || abs(deltaA) < minStepA || abs(deltaV) < minStepV) return

            val vote = if (deltaA * deltaV < 0f) 1 else -1
            votes.addLast(RailVote(sample.atMillis, vote, deltaV < 0f))
            while (votes.size > MAX_RAIL_VOTES || sample.atMillis - votes.first().atMillis > RAIL_VOTE_LIFETIME_MS) {
                votes.removeFirst()
            }
            val agreeing = votes.filter { it.sign == vote }
            if (agreeing.size >= MIN_RAIL_VOTES && agreeing.size * 4 >= votes.size * 3 &&
                agreeing.any { it.sagging } && agreeing.any { !it.sagging }
            ) sign = vote
        }
    }

    private class CurrentWindow(val startMillis: Long) {
        private val controller = RunningCurrent()
        private val firstBranch = RunningCurrent()
        private val secondBranch = RunningCurrent()

        val count: Int get() = controller.count

        fun add(current: Float, first: Float, second: Float) {
            controller.add(current)
            firstBranch.add(first)
            secondBranch.add(second)
        }

        fun mean(): WindowMean = WindowMean(
            controller.mean, firstBranch.mean, secondBranch.mean,
            controller.spread, maxOf(firstBranch.spread, secondBranch.spread),
        )
    }

    private class RunningCurrent {
        private var sum = 0f
        private var min = Float.POSITIVE_INFINITY
        private var max = Float.NEGATIVE_INFINITY
        var count = 0
            private set

        val mean: Float get() = sum / count
        val spread: Float get() = max - min

        fun add(amps: Float) {
            sum += amps
            min = minOf(min, amps)
            max = maxOf(max, amps)
            count++
        }
    }

    private data class WindowMean(
        val controller: Float,
        val firstBranch: Float,
        val secondBranch: Float,
        val controllerSpread: Float,
        val branchSpread: Float,
    ) {
        val bmsTotal: Float get() = firstBranch + secondBranch

        fun isQuiet(): Boolean = controllerSpread <= MAX_QUIET_CONTROLLER_SPREAD_A &&
            branchSpread <= MAX_QUIET_BMS_SPREAD_A &&
            abs(firstBranch) + abs(secondBranch) <= MAX_QUIET_BMS_A
    }

    private companion object {
        const val MOVING_KMH = 5f
        const val TRIP_RESET_TOLERANCE_KM = 0.01f
        const val RAIL_PAIR_MS = 1000L
        const val MAX_SAMPLE_GAP_MS = 1800L
        const val MIN_RAIL_STEP_A = 1.5f
        // Some unconfigured wheels report the positive nominal rail scale. 70 mV there
        // is roughly 100 mV on T4's physical rail, and well above one wire count.
        const val MIN_RAIL_STEP_V = 0.069f
        const val MIN_BRANCH_STEP_A = 1f
        // One 0.1 V BMS wire count, with room for Float representation.
        const val MIN_BRANCH_STEP_V = 0.09f
        const val MIN_RAIL_VOTES = 3
        const val MAX_RAIL_VOTES = 8
        const val RAIL_VOTE_LIFETIME_MS = 120_000L
        const val BMS_WINDOW_MS = 4000L
        const val BMS_MAX_AGE_MS = 2500L
        const val BMS_RAIL_TOLERANCE = 0.02f
        const val MIN_SAMPLES_IN_BMS_WINDOW = 3
        const val MIN_BMS_WINDOWS = 6
        const val MAX_BMS_WINDOWS = 12
        const val MIN_CONTROLLER_VARIANCE = 1.0
        const val MIN_BRANCH_VARIANCE = 0.25
        const val MIN_BMS_CORRELATION = 0.75
        const val OFFSET_WINDOWS = 3
        // The ET Max controller varies by roughly 1–1.7 A across quiet windows even
        // though the branch readings are stable. Average that noise before comparing offsets.
        const val MAX_QUIET_CONTROLLER_SPREAD_A = 1.8f
        const val MAX_QUIET_BMS_SPREAD_A = 0.75f
        const val MAX_OFFSET_SPREAD_A = 0.75f
        const val MAX_QUIET_BMS_A = 3f
        const val MAX_OFFSET_A = 8f
    }
}
