package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Builds a controller-only vehicle: zero packs, one [Controller] at index 0.
 * Sibling of [singlePackVehicle] — same calling conventions (parameter
 * naming, `createdAt` threaded straight through, the sole source named after
 * the vehicle) but for the controller shape instead of the single-pack one.
 *
 * [Controller.canId] is always null here — `planLinks` rejects CAN-forwarded
 * sources until Part C, so this builder must never set one.
 *
 * [Controller.providesDerivedBattery] is unconditionally `true` and
 * deliberately NOT a parameter: a vehicle built by this function has no pack,
 * so its controller is the only possible battery source
 * (`G-vehicle-composer.md §6`). That is derivable from the shape this builder
 * always produces, not a choice G1 callers make — exposing it as a parameter
 * now would be guessing at the editable toggle Part G2 adds once vehicles can
 * mix packs and controllers.
 */
@OptIn(ExperimentalTime::class)
fun controllerVehicle(
    id: String,
    name: String,
    iconKey: String,
    controllerType: ControllerType,
    address: String,
    chemistry: Chemistry,
    createdAt: Instant,
    motor: MotorConfig = MotorConfig(),
): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = emptyList(),
    controllers = listOf(
        Controller(
            index = 0,
            label = name,
            controllerType = controllerType,
            address = address,
            motor = motor,
            providesDerivedBattery = true
        )
    ),
    chemistry = chemistry,
    createdAt = createdAt,
    dashboardStyle = null,
    secondaryGauge = SecondaryGauge.DUTY
)
