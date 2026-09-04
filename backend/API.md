# Volty Nearby API contract

Base URL: `https://volty.sodove.ru/v1`. JSON uses the same camelCase names and uppercase enum values as `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/social`. Bearer access tokens are short-lived; refresh tokens are opaque, one-time rotating credentials.

## Navigation

`POST /navigation/routes` accepts `routingProfile` with one of `generic`, `motorcycle`,
`bicycle`, or `pedestrian`. The Android client selects this profile from route style and top
speed: bicycle is primary up to 30 km/h, pedestrian is a low-speed curvy fallback, motorcycle
is primary above 30 km/h, and generic is the final fallback. The legacy `profile` field is
unrelated to routing and remains ignored for compatibility.

When GraphHopper is enabled, `VOLTY_NAV_PROFILE`, `VOLTY_NAV_PROFILE_MOTORCYCLE`,
`VOLTY_NAV_PROFILE_BICYCLE`, and `VOLTY_NAV_PROFILE_PEDESTRIAN` must all point to configured
GraphHopper profiles. A missing selected mapping returns an explicit unavailable response rather
than silently substituting generic.

## Authentication and account

| Method | Path | Body / result |
|---|---|---|
| POST | `/auth/register` | `{email,password,displayName}` -> `SessionCredentials`; account is immediately usable, with no email-confirmation gate |
| POST | `/auth/login` | `{email,password}` -> `SessionCredentials` |
| POST | `/auth/refresh` | `{refreshToken}` -> rotated `SessionCredentials` |
| POST | `/auth/logout` | bearer -> `{loggedOut:true}`; revokes all sessions |
| GET/POST | `/auth/verify` | legacy compatibility endpoint; query `token` or `{token}` -> `{verified:true}`; registration/login do not depend on it |
| POST | `/auth/password-reset/request` | `{email}` -> `202`; never reveals account existence |
| POST | `/auth/password-reset` | `{token,newPassword}` -> `{reset:true}` |
| GET | `/profile` | bearer -> authenticated profile |
| PATCH | `/profile` | `{displayName}` -> authenticated profile |
| DELETE | `/account` | bearer -> `204`; revokes and soft-deletes account |

## Friends and invite-only groups

| Method | Path | Body / result |
|---|---|---|
| GET | `/friends` | bearer -> `List<FriendSummary>` |
| GET | `/users/search?q=...` | bearer -> `List<UserSearchResult>`; returns opaque `userId`, `displayName`, optional friendship id/state, never email |
| POST | `/friends/requests` | `{userId}` -> `201` |
| POST | `/friends/requests/{friendshipId}/respond` | `{accept}` -> result |
| GET | `/groups` | bearer -> `List<RideGroup>` |
| POST | `/groups` | `{name}` -> `RideGroup`; owner also receives `inviteCode` |
| POST | `/groups/join` | `{inviteCode}` -> joined `RideGroup` |
| DELETE | `/groups/{groupId}` | bearer -> `204` |

Groups never become public: membership is required for every group read, share, and live connection. Invite codes expire after 30 days in the MVP.

## Sharing and live snapshots

The client-compatible `POST /groups/{groupId}/sharing` accepts `{groupId,profile,ttlMillis,startedAtEpochMillis}` to start a share. The legacy-compatible `POST /sharing/start` accepts the same body. `profile` is `LOCATION`, `RIDE`, or `FULL`; TTL is positive and capped at 24 hours. The server clock is authoritative for the session start and expiry, so a device with an incorrect wall clock cannot make sharing fail. Starting a new share revokes the previous share for that user/group.

`POST /groups/{groupId}/sharing/update` accepts `{capturedAtEpochMillis,location,telemetry}`. The same body is also accepted by `POST /groups/{groupId}/sharing` when `ttlMillis` is absent. Location coordinates and accuracy are validated, while the server stamps the accepted update time and freshness window itself; client wall-clock timestamps are not trusted. `LOCATION` rejects telemetry; `RIDE` strips full metrics; `FULL` preserves only the capability/known/value fields received from the client. Values are never fabricated by the server. Only the latest update is retained. Authorization and the live-row write are one database transaction: the member row and active sharing session are locked before the write, so a publish concurrent with stop, leave, renewal, or expiry cannot recreate a live row after revocation.

`POST /groups/{groupId}/sharing/renew` accepts `{ttlMillis,startedAtEpochMillis}` and renews the current session without changing its profile. The server clock is authoritative for the renewed session start. Renewal rotates the active session and removes the previous live update before the next publish.

`DELETE /groups/{groupId}/sharing` immediately removes the live row and broadcasts `{"type":"share_revoked","userId":"opaque-user-id"}`. `POST /groups/{groupId}/sharing/stop` is an equivalent explicit-stop alias.

Live snapshot participants are roster-complete: `location` and `telemetry` are explicitly nullable JSON fields and are emitted as `null` when no accepted live update exists. A location/telemetry row is projected only while its member has current, unexpired sharing. Presence becomes `STALE` after 15 seconds without a live update; this is the single `LIVE_LOCATION_FRESHNESS_MILLIS` threshold used by the server and client contract, not a separate grace period.

The authenticated WebSocket is available at both `/v1/ws/groups/{groupId}` (the mobile client path) and `/v1/groups/{groupId}/live`. The first frame is `{"type":"snapshot","snapshot":{"groupId":"...","capturedAtEpochMillis":0,"participants":[]}}`. Expiry broadcasts `{"type":"share_expired","userId":"..."}`. Presence is `ONLINE`, `STALE`, or `OFFLINE`; no BLE address, vehicle id, or route history is part of any payload.

WebSocket admission checks membership before registration and revalidates it immediately after registration to close the registration/query race. If membership is revoked, the server sends `{"type":"subscription_terminated"}` and closes the WebSocket with a policy-violation close; live sessions are also removed on leave and group deletion. This is terminal: clients must stop retrying until the user selects a valid group. Transient network closes may retry with backoff and must request a fresh snapshot after reconnecting; they must not erase the last roster locally.

## Typed errors and voice boundary

Errors have HTTP status plus `{"code":"invalid_request","message":"...","requestId":"...","details":{}}`. Common codes are `unauthorized`, `forbidden`, `not_found`, `conflict`, `rate_limited`, `invalid_request`, `sharing_expired`, `refresh_reuse`, and `voice_provider_unconfigured`. `Retry-After` is set for rate limits.

`GET /voice/provider` is authenticated and reports whether the backend can really mint room credentials:

```json
{"available":true,"provider":"livekit","serverUrl":"wss://voice.sodove.ru"}
```

When voice is disabled or not fully configured it returns `available:false` with `provider:"unconfigured"` (or the configured provider name) and a message instead of inventing readiness.

`POST /groups/{groupId}/voice/join` is authenticated, requires group membership, and returns a short-lived LiveKit join payload:

```json
{
  "provider":"livekit",
  "serverUrl":"wss://voice.sodove.ru",
  "roomId":"volty-group-<groupId>",
  "participantToken":"<livekit-jwt>",
  "expiresAtEpochMillis":1735689600000
}
```

The LiveKit JWT carries the opaque Volty user id as `sub`, the Volty display name as `name`, and `video` grants for the exact room, `roomJoin`, `canPublish`, `canPublishSources:["microphone"]`, and `canSubscribe`. `/voice/leave` remains idempotent cleanup and always returns `{left:true}`.
