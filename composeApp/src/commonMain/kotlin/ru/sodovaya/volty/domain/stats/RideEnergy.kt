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
 * `BmsData` result so a discharge reports positive Wh used, and [windowedRide]
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
     * not this number, is the real bound, and the buffer evicts on a
     * `hardCap = 60_000` sample count as well as on age. The **session** boundary
     * is the `since` argument of [windowedRide], not the window: the buffer is
     * cleared on disconnect and on connecting to a different device, but
     * deliberately kept across a reconnect to the same address so the graph
     * survives link drops, and a reconnect restarts [ControllerData.tripKm] (it
     * is a session delta).
     */
    val SESSION_WINDOW: Duration = 4.hours

    /**
     * The energy and the distance of one retained window, **measured over the
     * same samples**.
     *
     * The pairing is the whole point of the type. [wh] can only ever cover what
     * the ring buffer still holds, so pairing it with a *session-total* distance
     * would make the quotient read progressively low the moment eviction starts,
     * with nothing but the `≈` to say so — and eviction is reachable on the very
     * vehicle this exists for: the Begode capture behind
     * [ru.sodovaya.volty.data.ble.BleConfig] is 228 notifications in 13 s, one
     * buffered aggregate apiece, so the 60 000-sample hard cap arrives in a
     * couple of hours.
     */
    data class WindowedRide(val wh: Float, val km: Float)

    /**
     * Trapezoidal `∫ value·dt` over [samples], in **value-units × hours**, in
     * the samples' own sign — see this object's KDoc on why it does not negate.
     *
     * Returns **null** when fewer than two samples are given, i.e. when there is
     * no interval to integrate over. Null rather than `0.0` because those are
     * different statements: `0.0` is what a genuinely balanced interval
     * integrates to, and "nothing to integrate yet" must not arrive at a gauge
     * as a confident zero.
     *
     * **Every sample handed over is integrated; deciding which samples are
     * measurements belongs to the caller.** [windowedRide] filters first,
     * because it needs the very same set for its distance — and a second,
     * private "is this a measurement" rule in here would be a rule the divisor
     * could disagree with. (The nullable-extractor version of this function was
     * exactly that: once [windowedRide] pre-filtered, no null could reach it,
     * and mutants R2/S4 could not be told from the code they replaced.)
     *
     * [timestampOf] is read from the sample rather than from a clock, so the
     * arithmetic is pure and the gaps are the real arrival gaps. **A pair of
     * samples in descending time order therefore SUBTRACTS energy**, and nothing
     * here re-sorts them: both callers hand over a list in arrival order (a
     * `SampleRingBuffer`, which only ever appends), and the motion aggregate's
     * own timestamp is a `maxOf` across contributors, so it cannot go backwards
     * while the buffer moves forwards. Stated rather than guarded because a
     * `sortedBy` here would be a line no producer could make observable.
     */
    fun <T> integrateHours(
        samples: List<T>,
        timestampOf: (T) -> Instant,
        valueOf: (T) -> Float
    ): Double? {
        val points = samples.map { s -> timestampOf(s) to valueOf(s) }
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
     * The watt-hours and the kilometres of the retained window since [since], or
     * null when fewer than two of its samples carry a measured power.
     *
     * **Both numbers are taken over the SAME samples** — the ones whose power is
     * a measurement — and that is what makes their quotient a consumption figure
     * rather than a ratio of two different rides. A sample dropped from the
     * integral for having no power is dropped from the distance too; charging
     * the kilometres it covered against energy nobody measured would read low
     * for exactly the reason the whole contract exists.
     *
     * **The filter is where "an unobserved power is not a zero" is enforced**, and
     * it is deliberately the ONLY place: since `I` Task 7 a VESC node answering
     * with `v_in = 0` genuinely clears [ControllerData.hasPower], so integrating
     * an unmeasured power as 0 W is reachable today rather than hypothetical.
     * Dropping such a sample bridges the hole with the trapezoid between the two
     * nearest *measured* neighbours, which assumes the power varied smoothly
     * across it; reading it as 0 W would assume the vehicle coasted through it,
     * which is a claim nobody made. Note that the predicate is
     * [MotionReadings.powerW] and not `powerW != 0f`: a measured zero is a
     * reading, and a stretch of it is most of a coasting ride.
     *
     * **[wh] is positive for a ride.** [ControllerData.powerW] is
     * discharge-positive, so no negation happens here (this object's KDoc argues
     * the asymmetry with the Graph screen's caller). A stretch of regen
     * subtracts, so a descent that puts more back than it took reports a
     * **negative** figure; that is the honest answer and the same net convention
     * the Graph screen's "used" already reports for the pack. It is deliberately
     * NOT the gross `amp_hours`-style counter VESC keeps, because there is no
     * `regenWh` published beside it to make a gross figure readable.
     *
     * **[km] is a delta across the window, not [ControllerData.tripKm] itself.**
     * `tripKm` is a session counter and the buffer is not: it evicts on age and
     * on a sample-count hard cap. Dividing a windowed numerator by a session
     * divisor is wrong the instant the first sample is evicted, and wrong by a
     * factor that grows silently for the rest of the ride. A delta across the
     * same window stays *correct* instead of degrading. The subtraction is sound
     * because `tripKm` is monotonic non-decreasing within one connection (it is
     * a delta from a baseline the protocol takes at its first frame, folded
     * across controllers with `maxOf`) and [since] excludes the previous
     * connection, where it restarted.
     *
     * [since] bounds both halves to the CURRENT connection. The motion ring
     * buffer survives a reconnect to the same address on purpose, but `tripKm`
     * restarts with the protocol, so without the bound the previous leg's
     * watt-hours would be charged against distance travelled after it — and the
     * distance delta would straddle a counter that went backwards. Null means
     * "no session start known yet", and then everything retained is used.
     */
    fun windowedRide(samples: List<ControllerData>, since: Instant?): WindowedRide? {
        val measured = (if (since == null) samples else samples.filter { it.timestamp >= since })
            .filter { MotionReadings.powerW(it) != null }
        // `it.powerW` and not `MotionReadings.powerW(it)`: the filter above has
        // already decided that every remaining sample's power IS a measurement,
        // and asking the gate a second time would be a question with one answer.
        val wh = integrateHours(measured, { it.timestamp }) { it.powerW } ?: return null
        // Non-null above ⇒ at least two measured samples ⇒ first()/last() are safe.
        return WindowedRide(wh = wh.toFloat(), km = measured.last().tripKm - measured.first().tripKm)
    }

    /**
     * **The synthesised consumption: a WINDOWED average, not a session one.**
     *
     * Wh over the retained window divided by the kilometres of that same window
     * ([windowedRide]). Null when there is nothing to integrate, and null when
     * the window covers no positive distance — the refusal is
     * [RideMetrics.sessionWhPerKm]'s, so a synthesised figure cannot invent a
     * division the measured branch would decline.
     *
     * **This equals the session average until the ring buffer starts evicting,
     * and stays a true consumption figure afterwards** — which is the reason it
     * is windowed and not accumulated. Please do not "restore" a session-total
     * divisor: pairing a numerator that can only cover the retained tail with a
     * distance that covers the whole ride makes the readout drift low with no
     * symptom a rider could notice. An accumulator that survived eviction would
     * also have to be kept in step with connects, resets and vehicle switches,
     * which is state this object deliberately does not own.
     */
    fun synthesisedWhPerKm(samples: List<ControllerData>, since: Instant?): Float? =
        windowedRide(samples, since)?.let { RideMetrics.sessionWhPerKm(it.wh, it.km) }
}
