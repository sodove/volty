# Smooth Map Follow Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Volty's MapLibre ride map smooth between GPS fixes, correct delayed/noisy fixes without rubber-banding, preserve manual pan/zoom briefly while moving, and return to follow mode automatically.

**Architecture:** Keep raw Android locations as the source for the trail and telemetry, but add a common display-only motion estimator that predicts at frame cadence and applies bounded residual correction. Add a common follow-mode state machine for gesture ownership and auto-return, then wire it into the cached Android MapLibre view and the existing “Моё положение” dashboard button.

**Tech Stack:** Kotlin Multiplatform common code, Android `LocationManager`, MapLibre Android, Jetpack Compose, `kotlin.test`, Gradle debug unit tests.

**Spec:** `docs/superpowers/specs/2026-08-22-smooth-map-follow-mode-design.md`

## Global Constraints

- Android target only; `minSdk 26`.
- Raw Android locations remain the source for trail and telemetry; prediction is display-only.
- The frame loop remains the visual refresh mechanism; never queue a per-fix `easeCamera` animation.
- GPS measurement time, not callback arrival time, is the origin of prediction.
- The live blur/vignette overlay is not changed by this feature.
- Compose UI tests are not added because this repository has no instrumented Compose test setup.
- Preserve all existing unrelated uncommitted work in `C:\Users\sodovaya\Desktop\volty`.

---

### Task 1: Build the pure GPS motion estimator

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimator.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapLocationPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimatorTest.kt`

**Interfaces:**
- Consumes: `RideMapPredictedCoordinate` and existing map prediction constants from `RideMapLocationPolicy.kt`.
- Produces: `RideMapMotionFix`, `RideMapMotionEstimate`, `RideMapMotionEstimatorPolicy`, `defaultRideMapMotionEstimatorPolicy`, and `RideMapMotionEstimator.accept/estimate` for the Android map layer.

- [ ] **Step 1: Write the failing estimator tests**

Add tests with these exact behaviors:

```kotlin
@Test
fun prediction_uses_fix_measurement_time_and_speed() { /* 10 m/s east for 1 s moves east */ }

@Test
fun prediction_is_capped_after_gps_age_limit() { /* 10 s age equals maxPredictionAgeMillis */ }

@Test
fun late_fix_is_corrected_without_teleporting_the_display() { /* display is between old prediction and new fix at correction start */ }

@Test
fun out_of_order_fix_is_rejected() { /* older timestamp returns false and does not change estimate */ }

@Test
fun missing_speed_and_bearing_are_derived_from_consecutive_fixes() { /* distance/time and bearing become usable */ }

@Test
fun bearing_filter_takes_the_shortest_wraparound_path() { /* 359° to 1° does not rotate through 180° */ }

@Test
fun stationary_fix_does_not_replace_the_last_trustworthy_bearing() { /* invalid/stationary course leaves heading stable */ }
```

Use fixed timestamps and coordinate tolerances; do not assert on wall-clock sleeps.

- [ ] **Step 2: Run the focused test and verify the expected red failure**

Run:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.map.RideMapMotionEstimatorTest"
```

Expected: compilation/test failure because the estimator types and behavior do not yet exist.

- [ ] **Step 3: Implement the minimal estimator**

Implement `RideMapMotionEstimator` as a small mutable state holder:

```kotlin
internal data class RideMapMotionFix(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
)

internal data class RideMapMotionEstimate(
    val coordinate: RideMapPredictedCoordinate,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
)

internal class RideMapMotionEstimator(
    private val policy: RideMapMotionEstimatorPolicy = defaultRideMapMotionEstimatorPolicy,
) {
    fun accept(fix: RideMapMotionFix): Boolean
    fun estimate(nowMillis: Long, speedMetersPerSecondOverride: Float? = null): RideMapMotionEstimate?
}
```

Use local-meter east/north offsets for residual correction, a 2 m dead zone, a 350 ms ordinary correction window, and a 30 m hard re-anchor threshold. Clamp prediction age to 1,500 ms. Derive missing speed/course from the previous accepted fix only when its timestamp delta is positive and its distance is meaningful. Smooth heading with shortest-path circular interpolation and keep it unchanged below the moving threshold.

- [ ] **Step 4: Run the focused test and verify green**

Run the same focused Gradle test. Expected: all estimator tests pass with no new warnings.

- [ ] **Step 5: Commit only the estimator files**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimator.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapLocationPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimatorTest.kt
git commit -m "feat: add smooth ride map motion estimator"
```

### Task 2: Add the follow/free camera state machine

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapFollowPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapFollowPolicyTest.kt`

