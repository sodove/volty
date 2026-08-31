# Nearby + Light social redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved Nearby social hub, live participant markers on the Light dashboard, and consistent group-map entry from the other dashboards without disturbing existing telemetry behavior.

**Architecture:** Keep the existing provider-neutral `SocialRepository`/`SocialLiveSession` and root-hosted native map. Rework only the presentation shell around those contracts: Nearby becomes a tabbed social surface with profile and root navigation, Light remains a no-bottom-nav telemetry HUD with the map layer underneath, and non-Light dashboards expose a single Nearby action. Keep social correctness in component/repository/backend contracts rather than hiding it in Composables.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Decompose, Ktor backend, kotlinx-coroutines, kotlin.test/Turbine, MapLibre Android host.

**Spec:** `docs/superpowers/specs/2026-08-23-nearby-light-social-redesign.md`

## Global Constraints

- Preserve all existing dirty changes in the checkout; never reset, checkout, or overwrite unrelated files.
- Do not change BLE protocol writes; in particular never write to Begode or Veteran/Leaperkim FFE1.
- Ride/Battery/Graph/Settings remain usable without authentication; only Nearby/social actions gate on login.
- Use the existing `ParticipantMarker`/live-session data. Never fabricate coordinates, speed, telemetry, or availability.
- Keep Light without the root bottom tab bar; Nearby itself keeps the root app bottom navigation.
- Do not add a map SDK or WebRTC implementation; use the current provider-neutral/native map seams.
- Sharing is group-scoped, TTL-bound, revocable, and renewal preserves the selected profile.
- Duplicate group joins must converge to one group entry; owner deletion and member leave must clear selected/live state.
- Russian strings go in both `composeApp/src/commonMain/composeResources/values/strings.xml` and `values-ru/strings.xml`.
- Compose UI is device-verified; common state/mapping logic receives unit tests. Run `./gradlew.bat :composeApp:testDebugUnitTest` after each task and `./gradlew.bat :backend:test` for backend tasks.
- Do not create a commit in this run; leave the accumulated changes available for final review and the user's later commit/push decision.

---

### Task 1: Social group correctness and component contract

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/social/SocialContracts.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/DefaultSocialRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/HttpSocialTransport.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/nearby/NearbyComponent.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Application.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Database.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Model.kt`
- Test: existing social repository/backend tests plus focused new tests beside the changed code.

**Interfaces:**
- Produces `SocialRepository.deleteGroup(groupId)`/transport support and `NearbyComponent.onDeleteGroup(group)` (or the repository's established equivalent) with owner-only semantics.
- Preserves the existing `joinGroup`, `renewSharing`, `leaveGroup`, and live-session state contracts for the UI task.

- [ ] **Step 1: Write/extend failing contract tests** for owner deletion, member leave, duplicate join convergence, and renewal retaining the chosen telemetry profile.
- [ ] **Step 2: Run the focused backend and common tests** and confirm the new assertions fail for the current implementation.
- [ ] **Step 3: Implement the smallest contract-preserving changes** in the backend, transport, repository, and component. Deletion must be authorization-checked server-side; joining an already joined group must return the existing membership without a duplicate list entry.
- [ ] **Step 4: On successful delete/leave, clear `selectedGroup`, sharing/live markers, and pending voice state without touching unrelated vehicle state.**
- [ ] **Step 5: Run `./gradlew.bat :backend:test` and `./gradlew.bat :composeApp:testDebugUnitTest`; record the exact results in the agent report.**

### Task 2: Light map marker layer and group ride affordance

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/LightRideDashboard.kt`
- Modify only if required by the existing seam: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapHostPolicy.kt`, `RideMapMarkerPresentation.kt`, or their focused tests.
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapMarkerPresentationTest.kt` and any new pure Light layout/state test.

**Interfaces:**
- Consumes the root-provided `mapLayer`, existing `onOpenNearby`/`onRecenterMap`, and live marker data already hosted above the dashboard.
- Produces a Light-specific compact group status affordance and a marker-friendly transparent HUD layout; it must not add a bottom nav or own a second map host.

