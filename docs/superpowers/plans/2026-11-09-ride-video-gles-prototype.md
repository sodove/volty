# Ride video GLES prototype

> Use superpowers:subagent-driven-development for the isolated drawing tasks.

**Goal** Compare a GLES HUD with the Canvas FIFO implementation at `87e9814d` on the same Bumble input.

**Architecture** Keep CameraX Recorder and the existing overlay scene and telemetry timeline. An opt-in CameraX SurfaceProcessor queues camera textures on its GL thread, draws the existing scene over each oldest texture and gives EGL the original capture timestamp. Keep the default Canvas path available for the A/B.

- [x] Build the Canvas control and record the Sherman L capture through Bumble and Android Netsim.
- [x] Render scene shapes and small cached glyph textures into the encoder surface. Keep the camera preview independent.
- [x] Preserve FIFO depth, chronological stop drain, timestamp conversion and the existing interpolation and hold behavior.
- [x] Capture the GLES variant under the same emulator settings. Inspect video orientation, HUD content, packet timestamps and gaps.
- [x] Run the Android unit suite before committing.

Android minSdk 26, four Gradle workers and four active processors. The replay uses the virtual Sherman L only. Never write to FFE1 on Begode.

Build the prototype with `-PvoltyGlesRideHud=true`. On the x86 emulator at 720p/30, Canvas recorded 29.87 FPS with three packet gaps above 50 ms. The final GLES run recorded 29.91 FPS with two such gaps. The nine-frame FIFO tail drained and 135 sampled capture timestamps agreed with relative MP4 PTS to within 3 µs. A separate smoke test recorded a 1280×720 clip with upright HUD after a 90° device rotation. Glyph layout and shader edge coverage are approximate in the prototype.
