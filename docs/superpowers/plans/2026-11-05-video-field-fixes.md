# Ride video field fixes

> Execute with superpowers:subagent-driven-development. Primary owns Android camera integration; narrowly scoped display smoothing is delegated.

**Goal:** recover the camera after sleep, provide supported faster/stabilized capture, show useful earned current in Icon chips, and animate overlay numbers.

**Architecture:** keep CameraX 1.5.3 and the existing VIDEO_CAPTURE OverlayEffect. Recreate an idle session across host stop/start, defer resource release until recording finalization, and let the screen close independently. Negotiate normal capture configurations against their actual preview/video/effect session. Live display follows a causal 120 ms chase; raw samples remain the authority for presence and thresholds.

**Constraints:** no Begode writes; no inferred current from power; preserve unknown/freshness/provenance contracts; Russian and English resources; CPU cap 4; no claim of physical Pixel verification. CameraX high-speed sessions exclude CameraEffect, so 120 FPS needs a different pipeline and is outside this corrective patch.

**Revision after source comparison:** the rider asked for the actual Telemetry Studio behavior, and accepted a smaller telemetry delay. Replace the initial dashboard-response-filter choice above with 80ms buffered linear interpolation; add centered content movement at TS's `0.15` per frame. Preserve the previous decision here as a retraction. Details: `docs/superpowers/specs/2026-11-05-video-interpolation-comparison.md`.

## Tasks

- [x] Camera lifecycle: tested issued suspend/rebind operations across idle and active recordings, late attachment, repeated stop/start, and finalization while backgrounded. Rebind only after all outstanding segments finalize. STOPPING allows Back; closing the screen retains finalization ownership.
- [x] Capture modes: query supported fixed normal 30/60 FPS for the actual session, prefer 60, retain measured FPS separately, and request supported stabilization. Report CaptureResult mode rather than assuming that requesting stabilization proves it is active. Graceful automatic fallback when no supported exact range exists.
- [x] Current: **retraction of the initial controller-first choice:** follow the rider's real-BMS-first rule, with controller fallback for T4. Add explicit provenance, preserve genuine zero, normalize the automatic metric's sign, keep explicit metrics and edited presets meaningful. Test independently stale contributors and expiry from the selected source.
- [x] Initial smoothing: raw-gated numeric/secondary text and draw-key integration retained; the dashboard chase was superseded by the source-comparison correction below.
- [x] Source-comparison correction: 80ms buffered interpolation, TS 0.15 content motion, animated positions in draw keys, display frame clock for preview and each normal capture frame for recording. Old left-aligned ICON_VALUE saved documents keep their layout. Duplicate timestamps select the last offered sample.
- [x] Verify: full fresh suite **6559 tests, 0 failures, 2 existing skips**; debug APK; three emulator idle sleep/wake cycles, recording after wake, stop-and-Back, background stop with valid MP4. Production signed APK and embedded offline pins verified; eight arm64 libraries and APK ZIP pass 16KB alignment. Physical Pixel stabilization/1080p60 remain field checks; emulator capture was about 20 FPS. Reviewed lifecycle and current selectors; regression tests cover review's duplicate-timestamp finding.
