# Telemetry Graphs and Ride History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Each task is independently testable and uses checkbox (`- [ ]`) tracking.

**Goal:** Extend the existing graph destination into an honest, timestamped telemetry workspace with battery/cell/controller series, synchronized multi-card selection, XY comparisons, and persisted completed rides.

**Architecture:** Keep BLE decoding and repository contracts unchanged wherever possible. Add a pure graph telemetry layer that maps `BmsData` and `ControllerData` into timestamped, evidence-gated series; let `GraphComponent` merge the two existing repository flows; let Compose render adaptive cards and forward timestamp/metric intents. Persist downsampled, already-decoded points through a small SQLDelight history repository and a recorder attached to the existing sample funnel.

**Tech Stack:** Kotlin Multiplatform common code, Compose Multiplatform Material 3, Decompose, kotlinx-coroutines/Flow, SQLDelight migrations, kotlin.test and `runTest`.

## Global Constraints

- `minSdk = 26`; never use SQLite features newer than the device SQLite version.
- Preserve the unknown-vs-zero contract: an unavailable metric produces no point, never a fabricated `0f`.
- `BmsRepository.motionSamples(window)` is the vehicle-level motion aggregate and must remain the motion graph source.
- Existing battery graph sign conventions remain explicit and tested; do not move sign inversion into shared energy integration.
- The app UI needs strings in both `composeResources/values/strings.xml` and `values-ru/strings.xml`.
- Compose has no unit-testable UI source set; test chart geometry, selection, mapping, and component state in common tests and report device visual QA separately.
- SQLDelight migration `N.sqm` migrates `N.db` to `(N+1).db`; schema removal requires table rebuilds, not `DROP COLUMN`.
- Do not write to any BLE characteristic as part of graph/history work.
- Every task ends with a focused test command and a small commit; run the full suite before release.

---

### Task 1: Add the pure timestamped telemetry model

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphTelemetry.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphTelemetryMapper.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponent.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphTelemetryTest.kt`

**Interfaces:**
- Consumes: `BmsData`, `ControllerData`, and the existing `GraphMetric` names (`SOC`, `POWER`, `CURRENT`, `VOLTAGE`, `TEMPERATURE`).
- Produces: `GraphMetric`, `GraphPoint`, `GraphSeries`, `nearestPoint`, `pairByNearestTimestamp`, and metric mappers used by later component/UI tasks.

- [x] **Step 1: Write failing pure tests**

```kotlin
@Test
fun `battery power omits an unearned value even when numeric field is nonzero`() {
    val data = BmsData(power = 4200f, hasPower = false, timestamp = t0)
    assertNull(GraphTelemetryMapper.battery(data, GraphMetric.POWER))
}

@Test
fun `cell delta is max minus min in millivolts`() {
    val data = BmsData(cellVoltages = listOf(4.01f, 4.17f, 4.08f), timestamp = t0)
    assertEquals(160f, GraphTelemetryMapper.battery(data, GraphMetric.CELL_DELTA_MV)!!.value)
}

@Test
fun `nearest point ties choose the earlier timestamp`() {
    assertEquals(t0, nearestPoint(listOf(point(t0, 1f), point(t2, 2f)), t1)?.timestamp)
}

@Test
fun `xy pairing drops samples beyond maximum gap`() {
    val pairs = pairByNearestTimestamp(
        x = listOf(point(t0, 10f)), y = listOf(point(t0 + 30.seconds, 60f)), maxGap = 2.seconds
    )
    assertTrue(pairs.isEmpty())
}
```

- [x] **Step 2: Run the focused test and verify it fails**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.graph.GraphTelemetryTest" --no-build-cache --rerun-tasks --console=plain`

Expected: compilation/test failure because the new metric/model functions do not exist.

- [x] **Step 3: Implement the metric catalogue and immutable point types**

