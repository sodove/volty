package ru.sodovaya.volty.domain.model

/**
 * Safe (non-throwing) counterparts to the primary-pack shortcuts in
 * `Vehicle.kt` (`primaryPack`, `bmsType`, `bmsAddress`, `cellCount`), which
 * are built on `packs.first()` and throw `NoSuchElementException` for a
 * controller-only vehicle. Use these wherever a vehicle might have zero
 * packs.
 */
val Vehicle.primaryPackOrNull: Pack? get() = packs.firstOrNull()
val Vehicle.bmsTypeOrNull: BmsType? get() = primaryPackOrNull?.bmsType
val Vehicle.bmsAddressOrNull: String? get() = primaryPackOrNull?.bmsAddress
val Vehicle.cellCountOrNull: Int? get() = primaryPackOrNull?.cellCount

/** Every BLE address this vehicle can be recognised by — packs and controllers alike. */
val Vehicle.allAddresses: Set<String>
    get() = (packs.map { it.bmsAddress } + controllers.map { it.address }).toSet()

/** True when this vehicle has no battery source of its own and a controller must derive one. */
val Vehicle.needsDerivedBattery: Boolean get() = packs.isEmpty() && controllers.isNotEmpty()

/**
 * Indexes saved vehicles by *every* address each one can be recognised by, so a
 * scan result can be matched back to its vehicle whichever of its sources
 * happened to advertise.
 *
 * Deliberately NOT `associateBy { it.bmsAddress }` (the old shape): that keyed
 * a vehicle by its primary pack alone, so a controller-only vehicle — which has
 * no pack address at all — could never be recognised from its own
 * advertisement, and a vehicle with both a pack and a controller was invisible
 * whenever the controller was the thing in range.
 *
 * Collision policy matches the `associateBy` it replaces: when two vehicles
 * share an address, the later one in [vehicles] wins.
 */
fun vehiclesByAddress(vehicles: List<Vehicle>): Map<String, Vehicle> =
    vehicles.flatMap { v -> v.allAddresses.map { address -> address to v } }.toMap()