- [ ] **Step 1: Inspect the current Light/map host composition and add a focused pure assertion** for the safe overlay slot/visibility rule: no active group means no group status affordance; active markers remain data-driven.
- [ ] **Step 2: Implement the visual hierarchy from the reference**: native map behind the HUD, gauges/graphs unchanged, group status control above the telemetry strip, and existing map recenter controls preserved.
- [ ] **Step 3: Make participant taps/status taps invoke the existing Nearby/group callback or a transient sheet callback without blocking the gauge/graph region.**
- [ ] **Step 4: Verify `DashboardStyle.LIGHT` still hides the root bottom tab bar and that unavailable map providers leave a truthful fallback.**
- [ ] **Step 5: Run `./gradlew.bat :composeApp:testDebugUnitTest`; report that visual/device behavior still needs the Android smoke build.**

### Task 3: Nearby social hub, profile, and app navigation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/nearby/NearbyScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-ru/strings.xml`
- Test: existing `NearbyUiStateTest`/component tests; do not add fake Compose UI tests.

**Interfaces:**
- Consumes the existing `NearbyComponent.State` and callbacks, including the deletion/leave contract produced by Task 1.
- Produces a scrollable Nearby surface with a profile header, `Люди`/`Друзья`/`Группы` sections, active ride card, friend actions, group create/join/manage actions, and existing sharing/voice controls reachable from the selected group.

- [ ] **Step 1: Map the current state fields and callbacks to the three sections** before editing; keep auth as the first screen for logged-out users.
- [ ] **Step 2: Add the profile block** using authenticated display name, profile edit/logout actions, and no credentials or BLE identifiers.
- [ ] **Step 3: Replace the long undifferentiated list with the three-section shell** from the reference: active group/nearby people, friends/invites, and groups/create/join/manage. Keep all existing actions wired to `NearbyComponent`.
- [ ] **Step 4: Add the app bottom navigation through the existing root chrome** by leaving root ownership intact; Nearby must not draw a second private bottom nav.
- [ ] **Step 5: Keep the native map out of the decorative social list background; expose `Карта группы` as the explicit action that opens the group map state.**
- [ ] **Step 6: Add Russian resource strings in both locales and run `./gradlew.bat :composeApp:testDebugUnitTest`.**

### Task 4: Nearby/group-map entry from other dashboards

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt` only for shared callback plumbing if needed.
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/dashboard/DashboardScreen.kt` and/or `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/graph/GraphScreen.kt` for dashboard-specific controls.
- Modify only if needed: focused navigation policy tests or common resource strings.

**Interfaces:**
- Consumes the existing root `onOpenNearby` callback and current root navigation policy.
- Produces one consistent Nearby/Group Ride action per non-Light dashboard: active group opens the full-screen map/group state; otherwise it opens Nearby. No duplicate root tab bar and no Light changes.

- [ ] **Step 1: Inspect each non-Light dashboard's existing header/action area** and identify a safe, non-overlapping placement.
- [ ] **Step 2: Add the same semantic action/content description** in each applicable dashboard, reusing existing callbacks instead of creating a second navigation path.
- [ ] **Step 3: Keep Graph/Battery scroll and telemetry interactions intact; do not force a map layer behind these screens.**
- [ ] **Step 4: Add/adjust pure navigation-policy coverage where a rule changes and run `./gradlew.bat :composeApp:testDebugUnitTest`.**

### Task 5: Integration verification

**Files:**
- Modify only files required to resolve verified integration failures; do not refactor unrelated code.
- Test: full common/backend suites and the Android release/debug build as appropriate.

- [ ] **Step 1: Review all agent reports and inspect `git diff --stat` plus overlapping files.**
- [ ] **Step 2: Run `./gradlew.bat :composeApp:testDebugUnitTest`, `./gradlew.bat :backend:test`, and `./gradlew.bat :composeApp:assembleRelease` if the environment has the configured signing/build inputs.**
- [ ] **Step 3: Run an Android smoke check for Light, Nearby, Friends, Groups, and a non-Light dashboard button; capture only actionable evidence.**
- [ ] **Step 4: Resolve only integration regressions, preserve unrelated dirty files, and report any device/provider limitation honestly.**

