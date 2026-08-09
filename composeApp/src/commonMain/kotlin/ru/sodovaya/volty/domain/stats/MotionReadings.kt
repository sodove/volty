package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedUnknownReason

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
 * [ControllerData.hasInputVoltage], [ControllerData.hasPower], [ControllerData.hasBatteryCurrent],
 * [ControllerData.hasEnergyCounters], [ControllerData.hasMotorTemp],
 * [ControllerData.hasEscTemp], [ControllerData.hasDistance],
 * [ControllerData.speedKnown]) and this object is
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
 *  - **the battery.** [ru.sodovaya.volty.domain.model.BmsData.socKnown] owns
 *    state of charge, while [BmsReadings] owns pack current/power. An earlier
 *    revision said no producer could distinguish absent battery current from a
 *    genuine zero; Kelly's phase-current-only monitor disproved that claim.
 *    Those battery-path rules stay separate from motion telemetry;
 *  - **the alarm.** [ru.sodovaya.volty.domain.alert.availabilityFor] reads the
 *    same flags to decide what may be *armed*; that is a different question from
 *    what may be *drawn* (an unknown value is undrawable AND unarmable, but a
 *    known value can be drawable and still unarmable — see
 *    [ru.sodovaya.volty.domain.alert.AlertAvailability.Unknown]). Nothing here
 *    changes what the alarm sees;
 *  - **motor current and eRPM.** No producer can distinguish either from a
 *    genuine zero. Input-side battery current and distance counters DO carry
 *    flags now: Kelly's ETS monitor identifies its phase current but has no
 *    battery current, odometer or trip at all.
 */
object MotionReadings {

    /** The wheel's ground speed, or null when [ControllerData.speedKnown] is false. */
    fun speedKmh(motion: ControllerData): Float? =
        if (motion.speedKnown) motion.speedKmh else null

    /** The typed reason for an unknown speed, or null for a known value/legacy unknown. */
    fun speedUnknownReason(motion: ControllerData): SpeedUnknownReason? =
        if (motion.speedKnown) null else motion.speedUnknownReason

    /** Duty/ШИМ, or null when this firmware has never reported a PWM (`D §7.2`). */
    fun dutyPercent(motion: ControllerData): Float? =
        if (motion.hasDuty) motion.dutyPercent else null

    /** Input-side battery current, or null when the controller only reports phase current. */
    fun batteryCurrentA(motion: ControllerData): Float? =
        if (motion.hasBatteryCurrent) motion.batteryCurrentA else null

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

    /** Lifetime distance, or null when the controller does not keep an odometer. */
    fun odometerKm(motion: ControllerData): Float? =
        if (motion.hasDistance) motion.odometerKm else null

    /** Connection-session distance, or null when the controller does not keep an odometer. */
    fun tripKm(motion: ControllerData): Float? =
        if (motion.hasDistance) motion.tripKm else null

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
     * figure when the protocol keeps counters, otherwise
     * [synthesisedWhPerKm], otherwise null.
     *
     * **A measurement always wins.** A protocol that keeps counters is reporting
     * what its own coulomb counting says; an integral of `power × dt` over BLE
     * arrival gaps is a reconstruction, and preferring the reconstruction on a
     * VESC would replace a number the firmware stands behind with one we made
     * up. The synthesis is a floor under the vehicles that have nothing, not an
     * improvement on the ones that do.
     *
     * [synthesisedWhPerKm] arrives already divided
     * ([RideEnergy.synthesisedWhPerKm]) because its numerator and its divisor
     * are both windowed and must come from the same samples — a caller that
     * handed over watt-hours alone would leave this function to divide them by
     * [ControllerData.tripKm], which is a session total and therefore the wrong
     * denominator once the ring buffer starts evicting. Null when the caller has
     * nothing to offer, which is exactly the pre-existing blank — never a zero.
     *
     * The two branches are therefore **not the same average**: the measured one
     * is a session figure the firmware kept, the synthesised one is a windowed
     * figure equal to it until eviction begins. Both are honest answers to "what
     * is this ride costing"; the flag is what tells them apart.
     */
    fun sessionConsumption(motion: ControllerData, synthesisedWhPerKm: Float?): SessionConsumption? {
        sessionWhPerKm(motion)?.let { return SessionConsumption(it, synthesised = false) }
        return synthesisedWhPerKm?.let { SessionConsumption(it, synthesised = true) }
    }
}
