# Show all BLE devices + manual BMS-type selection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user see every nearby Bluetooth device in the picker (not just auto-detected BMS) and choose/override the BMS type before connecting.

**Architecture:** `DiscoveredDevice.bmsType` becomes nullable; `KableBmsRepository.scanAll()` stops dropping unrecognized advertisements. `PickerComponent` classifies scan results into saved / detected / undetected buckets and exposes a type-selection sheet whose chosen type overrides the auto-detected guess on connect. `PickerScreen` gains a "Show all" toggle and a `ModalBottomSheet` type picker.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (material3), Decompose 3.4.0, kotlinx-coroutines-test, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-06-12-show-all-ble-devices-manual-type-design.md`

**Build/test commands (Windows PowerShell):**
- Unit tests (incl. commonTest): `.\gradlew.bat :composeApp:testDebugUnitTest --console=plain`
- Single class: `.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.picker.PickerComponentTest" --console=plain`

---

### Task 1: Add string resources (EN + RU)

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-ru/strings.xml`

- [ ] **Step 1: Add the three new strings to the EN file**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, inside the `<!-- Picker -->` block (after the `picker_error` line, line 49), add:

```xml
    <string name="picker_show_all">Show all Bluetooth devices (%1$d)</string>
    <string name="picker_type_unknown">Unknown</string>
    <string name="picker_pick_type_title">Select BMS type</string>
```

- [ ] **Step 2: Add the same three strings to the RU file**

In `composeApp/src/commonMain/composeResources/values-ru/strings.xml`, after the `picker_error` line (line 49), add:

```xml
    <string name="picker_show_all">Показать все Bluetooth-устройства (%1$d)</string>
    <string name="picker_type_unknown">Неизвестно</string>
    <string name="picker_pick_type_title">Выберите тип BMS</string>
```

- [ ] **Step 3: Build to generate the `Res.string.*` accessors**

Run: `.\gradlew.bat :composeApp:compileDebugKotlinAndroid --console=plain`
Expected: `BUILD SUCCESSFUL`. (This regenerates the `volty.composeapp.generated.resources` accessors so later tasks can import `Res.string.picker_show_all`, `picker_type_unknown`, `picker_pick_type_title`.)

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-ru/strings.xml
git commit -m "feat(picker): add strings for show-all toggle and BMS-type sheet"
```

---

### Task 2: Nullable `bmsType`, unfiltered scan, picker rework + tests

This is the core task. It changes the shared model, the scan flow, the component logic (test-driven), and the screen — together, because `PickerComponent`'s public API changes and `PickerScreen` is its only consumer, so they must compile as a unit.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt` (the `DiscoveredDevice` data class, line 12-18)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt` (`scanAll`, lines 249-264)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerScreen.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponentTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponentTest.kt`:

