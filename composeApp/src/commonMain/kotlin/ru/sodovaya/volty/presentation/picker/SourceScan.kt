package ru.sodovaya.volty.presentation.picker

import ru.sodovaya.volty.data.ble.ProtocolKind
import ru.sodovaya.volty.data.ble.protocolKind
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.repository.DiscoveredDevice

/**
 * # What a scan found, in the terms a composer needs (G2 Task 5)
 *
 * ## Where the shared piece lives, and what is shared
 *
 * **The scanner itself was already shared and is untouched:**
 * `BmsRepository.scanAll()` is the one BLE scan in this app, and both the picker
 * and the composer collect that same flow. This file is what the *composer*
 * additionally needs and the picker had open-coded or not needed at all:
 *
 *  - [SourceRole] — `G §7`'s "controller / battery / both" labelling. The picker
 *    never had this: [preselectedChoice] answers "which row do I highlight",
 *    which is a different question and cannot say **both**;
 *  - [ScannedAdd] / [addsFor] — which adds a discovered device offers, including
 *    `G §3` flow 3's single "+ Wheel";
 *  - [addControllerType] / [addBmsType] — the type an add starts from;
 *  - [withScanHit] — folding one hit into a list, deduplicated by address.
 *
 * It sits in `presentation/picker/` beside [SourceChoice] rather than under
 * `vehicle/` because it is about *devices a scan found*, not about a draft; the
 * composer imports it, the way it imports the repository's scan.
 *
 * **`DefaultPickerComponent`'s own three-list routing is deliberately left
 * alone.** It answers a question this file does not — saved vehicle vs detected
 * device vs unrecognised device, with the connected peripheral seeded in
 * because it stops advertising — and it is the pinned battery path. Rewriting it
 * to run through [withScanHit] would be a change to that path for the sake of
 * sharing eleven lines.
 *
 * ## Everything here is pure
 *
 * Compose is not unit-testable in this repo, so the sheet that shows a scan is a
 * renderer over these functions and holds no rule of its own.
 */

// ---------------------------------------------------------------------------
// Rider-visible identity
// ---------------------------------------------------------------------------

/**
 * The two text fields a scan row needs to identify a physical BLE peripheral.
 *
 * A name is only a hint: several BMS boxes can advertise the same name (or no
 * name), while the address is the fact that distinguishes them. Named rows
 * therefore retain their address; unnamed rows promote it to the title rather
 * than inventing a generic label that makes two devices look the same.
 */
data class ScanDeviceLabel(
    val title: String,
    val address: String?
)

/** The full, rider-visible identity of this scan hit. */
fun DiscoveredDevice.scanDeviceLabel(): ScanDeviceLabel =
    name?.let { ScanDeviceLabel(title = it, address = address) }
        ?: ScanDeviceLabel(title = address, address = null)

/** Qualitative signal feedback for walking toward one otherwise identical box. */
enum class SignalProximity { WARMER, COLDER }

private const val WARMER_RSSI_DBM = -70

/**
 * RSSI is not a distance measurement, so present it only as the direction a
 * rider can use: cross -70 dBm by walking closer and the row becomes warmer.
 */
fun DiscoveredDevice.signalProximity(): SignalProximity =
    if (rssi >= WARMER_RSSI_DBM) SignalProximity.WARMER else SignalProximity.COLDER

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

/**
 * What a discovered device can be a source of (`G §7`).
 *
 * [BOTH] is not a hedge — it is the electric unicycle: a Begode is a controller
 * **and** its own battery over one BLE link, which is why `G §3`'s third flow
 * exists at all. [UNKNOWN] is a device neither detector recognised; it still
 * gets every add offered, because detection is a hint and not a lock (the same
 * rule [SourceChoice] states for the picker's type sheet).
 */
enum class SourceRole { CONTROLLER, BATTERY, BOTH, UNKNOWN }

/**
 * Whether some [ControllerType] speaks this wire protocol — derived from the
 * enum rather than listed, so a new controller type is classified by the
 * `protocolKind()` mapping it must already have and cannot be forgotten here.
 */
private fun ProtocolKind.isControllerKind(): Boolean =
    ControllerType.entries.any { it.protocolKind() == this }

/** The battery twin of [isControllerKind], derived the same way from [BmsType]. */
private fun ProtocolKind.isBatteryKind(): Boolean =
    BmsType.entries.any { it.protocolKind() == this }

/**
 * The wire protocol this device was detected as, or null when neither detector
 * fired.
 *
 * The two fields are mutually exclusive as a *detection result*
 * (`BmsTypeDetector.detectController` returns null whenever `detect` already
 * matched), so this is a choice between at most one of them — but the
 * controller field is read first anyway, so that a device carrying both (only
 * reachable from the picker's synthetic "currently connected" row, which
 * carries a vehicle's controller AND pack type) is described by its controller.
 */
private fun DiscoveredDevice.detectedKind(): ProtocolKind? =
    controllerType?.protocolKind() ?: bmsType?.protocolKind()

/**
 * [SourceRole] for a discovered device.
 *
 * A Begode reports as a *battery* (`BmsTypeDetector.detect` matches its name
 * before the controller detector ever runs), so classifying on
 * `controllerType != null` alone would call a wheel a battery and hide the
 * single "+ Wheel" add on the one device it was written for. Classifying on the
 * PROTOCOL fixes that at the root: `ProtocolKind.BEGODE` is reachable from both
 * enums, which is precisely what "one device, two sources" means.
 */
