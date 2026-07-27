package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import kotlin.jvm.JvmInline

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

    /**
     * This controller protocol does not report duty/ШИМ at all (`H §7` for
     * Kelly KLS) — **layer 1, a permanent property of the protocol**, reached
     * only when NO controller on the vehicle reports duty. That is what makes
     * naming [type] sound here: with every controller equally unable, the
     * lowest-indexed one is a fair representative.
     *
     * Not to be used for the observed layer — see [ControllerHasNotReportedDuty].
     */
    data class ControllerReportsNoDuty(val type: ControllerType) : AlertUnavailableReason

    /**
     * Duty is a fact this protocol *can* report, but nothing has yet — **layer
     * 2, from a live [ControllerData.hasDuty]**. Begode latches WheelLog's
     * `truePWM` on the first non-zero PWM and publishes 0 until then, which is
     * indistinguishable from a genuine 0 %, so the alarm must not arm against
     * it (`D §7.2`).
     *
     * **Deliberately carries no [ControllerType], unlike its layer-1 sibling.**
     * The vehicle-level aggregate folds `hasDuty` with `any` over the ONLINE
     * controllers, so this is reachable on a vehicle whose other controller
     * demonstrably does report duty and merely happens to be offline: a VESC at
     * index 0 beside a Begode at index 1, VESC offline, wheel's latch still
     * open. Naming "the lowest-indexed controller" there would tell the rider
     * *"VESC controllers do not report duty"* — false, and un-actionable, which
     * is the opposite of what `F §10`'s greyed row with a reason is for. There
     * is no controller this reason could honestly name, so it names none.
     *
     * Distinct from [ControllerReportsNoDuty] for a second reason too: this one
     * may stop being true on the very next frame. Its wording says "yet"; the
     * other's states a permanent hardware fact.
     */
    data object ControllerHasNotReportedDuty : AlertUnavailableReason

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
     * hardware actually supplies it is not yet known — is the sensor wired, and
     * (since Part D Task 4) does this firmware report a duty at all.
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
 * **May the alarm engine fire this kind?** True only for
 * [AlertAvailability.Available]. [AlertAvailability.Unknown] is deliberately
 * false — see its doc for why "not armable" and "no sensor" are different
 * statements.
 *
 * This is the *engine's* question, not the settings screen's. A screen that
 * greys a row on `!isArmable` makes every motion threshold uneditable while
 * disconnected, which is the normal state when a rider opens settings — use
 * [isConfigurable] there.
 */
val AlertAvailability.isArmable: Boolean get() = this is AlertAvailability.Available

/**
 * **May the rider edit this kind's thresholds?** True for
 * [AlertAvailability.Available] *and* [AlertAvailability.Unknown], false only
 * for [AlertAvailability.Unavailable].
 *
 * The two predicates deliberately disagree on [AlertAvailability.Unknown], and
 * that disagreement is the whole point of having a third state:
 *
 *  - **editing is allowed** — the rider opens settings while disconnected, which
 *    is the normal case. Refusing the edit would claim their hardware lacks a
 *    sensor we have never looked for, and would leave the thresholds
 *    permanently untunable on a phone that is never connected while parked;
 *  - **arming is not** — there is no sample, so there is no value to compare a
 *    threshold against, and nothing could fire anyway.
 *
 * `F §10`'s "impossible to arm" is enforced by [isArmable] and [armedRules]
 * alone. Editability is a UI affordance and must never be used as the alarm
 * gate: a kind can be configurable and still, correctly, never sound.
 */
val AlertAvailability.isConfigurable: Boolean get() = this !is AlertAvailability.Unavailable

