# Volty Plan 3 — Dashboard, detail screens, M3 Expressive theme

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Replace the temporary `DebugScreen` with the full production Dashboard (hero SOC card, metric grid, bottom button group, vehicle bottom sheet). Add Cells detail (compact 3-column grid), Graph detail (5 metrics × 5 windows), Settings (theme, dynamic color, scan timeout, vehicle list). Apply Material 3 Expressive theme + Volty fallback palette.

**Architecture:** Shared composables in `presentation/common/` (VoltyTheme, MetricCard, SparklineGraph, etc). Decompose adds `Dashboard`/`Cells`/`Graph`/`Settings` as proper navigation destinations. `DashboardComponent` reads from `BmsRepository` + `MovingAverage` and exposes a derived UI state.

**Builds on:** Plan 1 (foundation) + Plan 2 (persistence + flow).

**Spec:** `docs/superpowers/specs/2026-05-23-volty-design.md`

---

## File map

```
volty/
├── gradle/libs.versions.toml        (modify — add androidx.graphics:graphics-shapes)
├── composeApp/build.gradle.kts      (modify — add shapes dep, override material3 to alpha)
├── composeApp/src/
│   ├── commonMain/kotlin/com/volty/app/
│   │   ├── presentation/
│   │   │   ├── common/
│   │   │   │   ├── VoltyTheme.kt              (new — MaterialExpressiveTheme + palette)
│   │   │   │   ├── VoltyShapes.kt             (new — expressive shape system, asymmetric squircles)
│   │   │   │   ├── VoltyColors.kt             (new — fallback palette)
│   │   │   │   ├── VehiclePill.kt             (new — top bar capsule)
│   │   │   │   ├── MetricCard.kt              (new — container variants)
│   │   │   │   ├── SparklineGraph.kt          (new — canvas line + glow)
│   │   │   │   └── PowerRangeBar.kt           (new — min/peak with now-marker)
│   │   │   ├── dashboard/
│   │   │   │   ├── DashboardComponent.kt      (new — derives UI state from BmsRepository)
│   │   │   │   ├── DashboardScreen.kt         (new)
│   │   │   │   └── VehicleSheet.kt            (new — bottom-sheet vehicle switcher)
│   │   │   ├── cells/
│   │   │   │   ├── CellsComponent.kt          (new)
│   │   │   │   └── CellsScreen.kt             (new — compact 3-col grid, 21+ cells)
│   │   │   ├── graph/
│   │   │   │   ├── GraphComponent.kt          (new)
│   │   │   │   └── GraphScreen.kt             (new — 5 metrics × 5 windows, canvas line graph)
│   │   │   ├── settings/
│   │   │   │   ├── SettingsComponent.kt       (new)
│   │   │   │   └── SettingsScreen.kt          (new — theme, dynamic color, vehicle list)
│   │   │   └── root/RootComponent.kt          (modify — add Cells/Graph/Settings configs; replace DebugComponent with DashboardComponent)
│   │   ├── App.kt                             (modify — wrap in VoltyTheme)
│   │   └── presentation/debug/                (DELETE — replaced by Dashboard)
│   ├── androidMain/kotlin/com/volty/app/
│   │   └── presentation/common/DynamicColor.android.kt   (new — Android 12+ dynamic color)
```

---

## Task 1: Add `androidx.graphics:graphics-shapes` + verify material3 alpha override

**Files:** `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`

- [ ] Add to `[versions]`: `graphics-shapes = "1.0.1"`.
- [ ] Add to `[libraries]`: `graphics-shapes = { module = "androidx.graphics:graphics-shapes", version.ref = "graphics-shapes" }`.
- [ ] Add to `androidMain.dependencies`: `implementation(libs.graphics.shapes)`.
- [ ] Confirm `compose-material3` resolves to a version that includes Expressive APIs. CMP 1.11.x bundles `material3 1.5.0-alpha17`. If `MaterialExpressiveTheme` is unresolved, add an explicit override in `commonMain.dependencies`: `implementation("org.jetbrains.compose.material3:material3:1.9.0-alpha04")` — adjust the exact alpha version to what's actually available at build time. Note in commit that this is the version used.
- [ ] `./gradlew :composeApp:assembleDebug`. Commit `chore(gradle): add graphics-shapes for shape morphing`.

---

## Task 2: VoltyTheme with M3 Expressive + dynamic color

**Files:**
- `composeApp/src/commonMain/kotlin/com/volty/app/presentation/common/VoltyTheme.kt`
- `composeApp/src/commonMain/kotlin/com/volty/app/presentation/common/VoltyColors.kt`
- `composeApp/src/androidMain/kotlin/com/volty/app/presentation/common/DynamicColor.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/App.kt`

### Implementation outline

`VoltyColors.kt` — define `voltyLightColors` and `voltyDarkColors` as `ColorScheme` values matching the spec's palette: primary indigo-violet (`#5b53d4`), tertiary green (`#2e6b3a`), error red, plus container colors derived per M3 tonal palette spec. Use either `lightColorScheme(...)` from material3, or manually populate all 30+ tokens.

