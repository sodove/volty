# Nearby Android Calls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for the primary implementation. Keep architecture, account ownership and audio integration with the primary agent. Applicable AGENTS.md permits only narrow Luna xhigh subtasks with complete context; no Astra without a new user request.

**Goal:** Make direct calls usable with Volty incoming-call UI, Android Telecom lifecycle and observable background delivery, while fixing explicit phone-microphone/headset playback selection.

**Architecture:** Retain the current backend/LiveKit and exact-account call state machine. Add an Android platform adapter which mirrors verified call state and returns system actions through existing gates. One route authority per session; Telecom-owned direct calls must not also use AudioSwitch for communication endpoint selection. Keep the ride pill, group voice membership and position consent independent.

**Tech stack:** Existing Kotlin/Compose/Decompose/Koin, Firebase Messaging, WorkManager, WebRTC ADM; Android Telecom/Core-Telecom and NotificationCompat.CallStyle. Read official APIs and verify the chosen dependency's current stable version and minSdk26 compatibility before adding it. Do not introduce a guessed version or default-dialer role.

**Design:** `docs/superpowers/specs/2026-10-09-nearby-android-calls-design.md` (user selected the Telecom/Volty option). Primary execution started under the user's standing instruction to continue without repeated check-ins and the approved scope; Task1 diagnostics are implemented, broader call/audio integration remains pending.

## Task 1 — observable background delivery

Ownership: new common push outcome model; `VoltyFirebaseMessagingService.kt`, `IncomingCallPushWorker.kt`, `AndroidPushStartup.kt`, push registration/wake/gate and `AndroidIncomingCallNotificationPort.kt`; Android diagnostics export/settings projection as appropriate. Backend queue outcome instrumentation only if needed after tracing, without schema changes for convenience.

- [x] Define bounded enum-only stage outcomes for registration, FCM receipt, queued worker/start, account restoration, exact-binding verification, fresh call verification and Android post rejection/submission. Distinguish notification eligibility from registration/receipt. No token/payload/name/email/PCM/credentials in outcomes or logs.
- [x] Add failing pure tests for rejected binding versus actually issued backend denial and diagnostic-sink failure isolation. Existing tests retain expiry, stale-account and publication-fence assertions. Native history/copy are inspected on the emulator rather than dressed up as common tests.
- [x] Wire outcomes into the actual existing paths without widening hint authority or replacing failures with success. Record whether worker is delayed/retried/exhausted; avoid catch-all ambiguity. Preserve cancellation semantics and exact-account fences.
- [x] Capture controlled owned-fixture calls on a Play-enabled emulator: minimized receiver gets actual FCM/WorkManager verification and a visible incoming notification; after native Demo disconnection and `am kill`, confirmed absent process is restarted by FCM and fresh registration discovery earns another notification. Native decline confirms backend DECLINED. This establishes delivery for the fixture/emulator, not the Pixel-specific cause.
- [x] Preserve the original user account. Two isolated synthetic accounts used for warm/cold checks were deleted after exact UUID/name verification; native receiver subsequently shows unauthenticated state. Diagnostics increment fresh suite3479tests/344reports, both signed variants and migration pass. User declined the Pixel clipboard request for now; continue independent checks without requesting it again.

## Task 2 — native routing evidence and explicit phone capture

Ownership: `OwnedVoiceAudio.kt`, `VoiceAudioRouteMonitor.kt`, `AndroidVoiceSdkSession.kt`, `AndroidVoiceAudioFactory.kt`, common diagnostic/route result model and relevant tests; diagnostic activity/service only for controlled probes.

- [x] Document the retraction: REQUESTED is only an ADM preference submission; actual cached WebRTC144.7559.05 bytecode discards AudioRecord.setPreferredDevice's false result. Current uncorrelated recording configs remain uncorrelated. Public ADM custom capture callback plus disabled SDK AudioRecord can support an owned stream without reflection, but SDK mute is applied before the custom callback; any adapter must own final mute/lease gating and native lifecycle evidence. No audio fix is claimed from this inspection.
- [ ] Introduce an owned stream routing boundary with observable AudioRecord/AudioTrack device identity and preference result. Use public SDK/platform APIs or an explicitly owned adapter, never reflection into private SDK fields. Maintain native release and pending-request barriers.
- [ ] Write meaningful tests for stale route evidence, requested versus confirmed changes, explicit-source rejection, unplugging and mute preservation; assert commands/evidence rather than repeating priority-list implementation.
- [ ] Verify ordering for output endpoint changes and microphone requests. Separate a requested Bluetooth communication pair from an explicit phone capture/playback split. Never silently substitute headset mic or phone speaker for an explicit choice.
- [ ] Evaluate the split with the user's actual Bluetooth device. A2DP playback-only is a candidate, not a guarantee; record latency and actual routes. If native APIs cannot achieve the split, implement the owned capture/playback adapter after the probe establishes the requirement, rather than declaring the preference applied.
- [ ] Keep route authority configurable by the eventual Telecom adapter, and group voice semantics unchanged. Run fresh common tests and both signed builds before a code commit. Physical audibility remains required even when emulator tests pass.