**Interfaces:**
- Consumes: MapLibre camera-move origin translated by the Android adapter and current earned vehicle speed.
- Produces: `RideMapFollowMode`, `RideMapFollowState`, `RideMapCameraMoveOrigin`, `onRideMapCameraMoveStarted`, `shouldAutoReturnToFollow`, and `recenterRideMap`.

- [ ] **Step 1: Write the failing follow-policy tests**

Cover:

```kotlin
@Test
fun gesture_enters_free_mode_and_records_time() { /* mode FREE, timestamp saved */ }

@Test
fun programmatic_move_keeps_follow_mode() { /* API/developer move does not disable follow */ }

@Test
fun manual_recenter_returns_to_following() { /* mode FOLLOWING, timer cleared */ }

@Test
fun moving_map_auto_returns_after_two_seconds() { /* false at 1,999 ms, true at 2,000 ms */ }

@Test
fun stopped_or_unknown_speed_does_not_auto_return() { /* false for 0 and null */ }

@Test
fun a_new_gesture_resets_the_auto_return_deadline() { /* latest gesture owns the grace period */ }
```

- [ ] **Step 2: Run the focused test and verify red**

Run:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.map.RideMapFollowPolicyTest"
```

Expected: failure because the follow policy does not exist.

- [ ] **Step 3: Implement the minimal policy**

Use these interfaces and constants:

```kotlin
internal enum class RideMapFollowMode { FOLLOWING, FREE }
internal enum class RideMapCameraMoveOrigin { GESTURE, PROGRAMMATIC }
internal data class RideMapFollowState(
    val mode: RideMapFollowMode = RideMapFollowMode.FOLLOWING,
    val lastGestureAtMillis: Long? = null,
)
internal const val RIDE_MAP_AUTO_RETURN_DELAY_MILLIS = 2_000L
internal const val RIDE_MAP_MOVING_SPEED_THRESHOLD_KMH = 2f
```

`onRideMapCameraMoveStarted` changes state only for `GESTURE`; `shouldAutoReturnToFollow` requires `FREE`, a finite speed at or above the threshold, a recorded gesture, and elapsed time at least the delay; `recenterRideMap` returns the default following state.

- [ ] **Step 4: Run the focused test and verify green**

Run the same focused Gradle test. Expected: all follow-policy tests pass.

- [ ] **Step 5: Commit only the follow policy files**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapFollowPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapFollowPolicyTest.kt
git commit -m "feat: add ride map follow policy"
```

### Task 3: Feed the estimator from the ride and Android location layers

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootScreen.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt`

**Interfaces:**
- Consumes: `MotionReadings.speedKmh(activeRideState.motion)` when the active ride has earned speed.
- Produces: an Android map layer that accepts `vehicleSpeedKmh: Float?` and uses `RideMapMotionEstimator` for frame-time camera coordinates.

- [ ] **Step 1: Add the failing integration contract test**

Add this concrete override test to `RideMapMotionEstimatorTest`:

```kotlin
@Test
fun a_valid_vehicle_speed_override_wins_over_stale_location_speed() {
    val estimator = RideMapMotionEstimator()
    estimator.accept(
        RideMapMotionFix(
            latitude = 56.8,
            longitude = 60.6,
            timestampMillis = 0L,
            accuracyMeters = 3f,
            speedMetersPerSecond = 1f,
            bearingDegrees = 90f,
        ),
    )
    val locationSpeedEstimate = checkNotNull(estimator.estimate(1_000L))
    val vehicleSpeedEstimate = checkNotNull(
        estimator.estimate(1_000L, speedMetersPerSecondOverride = 10f),
    )
    assertTrue(vehicleSpeedEstimate.coordinate.longitude > locationSpeedEstimate.coordinate.longitude)
}
```

- [ ] **Step 2: Run the test and verify red**

Run the estimator focused test. Expected: the override API is missing or the assertion fails.

- [ ] **Step 3: Implement the integration**

Add `vehicleSpeedKmh: Float? = null` to the common expect and Android actual `PlatformRideMapLayer` functions. In `RootScreen`, pass the earned active-ride speed through `MotionReadings.speedKmh(activeRideState.motion)`; pass `null` on other screens so Android location speed remains the fallback.

In `PlatformMapLayer.android.kt`:

- Keep the current raw `Location` acceptance, trail append, GPS-over-network priority, monotonic age calculation, and `enrichMapLocation` behavior.
- Create one remembered `RideMapMotionEstimator` for the cached ride map.
- On each accepted location, feed a `RideMapMotionFix` using `location.elapsedRealtimeNanos / 1_000_000L` when available, otherwise `location.time`, with the enriched speed/bearing.
- In the existing `withFrameNanos` loop, call `estimate(SystemClock.elapsedRealtime(), vehicleSpeedKmh?.div(3.6f))` and use its coordinate/course instead of recomputing directly from the raw `Location`.
- Keep `moveCamera` from the frame loop; do not restore `easeCamera` or per-fix animation.

- [ ] **Step 4: Run the estimator and compile the map integration**

Run:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.map.RideMapMotionEstimatorTest"
.\gradlew.bat :composeApp:compileDebugKotlinAndroid
```

