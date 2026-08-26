# Nearby Live Map Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore reliable Nearby roster/marker visibility and make live group state resilient to GPS gaps, WebSocket reconnects, lifecycle changes, and stale data.

**Architecture:** Keep the backend authoritative for group membership and sharing expiry, and make live snapshots roster-complete (`location` may be null). On Android/common code, maintain one cached live projection with a freshness ticker; list, sheet, and MapLibre markers are projections of that state. Keep runtime ownership at application scope, refresh rejected WebSocket credentials once, and expose diagnostics at each data boundary.

**Tech Stack:** Kotlin Multiplatform, Compose, Ktor, SQLDelight/JDBC backend, Ktor WebSockets, MapLibre, Koin, common unit tests, Android emulator smoke tests.

**Spec:** `docs/superpowers/specs/2026-08-27-nearby-live-map-stability-design.md`

## Global Constraints

- Preserve all existing user changes in the dirty worktree; do not reset, checkout, or bulk-rewrite unrelated files.
- Keep public API compatibility unless a task explicitly updates both backend contract and client decoder.
- A member without a location is visible in roster/sheet but does not produce a map marker.
- Use the existing 15-second client location freshness and document any server grace period; never mix unexplained 15s/30s status rules.
- Write a failing regression test before production code for each behavior change.
- Do not log bearer tokens, voice credentials, exact private coordinates, or raw personal data.
- Preserve both `values/strings.xml` and `values-ru/strings.xml` when user-facing text changes.

---

### Task 1: Reproduce and instrument the missing-participant path

**Files:**
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Database.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Application.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/HttpSocialTransport.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/DefaultSocialRepository.kt`
- Test: `backend/src/test/kotlin/ru/sodovaya/volty/backend/SocialInvariantsTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/social/HttpSocialTransportTest.kt`

**Interfaces:**
- Consumes: existing `ParticipantSnapshot`, `GroupDto`, live event JSON, and store snapshot query.
- Produces: a deterministic contract test/diagnostic path proving whether a member disappears in store, HTTP serialization, WebSocket decoding, or runtime projection.

- [ ] **Step 1: Write the failing contract test** for a group with two members where only one has a `live_updates` row; assert the live snapshot contains both members and the second has `location == null`.
- [ ] **Step 2: Run the smallest backend test command available** and record the expected failure showing the no-row member is omitted. If the standalone backend cannot run in this environment, keep the test and document the exact unavailable tool.
- [ ] **Step 3: Add boundary diagnostics** with counts only: group id hash/opaque id, roster count, live-row count, snapshot participant count, decoded marker count, and reconnect reason. Ensure auth tokens and coordinates are excluded.
- [ ] **Step 4: Run the focused transport/repository tests** and confirm diagnostics do not change decoded event behavior.
- [ ] **Step 5: Commit only this task's files** with `git add` paths explicitly listed and message `test: trace nearby live participant visibility`.

### Task 2: Make backend snapshots and membership events authoritative

**Files:**
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Database.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Application.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Model.kt` only if serialization needs an explicit nullable location field
- Test: `backend/src/test/kotlin/ru/sodovaya/volty/backend/SharingEndpointContractTest.kt`
- Test: `backend/src/test/kotlin/ru/sodovaya/volty/backend/SocialInvariantsTest.kt`

**Interfaces:**
- Consumes: Task 1 snapshot reproduction and existing `liveHub` APIs.
- Produces: roster-complete snapshot, explicit observer updates for leave/renew/delete, and terminal handling for subscriptions that are no longer group members.

- [ ] **Step 1: Add failing tests** for no-live-row roster inclusion, leave notification to another observer, renew replacement notification, and deleted-group subscription termination.
- [ ] **Step 2: Run the focused backend tests** and verify each fails for the corresponding missing event/row.
- [ ] **Step 3: Change the snapshot query** to start from `group_members`, left join the user/sharing/live-update data, and compute presence/location independently. Preserve sharing permission and expiry semantics.
- [ ] **Step 4: Emit an explicit snapshot/revoke event** after leave, renew, and delete. Make WebSocket setup register before the initial snapshot is sent or otherwise close the query/registration race.
- [ ] **Step 5: Return a terminal non-member signal** that the client can distinguish from transient network failure; prevent an endless retry loop for a deleted/left group.
- [ ] **Step 6: Run the focused backend contract suite and inspect the serialized JSON** for nullable location/presence compatibility.
- [ ] **Step 7: Commit the backend task** with `feat: make nearby live snapshots roster complete`.

