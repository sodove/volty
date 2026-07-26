package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryController

/**
 * Why a motion alert cannot be armed on this vehicle.
 *
 * A *domain* value, not a string: the rider-facing wording lives in the string
 * catalogue with every other translated string
 * (`presentation/common/EnumLabels.kt` → `alertUnavailableReasonLabel`). Domain
 * must not import Compose resources — nothing under `domain/` does today — and a
 * Russian literal baked into a pure module would be untranslatable, untestable
 * without string matching, and invisible to `values/` vs `values-ru/`.
 *
 * [ControllerReportsNoDuty] carries the [ControllerType] rather than a
 * pre-rendered name so the sentence names the actual hardware. Hard-coding
 * "Kelly" in the string would be wrong the moment a second protocol turns out
 * not to report duty (`E §9.3` is exactly that open question for FarDriver).
 */
sealed interface AlertUnavailableReason {
    /** The vehicle has no motor controller at all, so there is no motion telemetry. */
    data object NoController : AlertUnavailableReason

    /** This controller protocol does not report duty/ШИМ at all (`H §7` for Kelly KLS). */
    data class ControllerReportsNoDuty(val type: ControllerType) : AlertUnavailableReason

    /** No motor thermistor is wired on this controller ([ControllerData.hasMotorTemp]). */
    data object NoMotorTempSensor : AlertUnavailableReason

    /** No ESC/MOSFET temperature sensor on this controller ([ControllerData.hasEscTemp]). */
    data object NoEscTempSensor : AlertUnavailableReason

    /** Speed is neither reported nor derivable ([SpeedSource.NONE]). */
    data object NoSpeedSource : AlertUnavailableReason
}

/**
 * Whether a [MotionAlertKind] can be armed on a vehicle — **a fact, not a
 * preference** (`F §10`). No setting, default, restored backup or code path may
 * arm a kind that is not [Available]; see [armedRules], which is the only gate
 * the alarm engine goes through.
 *
 * Three states, because availability is decided in two layers and the rider
 * opens the settings screen *while disconnected*, when the second layer does not
 * exist yet:
 *
 *  1. **static** — from [ControllerType] alone, known without ever connecting;
 *  2. **observed** — from a live [ControllerData] sample: is the sensor wired?
 *
 * Collapsing this into a Boolean forces a lie on a never-connected vehicle:
 * either "the sensor is missing" (unearned) or "go ahead, arm it" (unfounded).
 */
sealed interface AlertAvailability {
    /** The hardware supplies this metric. Arm it. */
    data object Available : AlertAvailability

    /** The hardware provably cannot supply it. Shown greyed, [reason] stated in words. */
    data class Unavailable(val reason: AlertUnavailableReason) : AlertAvailability

    /**
     * Layer 1 permits this kind, but no sample has been seen, so whether the
     * sensor is actually wired is not yet known.
     *
     * **At arming time Unknown behaves as not-armable** — no sample means there
     * is no value to compare a threshold against, so an alarm could not fire
     * anyway, and [armedRules] drops it exactly like [Unavailable].
     *
     * **In the UI it must NEVER read as "your hardware lacks this sensor".**
     * That is a claim we have not earned: the rider has simply never connected.
     * The wording belongs to `alertAvailabilityNote` and says so — "no data yet,
     * connect to check" — and the row becomes [Available] or [Unavailable] on the
     * first sample without the rider touching anything.
     */
    data object Unknown : AlertAvailability
}

/**
 * True only for [AlertAvailability.Available]. [AlertAvailability.Unknown] is
 * deliberately false — see its doc for why "not armable" and "no sensor" are
 * different statements.
 */
val AlertAvailability.isArmable: Boolean get() = this is AlertAvailability.Available

/**
 * Whether this controller protocol reports duty/ШИМ at all — a permanent
 * property of the protocol, observable without connecting.
 *
 * Exhaustive with no `else`: a new [ControllerType] must answer this at compile
 * time rather than inherit an optimistic default, because the default silently
 * offers riders an alarm their hardware can never raise.
 *
 * - VESC — duty is a first-class field of `COMM_GET_VALUES`.
 * - Begode — the 20-byte frames carry PWM/duty, and `D §3` makes a trustworthy
 *   duty the headline requirement of that part.
 * - Kelly KLS — **does not report duty** (`H §7`, `H` table row "no duty").
 * - FarDriver — `E §9.3` is still open ("is a true PWM/duty exposed?"). Left
 *   `true` because no FarDriver motion decoder exists yet, so no FarDriver
 *   sample can exist and every FarDriver kind resolves to
 *   [AlertAvailability.Unknown] regardless. **Part E must flip this to `false`
 *   if the answer is no** — that is the one line to change, and this test file
 *   pins the current answer so the change is deliberate.
 */
internal val ControllerType.reportsDuty: Boolean
    get() = when (this) {
        ControllerType.VESC -> true
        ControllerType.BEGODE -> true
        ControllerType.FARDRIVER -> true
        ControllerType.KELLY -> false
    }