```kotlin
package ru.sodovaya.volty.presentation.picker

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PickerComponentTest {

    private class FakeBmsRepo(private val scan: List<DiscoveredDevice>) : BmsRepository {
        val guestConnects = mutableListOf<Pair<String, BmsType>>()
        val vehicleConnects = mutableListOf<Vehicle>()
        override val activeData = MutableStateFlow(BmsData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = scan.asFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> { vehicleConnects += vehicle; return Result.success(Unit) }
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> { guestConnects += address to type; return Result.success(Unit) }
        override suspend fun connectDemo(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class FakeVehicleRepo(private val saved: List<Vehicle>) : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        val deletes = mutableListOf<String>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(saved)
        override suspend fun get(id: String): Vehicle? = saved.firstOrNull { it.id == id }
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) { deletes += id }
        override suspend fun touch(id: String) {}
    }

    private fun vehicle(id: String, address: String) = Vehicle(
        id = id, name = "Saved", iconKey = "generic",
        bmsType = BmsType.JK_BMS, bmsAddress = address,
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
    )

    private fun device(address: String, type: BmsType?, rssi: Int = -50) =
        DiscoveredDevice(address = address, name = "dev-$address", rssi = rssi, bmsType = type)

    private fun component(
        mode: String,
        scan: List<DiscoveredDevice>,
        saved: List<Vehicle> = emptyList(),
        bmsRepo: FakeBmsRepo = FakeBmsRepo(scan),
        vehicleRepo: FakeVehicleRepo = FakeVehicleRepo(saved),
    ): Pair<DefaultPickerComponent, FakeBmsRepo> {
        val ctx = DefaultComponentContext(LifecycleRegistry())
        val c = DefaultPickerComponent(
            componentContext = ctx,
            mode = mode,
            bmsRepository = bmsRepo,
            vehicleRepository = vehicleRepo,
            onConnectedKnown = {},
            onConnectedForEdit = {},
            onConnectedGuestNoSave = {},
            onAddNewBatteryRequested = {},
            onDemoConnected = {},
            onCancelled = {},
        )
        return c to bmsRepo
    }

    @Test
    fun `scan results are classified into saved, detected and undetected`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = vehicle(id = "v1", address = "AA:SAVED")
        val scan = listOf(
            device("AA:SAVED", BmsType.JK_BMS),   // known address -> myInRange
            device("BB:DETECT", BmsType.JBD_BMS), // recognized -> otherNearby
            device("CC:UNKNOWN", null),           // unrecognized -> otherDevices
        )
        val (c, _) = component(mode = "cold", scan = scan, saved = listOf(saved))
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(listOf("v1"), s.myInRange.map { it.id })
        assertEquals(listOf("BB:DETECT"), s.otherNearby.map { it.address })
        assertEquals(listOf("CC:UNKNOWN"), s.otherDevices.map { it.address })
        Dispatchers.resetMain()
    }

    @Test
    fun `undetected devices are sorted by rssi descending`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scan = listOf(
            device("FAR", null, rssi = -90),
            device("NEAR", null, rssi = -40),
            device("MID", null, rssi = -65),
        )
        val (c, _) = component(mode = "guest", scan = scan)
        advanceUntilIdle()

        assertEquals(listOf("NEAR", "MID", "FAR"), c.state.value.otherDevices.map { it.address })
        Dispatchers.resetMain()
    }

    @Test
    fun `onConnectWithType uses the chosen type, overriding the guess (guest mode)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val unknown = device("CC:UNKNOWN", null)
        val (c, repo) = component(mode = "guest", scan = listOf(unknown))
        advanceUntilIdle()

        c.onConnectWithType(unknown, BmsType.JBD_BMS)
        advanceUntilIdle()

        assertEquals(listOf("CC:UNKNOWN" to BmsType.JBD_BMS), repo.guestConnects)
        Dispatchers.resetMain()
    }

    @Test
    fun `onDeviceTapped opens and onTypeSheetDismissed closes the type sheet`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val d = device("CC:UNKNOWN", null)
        val (c, _) = component(mode = "guest", scan = listOf(d))
        advanceUntilIdle()

        c.onDeviceTapped(d)
        assertEquals("CC:UNKNOWN", c.state.value.typePickerFor?.address)
        c.onTypeSheetDismissed()
        assertTrue(c.state.value.typePickerFor == null)
        Dispatchers.resetMain()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.picker.PickerComponentTest" --console=plain`
Expected: FAIL — compilation errors (`onConnectWithType`, `onDeviceTapped`, `onTypeSheetDismissed`, `otherDevices`, `typePickerFor` unresolved; `DiscoveredDevice` does not accept a null `bmsType`).

- [ ] **Step 3: Make `DiscoveredDevice.bmsType` nullable**

In `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt`, change the data class (lines 12-18):

```kotlin
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Auto-detected BMS type, or `null` when the scanner did not recognize the device. */
    val bmsType: BmsType?,
    val knownVehicle: Vehicle? = null
)
```

- [ ] **Step 4: Stop filtering in `scanAll`**

In `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`, replace the `scanner.advertisements.collect { ad -> ... }` body (lines 249-264) so unrecognized devices are emitted with `bmsType = null` instead of being dropped:

```kotlin
        scanner.advertisements.collect { ad ->
            val name = ad.name
            val serviceList = ad.uuids.map { it.toString().lowercase() }
            // May be null: the picker now lists every device and lets the user
            // pick the type manually, so we no longer drop unrecognized ads.
            val type = BmsTypeDetector.detect(name = name, serviceUuids = serviceList)
            val id = ad.identifier.toString()
            cacheAdvertisement(id, ad)
            emit(
                DiscoveredDevice(
                    address = id,
                    name = name,
                    rssi = ad.rssi,
                    bmsType = type,
                    knownVehicle = knownAddresses[id]
                )
            )
        }
```

