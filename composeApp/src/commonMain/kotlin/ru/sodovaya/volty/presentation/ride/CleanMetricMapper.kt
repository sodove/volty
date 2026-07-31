package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
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
     * **How full the hero's outer speed ring is drawn** — 0..1, and **0 for a
     * speed nobody has observed**.
     *
     * The one place `G §9`'s contract leaked past the three mappers. The hero's
     * readout has always dashed an unknown speed ([heroSpeedValue]), but the
     * needle beside it was computed as a bare `motion.speedKmh / vehicleMaxSpeed`
     * inside `RideHero`, and `speedKmh` is `0f` on exactly the samples
     * [ru.sodovaya.volty.domain.model.SpeedSource.NONE] marks — a VESC slave
     * whose wheel diameter nobody measured. So the arc drew a confident empty
     * ring, i.e. a claim of "standing still", right next to the `—` that says we
     * do not know. Both halves must say the same thing; the arc parks, exactly
     * as [ClassicDialSpecs]' SPEED dial already parks its needle.
     *
     * A parked ring and a genuine 0 km/h look alike, and that is fine: an empty
     * arc is not a *reading*, it is the absence of one, and the readout in the
     * middle of it is what distinguishes the two cases. What was wrong was
     * letting a placeholder DRIVE the arc — with a reported speed above the
     * scale, or a decoder writing into `speedKmh` without setting a source, the
     * two spellings differ.
     *
     * Guarded against a non-positive [maxSpeedKmh] the same way
     * [SecondaryGaugeMapper]'s own `frac` is: a fraction of `x / 0` is a NaN, and
     * a NaN sweep angle draws nothing while quietly poisoning the animation.
     */
    fun heroSpeedFraction(motion: ControllerData, maxSpeedKmh: Float): Float {
        val speed = MotionReadings.speedKmh(motion) ?: return 0f
        if (maxSpeedKmh <= 0f) return 0f
        return (speed / maxSpeedKmh).coerceIn(0f, 1f)
    }

    /**
     * The hero's big number: the speed in the rider's units, or
     * [UNKNOWN_READOUT] when [ru.sodovaya.volty.domain.model.ControllerData.speedKnown]
     * is false.
     *
     * The behaviour is what `RideHero` already did; what moves here is the
     * *decision*, out of the `@Composable` a test cannot reach and off a local
     * `"—"` literal that was a second spelling of [UNKNOWN_READOUT]. The unit
     * caption above it stays in the composable — it swaps for a localized
     * `ride_speed_unknown` string, which needs a resource resolver.
     */
    fun heroSpeedValue(motion: ControllerData, units: UnitSystem): String =
        MotionReadings.speedKmh(motion).readoutOr { UnitFormatter.speed(it, units) }

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