Keep the existing enum entries as aliases for the current BMS graph and add stable entries for `CELL_MIN_V`, `CELL_MAX_V`, `CELL_DELTA_MV`, `SPEED`, `DUTY`, `MOTOR_CURRENT`, `INPUT_VOLTAGE`, `MOTOR_POWER`, `ERPM`, `ESC_TEMPERATURE`, and `MOTOR_TEMPERATURE`. Add source/group metadata without importing Compose resources:

```kotlin
enum class GraphSource { BATTERY, MOTION }

data class GraphPoint(val timestamp: Instant, val value: Float)
data class GraphSeries(val metric: GraphMetric, val points: List<GraphPoint>)
data class GraphPair(val x: GraphPoint, val y: GraphPoint)
```

Implement evidence-gated extraction in `GraphTelemetryMapper`: `socKnown`, BMS `hasPower`/`hasCurrent`, non-empty cell/temperature lists, motion `speedKnown`, `hasDuty`, `hasBatteryCurrent`, `hasInputVoltage`, `hasPower`, `hasEscTemp`, and `hasMotorTemp`. Preserve the current battery power/current display transform as a metric-level transform, not in the repository.

- [x] **Step 4: Implement deterministic timestamp helpers**

`nearestPoint(points, target)` sorts/assumes timestamp order, returns the closest point, and resolves equal distances to the earlier sample. `pairByNearestTimestamp(x, y, maxGap)` emits one pair per X point, chooses the same tie rule, and drops a pair when the absolute gap exceeds `maxGap`.

- [x] **Step 5: Run the focused test and commit**

Run the command from Step 2; expected: all focused tests pass. Commit:

```text
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphTelemetryTest.kt
git commit -m "feat(graph): add timestamped telemetry model"
```

### Task 2: Merge BMS and motion streams in `GraphComponent`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponent.kt`
- Modify: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponentUsedTest.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponentTelemetryTest.kt`

**Interfaces:**
- Consumes: `BmsRepository.samples`, `BmsRepository.motionSamples`, and Task 1 mappers.
- Produces: `GraphComponent.State.series`, `visibleMetrics`, `selectedTimestamp`, `selectedPoints`, and intents `onMetricAdded`, `onMetricRemoved`, `onTimestampSelected`, `onComparisonRequested`.

- [x] **Step 1: Extend the component contract and write failing tests**

Add state fields without exposing raw repository models to Compose:

```kotlin
val visibleMetrics: List<GraphMetric> = listOf(GraphMetric.POWER)
val series: Map<GraphMetric, GraphSeries> = emptyMap()
val selectedTimestamp: Instant? = null
val selectedPoints: Map<GraphMetric, GraphPoint> = emptyMap()
```

Add tests proving that motion metrics use `motionSamples`, battery metrics use `samples`, streams with different timestamps remain intact, and selecting one timestamp resolves nearest honest points per metric. Keep `values`, `nowValue`, `avg`, `peak`, `min`, and `used` as derived compatibility fields until the screen migration is complete.

- [x] **Step 2: Run the focused tests and verify failure**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.graph.GraphComponentTelemetryTest" --tests "ru.sodovaya.volty.presentation.graph.GraphComponentUsedTest" --no-build-cache --rerun-tasks --console=plain`

Expected: compilation failures for the new state/intents and failing motion-series assertions.

- [x] **Step 3: Collect both repository flows for the selected window**

Use one coroutine and `combine` over `samples(duration)` and `motionSamples(duration)`. Map each list into `GraphSeries`, retain timestamp order, and rebuild all visible series on every emission. For `GraphWindow.ALL`, use the history-backed window supplied by the later ride task; until then retain the existing six-hour request and the ring buffer’s actual four-hour cap without inventing points.

- [x] **Step 4: Implement metric-card and selection intents**

`onMetricAdded` is idempotent, `onMetricRemoved` never removes the final card, and `onTimestampSelected(null)` clears selection. Recompute `selectedPoints` with `nearestPoint` for every visible series; a metric with no point remains absent. Preserve legacy `onMetricSelected` by replacing the first visible card so existing navigation/tests remain source-compatible.

