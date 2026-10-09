# Nearby emulator acceptance — 2026-10-09

Two Android API34 Google APIs x86 emulators, different display densities and
Russian/English locales, ran signed releaseX86 builds against the deployed backend.
Original emulator data was preserved. A second, separate test AVD was created.
Two synthetic accounts and a private server were removed after testing.

## Confirmed defects corrected in 0.7.16/code39

- The 0.7.15/code38 candidate crashed at MainActivity startup: Koin requested the
  unbound NearbyVoiceSwitchGuard interface. AndroidModule now resolves the
  registered NearbySharingSwitchGuard explicitly. Actual startup succeeds.
- Nearby rendered transparently over the persistent map and its header overlapped
  the status bar. An opaque themed surface and status-bar padding correct both.
- The server navigation's Create action was clipped beyond the horizontal viewport.
  FlowRow wraps the actions; the complete button is visible in both locales/densities.

Retraction: previous signed-artifact verification established build/signature/key
exclusion, but did not establish Android startup. The 0.7.15 artifact is retired
because the emulator exposed this native DI crash. Common unit tests do not cover
Android DI startup or Compose rendering.

## Actual UI checks

- Both accounts log in. Authentication survives install-r of the corrected build.
- A private server and invitation are created through the UI; the other account
  joins by invitation code and opens its voice channel.
- Both actual Android voice engines report established connections. Both rosters
  converge to the same two names and two connected participants.
- Synthetic emulator location sharing starts independently of voice. Hiding
  position preserves both voice connections.
- Disconnecting one participant removes its voice panel, preserves server
  membership, and changes the other participant's roster to one connected member.
- A direct friend call rings, is accepted, and establishes connections on both sides.
  Friendship and the one-use invitation used for joining were prepared through
  the API; UI friendship request/acceptance was not exercised.
- With the callee app in the background, Android displays an incoming-call
  notification. Accept opens the app and establishes voice. The process remained
  alive; this does not prove delivery after process death or OEM suspension.

Host VPN DNS was unusable inside the emulators. Cold-starting the owned emulators
with the host Wi-Fi DNS through the SDK's dns-server flag restored name resolution.
No host VPN/DNS/proxy settings were changed. This was an environment issue.

## Open defect exposed by the UI test

When the caller disconnects an established direct call, the callee's panel remains
with “Voice connection failed. Try again.” instead of a normal end. It persists
after settling. Explicit Disconnect still dismisses the panel. Do not count remote
call termination as accepted. AndroidVoiceSdkSession currently maps every SDK
Disconnected event to EngineFailed; VoiceControlOwner also maps heartbeat Ended
to Failed. These are investigation leads, not a proven complete fix. A genuine
transport failure must remain distinguishable from a confirmed terminal call.

## Verification and limits

Fresh cleaned, rerun common suite: 3458 tests in 342 suites, zero failures/errors/
skips; exact XML counts and freshness checked. Release, releaseX86 and SQLDelight
migration verification passed (115 tasks: 34 executed, 81 up-to-date).

Immutable ARM phone artifact: `volty-nearby-c941j7-release.apk`, 69,654,142 bytes,
SHA256 `ff2eeaceb99e70b603e65527f6c974a2760001aa1917a5bbfd4cc9f1db6944ab`.
Version 0.7.16/code39, package ru.sodovaya.volty, target36. APK v2 signature matches
the existing signer. Every archive entry was scanned; the supplied service-account
private key is absent in PEM, escaped PEM and DER forms.

Screenshots, UI dumps and command logs are retained in the ignored Stage188 ledger
directory. Emulator connection and roster evidence does not establish physical
two-way audibility, Xiaomi behavior or phone-microphone/headphone-output routing.
Those checks remain deferred. Nearby is not fully accepted.