`VoltyTheme.kt`:

```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoltyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = when {
        dynamicColor && supportsDynamicColor() -> dynamicVoltyColors(darkTheme)
        darkTheme -> voltyDarkColors
        else -> voltyLightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
```

`supportsDynamicColor()` and `dynamicVoltyColors()` are `expect` functions implemented in `androidMain/.../DynamicColor.android.kt`:

```kotlin
actual fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun dynamicVoltyColors(darkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
```

`App.kt`:
```kotlin
@Composable
fun App(root: RootComponent) {
    VoltyTheme {
        Surface { RootScreen(root) }
    }
}
```

- [ ] If `MaterialExpressiveTheme` / `MotionScheme.expressive()` / `ExperimentalMaterial3ExpressiveApi` are unresolved, that means the bundled material3 version doesn't include Expressive yet. Either override the material3 dep (see Task 1 note) OR fall back to `MaterialTheme(colorScheme = colors)` and remove the motionScheme/expressive bits — document that the theme isn't expressive yet and revisit when CMP catches up.
- [ ] Build + commit `feat(theme): VoltyTheme with M3 Expressive + dynamic color on A12+`.

---

## Task 3: Shared composables (MetricCard, SparklineGraph, PowerRangeBar, VehiclePill)

**Files:**
- `composeApp/src/commonMain/kotlin/com/volty/app/presentation/common/MetricCard.kt`
- `composeApp/src/commonMain/kotlin/com/volty/app/presentation/common/SparklineGraph.kt`
- `composeApp/src/commonMain/kotlin/com/volty/app/presentation/common/PowerRangeBar.kt`
- `composeApp/src/commonMain/kotlin/com/volty/app/presentation/common/VehiclePill.kt`

### `MetricCard`

```kotlin
enum class MetricCardVariant { Default, Tertiary, Primary }

@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    variant: MetricCardVariant = MetricCardVariant.Default,
    sub: String? = null,
    extra: @Composable (() -> Unit)? = null
) { /* surfaceContainer / tertiaryContainer / primaryContainer per variant */ }
```

### `SparklineGraph`

```kotlin
@Composable
fun SparklineGraph(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    glowAlpha: Float = 0.15f
) { /* Canvas line path + filled glow under */ }
```

Auto-fit min/max of values to canvas height. Empty list → render nothing.

### `PowerRangeBar`

Min/peak range with a now-marker. Inputs: `min: Float`, `peak: Float`, `now: Float`, `color: Color`.

### `VehiclePill`

Top capsule with avatar + name + status text + chevron, tappable to open vehicle sheet:

```kotlin
@Composable
fun VehiclePill(
    name: String,
    statusText: String,
    statusColor: Color = MaterialTheme.colorScheme.tertiary,
    iconKey: String,
    onClick: () -> Unit
) { ... }
```

- [ ] Build. Single commit `feat(ui): shared composables for dashboard`.

---

## Task 4: DashboardComponent + DashboardScreen + VehicleSheet

