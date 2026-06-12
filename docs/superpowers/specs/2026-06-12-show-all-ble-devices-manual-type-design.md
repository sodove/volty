# Show all BLE devices + manual BMS-type selection

**Date:** 2026-06-12
**Status:** Approved (design)

## Problem

The device picker only shows advertisements that `BmsTypeDetector` auto-recognizes
as a BMS. `KableBmsRepository.scanAll()` drops everything else
(`detect(...) ?: return@collect`). So:

- A BMS the heuristic doesn't recognize (unusual name, or no advertised name at
  all) is invisible — the user cannot connect to it at all.
- When the heuristic *does* fire, it can be wrong. `0xFFE0` is shared by JK and
  ANT, so a device guessed as JK might actually be ANT, and the user has no way
  to correct it.

## Goal

Let the user (a) see every nearby Bluetooth device, not just auto-detected BMS,
and (b) choose / override the BMS type before connecting.

## Decisions (from brainstorming)

- Undetected devices live **behind a "Show all" toggle** — the default view stays
  the clean detected-BMS list.
- Type selection is a **bottom sheet available on any device** (detected or not),
  pre-selected to the guessed type, so wrong guesses can be overridden.
- The "Show all" list **includes nameless devices**, labeled by address, so a BMS
  that advertises no name is still reachable (find it by signal strength).

## Architecture

### 1. Data model — `DiscoveredDevice.bmsType` becomes nullable

`domain/repository/BmsRepository.kt`:

```kotlin
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bmsType: BmsType?,   // was BmsType — null = scanner did not recognize it
    val knownVehicle: Vehicle? = null
)
```

All consumers of `.bmsType` are updated for nullability (`PickerComponent`,
`PickerScreen.DeviceRow`).

### 2. Repository — `scanAll()` stops filtering

`data/ble/KableBmsRepository.kt` `scanAll()`: emit **every** advertisement.
Replace the `?: return@collect` drop with a nullable assignment:

```kotlin
scanner.advertisements.collect { ad ->
    val name = ad.name
    val serviceList = ad.uuids.map { it.toString().lowercase() }
    val type = BmsTypeDetector.detect(name = name, serviceUuids = serviceList) // may be null
    val id = ad.identifier.toString()
    cacheAdvertisement(id, ad)
    emit(DiscoveredDevice(address = id, name = name, rssi = ad.rssi, bmsType = type,
        knownVehicle = knownAddresses[id]))
}
```

The advertisement is still cached so the connect path can resolve it.
`ScanningComponent` (startup auto-scan) is unaffected: it filters by known
address and never reads `bmsType`.

### 3. Picker state & classification — `PickerComponent`

`PickerComponent.State` gains:

```kotlin
val otherDevices: List<DiscoveredDevice> = emptyList(), // undetected (bmsType == null)
val showAll: Boolean = false,
val typePickerFor: DiscoveredDevice? = null,            // device whose type sheet is open
```

In the `scanAll().collect` loop, route by detection:

- `matched != null`            → `myInRange` (unchanged).
- `bmsType != null`            → `otherNearby` (detected; shown by default).
- `bmsType == null`            → `otherDevices` (undetected; behind toggle).

`otherDevices` is presented sorted by `rssi` descending so the closest device
floats to the top. De-dupe by address within each list (as today).

New methods on the component interface:

```kotlin
fun onToggleShowAll()
fun onDeviceTapped(device: DiscoveredDevice)   // opens the type sheet
fun onTypeSheetDismissed()
fun onConnectWithType(device: DiscoveredDevice, type: BmsType)
```

`onConnectKnown(vehicle)` is unchanged (saved vehicles already carry a type).
`onConnectOther` is removed; its connect logic moves into `onConnectWithType`,
which uses the **passed type** rather than `device.bmsType`:

- add mode  → build/save `Vehicle(bmsType = type, ...)`, then `connect(v)`
  (existing rollback-on-failure behavior preserved).
- guest/cold → `connectGuest(device.address, type)`.

### 4. Type-selection sheet — `PickerScreen`

Tapping any device row calls `onDeviceTapped(device)`, which sets
`typePickerFor`. The screen renders a `ModalBottomSheet` when `typePickerFor != null`
listing the four `BmsType` values (label via existing
`presentation/common/EnumLabels.bmsTypeLabel`). The entry matching
`device.bmsType` (when non-null) is visually pre-selected. Selecting an entry
calls `onConnectWithType(device, type)` and the sheet dismisses; the existing
`connecting` spinner / `error` handling is reused.

A "Show all Bluetooth devices (N)" toggle row sits below the detected sections,
where N = `otherDevices.size`; tapping it calls `onToggleShowAll()`. When
`showAll` is true, an "Other devices" section renders `otherDevices`. `DeviceRow`
handles a null `bmsType` ("Unknown") and a null `name` (`BMS <last-4-of-address>`).

### 5. Strings (RU/EN)

New resources in `composeResources` `strings.xml` (both locales):

- `picker_show_all` — e.g. "Show all Bluetooth devices (%1$d)" / "Показать все Bluetooth-устройства (%1$d)"
- `picker_type_unknown` — "Unknown" / "Неизвестно"
- `picker_pick_type_title` — "Select BMS type" / "Выберите тип BMS"

### 6. Error handling

Unchanged flow: connect failure sets `connecting = null, error = <message>`;
add-mode failure additionally rolls back the saved `Vehicle`. The type sheet
closes on selection (before the connect attempt) so a failure surfaces in the
existing error banner, not inside the sheet.

## Testing

- `BmsTypeDetector` detection: already covered.
- New: lightweight fake `BmsRepository` in `commonTest` whose `scanAll()` emits a
  scripted list of `DiscoveredDevice`. Cover `PickerComponent`:
  1. an undetected device (`bmsType == null`) lands in `otherDevices`; a detected
     one lands in `otherNearby`; a known-address one lands in `myInRange`.
  2. `onConnectWithType(device, JBD_BMS)` invokes `connectGuest(address, JBD_BMS)`
     (guest mode) even when `device.bmsType` was `null` or a different value —
     i.e. the chosen type wins.
  - `PickerComponent` uses `Dispatchers.Main`; the test sets a test dispatcher via
    `Dispatchers.setMain` (or constructs with an injected scope if simpler).

## Out of scope (YAGNI)

- Persisting a per-device "remembered" type choice (add-mode already saves the
  Vehicle with the chosen type).
- Signal-strength bars / richer device metadata beyond the existing dBm text.
- Manual type override for already-**saved** vehicles (they are edited via the
  vehicle-edit flow, not the picker).
