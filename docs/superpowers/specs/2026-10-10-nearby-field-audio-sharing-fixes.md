# Nearby 0.7.25 field fixes

The 0.7.24 field report contains separate native audio, location and incoming-call failures. FCM reached the process and accepted call verification; notification posting failed afterwards.

## Audio

Nonblocking AudioTrack writes can consume only part of a frame or return zero. Preserve the unwritten aligned tail in a bounded 120 ms per-stream backlog. Use at least 80 ms native capacity and prime 40 ms before playback; clear backlog on mute, routing and release. Capture follows rolling 10 ms deadlines rather than accumulating callback sleep overshoot. A long stall resets cadence instead of emitting an obsolete burst.

Cancelled or rejected route changes restore the preceding input/output pair while the current session still owns its account lease. Successful rollback keeps the call active. Only an unrecoverable native rollback under a current owner terminates the session. Retirement cannot positively reactivate audio.

## Location

The existing fresh-only audience expires samples after 15 seconds. New clients request lastKnown=true; old clients retain the original contract. A location can remain on the map until the earliest of original capture+90 seconds, current sharing consent, viewer/peer session leases, membership and room authority. After 15 seconds it is visibly stale and carries no telemetry. Advancing server authority may renew consent credit but cannot renew the original sample bound or freshness. Revocation and room/session exit still remove it immediately.

## Incoming notifications

Android rejects CallStyle without a foreground service/user-initiated job/full-screen intent. See [Android 17 NotificationManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/services/core/java/com/android/server/notification/NotificationManagerService.java). Reproduced with API34 native notification manager. If full-screen permission is absent, post an ordinary high-priority CATEGORY_CALL notification with explicit Answer/Decline actions. Keep exact ticket/account verification and display-only open behavior. Diagnostics distinguish security, invalid-request and platform post failures without exception text or private data.

## Verification and limits

Regression tests exercise partial/zero writes, bounded backlog, cancelled route rollback, retirement, cadence overshoot, immutable sample age and telemetry removal. Real PostgreSQL tests verify consent/session bounds and the opt-in projection. A native emulator probe uses two actual LiveKit SDK peers, verifies bidirectional RTP/focus-owner PCM and twelve cancelled route changes without losing the call. A native notification probe reproduces the rejected style and verifies production fallback posting with full-screen permission denied. Temporary probes are excluded from release.

Physical click elimination, independent Bluetooth phone microphone/A2DP output and Pixel notification display require the rider's next field check. These changes do not claim every network reconnect failure is resolved.
