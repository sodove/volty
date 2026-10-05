# Nearby UI assembly implementation plan

> Execute inline with superpowers:executing-plans. No additional Astra invocation or quota-exhausted subagents.

**Goal:** Replace the legacy group UI with servers, channels, friend calls and a root-owned conversation panel.

**Architecture:** Screen components own forms, read projections and membership commands. Existing account-bound HTTP sessions own authentication, and existing root voice/sharing startups own conversations. Composables render projections and dispatch commands; leaving Nearby never closes the account or conversation.

**Tech stack:** Kotlin, coroutines, Decompose, Compose Multiplatform; existing Ktor v2 adapters.

**Spec:** `docs/superpowers/specs/2026-10-03-nearby-voice-spaces-design.md`, especially screen scenarios and sharing/full disconnect.

## Constraints

- Preserve accounts/friends, never migrate or relabel legacy groups as servers.
- Do not change BLE/controller/battery behavior.
- Keep server membership, channel voice roster and map positions as different projections. Membership/channel existence does not prove anyone is in voice.
- Closing a screen cannot call voice stop, sharing revoke or authentication retirement.
- Sharing needs its own visible-activity location-foreground acknowledgement; joining voice never starts GPS.
- Phone microphone and headset output remain separate choices; SDK connection does not prove audibility.
- Russian UI strings in both `values` and `values-ru`. No Compose visual-test claims from JVM tests.
- No push/deploy/stock-SFU fallback; default deployment still legacy until coordinated cutover.

## Task 1 — server workspace controller

- [x] Add `presentation/nearby/CommunityWorkspace.kt`: screen-owned browser and single-flight create/join/confirmed leave/delete commands borrowing exact account ports. Require live screen/account Jobs, reject stale results and mismatched receipt identities, retain operation IDs for uncertain retries within this workspace lifetime.
- [x] Add finite `CommunityWorkspaceTest` fixtures: actual issued create/join/removal, held completion after close/account retirement, double-submit, changed selection before confirmation, owner successor-required result, exact original retry IDs and incoherent foreign receipts.
- [x] Add the workspace factory to `NearbyCommunityAccount`, without making the screen close its borrowed API/account browser/invitation owner.
- [x] Run focused tests, account/browser regression tests, full fresh Android suite before commit (3115 exact tests, zero failures/errors/skips).

## Task 2 — server screen and shell

- [x] Build reusable `ServerWorkspacePresenter`, `ServerWorkspaceScreen` and forms: owned projection, private-by-default creation, catalog join, channels, target-bound removal dialog; 7 finite presenter tests plus existing workspace/account/browser suite. Full Android 3122 exact zero bad. This is compiled UI code, not a visual/device check or root activation.
- [ ] Before activating owner departure UI, provide an authoritative paginated member directory for successor selection. Current community details contain owner/channels but no member list. Never ask users to copy a user UUID or infer server membership from friendships/voice roster.

- [x] Introduce `DefaultVoiceSpacesNearbyComponent` with login/registration, verified profile display and friends from the existing social repository, plus v2 server workspace from the exact `SocialAuthenticatedAccount`. Twelve finite component tests exercise actual repository commands and issued transport requests; retirement clears projections before unrelated held account children complete. Profile editing and password recovery still need new-shell controls before release.
- [x] Bind the server presenter to the actual repository's authenticated-account flow and actual community factory. Same-user relogin replaces the consumer, stale callbacks retain the original authority, screen closure preserves authentication, failed construction has a bounded explicit retry. Five actual-repository/HTTP tests; full Android 3127 exact zero bad. Decompose shell and Root activation remain open.
- [x] Compile the reusable main view: `Серверы` and `Друзья`, searchable catalog and people, private/public creation, confirmed friend-request actions and accepted-friend call callback. Forms/lists scroll and auth fields are bounded; all seven shell strings exist in both locales. Calls are only Root admission callbacks at this milestone. No Root activation or visual/device proof yet.
- [ ] Server view: title and channels first, creation/invites/leave/delete in secondary actions. Opening or joining a server does not start voice. No fabricated per-channel participant counts.
- [ ] Owner leave reports successor selection or explicit deletion; destructive actions require target-bound confirmation. No automatic delete after a leave rejection.
- [ ] Compose compact: one pane and back navigation. Medium/expanded: server list and selected server side by side. Forms/dialogs remain bounded and scrollable.

## Task 3 — root conversation panel and activation

- [x] Add original-operation disconnect admission to `VoiceConversationStartup`: stale/repeated commands cannot withdraw a replacement intent; works during held acquisition/preferences and from a cancelled UI caller. Four finite tests, scoped 103 and full Android 3143 exact zero bad. True means intent withdrawal, not native/remote cleanup acknowledgement. UI panel/Root activation remain open.
- [ ] Add root-owned projection/commands around existing `VoiceConversationStartup` and `ChannelSharingStartup`; identity-bound actions cannot affect a later conversation.
  - Voice request owner borrows the actual social repository/application Job, not an Activity/Decompose Job. Pending permission/switch decisions retain the exact account and original conversation operation; queued/late results cannot replace a newer request.
  - Activity launch boundary owns only its callback references and availability. Closing that boundary withdraws future foreground launches and releases Activity references, while an already-earned conversation stays with the process owner.
  - Sharing switch guard is mandatory: original sharing is withdrawn before stopping voice, and cleanup admission is checked before a replacement starts. Permission refusal or an unavailable visible boundary preserves the current allowed conversation/trip.
  - Register only an actual sharing preparation belonging to the current account and original voice tag. Keep hidden preparations until producer/native/child cleanup finishes. Failed original remote cleanup uses the existing durable revoke recovery, with live unrelated preparations protected; no reconnect shortcut or permanently cached failure.
  - Root friend calls use typed outgoing targets and a generated call identity distinct from the permission operation. Incoming acceptance rechecks actual server status and original relative ring credit before withdrawing the existing conversation; stale/expired/unverifiable rings preserve it. Render only the exact call roster owned by the current voice operation.
- [ ] Joining a channel requests microphone permission and visible foreground launch once; starting a trip separately requests location permission/foreground launch. Denial keeps existing allowed state.
- [ ] Panel above root navigation: target, actual connection phase, mute and visible `Отключиться`; expanded view includes deafen, independent input/output and `Не показывать позицию`.
- [ ] Channel switch/accept call during a trip confirms ending original trip, revokes original sharing and stops original voice before new admission. Membership remains.
- [ ] Switch real Root child/screen only after the functional paths exist. Remove old Nearby voice/sharing execution and legacy map consumers together; don't render v2 controls over the old group runtime.

## Task 4 — calls, map and device gate

- [ ] Accepted-friend call action; incoming accept/reject/cancel/expired projections and real Android notification/MainActivity bridge. Push is a hint, server state remains authority.
- [ ] Channel map uses only current exact sharing audience. No friend-call carryover and no cached position counted as voice presence.
- [ ] Assemble signed APK, verify compact/expanded visually and test two actual phones: independent microphone/output, Xiaomi bilateral audio, background/lock, disconnect/rejoin, network transitions and roster agreement.

## Review focus

Late result after account/screen disposal; uncertain create/removal retry; channel switch while original capture is pending; destructive confirmation targeting a different server; permissions denied after leaving the visible activity. JVM tests cover command/state behavior, while actual native rendering/audio/foreground service behavior requires devices.
