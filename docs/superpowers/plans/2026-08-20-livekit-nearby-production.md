# LiveKit Nearby Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make one Docker Compose deployment provide working Volty Nearby, live participant state, and a self-hosted LiveKit voice room, with the Android app wired to it.

**Architecture:** Volty remains the identity/ACL/token issuer. LiveKit is a separate media plane on `voice.sodove.ru`; the backend mints a short-lived group-scoped LiveKit JWT and never exposes the API secret. The Android app uses a provider-neutral voice repository backed by an Android LiveKit engine and requests microphone permission only after Join.

**Tech Stack:** Ktor/JVM, PostgreSQL, Docker Compose, host nginx reverse proxy, LiveKit Server, LiveKit Kotlin server SDK, LiveKit Android SDK, Kotlin Multiplatform, Compose Multiplatform, Koin, coroutines, kotlin.test + Turbine.

**Spec:** `docs/superpowers/specs/2026-08-20-livekit-nearby-production-design.md`

## Global Constraints

- Preserve existing dirty work; do not reset, checkout, commit, or push.
- Keep `volty.sodove.ru` as the REST host; do not use `api.sodove.ru`.
- Do not add Redis; the LiveKit deployment is single-node.
- Never serialize BLE addresses, vehicle ids, route history, or raw controller data into voice payloads.
- Keep location sharing separate and opt-in; joining voice must not start it.
- No PTT, recording, video, background microphone service, or audio over the telemetry WebSocket.
- Russian UI strings go in both `values/` and `values-ru/`.
- `minSdk 26`; Compose UI behaviour belongs in components/pure boundaries, not fake Compose tests.
- Every production behaviour change follows failing-test-first TDD.

---

### Task 1: Volty voice API and LiveKit deployment

**Files:**
- Modify: `backend/build.gradle.kts`, `backend/src/main/kotlin/ru/sodovaya/volty/backend/Database.kt`, `Model.kt`, `Application.kt`.
- Test: `backend/src/test/kotlin/ru/sodovaya/volty/backend/VoiceContractTest.kt`.
- Modify: `.env.example`, `docker-compose.yml`, `nginx/volty.conf.example`, `backend/API.md`, `backend/README.md`.
- Create: `livekit.yaml`.

