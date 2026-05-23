# Volty — Design Spec

| Field | Value |
|---|---|
| Date | 2026-05-23 |
| Status | Draft — pending user review |
| Author | sodove + Claude (brainstorm) |
| Target | MVP-1 |

## 1. Overview

**Volty** is an Android-only app for monitoring smart Bluetooth BMS (battery management systems) used by personal-electric-vehicle riders, DIY EV builders, solar/home-battery owners, e-bike/scooter/skateboard owners and similar audiences. Users save one or more BMS profiles ("vehicles") and the app auto-connects when one is in range, then shows live data, predictive metrics and alerts.

The project starts from BMS code already implemented in `kelly-connect` (4 protocols over BLE: JK, JBD, ANT, Daly) but is otherwise a fresh app with its own architecture, brand and visual language. The two projects stay separate; no Kelly-controller code is migrated.

## 2. Goals and non-goals

### Goals (MVP-1)

1. Save multiple BMS profiles with name, icon, BMS type, BLE address, chemistry preset.
2. Auto-connect when exactly one known BMS is in range (3-second cancellable countdown, then dashboard).
3. Picker screen when 2+ known BMS in range, or when scanning unknown BMS (also used for "Add new battery" and Guest mode).
4. Dashboard with SOC, V, A, W, temperatures, cell delta (mini-spread), live sparkline, predicted time-to-empty using moving average over user-configurable window.
5. Compact Cells detail screen (target: 20+ cells visible without scroll).
6. Graph detail screen with metric switcher (SOC / Power / Current / Voltage / Temperature) and window (1m / 5m / 15m / 1h / All session).
7. Foreground service that keeps BLE connection alive while the app is backgrounded, with persistent notification ("Volty · {vehicle} · SOC · V · A · ETA").
8. Alert engine with configurable thresholds (cell V high/low, cell delta, temperature, SOC low, disconnect, charge complete).
9. Guest mode for quick connect without saving (e.g. in a service shop or while testing a friend's battery).
10. Visual: Material 3 Expressive, dynamic color on Android 12+, portrait-only, dark + light + system theme.

### Non-goals (MVP-1)

- iOS, desktop, web targets — Android only.
- Trip history / session log persistence to disk (live ring buffer only; persistence deferred to v1.1).
- Cloud sync, sharing, social features.
- Speed / distance / range in km (no GPS reading from BMS, and we don't add phone-GPS for MVP-1).
- Landscape orientation.
- BMS settings writes (Volty is read-only against the BMS).
- Widgets / tiles (deferred to v1.1+).
- KLS motor-controller integration (lives in Kelly-connect).

### v1.1+ backlog (informational, not in scope for this spec)

- Trip history persistence (`session_data` table, week stats).
- Android widget / lock-screen tile with live SOC.
- BMS firmware write support (stretch).
- Phone-GPS speedometer overlay → derived km range using historical Wh/h baseline.

## 3. Target users and use cases

- Casual riders monitoring one e-board / e-scooter / e-bike battery.
- Power users with 3-5 batteries across multiple vehicles.
- Hobbyists with home/solar/RV/boat lithium packs.
- Service shops needing fast guest-mode access to a customer pack.

Top user stories:

- "I open the app while standing near my board, it shows the dashboard in ~5 seconds without me touching anything."
- "I have a skateboard and an e-bike; one tap on the top pill switches the active vehicle."
- "I'm not actively riding but I want my phone to ping me if the battery temperature shoots up."
- "I'm at a buddy's place and want to peek at his BMS without saving anything."

## 4. Tech stack

| Component | Library | Version | Notes |
|---|---|---|---|
| Language | Kotlin | 2.3.20 | |
| UI | Compose Multiplatform | 1.11.1 | KMP-style project; Android target only |
| M3 components | `org.jetbrains.compose.material3:material3` | 1.5.0-alpha17 (CMP-bundled) or alpha19 override | Required for `MaterialExpressiveTheme` and Expressive APIs |
| Shape morphing | `androidx.graphics:graphics-shapes` | 1.0.1 | Polygon morphs for avatars and hero card |
| Navigation | Decompose | 3.4.0 | Stable; reuse Kelly's familiarity |
| DI | Koin | 4.2.1 | |
| BLE | Kable | 0.43.0 | |
| Local DB | SQLDelight | 2.2.1 | Vehicles + alert configs |
| Preferences | DataStore Preferences | latest stable | Theme, last-used vehicle id, scan timeout |
| Coroutines | kotlinx-coroutines | 1.10.2 | |
| Time | kotlinx-datetime | 0.6.1 | |
| Serialization | kotlinx-serialization-json | 1.10.0 | |

Risks:
- Expressive APIs (`MaterialExpressiveTheme`, `LoadingIndicator`, `ButtonGroup`, …) are on `material3` alpha track. Tagged with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`. Acceptable for greenfield; revisit when stable.

CI is intentionally light: GitHub Actions can build debug APK and run JVM unit tests on PR, but no snapshot/E2E pipelines for MVP-1.

## 5. Project layout

Repo location: `C:\Users\sodovaya\Desktop\volty\` (new repo, separate from `kelly-connect`).

```
volty/
├── composeApp/
│   ├── androidMain/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/volty/app/
│   │   │   ├── MainActivity.kt
│   │   │   ├── VoltyApplication.kt
│   │   │   ├── di/AndroidModule.kt
│   │   │   ├── service/MonitoringService.kt          // foreground service
│   │   │   ├── service/Notifier.android.kt           // expect/actual notifier
│   │   │   └── permissions/PermissionGate.kt
│   └── commonMain/
│       └── kotlin/com/volty/app/
│           ├── App.kt                                 // Compose root
│           ├── di/AppModule.kt
│           ├── di/RepositoryModule.kt
│           ├── domain/
│           │   ├── model/                             // BmsData, Vehicle, AlertConfig, Chemistry…
│           │   ├── repository/                        // BmsRepository, VehicleRepository
│           │   └── usecase/
│           │       ├── MovingAverageUseCase.kt
│           │       ├── PredictRemainingTimeUseCase.kt
│           │       └── AlertEngine.kt
│           ├── data/
│           │   ├── bms/                               // Copied 1:1 from Kelly
│           │   │   ├── BmsProtocol.kt
│           │   │   ├── JkBmsProtocol.kt
│           │   │   ├── JbdBmsProtocol.kt
│           │   │   ├── AntBmsProtocol.kt
│           │   │   ├── DalyBmsProtocol.kt
│           │   │   ├── CrcUtils.kt
│           │   │   └── ByteArrayAccumulator.kt
│           │   ├── ble/
│           │   │   └── KableBmsRepository.kt          // extended for multi-BMS scan
│           │   ├── db/
│           │   │   ├── VoltyDatabase.sq               // SQLDelight schema
│           │   │   ├── VehicleQueries.sq
│           │   │   └── VehicleRepositoryImpl.kt
│           │   └── prefs/AppPrefs.kt                  // DataStore wrapper
│           └── presentation/
│               ├── root/
│               │   ├── RootComponent.kt
│               │   └── RootScreen.kt
│               ├── welcome/{Component,Screen}.kt
│               ├── permissions/{Component,Screen}.kt
│               ├── scanning/{Component,Screen}.kt
│               ├── autoconnect/{Component,Screen}.kt
│               ├── picker/{Component,Screen}.kt
│               ├── dashboard/{Component,Screen}.kt    // includes vehicle bottom sheet
│               ├── cells/{Component,Screen}.kt
│               ├── graph/{Component,Screen}.kt
│               ├── settings/{Component,Screen}.kt
│               ├── vehicle/{Component,Screen}.kt
│               └── common/
│                   ├── VoltyTheme.kt
│                   ├── MetricCard.kt
│                   ├── SparklineGraph.kt
│                   ├── CountdownRing.kt
│                   ├── MorphingAvatar.kt
│                   ├── VehiclePill.kt
│                   └── CellGrid.kt
└── build.gradle.kts, settings.gradle.kts, gradle.properties
```

No `protocol/` module (Volty does not use the ETS protocol).

## 6. Domain model

```kotlin
data class BmsData(
    val voltage: Float,
    val current: Float,        // + = charging, − = discharging
    val power: Float,          // V × I
    val soc: Float,            // 0..100
    val charge: Float,         // Ah remaining
    val capacity: Float,       // Ah full
    val numCycles: Int,
    val cellVoltages: List<Float>,
    val temperatures: List<Float>,
    val chargeEnabled: Boolean,
    val dischargeEnabled: Boolean,
    val isConnected: Boolean,
    val timestamp: Instant      // new vs. Kelly — required for graphs/averaging
)

enum class BmsType { JK_BMS, JBD_BMS, ANT_BMS, DALY_BMS }

enum class Chemistry {
    LI_ION_NMC,    // 4.20 / 2.80 V/cell typical
    LIFEPO4,       // 3.65 / 2.50 V/cell typical
    LEAD_ACID      // pack-level, no per-cell defaults
}

data class Vehicle(
    val id: String,                     // UUID
    val name: String,
    val iconKey: String,                // preset key: "skateboard" | "ebike" | "scooter" | "moto" | "solar" | "ev" | "boat" | "rv" | "generic"
    val bmsType: BmsType,
    val bmsAddress: String,             // BLE identifier (platform string)
    val chemistry: Chemistry,
    val cellCount: Int?,                // discovered on first connect
    val averagingWindowMin: Int,        // 1 | 5 | 10 | 30 (default 5)
    val alertConfig: AlertConfig,
    val createdAt: Instant,
    val lastConnectedAt: Instant?,
    val isPinned: Boolean
)

data class AlertConfig(
    val cellHighV: Float? = null,        // null → derive from chemistry default
    val cellLowV: Float? = null,
    val cellDeltaMv: Int? = 200,
    val temperatureHighC: Float? = 60f,
    val socLowPercent: Int? = 15,
    val socCutoffPercent: Int? = null,   // off by default
    val disconnectNotify: Boolean = true,
    val chargeCompleteNotify: Boolean = true
)

enum class AlertSeverity { CRITICAL, WARNING, INFO }

data class BmsSample(val data: BmsData, val timestamp: Instant)

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data class Connecting(val vehicle: Vehicle?) : ConnectionState()
    data class Connected(val vehicle: Vehicle?) : ConnectionState()  // null when guest
    data object Disconnected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
```

## 7. Storage

| Data | Storage | Why |
|---|---|---|
| Vehicles + AlertConfig | SQLDelight | Relational, easy to extend with sessions table in v1.1+ |
| App prefs (theme, last-vehicle, scan timeout, dynamic-color, guest-mode-show-saved) | DataStore Preferences | Simple key-value |
| Live `BmsData` and ring buffer for graphs / moving avg | In-memory only | Not persisted in MVP-1 |
| BLE protocol parser state | In-memory | Volatile by design |

DataStore keys:
```
last_vehicle_id: String?
theme_mode: enum (system | light | dark)   // default: system
dynamic_color_enabled: Boolean             // default: true
first_launch_done: Boolean
scan_timeout_seconds: Int                  // default: 5
auto_connect_countdown_seconds: Int        // default: 3
guest_mode_show_saved: Boolean             // default: true
```

Ring buffer (per active vehicle):
- size = `max_window_minutes × sample_rate_per_sec` with headroom; e.g. 30 min × 1 Hz × ~10 floats per sample × 4 bytes ≈ 7 KB
- moving-average uses the same buffer, slicing the configured window

## 8. Repository layer

```kotlin
interface BmsRepository {
    fun scanAll(): Flow<DiscoveredDevice>
    val activeData: StateFlow<BmsData>
    val activeVehicle: StateFlow<Vehicle?>
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(vehicle: Vehicle): Result<Unit>
    suspend fun connectGuest(address: String, type: BmsType): Result<Unit>
    suspend fun disconnect()
    fun samples(window: Duration): Flow<List<BmsSample>>
    fun movingAverage(window: Duration): StateFlow<MovingAvg>
}

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bmsType: BmsType,
    val knownVehicle: Vehicle?
)

data class MovingAvg(
    val avgPowerW: Float,
    val avgCurrentA: Float,
    val window: Duration
)

interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>
    suspend fun get(id: String): Vehicle?
    suspend fun upsert(v: Vehicle)
    suspend fun delete(id: String)
    suspend fun touch(id: String)         // updates lastConnectedAt
}
```

`KableBmsRepository` reuses Kelly's connect/observe/handshake logic, but with these changes:
- `scanAll()` filters by known BMS service UUIDs across all 4 types (no upfront type selection)
- `connect(Vehicle)` resolves BMS type, looks up cached advertisement, creates protocol via factory
- per-sample push to ring buffer in addition to `activeData` StateFlow
- exponential-backoff auto-reconnect on `BLE link lost` (max 3 attempts, 2 s / 5 s / 10 s)

## 9. Connection flow

```
app launch
  if savedVehicles.isEmpty() → Welcome
  if missing permissions → PermissionsGate
  else → Scanning

