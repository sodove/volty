package ru.sodovaya.volty.data.demo

import ru.sodovaya.volty.domain.model.BmsData
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Pure, deterministic-ish generator of a realistic synthetic [BmsData] stream
 * for the "Try demo" mode. No BLE, no I/O — every value is computed from the
 * tick index so [sampleAt] is trivially unit-testable.
 *
 * Models a 16s Li-ion (NMC) pack cycling through three phases (~3 min loop):
 *  - **A "riding"** (~100s): discharge, current oscillating roughly -3..-12 A with
 *    smooth sine noise and occasional 2-3 s regen spikes (+2..+5 A).
 *  - **B "stopped"** (~20 s): current ≈ 0, power ≈ 0.
 *  - **C "charging"** (~60 s): current +4..+6 A — flips the dashboard hero to the
 *    green charging card, which is the most interesting state for reviewers.
 *
 * SOC starts at 76 %, drains slowly during A and rises during C. The pack stays
 * clean (no faults, balanced cells bar one slightly-low cell to exercise the
 * min-highlight in the cells view).
 *
 * The simulator carries no mutable state: pass it nothing, then either drive it
 * with [run] (a 700 ms loop) or evaluate single points with [sampleAt].
 */
@OptIn(ExperimentalTime::class)
class DemoBmsSimulator(
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
) {

    companion object {
        const val TICK_INTERVAL_MS: Long = 700L

        // Phase durations expressed in seconds.
        private const val PHASE_A_SECONDS = 100
        private const val PHASE_B_SECONDS = 20
        private const val PHASE_C_SECONDS = 60
        private const val CYCLE_SECONDS = PHASE_A_SECONDS + PHASE_B_SECONDS + PHASE_C_SECONDS

        const val CELL_COUNT = 16
        const val CAPACITY_AH = 35.0f
        const val NUM_CYCLES = 42

        // SOC bounds (kept well inside 0..100 so noise never clips weirdly).
        private const val SOC_START = 76.0f
        private const val SOC_MIN = 35.0f
        private const val SOC_MAX = 92.0f

        // Stable per-run seed: same demo curve every launch (nice for screenshots).
        private const val SEED = 8765309L
    }

    /**
     * Suspend loop: compute a sample for the current tick, hand it to [emit],
     * then [delay] one [tickIntervalMs] interval. Runs until the calling scope
     * is cancelled.
     */
    suspend fun run(emit: (BmsData) -> Unit): Unit = coroutineScope {
        var tick = 0
        while (isActive) {
            emit(sampleAt(tick))
            tick++
            delay(tickIntervalMs)
        }
    }

    /**
     * Pure function: the synthetic [BmsData] at [tickIndex] (0-based). Timestamp
     * is the wall clock at call time so the ring buffer / graph windows behave
     * exactly as with a real connection.
     */
    fun sampleAt(tickIndex: Int): BmsData {
        val rnd = Random(SEED + tickIndex)
        val elapsedSec = tickIndex * tickIntervalMs / 1000.0
        val cyclePos = (elapsedSec % CYCLE_SECONDS)
        val phase = phaseOf(cyclePos)

        val soc = socAt(tickIndex).coerceIn(0f, 100f)
        val current = currentAt(phase, elapsedSec, cyclePos, rnd)

        val cells = cellVoltages(soc, rnd)
        val voltage = cells.sum()
        val power = voltage * current

        val temps = temperatures(elapsedSec, rnd)

        val charge = CAPACITY_AH * soc / 100f

        return BmsData(
            voltage = voltage,
            current = current,
            power = power,
            soc = soc,
            charge = charge,
            capacity = CAPACITY_AH,
            numCycles = NUM_CYCLES,
            cellVoltages = cells,
            temperatures = temps,
            chargeEnabled = true,
            dischargeEnabled = true,
            bmsFaults = emptyList(),
            isConnected = true,
            timestamp = Clock.System.now()
        )
    }

    private enum class Phase { RIDING, STOPPED, CHARGING }

    private fun phaseOf(cyclePos: Double): Phase = when {
        cyclePos < PHASE_A_SECONDS -> Phase.RIDING
        cyclePos < PHASE_A_SECONDS + PHASE_B_SECONDS -> Phase.STOPPED
        else -> Phase.CHARGING
    }

    /**
     * SOC integrated across whole completed cycles plus the current partial one.
     * Drains ~0.01 %/s during riding, flat while stopped, and recovers during
     * charging. Clamped to [SOC_MIN]..[SOC_MAX] so a long demo session keeps
     * cycling visibly instead of pinning at an extreme.
     */
    private fun socAt(tickIndex: Int): Float {
        val totalSec = tickIndex * tickIntervalMs / 1000.0
        var soc = SOC_START.toDouble()
        val stepSec = tickIntervalMs / 1000.0
        var t = 0.0
        // Integrate in tick-sized steps; cheap enough for demo cadence and keeps
        // the curve consistent with the per-phase current sign.
        while (t < totalSec) {
            val cyclePos = t % CYCLE_SECONDS
            soc += when (phaseOf(cyclePos)) {
                Phase.RIDING -> -0.012 * stepSec
                Phase.STOPPED -> 0.0
                Phase.CHARGING -> 0.045 * stepSec
            }
            soc = soc.coerceIn(SOC_MIN.toDouble(), SOC_MAX.toDouble())
            t += stepSec
        }
        return soc.toFloat()
    }

    private fun currentAt(phase: Phase, elapsedSec: Double, cyclePos: Double, rnd: Random): Float = when (phase) {
        Phase.RIDING -> {
            // Smooth oscillation -3..-12 A via sine, plus a little jitter.
            val base = -7.5 + 4.0 * sin(elapsedSec / 6.0)
            val jitter = (rnd.nextFloat() - 0.5f) * 1.2f
            var c = (base + jitter).toFloat()
            // Occasional 2-3 s regen spike (positive current).
            if (isRegenWindow(cyclePos)) {
                c = 2f + rnd.nextFloat() * 3f
            }
            c.coerceIn(-12f, 5f)
        }
        Phase.STOPPED -> (rnd.nextFloat() - 0.5f) * 0.3f // ~0 A
        Phase.CHARGING -> 4f + rnd.nextFloat() * 2f       // +4..+6 A
    }

    /** Regen spikes land in two short windows during the riding phase. */
    private fun isRegenWindow(cyclePos: Double): Boolean {
        val inFirst = cyclePos in 30.0..33.0
        val inSecond = cyclePos in 70.0..72.0
        return inFirst || inSecond
    }

    private fun cellVoltages(soc: Float, rnd: Random): List<Float> {
        val nominal = 3.3f + soc * 0.9f / 100f // ~3.3..4.2 V across SOC range
        return List(CELL_COUNT) { i ->
            val noise = (rnd.nextFloat() - 0.5f) * 0.016f // ±8 mV
            // Keep one cell slightly low so the cells view shows a min highlight.
            val bias = if (i == 7) -0.015f else 0f
            (nominal + noise + bias).coerceIn(2.5f, 4.25f)
        }
    }

    private fun temperatures(elapsedSec: Double, rnd: Random): List<Float> {
        // 4 sensors, 24..31 °C drifting slowly; T2 (index 1) always ~2° hotter.
        val drift = 3.5 * sin(elapsedSec / 40.0)
        val base = 27.0 + drift
        return List(4) { i ->
            val hotBias = if (i == 1) 2.0 else 0.0
            val perSensor = (i - 1.5) * 0.4
            val noise = (rnd.nextFloat() - 0.5f) * 0.4f
            (base + hotBias + perSensor + noise).toFloat()
        }
    }
}