## Task 3 — direct-call Telecom adapter

Ownership: new `AndroidDirectCallPlatform.kt` and associated lifecycle/service adapter in `data/voice`, DI/Application/manifest wiring; a small pure projection/command owner in common voice code; exact interfaces with `VoiceAccountCoordinator`, `AuthenticatedVoiceRuntime`, incoming registry and foreground launch.

- [ ] Verify current official Core-Telecom API/version, callback behavior on API26..36, service/permission requirements and compatibility with separate input selection. Select Core-Telecom or a platform self-managed fallback with documented reasons.
- [ ] Define one platform-call identity per original account/call/operation. Write failing tests for duplicate discovery/FCM, expired incoming, caller cancellation, logout, old-account callbacks, switching and late native completions.
- [ ] Mirror verified incoming/outgoing/accepted/media/terminal state. Do not mark calls active on queued acceptance. Route answer, reject and disconnect through the existing state machine, including fresh target verification and cleanup retry.
- [ ] Register the phoneCall foreground service/permission and account; post/update the required call notification within Android's platform deadline. Preserve microphone consent and accepted-call launch boundary. Implement system/headset callbacks with exact identity checks.
- [ ] Disable AudioSwitch communication routing for Telecom-owned direct calls and use Telecom endpoint flows/callbacks as sole authority. Group sessions retain their owner. Handle retirement in both orders without leaked platform calls or route/focus restoration errors.
- [ ] Verify rejection, normal hang-up, caller cancellation, expiry and account retirement through an owned native fixture; inspect actual platform calls and resource cleanup. Fresh full suite/builds before commit.

## Task 4 — incoming-call screen and notification actions

Ownership: new internal Android incoming-call Activity/presentation, notification/pending intent adapter, action-address parsing and MainActivity handoff; bilingual strings; existing common registry only where necessary.

- [x] Separate opening the incoming-call screen from ACCEPT. OPEN is a distinct canonical address and PendingIntent; body/full-screen launch renders an internal Activity. Only an explicit answer button launches MainActivity ACCEPT; original binding/ticket and admission gates remain. Earlier contentIntent used ACCEPT; this was a real auto-answer risk.
- [x] Write failing tests for display-only action and non-consuming native ticket lookup (RED parser, then RED actual lookup reached). Cover wrong binding/ticket/action, consumed/retired/expired tickets and deliberately identical ticket IDs after same-user relogin. Existing duplicate acceptance tests retain the original action gates. No Compose unit-testing claim.
- [ ] Use CallStyle incoming/ongoing notifications with a verified caller label, answer/reject or hang-up. A lock-screen Volty screen shows actual call state and clean terminal transitions. Reuse existing app styling and animate transitions. Strings in values and values-ru.
- [ ] Apply allowed full-screen/lock-screen APIs and eligibility checks. Heads-up remains usable when full-screen permission is absent. Handle launch failure, no mic permission and a changed account explicitly. Do not assume force-stopped applications can be revived by FCM.
- [ ] Native verification: minimized/locked receiver; answer, reject, hang-up; caller cancels while ringing; expired screen; rotate/recreate; logout/relaunch; deny notification/full-screen/mic access. Inspect screenshots and actual issued commands; Compose is not unit-testable here.
- [ ] Fresh full suite, signed ARM/x86 and migration verification; scan immutable APK for credentials and verify existing signer. Install -r only, preserve original user data.

## Task 5 — physical acceptance and release

- [ ] Pixel7 with notifications allowed: foreground, minimized, lock screen, removed from recents; capture credential-free stage outcomes and native notification/UI. Separate Android force-stop from these states.
- [ ] Bluetooth: isolate sound near phone versus headset mic and check what the other device hears. Confirm explicit phone capture + headset playback, switching, mute/deafen, unplug/reconnect and route evidence. Include Xiaomi when available.
- [ ] Record measured results and unresolved limits in the ledger; no percentage/complete claim substitutes for physical checks. Release the signed APK with its concrete changes and remaining limits. Backend deploy only if a diagnosed backend change is needed.
