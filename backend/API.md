# Volty Nearby API contract

Base URL: `https://volty.sodove.ru/v1`. JSON uses the same camelCase names and uppercase enum values as `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/social`. Bearer access tokens are short-lived; refresh tokens are opaque, one-time rotating credentials.

## Authentication and account

| Method | Path | Body / result |
|---|---|---|
| POST | `/auth/register` | `{email,password,displayName}` -> `SessionCredentials` |
| POST | `/auth/login` | `{email,password}` -> `SessionCredentials` |
| POST | `/auth/refresh` | `{refreshToken}` -> rotated `SessionCredentials` |
| POST | `/auth/logout` | bearer -> `{loggedOut:true}`; revokes all sessions |
| GET/POST | `/auth/verify` | query `token` or `{token}` -> `{verified:true}` |
| POST | `/auth/password-reset/request` | `{email}` -> `202`; never reveals account existence |
| POST | `/auth/password-reset` | `{token,newPassword}` -> `{reset:true}` |
| GET | `/profile` | bearer -> authenticated profile |
| PATCH | `/profile` | `{displayName}` -> authenticated profile |
| DELETE | `/account` | bearer -> `204`; revokes and soft-deletes account |

## Friends and invite-only groups

| Method | Path | Body / result |
|---|---|---|
| GET | `/friends` | bearer -> `List<FriendSummary>` |
| POST | `/friends/requests` | `{userId}` -> `201` |
| POST | `/friends/requests/{friendshipId}/respond` | `{accept}` -> result |
| GET | `/groups` | bearer -> `List<RideGroup>` |
| POST | `/groups` | `{name}` -> `RideGroup`; owner also receives `inviteCode` |
| POST | `/groups/join` | `{inviteCode}` -> joined `RideGroup` |
| DELETE | `/groups/{groupId}` | bearer -> `204` |

Groups never become public: membership is required for every group read, share, and live connection. Invite codes expire after 30 days in the MVP.

## Sharing and live snapshots

The client-compatible `POST /groups/{groupId}/sharing` accepts `{groupId,profile,ttlMillis,startedAtEpochMillis}` to start a share. The legacy-compatible `POST /sharing/start` accepts the same body. `profile` is `LOCATION`, `RIDE`, or `FULL`; TTL is positive and capped at 24 hours. Starting a new share revokes the previous share for that user/group.

`POST /groups/{groupId}/sharing/update` accepts `{capturedAtEpochMillis,location,telemetry}`. The same body is also accepted by `POST /groups/{groupId}/sharing` when `ttlMillis` is absent. Location is required and validated. `LOCATION` rejects telemetry; `RIDE` strips full metrics; `FULL` preserves only the capability/known/value fields received from the client. Values are never fabricated by the server. Only the latest update is retained.

`DELETE /groups/{groupId}/sharing` immediately removes the live row and broadcasts `{"type":"share_revoked","userId":"opaque-user-id"}`. `POST /groups/{groupId}/sharing/stop` is an equivalent explicit-stop alias.

The authenticated WebSocket is available at both `/v1/ws/groups/{groupId}` (the mobile client path) and `/v1/groups/{groupId}/live`. The first frame is `{"type":"snapshot","snapshot":{"groupId":"...","capturedAtEpochMillis":0,"participants":[]}}`. Expiry broadcasts `{"type":"share_expired","userId":"..."}`. Presence is `ONLINE`, `STALE`, or `OFFLINE`; no BLE address, vehicle id, or route history is part of any payload.

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
