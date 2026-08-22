# Nearby social platform: design

## Context

Volty already has a Vescape-style ride HUD, but it has no earned location
source, map layer, account identity, server transport, or social navigation.
The existing BLE/vehicle experience must remain usable without an account.
Nearby is an opt-in social surface for riders who want friends, ride groups,
live participant markers, shared telemetry, and voice.

The repository now contains the first Volty backend slice under `backend/`.
The public contract is served at `https://volty.sodove.ru/v1`; PostgreSQL is
the durable store and the existing host nginx terminates TLS. The client still keeps a provider-
neutral transport boundary so the API can evolve without leaking server
details into the UI.

## Goals

- Keep BLE monitoring and local dashboards available as a guest.
- Require normal registration/login only when the user opens Nearby or joins a
  social action. Use email + password, email verification, password reset, and
  logout/revocation. Keep credentials out of SQLDelight/DataStore.
- Use a server-issued opaque user id. It must never be a BLE address, vehicle
  id, or display name.
- Support friends and invite-only ride groups. A group has a current live
  snapshot, not a route-history product.
- Put participants on the map below the Vescape telemetry HUD when a map
  provider is configured. The common layer only consumes earned marker data;
  it must not fabricate coordinates.
- Make sharing one clear consent decision. `LOCATION` shares position only,
  `RIDE` shares position plus basic ride state, and `FULL` shares every metric
  the local producer has actually earned, with capability/known flags.
- Support a group voice room with open microphone by default and an explicit
  mute control. No push-to-talk, no recording, no audio tunneled through the
  state WebSocket. The media provider is a WebRTC/SFU boundary.
- Expire live location, telemetry, and presence server-side. Revoke sharing
  immediately. Do not retain route history or raw BLE identifiers by default.

## Non-goals for this repository

- No global stranger radar, phone contacts, or automatic friend discovery.
- No route-history product or BLE identifiers in the backend schema. The
  backend itself is in this workspace and owns PostgreSQL migrations.
- No custom SFU implementation or audio codec. The client exposes the media
  boundary and state model; a provider adapter can be supplied by the app
  owner/server deployment.
- No map tile scraping or fake canvas coordinates. A missing map provider is an
  honest unavailable state.
- No background location permission as a prerequisite for Nearby. Foreground
  live sharing starts from a visible social screen after consent; a later
  Android service can be added when the backend/product requires background
  tracking.

## Domain model

Common code owns serializable, provider-neutral models:

- `SocialSession`: `LoggedOut`, `Authenticating`, `Authenticated(userId,
  displayName, accessTokenState)`.
- `FriendSummary`: server user id, display name, friendship state.
- `RideGroup`: id, name, owner id, invite metadata, member summaries.
- `ParticipantSnapshot`: user id, display name, presence, location, accuracy,
  timestamp, stale-after timestamp, and optional `SharedTelemetry`.
- `SharedTelemetry`: schema version, share profile, and nullable metric fields
  carrying a `known` bit/capability rather than sentinel numbers.
- `VoiceRoomState`: `Unavailable`, `Available`, `Joining`, `Joined(muted)`,
  `Failed`; mute is an explicit state transition.

The local vehicle id and BLE address are never serialised into social payloads.
`FULL` is still bounded by the metrics the producers earned: unknown values are
omitted/marked unknown, never replaced with zero or a fabricated default.

## Boundaries

```text
NearbyComponent
  -> SocialRepository
       -> SocialTransport (HTTPS REST + authenticated WebSocket)
  -> LocationSharePolicy + LocationProvider
  -> TelemetryShareMapper
  -> VoiceRoomRepository
       -> VoiceTransport (WebRTC/SFU signalling/media adapter)
  -> MapRenderer (Android provider adapter, optional)
```

`SocialRepository` owns session, friends, groups, live snapshot, and share
state. `SocialTransport` owns HTTP/WebSocket framing and reconnection. The
transport must expose server errors as typed failures, not silently empty
lists.

`LocationSharePolicy` is pure: sharing is off by default, requires an
authenticated group audience, has a TTL, and produces `stale` after the TTL
without deleting the last server snapshot prematurely. Revocation emits an
explicit stop before local state is cleared.

`TelemetryShareMapper` accepts the existing earned telemetry models and an
explicit profile. It never reads the BLE layer directly and never sends raw
addresses. The `FULL` profile is one user-facing consent, not a page of ten
independent toggles.

`MapRenderer` is an Android-only host. Common code supplies marker view data
and hit-test actions. The Vescape HUD receives the renderer as a background
layer; if no map adapter is installed, the HUD remains functional and shows a
short unavailable state instead of drawing invented positions.

`VoiceRoomRepository` has no PTT API. It exposes join/leave/mute and the
provider supplies the active speaker/participant state. Microphone permission
is requested only on a confirmed join action. No recording is persisted.

## Registration and privacy UX

- Ride/Battery/Settings stay usable logged out.
- Opening Nearby presents login/register, with a brief explanation that it is
  required only for social features.
- Registration collects email, password, and display name. The server owns
  password hashing, verification, reset, session revocation, and account
  deletion. Access/refresh credentials are stored behind an Android Keystore
  adapter, never in SQLDelight.
- Joining a group does not start sharing. The user chooses `Location`, `Ride`,
  or `Full telemetry`, sets a finite duration, and sees the audience before
  enabling it.
- The group view shows `sharing`, `stale`, `muted`, and `offline` states. A
  member tap can show only the fields allowed by that member's profile.
- Voice is a separate join action and has a persistent mute button while
  joined. Leaving the group leaves the voice room and revokes media tracks.

## Server contract expected by the client

REST endpoints are conventional and versioned (`/v1`): auth register/login/
verify/reset/logout, profile, friends, groups, invites, and sharing settings.
The live channel is an authenticated WebSocket carrying group snapshot,
presence, location, telemetry, and explicit revoke/expiry events. Voice uses a
separate WebRTC/SFU signalling endpoint and short-lived room credentials.

PostgreSQL is the source of truth. Redis is deliberately not part of the
contract. A single instance can keep an in-memory WebSocket registry; if the
server later becomes multi-instance, use the existing server's chosen durable
notification/broker mechanism rather than making the mobile client depend on
Redis.

The server must enforce group ACLs, invite expiry, rate limits, TLS, token
rotation/revocation, telemetry TTL, and no location history by default. The
client treats those guarantees as server responsibilities and still renders
staleness honestly.

## Android integration

The first slice uses foreground-visible location sharing and a provider-neutral
`MapHost`. Android location/microphone permissions are requested only from the
Nearby UI. BLE `MonitoringService` is unchanged and must not be reused for
social location or voice. A future background implementation must use separate
foreground-service types and explicit consent.

## Testing strategy

- Pure common tests for share policy, TTL/stale transitions, telemetry profile
  mapping, auth gating, marker mapping, and voice mute transitions.
- Repository/component tests use fake transport and Turbine; assert issued
  requests and published snapshots, not wall-clock timing alone.
- Migration verification remains mandatory if local social cache tables are
  added. The current schema is version 12, so any new migration is `12.sqm`
  with a freshly generated `13.db` snapshot.
- Compose/map rendering and actual Android permission/media behaviour remain
  device-only; do not dress them up as common unit tests.

## Open integration seam

The client now has a concrete `HttpSocialTransport` pointed at
`https://volty.sodove.ru/v1`, and the repository contains the matching Ktor
backend plus Docker Compose deployment files. A commercial map provider and a
WebRTC/SFU vendor are still deliberately open seams: until those are
configured the app shows truthful unavailable states. Production deployment
also requires DNS for `volty.sodove.ru` and real secrets in `.env`.
