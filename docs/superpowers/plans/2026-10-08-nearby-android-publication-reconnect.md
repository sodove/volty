# Nearby Android publication reconnect implementation plan

> **For agentic workers:** Use superpowers:executing-plans for the complex ownership/lifecycle changes with the primary agent. Delegate only narrow read-only reviews to Luna/xhigh with full context; no Astra.

**Goal:** Preserve the user's mute intent and restore the owned microphone publication after SDK full reconnect, without premature mute failure, duplicate microphone allocation, or speech before current authorization.

**Architecture:** The SDK continues to own transport reconnect. The driver tracks publication readiness separately from transport and accepts mute intent while publication is missing. Actual owned SDK publication acknowledgement restores readiness and reconciles the latest intent. A muted publication skipped by SDK republish is restored closed, using the existing owned track; only the driver may apply the authorized latest mute intent.

**Evidence:** Android SDK2.25.3 RTCEngine notifies Reconnected before onPostReconnect; LocalParticipant.prepareForFullReconnect clears publications, and republishTracks skips muted tracks. AndroidVoiceSdkSession currently ignores local TrackPublished; its setMuted fails immediately without a publication. Reducer clears published on Reconnecting, and the conversation panel requires published for audio settings. Sources read locally and version verified against Gradle. See [network evidence](../specs/2026-10-08-nearby-degraded-network-evidence.md).

Authorization comes from the existing user-approved Nearby rewrite and continuous execution request. This repairs the agreed reconnect behavior; no new user-facing product choice or transport/dependency replacement is needed.

## Task 1 — readiness and deferred mute intent

- [x] Add driver regressions with deliberately absent publication after an actual delivered Reconnecting event. Assert no SDK mute is issued while missing; a later Published event reconciles the latest desired value.
- [x] Test wrong/stale/revoked callbacks and a rejected restored mute. Assert issued commands and published failure events, not timing alone.
- [x] Update VoiceEngineDriver readiness at owned reconnect/unpublish/publish boundaries without admitting initial publication before start acknowledges it or opening an unauthorized handle.
- [x] Handle local AUDIO RoomEvent.TrackPublished in AndroidVoiceSdkSession only for the exact owned localTrack. No guessed publication from Reconnected.
- [x] Run focused tests, then fresh full unit suite. Pure tests prove driver decisions; SDK mapping requires compilation and device validation, not fabricated Compose/Robolectric coverage.

## Task 2 — closed restoration of SDK-skipped muted publication

- [x] Add a narrowly scoped common coordinator/port only where necessary to test SDK ordering independently of Android. SDK publication removal may occur after a first closed restore acknowledgement; coalesce restoration and preserve the original SDK-skipped-mute mode across that ordering.
- [x] Reuse the owned local track and publish closed. Do not unmute the SDK's saved publication to influence its private reconnect cache, invent completion from elapsed time, or allocate a competing Room/audio stack.
- [x] Consume actual publication/unpublication/transport observations. Existing unmuted publications wait for SDK restoration; a skipped muted publication gets a bounded, cancellable closed restore. Stop/owner retirement cancels work and rejects late acknowledgements.
- [x] Driver alone applies latest authorized mute after restoration. Test changing intent, late SDK removal, permission/lease retirement, failures, no parallel publications and no microphone enable before acknowledgement.
- [x] Compile Android, run focused and fresh full tests; review narrow interfaces/ordering; rebuild and verify signed APK after final native changes. Phones remain deferred by user.

## Completion limits

This plan does not establish Xiaomi audibility, actual split microphone/output routing, foreground/device lifecycle, killed-process incoming push, or removal of the rare public SFU subscriber defect. Preserve those requirements in the full Nearby goal. No BLE or server config changes.

Task 1 verified on 2026-10-08: original three regressions were actual RED (c941dr, 3 tests/3 failures); strengthened early-publication evidence regression was actual RED (c941du, 1 test/1 failure). Final fresh full Android gate c941dv: exit 0, 44s, 35 tasks executed; exactly 3378 tests/334 fresh XML reports, no failures/errors/skips. Five added tests cover missing publication, latest intent, wrong tag, revoked authorization, restored mute rejection, queued controls and initial acknowledgement. Narrow Luna/xhigh review blocker reproduced and fixed; targeted recheck clear. Android compiles in the test gate; no device or signed-APK validation claimed. Task 2 remains open.

Task 2 verified on 2026-10-08. See the dated Android reconnect evidence for actual RED/focused/full results and scope. Final full gate c941e1: 3393 tests/335 fresh XML reports, zero failures/errors/skips, uncached exit 0/43s/35 executed. Narrow lifecycle and native bridge reviews clear. Signed c941e2 release plus migration gate: exit 0/1m41s/63 tasks executed. APK signature v2 verified, one signer, same signing identity as previous c8d0c90a artifact; package 0.7.13/code36/min26/target36. Immutable local APK SHA256 a403d1cacb314f0fa3b1f3d07cd1077004e1836667c51197361991fdaedcbd82, 69114247 bytes. R8 mapping contains the recovery coordinator and guarded native mute operation. No phone install, audible test or production deployment claimed. This completes the publication-recovery plan, not the full Nearby goal.

Historical note: the Evidence paragraph describes the adapter when this plan began. Task 1 now consumes exact-owned TrackPublished and verifies an actual retained publication on soft resume; Task 2 restores the SDK-skipped muted publication closed. Transport still cannot supply capture/render evidence.