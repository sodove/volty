# Smooth Map Follow Mode

## Goal

Keep the ride map visually smooth between infrequent Android location fixes while
allowing the rider to pan and zoom the map without the camera immediately snapping
back to the rider.

## Existing behavior

The Android MapLibre layer already requests frequent location updates, predicts a
short-lived position from the latest location speed and bearing, and updates the
camera from a frame-clock loop. The same loop currently writes the predicted target,
zoom, tilt, and bearing on every frame. That makes the animation smooth, but it also
overrides every user gesture. The live map blur/vignette pipeline is independent and
must remain unchanged.

The map must not be limited by the location callback cadence. Android location
callbacks are measurement input, not render events. The display estimator is sampled
from the frame clock, while GPS and vehicle telemetry only update its state.

The map must not be limited by the location callback cadence. Android location
callbacks are measurement input, not render events. The display estimator is sampled
from the frame clock, while GPS and vehicle telemetry only update its state.

## Design

Add a camera follow state owned by the Android MapLibre view:

- `FOLLOWING`: every rendered frame applies the existing predicted target, speed-based
  zoom, tilt, and filtered bearing.
- `FREE`: location callbacks, prediction, trail updates, and blur continue, but the
  frame loop does not write target, zoom, tilt, or bearing. MapLibre owns the camera
  after the user's gesture.

MapLibre's camera-move-start callback switches to `FREE` only for gesture-originated
  movement. Programmatic camera writes from the frame loop must be marked as internal
  so they do not disable following. A user pan, pinch, or rotate therefore exits
  follow mode without fighting the gesture.

Returning to `FOLLOWING` is an explicit recenter action. The existing ride dashboard
map action will call a small callback exposed by the map layer; if that control is not
currently wired, the Android map layer will provide a clearly scoped recenter affordance
without changing unrelated dashboard behavior. Re-entering follow mode immediately
uses the current predicted location and current camera heading, so it does not queue an
old MapLibre animation.

While the vehicle is moving, a gesture temporarily wins over follow mode. After the
last user camera gesture, wait about two seconds before returning to `FOLLOWING`; a new
gesture resets that delay. Auto-return is disabled while the vehicle is stopped or its
speed is unknown, so the rider can inspect the map while stationary without losing the
chosen view. A manual recenter still returns to `FOLLOWING` immediately.

## GPS and motion estimator

Use a small display-only estimator between the Android location provider and
MapLibre. It has one responsibility: estimate the rider's current display position
smoothly without changing the raw location data used by the trail or telemetry.

Each accepted location fix is an anchor containing latitude, longitude, measurement
time, accuracy, speed, and course. The measurement time must prefer Android's
monotonic `elapsedRealtimeNanos` age over wall-clock time, so a delayed GPS callback is
projected from when the fix was actually measured rather than from when it arrived in
the UI.

The estimator follows this sequence:

1. **Accept and prioritise fixes.** Request a short Android interval, accept every
   valid fix without an artificial distance throttle, reject out-of-order fixes, and
   prefer the GPS provider after the first GPS fix so a less accurate network callback
   cannot pull the map backwards.
2. **Complete the motion input.** Prefer the current earned vehicle speed from the
   ride telemetry when it is available; otherwise use Android `Location.speed`. If
   speed or course is missing, derive it from the previous accepted fix and its
   monotonic time. Invalid, stale, or non-finite values are ignored.
3. **Predict at render time.** At every frame, advance the latest anchor by
   `speed * age` along the current course. Prediction is capped at a short maximum age
   (currently about 1.5 seconds), preventing the map from flying away after GPS loss.
4. **Correct residual error.** When a new fix arrives, compare it with the estimator's
   predicted position at that fix's measurement time. Do not replace the displayed
   position immediately. Ignore tiny residuals, blend ordinary residuals over roughly
   200–500 ms, and re-anchor quickly for a clearly invalid or very large jump. This
   prevents the `prediction -> GPS -> prediction` rubber-band effect while still
   recovering from a real GPS correction.
5. **Stabilise course separately.** Use circular angle interpolation, update heading
   only while moving, and retain the last trustworthy heading when stopped. Position
   smoothing must not be used as a substitute for heading filtering: noisy courses
   such as `120°, 123°, 118°, 126°` must not rotate the camera back and forth.

The common map API receives an optional `vehicleSpeedKmh` from the active ride state.
When it is absent, the Android layer falls back to the speed earned by the location
provider. The map's camera estimator never invents a speed from an unconnected
dashboard value.

The estimator is display-only. The raw accepted fix remains available for the trail,
accuracy reporting, and future diagnostics. The frame loop applies the estimator's
predicted coordinate and filtered course at visual cadence; it never queues a new
MapLibre animation for every GPS callback.

## GPS and motion estimator

Use a small display-only estimator between the Android location provider and
MapLibre. It has one responsibility: estimate the rider's current display position
smoothly without changing the raw location data used by the trail or telemetry.

