# Android owned microphone publication recovery

## Source-confirmed problem

The pinned Android SDK is LiveKit 2.25.3. Its `LocalParticipant.prepareForFullReconnect()` saves publications and clears the live publication map. `republishTracks()` later calls `unpublishTrack(track, false)` for each saved publication, but republishes only publications whose saved `muted` value is false. `RTCEngine`/`Room` can announce Reconnected before this post-reconnect work finishes.

Consequently, a previously muted microphone needs application restoration. A first manual restoration can itself be removed by the SDK's later unpublish of the same track. Reconnected alone cannot establish publication readiness. These are source-confirmed ordering problems; they do not establish the cause of the reported Xiaomi silence or the separate rare SFU subscriber failure.

## Implemented ownership boundaries

The SDK retains transport recovery. One `VoicePublicationRecovery` per native session observes reconnect, actual local removal and publication. It restores only the SDK-skipped muted publication, reusing the existing `LocalAudioTrack` and Room. The ADM is muted and the track disabled before publishing; the exact owned publication must exist in the current SDK map and be marked muted before application acknowledgement. SDK callbacks during the suspended closed restore are withheld until that acknowledgement.

The skipped-muted mode survives a first restored publication being unmuted by the driver. A confirmed later SDK removal can trigger one further closed restoration. Duplicate observations cannot create parallel attempts. Each cooperative attempt has a five-second timeout; at most two attempts are issued per reconnect cycle. Cancellation-resistant work retains its slot until it actually retires: timeout requests cancellation, and never proves native release or permits overlap.

Reconnecting closes the ADM without changing the SDK's saved `publication.muted`, so originally unmuted tracks remain on the SDK republish path. Soft resume acknowledges publication only from the actual owned map entry. Late callbacks referring to an obsolete publication cannot replace current map evidence.

The driver stores desired mute independently of publication readiness. After Main dispatch and the native resource-lock wait, `setMutedIfCurrent` checks the current handle, publication version, desired-mute version and authorization before synchronous ADM/publication writes. A superseded unmute is skipped. Publication restoration itself stays closed; only a current driver control may open it.

## Actual verification

- `c941dw`: eight recovery tests, seven expected assertion failures before implementation; uncached Gradle exit 1, 37 seconds, 35 tasks executed.
- `c941dx`: all eight passed after implementation, uncached exit 0, 37 seconds.
- `c941dy`: eleven tests, one expected failure for removal observed after Reconnected; uncached exit 1, 37 seconds. Fixed by retaining the awaiting-publication selection window until actual acknowledgement.
- `c941dz`: eleven recovery tests passed; two new native-boundary admission regressions failed because outdated unmute was issued after permission retirement or a newer mute intent. Uncached exit 1, 37 seconds. The guarded SDK operation fixes both.
- `c941e0`: all 45 focused driver/recovery tests passed, uncached exit 0, 37 seconds.
- `c941e1`: final full unit suite and Android debug compilation passed, uncached exit 0, 43 seconds, 35 tasks executed. Exactly 3393 tests in 335 fresh XML reports; no failures, errors or skips. The final suite includes rejected/throwing restore and combined driver/recovery latest-intent and revoked-authorization checks.

A narrow Luna/xhigh review separately inspected single-job retirement and the Android closed-publication/guarded-mute bridge against the pinned SDK source. No remaining concrete blocker was found in those reviewed boundaries. This is not a whole-branch review, a mutation sweep or a device audibility result.

## Limits and next evidence

Phones remain deferred by the user. Xiaomi capture/render, split phone-microphone/headset-output routing, hotplug, native foreground behavior and audible recovery require physical checks. A transport/publication acknowledgement does not invent capture/render evidence; those flags still depend on actual native observations. Reconnect handling of ongoing native capture/render evidence needs its own audit. Killed-process incoming push, the rare subscriber negotiation defect, active-call production SFU recovery and final branch integration also remain open.

No server, database, SFU configuration, SDK dependency, BLE or vehicle changes are part of this fix. The separate degraded-network evidence remains applicable and does not exclude poor connectivity as a field failure cause.