- [x] **Step 5: Run focused tests and commit**

Run the command from Step 2; expected: all graph component tests pass. Commit:

```text
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponent.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph
git commit -m "feat(graph): merge battery and motion series"
```

### Task 3: Replace the single graph screen with adaptive multi-card UI

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-ru/strings.xml`

**Interfaces:**
- Consumes: Task 2 `GraphComponent.State` and callbacks; Task 1 geometry/selection helpers.
- Produces: portrait/landscape adaptive cards, synchronized selection marker, metric picker, and accessible value labels.

- [x] **Step 1: Keep rendering code pure at the boundary**

Refactor the private chart to accept `GraphSeries` and `selectedTimestamp`, while keeping range calculation and timestamp-to-X mapping in common pure helpers. The Canvas only draws paths, grid, “now” marker, and selected marker.

- [x] **Step 2: Add pointer selection**

Use `pointerInput(series)` with tap/drag gesture handling. Convert the local X coordinate to the nearest timestamp using chart geometry, then call `component.onTimestampSelected(timestamp)`. Do not select by list index or wall-clock delay.

- [x] **Step 3: Implement adaptive cards**

Use `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 280.dp))` inside the scaffold content, with `WindowInsets` supplied by `Scaffold` and measured available width. Keep the metric picker in a `LazyRow` grouped by Battery/Motion/Cells; landscape naturally gets multiple columns and portrait remains one column. Add remove/add actions without fixed device offsets.

- [x] **Step 4: Render shared selection details**

Show the selected local time and each card’s nearest honest point. Show the Russian/English “нет данных”/“No data” string when a metric has no sample near the selected time. Preserve current/average/peak/min/used summaries for the active card.

- [x] **Step 5: Add all strings in both locales and run compilation**

Add labels for speed, duty, RPM, ESC/motor temperature, cell min/max/spread, selected time, add/remove chart, and comparison. Run:

```text
.\gradlew.bat :composeApp:compileDebugKotlin --no-build-cache --rerun-tasks --console=plain
```

Expected: compile success. Device screenshot QA is intentionally separate because this repository has no Compose UI test source set.

- [x] **Step 6: Commit**

```text
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphScreen.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-ru/strings.xml
git commit -m "feat(graph): add adaptive multi-card charts"
```

### Task 4: Add SQLDelight ride and telemetry-point storage

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/RideRow.sq`
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/RidePointRow.sq`
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/10.sqm`
- Generate: `composeApp/src/commonMain/sqldelight/databases/11.db`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/RideHistoryRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightRideHistoryRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/VoltyDatabaseProvider.kt` only if a shared query accessor is needed.
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightRideHistoryRepositoryTest.kt`

**Interfaces:**
- Consumes: `GraphMetric`, `GraphPoint`, vehicle id, and SQLDelight’s generated `VoltyDatabase`.
- Produces: `RideSummary`, `StoredRide`, `RidePoint`, `startRide`, `appendPoint`, `finishRide`, `listRides`, `loadRide`, `deleteRide`, and `pruneOldest`.

- [x] **Step 1: Write repository tests against `JdbcSqliteDriver.IN_MEMORY`**

Cover start/finish round-trip, point ordering, evidence/known bit, optional cell index, deleting one ride, and pruning the oldest ride while leaving newer rows. Use `VoltyDatabase.Schema.create(driver)` and assert that no unknown point is returned as a numeric zero.

- [x] **Step 2: Add schema definitions at the current version boundary**

`RideRow` stores `id`, `vehicleId`, `startedAt`, nullable `endedAt`, and summary fields needed by the history list. `RidePointRow` stores `(rideId, metric, timestamp, cellIndex)` as a composite primary key plus `value` and `isKnown`. Index by `(rideId, timestamp)` and `(vehicleId, startedAt DESC)`.