Scanning (scan_timeout = 5 s; cancellable with "Skip → device list")
  known found in range during scan:
    on 2+ known → cancel scan → Picker(myKnown ∪ otherNearby)
    on timeout with 1 known → AutoConnect(vehicle, countdown = 3 s)
    on timeout with 0 known → NoneFound(retry / quick connect / view saved)

AutoConnect(vehicle, countdown)
  countdown ticks 3 → 0; user can:
    "Connect now" → skip remaining countdown
    "Cancel" → Picker
  on countdown end → repository.connect(vehicle)
    handshake timeout = 5 s
    success → Dashboard (start MonitoringService)
    fail → ConnectionFailed(retry / cancel)

Picker
  shows "My batteries in range" (known) + "Other BMS nearby" (with badge "saved" if matches known but in guest section)
  tap → connect (known) or connectGuest (unknown)
  "+ Add new battery" → scan all unknown → on first successful connect → VehicleEdit(name, icon, chemistry)

Guest scan (from Quick connect)
  same UI as Picker, but no auto-save; tapping a known vehicle still uses its saved config
```

## 10. Foreground service

`MonitoringService` (`androidMain/.../service/`):
- Started on first `connect()` call from `BmsRepository`
- `Service.START_STICKY`
- `serviceType = FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (Android 14+)
- Persistent notification format: `"Volty · {vehicle.name}"` (title), `"{soc}% · {voltage} V · {current_signed} A · ETA {time}"` (text)
- Notification updates throttled to ≥ 2 s between updates to avoid battery drain
- Actions: `Open` (opens MainActivity), `Disconnect` (broadcast → repository.disconnect → stopSelf)
- Stops on `disconnect()` or `Disconnect` action

