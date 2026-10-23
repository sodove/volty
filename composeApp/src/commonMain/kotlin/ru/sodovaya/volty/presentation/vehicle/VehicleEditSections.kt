package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerType

/**
 * The pages of the vehicle editor's hub. The hub itself is "no section open"; every page edits
 * the SAME [VehicleDraft]/[VehicleEditComponent.State] the hub does, so leaving a page writes
 * nothing and loses nothing — only Save persists.
 */
enum class VehicleEditSection { IDENTITY, CONTROLLER, BATTERY, MOTOR, LAYOUT, DASHBOARD, SPEED_VOLUME }

/** Where the vehicle's battery comes from, as the hub row and the battery page name it. */
enum class BatterySource {
    /** A BMS on its own BLE link. */
    OWN_BMS,

    /** No pack: a controller stands in for the battery (a wheel without a smart BMS). */
    FROM_CONTROLLER,

    /** Every pack is reached through a controller's own address - one device serving both. */
    DEVICE_IS_BOTH,
    NONE
}

/**
 * [cellCount] is the S the page shows; [cellCountLearned] is true only when it came from the
 * wheel's own proof and not from anything the rider (or an earlier auto-fill) wrote down.
 */
data class BatterySummary(
    val source: BatterySource,
    val chemistry: Chemistry,
    val cellCount: Int?,
    val cellCountLearned: Boolean
)

/**
 * The battery in one line's worth of facts.
 *
 * Source precedence: any pack on a link of its own is [BatterySource.OWN_BMS]; packs that all sit
 * on a controller's address are [BatterySource.DEVICE_IS_BOTH]; with no packs, a controller whose
 * derived-battery switch resolves ON is [BatterySource.FROM_CONTROLLER]; else [BatterySource.NONE].
 *
 * The count follows `Controller.derivedCellCount`'s rule: a value the rider typed outranks one the
 * wheel inferred, and only an inferred one is marked learned. [chemistry] is a parameter because
 * it lives on the vehicle, not on the draft. [NONE] carries no count at all.
 */
fun batterySummary(draft: VehicleDraft, chemistry: Chemistry, learnedCellCount: Int?): BatterySummary {
    val source = when {
        draft.packs.any { !draft.isHostedPack(it) } -> BatterySource.OWN_BMS
        draft.packs.isNotEmpty() -> BatterySource.DEVICE_IS_BOTH
        draft.controllers.any { draft.resolvedDerivedBattery(it) } -> BatterySource.FROM_CONTROLLER
        else -> BatterySource.NONE
    }
    val typed: Int? = when (source) {
        BatterySource.OWN_BMS ->
            draft.packs.firstOrNull { !draft.isHostedPack(it) }?.cellCount
        BatterySource.DEVICE_IS_BOTH -> draft.packs.firstOrNull()?.cellCount
        BatterySource.FROM_CONTROLLER ->
            draft.controllers.firstOrNull { draft.resolvedDerivedBattery(it) }?.derivedCellCount
        BatterySource.NONE -> null
    }
    return when {
        source == BatterySource.NONE -> BatterySummary(source, chemistry, null, false)
        typed != null -> BatterySummary(source, chemistry, typed, false)
        learnedCellCount != null -> BatterySummary(source, chemistry, learnedCellCount, true)
        else -> BatterySummary(source, chemistry, null, false)
    }
}

/** The first controller's type and link, and how many more there are. */
data class ControllerSummary(val type: ControllerType, val address: String, val others: Int)

fun controllerSummary(draft: VehicleDraft): ControllerSummary? {
    val first = draft.controllers.firstOrNull() ?: return null
    return ControllerSummary(first.controllerType, first.address, draft.controllers.size - 1)
}

/** Pole pairs and wheel diameter of the first controller; null = not set (a diameter of 0 is unset too). */
data class MotorSummary(val polePairs: Int?, val wheelDiameterMm: Int?)

fun motorSummary(draft: VehicleDraft): MotorSummary {
    val motor = draft.controllers.firstOrNull()?.motor ?: return MotorSummary(null, null)
    return MotorSummary(motor.polePairs, motor.wheelDiameterMm?.takeIf { it > 0 })
}
