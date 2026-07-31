package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.ControllerData

/**
 * **The unknown-vs-zero contract for motion telemetry** (`G §9`, `§9.1`, `§9.3`).
 *
 * Part F taught the *alarm* that a value nobody has observed is not a zero
 * ([ru.sodovaya.volty.domain.alert.AlertAvailability]). The dashboard and the
 * aggregator never learned it, and three fields showed the consequence: a wheel
 * whose firmware has never reported a PWM showed a confident **0 %** on the duty
 * dial, an unavailable voltage scale rendered **"0.0 kW"**, and a Begode read
 * **"0.0 Wh/km"** for a whole ride. This object is the rule, stated once, so the
 * gauges stop each deciding it for themselves.
 *
 * ## The rule
 *
 * Every motion quantity a gauge can draw is exposed here as a `Float?`, where
 * **null means "not observed" and is never a zero**. [ControllerData] cannot use
 * that encoding itself — its fields are non-nullable magnitudes that decoders,
 * the aggregator and the alarm all compare against thresholds — so the
 * distinction lives in a parallel set of known-flags ([ControllerData.hasDuty],
 * [ControllerData.hasInputVoltage], [ControllerData.hasPower],
 * [ControllerData.hasEnergyCounters], [ControllerData.hasMotorTemp],
 * [ControllerData.hasEscTemp], [ControllerData.speedKnown]) and this object is
 * the single place that pairs each flag with its number.
 *
 * Renderers must read motion through here rather than off [ControllerData]
 * directly. There are three of them — Classic's dial cluster
 * ([ru.sodovaya.volty.presentation.ride.ClassicDialSpecs]), Clean's metric cards
 * ([ru.sodovaya.volty.presentation.ride.CleanMetricMapper]) and the hero's
 * secondary gauge ([ru.sodovaya.volty.presentation.ride.SecondaryGaugeMapper]) —
 * and the *rendering* half of the rule is
 * [ru.sodovaya.volty.presentation.ride.UNKNOWN_READOUT]: one marker, `—`,
 * visibly different from any real low reading, on all three.
 *
 * ## What it deliberately does NOT cover
 *
 *  - **the battery.** [ru.sodovaya.volty.domain.model.BmsData.socKnown] already
 *    says exactly this for state of charge, and the battery path is pinned by
 *    tests that predate this contract. It is the same rule, older, and it stays
 *    where it is;
 *  - **the alarm.** [ru.sodovaya.volty.domain.alert.availabilityFor] reads the
 *    same flags to decide what may be *armed*; that is a different question from
 *    what may be *drawn* (an unknown value is undrawable AND unarmable, but a
 *    known value can be drawable and still unarmable — see
 *    [ru.sodovaya.volty.domain.alert.AlertAvailability.Unknown]). Nothing here
 *    changes what the alarm sees;
 *  - **current, eRPM, odometer and trip.** No producer can distinguish an
 *    unreported one from a genuine zero (`D`'s "absent distances" note on
 *    [ru.sodovaya.volty.data.bms.BegodeProtocol]), so a flag for them would be a
 *    field nothing could ever set to false.
 */
object MotionReadings {

    /** The wheel's ground speed, or null when [ControllerData.speedKnown] is false. */
    fun speedKmh(motion: ControllerData): Float? =
        if (motion.speedKnown) motion.speedKmh else null

    /** Duty/ШИМ, or null when this firmware has never reported a PWM (`D §7.2`). */
    fun dutyPercent(motion: ControllerData): Float? =
        if (motion.hasDuty) motion.dutyPercent else null

    /** Rail voltage, or null when no scale is available to turn the reading into volts. */
    fun inputVoltageV(motion: ControllerData): Float? =
        if (motion.hasInputVoltage) motion.inputVoltageV else null

    /** Electrical power, or null when the voltage it is derived from is unavailable. */
    fun powerW(motion: ControllerData): Float? =
        if (motion.hasPower) motion.powerW else null