## 11. Alert engine

`AlertEngine` (commonMain UseCase) subscribes to `BmsRepository.activeData` and `activeVehicle`, applies thresholds, and calls `Notifier` (expect/actual):

| Trigger | Default | Severity | Configurable |
|---|---|---|---|
| Cell V high (> 4.20 for Li-ion, > 3.65 for LiFePO4) | on | CRITICAL | threshold per vehicle |
| Cell V low (< 2.80 for Li-ion, < 2.50 for LiFePO4) | on | CRITICAL | threshold per vehicle |
| Cell delta (Δ = max − min) > 200 mV | on | WARNING | threshold per vehicle |
| Temperature > 60°C | on | CRITICAL | threshold per vehicle |
| SOC < 15% | on | WARNING | threshold per vehicle |
| Disconnect / BLE link lost | on | INFO | toggle |
| Charge complete (SOC = 100% and `|current|` < 0.1 A for 30 s) | on | INFO | toggle |
| Discharge cutoff approaching (SOC < 5%) | off | CRITICAL | toggle |

Behavior:
- Debounce 3 s per alert kind to prevent spam from sensor jitter
- One-shot per crossing (re-arms when value crosses back over hysteresis ±5 %)
- Critical alerts use high-priority notification channel with sound + vibration
- Warning uses default channel with vibration
- Info uses low-priority silent channel