/**
 * Which motion alerts this vehicle's hardware can supply, for every
 * [MotionAlertKind] — including the ones it cannot, which the settings screen
 * still shows, greyed, **with the reason in words** (`F §10`). An absent row
 * leaves the rider wondering; a greyed row with an explanation teaches them what
 * their hardware measures.
 *
 * [latestMotion] is the vehicle-level motion aggregate
 * ([ru.sodovaya.volty.domain.repository.BmsRepository.activeMotion]) — **or null
 * when no sample has ever been observed**, which is the normal case on the
 * settings screen while disconnected.
 *
 * **Callers must pass null rather than a placeholder.** `activeMotion` emits a
 * default `ControllerData()` when nothing is connected, and that placeholder
 * carries `hasMotorTemp = false` and `speedSource = NONE` — feeding it in as if
 * it were evidence would tell a rider who has never connected that their motor
 * thermistor is missing. Task 5 and the Part G2 screen must gate on "have we
 * actually received a sample" before calling.
 *
 * The result always has an entry for every kind, in enum order, so the UI can
 * render the full list without deciding anything itself.
 */
fun availabilityFor(
    vehicle: Vehicle,
    latestMotion: ControllerData?
): Map<MotionAlertKind, AlertAvailability> {
    val controllers = vehicle.controllers
    if (controllers.isEmpty()) {
        // No motion source at all: not a sensor question, and no sample could
        // ever change it. Unavailable for every kind, never Unknown.
        return MotionAlertKind.entries.associateWith {
            AlertAvailability.Unavailable(AlertUnavailableReason.NoController)
        }
    }
    return MotionAlertKind.entries.associateWith { kind ->
        availabilityOf(kind, controllers, vehicle.primaryController, latestMotion)
    }
}

private fun availabilityOf(
    kind: MotionAlertKind,
    controllers: List<Controller>,
    primary: Controller?,
    latestMotion: ControllerData?
): AlertAvailability = when (kind) {
    // Duty is settled by the protocol alone — an ESC computes it, there is no
    // sensor to be missing — so it never resolves to Unknown. A vehicle whose
    // controllers ALL lack duty cannot have it; one controller that reports duty
    // is enough, because the vehicle-level aggregate folds them together.
    MotionAlertKind.DUTY ->
        if (controllers.any { it.controllerType.reportsDuty }) {
            AlertAvailability.Available
        } else {
            AlertAvailability.Unavailable(
                AlertUnavailableReason.ControllerReportsNoDuty(
                    (primary ?: controllers.first()).controllerType
                )
            )
        }

    // The rest are sensor questions, answerable only from a live sample.
    MotionAlertKind.SPEED -> observed(latestMotion, AlertUnavailableReason.NoSpeedSource) {
        it.speedSource != SpeedSource.NONE
    }
    MotionAlertKind.MOTOR_TEMP -> observed(latestMotion, AlertUnavailableReason.NoMotorTempSensor) {
        it.hasMotorTemp
    }
    MotionAlertKind.ESC_TEMP -> observed(latestMotion, AlertUnavailableReason.NoEscTempSensor) {
        it.hasEscTemp
    }
}

/** Layer 2: with no sample there is nothing to observe, so the answer is Unknown — not "missing". */
private inline fun observed(
    latestMotion: ControllerData?,
    reason: AlertUnavailableReason,
    present: (ControllerData) -> Boolean
): AlertAvailability = when {
    latestMotion == null -> AlertAvailability.Unknown
    present(latestMotion) -> AlertAvailability.Available
    else -> AlertAvailability.Unavailable(reason)
}

/**
 * The rules an alarm engine may actually arm — **the single gate** through which
 * rider configuration reaches Task 5's alarm engine and Task 6's motion
 * one-shots.
 *
 * Two things are dropped, and the first is the load-bearing one:
 *  - any kind that is not [AlertAvailability.isArmable]. A config *can* carry
 *    enabled levels for an unavailable kind — the rider tuned it, then swapped
 *    to hardware without the sensor, or a backup was restored onto a different
 *    vehicle. `F §10` says such an alert must be impossible to arm, so it is
 *    dropped here rather than trusted to a downstream `if`;
 *  - any [AlertRule.isOff] rule, which the rider switched off and which has no
 *    levels to compare against anyway.
 *
 * Levels are otherwise passed through untouched — muted levels stay in place so
 * the engine can skip them without promoting the ones above (`F §10.2`).
 */
fun armedRules(
    configured: List<AlertRule>,
    availability: Map<MotionAlertKind, AlertAvailability>
): List<AlertRule> = configured.filter { rule ->
    !rule.isOff && availability[rule.kind]?.isArmable == true
}

/** [armedRules] against this vehicle's live availability. See [availabilityFor] on [latestMotion]. */
fun armedRules(
    vehicle: Vehicle,
    latestMotion: ControllerData?,
    configured: List<AlertRule>
): List<AlertRule> = armedRules(configured, availabilityFor(vehicle, latestMotion))