### Task 3: Unify client live projection and freshness behavior

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/nearby/SocialLiveSession.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/DefaultSocialRideRuntime.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/nearby/NearbyScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/LightGroupSheetState.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/nearby/ParticipantMarkerMapper.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/nearby/SocialLiveSessionTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/nearby/NearbyUiStateTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/nearby/ParticipantMarkerMapperTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/LightGroupSheetStateTest.kt`

**Interfaces:**
- Consumes: roster-complete snapshots and terminal membership events from Task 2.
- Produces: one cached projection used by Nearby list, Light bottom sheet, and map; deterministic stale transitions without blanking the roster.

- [ ] **Step 1: Write failing tests** asserting that a network failure preserves roster, a null-location member appears as “no current point”, and a cached point becomes stale from elapsed time without a new WebSocket event.
- [ ] **Step 2: Run the focused common tests** and verify the old behavior fails.
- [ ] **Step 3: Add a clock-driven ticker or injectable `now` source** to recompute freshness; align presence and marker stale thresholds through one policy function.
- [ ] **Step 4: Make list, sheet, and marker mapping consume the same cached projection**, retaining the last snapshot on transient failures and clearing only on terminal non-membership.
- [ ] **Step 5: Add selected-group cleanup** after leave/delete and ensure stale selection cannot keep a bottom sheet/banner alive.
- [ ] **Step 6: Run all nearby/runtime focused tests and inspect marker counts for roster-vs-location semantics.**
- [ ] **Step 7: Commit the client projection task** with `fix: keep nearby roster through live refresh gaps`.

### Task 4: Harden GPS publication, auth refresh, and lifecycle recovery

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/social/AndroidLocationProvider.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/HttpSocialTransport.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/DefaultSocialRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/SocialRuntimeStore.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/social/DefaultSocialRepositoryTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/social/HttpSocialTransportTest.kt`
- Test: add focused runtime lifecycle test beside `DefaultSocialRideRuntimeTest.kt`

**Interfaces:**
- Consumes: Task 3 runtime projection and Task 2 terminal WebSocket signal.
- Produces: reliable first location, provider fallback, one forced token refresh on auth rejection, and a runtime that survives Activity recreation without leaking subscriptions.

- [ ] **Step 1: Write failing tests** for provider initial replay, forced token refresh after handshake rejection, root teardown not closing app-scoped runtime, and pending group-selection cancellation.
- [ ] **Step 2: Run the focused tests** and confirm each failure is behavioral rather than a test setup error.
- [ ] **Step 3: Use replay/state semantics for the initial location** and accept a usable network/passive provider when GPS is enabled but unfixed; keep platform code isolated behind the existing provider interface.
- [ ] **Step 4: Add a single forced refresh path for WebSocket 401/403/invalid-token close**, then resume normal backoff; terminal membership errors must not refresh/retry.
- [ ] **Step 5: Separate UI detachment from app-runtime shutdown**, cancel pending selection orchestration explicitly, and rehydrate only valid sharing after process restoration.
- [ ] **Step 6: Run targeted common tests and Android compilation; record any device-only behavior for emulator smoke.**
- [ ] **Step 7: Commit the recovery task** with `fix: recover nearby sharing across reconnects and lifecycle`.

### Task 5: Preserve map presentation quality while restoring markers

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimator.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapCameraSmoother.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapMarkerPresentation.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapTrailPolicy.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimatorTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapMarkerPresentationTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapTrailPolicyTest.kt`

**Interfaces:**
- Consumes: Task 3 marker projection, including stale state and nullable locations.
- Produces: visible stale-vs-fresh marker styling, stable trail alpha, and no regression to smooth position/camera interpolation or speed-dependent zoom.

- [ ] **Step 1: Add failing presentation tests** for stale marker styling, no marker for null location, stable trail alpha while stationary, and the current interpolation duration/zoom bounds.
- [ ] **Step 2: Run the map-focused tests** and verify the regression cases fail or expose the old behavior.
- [ ] **Step 3: Update presentation only at the marker/trail boundary**; do not couple incoming WebSocket frequency to camera animation frames.
- [ ] **Step 4: Verify the Android GeoJSON layer receives all non-null member points and does not drop them on a transient failure.
- [ ] **Step 5: Run map-focused tests and compile the Android target; do not claim device behavior from unit tests.
- [ ] **Step 6: Commit the map task** with `fix: keep nearby markers and map motion stable`.

### Task 6: Integrate, review, emulator-smoke, and release gate

**Files:**
- Modify only files required by review findings.
- Test: existing common/backend tests plus an emulator smoke checklist stored in `docs/superpowers/plans/2026-08-27-nearby-live-map-stability-smoke.md`.

**Interfaces:**
- Consumes: Tasks 1–5 commits and their test evidence.
- Produces: verified branch state with an explicit report for any backend-tooling or device limitation; release build only after all gates pass.

- [ ] **Step 1: Review each agent diff against this plan** and check that no unrelated dirty changes were overwritten.
- [ ] **Step 2: Run `git diff --check` and the targeted nearby/map test set.
- [ ] **Step 3: Run `./gradlew.bat :composeApp:testDebugUnitTest` from `C:\Users\sodovaya\Desktop\volty`.
- [ ] **Step 4: Run `./gradlew.bat :composeApp:assembleRelease` only after tests pass.
- [ ] **Step 5: On the emulator, test two accounts: join same group, start sharing, wait for both points, kill/recreate Activity, toggle network, leave/renew sharing, and confirm roster/map convergence.
- [ ] **Step 6: Run a final code review over the aggregate diff and resolve Critical/Important findings before reporting completion.
- [ ] **Step 7: Report exact commits, test commands/results, emulator observations, and remaining limitations; do not claim backend verification if its standalone toolchain was unavailable.