Default thresholds derived from `Chemistry` enum when `AlertConfig.cellHighV` etc. are null.

## 12. Presentation

Navigation: Decompose `StackNavigation<Config>`.

```
Config (sealed)
  Welcome
  PermissionsGate
  Scanning
  AutoConnect(vehicle)
  Picker(mode: Add | Guest | Cold)
  Dashboard
  Cells
  Graph
  Settings
  VehicleEdit(vehicleId?: String)
```

Component per screen (state + events through `StateFlow<UiState>` + `onEvent(...)`). Bottom-sheet vehicle switcher lives inside `DashboardComponent` (its state belongs to Dashboard).

Shared composables (`presentation/common/`):

- `VoltyTheme` — wraps `MaterialExpressiveTheme` with dynamic-color on Android 12+ and Volty fallback palette
- `VehiclePill` — top capsule (avatar + name + status), used on Dashboard / Cells / Graph
- `MetricCard` — base metric tile with `Default | Tertiary | Primary` variants
- `SparklineGraph` — Canvas-based, path + glow gradient
- `MorphingAvatar` — animates `borderRadius` via spring (used for charging "pulse", connecting state)
- `CountdownRing` — Canvas, rotating arc + center number
- `CellGrid` — compact 3-column cell list; target 21 cells without scroll (24 px row height); supports min/max highlight

Visual direction:
- Material 3 Expressive: `MaterialExpressiveTheme`, `MotionScheme.expressive()`, `LoadingIndicator`, `ButtonGroup` for bottom tabs
- Dynamic color on Android 12+ (default on)
- Fallback palette: indigo-violet primary (`#5b53d4`), green tertiary (`#2e6b3a` "good")
- Light + dark + system; portrait only
- Hero card uses asymmetric squircle (28-36-28-36 px corners) and morphs between primaryContainer (discharging) and tertiaryContainer (charging) using `RoundedPolygon` from `androidx.graphics:graphics-shapes`
- Bottom navigation uses Expressive button group with asymmetric squircle active state

