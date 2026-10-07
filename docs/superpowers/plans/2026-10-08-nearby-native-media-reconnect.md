# Nearby native media evidence after reconnect

Continue the user-approved Nearby rewrite under existing continuous execution authorization. Primary owns the lifecycle/native integration; delegate only narrow read-only reviews to Luna/xhigh with sufficient context. No Astra.

## Source evidence and design

`VoiceSessionReducer` clears capture, render and subscribed peers on Reconnecting. The current Android adapter reacknowledges only local publication. LiveKit Android 2.25.3 `Room.onPostReconnect(false)` retains existing subscribed remote tracks and updates their settings. The injected ADM can remain running without another start callback; its samples callbacks already accumulate byte counts in `VoiceDiagnostics`.

Recover audio-device evidence from actual fresh sample-byte progress paired with current native running=true. Consume progress even when running is false/unknown so later reuse cannot claim a new start. Do not infer capture/render from transport or local publication. A pure tracker selects events; `OwnedVoiceAudio` serializes actual start/stop callbacks, reconnect reset and snapshot reconciliation under its existing monitor, including event enqueue, to prevent a stale poll-start overtaking native stop.

Recover peer subscription evidence from the current SDK map, requiring audio publication, admitted subscription and an actual audio track. Reset reported peer evidence at reconnect; reconcile actual snapshots on reconnect completion, relevant track events and the existing bounded stats cadence. Do not trust late event payloads to mutate the current peer set. One remaining valid audio track keeps a peer subscribed; removed/currently denied tracks cannot be counted.

This repairs evidence state, not physical audibility. Microphone/output routing, Xiaomi behavior and native device lifecycle remain physical checks. No API/SDK dependency/server/BLE changes.

## Execution

- [x] Add common audio and remote evidence contracts with tests; observe actual assertion RED before behavior implementation.
- [x] Cover reused counters, false/unknown flags despite fresh bytes, foreign generation, direct start/stop, reconnect resets, closure and independent capture/render evidence. Use deliberately incoherent fixtures.
- [x] Cover retained subscriptions, late obsolete events through current snapshots, multi-track peers, denied/missing/non-audio observations and owner closure.
- [x] Implement pure event selection and integrate native serialized observation boundaries; refresh physical evidence independently of stats-request success. Reconcile only current SDK maps.
- [x] Focused tests, fresh exact-count full suite, narrow contract/native boundary review, signed release and migration verification. Source/tests frozen during Gradle runs.
- [x] Record verification limits and preserved whole-goal gaps; commit after the required fresh full gate. Phones remain deferred by user.

Completed on 2026-10-08. Exact verification and limits are recorded in [native media evidence](../specs/2026-10-08-nearby-native-media-evidence.md). This closes the source-confirmed evidence repair, not the full Nearby goal.
