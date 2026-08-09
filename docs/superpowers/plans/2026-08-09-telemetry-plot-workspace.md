# Telemetry Plot Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make graph comparison behave like VESC Tool: one synchronized multi-trace plot and a complete selectable telemetry table.

**Architecture:** Keep `GraphComponent`'s timestamped series and evidence rules. Add pure plot geometry helpers, then replace the Compose card/picker layout with a shared canvas plus a full metric table that works in portrait and landscape.

**Tech Stack:** Kotlin Multiplatform common code, Compose Multiplatform Material 3, Kotlin test, existing Decompose component state.

## Global Constraints

- Unknown metrics produce no point and never render as numeric zero.
- All `GraphMetric` values must remain discoverable in the table; no horizontal-only control may be the sole access path.
- The plot uses one time axis; each trace may use an independent vertical range and must show its unit.
- No BLE writes or persistence schema changes are part of this UI correction.
- Compose visual QA is done on the Android emulator in portrait and landscape.

---

### Task 1: Add pure overlay geometry

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphPlotGeometry.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/graph/GraphPlotGeometryTest.kt`

- [ ] **Step 1: Write failing tests** for a union time axis, per-series non-zero range, and normalization of a value to the plot fraction.
- [ ] **Step 2: Run the focused test and confirm the missing-helper failure.**
- [ ] **Step 3: Implement the smallest pure helpers** and keep unknown/empty series empty.
- [ ] **Step 4: Run the focused test and commit** `feat(graph): add overlay plot geometry`.

### Task 2: Replace the graph screen with a VESC-style workspace

**Files:**
- Replace: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-ru/strings.xml`

- [ ] **Step 1: Render one shared canvas** with colored traces, per-trace ranges, grid, legend, common cursor, and tap-to-time selection.
- [ ] **Step 2: Render a complete grouped metric table** with Plot checkboxes, current/selected values, units, and no hidden columns.
- [ ] **Step 3: Make portrait and landscape layouts adaptive** using measured width and weights; keep all spacing inset-derived.
- [ ] **Step 4: Replace the clipped comparison dialog** with the same plot/table workspace while preserving history actions and callbacks.
- [ ] **Step 5: Add localized strings and run the debug compile**, then commit `feat(graph): redesign comparison workspace`.

### Task 3: Verify and publish

- [ ] **Step 1: Run focused graph tests, full unit tests, and migration verification.**
- [ ] **Step 2: Install the release APK on the emulator and capture portrait/landscape screenshots.**
- [ ] **Step 3: Run `git diff --check`, commit any final fix, and push `feat/vehicle-composer`.**