- [x] **Step 3: Add the v10 → v11 migration**

Confirm `10.db` is the current snapshot, create `10.sqm` with only the two new tables/indexes, then run `generateCommonMainVoltyDatabaseSchema` to produce `11.db`. Do not edit old migrations and do not use `DROP COLUMN`.

- [x] **Step 4: Implement the repository with transactions**

Serialize a ride start/finish and point inserts in SQLDelight transactions. Store timestamps as ISO-8601 strings, parse them back to `Instant`, and map metric enum names defensively so an unknown future metric is skipped rather than crashing history loading.

- [x] **Step 5: Run focused repository and migration checks and commit**

Run:

```text
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.db.SqlDelightRideHistoryRepositoryTest" --no-build-cache --rerun-tasks --console=plain
.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration --no-build-cache --rerun-tasks --console=plain
```

Expected: tests and migration verifier pass. Commit:

```text
git add composeApp/src/commonMain/sqldelight composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/RideHistoryRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightRideHistoryRepository.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightRideHistoryRepositoryTest.kt
git commit -m "feat(history): persist downsampled ride telemetry"
```

### Task 5: Record bounded ride telemetry from the existing sample funnel

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/history/RideTelemetryRecorder.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/history/RideTelemetryRecorderTest.kt`

**Interfaces:**
- Consumes: accepted `BmsData`/`ControllerData` samples at the existing funnel, `activeVehicle`, connection transitions, and `RideHistoryRepository`.
- Produces: one downsampled completed ride per connection session, with deterministic bucket writes and bounded retention.

- [x] **Step 1: Write failing recorder tests**

Use a fake history repository and fake clock to prove: a session starts once for an active vehicle, repeated frames in one bucket collapse to the latest known value, different metrics may be emitted in the same bucket, disconnect finishes the ride, reconnect starts a new ride, and oldest rides are pruned after the configured cap.

- [x] **Step 2: Implement bucketed recording**

Define a five-second default bucket in one constant. Map each accepted aggregate sample through `GraphTelemetryMapper`, keep the latest point per `(metric, cellIndex, bucket)`, flush completed buckets to the history repository, and never insert absent metrics. Keep recorder writes off the sample callback’s critical section by sending immutable batches to its own coroutine channel.

- [x] **Step 3: Integrate without changing BLE wire behaviour**

Invoke the recorder only after the existing accepted-sample transaction has updated the ring buffers/active flows. Give production `KableBmsRepository` a `RideHistoryRepository` dependency through Koin; make the test factory default to a no-op implementation so existing repository tests do not need database setup. Finish/flush on disconnect and lifecycle destruction.

- [x] **Step 4: Run recorder and existing motion tests and commit**

Run:

```text
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.history.RideTelemetryRecorderTest" --tests "ru.sodovaya.volty.data.ble.KableBmsRepositoryMotionTest" --no-build-cache --rerun-tasks --console=plain
```

Expected: focused tests pass. Commit:

```text
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/history/RideTelemetryRecorder.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/history/RideTelemetryRecorderTest.kt
git commit -m "feat(history): record bounded telemetry rides"
```

### Task 6: Load completed rides and expose history actions in the graph component

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootComponent.kt` only if Koin/history injection is not available at graph construction.
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphHistoryComponentTest.kt`

**Interfaces:**
- Consumes: `RideHistoryRepository` and Task 2 graph state.
- Produces: `history: List<RideSummary>`, `selectedRideId`, `onRideSelected`, `onLiveRideSelected`, and series loaded from persisted points.

- [x] **Step 1: Write failing history-state tests**

Assert that opening the graph defaults to live data, selecting a completed ride replaces live series with stored points, the same timestamp selection works on stored series, deleting a ride refreshes the list, and an empty history is represented as an empty list rather than an error.

- [x] **Step 2: Add history dependency to graph construction**

Pass the Koin `RideHistoryRepository` into `DefaultGraphComponent` from the graph child factory. Keep the repository interface injectable so component tests use a fake.

- [x] **Step 3: Implement live/history mode switching**

When `selectedRideId == null`, keep collecting the two live flows. When a ride is selected, cancel only the live collection job, load stored points, and expose the ride’s start/end summary. Selecting “current ride” restarts live collection and clears timestamp selection.

- [x] **Step 4: Run focused tests and commit**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.graph.GraphHistoryComponentTest" --no-build-cache --rerun-tasks --console=plain`

