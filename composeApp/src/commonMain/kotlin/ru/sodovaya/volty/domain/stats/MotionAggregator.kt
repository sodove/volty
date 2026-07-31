package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Result of folding a vehicle's [ControllerState]s into one aggregate
 * [ControllerData], mirroring [ru.sodovaya.volty.domain.stats.PackAggregator]'s
 * [ru.sodovaya.volty.domain.model.VehicleData] on the battery side.
 */
data class MotionResult(val aggregate: ControllerData, val partial: Boolean)

/**
 * Derives a vehicle-level [ControllerData] from its controllers. Pure — no
 * BLE, no state, only a fallback clock read for the timestamp. All
 * multi-controller maths lives here so it can be tested without a radio.
 */
@OptIn(ExperimentalTime::class)
object MotionAggregator {

    fun build(controllers: List<ControllerState>): MotionResult = MotionResult(
        aggregate = aggregate(controllers),
        partial = controllers.isNotEmpty() && controllers.any { !it.isOnline }
    )

    fun aggregate(controllers: List<ControllerState>): ControllerData {
        val online = controllers.filter { it.isOnline }
        if (online.isEmpty()) return ControllerData(isConnected = false)

        val d = online.map { it.data }
        val labelled = online.size > 1

        // Deliberately NOT filtered by `speedKnown`, even though the value fold
        // below is: a `NONE` contributor matches neither branch, so filtering
        // the election would be a line no implementation could distinguish from
        // its absence. The two are consulted together — the value fold takes the
        // max over exactly the contributors this election is decided by.
        val speedSource = when {
            d.any { it.speedSource == SpeedSource.REPORTED } -> SpeedSource.REPORTED
            d.any { it.speedSource == SpeedSource.DERIVED } -> SpeedSource.DERIVED
            else -> SpeedSource.NONE
        }

        // The four energy counters share one flag and one filtered contributor
        // list, because they answer one question — see [ControllerData.hasEnergyCounters].
        val counting = d.measuring { it.hasEnergyCounters }

        return ControllerData(
            // Filtered, and the filter is what stops a hollow contributor
            // WINNING a maxOf: a contributor whose flag and value disagree
            // (`speedKmh = 99f` with `speedSource = NONE`) hands the vehicle a
            // speed nobody observed, which is what the alarm then compares a
            // threshold against. A speed nobody measured is published as `0f`
            // (VescValues' `derived ?: 0f`), and this filter is what keeps that
            // placeholder out of the max.
            //
            // **This fold is NOT what protects the vehicle from a signed
            // speed**, and an earlier revision of this comment implied it was.
            // Since `I` Task 10 both producers publish a MAGNITUDE at decode, so
            // no negative contributor can reach here at all — and if one ever
            // did, NEITHER outcome would be protection: with the sole measuring
            // contributor at -12 km/h the filter keeps it and the max is -12,
            // while an unfiltered fold would floor it at a hollow 0. Downstream
            // the two are near-indistinguishable — instant consumption is null
            // either way, the session peak cannot advance either way, and the
            // SPEED alarm cannot fire either way; only the number drawn differs.
            // The guarantee is the decoders' (`VescValues`' object KDoc,
            // `BegodeProtocol.speedKmh`), and it is not restatable here.
            //
            // `ifEmpty { d }` for the same reason as inputVoltageV below: with
            // nobody measuring there is nothing to prefer, `speedKnown` on the
            // aggregate already says so, and the one-controller fold stays an
            // identity even on a sample whose flag and value disagree.
            speedKmh = d.measuring { it.speedKnown }.maxOf { it.speedKmh },
            speedSource = speedSource,
            // Filtered by the same rule, and this one is named as a HAZARD in
            // `MotionAlertAvailability`'s DUTY branch: on a mixed vehicle DUTY is
            // Available because ONE controller supplies it, while the maxOf ran
            // over BOTH — so a decoder that writes a non-zero number into a
            // `dutyPercent` its protocol does not actually report would raise
            // the ШИМ alarm on a number that is not a duty measurement. The
            // filter is what makes the availability claim and the number agree.
            dutyPercent = d.measuring { it.hasDuty }.maxOf { it.dutyPercent },
            // `any`, exactly like hasMotorTemp below: one controller that
            // MEASURES duty is enough for the vehicle, because the maxOf fold
            // above already carries that controller's real reading. Folding
            // with `all` (or dropping the fold and inheriting the default)
            // would cost a mixed VESC + Begode vehicle its duty availability
            // outright the moment the wheel's truePWM latch is still open —
            // a worse bug than the unmeasured-duty one the flag exists for.
            hasDuty = d.any { it.hasDuty },
            motorCurrentA = d.sumOf { it.motorCurrentA.toDouble() }.toFloat(),
            batteryCurrentA = d.sumOf { it.batteryCurrentA.toDouble() }.toFloat(),
            // `G §9.3`: averaged over the controllers that MEASURE a voltage,
            // not over all of them. Begode is the first decoder that publishes
            // `0` meaning unknown (no cell count ⇒ no scale ⇒ no honest
            // voltage), and a head-unit row answering a 0 V rail used to be
            // averaged in as though it were a measurement — reporting two
            // thirds of a three-controller vehicle's real pack voltage.
            //
            // Falls back to averaging EVERYTHING when nobody measures, rather
            // than substituting a 0: with no observation anywhere the fold has
            // nothing to prefer, `hasInputVoltage` below already says the
            // number is not a measurement, and this keeps the one-controller
            // fold an identity even on a sample whose flag and value disagree.
            inputVoltageV = d.measuring { it.hasInputVoltage }
                .map { it.inputVoltageV }
                .average()
                .toFloat(),
            // `any`, because the field above is an AVERAGE: one controller that
            // measures the rail answers for the vehicle, and the average is
            // taken over exactly those controllers. Same shape as hasDuty.
            hasInputVoltage = d.any { it.hasInputVoltage },
            // Summed over the contributors that MEASURE a power, so the figure
            // behind a false flag is a partial of real measurements rather than
            // a partial of garbage. The flag below still folds with `all`, so
            // nothing downstream reads this partial as the vehicle's power —
            // what the filter buys is that the number and the flag stop
            // DISAGREEING: a contributor with `powerW = 4200f, hasPower = false`
            // (a combination no producer emits, which is exactly why the fixture
            // uses it) used to add 4200 W of placeholder to the total.
            //
            // Invisible to any fixture whose unmeasured fields happen to hold
            // `0f`, which every producer's do and every fixture's used to.
            powerW = d.measuring { it.hasPower }.sumOf { it.powerW.toDouble() }.toFloat(),
            // `all`, NOT `any` — and this is the rule, not an exception: **a
            // known-flag folds the way its field's arithmetic does.** `maxOf`
            // and `average` fields (duty, temperature, voltage) take `any`,
            // because one real reading is the vehicle's reading. A SUM is
            // different: a total with an unobserved term is not a measurement
            // of the whole vehicle, it is an understatement of it, and a Begode
            // with no cell count contributes a real battery current with a 0 W
            // power. Showing `—` for the vehicle's power is the honest answer;
            // showing the partial sum is the same confident zero one layer up.
            //
            // Costs nothing on a single-controller vehicle, where `all` and
            // `any` agree and the fold is the identity either way.
            hasPower = d.all { it.hasPower },
            // **This fold DESTROYS the direction, and nothing else in the app
            // does.** `eRpm` is the one field that still carries the sign of the
            // motion after `I` Task 10 made both speed producers publish a
            // magnitude — but only on a CONTROLLER sample. Unfiltered `maxOf`
            // over a signed field means one controller reversing at -12 000
            // beside anything reporting `0` (every Begode does, and so does a
            // VESC whose `GET_VALUES` has not landed) folds to `0`.
            //
            // Left as it is, deliberately: no consumer reads this for direction
            // — `ComposerDuplicates` uses `eRpm != 0f` only as a liveness
            // witness — and what a vehicle-level eRPM should mean when two
            // motors have opposite polarity is a question no producer has posed
            // yet. **A reverse indicator must read a controller sample, not
            // this.** Recorded here rather than only in a report, because the
            // claim it qualifies ("the direction is not destroyed") is stated in
            // `VescValues`' object KDoc two files away.
            eRpm = d.maxOf { it.eRpm },
            // **Unfiltered, and that is the fix rather than an omission.**
            //
            // `hasEscTemp` is a GETTER over the value (`escTempC > -50f`), not a
            // stored flag, so filtering this `maxOf` by it would be a line no
            // implementation could distinguish from its absence:
            //
            //     max(xs) > SENTINEL  ⟺  ∃x ∈ xs : x > SENTINEL
            //
            // — i.e. `maxOf` ALREADY composes with the sentinel encoding to give
            // exactly the `any` fold every other maxOf field gets, and a
            // `filter { it.hasEscTemp }.ifEmpty { d }.maxOf { … }` returns the
            // same float for **every value a producer can emit**. (Without the
            // `ifEmpty` it would throw on a vehicle where nobody has an ESC
            // sensor — strictly worse.)
            //
            // The equivalence is over producible inputs, not over the whole
            // Float domain: `NaN` breaks it, because `NaN > -50f` is false (so
            // the filter drops it) while `maxOf` propagates it. No decoder can
            // produce one — every `escTempC` on this path is a scaled i16 —
            // so the claim is stated at the strength it actually holds rather
            // than as an algebraic identity.
            //
            // What makes that sound is the SENTINEL, which is why both producers
            // are careful to write one: `BegodeProtocol.NO_TEMP_SENSOR_C` is
            // -100 °C and `VescValues` passes VESC's own implausibly-low reading
            // through. A future decoder that left this at its `0f` default would
            // CLAIM a sensor — but it would claim one on its own
            // `ControllerData` too, before any fold sees it, so the defect would
            // be that decoder's and not this one's.
            //
            // Pinned by `the esc temperature fold carries the sentinel through
            // untouched`, which fails for `average`, `minOf`, `first` and for an
            // `ifEmpty`-less filter.
            escTempC = d.maxOf { it.escTempC },
            // Filtered, UNLIKE escTempC above, and the difference is the whole
            // reason `hasMotorTemp` is a stored flag: it is independent of the
            // value, so a contributor can carry `motorTempC = 0f` (the field's
            // own default, beside `hasMotorTemp = false`, also the default) and
            // beat a real winter reading of -8 °C in an unfiltered `maxOf`. The
            // aggregate would then claim a sensor — correctly, one controller
            // has one — while reporting the OTHER controller's placeholder.
            motorTempC = d.measuring { it.hasMotorTemp }.maxOf { it.motorTempC },
            hasMotorTemp = d.any { it.hasMotorTemp },
            // `maxOf`, and it MUST NOT become a sum — this is the one fold in
            // this object where the obvious "totals are sums" instinct is
            // actively wrong.
            //
            // These come from `COMM_GET_VALUES_SETUP`, which since `I` Task 4 is
            // asked of **every** controller — so a two-uBox vehicle hands this
            // fold two readings of the same journey. `maxOf` returns it once; a
            // sum would return twice the distance the vehicle has ever
            // travelled, and the trip with it.
            //
            // **The reason is the ground, not the firmware.** This comment used
            // to say `mc_interface_get_setup_values()` "has already summed
            // across every CAN node and divided the tachometer by the number of
            // VESCs" — that is the firmware inverted, and no such division
            // exists anywhere. The SETUP frame's two distance fields are
            // `mc_interface_get_distance()` / `_abs()` (`commands.c:853,856`),
            // which scale this node's OWN tachometer by its OWN wheel config
            // (`mc_interface.c:1624-1643`); `mc_interface_get_setup_values()`
            // CAN-sums the currents and the four Ah/Wh counters and never
            // touches a tachometer at all (`mc_interface.c:1651-1688`).
            //
            // `maxOf` survives the correction, on a stronger reason: two
            // controllers bolted to one vehicle travel the same ground, so their
            // LOCAL odometers agree, and the max of equal numbers is that
            // number. The old reason needed the firmware to be doing something
            // clever; this one only needs the wheels to be attached to the same
            // frame.
            //
            // Pinned by `MotionAggregatorTest.one vehicle odometer reported by
            // two controllers is not doubled`. See `VescGatewayProtocol`'s
            // "Why the SETUP frame is an OVERLAY" section for the wire detail.
            odometerKm = d.maxOf { it.odometerKm },
            tripKm = d.maxOf { it.tripKm },
            // Summed over the contributors that KEEP counters, for the same
            // reason as powerW above — one filtered list ([counting]) because
            // one flag answers for all four.
            //
            // These four ARE CAN-summed on the wire (`mc_interface.c:1655-1679`
            // — unlike the distances above, which are not), so whether the sum
            // is safe depends on WHICH protocol produced the contributor, and
            // the answer differs:
            //
            //  - **`VescGatewayProtocol` — safe, by a decision taken elsewhere.**
            //    `publishController` copies only speed, its source, odometer,
            //    trip and battery level out of the SETUP overlay, so the counters
            //    it publishes come from `GET_VALUES` and are genuinely per-node.
            //    If anyone ever adds them to that overlay, THIS fold starts
            //    double-counting — the two decisions are coupled, and this is
            //    the only place that says so.
            //  - **`VescProtocol` — safe only because of the topology today.**
            //    It publishes the whole `decodeSetupValues` result
            //    (`VescProtocol.kt:164`), CAN-summed counters included. One such
            //    link folds to itself, so nothing is wrong on any vehicle the
            //    composer can currently build. Two independent `VescProtocol`
            //    links whose controllers share one CAN bus would each report the
            //    bus-wide total and this fold would sum them again. Nothing
            //    forbids that config; it is a hazard on the ledger, not a fixed
            //    property, and it is the reason the bullet above is scoped to
            //    one protocol rather than stated of the fold.
            consumedAh = counting.sumOf { it.consumedAh.toDouble() }.toFloat(),
            consumedWh = counting.sumOf { it.consumedWh.toDouble() }.toFloat(),
            regenAh = counting.sumOf { it.regenAh.toDouble() }.toFloat(),
            regenWh = counting.sumOf { it.regenWh.toDouble() }.toFloat(),
            // `all`, for the same reason as hasPower: the four counters above
            // are sums.
            hasEnergyCounters = d.all { it.hasEnergyCounters },
            // `A-foundation`'s "the aggregator silently drops
            // batteryLevelFraction": this field was never copied, so
            // `activeMotion` published null for it whatever the controller
            // reported. Harmless only because both VESC decoders read it
            // upstream of the fold — it became a silent null the moment
            // anything read it off the aggregate.
            //
            // Averaged over the controllers that HAVE one, which is the same
            // fold inputVoltageV gets and for the same reason: every controller
            // on one vehicle sees one pack, so a level any of them computed is
            // the vehicle's level. Already nullable, so it needs no flag of its
            // own — it is the shape the rest of this contract has to fake.
            //
            // Same warning as odometerKm above, and the same wire reason: since
            // `I` Task 4 a gateway asks `GET_VALUES_SETUP` of every controller,
            // so several controllers now report the SAME pack's level from the
            // SAME setup frame. An average of equal numbers is that number; a
            // sum would read 168 % on a two-uBox scooter.
            batteryLevelFraction = d.mapNotNull { it.batteryLevelFraction }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat(),
            faults = online.flatMap { s ->
                s.data.faults.map { if (labelled) "${s.controller.label}: $it" else it }
            },
            isConnected = true,
            timestamp = d.maxOfOrNull { it.timestamp } ?: Clock.System.now()
        )
    }

