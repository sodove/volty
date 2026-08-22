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

## Data and rendering rules

The following existing behavior remains load-bearing:

- Raw Android locations remain the source for the trail and telemetry.
- Display position is predicted only for a bounded age using speed and bearing.
- The frame loop remains the visual refresh mechanism; no per-fix `easeCamera` queue is
  introduced.
- Invalid or stationary bearings do not rotate the camera.
- Automatic speed-based zoom runs only in `FOLLOWING`; manual zoom is preserved in
  `FREE`.
- The live blur/vignette overlay is not changed by this feature.

## Testing

Pure common policy tests will cover the follow-mode transition contract:

1. A gesture-originated camera movement disables following.
2. A programmatic frame update does not disable following.
3. Re-centering enables following again.
4. Automatic zoom is only selected while following; free mode preserves the current
   camera values.

The Android MapLibre callback wiring will be verified by the Android debug build. The
existing common test suite and release/build verification remain required. Compose UI
tests are not added because this repository has no instrumented Compose test setup.

## Non-goals

This change does not replace the current location provider, add IMU/gyro fusion, change
the trail format, alter the map style, or redesign the working blur/vignette effect.