**Files:** dashboard/* (3 files)

### `DashboardComponent`

Derives UI state from:
- `bmsRepository.activeData`
- `bmsRepository.activeVehicle`
- `bmsRepository.movingAverage(window)` — window from `activeVehicle.averagingWindowMin`
- `bmsRepository.samples(5.minutes)` — for sparkline
- `vehicleRepository.vehicles` — for sheet

```kotlin
data class State(
    val vehicle: Vehicle? = null,
    val data: BmsData = BmsData(),
    val avgPowerW: Float = 0f,
    val avgCurrentA: Float = 0f,
    val powerMin: Float = 0f,
    val powerPeak: Float = 0f,
    val sparkline: List<Float> = emptyList(),
    val cellsMinV: Float = 0f,
    val cellsMaxV: Float = 0f,
    val cellsDeltaMv: Int = 0,
    val savedVehicles: List<Vehicle> = emptyList(),
    val sheetOpen: Boolean = false
)
```

Compute derived fields with simple reactive flows.

Events: `onTabClicked(tab)` → push Cells/Graph/Settings configs. `onSheetToggle()` → toggle `sheetOpen`. `onSwitchVehicle(v)` → disconnect, connect to `v`. `onDisconnect()`.

### `DashboardScreen`

Layout:
1. `VehiclePill` at top.
2. Hero card: SOC% (big), "X.X / Y.Y Ah remaining", progress bar, "≈ time at avg" + instantaneous A. Use `primaryContainer` (discharging) or `tertiaryContainer` (charging).
3. Metric grid 2-column:
   - Voltage card
   - Power card (with PowerRangeBar)
   - Wide sparkline (Power · last 5 min)
   - Temperature card (with T1/T2 chips)
   - Cells card (avg V + Δ + micro-bars showing per-cell spread)
4. Bottom button group: Live | Cells | Graph | ⚙

### `VehicleSheet`

`ModalBottomSheet` with the saved vehicles list. Tap to switch active. "+ Add battery" at bottom.

- [ ] Wire into RootComponent: `Config.Dashboard` now creates `DefaultDashboardComponent` instead of `DefaultDebugComponent`. Push handlers for Cells/Graph/Settings.
- [ ] Build. Commit `feat(ui): Dashboard with hero + metric grid + vehicle sheet`.

---

## Task 5: Cells detail screen

**Files:** cells/*

3 summary cards (Max / Min / Δ) at the top + a 3-column compact grid of all cells. Target: ≥21 cells visible without scroll (24 dp row height including padding).

Each cell row: index (e.g. "1"), voltage (3 decimal places), micro-bar showing relative position within the cell-V range. Min and Max cells highlighted with `primaryContainer` / warning color.

`CellsComponent.State`:
```kotlin
data class State(
    val cells: List<Cell> = emptyList(),
    val maxIdx: Int = -1,
    val minIdx: Int = -1,
    val deltaMv: Int = 0,
    val avgV: Float = 0f
)
data class Cell(val index: Int, val voltageV: Float, val rangeFraction: Float)
```

Derive from `bmsRepository.activeData.cellVoltages`.

- [ ] Wire into RootComponent. Commit `feat(ui): Cells detail with compact 3-col grid`.

---

## Task 6: Graph detail screen

**Files:** graph/*

Top: segmented control (SOC / Power / Current / Volt / Temp).
Middle: large canvas line graph + grid + now-marker (path computed from `samples()` filtered to selected window).
Window chips at bottom of graph: 1m / 5m / 15m / 1h / All.
Stats row: avg / peak / min / used (Wh for power, Ah for current, etc.).

`GraphComponent.State`:
```kotlin
data class State(
    val metric: GraphMetric = GraphMetric.Power,
    val window: GraphWindow = GraphWindow.M5,
    val samples: List<BmsData> = emptyList(),
    val avg: Float = 0f,
    val peak: Float = 0f,
    val min: Float = 0f,
    val used: Float = 0f
)
enum class GraphMetric { SOC, POWER, CURRENT, VOLTAGE, TEMPERATURE }
enum class GraphWindow { M1, M5, M15, H1, ALL }
```

`used` is integration over time: for Power → Wh, for Current → Ah.

- [ ] Wire into RootComponent. Commit `feat(ui): Graph detail with 5 metrics × 5 windows`.

---

## Task 7: Settings screen

**Files:** settings/*

Sections:
1. Theme: system / light / dark (segmented control).
2. Dynamic color: switch (Android 12+).
3. Default scan timeout (slider 3-15 s).
4. Default auto-connect countdown (slider 0-10 s).
5. Saved vehicles list: tap a row → `Config.VehicleEdit(id)`. Long-press → confirm dialog → delete.
6. "+ Add new battery" at bottom.

`SettingsComponent` reads `AppPrefs` flows + `vehicleRepository.vehicles`.

- [ ] Wire into RootComponent. Commit `feat(ui): Settings with theme + vehicle management`.

---

## Task 8: Wire VehicleSheet ↔ Dashboard ↔ Detail screens; remove DebugScreen

- [ ] Delete `composeApp/src/commonMain/kotlin/com/volty/app/presentation/debug/` directory.
- [ ] `RootComponent.kt`: replace `Config.Dashboard` branch's `DefaultDebugComponent` with `DefaultDashboardComponent`. Add new branches for `Config.Cells`, `Config.Graph`, `Config.Settings`. Push them from Dashboard's tab button group.
- [ ] Update `RootScreen.kt` to render the new screens.
- [ ] Build + tests. Commit `feat(nav): wire Dashboard/Cells/Graph/Settings; remove DebugScreen`.

---

## Task 9: Plan 3 smoke test

Create `docs/qa/plan-3-smoke-test.md`:

1. Theme: toggle in Settings → light/dark/system applies. Dynamic color toggle changes accent (on A12+).
2. Connect a BMS. Dashboard renders: SOC, V, A, P, ETA, sparkline, temperatures, cell delta. Charging → green tertiary container; discharging → indigo primary container.
3. Tap "Cells" tab: detail screen lists all cells. Confirm ≥21 cells visible without scroll if pack has 21+ cells.
4. Tap "Graph" tab: switch metric, switch window. Lines render. Stats update.
5. Tap vehicle pill: bottom sheet opens with saved vehicles. Switch → reconnects.
6. Settings → vehicles list → edit a vehicle → save → returns to settings.

- [ ] Commit `docs: plan 3 smoke-test checklist`.

---

## Definition of done for Plan 3

- Dashboard fully replaces DebugScreen.
- M3 Expressive theme applied (or fallback documented if API not available at build time).
- All 4 main screens (Dashboard, Cells, Graph, Settings) reachable and functional.
- VehicleSheet allows switching between saved vehicles.
- Vehicle CRUD reachable from Settings.
- Build green, tests green, APK installs.