Dashboard composition (top → bottom):
1. `VehiclePill` (40 px) — tap → ModalBottomSheet with vehicle list
2. Hero card — SOC %, "X.X / Y.Y Ah remaining", progress bar, ETA + instantaneous A
3. Metric grid 2-col (Voltage, Power, sparkline-wide, Temperature, Cells)
4. Bottom button group: Live | Cells | Graph | ⚙ (settings)

Cells detail:
- Three summary cards (Max / Min / Δ) with cell index hints
- 3-column compact list of cells with progress meter and per-cell voltage; min and max highlighted
- Goal: ≥ 21 cells visible without scroll

Graph detail:
- Top segmented control: SOC / Power / Current / Voltage / Temperature
- Big number + secondary stats (avg / peak)
- Canvas line graph with grid + filled area under the line + now-marker
- Window chips: 1m / 5m / 15m / 1h / All
- Stats row: avg, peak, min, used (energy)

## 13. Permissions (Android)

Manifest:
- `BLUETOOTH_SCAN` (Android 12+, with `usesPermissionFlags=neverForLocation` since we don't derive location)
- `BLUETOOTH_CONNECT` (Android 12+)
- `ACCESS_FINE_LOCATION` (Android 11 and below, for BLE scan)
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` (Android 14+)
- `POST_NOTIFICATIONS` (Android 13+)

`PermissionsGate` component requests at first scan or first launch; offers "Open settings" deep-link on permanent denial.

## 14. Error handling

| Source | UX |
|---|---|
| Permission denied | blocking dialog with "Open settings" |
| BLE adapter off | inline banner on Scanning screen with system intent |
| Handshake timeout 5 s | `Failed("BMS не отвечает. Проверьте, что пакет включён.")` + retry |
| BLE link lost while connected | sticky notification → "Reconnecting…", repository tries 3× (2 / 5 / 10 s) then disconnects |
| Bad CRC / parse error | silent — protocol drops; surfaces only as missing data |
| SQLDelight migration failure | recovery dialog with export of current vehicles JSON |

Pattern:
- Repositories return `Result<T>` for actions, `Flow<X>` for streams
- Components map errors into `UiState.Error(message)` for UI
- No uncontrolled throws across the layer boundary

## 15. Testing

| Layer | Test scope | Tools |
|---|---|---|
| BMS parsers (`bms/`) | Unit tests, ported from Kelly + extra edge cases | `kotlin.test` |
| Moving average / predict | Pure-Kotlin unit tests with synthetic sample streams | `kotlin.test` |
| Alert engine | Fake `BmsData` flow → assert notifier called with right severity/debounce | `kotlin.test`, fakes |
| Repositories | Fake transport, exercise the state machine | `kotlin.test`, Turbine |
| Components (Decompose) | Drive `onEvent`, observe state via Turbine | Turbine + JUnit |
| UI snapshots | Optional, post-MVP | Roborazzi on JVM |
| Manual E2E | Real BMS + real Android, checklist `docs/qa-checklist.md` | — |

Not tested: BLE on emulator (unsupported), foreground-service lifecycle (fragile, manual), notification rendering (manual).

CI: minimal — Gradle build + JVM unit tests on PR. No snapshot or E2E pipelines for MVP-1.

## 16. Risks / open questions

- **Expressive APIs on alpha track.** Build can break with each `material3` alpha bump. Mitigation: pin to the version bundled with the chosen CMP release; review at each CMP upgrade.
- **BLE scan on Android 11 and below still requires location permission** despite `neverForLocation`. Users may be confused; explain in PermissionsGate copy.
- **Dynamic color on devices where wallpaper is monochrome** can produce a low-saturation palette. The hero accent then loses visual punch. Mitigation: fallback Volty palette is selectable in Settings.
- **JK BMS streaming vs. polling BMS interplay in shared scan.** All 4 protocols already coexist in Kelly's repository, but JK's streaming-after-handshake means we cannot multiplex two BMS on one BLE adapter without disconnect/reconnect. Volty intentionally allows only one active connection at a time.
- **Foreground service notification UX is mandatory on Android.** Cannot be hidden. Users who dislike it can use in-app-only mode by force-stopping the service, but they'll lose alerts. Accepted trade-off.
- **Range estimate is in time + energy only.** No km. v1.1+ may add GPS-based speed, but not promised.

## 17. Out of scope (explicitly)

- iOS, web, desktop targets.
- BMS settings writes (read-only).
- Cloud sync, shared dashboards, multi-user accounts.
- Bluetooth Classic / USB transports (BLE only).
- Landscape orientation.
- Trip log persistence (deferred).
- Range in kilometres (deferred).
- Widgets (deferred).
