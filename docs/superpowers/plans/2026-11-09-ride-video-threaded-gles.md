# Ride video encoder thread implementation plan

**Goal** Keep GLES camera intake running while the encoder surface blocks, while preserving frame order and telemetry timing.

**Architecture** The capture handler owns the SurfaceTexture, frame clock and delayed FIFO. A second handler owns the encoder EGL context, HUD rendering and swaps. Shared textures cross the boundary in FIFO order with GLES fences in both directions. Spare textures absorb brief stalls within a fixed memory budget. A saturated pool drops the incoming frame and counts the drop.

- [x] Reuse the 1440p60 stress recording and verify the baseline packet count and timestamp gaps.
- [x] Move encoder EGL work to a separate handler and preserve the original capture timestamp and telemetry clock per slot.
- [x] Drain the delayed FIFO and already submitted work before the stop callback. Retire surfaces and textures only after their queued work is resolved.
- [x] Repeat the stress test with the same source and HUD. Check packet order, capture-to-muxer counts, saturation and final timestamp. Run the Android unit suite with the repository Gradle CPU cap.
