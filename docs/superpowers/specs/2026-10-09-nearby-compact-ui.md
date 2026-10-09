# Nearby compact UI

User approved the three-screen visual preview on 2026-10-09 after actual emulator
acceptance exposed a sparse, repetitive layout. This is a presentation change to
the existing flows; the root account, voice and sharing owners retain authority.

- One Nearby heading, two sections (servers/friends), profile through an initial
  avatar. Logout stays in the profile rather than dominating every screen.
- My servers: compact rows with initials, earned visibility, catalog/create
  actions and invitation-code entry. Catalog retains explicit search/refresh.
- Server: title and voice channels first. Existing refresh/invitation/channel/
  departure/delete actions move to a dropdown; original enable predicates and
  target-bound destructive confirmation remain. Expanded widths retain two panes.
- Friends: compact avatar/name/call rows, incoming requests above accepted friends,
  sent and blocked states preserved. Search and all loading/error states remain.
- Root conversation dock: rounded, compact title/connection/roster summary, mute,
  expand and visible Disconnect. Active-position Hide stays visible without
  expansion. Details preserve independent audio input/output, participant names,
  GPS admission, cancellation and cleanup controls.

No invented online status or server/channel participant count. An established SDK
transport is described as a connection, not proof of audibility. Navigation and
screen disposal never stop a root conversation. The existing open remote-call
completion defect is separate from this visual change.

Visual verification uses the real Android emulators in both locales/densities,
with synthetic accounts, private server and actual voice sessions. Common unit
tests verify retained logic, not Compose rendering. Phone/headset and Xiaomi
acceptance remain deferred.

## Verified implementation — 2026-10-09

Signed 0.7.17/code40 runs on both emulators. Actual UI checks cover private server
creation, owner dropdown and cancellation of target-bound deletion confirmation,
invitation-code dialog, catalog Back to memberships, incoming friendship acceptance,
friend-list refresh, avatar/profile/logout and retained voice across navigation.
The private invitation membership for the second account was prepared through the
API; this round does not claim UI invitation submission acceptance.

Both native voice engines establish connections and converge to roster2/2. The
compact dock exposes Disconnect; details expose independent microphone/output
controls and both participant names. Synthetic location sharing starts through the
UI, Hide remains visible after collapsing details, and Hide preserves voice2/2.
The original common/runtime behavior is retained, including the open remote
direct-call completion defect documented in the emulator acceptance report.

Actual screenshots were inspected in Russian dark theme and English light theme,
at normal phone widths and a temporary 320dp viewport. A temporary approximately
785dp viewport shows server list/details side by side. SDK wm-size override was
reset afterward; native voice remained live through Activity configuration changes.
Tests used two synthetic accounts and a private server, all removed afterward.

Fresh cleaned/rerun suite: 3458 tests in 342 XML suites, zero failures/errors/skips,
exact counts/freshness asserted. Release, releaseX86 and SQLDelight migration
verification completed in 3m39s with all143 tasks executed. Source remained frozen
through this final run and subsequent emulator checks. Narrow review found a
missing catalog return route; it was corrected and actually exercised before
acceptance. No Compose-unit-test or physical audibility claim is made.

Immutable local ARM artifact `volty-nearby-c941k1-release.apk`: 69,654,142 bytes,
SHA256 `b15ddbe46a39f743984bffc5f5a8896ca80e2b84a8ca842902797dd8ccdaf874`.
APK package/version metadata and v2 signature/existing signer verified. Every
archive entry scanned: supplied service-account private key absent in PEM,
JSON-escaped PEM and DER forms. Screenshot/dump/command evidence remains in the
ignored Stage189 ledger directory. This supersedes 0.7.16 as the UI candidate,
without asserting whole Nearby or Xiaomi/headphone acceptance.
