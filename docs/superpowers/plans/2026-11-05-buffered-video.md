# Buffered ride video implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for narrow tasks. Primary owns camera/timebase/lifecycle integration.

**Goal:** Smooth real telemetry between received samples and keep it aligned with captured video, with at most 300 ms of lookahead. Give the rider a choice of supported stabilization modes for physical comparison.

**Architecture:** Preserve video timestamps while delaying delivery through CameraX OverlayEffect's GPU queue. Camera2 capture timestamps drive ordered release; draw on the original frame time using only data already available at rendering. Preview uses a bounded adaptive telemetry delay. Keep original receipt timestamps for availability and per-metric source timestamps for interpolation, so unrelated aggregate updates cannot shorten a metric's interpolation interval. Unknowns, expiry, vehicle boundaries and segment ownership remain authoritative. Flush queued frames before an explicit recorder stop; never block the UI or wait indefinitely for telemetry. Frame queue memory is bounded, including at UHD. Standard video stabilization becomes the preferred supported mode; preview stabilization and off remain selectable. This is a field-comparison change, not proof that Pixel's jumps are fixed.

**Tech Stack:** Kotlin common pure policies + CameraX 1.5.3, Camera2 metadata, existing renderer and lifecycle gate.

- [x] Add pure supported stabilization mode policy/tests and EN/RU strings; integrate selectable modes without forcing OIS/EIS together.
- [x] Add receipt/measurement-time-aware interpolation and adaptive preview clock; regress sparse 200–250 ms samples, repeated folds, unknowns, expiry, ties, vehicle changes and availability cutoffs.
- [x] Add bounded ordered frame release policy/tests, GPU queue integration, timestamp observation before buffering, stop/segment flush and diagnostics. Keep audio/video timestamps unshifted.
- [x] Run focused tests, full suite with exact count, debug build and emulator-only recording/stop/wake checks where available. Check final file frame cadence and A/V duration. Document device-only stabilization limits.

**Evidence:** Rider clips: 753 frames/12.587944 s, two >25 ms gaps; 929 frames/15.689656 s, twelve >25 ms gaps, none >40 ms. First clip power-card sequence holds ~200 ms then changes within 1–2 frames. Earlier 80 ms delay claim is retracted. Current source stays untouched: user confirmed saved chip explicitly selects battery current and will change it manually.

**References:** CameraX OverlayEffect queues GL textures and auto-renders oldest on overflow; drawFrameAsync renders by exact timestamp. Camera2 SENSOR_TIMESTAMP is shared by outputs (readout timestamps require care on newer APIs). Upstream source and official docs read during diagnosis; actual pinned 1.5.3 artifact must compile and emulator must verify timestamp matching. No physical-device operations authorized for this follow-up.