Each accepted location fix is treated as an anchor containing latitude, longitude,
measurement time, accuracy, speed, and course. The measurement time must prefer
Android's monotonic `elapsedRealtimeNanos` age over wall-clock time, so a delayed GPS
callback is projected from when the fix was actually measured rather than from when it
arrived in the UI.

The estimator follows this sequence:

1. **Accept and prioritise fixes.** Request a short Android interval, accept every
   valid fix without an artificial distance throttle, reject out-of-order fixes, and
   prefer the GPS provider after the first GPS fix so a less accurate network callback
   cannot pull the map backwards.
2. **Complete the motion input.** Prefer a valid vehicle speed when the ride telemetry
   exposes one; otherwise use Android `Location.speed`. If speed or course is missing,
   derive it from the previous accepted fix and its monotonic time. Invalid, stale, or
   non-finite values are ignored.
3. **Predict at render time.** At every frame, advance the latest anchor by
   `speed * age` along the current course. Prediction is capped at a short maximum age
   (currently about 1.5 seconds), preventing the map from flying away after GPS loss.
4. **Correct residual error.** When a new fix arrives, compare it with the estimator's
   predicted position at that fix's measurement time. Do not replace the displayed
   position immediately. Ignore tiny residuals, blend ordinary residuals over roughly
   200–500 ms, and re-anchor quickly for a clearly invalid or very large jump. This
   prevents the `prediction -> GPS -> prediction` rubber-band effect while still
   recovering from a real GPS correction.
5. **Stabilise course separately.** Use circular angle interpolation, update heading
   only while moving, and retain the last trustworthy heading when stopped. Position
   smoothing must not be used as a substitute for heading filtering: noisy courses
   such as `120°, 123°, 118°, 126°` must not rotate the camera back and forth.

The estimator is display-only. The raw accepted fix remains available for the trail,
accuracy reporting, and future diagnostics. The frame loop applies the estimator's
predicted coordinate and filtered course at visual cadence; it never queues a new
MapLibre animation for every GPS callback.

## Data and rendering rules

The following existing behavior remains load-bearing:

- Raw Android locations remain the source for the trail and telemetry.
- Display position is predicted only for a bounded age using speed and bearing.
- GPS measurement time, not callback arrival time, is the origin of prediction.
- A new fix corrects residual error over time instead of teleporting the display
  position.
- GPS/network provider selection, missing speed/course recovery, and course filtering
  happen before the camera consumes motion state.
- GPS measurement time, not callback arrival time, is the origin of prediction.
- A new fix corrects residual error over time instead of teleporting the display
  position.
- GPS/network provider selection, missing speed/course recovery, and course filtering
  happen before the camera consumes motion state.
- The frame loop remains the visual refresh mechanism; no per-fix `easeCamera` queue is
  introduced.
- Invalid or stationary bearings do not rotate the camera.
- Automatic speed-based zoom runs only in `FOLLOWING`; manual zoom is preserved in
  `FREE`.
- In `FREE`, the last user camera values are preserved for the grace period; automatic
  follow resumes only after continuous movement for the grace period.
- The live blur/vignette overlay is not changed by this feature.

## Testing

Pure common policy tests will cover the follow-mode transition contract:

1. A gesture-originated camera movement disables following.
2. A programmatic frame update does not disable following.
3. Re-centering enables following again.
4. Automatic zoom is only selected while following; free mode preserves the current
   camera values.
5. A moving vehicle schedules auto-return after the grace period, while a stopped or
   unknown-speed vehicle does not.
6. Prediction advances by speed and course from the anchor's measurement time.
7. Prediction stops advancing past the maximum GPS age.
8. A new fix produces a bounded correction instead of an immediate display jump.
9. Out-of-order and lower-priority network fixes cannot pull the estimator backwards.
10. Missing speed/course can be recovered from consecutive valid fixes, while invalid
    values leave the previous trustworthy state unchanged.
11. Course interpolation takes the shortest circular path and does not rotate while
    stationary.
6. Prediction advances by speed and course from the anchor's measurement time.
7. Prediction stops advancing past the maximum GPS age.
8. A new fix produces a bounded correction instead of an immediate display jump.
9. Out-of-order and lower-priority network fixes cannot pull the estimator backwards.
10. Missing speed/course can be recovered from consecutive valid fixes, while invalid
    values leave the previous trustworthy state unchanged.
11. Course interpolation takes the shortest circular path and does not rotate while
    stationary.

The Android MapLibre callback wiring will be verified by the Android debug build. The
existing common test suite and release/build verification remain required. Compose UI
tests are not added because this repository has no instrumented Compose test setup.

## Non-goals

This change does not replace the current location provider, add IMU/gyro fusion, change
the trail format, alter the map style, or redesign the working blur/vignette effect.
