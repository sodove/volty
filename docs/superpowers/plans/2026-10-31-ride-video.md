# Ride Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Only narrow tasks go to Luna xhigh; primary owns Android integration.

**Goal:** Record phone-camera video with a configurable, persisted telemetry overlay.

**Architecture:** Common pure overlay configuration and policies; Android Canvas renderer shared by preview and capture through CameraX OverlayEffect. Dedicated internal Activity and Decompose settings component, BmsRepository adapter independent of dashboard visibility.

**Tech Stack:** Kotlin, Compose, Decompose, kotlinx.serialization, Android CameraX 1.5.3.

**Spec:** docs/superpowers/specs/2026-10-31-ride-video-design.md

## Global Constraints

- minSdk 26; no SQLite changes; never write Begode FFE1.
- Preserve unrelated dirty files. Stay in current checkout to retain existing integrated work; no new worktree required.
- Gradle workers maximum 4, JVM -XX:ActiveProcessorCount=4.
- Strings in values and values-ru. No Compose Android backslash escapes.
- User's 'Давай прикрутим' authorizes the described functionality and implementation; do not ask for redundant approval. Builder selects routine technical defaults from spec.

## Review Focus

- Missing/stale source with nonzero fields must render unknown and never trigger numeric thresholds.
- Camera timebase UNKNOWN must map to elapsed receipt time, never compare epoch milliseconds directly.
- Permission denial, background/Back and Finalize errors must not leave active recording/resources or report false success.
- Invalid persisted schemas must not crash or create unbounded render work.
- Rotation/crop and portrait/landscape render position require device testing, not a purported Compose unit test.

### Task 1: Common overlay model and pure policies

**Files:** commonMain/.../domain/video/{OverlayModel,OverlayTemplates,OverlayPolicy}.kt; matching commonTest/.../domain/video/*Test.kt.

**Interfaces:** VideoMetric; OverlayWidgetKind; OverlayWidget(id, metric, kind, label, unit, style, conditions, arc); OverlayStyle; OverlayRule; OverlayColor; OverlayDocument; OverlayTemplates; VideoReading(value, estimated, atCeiling); VideoSnapshot(timeMillis, vehicleId, readings); VideoTimeline.offer/sample/clear; validated document JSON codec.

- [x] Write failing tests for defaults, rules ordered overrides and unknown bypass, validation and roundtrip, stale/future snapshots and reset; run targeted Gradle test to observe RED.
- [x] Implement finite bounded model, four templates, safe comparisons and bounded timeline.
- [x] Run focused tests; review API and implementation before integration.

### Task 2: Telemetry adapter and settings component

**Files:** commonMain/.../presentation/video/VideoTelemetryMapper.kt and VideoOverlayComponent.kt; Android video/VideoOverlayStore.kt; tests for pure mapping/edit policies.

- [x] Write and run failing tests for deliberately incoherent known flags, disconnect and estimated duty.
- [x] Map process repository snapshot without source mixing; component validates and persists changes/templates; startup fallback on malformed configuration.
- [x] Verify tests, review source and bounded persistence.

### Task 3: Native recording and renderer

**Files:** androidMain/.../video/{RideVideoActivity,VideoCameraController,VideoOverlayRenderer,VideoCameraScreen,VideoOverlayEditor}.kt; common/Android entry composable; Android manifest and CameraX dependencies.

- [x] Test the editing lock, stop/Finalize resource ownership and interrupted-output policy independently of Android; verify actual CameraX Start/Status/Finalize on emulator.
- [x] Port value card, icon/value and arc render behavior, styles, automatic row wrapping and conditions. Normalize against video crop, keep frame-time samples causal.
- [x] Bind CameraX Preview + VideoCapture with shared OverlayEffect. Handle permissions, lifecycle, front/rear and supported quality, optional audio, gallery save Q+ and API26-28 scan.
- [x] Add localized entry and editor, templates and recording status. Freeze configuration while recording, stop on background/Back, release after Finalize.
- [x] Compile debug APK and review integrated lifecycle paths.

### Task 4: Verification and handoff

- [x] Fresh full unit suite --rerun-tasks --no-build-cache; inspect XML aggregate counts and failures.
- [x] assembleDebug, resource and manifest checks.
- [x] Scoped independent review, fix actionable findings and repeat relevant tests.
- [x] Update ledger and deliver APK path plus explicit phone-check requirements (camera permission, actual MP4 parity, rotation/audio/long capture). No camera hardware success claims from unit tests. No push/publish.
