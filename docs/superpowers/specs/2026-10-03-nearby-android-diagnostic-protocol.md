# Android voice experiment — device protocol

This APK contains a separately owned experimental LiveKit adapter under
Settings → Debug → Voice diagnostics. It does not replace Nearby yet. It uses
the existing authenticated group endpoint, not v2 channel claims or leases.
Session generation is local. Media packets are never treated as backend ACKs.

## Preparation

Use two phones signed into different accounts in the same existing group. Start
from a fresh process; do not start legacy Nearby voice or sharing during the
experiment. Active legacy voice/sharing blocks the test because its old leave
cannot prove cancellation of a pending connect. BLE monitoring is unchanged.

Grant microphone and, on Android 12+, nearby-device permission. The microphone
foreground notification must appear before capture. A group remains in the
account after disconnect. No location/telemetry demand is created by this test.

## Measure rather than infer

1. Connect both phones with automatic routes, without headphones. Speak one at
   a time. Independently record whether each can hear the other. Export both
   traces before disconnect. Nonzero packets/samples alone do not prove audibility.
2. Repeat on the reported Xiaomi, recording the actual manufacturer/model and
   Android/build from the typed export instead of guessing from its product name.
   Check capture/render starts, RTP counters, audio focus/mode and stable errors.
3. With wired headphones, disconnect first, select PHONE microphone and HEADSET
   output, reconnect. Speak near the phone with headset microphone covered;
   confirm incoming voice stays in headphones. Repeat Bluetooth separately.
   Bluetooth communication-device intent may constrain input; actual stream
   routing remains unknown where Android public APIs cannot correlate it.
4. Test mute, incoming-sound mute and a silent room. The separately labelled
   silent communication keeper is never WebRTC playback evidence.
5. Disconnect while connecting; verify capture never starts afterwards. Retry
   after complete cleanup. Disconnect using the screen and notification; check
   the Android microphone indicator stops and the other phone loses this peer.
6. Rotate the screen and background the Activity during a call. Rotation must
   retain the same owner; background voice uses the foreground service. Back
   from the diagnostic Activity explicitly stops it. Kill the process; no call
   may automatically resume. Unplug the selected headset: explicit input fails
   closed, unsupported output is muted rather than playing through a speaker.

Exports contain bounded typed trace, desired routes, AudioSwitch-selected output,
communication-device hint and uncorrelated recording configuration types,
native capture/render byte counts and complete RTP counts. Actual stream routes
are explicitly UNKNOWN. Exports include app version, base APK SHA-256 and device
build, and exclude tokens, URLs, peer IDs, coordinates, raw errors, logcat and PCM.

## Verification boundary

Common tests establish ownership, cancellation, route policy and typed exports.
Android compilation, APK signature and database migration checks establish build
integrity. None establishes device audibility, Xiaomi support, foreground-service
behavior or PHONE→headphones routing. With no adb device attached these outcomes
are **NOT MEASURED**.

SFU strict revocation remains **NOT PROVEN**: the earlier native probe rejoined
with both original and SFU-refreshed tokens after RemoveParticipant. Server UX,
public catalog, direct calls, membership and v2 cutover remain subsequent work.
