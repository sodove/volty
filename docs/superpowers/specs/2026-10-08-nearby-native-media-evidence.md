# Native capture, render and subscriptions after reconnect

## Source-confirmed gap

`VoiceSessionReducer` clears capture, render and subscribed-peer evidence on Reconnecting. Previously, `AndroidVoiceSdkSession` reacknowledged only the owned local publication. LiveKit Android 2.25.3 `Room.onPostReconnect(false)` retains existing subscribed remote tracks and updates their settings. An ongoing injected ADM also need not emit another start callback. Thus retained media could remain unrepresented in the application state.

This establishes an evidence-state defect, not the cause of field silence. The SDK source also confirms that `RemoteTrackPublication.subscribed` rejects undesired or permission-disallowed tracks, and `RemoteParticipant.unpublishTrack` removes the map entry before posting unsubscribe. Current-map observations can therefore replace mutation based on an obsolete callback payload.

## Implemented evidence boundaries

`VoiceNativeAudioEvidence` requires fresh byte-counter growth and current native running=true to reacknowledge capture or render. False/unknown observations consume progress too, so changing a flag later cannot reuse old bytes. Counter decreases and negative values never rebase the high-water mark. Direct native start/stop callbacks remain independent evidence. Wrong-generation and closed owners emit nothing.

`OwnedVoiceAudio` serializes actual callbacks, reconnect reset, observation and nonblocking event enqueue under a separate `evidenceLock`. Reset and Reconnecting enqueue share that boundary, preventing a sample-based start from overtaking an observed stop/reset. Close fences the tracker while briefly holding this lock, then releases it before `module.release()`: native release may join callback threads which need the lock. Actual hardware callbacks during release still update the retired owner's diagnostic flags and fixed trace, but cannot emit live session events or update the keeper through a retired owner.

`VoiceRemoteAudioEvidence` diffs the current owned SDK map, requiring an audio publication, admitted subscription and an attached audio track. Reconnect resets reported peers; current retained subscriptions are then reacknowledged. A peer with another complete audio track remains subscribed. Missing, denied and non-audio contributors cannot make the peer complete. Delayed subscribe/unsubscribe callbacks trigger current-map reconciliation and cannot add or remove peers from their obsolete payloads.

Native audio and remote-map refresh reuse the existing one-second stats loop and run before RTC statistics retrieval. A failed stats request cannot hide independent evidence of sample progress/current subscriptions. No new reconnect loop, Room, microphone allocation, route policy or transport admission is introduced. Android DI and engine ownership use the same queued Main dispatcher; native audio callback synchronization handles their additional threads.

## Actual verification

- `c941e3`: all 12 initial common contract tests failed with assertions before behavior implementation; uncached Gradle exit 1, 40 seconds, 35 executed tasks.
- `c941e4`: all 12 passed after implementation; uncached exit 0, 37 seconds.
- Two additional reducer integration tests verify recovered flags without transport-invented media, preserved mute/deafen/sharing/lease state, and no stopped/replacement-owner revival.
- `c941e5`: intermediate full suite passed, uncached exit 0, 43 seconds, 35 executed tasks. Exactly 3407 tests/336 fresh XML reports, no failures/errors/skips.
- `c941e6`: final full suite after native diagnostic correction passed with the same exact count and fresh-report check; uncached exit 0, 43 seconds, 35 executed tasks.
- Narrow Luna/xhigh source reviews of the pure contracts, native serialization/release boundary, diagnostic correction and current-map admission found no concrete remaining defect in those scopes. No tests were delegated; no Astra was invoked.
- `c941e7`: signed release and SQLDelight migration verification passed, uncached exit 0, 1 minute 31 seconds, 63 executed tasks. APK signature v2 verifies with one signer, matching the previous signed artifact. Package 0.7.13/code36/min26/target36; R8 mapping contains both evidence trackers. Immutable local APK: `volty-nearby-c941e7-release.apk`, 69114247 bytes, SHA256 `f0c53b10cbb1d10f66e52609777ca44ec00317685d47230657c09a79990efe47`.

Pure tests prove event decisions and reducer interaction. Android wiring was compiled and source-reviewed; it was not exercised by Robolectric, Compose tests or a physical phone. This is not a mutation-sweep claim. Source and tests remained frozen during every Gradle invocation; subsequent documentation edits do not alter the signed native contents.

## Whole-goal limits

Sample callbacks and native running flags do not establish audible speech or actual input/output routing. Phones remain deferred by the user; Xiaomi, split phone microphone/headset output, hotplug and foreground/device lifecycle still require physical verification. The rare SFU subscriber negotiation defect, killed-process incoming push, active-call production recovery and whole-branch review/integration remain open. Poor network remains a possible field cause.

No server, database, SFU configuration, SDK dependency, BLE or vehicle changes are part of this repair. The full Nearby goal remains active.
