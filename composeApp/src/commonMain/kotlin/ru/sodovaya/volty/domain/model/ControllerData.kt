package ru.sodovaya.volty.domain.model

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class SpeedSource { REPORTED, DERIVED, NONE }

/** Why a controller could not provide speed. Null is kept for legacy/unknown causes. */
enum class SpeedUnknownReason {
    /** The decoder has eRPM but no positive wheel diameter/pole/gear geometry. */
    NO_WHEEL_GEOMETRY,
    /** The firmware path supplied no usable speed field. */
    FIRMWARE_DID_NOT_REPORT,
    /** Different controllers supplied different explicit causes. */
    MIXED
}

@OptIn(ExperimentalTime::class)
data class ControllerData(
    /**
     * Ground speed — **a MAGNITUDE. Never negative, on any protocol.**
     *
     * The contract every producer owes, stated on the field rather than in the
     * decoders that happen to keep it. It was learned the expensive way twice:
     * a Begode signs FORWARD negative on the rider's two wheels (`I` Task 1,
     * field report `2026-07-30-first-hardware-test` S1), and a VESC does the
     * same whenever its motor direction is configured the other way round
     * (`I` Task 10). Between those two tasks the two decoders disagreed about
     * this field for a whole part, and the reason nobody noticed is that there
     * was nothing here to disagree WITH.
     *
     * **It has to be a magnitude because every consumer compares it against an
     * UPPER threshold**, so a negative fails silently and totally rather than
     * loudly: [ru.sodovaya.volty.domain.stats.RideMetrics] nulls instant
     * consumption below `0.5`, the Ride dashboard's session peak cannot advance,
     * the SPEED alarm cannot fire, and both dial renderers draw it as a fraction
     * of full scale. [ru.sodovaya.volty.domain.stats.MotionAggregator]'s fold
     * does NOT rescue any of that — see the comment at its speed fold.
     *
     * **A new producer takes the magnitude at its own decode**, at the
     * assignment into this field, exactly as the four existing ones do
     * ([ru.sodovaya.volty.data.bms.vesc.VescValues] twice,
     * [ru.sodovaya.volty.data.bms.BegodeProtocol], and the demo simulator, whose
     * speeds are non-negative by construction). Not in a renderer, and not in
     * the aggregator: this is a property of the decode, so that a second
     * consumer of a protocol cannot acquire the field without it.
     *
     * The SIGN is not a direction and must not be treated as one — which sign
     * means forward is per-firmware and per-motor-wiring. Where it survives at
     * all it survives beside this field, never in it:
     * `BegodeProtocol.signedSpeedKmh()` and [eRpm] for VESC.
     *
     * `0f` is a real reading only when [speedSource] says so — see [speedKnown]
     * and [ru.sodovaya.volty.domain.stats.MotionReadings].
     */
    val speedKmh: Float = 0f,
    val speedSource: SpeedSource = SpeedSource.NONE,
    /** Populated only when [speedSource] is [SpeedSource.NONE]. */
    val speedUnknownReason: SpeedUnknownReason? = null,
    val dutyPercent: Float = 0f,
    /**
     * Whether [dutyPercent] is a MEASUREMENT rather than a placeholder — the
     * observed half of duty availability, and the twin of [hasMotorTemp].
     *
     * Duty has no "absent" encoding: every consumer treats it as a
     * non-negative 0..100 magnitude compared against upper thresholds, so an
     * unreported duty has to be published as `0f`, which is indistinguishable
     * from a genuine 0 %. A decoder whose hardware has not proved it reports
     * duty at all (Begode's `truePWM` latch — WheelLog's own name for it) would
     * therefore leave the ШИМ alarm, the headline safety feature for a wheel,
     * **displayed as armed and permanently unable to fire**: `F §10`'s
     * silent-dead-alarm class, on the one alert a EUC rider most needs.
     *
     * **Defaults to `true`**: the static
     * [ru.sodovaya.volty.domain.alert.reportsDuty] table already refuses the
     * protocols that report no duty at all, so a decoder that says nothing here
     * keeps exactly the behaviour it had — only a decoder that can tell
     * "reported" from "not yet reported" has anything to add, and only it sets
     * this.
     *
     * Read by [ru.sodovaya.volty.domain.alert.availabilityFor]'s DUTY branch as
     * its second layer, and folded across controllers by
     * [ru.sodovaya.volty.domain.stats.MotionAggregator.aggregate] with `any` —
     * one controller that measures duty is enough for the vehicle, because the
     * aggregate's `maxOf { dutyPercent }` already carries that controller's real
     * reading.
     */
    val hasDuty: Boolean = true,
    val motorCurrentA: Float = 0f,
    val batteryCurrentA: Float = 0f,
    /**
     * Whether [batteryCurrentA] is a measurement rather than a placeholder.
     *
     * Battery current is an input-side value, not interchangeable with the
     * phase current a controller such as Kelly KLS reports. The two can both
     * honestly be zero, so absence needs its own flag for Ride metrics.
     */
    val hasBatteryCurrent: Boolean = true,
    val inputVoltageV: Float = 0f,
    /**
     * Whether [inputVoltageV] is a MEASUREMENT rather than a placeholder — the
     * voltage member of the unknown-vs-zero contract ([hasDuty] is the first;
     * see [ru.sodovaya.volty.domain.stats.MotionReadings] for the whole rule).
     *
     * Voltage has no "absent" encoding either: a rail voltage is a non-negative
     * magnitude, so an unavailable one has to be published as `0f`, which is
     * indistinguishable from a dead pack. `BegodeProtocol.inputVoltageV` is the
     * first producer that genuinely cannot answer — the wheel reports against a
     * fixed 67.2 V reference and turning that into volts needs the pack's cell
     * count, which the rider may not have supplied (`D §2`).
     *
     * Since `I` Task 7 it is no longer the ONLY one:
     * [ru.sodovaya.volty.data.bms.vesc.VescValues] clears this on a reply
     * carrying `v_in = 0`, which is what a node that answers the frame without
     * measuring a rail sends. Until then nothing in the VESC path could reach
     * the false branch at all, so `G §9.3` was written up as fixed-by-flag while
     * the flag was unreachable for every VESC in the app.
     *
     * Folded across controllers with `any`, because
     * [ru.sodovaya.volty.domain.stats.MotionAggregator.aggregate] AVERAGES the
     * voltage over the controllers that measure it: one measurement is a
     * vehicle-level answer. This is the fold `G §9.3` is about — a head-unit row
     * answering a 0 V rail used to be averaged in as though it were a
     * measurement, reporting two thirds of a three-controller vehicle's real
     * pack voltage.
     *
     * **Defaults to `true`**, like [hasDuty]: a decoder that says nothing here
     * keeps exactly the behaviour it had.
     */
    val hasInputVoltage: Boolean = true,
    val powerW: Float = 0f,
    /**
     * Whether [powerW] is a MEASUREMENT rather than a placeholder.
     *
     * Separate from [hasInputVoltage] even though every producer today computes
     * `powerW = inputVoltageV * batteryCurrentA` and therefore sets the two
     * together, because **the two flags fold differently** and the aggregate is
     * itself a [ControllerData]: voltage is averaged (so `any` measurement
     * answers for the vehicle) while power is SUMMED (so a total is only a
     * measurement when EVERY term is one — see
     * [ru.sodovaya.volty.domain.stats.MotionAggregator.aggregate]). Collapsing
     * them into one flag would force the aggregate to lie about one of the two.
     *
     * **Defaults to `true`** — see [hasDuty].
     */
    val hasPower: Boolean = true,
    val eRpm: Float = 0f,
    val escTempC: Float = 0f,
    val motorTempC: Float = 0f,
    val hasMotorTemp: Boolean = false,
    val odometerKm: Float = 0f,
    /**
     * Distance travelled **since this connection started** — a SESSION delta,
     * not a counter the source keeps across connects.
     *
     * Stated here because it is the contract every decoder owes and none of
     * them can state on its own. [VescProtocol][ru.sodovaya.volty.data.bms.VescProtocol]
     * and [VescGatewayProtocol][ru.sodovaya.volty.data.bms.VescGatewayProtocol]
     * subtract a baseline taken at the connection's first frame;
     * [BegodeProtocol][ru.sodovaya.volty.data.bms.BegodeProtocol] does the same
     * against the wheel's lifetime odometer, deliberately NOT publishing the
     * wheel's own since-power-on field here; the demo simulator counts its own
     * session. A decoder that put a raw device counter in this field would show
     * a rider who connected mid-ride at km 30 a one-second-old session reading
     * 30.0 km on the TRIP tile.
     *
     * The reason it must be a session and not merely "some distance":
     * [ru.sodovaya.volty.domain.stats.RideMetrics.sessionWhPerKm] divides a
     * session's consumed Wh by this. A non-session denominator makes the
     * consumption figure the ratio of two different rides.
     *
     * Folded across controllers with `max` — every controller on one vehicle
     * has travelled the same distance, so the largest reading is the one whose
     * source has been connected longest.
     */
    val tripKm: Float = 0f,
    /**
     * Whether [odometerKm] and [tripKm] came from a distance counter. Kelly's
     * monitor supplies neither, so its forced zeroes must not read as a new
     * vehicle with a 0 km odometer and trip.
     */
    val hasDistance: Boolean = true,
    val consumedAh: Float = 0f,
    val consumedWh: Float = 0f,
    val regenAh: Float = 0f,
    val regenWh: Float = 0f,
    /**
     * Whether [consumedAh], [consumedWh], [regenAh] and [regenWh] are
     * MEASUREMENTS rather than placeholders — the third member of the
     * unknown-vs-zero contract, and the one `G §9.1` is about.
     *
     * A Begode's frames carry no energy counters at all, so all four read `0f`
     * forever. `0 Wh consumed` is also a real reading (a ride that has just
     * started), so the number cannot carry the distinction — and without it
     * `RideMetrics.sessionWhPerKm` returns `0f / tripKm` = a well-formed
     * **0.0 Wh/km** the instant the trip counter moves, on every Begode, for the
     * whole ride.
     *
     * One flag for all four counters rather than four: no producer has ever
     * reported a subset, and the question they answer ("does this protocol keep
     * energy counters?") is one question.
     *
     * Folded across controllers with `all`, because the counters are SUMMED —
     * see [hasPower] for why a summed field cannot use `any`.
     *
     * **Defaults to `true`** — see [hasDuty].
     */
    val hasEnergyCounters: Boolean = true,
    val faults: List<String> = emptyList(),
    /**
     * Controller-computed battery level 0..1 from COMM_GET_VALUES_SETUP, or null
     * when the frame carries none (plain GET_VALUES, non-VESC controllers). Used
     * only to seed a derived battery's SoC — the real fuel gauge, when a smart
     * BMS is present, always wins.
     */
    val batteryLevelFraction: Float? = null,
    val isConnected: Boolean = false,
    val timestamp: Instant = Clock.System.now()
) {
    val speedKnown: Boolean get() = speedSource != SpeedSource.NONE

    /**
     * Whether [escTempC] is a plausible ESC/MOSFET reading rather than VESC's
     * "no sensor wired" sentinel. [hasMotorTemp] is a stored flag pinned at
     * decode time (a genuinely absent motor reading has to survive per-frame);
     * the ESC reading has no field of its own, so this is computed from the
     * exact sentinel `VescValues` decodes the motor sensor against, instead of
     * threading one more constructor parameter through every call site that
     * builds a [ControllerData].
     *
     * **It is also why [ru.sodovaya.volty.domain.stats.MotionAggregator]'s
     * `escTempC` fold is the one `maxOf` there that is NOT filtered by its
     * flag.** Because the predicate is a threshold on the value,
     * `max(xs) > SENTINEL` is already identical to `∃x ∈ xs : x > SENTINEL` —
     * the fold composes with this getter to give exactly the `any` rule every
     * other maxOf field gets its flag folded by, and adding the filter would be
     * a line no test could distinguish from its absence for any value a
     * producer can emit. (Over the whole `Float` domain it is not an identity —
     * a `NaN` is dropped by the filter and propagated by `maxOf` — but every
     * `escTempC` on this path is a scaled i16, so no decoder can reach that.)
     * Every
     * producer therefore owes a sentinel here rather than a `0f`
     * (`BegodeProtocol.NO_TEMP_SENSOR_C`), because a `0f` claims a sensor
     * before any fold can see it.
     */
    val hasEscTemp: Boolean get() = escTempC > NO_TEMP_SENSOR_BELOW_C
}

/** VESC's "no sensor wired" reading — implausibly low rather than a dedicated flag. */
private const val NO_TEMP_SENSOR_BELOW_C = -50f
