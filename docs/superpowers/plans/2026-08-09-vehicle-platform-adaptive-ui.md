# Adaptive UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ride and battery dashboards responsive to landscape constraints while preserving the approved portrait layout.

**Architecture:** A pure `ResponsiveLayoutMode` function classifies the measured content bounds. Dashboard composables branch only at that boundary: portrait keeps the existing scroll column, wide windows use a full-width header plus two content panes. Insets remain Compose-provided padding, and no screen-size constants are introduced.

**Tech Stack:** Kotlin Multiplatform commonMain/commonTest, Compose Multiplatform, Material 3, kotlin.test, Android emulator screenshots.

## Global Constraints

- Compose UI is not unit-testable in this repository; test layout decisions and component state as pure/common code.
- `minSdk 26`; do not use APIs newer than the project's existing Compose/Android compatibility.
- Do not hardcode status/navigation insets or device dimensions.
- Russian UI strings remain in both `values/` and `values-ru/` when copy changes; this pass should not add copy.

---

### Task 1: Pure responsive layout decision

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/common/ResponsiveLayout.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/common/ResponsiveLayoutTest.kt`

**Interfaces:**
- Produces `ResponsiveLayoutMode` and `responsiveLayoutMode(widthPx: Int, heightPx: Int): ResponsiveLayoutMode` for dashboard composables.

- [ ] **Step 1: Write failing tests** for portrait, square, and landscape bounds; assert that only width strictly greater than height selects `WIDE`.
- [ ] **Step 2: Run the focused test and confirm it fails** because the classifier does not exist.
- [ ] **Step 3: Implement the enum and pure classifier** with no Android or Compose dependency.
- [ ] **Step 4: Run the focused test** and confirm all cases pass.
- [ ] **Step 5: Commit** with `test/ui: add responsive layout classifier`.

### Task 2: Responsive ride dashboard

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/common/ResponsiveLayoutTest.kt` (classifier coverage used by both dashboards)

**Interfaces:**
- Consumes `responsiveLayoutMode` from Task 1.
- Produces the same `RideDashboardComponent` callbacks/state rendering as portrait, with a wide-pane arrangement when constraints are landscape.

- [ ] **Step 1: Extract the existing ride body into small composables** so header/fault banner, hero, metrics, and trailing telemetry can be placed without duplicating state or callbacks.
- [ ] **Step 2: Add `BoxWithConstraints` around the screen content** and select the pure mode from `constraints.maxWidth/maxHeight`; keep `statusBarsPadding`, scrolling, and current portrait order unchanged.
- [ ] **Step 3: Implement the wide arrangement**: full-width vehicle/fault header, then a weighted row with `RideHero` in the first pane and `MetricCluster` plus `ConsumptionCard` in the second; keep Graph, odometer, and sample-rate details below the panes in the same scroll surface.
- [ ] **Step 4: Compile and run the existing ride/common tests**; add no Compose UI tests.
- [ ] **Step 5: Install the debug APK and capture portrait and landscape emulator screenshots**. Confirm the gauge, power/current/battery cards, and bottom navigation are all visible in landscape without inset constants or clipped content.
- [ ] **Step 6: Commit** with `feat(ui): adapt ride dashboard to landscape`.

### Task 3: Responsive battery dashboard and verification

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/dashboard/DashboardScreen.kt`
- Test: existing dashboard tests plus `ResponsiveLayoutTest.kt`

**Interfaces:**
- Consumes the same `ResponsiveLayoutMode` classifier and existing `DashboardComponent.State`.
- Produces identical telemetry values and callbacks in portrait and wide layouts.

- [ ] **Step 1: Extract battery header/hero/metric sections** without changing metric mapping or unknown-value semantics.
- [ ] **Step 2: Apply the wide two-pane layout**: header/fault/MOSFET row spans the width; hero and primary cards share the row; sparkline, cells, and secondary details remain scrollable and visible below.
- [ ] **Step 3: Run the focused dashboard tests and then the fresh full suite** with `--no-build-cache --rerun-tasks`; parse XML and assert exact test/failure/error counts.
- [ ] **Step 4: Capture emulator portrait and landscape screenshots and inspect the resulting layout.
- [ ] **Step 5: Commit** with `feat(ui): adapt battery dashboard to landscape`.

## Final review

Review the combined diff for duplicated state, hardcoded insets/dimensions, and
portrait regressions. Verify `git diff --check` and the full suite before
claiming completion.
