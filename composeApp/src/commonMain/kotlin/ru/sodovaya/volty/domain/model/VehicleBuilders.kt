package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * THE vehicle a Controller pick creates — dispatched on [controllerType],
 * because the right SHAPE is not the same for every controller and the picker
 * is not the layer that should know which.
 *
 * Two shapes, one rule: a vehicle must carry a stored [Pack] exactly when its
 * controller's own protocol decodes real battery packs off the same link.
 *
 *  - **[ControllerType.BEGODE] → [wheelVehicle]** — one controller AND one
 *    stored Begode pack at one address (`D §4`). A wheel is not a controller
 *    that *derives* a battery from its rail voltage; it multiplexes a genuine
 *    two-branch battery stream over the same 20-byte frames, which
 *    `BegodeProtocol` already decodes as packs. Building it pack-less made the
 *    link own only the two slots `planLinkPacks` synthesises, and those are
 *    never persisted and never carry a cell count — so `createProtocol`'s
 *    lookup found none, `inputVoltageV` stayed 0, and the Ride dashboard read a
 *    confident **"0.0 kW"** and "0.0 Wh/km" for the life of the vehicle, with
 *    no screen in the app able to repair it. With a real pack the wheel takes
 *    exactly the route a Begode configured as a BATTERY has always taken:
 *    `packs[0].cellCount` gets filled in either of two ways (Task 3) — the
 *    cell-count auto-fill (`KableBmsRepository.maybePersistCellCount`) once
 *    the wheel's own smart-BMS cells confirm it, or the rider's own typed
 *    value on a wheel with no smart BMS, which sends no cell frames and so
 *    can never be auto-filled at all — and the next connect scales the
 *    67.2 V-referenced rail voltage with whichever supplied it.
 *  - **every other type → [controllerVehicle]** — zero packs, the controller
 *    backing a DERIVED battery, which is what a VESC actually does.
 *
 * Exhaustive with no `else`: a new [ControllerType] must decide which shape it
 * is at compile time rather than silently inheriting the pack-less one.
 */
@OptIn(ExperimentalTime::class)
fun pickedControllerVehicle(
    id: String,
    name: String,
    iconKey: String,
    controllerType: ControllerType,
    address: String,
    chemistry: Chemistry,
    createdAt: Instant,
    motor: MotorConfig = MotorConfig(),
): Vehicle = when (controllerType) {
    ControllerType.BEGODE -> wheelVehicle(
        id = id,
        name = name,
        iconKey = iconKey,
        address = address,
        chemistry = chemistry,
        createdAt = createdAt,
        motor = motor
    )
    ControllerType.VESC, ControllerType.FARDRIVER, ControllerType.KELLY,
    ControllerType.NINEBOT, ControllerType.NINEBOT_LEGACY,
    ControllerType.KINGSONG, ControllerType.INMOTION, ControllerType.VETERAN -> controllerVehicle(
        id = id,
        name = name,
        iconKey = iconKey,
        controllerType = controllerType,
        address = address,
        chemistry = chemistry,
        createdAt = createdAt,
        motor = motor
    )
}

/**
 * Builds the electric-unicycle shape of `D §4`: ONE link owning
 * `controllers = [0]` and a stored Begode pack at index 0, both at the wheel's
 * single BLE address, because a wheel multiplexes its motherboard stream and
 * its battery over one connection.
 *
 * Only ONE pack is stored even though `BegodeProtocol.packCount` is 2, and that
 * is deliberate rather than an omission: it is byte-for-byte what the BATTERY
 * picker has always created for a Begode (`singlePackVehicle`), and
 * `planLinkPacks`' `expandedTo` synthesises the second branch's slot as a
 * LATENT one, so a wheel whose second branch never reports shows one pack
 * instead of a permanently-offline phantom. The slot is persisted later, by the
 * pack auto-fill, once it has actually produced data.
 *
 * [Controller.providesDerivedBattery] is `false` here, against
 * [controllerVehicle]'s unconditional `true`, and this is the whole difference
 * between the two builders: a derived battery is one COMPUTED from a
 * controller's own telemetry when nothing else can see the pack. A wheel's pack
 * is decoded, not computed — so claiming a derived battery would ask
 * `planLinkPacks` to number a second, phantom battery beside the real branches.
 *
 * No cell count: the wheel does not advertise one and the picker has not
 * connected yet. A smart-BMS wheel gets it auto-filled from its first cell
 * frames (`KableBmsRepository.maybePersistCellCount`), exactly as for any
 * other pack; a wheel with no smart BMS sends no cell frames at all and the
 * auto-fill can never fire, so the rider types it instead (Task 3) — the
 * only route to a rail voltage that wheel has.
 */
@OptIn(ExperimentalTime::class)
fun wheelVehicle(
    id: String,
    name: String,
    iconKey: String,
    address: String,
    chemistry: Chemistry,
    createdAt: Instant,
    motor: MotorConfig = MotorConfig(),
): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = listOf(
        Pack(index = 0, label = name, bmsType = BmsType.BEGODE, bmsAddress = address)
    ),
    controllers = listOf(
        Controller(
            index = 0,
            label = name,
            controllerType = ControllerType.BEGODE,
            address = address,
            motor = motor,
            providesDerivedBattery = false
        )
    ),
    // Two parallel branches, which is what BegodeProtocol reports and what the
    // battery picker's own Begode vehicles have always been.
    topology = PackTopology.PARALLEL,
    chemistry = chemistry,
    createdAt = createdAt,
    dashboardStyle = null,
    secondaryGauge = SecondaryGauge.DUTY
)

/**
 * Builds a controller-only vehicle: zero packs, one [Controller] at index 0.
 * Sibling of [singlePackVehicle] — same calling conventions (parameter
 * naming, `createdAt` threaded straight through, the sole source named after
 * the vehicle) but for the controller shape instead of the single-pack one.
 *
 * [Controller.canId] is always null here — this builder produces a
 * single-controller vehicle with no gateway to forward through, so it must
 * never set one. `planLinks` itself accepts CAN-forwarded sources since Part
 * C; nothing about that lifts this builder's own no-CAN-id contract.
 *
 * **Not for every controller type** — [pickedControllerVehicle] is the entry
 * point a Controller pick goes through, and it sends [ControllerType.BEGODE] to
 * [wheelVehicle] instead. See there for why a wheel needs a stored pack.
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