fun DiscoveredDevice.sourceRole(): SourceRole {
    val kind = detectedKind() ?: return SourceRole.UNKNOWN
    val controller = kind.isControllerKind()
    val battery = kind.isBatteryKind()
    return when {
        controller && battery -> SourceRole.BOTH
        controller -> SourceRole.CONTROLLER
        battery -> SourceRole.BATTERY
        else -> SourceRole.UNKNOWN
    }
}

// ---------------------------------------------------------------------------
// What a scanned device can be added as
// ---------------------------------------------------------------------------

/**
 * The adds a scanned device offers in the composer.
 *
 * [WHEEL] is `G §3` flow 3 — **one** add producing a controller and a battery on
 * **one link**. Its value is not the tap it saves: it records that the two
 * sources are the same physical device, which the rider knows and the app
 * cannot infer. Two separate adds would produce the same two rows with no such
 * claim, and the shared address is where that claim is stored (see
 * `VehicleDraft.addWheel`).
 */
enum class ScannedAdd { CONTROLLER, BATTERY, WHEEL }

/**
 * Every add, ordered so the detected one comes first.
 *
 * **Ordered, never filtered.** Detection is a hint: a misdetected device — or
 * one nobody has taught the detector about — must still be describable, which is
 * exactly the rule the picker's type sheet already follows. So a wheel leads
 * with [ScannedAdd.WHEEL] and an unrecognised device leads with the commonest
 * case, but no device is ever refused an add.
 *
 * Exhaustive over [SourceRole] with no `else`, so a new role has to decide.
 */
fun addsFor(device: DiscoveredDevice): List<ScannedAdd> = when (device.sourceRole()) {
    SourceRole.BOTH -> listOf(ScannedAdd.WHEEL, ScannedAdd.CONTROLLER, ScannedAdd.BATTERY)
    SourceRole.CONTROLLER -> listOf(ScannedAdd.CONTROLLER, ScannedAdd.BATTERY, ScannedAdd.WHEEL)
    SourceRole.BATTERY -> listOf(ScannedAdd.BATTERY, ScannedAdd.CONTROLLER, ScannedAdd.WHEEL)
    SourceRole.UNKNOWN -> listOf(ScannedAdd.CONTROLLER, ScannedAdd.BATTERY, ScannedAdd.WHEEL)
}

/**
 * The [ControllerType] an add starts this device from.
 *
 * Detection first; failing that, the controller type sharing the *battery's*
 * wire protocol — which is what turns a device detected as `BmsType.BEGODE`
 * into `ControllerType.BEGODE` rather than into a VESC. VESC is the fallback
 * because it is the only controller kind volty can actually decode
 * (`controllerMotionSupported`), so it is the one that will not immediately
 * raise an advisory on the card.
 *
 * Only an INITIAL value either way — the card that appears lets the rider
 * change it.
 */
fun DiscoveredDevice.addControllerType(): ControllerType =
    controllerType
        ?: bmsType?.protocolKind()?.let { k -> ControllerType.entries.firstOrNull { it.protocolKind() == k } }
        ?: ControllerType.VESC

/**
 * The [BmsType] an add starts this device from — the mirror of
 * [addControllerType].
 *
 * `JK_BMS` is the fallback for the same reason the composer's own "+ Battery"
 * button uses it: it is the form's existing default, and a VESC controller has
 * no battery kind to borrow (`ProtocolKind.VESC` maps to no [BmsType] at all).
 */
fun DiscoveredDevice.addBmsType(): BmsType =
    bmsType
        ?: controllerType?.protocolKind()?.let { k -> BmsType.entries.firstOrNull { it.protocolKind() == k } }
        ?: BmsType.JK_BMS

// ---------------------------------------------------------------------------
// Accumulating a scan
// ---------------------------------------------------------------------------

/**
 * Fold one scan hit into the list a sheet is showing.
 *
 * A BLE scan re-reports the same peripheral continuously, so the list is keyed
 * by address. A repeat **replaces** the stored entry only when the new hit
 * carries detection the old one lacked — a device is often seen first as an
 * unrecognised advertisement and only later with its service UUIDs, and a list
 * that kept the first sighting would offer the wrong adds forever. A repeat that
 * adds nothing is dropped rather than written, so a row does not re-order or
 * flicker its RSSI on every advertisement.
 *
 * Order is first-appearance and never re-sorted: rows a rider is reaching for
 * must not move under their finger. (The picker sorts its *unknown* list by
 * RSSI, which it can because that list is a haystack; this one is a list you
 * pick from.)
 */
fun List<DiscoveredDevice>.withScanHit(hit: DiscoveredDevice): List<DiscoveredDevice> {
    val at = indexOfFirst { it.address == hit.address }
    if (at < 0) return this + hit
    val old = this[at]
    val improved = (old.bmsType == null && hit.bmsType != null) ||
        (old.controllerType == null && hit.controllerType != null) ||
        (old.name == null && hit.name != null)
    if (!improved) return this
    return toMutableList().also { it[at] = hit }
}
