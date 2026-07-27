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

        val speedSource = when {
            d.any { it.speedSource == SpeedSource.REPORTED } -> SpeedSource.REPORTED
            d.any { it.speedSource == SpeedSource.DERIVED } -> SpeedSource.DERIVED
            else -> SpeedSource.NONE
        }

        return ControllerData(
            speedKmh = d.maxOf { it.speedKmh },
            speedSource = speedSource,
            dutyPercent = d.maxOf { it.dutyPercent },
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
            inputVoltageV = d.filter { it.hasInputVoltage }
                .ifEmpty { d }
                .map { it.inputVoltageV }
                .average()
                .toFloat(),
            // `any`, because the field above is an AVERAGE: one controller that
            // measures the rail answers for the vehicle, and the average is
            // taken over exactly those controllers. Same shape as hasDuty.
            hasInputVoltage = d.any { it.hasInputVoltage },
            powerW = d.sumOf { it.powerW.toDouble() }.toFloat(),
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
            eRpm = d.maxOf { it.eRpm },
            escTempC = d.maxOf { it.escTempC },
            motorTempC = d.maxOf { it.motorTempC },
            hasMotorTemp = d.any { it.hasMotorTemp },
            odometerKm = d.maxOf { it.odometerKm },
            tripKm = d.maxOf { it.tripKm },
            consumedAh = d.sumOf { it.consumedAh.toDouble() }.toFloat(),
            consumedWh = d.sumOf { it.consumedWh.toDouble() }.toFloat(),
            regenAh = d.sumOf { it.regenAh.toDouble() }.toFloat(),
            regenWh = d.sumOf { it.regenWh.toDouble() }.toFloat(),
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
}