/**
 * Whether this controller protocol reports duty/ШИМ at all — a permanent
 * property of the protocol, observable without connecting.
 *
 * **Layer 1 only.** A `true` here means the protocol CAN report duty, not that
 * a given unit does: [ControllerData.hasDuty] is the observed second layer, and
 * [availabilityFor] requires both. Keep this answering the protocol question —
 * a firmware that turns out not to fill the field in is the sample's business,
 * not this table's.
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
 * **A disconnected placeholder is not evidence, and this function enforces
 * that.** `activeMotion` is a non-nullable flow that emits a default
 * `ControllerData()` whenever nothing is connected, and that placeholder carries
 * `hasMotorTemp = false`, `speedSource = NONE`, `escTempC = 0f` and
 * `hasDuty = true` — taken as evidence it would tell a rider who has never
 * connected both that their motor thermistor is missing *and* that their ESC
 * sensor and their duty are fine. So a sample with [ControllerData.isConnected]
 * false is discarded here and treated exactly like null.
 *
 * That guard is sound rather than a lucky proxy:
 * [ru.sodovaya.volty.domain.stats.MotionAggregator.aggregate] is the only
 * producer of the vehicle-level aggregate and sets `isConnected = true` if and
 * only if at least one controller is online, and every reset path in
 * `KableBmsRepository` writes the default `false`. `isConnected` therefore *is*
 * the "this is a real observation" discriminator.
 *
 * Callers should still prefer passing null (or a cached last-good sample) rather
 * than relying on the guard: a cached sample keeps `isConnected = true`, so
 * sensor knowledge survives the ride ending and the settings screen stays stable
 * instead of reverting to [AlertAvailability.Unknown] the moment the link drops.
 * A caller that does not bother degrades to Unknown, which is merely uninformed
 * rather than wrong, and is arming-neutral.
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
    val observedSample = latestMotion?.takeIf { it.isConnected }
    return MotionAlertKind.entries.associateWith { kind ->
        availabilityOf(kind, controllers, observedSample)
    }
}

private fun availabilityOf(
    kind: MotionAlertKind,
    controllers: List<Controller>,
    latestMotion: ControllerData?
): AlertAvailability = when (kind) {
    // Duty is decided in BOTH layers, like every other kind — the difference
    // is only that its layer 1 can rule it out on its own.
    //
    // Layer 1, the protocol: a vehicle whose controllers ALL lack duty cannot
    // have it, and no sample can rescue that (a Kelly's `dutyPercent` field is
    // not a duty measurement whatever arrives in it). One controller that
    // reports duty is enough, because the vehicle-level aggregate folds them
    // together.
    //
    // Layer 2, the sample (`D §7.2`, added by Part D Task 4): "this protocol
    // reports duty" is not the same statement as "this wheel's firmware fills
    // the field in". Begode latches WheelLog's `truePWM` on the first non-zero
    // reading and publishes 0 until then — indistinguishable from a genuine
    // 0 % — so a firmware that never reports PWM would leave the ШИМ alarm
    // displayed as armed and permanently unable to fire. ControllerData.hasDuty
    // is what tells the two apart; it defaults to true, so every other decoder
    // reaches Available exactly as before.
    //
    // No sample ⇒ Unknown, never Unavailable, for the same reason as the sensor
    // kinds: the rider opening settings while disconnected must keep their
    // thresholds editable (isConfigurable) and must never be told their
    // hardware lacks something we have not looked for.
    //
    // HAZARD for Part H (and any future non-duty decoder): MotionAggregator
    // folds duty as `maxOf { it.dutyPercent }`. On a mixed VESC + Kelly vehicle
    // DUTY is Available — correctly, the VESC supplies it — and the aggregate
    // takes the max across BOTH controllers. A Kelly decoder that writes a
    // non-zero `dutyPercent` into a field the protocol does not actually report
    // would therefore raise the alarm on a number that is not a duty
    // measurement. `H §7`'s table already says `dutyPercent = 0`; keep it there.
    MotionAlertKind.DUTY ->
        if (!controllers.any { it.controllerType.reportsDuty }) {
            AlertAvailability.Unavailable(
                AlertUnavailableReason.ControllerReportsNoDuty(
                    // Same "lowest index wins" rule as Vehicle.primaryController,
                    // spelled out because that helper's nullable type does not
                    // reflect the non-emptiness already established above — and a
                    // fallback elvis here would imply a nullability that cannot
                    // occur. Sound ONLY here: this branch is reached when every
                    // controller is equally unable, so any of them represents the
                    // vehicle. The observed branch below must NOT name one — see
                    // [AlertUnavailableReason.ControllerHasNotReportedDuty].
                    controllers.minBy { it.index }.controllerType
                )
            )
        } else {
            observed(latestMotion, AlertUnavailableReason.ControllerHasNotReportedDuty) {
                it.hasDuty
            }
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
 * Rules that have been through [armedRules] — **the only shape an alarm engine
 * may accept**.
 *
 * A distinct type rather than a bare `List<AlertRule>` because `F §10` makes a
 * claim about *reachability*: an unavailable alert must be impossible to arm.
 * Raw rider config and gated config have identical structure, so a
 * `List<AlertRule>` parameter on Task 5's engine would accept either and the
 * gate would be enforced by nothing but convention and code review. With this
 * wrapper, handing the engine an ungated config does not compile.
 *
 * Zero runtime cost — it erases to the list it wraps.
 */
@JvmInline
value class ArmedRules(val rules: List<AlertRule>) {
    val isEmpty: Boolean get() = rules.isEmpty()

    companion object {
        /** Nothing may sound — a vehicle with no controllers, or nothing configured. */
        val NONE: ArmedRules = ArmedRules(emptyList())
    }
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
 *    dropped here rather than trusted to a downstream `if`. Note this also drops
 *    [AlertAvailability.Unknown], which stays *editable* ([isConfigurable]) but
 *    never armed;
 *  - any [AlertRule.isOff] rule, which the rider switched off and which has no
 *    levels to compare against anyway.
 *
 * A kind missing from [availability] is dropped too: a gate that was never
 * evaluated stays shut. [availabilityFor] always populates every kind, so this
 * only bites a caller assembling a partial map by hand.
 *
 * Levels are otherwise passed through untouched — muted levels stay in place so
 * the engine can skip them without promoting the ones above (`F §10.2`).
 */
fun armedRules(
    configured: List<AlertRule>,
    availability: Map<MotionAlertKind, AlertAvailability>
): ArmedRules = ArmedRules(
    configured.filter { rule ->
        !rule.isOff && availability[rule.kind]?.isArmable == true
    }
)

/** [armedRules] against this vehicle's live availability. See [availabilityFor] on [latestMotion]. */
fun armedRules(
    vehicle: Vehicle,
    latestMotion: ControllerData?,
    configured: List<AlertRule>
): ArmedRules = armedRules(configured, availabilityFor(vehicle, latestMotion))
