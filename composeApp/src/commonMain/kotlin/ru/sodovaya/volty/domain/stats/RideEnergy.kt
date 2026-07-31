package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.ControllerData
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * **Energy integrated from power, for the vehicles that count no watt-hours.**
 *
 * A Begode's frames carry no `consumedWh`/`consumedAh` at all
 * ([ControllerData.hasEnergyCounters] is false for the whole ride), so
 * [MotionReadings.sessionWhPerKm] correctly refuses to answer and the Ride
 * dashboard's session-consumption chip is blank *by construction* — while the
 * Graph screen has been integrating the same quantity, correctly, all along.
 * This object is that arithmetic, extracted, so there is **one** integrator
 * rather than two: [DefaultGraphComponent][ru.sodovaya.volty.presentation.graph.DefaultGraphComponent]'s
 * "used Wh/Ah" and the dashboard's synthesised session both call
 * [integrateHours].
 *
 * ## Sign: this function does NOT negate, and its two callers differ
 *
 * The trap that makes one shared integrator worth arguing about. The app holds
 * **two opposite current conventions**, and
 * [VescProtocol.derivedBatteryFrom][ru.sodovaya.volty.data.bms.VescProtocol] is
 * the single place they meet — it negates a controller sample into a battery
 * one (`current = -m.batteryCurrentA`, `power = -m.powerW`):
 *
 *  - **[ControllerData.powerW] is discharge-POSITIVE** (VESC's own convention:
 *    `v_in * current_in`, positive while drawing from the pack, negative under
 *    regen). Every producer agrees — `BegodeProtocol.rebuildMotion` negates the
 *    wheel's battery-convention accessor specifically to reach it, and
 *    `DemoBmsSimulator.wheelBatteryCurrentA` documents itself as "always
 *    positive (VESC convention)";
 *  - **[BmsData.power][ru.sodovaya.volty.domain.model.BmsData] is
 *    charge-positive** (`+ = charging`, `− = discharging`), which is why
 *    `AntBmsProtocol` negates on decode and the Graph screen negates for
 *    display.
 *
 * So [integrateHours] returns the integral **in the samples' own sign**, and
 * each caller applies its own convention on top: the Graph screen negates its
 * `BmsData` result so a discharge reports positive Wh used, and [sessionWh]
 * does **not** negate its `ControllerData` result, because a ride is already
 * positive there. A shared integrator that silently baked in one caller's sign
 * would be worse than two integrators.
 *
 * ## Provenance
 *
 * Nothing here sets [ControllerData.hasEnergyCounters]. A derived number
 * presented as a measurement is the exact defect the unknown-vs-zero contract
 * exists to prevent, so the provenance travels as a separate signal —
 * [MotionReadings.SessionConsumption.synthesised] — and a consumer that has
 * never heard of synthesis still sees "this protocol keeps no counters".
 */
@OptIn(ExperimentalTime::class)
object RideEnergy {

    /**
     * How far back the dashboard asks the motion ring buffer for samples.
     *
     * Matches [SampleRingBuffer][ru.sodovaya.volty.data.memory.SampleRingBuffer]'s
     * own default `maxAge`, i.e. "everything retained" — the buffer's retention,
     * not this number, is the real bound. The **session** boundary is the
     * `since` argument of [sessionWh], not the window: the buffer is cleared on
     * disconnect and on connecting to a different device, but deliberately kept
     * across a reconnect to the same address so the graph survives link drops,
     * and a reconnect restarts [ControllerData.tripKm] (it is a session delta).
     */
    val SESSION_WINDOW: Duration = 4.hours

    /**
     * Trapezoidal `∫ value·dt` over [samples], in **value-units × hours**, in
     * the samples' own sign — see this object's KDoc on why it does not negate.
     *
     * Returns **null** when fewer than two samples carry a value, i.e. when
     * there is no interval to integrate over. Null rather than `0.0` because
     * those are different statements: `0.0` is what a genuinely balanced
     * interval integrates to, and "nothing to integrate yet" must not arrive at
     * a gauge as a confident zero.
     *
     * **A sample whose [valueOf] is null is DROPPED, not read as zero.** That is
     * the whole reason the extractor is nullable: since `I` Task 7 a VESC node
     * answering with `v_in = 0` genuinely clears [ControllerData.hasPower], so
     * integrating an unmeasured power as 0 W is reachable today rather than
     * hypothetical — and it is the same "an unobserved value is not a zero"
     * defect [MotionReadings] exists for. Dropping bridges the gap with the
     * trapezoid between the two nearest *measured* samples, which assumes the
     * power varied smoothly across the hole; treating the hole as 0 W would
     * assume the vehicle coasted through it, which is a claim nobody made.
     *
     * [timestampOf] is read from the sample rather than from a clock, so the
     * arithmetic is pure and the gaps are the real arrival gaps.
     */
    fun <T> integrateHours(
        samples: List<T>,
        timestampOf: (T) -> Instant,
        valueOf: (T) -> Float?
    ): Double? {
        val points = samples.mapNotNull { s -> valueOf(s)?.let { timestampOf(s) to it } }
        if (points.size < 2) return null
        var acc = 0.0
        for (i in 1 until points.size) {
            val (tPrev, vPrev) = points[i - 1]
            val (tNow, vNow) = points[i]
            val dtHours = (tNow - tPrev).inWholeMilliseconds / 1000.0 / 3600.0
            acc += (vPrev + vNow) / 2.0 * dtHours
        }
        return acc
    }

    /**
     * Watt-hours drawn over [samples] since [since], or null when fewer than
     * two of them carry a measured power.
     *
     * **Positive for a ride** — [ControllerData.powerW] is discharge-positive,
     * so no negation happens here (this object's KDoc argues the asymmetry with
     * the Graph screen's caller). A stretch of regen subtracts, so a descent
     * that puts more back than it took reports a **negative** figure; that is
     * the honest answer and the same net convention the Graph screen's "used"
     * already reports for the pack. It is deliberately NOT the gross
     * `amp_hours`-style counter VESC keeps, because there is no `regenWh`
     * published beside it to make a gross figure readable.
     *
     * [since] bounds the integral to the CURRENT connection. The motion ring
     * buffer survives a reconnect to the same address on purpose, but
     * [ControllerData.tripKm] — the denominator this figure is about to be
     * divided by — restarts with the protocol, so energy from before the
     * reconnect would be charged against distance travelled after it. Null
     * means "no session start known yet", and then everything retained is
     * integrated.
     */
    fun sessionWh(samples: List<ControllerData>, since: Instant?): Float? {
        val inSession = if (since == null) samples else samples.filter { it.timestamp >= since }
        return integrateHours(inSession, { it.timestamp }) { MotionReadings.powerW(it) }?.toFloat()
    }
}