- [ ] **Step 5: Rework `PickerComponent` (state + interface + logic)**

In `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponent.kt`:

5a. Add `import ru.sodovaya.volty.domain.model.BmsType` to the imports.

5b. Replace the `interface PickerComponent { ... }` block (lines 25-40) with:

```kotlin
interface PickerComponent {
    val state: StateFlow<State>
    fun onConnectKnown(vehicle: Vehicle)
    fun onToggleShowAll()
    fun onDeviceTapped(device: DiscoveredDevice)
    fun onTypeSheetDismissed()
    fun onConnectWithType(device: DiscoveredDevice, type: BmsType)
    fun onAddNewBattery()
    fun onTryDemo()
    fun onBack()

    data class State(
        val mode: String = "cold",
        val myInRange: List<Vehicle> = emptyList(),
        val otherNearby: List<DiscoveredDevice> = emptyList(),   // detected (bmsType != null)
        val otherDevices: List<DiscoveredDevice> = emptyList(),  // undetected (bmsType == null)
        val showAll: Boolean = false,
        val typePickerFor: DiscoveredDevice? = null,             // device whose type sheet is open
        val connecting: String? = null,    // address being connected
        val error: String? = null
    )
}
```

5c. Replace the `scanJob = scope.launch { bmsRepository.scanAll().collect { dev -> ... } }` block (lines 99-114) with a three-way classification:

```kotlin
        scanJob = scope.launch {
            bmsRepository.scanAll().collect { dev ->
                val matched = savedByAddress[dev.address]
                _state.update { s ->
                    when {
                        matched != null -> {
                            val myInRange = if (s.myInRange.any { it.id == matched.id }) s.myInRange
                                            else s.myInRange + matched
                            s.copy(myInRange = myInRange)
                        }
                        dev.bmsType != null -> {
                            val otherNearby = if (s.otherNearby.any { it.address == dev.address }) s.otherNearby
                                              else s.otherNearby + dev
                            s.copy(otherNearby = otherNearby)
                        }
                        else -> {
                            if (s.otherDevices.any { it.address == dev.address }) s
                            else s.copy(otherDevices = (s.otherDevices + dev).sortedByDescending { it.rssi })
                        }
                    }
                }
            }
        }
```

5d. Replace the `override fun onConnectOther(device: DiscoveredDevice) { ... }` method (lines 127-156) with the new toggle/sheet/connect methods. The connect logic is the old body, but it reads the passed `type` instead of `device.bmsType`, and it clears `typePickerFor` up front:

```kotlin
    override fun onToggleShowAll() {
        _state.update { it.copy(showAll = !it.showAll) }
    }

    override fun onDeviceTapped(device: DiscoveredDevice) {
        _state.update { it.copy(typePickerFor = device) }
    }

    override fun onTypeSheetDismissed() {
        _state.update { it.copy(typePickerFor = null) }
    }

    override fun onConnectWithType(device: DiscoveredDevice, type: BmsType) {
        scope.launch {
            _state.update { it.copy(typePickerFor = null, connecting = device.address, error = null) }
            scanJob?.cancel()
            if (mode == "add") {
                // Create and save Vehicle, then connect as known so activeVehicle is set
                val v = Vehicle(
                    id = "v-" + kotlin.random.Random.nextLong().toString(16).removePrefix("-"),
                    name = device.name ?: "BMS ${device.address.takeLast(4)}",
                    iconKey = "generic",
                    bmsType = type,
                    bmsAddress = device.address,
                    chemistry = Chemistry.LI_ION_NMC,
                    createdAt = Clock.System.now()
                )
                vehicleRepository.upsert(v)
                val result = bmsRepository.connect(v)
                if (result.isSuccess) onConnectedForEdit(v.id)
                else {
                    vehicleRepository.delete(v.id) // rollback so user isn't stuck with a broken save
                    _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
                }
            } else {
                // guest / cold mode: connect as guest, no save
                val result = bmsRepository.connectGuest(device.address, type)
                if (result.isSuccess) onConnectedGuestNoSave()
                else _state.update { it.copy(connecting = null, error = result.exceptionOrNull()?.message) }
            }
        }
    }
```

- [ ] **Step 6: Update `PickerScreen`**

In `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerScreen.kt`:

6a. Add imports (place beside the existing ones):

```kotlin
import androidx.compose.material3.ModalBottomSheet
import ru.sodovaya.volty.domain.model.BmsType
import volty.composeapp.generated.resources.picker_pick_type_title
import volty.composeapp.generated.resources.picker_show_all
import volty.composeapp.generated.resources.picker_type_unknown
```

6b. In the `LazyColumn`, change the detected-section `items(...)` `onClick` to open the sheet, and add the "Show all" toggle + undetected section right after it. Replace the existing `if (state.otherNearby.isNotEmpty()) { ... }` block (lines 112-127) with:

```kotlin
                if (state.otherNearby.isNotEmpty()) {
                    item {
                        SectionHeader(
                            if (state.mode == "guest" || (state.mode == "cold" && state.myInRange.isEmpty()))
                                stringResource(Res.string.picker_detected)
                            else stringResource(Res.string.picker_other_nearby)
                        )
                    }
                    items(state.otherNearby, key = { "d-" + it.address }) { d ->
                        DeviceRow(
                            device = d,
                            isConnecting = state.connecting == d.address,
                            onClick = { component.onDeviceTapped(d) }
                        )
                    }
                }

                if (state.otherDevices.isNotEmpty()) {
                    item {
                        TextButton(
                            onClick = component::onToggleShowAll,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text(stringResource(Res.string.picker_show_all, state.otherDevices.size))
                        }
                    }
                    if (state.showAll) {
                        items(state.otherDevices, key = { "u-" + it.address }) { d ->
                            DeviceRow(
                                device = d,
                                isConnecting = state.connecting == d.address,
                                onClick = { component.onDeviceTapped(d) }
                            )
                        }
                    }
                }
```

6c. Update the empty-state condition (line 129) to also account for `otherDevices`:

```kotlin
                if (state.myInRange.isEmpty() && state.otherNearby.isEmpty() && state.otherDevices.isEmpty()) {
```

6d. Add the type-selection `ModalBottomSheet` at the end of the `Scaffold` content lambda — immediately after the closing brace of the outer `Column` (after line 156, the `}` that closes `Column`, still inside the `{ padding -> ... }` lambda):

```kotlin
        state.typePickerFor?.let { device ->
            ModalBottomSheet(onDismissRequest = component::onTypeSheetDismissed) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        stringResource(Res.string.picker_pick_type_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    BmsType.entries.forEach { type ->
                        val selected = device.bmsType == type
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { component.onConnectWithType(device, type) }
                                .padding(14.dp)
                        ) {
                            Text(
                                bmsTypeLabel(type),
                                fontSize = 14.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
```

6e. Update `DeviceRow` (lines 192-211) so a null `bmsType` shows the "Unknown" label and a null `name` falls back to a short address. Replace the body of `DeviceRow`'s `Column` (the two `Text` lines, 205-207) with:

```kotlin
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name ?: "BMS ${device.address.takeLast(4)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val typeLabel = device.bmsType?.let { bmsTypeLabel(it) } ?: stringResource(Res.string.picker_type_unknown)
            Text("$typeLabel  ·  ${device.rssi} dBm", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
```

- [ ] **Step 7: Run the picker test and verify it passes**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.picker.PickerComponentTest" --console=plain`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponent.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/PickerScreen.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/picker/PickerComponentTest.kt
git commit -m "feat(picker): list all BLE devices and let user pick BMS type"
```

---

### Task 3: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the entire unit-test suite**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`. Confirms the nullable-`bmsType` change didn't break other consumers (`BmsTypeDetectorTest`, `KableBmsRepository*Test`, etc.).

- [ ] **Step 2: Compile the Android debug variant end-to-end**

Run: `.\gradlew.bat :composeApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. Confirms `PickerScreen` (Compose/material3 `ModalBottomSheet`) compiles for the real target.

- [ ] **Step 3: Manual smoke check (on device, no automated equivalent)**

With a phone + a real BMS in range: open the picker, confirm the detected BMS appears as before, tap "Show all Bluetooth devices (N)", confirm other devices (including nameless ones, by address + RSSI) appear sorted strongest-first, tap one, pick a type in the sheet, and confirm a connection attempt starts. This step has no unit-test equivalent (requires the BLE stack + hardware) and is the only way to verify the `scanAll` change against live advertisements.
