package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.util.formatFixed
import ru.sodovaya.volty.util.formatSigned

/**
 * The Clean renderer's third of `G §9`'s contract: the readout strings its
 * metric cards show, decided here rather than inside `RideDashboardScreen`'s
 * `@Composable`s.
 *
 * It exists because **Compose UI is not unit-testable in this repo** — no
 * Robolectric, no `compose-ui-test`, no instrumented source set — so a decision
 * left inside `MetricCluster` or `ConsumptionCard` is a decision no test can
 * reach. Classic's gauges already had [ClassicDialSpecs] and the hero's
 * secondary already had [SecondaryGaugeMapper]; Clean's two motion cards were
 * the one renderer of the three deciding in the composable, which is exactly
 * where `§9`'s **"0.0 kW"** and `§9.1`'s **"avg 0.0 Wh/km"** survived review.
 *
 * Every function here reads its number through [MotionReadings] and renders an
 * absent one as [UNKNOWN_READOUT], so all three renderers agree per sample —
 * see `UnknownMotionRenderingTest`, which spans them.
 */
object CleanMetricMapper {

    /**
     * The POWER card's headline: kilowatts to one decimal, or [UNKNOWN_READOUT]
     * when no voltage scale is available to derive watts from (`G §9`).
     *
     * The unit goes with the number rather than being appended by the caller,
     * for the reason the BATTERY card next to it already demonstrates: an
     * unknown reading shows a bare `—`, not `— kW`. A dash carrying a unit reads
     * as a measurement of zero in that unit, which is the claim this whole
     * contract exists to stop making.
     */
    fun powerValue(motion: ControllerData): String =
        MotionReadings.powerW(motion).readoutOr { "${formatFixed(it / 1000f, 1)} kW" }

    /**
     * The POWER card's sub-line. **Not** part of the contract: battery current
     * is reported by every decoder here, including a Begode with no cell count
     * — the missing scale costs the volts, not the amps — so it has no
     * known-flag and renders as a plain signed number.
     */
    fun powerSub(motion: ControllerData): String = "${formatSigned(motion.batteryCurrentA, 1)} A"

    /**
     * The consumption card's headline: live Wh/km, or [UNKNOWN_READOUT] while
     * standing still or when power is unobserved. Unit-less — the card writes
     * its own " Wh/km" beside it in a smaller type.
     */
    fun instantConsumptionValue(motion: ControllerData): String =
        MotionReadings.instantWhPerKm(motion).readoutOr { formatFixed(it, 1) }

    /**
     * The session average to substitute into `ride_consumption_avg`, or **null
     * meaning "do not show the chip at all"**.
     *
     * Null rather than a dash here, deliberately and unlike every other case in
     * this object: the chip is a whole "avg 16.9 Wh/km" phrase in the card's
     * corner, and "avg — Wh/km" is a sentence about an absence where showing
     * nothing says the same thing in less space. The headline beside it still
     * carries the dash, so the card never goes wordless.
     *
     * Takes the already-computed figure rather than the sample, because
     * `RideDashboardComponent` folds it into its state from the motion stream
     * (via [MotionReadings.sessionWhPerKm], which is where `§9.1`'s flag is
     * honoured) and the card must not recompute a second, disagreeing answer.
     */
    fun sessionConsumptionValue(sessionWhPerKm: Float?): String? =
        sessionWhPerKm?.let { formatFixed(it, 1) }
}