Expected: all focused tests pass. Commit:

```text
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponent.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootComponent.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphHistoryComponentTest.kt
git commit -m "feat(graph): browse persisted ride history"
```

### Task 7: Add the XY comparison mode

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphTelemetry.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-ru/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphComparisonTest.kt`

**Interfaces:**
- Consumes: two `GraphSeries` values and Task 1 `pairByNearestTimestamp`.
- Produces: `ComparisonState(xMetric, yMetric, pairs, selectedPair)` and `onComparisonRequested(x, y)`.

- [x] **Step 1: Add failing comparison tests**

Cover voltage-vs-current pairing, tie-breaking, unknown point omission, selection returning the source timestamp, and rejection of a comparison where either metric has no series.

- [x] **Step 2: Implement comparison state**

Use a fixed two-second maximum pairing gap for the first version, store the chosen metrics in state, and derive pairs from the currently displayed live/history series. Do not introduce a second timebase or arbitrary formulas.

- [x] **Step 3: Render the XY plot and chooser**

Add a comparison action to the graph screen, two metric pickers, axis labels with units, and a tappable scatter/line plot. Show X/Y values and source time for the selected pair. Use the same evidence and no-fabricated-value rules as time-series cards.

- [x] **Step 4: Add strings, run focused tests, and commit**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.graph.GraphComparisonTest" --no-build-cache --rerun-tasks --console=plain`

Expected: focused tests pass. Commit:

```text
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-ru/strings.xml composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphComparisonTest.kt
git commit -m "feat(graph): add timestamp-paired XY comparisons"
```

### Task 8: Verify, polish, and release the feature

**Files:**
- Modify: `composeApp/build.gradle.kts` (bump `versionCode` from 8 to 9 and `versionName` from `0.3.5` to `0.4.0` only after the feature is complete).
- Modify: graph files/resources only for issues found by verification.
- Test: existing full common test suite and migration verifier.

**Interfaces:**
- Consumes: all previous tasks and the existing release signing configuration.
- Produces: a verified debug suite, migration check, release APK, and checksum.

- [x] **Step 1: Run formatting/static checks**

Run `git diff --check` and inspect for hardcoded insets, fabricated zeroes, unbounded `runTest` loops, and writes to BLE characteristics.

- [x] **Step 2: Run the fresh full suite**

Run:

```text
.\gradlew.bat :composeApp:testDebugUnitTest --no-build-cache --rerun-tasks --no-configuration-cache --console=plain
.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration --no-build-cache --rerun-tasks --console=plain
```

Expected: exact test count is reported with 0 failures/errors/skips, and migration verification succeeds.

- [x] **Step 3: Bump version and build release**

Change only the app version fields after tests pass, then run:

```text
.\gradlew.bat :composeApp:assembleRelease --no-build-cache --rerun-tasks --no-configuration-cache --console=plain
```

Verify the APK at `composeApp/build/outputs/apk/release/composeApp-release.apk` and record its SHA-256. If an emulator is available, capture portrait and landscape graph screenshots; otherwise report visual QA as unverified instead of claiming it.

- [x] **Step 4: Commit the release metadata and final verification**

```text
git add composeApp/build.gradle.kts
git commit -m "release: publish telemetry graph history"
git status --short
```

Leave unrelated untracked workspace files untouched.