**Interfaces:**
- `AppConfig` consumes `VOLTY_VOICE_PROVIDER`, `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `VOLTY_VOICE_TOKEN_TTL_SECONDS`, and `VOLTY_PUBLIC_IP`.
- `POST /v1/groups/{groupId}/voice/join` produces `VoiceJoinResponse` with `provider`, `serverUrl`, `roomId`, `participantToken`, and `expiresAtEpochMillis`.
- LiveKit receives an opaque room name, Volty user identity, display name, and microphone-only publish grant.

- [ ] Write failing contract tests for disabled provider, non-member rejection, and valid LiveKit token claims/TTL.
- [ ] Run `.\gradlew.bat -c backend\settings.gradle.kts test --tests '*VoiceContractTest'` and verify the new tests fail because the endpoint/provider is not implemented.
- [ ] Add the official `io.livekit:livekit-server:0.15.0` dependency and use its `AccessToken`, `RoomJoin`, `RoomName`, and microphone grant APIs rather than hand-building JWT claims.
- [ ] Implement config validation: provider is available only when it is `livekit` and all LiveKit credentials are present; fail startup with a clear error for a production `livekit` configuration missing a secret.
- [ ] Implement group membership checks and issue a bounded token with room join, microphone publish source, and subscribe grants. Keep the room name derived from the opaque group id and keep the API secret server-side.
- [ ] Implement `GET /v1/voice/provider` and `POST /v1/groups/{groupId}/voice/join`; leave `voice/leave` as idempotent cleanup.
- [ ] Add `livekit/livekit-server:v1.13.5` to Compose with a pinned config, public-IP discovery, UDP media range, and embedded TURN enabled. Do not add Redis.
- [x] Add the host-nginx TLS/WebSocket reverse-proxy example for
  `voice.sodove.ru` signaling and document firewall/DNS requirements and
  one-command startup.
- [ ] Run the backend voice tests, all backend tests, and `installDist`.

### Task 2: Android LiveKit voice engine

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/social/SocialContracts.kt`, `VoiceRoomState.kt`, `SocialModels.kt`, `DefaultSocialRepository.kt`, `HttpSocialTransport.kt`, `AppModule.kt`.
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/social/VoiceRoomEngine.kt`, `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/social/LiveKitVoiceRoomRepository.kt`.
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/social/AndroidLiveKitVoiceRoomEngine.kt`.
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt`, `composeApp/src/androidMain/AndroidManifest.xml`, `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`.
- Test: common social/voice tests and `HttpSocialTransportTest.kt`.

**Interfaces:**
- `SocialTransport.joinVoice(accessToken, groupId)` produces `VoiceRoomCredentials(provider, serverUrl, roomId, participantToken, expiresAtEpochMillis)`.
- `VoiceRoomEngine.connect(serverUrl, participantToken)`, `disconnect()`, `setMuted(Boolean)`, and `participants: StateFlow<List<VoiceParticipant>>` are Android media operations behind a common interface.
- `LiveKitVoiceRoomRepository` consumes `SocialRepository` + `VoiceRoomEngine` and produces the existing `VoiceRoomState` transitions.

- [ ] Write failing common tests for successful join/open mic, mute/unmute, leave cleanup, provider failure, and engine connection failure.
- [ ] Run the focused common tests and verify they fail because the concrete repository/engine is absent.
- [ ] Add the official LiveKit Android SDK version `2.25.3` and `RECORD_AUDIO`/`MODIFY_AUDIO_SETTINGS` manifest permissions.
- [ ] Extend the social transport/repository with authenticated voice join and idempotent leave endpoints, preserving the existing refresh-on-401 path.
- [ ] Implement `LiveKitVoiceRoomRepository` with `Available -> Joining -> Joined`, explicit error state, participant-flow collection, disconnect on leave/destroy/group switch, and no PTT API.
- [ ] Implement the Android engine with `Room.connect`, microphone-only local publication, remote audio subscription, mute control, and participant/speaking event mapping. Do not log tokens.
- [ ] Bind the real repository/engine in Koin; keep the unavailable implementation only for tests or an explicitly disabled provider.
- [ ] Add the microphone permission launcher to Nearby. Request it only when Join is tapped; denial must not call the engine.
- [ ] Update Russian and fallback strings for connected voice, permission denial, and connection failure.
- [ ] Run focused tests and the full Android unit suite.

### Task 3: Deployment verification and handoff

**Files:**
- Modify: `backend/API.md`, `backend/README.md`, `docs/superpowers/specs/2026-08-20-nearby-social-platform-design.md`, `docs/superpowers/plans/2026-08-20-nearby-social-platform.md`.
- Update: `.superpowers/sdd/2026-08-20-livekit-nearby-production/progress.md`.

- [ ] Run `docker compose --env-file .env.example config` if Docker is installed; otherwise record that limitation and manually inspect all interpolations.
- [ ] Run `.\gradlew.bat :composeApp\testDebugUnitTest --rerun-tasks` and assert the exact XML count with zero failures/errors/skips.
- [ ] Run `.\gradlew.bat :composeApp\verifyCommonMainVoltyDatabaseMigration --rerun-tasks`.
- [ ] Run `.\gradlew.bat :composeApp\assembleRelease --rerun-tasks`, record APK SHA-256, and run `git diff --check`.
- [ ] Verify no secret is committed, no API host is changed, and no unearned telemetry/location is introduced.
- [ ] Document the exact VPS procedure: DNS, firewall, `.env`, `docker compose up -d --build`, health checks, and Android app endpoint.
