# LiveKit Nearby production slice

## Goal

One deployment command must bring up the Volty social backend, PostgreSQL,
LiveKit audio SFU, and TLS routing. The Android app must be able to obtain a
short-lived group-scoped LiveKit token from Volty, request microphone
permission only after the user taps Join, publish microphone audio, subscribe
to other riders, mute/unmute, and leave cleanly.

## Decisions

- Use self-hosted LiveKit for the voice media plane.
- Use `volty.sodove.ru` for REST/WebSocket social APIs and
  `voice.sodove.ru` for LiveKit signaling/media discovery.
- Keep one Compose stack: `app`, `db`, and `livekit`; an existing host nginx
  terminates TLS and reverse-proxies the API and LiveKit signaling.
- Do not add Redis. This is a deliberately single-node deployment; scaling
  LiveKit to multiple nodes is a later infrastructure decision.
- Keep the LiveKit API secret only in the backend/container environment. The
  Android app receives only a short-lived room token.
- Room names are opaque to users and deterministic per group; participant
  identity is the existing opaque Volty user id, never a BLE address.
- Voice is group-member-only. Joining a voice room does not start location or
  telemetry sharing.
- Open microphone is the default after an explicit Join confirmation. There
  is no push-to-talk, recording, or audio over the telemetry WebSocket.

## Deployment contract

Required `.env` values:

```text
POSTGRES_PASSWORD=<random>
VOLTY_JWT_SECRET=<random, at least 64 characters>
VOLTY_PUBLIC_IP=<public IPv4 of the VPS>
LIVEKIT_API_KEY=<random key id>
LIVEKIT_API_SECRET=<random secret>
```

The public DNS records `volty.sodove.ru` and `voice.sodove.ru` must point to
the VPS. The firewall must allow `80/tcp`, `443/tcp`, `7881/tcp`, and the
configured LiveKit UDP media range. The deployment command is:

```bash
docker compose up -d --build
```

## API contract

`GET /v1/voice/provider` returns:

```json
{"available":true,"provider":"livekit","serverUrl":"wss://voice.sodove.ru"}
```

`POST /v1/groups/{groupId}/voice/join` requires a Volty Bearer token and group
membership. It returns:

```json
{
  "provider":"livekit",
  "serverUrl":"wss://voice.sodove.ru",
  "roomId":"<opaque-room-id>",
  "participantToken":"<short-lived-livekit-jwt>",
  "expiresAtEpochMillis":0
}
```

The LiveKit token has `roomJoin`, the exact group room, `canPublish`,
`canPublishSources:["microphone"]`, and `canSubscribe`. Its identity is the
Volty user id and its display name is the Volty profile name. The TTL is
bounded by a backend configuration value and is short enough for self-hosted
token revocation semantics.

## Android contract

`VoiceRoomRepository` remains the UI-facing state machine. A concrete
`LiveKitVoiceRoomRepository` obtains credentials through the authenticated
`SocialRepository`, connects an Android LiveKit room, enables the microphone,
and mirrors participant/speaking events into `VoiceRoomState.Joined`.

The repository exposes `android.permission.RECORD_AUDIO` as a permission seam;
the Nearby screen requests it only on the confirmed Join action. Denial leaves
the user in a visible failed state and does not create a room connection.

## Non-goals

- No background microphone service.
- No video, screen sharing, recording, SIP, or push-to-talk.
- No Redis, route history, BLE identifiers, or telemetry transfer through the
  LiveKit room.
- No claim that runtime media connectivity is verified on this Windows host;
  it requires a VPS with public UDP and two real Android devices.

## Verification

- Backend contract tests cover provider availability, group ACL, token claims,
  short TTL, and disabled-provider behavior.
- Common tests cover voice repository connect/mute/leave/failure and permission
  denial using fake social and engine boundaries.
- The full Android test suite, migration verification, release build, backend
  tests/installDist, and Docker Compose config all run before handoff.