    /** Motor winding temperature, or null when no thermistor is wired. */
    fun motorTempC(motion: ControllerData): Float? =
        if (motion.hasMotorTemp) motion.motorTempC else null

    /** ESC/MOSFET temperature, or null when VESC's "no sensor" sentinel is present. */
    fun escTempC(motion: ControllerData): Float? =
        if (motion.hasEscTemp) motion.escTempC else null

    /**
     * Live consumption from power and speed, or null when either is unobserved
     * — or when the vehicle is too slow for the division to mean anything
     * ([RideMetrics.instantWhPerKm]).
     *
     * The power guard is the `§9` half: `instantWhPerKm(0f, speed)` returns a
     * perfectly well-formed `0f`, so a missing voltage scale used to arrive at
     * the gauge as a confident **0.0 Wh/km** rather than as an absence.
     */
    fun instantWhPerKm(motion: ControllerData): Float? {
        val power = powerW(motion) ?: return null
        val speed = speedKmh(motion) ?: return null
        return RideMetrics.instantWhPerKm(power, speed)
    }

    /**
     * Session-average consumption, or null when this protocol keeps no energy
     * counters ([ControllerData.hasEnergyCounters]) or the trip has not started.
     *
     * The flag is the `§9.1` half: [RideMetrics.sessionWhPerKm] returns null only
     * when `tripKm <= 0`, so on a Begode the fallback became a well-formed
     * `0.0 Wh/km` the instant the trip counter moved.
     */
    fun sessionWhPerKm(motion: ControllerData): Float? =
        if (!motion.hasEnergyCounters) null
        else RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm)

    /**
     * What a consumption gauge shows: the live figure while moving, the session
     * average otherwise, null when neither is observed.
     *
     * Stated once here because all three renderers used to spell this fallback
     * out themselves, and a fallback duplicated three times is a fallback that
     * gets fixed in two places.
     */
    fun whPerKm(motion: ControllerData): Float? = instantWhPerKm(motion) ?: sessionWhPerKm(motion)

    /**
     * A session-consumption figure **together with where it came from**.
     *
     * [synthesised] is true when [whPerKm] was integrated from power
     * ([RideEnergy]) rather than read off the protocol's own counters. It is a
     * SEPARATE signal from [ControllerData.hasEnergyCounters], deliberately and
     * for the reason this whole contract exists: a derived number published as
     * a measurement is a lie with better manners than a confident zero, and a
     * consumer that has never heard of synthesis must still see "this protocol
     * keeps no counters" rather than a fake reading.
     */
    data class SessionConsumption(val whPerKm: Float, val synthesised: Boolean)

    /**
     * The session average a gauge may show, with its provenance — the measured
     * figure when the protocol keeps counters, otherwise [synthesisedWh]
     * divided by the same session distance, otherwise null.
     *
     * **A measurement always wins.** A protocol that keeps counters is reporting
     * what its own coulomb counting says; an integral of `power × dt` over BLE
     * arrival gaps is a reconstruction, and preferring the reconstruction on a
     * VESC would replace a number the firmware stands behind with one we made
     * up. The synthesis is a floor under the vehicles that have nothing, not an
     * improvement on the ones that do.
     *
     * [synthesisedWh] is null when the caller has nothing to offer (no samples
     * yet, or a stream in which fewer than two samples carried a measured
     * power), which is exactly the pre-existing "blank" — never a zero.
     */
    fun sessionConsumption(motion: ControllerData, synthesisedWh: Float?): SessionConsumption? {
        sessionWhPerKm(motion)?.let { return SessionConsumption(it, synthesised = false) }
        val wh = synthesisedWh ?: return null
        // The same divisor and the same "a trip that has not started is not a
        // distance" guard the measured branch goes through — RideMetrics owns
        // that rule for both, so a synthesised figure cannot invent a division
        // the measured one refuses.
        val perKm = RideMetrics.sessionWhPerKm(wh, motion.tripKm) ?: return null
        return SessionConsumption(perKm, synthesised = true)
    }
}
