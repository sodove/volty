# Nearby stock capture experiment and persistent diagnostics

The 0.7.25 field report describes Xiaomi silence without a microphone indicator,
occasional direct-call stalls, and a three-person room freezing and disconnecting.
The user approved trying the stock capture path, an SDK upgrade, and useful logging
together. This is a controlled field build, not a claim of physical-device acceptance.

## Audio ownership

AUTO and HEADSET use WebRTC's native AudioRecord and its shared playback. The app's
callback observes successful native reads without replacing PCM or adding capture
pacing. Hardware AEC/NS stay disabled; WebRTC software processing stays enabled.

PHONE retains the existing independently routed builtin microphone and media
playback, including A2DP. Capture switches are fenced: stop the custom recorder
before enabling SDK capture; wait through two callbacks after disabling SDK capture
before starting the custom recorder. PHONE PCM remains gated by measured input route
and current mute intent. Failure callbacks do not turn missing native reads into
successful capture evidence or an unpaced busy loop.

Review correction: closing the frame gate precedes input changes, and applying the
selected input precedes re-enabling SDK capture. Disabled callbacks during final
PHONE cleanup retain ten-millisecond pacing. Three regression tests cover the
handoff gate; native verification covers cancelled changes and PHONE shutdown.

Retraction: the ADM public preferred-input setter cannot accept null in the pinned
WebRTC binary: it dereferences the device for logging. Its package-level recording
setter supports null. A small compile-checked bridge restores automatic routing
without reflection or leaving a previous headset pinned. The initial emulator
attempt exposed and failed on this issue; it was not counted as a successful test.

LiveKit is pinned to 2.28.0 / WebRTC 144.7559.09. The
[official release](https://github.com/livekit/client-sdk-android/releases/tag/v2.28.0)
contains a silent-microphone race fix. Its applicability to our manual publication
sequence is unproven; the upgrade is part of the approved experiment. The existing
pinned AudioSwitch fork is retained. WebRTC's resolved capture-thread bytecode was
checked: native-read count, dynamic recording disable/enable, and callback ordering
match the earlier pinned binary.

## Diagnostic contract

The existing Nearby profile copy button exports push diagnostics and the last three
voice allocations. Private app storage retains bounded reports after call cleanup
and process restart. A single IO writer uses AtomicFile; report formatting and disk
writes are limited to once per second. Export reads current in-memory state, and
cleanup publishes its final state explicitly.

Review correction: final reports from older, still-retained allocations update
their own slot even after a newer allocation starts. They cannot replace the newer
report or reinsert an evicted allocation. Three regression tests cover this history;
the three-peer native probe verifies all three final reports after process restart.

Reports include desired routes, capture path, mute gates, Android recording source
and client-silenced observations, successful capture/render byte counts, numeric
post-mute PCM levels, callback priority and maximum duration, remote audio-track
count, RTP counters/loss/jitter/mean jitter-buffer delay, classified native errors,
and whitelisted SDK disconnect reasons. PHONE playback reports owned AudioTrack
underruns and maximum write duration. SDK underruns remain unknown where no public
measurement is available. Recording configurations remain explicitly uncorrelated;
they do not prove the SDK recorder's actual input route.

No PCM, tokens, peer/session IDs, coordinates, account details, or arbitrary SDK
exception messages are persisted. Old callbacks cannot replace the current report.
There is no unconditional microphone gain increase or fixed extra jitter buffer.

## Verification and field acceptance

Five regression tests first failed against stubs/missing export fields, then the
selected instrumentation/diagnostic/disconnection suite passed (14 tests).
The corrected native emulator probe connected three real LiveKit peers to an
exact-owned private community and maintained their backend leases. It verified two
remote tracks per peer, successful stock capture/shared render, bidirectional RTP,
PHONE's measured builtin input/owned output counters, return to SDK native reads,
and twelve cancelled route changes without engine failure. Persistent export was
checked after force-stopping and restarting the process. Temporary activities and
their manifest entries are removed before the final release. Test accounts and
private communities were deleted with explicit ownership checks.

The final uncached full unit run passed 3,521 tests across 354 fresh XML reports
with no failures, errors, or skips. The additional six history/handoff tests first
failed against the faulty contracts and then passed with the review corrections.

An earlier PHONE-counter assertion ran before the one-second asynchronous poll had
sampled a newly created output track; it failed and was replaced with a bounded wait
for published native facts. It was not scored as proof that playback failed.

Physical Xiaomi microphone startup and two-way audibility, Bluetooth independent
routing, headset volume, and three-device audio quality remain rider checks. The
emulator does not reproduce those devices or establish audible quality. For a failed
call, copy diagnostics from Nearby profile after disconnecting; the report survives
the disconnect. Backend/SFU configuration is unchanged by this audio batch.
