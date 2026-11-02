# Ride video with configurable telemetry overlay

User approved implementation of the functionality discussed in chat: record with the phone camera and immediately save a video with a configurable TelemetryStudio-like overlay. No video timeline or postproduction editor. The sample video uses small top cards and a bottom speed arc.

## Scope

- Open Camera from the ride dashboard, independently of dashboard style.
- Native Android CameraX preview and recording, with the SAME OverlayEffect rendering the telemetry into preview and encoded frames. Save MP4 with optional microphone audio; permissions requested on this screen only.
- Three widget kinds: value card, icon/value, arc. Select metrics, label, unit, order, colors, opacity, font size, rounding/padding/shadow; arcs have position, bounds and thickness. Cards automatically wrap/center as in Studio.
- Four starting templates (Top Row, HUD, Minimal, Racing), saved user templates and selected configuration persist locally. User can add/remove/reorder widgets and edit their settings before recording; recording freezes editing and capture settings.
- Threshold rules with finite numeric operands, comparison operators and foreground/background overrides; later matching rule wins. Missing data does not evaluate as zero.
- Supported initial metrics: controller speed, duty/PWM, controller temperature, motor temperature, motor current, controller battery current/power, battery SOC/voltage/current/power/temperature. Labels identify the source; no silently mixed controller/battery channels. Duty estimates retain ~ and ceiling markers. No new BLE commands.
- Resolution selection HD/FHD/UHD with explicit supported-quality filtering. Default FHD when supported. Standard SDR video; use CameraX default compatible frame rate, no unverified 60 fps promise. Rear/front switch while idle. Audio optional, off by default (avoids silently opening microphone and concurrent voice capture).

## Architecture and decisions delegated to builder

Pure common Kotlin serialized overlay model, templates, validation, threshold evaluation, frame-time sample selection and telemetry mapping. Android Canvas renderer uses a normalized 1920-wide design coordinate space and renders within the rotation-corrected camera crop. Same render function for encoded and preview outputs. Android-specific camera controller owns resources; Compose only renders state and forwards actions. Decompose component owns editable settings; local storage persists a versioned document. Dedicated internal Camera Activity avoids disturbing retained main navigation/map; entry is an expect/actual composable button. This is a builder decision, not a new user request.

Telemetry is observed from process BmsRepository, not a hidden dashboard component. Sample delivery uses elapsed realtime receipt timestamps. Camera timestamp origin must be compared/mapped explicitly (camera source can use UNKNOWN timebase); do not assume wall clock or direct equality. Use a bounded sample history, select only snapshots received no later than frame time, and expire live values after a short documented freshness interval. In addition respect each existing source's has/known/online flags and aggregation completeness. Across vehicle change/disconnect clear prior samples. Preserve the demo nature if the source is a simulated vehicle.

No original clean duplicate video is saved in this version. Existing ride logging continues independently; opening/stopping camera never connects/disconnects the vehicle or starts/stops an existing ride log. Recording begins only after explicit button press. Finish on background/Back; retain resources until Finalize, report errors honestly, and never call failed capture successful. Keep the screen awake during foreground capture. Configuration changes retain this camera screen via Activity configChanges and update target rotation while idle; freeze orientation during recording. Camera/microphone permission revocation and capture exceptions show recoverable errors. Files saved to public gallery (MediaStore Q+, public Movies/Volty file plus media scan on API26-28, requesting WRITE_EXTERNAL_STORAGE with maxSdkVersion 28). No camera foreground service because background recording is outside scope.

The builder chooses practical controls and safe limits for the configuration. Validate bounded widget count, colors, font sizes, finite arc ranges and thresholds, unique IDs, persistence payload size. Keep text readable on compact portrait and expanded landscape; editor scrolls. Use resources in values and values-ru. No Android backslash escapes in Compose strings.

## Reuse and limits

Port rendering behavior from telemetry-studio/packages/engine/src/{renderer,conditions,templates}.ts and widgets/{valueCard,iconValue,arcIndicator}.ts. Do not port TS/CanvasKit runtime, FFmpeg, server, user authentication, log parsers or stale-data filling. No importing Studio template files (Studio has no existing file export). Reuse concepts rather than copying undeclared/dead capabilities such as progress_bar. Android icons can use a deterministic small vector/glyph set with no new font bundle; font/layout differences need device visual verification.

## Verification

TDD pure logic: unknown/incoherent input flags, stale/frame-time selection, disconnect reset, estimated duty, rules/order, serialization validation, templates/edit/reorder and recorder state transitions. Run targeted unit tests freshly then full :composeApp:testDebugUnitTest with --rerun-tasks --no-build-cache and assert XML counts/failures. This is not a mutation sweep; do not claim mutation evidence. Assemble debug APK. Camera permission flow, crop/rotation, preview-vs-file parity, audio, 20-30 minute thermals and actual MP4 playback require phone checks and must be reported as such, not disguised as unit tests.

## Global constraints

Android minSdk 26. No schema migration. Never write Begode FFE1. Preserve all unrelated working-tree changes. Gradle workers maximum 4 and JVM -XX:ActiveProcessorCount=4. No commit of unrelated work; user did not request push/publish.