Expected: the override test and Android compilation pass.

- [ ] **Step 5: Commit the estimator wiring**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootScreen.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimator.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapMotionEstimatorTest.kt
git commit -m "feat: drive map prediction from ride speed"
```

### Task 4: Wire gesture ownership, auto-return, and recenter action

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/LightRideDashboard.kt`

**Interfaces:**
- Consumes: `RideMapFollowPolicy` and `vehicleSpeedKmh` from Task 3.
- Produces: gesture-safe camera behavior, two-second moving auto-return, and a working “Моё положение” button.

- [ ] **Step 1: Re-run the common policy tests before Android wiring**

Because Compose UI is not instrumented in this project, the executable contract is the common `RideMapFollowPolicyTest` suite from Task 2. It already asserts the exact `recenterRideMap` result and the two-second auto-return boundary.

- [ ] **Step 2: Run the follow-policy tests and verify they remain green**

Run:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.map.RideMapFollowPolicyTest"
```

Expected: all policy tests pass before the Android callback wiring begins.

- [ ] **Step 3: Implement the camera state wiring**

In the Android `MapLibreMap` setup, register a camera-move-start listener and translate only `REASON_GESTURE` to `RideMapCameraMoveOrigin.GESTURE`. Store `RideMapFollowState` in the map composable. The frame loop must:

```kotlin
if (followState.mode == RideMapFollowMode.FREE &&
    shouldAutoReturnToFollow(followState, effectiveSpeedKmh, nowMillis)
) {
    followState = recenterRideMap(followState)
}
if (followState.mode == RideMapFollowMode.FOLLOWING) {
    readyMap.moveCamera(CameraUpdateFactory.newCameraPosition(nextPosition))
}
```

Do not change the camera in `FREE`, preserving user pan, pinch zoom, and rotation. A new gesture updates the timestamp and restarts the two-second grace period. Register/remove the listener with the cached map lifecycle so recomposition cannot stack listeners.

For the explicit recenter action, create a `mapRecenterRequest` counter in `RootScreen`, pass it to `PlatformRideMapLayer`, and increment it from a new `onRecenterMap` callback passed through `RideDashboardScreen` to `LightRideDashboard`. The existing `LocationSearching` button must call that callback. A changed request counter resets the Android follow state and immediately lets the frame loop apply the current predicted position.

- [ ] **Step 4: Run compilation and the full unit suite**

Run:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest
.\gradlew.bat :composeApp:compileDebugKotlinAndroid
```

Expected: full unit suite passes and Android Kotlin compilation succeeds.

- [ ] **Step 5: Commit the follow-mode integration**

```powershell
git add composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootScreen.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/LightRideDashboard.kt
git commit -m "feat: preserve manual map gestures"
```

### Task 5: Verify the complete Volty build without touching blur

**Files:**
- Test only; no production file changes expected.

**Interfaces:**
- Consumes: completed Tasks 1–4.
- Produces: verified debug tests, release APK compilation, and a clean scoped review of map changes.

- [ ] **Step 1: Run the complete required checks**

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest
.\gradlew.bat :composeApp:assembleRelease
.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration
```

- [ ] **Step 2: Inspect the final scoped diff**

```powershell
git diff HEAD~4 --stat
git diff HEAD~4 --check
git status --short
```

Confirm that the feature commits contain only map estimator/follow wiring, dashboard callback plumbing, tests, and the already-approved design/plan documents. Do not stage or revert unrelated existing project changes.

- [ ] **Step 3: Commit verification notes only if a file is required**

Do not create a new file for a passing verification. Report the exact Gradle results and any device-only limitation instead.