    /**
     * The contributors that MEASURE the quantity a fold is about — or, when
     * none of them do, all of them.
     *
     * **The unknown-vs-zero contract, applied at the fold.** Every value in
     * [ControllerData] is a non-nullable magnitude, so a quantity a controller
     * cannot answer is published as some placeholder (`0f`, or a sentinel) with
     * a known-flag beside it saying so ([ru.sodovaya.volty.domain.stats.MotionReadings]
     * is the whole rule). Folding the placeholder in as though it were a
     * reading is how that flag stopped being load-bearing: an average of a real
     * 78 V and a phantom 0 V is 39 V, a `maxOf` over a real -12 km/h and a
     * hollow 0 is 0, and a sum of a real 4000 W and a placeholder 4200 W is a
     * number describing nothing.
     *
     * **The `ifEmpty` is not a fallback to zero, and that is deliberate.** With
     * nobody measuring, the fold has nothing to prefer: the flag beside the
     * result already says the number is not a measurement, and keeping the
     * contributors makes the one-controller fold an IDENTITY — including on a
     * sample whose flag and value disagree, which is what
     * `KableBmsRepositoryBegodeFunnelTest` relies on. Substituting a `0` here
     * would be the confident zero this contract exists to remove, re-introduced
     * one layer down.
     *
     * Not applied to [ControllerData.escTempC] — see the argument at that fold —
     * nor to the currents, eRPM, odometer or trip, which have no flag because no
     * producer can tell an unreported one from a genuine zero.
     */
    private fun List<ControllerData>.measuring(
        knows: (ControllerData) -> Boolean
    ): List<ControllerData> = filter(knows).ifEmpty { this }
}
