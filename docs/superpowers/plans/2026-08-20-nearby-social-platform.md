# Nearby social platform implementation plan

Implement optional registration, friends/groups, live map participants in the
Vescape surface, one-profile telemetry sharing, and open-mic group voice
boundaries in the current `feat/vehicle-composer` worktree. The first backend
slice is included under `backend/` and is deployed as `volty.sodove.ru` via
Docker Compose; map and voice remain honest provider seams.

## Global Constraints

- Preserve all existing dirty work. Do not reset, checkout, or commit.
- Do not change BLE protocol writes. In particular, never write to Begode or
  Veteran/Leaperkim FFE1.
- The local dashboard and BLE monitoring must work without registration.
- Registration is required only when entering Nearby/social actions.
- No Redis dependency. Live state is REST/WebSocket; voice is a separate
  WebRTC/SFU boundary with mute, never push-to-talk.
- Never fabricate coordinates, telemetry, speed, temperature, voltage, or
  battery values. Unknown remains unknown.
- Location is off by default, group-scoped, TTL-bound, revocable, and not a
  route-history feature.
- Russian UI strings must be added to both `values/` and `values-ru/`.
- Keep Compose decisions in components/pure mappers so common tests cover
  behaviour; do not write fake Compose tests.
- Current SQLDelight schema is version 12. If tables are added, create
  `12.sqm` and regenerate `13.db`, then run migration verification.
- Do not add a map SDK or WebRTC implementation until a concrete provider is
  available in the workspace. Implement the provider-neutral adapter seams
  and an honest unavailable state.

## Task 1 — Social domain, sharing policy, transport and voice contracts

**Files:** new common `domain/social/*` models/interfaces/policies and focused
`commonTest` coverage.

- [x] Add serializable session, friend, group, participant, location, telemetry,
  and voice state models.
- [x] Add `SocialTransport` and `SocialRepository` contracts for auth, profile,
  friends, groups, live state, share start/stop, and typed failures.
- [x] Add pure `LocationSharePolicy`, `TelemetryShareMapper`, and voice mute
  transition logic.
- [x] Cover logged-out gating, TTL/stale/revoke behaviour, FULL telemetry
  preserving known flags, no BLE identifiers, and open-mic mute transitions.
- [x] Run the focused common tests and inspect the diff for accidental product
  assumptions.

## Task 2 — Local social cache and credential boundary

**Files:** SQLDelight social cache schema/migration, repository adapter,
Android Keystore credential boundary, DI.

- [x] Add secure social credential storage. Durable social profile/group cache is
  deferred; live data remains ephemeral and no route history is stored.
- [x] Do not persist live location/telemetry history or bearer tokens in the DB.
- [x] Add `CredentialStore` common interface and Android Keystore-backed
  implementation; common fake for tests.
- [x] No SQLDelight migration was needed: credentials are not DB data. Add
  `12.sqm`/`13.db` only when a real offline social cache is introduced.
- [x] Bind the concrete HTTPS/WebSocket transport to `volty.sodove.ru`; keep
  the unavailable implementation for tests and provider fallbacks.

## Task 3 — Registration gate and Nearby navigation

**Files:** root navigation, Nearby component/screen/auth form, resources.

- [x] Add a Nearby root destination/tab without breaking Ride/Battery/Settings.
- [x] Opening Nearby while logged out shows login/register instead of affecting
  the local vehicle experience.
- [x] Implement email/password/display-name form states, validation, typed
  loading/error/success states, logout, and account deletion affordances behind
  `SocialRepository`.
- [x] Add friends list, invite-only group list/create/join-by-code shell, and
  explicit sharing controls with audience + TTL preview.
- [x] Add Russian and fallback strings, accessibility labels, and honest empty
  states for an unconfigured transport.

## Task 4 — Live participants and Vescape map slot

**Files:** common participant mapper/marker models, Vescape dashboard layering,
Android `MapHost` boundary and unavailable implementation.

- [x] Map server snapshots to marker view data with online/stale/offline and
  profile-limited telemetry details.
- [x] Add a background map host slot beneath the existing Vescape HUD; do not
  change earned telemetry rendering.
- [x] Add a provider-neutral adapter seam and a no-provider state. Never
  draw a marker without a server location and timestamp within TTL.
- [x] Add marker mapping tests, including stale expiry, missing location, and
  FULL telemetry with unknown metrics.

## Task 5 — Group voice room boundary

**Files:** common voice repository/component/screen state, Android permission
  seam, resources.

- [x] Add join/leave/open-mic/mute state and active-speaker display to the group
  shell. No PTT controls.
- [x] Declare microphone permission for the future adapter; request it only
  after the user confirms joining once a provider is installed.
- [x] Keep media transport separate from the live telemetry WebSocket and leave
  room/revoke tracks on group exit.
- [x] Provide a no-provider/unavailable state until the external WebRTC/SFU
  adapter is supplied; do not pretend that a UI toggle is real audio.
- [x] Test state transitions and accidental group-exit cleanup.

## Task 6 — Integration review and verification

- [x] Add/adjust Koin bindings and Android manifest permissions only for the
  implemented foreground-visible seams; do not add background location by
  default.
- [x] Run `git diff --check`.
- [x] Run `.\gradlew.bat :composeApp\testDebugUnitTest --rerun-tasks` with fresh results.
- [x] Run `.\gradlew.bat :composeApp\verifyCommonMainVoltyDatabaseMigration`.
- [x] Run `.\gradlew.bat :composeApp\assembleRelease` if the slice compiles.
- [x] Perform a final review against this plan and record any provider-dependent
  seam explicitly; do not claim backend/map/voice delivery without adapters.

## Task 7 — Volty backend and Docker deployment

**Files:** `backend/*`, `docker-compose.yml`, `nginx/volty.conf.example`, `.env.example`.

- [x] Add Ktor/JVM REST and authenticated WebSocket API under `/v1`.
- [x] Persist accounts, rotating refresh tokens, friends, groups, sharing
  sessions, and the latest live snapshot in PostgreSQL.
- [x] Enforce group ACLs, invite expiry, share TTL/revocation, typed errors,
  rate limits, and no raw BLE identifiers or route history.
- [x] Add Docker images for the app and PostgreSQL; use the existing host nginx
  for TLS termination and reverse proxy at `volty.sodove.ru`; do not add Redis.
- [x] Add backend tests and install distribution verification. Docker runtime
  validation remains pending until Docker is available on the deployment host.
