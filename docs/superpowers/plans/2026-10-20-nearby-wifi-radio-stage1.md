# Nearby Wi‑Fi Radio — Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An offline group voice "radio" for up to 10 riders of the same Volty server, phone-to-phone over Wi‑Fi Direct, alongside the existing LiveKit server voice.

**Architecture:** Star topology. The phone that raises the radio becomes the Wi‑Fi Direct group owner (hub) with a network name and passphrase derived from a per-server radio key the backend issues in advance. Clients send 20 ms PCM frames over UDP to the hub; the hub keeps a jitter buffer per sender and sends each client a "everyone but you" mix. All logic that can be pure (key derivation, packet codec, gate, jitter buffer, mixer, hub/client session state machines) lives behind small ports and is unit-tested; the Android layer (Wi‑Fi Direct, AudioRecord/AudioTrack, foreground service, UI) only wires them.

**Tech Stack:** Kotlin Multiplatform (Android only), Compose, Koin, Ktor client (OkHttp), DataStore, `android.net.wifi.p2p`, `javax.crypto` (AES‑GCM, HMAC‑SHA256), kotlin.test + Turbine. Backend: Ktor server + JDBC/Postgres.

**Spec:** `docs/superpowers/specs/2026-10-20-nearby-wifi-radio-design.md`

## Global Constraints

- Android only, `minSdk 26`; **radio itself requires Android 10 (API 29)+**; below that the UI shows `Рация недоступна на этой версии Android` and nothing radio-related starts.
- **No Bluetooth between phones.** Never touch BLE/BT APIs in radio code. The wheel/BMS BLE link must be unaffected.
- **Never write to FFE1** (project Global Constraint 5) — radio code has no reason to reference BLE at all.
- Wi‑Fi Direct group on **5 GHz** (`WifiP2pConfig.GROUP_OWNER_BAND_5GHZ`), falling back to auto only if group creation fails on 5 GHz.
- Audio: **16 000 Hz mono, 20 ms frames (320 samples)** everywhere. Wire payload depends on the radio mode chosen by the hub: `LOW_LATENCY` = raw PCM16 (640 bytes), jitter target 2 frames; `BALANCED` (default) = Opus 24 kbit/s, target 3 frames; `RELIABLE` = Opus 12 kbit/s with in-band FEC and expected loss 20 %, target 6 frames. Opus via `io.github.jaredmdobson:concentus:1.0.2`.
- Capacity: hub + **9** clients (10 people). The 10th client gets `FULL`.
- Only members of the same Volty server can derive the network name, passphrase and audio key.
- Mobile data must keep working while in the radio (no `bindProcessToNetwork`, no route changes).
- While on the radio, this phone's own server voice is disconnected; starting server voice stops the radio.
- UI strings in **both** `values/strings.xml` and `values-ru/strings.xml`; no Android backslash escapes (Compose Multiplatform does not process them).
- Acceptance targets (field, Task 10): mouth‑to‑ear ≤ ~0.3 s; holds across a ~100 m column on the move; battery per hour ≤ ~15 % client, ≤ ~25 % hub.
- Suite before every commit: `.\gradlew.bat :composeApp:testDebugUnitTest` (and `.\gradlew.bat :backend:test` for backend tasks). A `BUILD SUCCESSFUL in 1s` is a cache hit, not evidence — check the test count changed.
- `runTest` hazard: never start an unbounded delayed loop inside `runTest` without cancelling it; drive loops with an explicit tick function or `backgroundScope`.

## Review Focus

1. **Two members with different key epochs** (one refreshed after a removal, one did not) → they derive different names and silently never meet. Expected: the client shows `Ключ рации устарел — откройте Nearby с интернетом` when its cached key is older than the newest epoch it has seen, and a join that finds nothing for 30 s says `Рация не найдена`. Test in Task 7 (`clientJoinTimesOutAsNotFound`) and Task 3 (`staleEpochIsReported`).
2. **Hub goes silent mid‑ride** (rode off, battery died) → every client must reach `LOST` within 3 s, not hang in `CONNECTED`. Test in Task 6 (`clientMarksLostAfterThreeSilentSeconds`).
3. **A client vanishes without BYE** → hub must free its slot within 3 s so a tenth rider can join. Test in Task 6 (`hubEvictsSilentClient`).
4. **Midnight UTC during a ride** → a joiner after 00:00 must still find a radio raised before 00:00. Test in Task 2 (`joinCandidatesCoverTodayAndYesterday`).
5. **Garbage / foreign / replayed UDP packets** on the group network → dropped without crashing or being played. Test in Task 3 (`rejectsTamperedPacket`, `rejectsReplayedSequence`).

---

### Task 1: Backend — per-server radio key endpoint

**Files:**
- Create: `backend/src/main/kotlin/ru/sodovaya/volty/backend/nearby/RadioKeyEndpointPort.kt`
- Create: `backend/src/main/kotlin/ru/sodovaya/volty/backend/nearby/JdbcRadioKeyRepository.kt`
- Create: `backend/src/main/kotlin/ru/sodovaya/volty/backend/nearby/RadioKeyRoutes.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Application.kt` (add `nearbyRadioKeys` dependency next to `nearbyMembers`, install routes next to `installNearbyCommunityMembersRoutes`)
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/nearby/NearbyBackendConfiguration.kt` (read `VOLTY_RADIO_SECRET`)
- Modify: `docker-compose.yml`, `DEPLOY.md` (document `VOLTY_RADIO_SECRET`)
- Test: `backend/src/test/kotlin/ru/sodovaya/volty/backend/nearby/RadioKeyRoutesTest.kt`, `backend/src/test/kotlin/ru/sodovaya/volty/backend/nearby/JdbcRadioKeyRepositoryTest.kt`

**Interfaces:**
- Produces: `GET /v2/nearby/communities/{communityId}/radio-key` → `200 {"communityId":String,"epoch":Long,"key":String /* base64, 32 bytes */}`; `404 community_unavailable` for non-members/deleted; `503 nearby_unavailable` when secret not configured.
- Produces: `sealed interface RadioKeyResult { data class Issued(val communityId:String,val epoch:Long,val key:ByteArray); data object Denied }`, `fun interface RadioKeyEndpointPort { fun issue(actor:String, community:String): RadioKeyResult }`.

Decisions: `epoch` = count of rows in `nearby_community_outbox` for the community with `kind IN ('MEMBER_LEFT','MEMBER_REMOVED','MEMBER_BANNED')` — no migration. `key = HMAC-SHA256(secret, "volty-radio|$communityId|$epoch")`. Secret: `VOLTY_RADIO_SECRET`, base64, decoded length ≥ 32 bytes, else endpoint disabled (503) and a startup log line. Membership check identical to the members endpoint (active community, actor in `nearby_community_members`).

- [ ] **Step 1: Write failing tests**

`JdbcRadioKeyRepositoryTest` (uses `NearbyPostgresFixture` like other Jdbc tests):
- `memberGetsStableKeyAtEpochZero` — two calls return equal `epoch=0` and equal 32-byte key.
- `departureAdvancesEpochAndChangesKey` — after inserting a `MEMBER_LEFT` outbox row, `epoch=1` and key differs.
- `nonMemberIsDenied` — returns `RadioKeyResult.Denied`.
- `keysDifferAcrossCommunities`.

`RadioKeyRoutesTest` (fake port, same harness as `CommunityMembersRoutesTest`): 200 body shape with base64 decoding to 32 bytes; 404 on `Denied`; 401 without token; 503 when port is null.

- [ ] **Step 2: Run** `.\gradlew.bat :backend:test --tests "*RadioKey*"` — expect FAIL (unresolved references).
- [ ] **Step 3: Implement** the port, repository (`class JdbcRadioKeyRepository(private val source: DataSource, private val secret: ByteArray): RadioKeyEndpointPort`), routes (`fun Route.installNearbyRadioKeyRoutes(dependencies: AppDependencies)`), config and wiring.
- [ ] **Step 4: Run** `.\gradlew.bat :backend:test` — expect PASS, test count increased by 8.
- [ ] **Step 5: Commit** `feat(nearby): issue per-server radio keys`

---

### Task 2: Client — radio key fetch, cache and credential derivation

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioKey.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/nearby/RadioKeyHttpPort.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/RadioCredentials.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/AndroidRadioKeyStore.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/ru/sodovaya/volty/data/radio/RadioCredentialsTest.kt`, `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/nearby/RadioKeyHttpPortTest.kt`

**Interfaces:**
- Produces (common): `data class RadioKey(val communityId:String, val epoch:Long, val key:ByteArray)` (equals/hashCode by content); `interface RadioKeyStore { suspend fun get(actor:String, community:String):RadioKey?; suspend fun put(actor:String, key:RadioKey); suspend fun newestEpochSeen(actor:String, community:String):Long? }`.
- Produces: `class RadioKeyHttpPort(client:HttpClient, baseUrl:String, actor:String, bearer:suspend ()->String?) { suspend fun fetch(community:String): RadioKey? }` — follows `CommunityMembersHttpPort` (10 s timeout, size cap 1 KiB, strict JSON, `null` on any failure).
- Produces (android): `data class RadioCredentials(val networkName:String, val passphrase:String, val audioKey:ByteArray)`; `object RadioCredentialDerivation { fun derive(key:RadioKey, utcDay:Long):RadioCredentials; fun joinCandidates(key:RadioKey, nowMillis:Long):List<RadioCredentials> /* today, then yesterday */ }`.
- `AndroidRadioKeyStore(context)` stores keys encrypted the same way `AndroidInvitationDraftStore` does (per actor), implementing `RadioKeyStore`.

Derivation (HMAC‑SHA256 with the 32-byte key):
- `networkName = "DIRECT-Vy-" + base32Lower(HMAC("net|$epoch|$utcDay")).take(10)` (≤ 32 chars, starts with `DIRECT-` + 2 alnum as Android requires).
- `passphrase = base64Url(HMAC("psk|$epoch|$utcDay")).take(24)`.
- `audioKey = HMAC("audio|$epoch").copyOf(16)` (AES‑128‑GCM; independent of day so a ride crossing midnight keeps its audio key).
- `utcDay = nowMillis / 86_400_000`.

- [ ] **Step 1: Write failing tests** — `derivationIsDeterministic`, `networkNameHasDirectPrefixAndFitsLength` (`startsWith("DIRECT-Vy-")`, `length <= 32`), `passphraseLengthWithinWpaRange` (8..63), `dayChangesNameButNotAudioKey`, `epochChangesEverything`, `joinCandidatesCoverTodayAndYesterday` (at `nowMillis = 86_400_000L * 20_000 + 1_000`, candidates are days 20000 and 19999 in that order). HTTP: `parsesIssuedKey`, `rejectsWrongCommunityId`, `rejectsKeyNot32Bytes`, `returnsNullOn404` (Ktor `MockEngine`).
- [ ] **Step 2: Run** `.\gradlew.bat :composeApp:testDebugUnitTest --tests "*RadioCredentials*" --tests "*RadioKeyHttpPort*"` — FAIL.
- [ ] **Step 3: Implement** the files above. Refresh trigger: whenever the Nearby community list is loaded successfully, fetch keys for every listed community and `put` them (wire in the same place the community list is consumed; one fetch per community per app start is enough).
- [ ] **Step 4: Run** the same command — PASS; then the full suite.
- [ ] **Step 5: Commit** `feat(radio): fetch, cache and derive radio credentials`

---

### Task 3: Packet codec with authenticated encryption

**Files:**
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/RadioPacket.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/ru/sodovaya/volty/data/radio/RadioPacketTest.kt`

**Interfaces:**
- Produces:
  - `enum class RadioPacketType(val wire:Int) { HELLO(1), AUDIO(2), ROSTER(3), BYE(4), FULL(5) }`
  - `data class RadioPacket(val type:RadioPacketType, val sender:Int /*0=hub,1..9 client slot, 255=unassigned*/, val session:Int /*random per process start*/, val seq:Long, val payload:ByteArray)`
  - `class RadioPacketCodec(audioKey:ByteArray) { fun encode(p:RadioPacket):ByteArray; fun decode(bytes:ByteArray):RadioPacket? }`
  - `class RadioReplayGuard { fun accept(sender:Int, session:Int, seq:Long):Boolean }` — per `(sender, session)` sliding window of 64.
  - Payload helpers: `RadioHello(userId:String, displayName:String, sentAtMillis:Long, echoMillis:Long?)`, `RadioRoster(entries:List<RadioRosterEntry(slot:Int, userId:String, displayName:String)>, sentAtMillis:Long, echoMillis:Long?)` with `encode():ByteArray` / `decode(ByteArray):T?` (length‑prefixed UTF‑8; names ≤ 80 chars).

Wire format: header `'V','R', version=1, type(1), sender(1), session(4), seq(8)` = 17 bytes clear and used as AES‑GCM AAD; nonce = `session(4) ‖ seq(8)`; ciphertext+tag follows. Max datagram 1200 bytes. `decode` returns `null` for bad magic/version/length/tag.

- [ ] **Step 1: Failing tests** — `roundTripsEveryType`, `rejectsTamperedPacket` (flip one ciphertext byte → `null`), `rejectsHeaderTamper` (flip sender → `null`), `rejectsForeignKey`, `rejectsTruncated`, `rejectsReplayedSequence` (guard accepts 5, rejects 5 again, accepts 6, rejects 5‑64), `audioFrameFitsDatagram` (640‑byte payload encodes ≤ 1200), `staleEpochIsReported` — belongs to Task 2's store contract: `newestEpochSeen` greater than cached epoch → caller sees `stale = true` (put this test in `RadioCredentialsTest` if simpler).
- [ ] **Step 2: Run** `--tests "*RadioPacket*"` — FAIL.
- [ ] **Step 3: Implement** with `javax.crypto.Cipher("AES/GCM/NoPadding")`, 128‑bit tag.
- [ ] **Step 4: Run** — PASS; full suite.
- [ ] **Step 5: Commit** `feat(radio): authenticated radio packet codec`

---

### Task 4: Voice gate, jitter buffer and mix-minus mixer (pure)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioVoiceGate.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioJitterBuffer.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioMixer.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/radio/RadioAudioPipelineTest.kt`

**Interfaces:**
- `const val RADIO_FRAME_SAMPLES = 320` (in `RadioMixer.kt`).
- `class RadioVoiceGate { fun process(frame:ShortArray):Boolean /* true = transmit */ }` — RMS in dBFS; noise floor tracked as running minimum that falls instantly and rises 0.5 dB per second of frames; open when `rms >= max(floor + 12, -50)`; hangover 15 frames (300 ms) after the last open frame.
- `class RadioJitterBuffer(targetFrames:Int = 3, maxFrames:Int = 10) { fun push(seq:Long, frame:ShortArray); fun pop():ShortArray? /* null = nothing to play */ }` — starts playing once `targetFrames` buffered; drops frames older than the last popped seq; overflow drops oldest; one missing frame is concealed by repeating the previous frame at half amplitude, longer gaps return `null`.
- `object RadioMixer { fun mix(frames:List<ShortArray>):ShortArray }` — int sum, then soft limiter: samples beyond ±24 576 compressed with `24576 + (x-24576)/4`, then clamp to Short range. `mixMinus` is the caller's job: hub calls `mix` on all frames except the recipient's.

- [ ] **Step 1: Failing tests** — gate: `silenceNeverOpens` (300 frames of zeros → all false), `speechAboveNoiseOpens` (floor from 100 frames of −60 dBFS noise, then −30 dBFS tone opens), `steadyWindNoiseClosesAfterAdaptation` (constant −35 dBFS noise: open at most during the first 2 s, closed after), `hangoverKeepsOpen15Frames`. Jitter: `waitsForTargetBeforePlaying`, `reordersOutOfOrderFrames`, `concealsSingleLoss`, `returnsNullOnLongGap`, `dropsLateFrame`, `capsAtMaxFrames`. Mixer: `mixOfOneIsIdentityBelowKnee`, `sumsTwoQuietFrames`, `neverOverflows` (nine frames of `Short.MAX_VALUE` → every sample ≤ 32767).
- [ ] **Step 2: Run** `--tests "*RadioAudioPipeline*"` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; full suite.
- [ ] **Step 5: Commit** `feat(radio): voice gate, jitter buffer and mixer`

---

### Task 4b: Radio modes and Opus codec

**Files:**
- Modify: `gradle/libs.versions.toml` (`concentus = "1.0.2"`, library `concentus = { module = "io.github.jaredmdobson:concentus", version.ref = "concentus" }`), `composeApp/build.gradle.kts` (androidMain dependency)
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioMode.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/RadioFrameCodec.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/ru/sodovaya/volty/data/radio/RadioFrameCodecTest.kt`

**Interfaces:**
- Produces (common): `enum class RadioMode(val wire:Int, val jitterTargetFrames:Int, val opusBitrate:Int? /* null = PCM */, val fec:Boolean) { LOW_LATENCY(1, 2, null, false), BALANCED(2, 3, 24_000, false), RELIABLE(3, 6, 12_000, true) }`; `RadioMode.fromWire(Int):RadioMode?`.
- Produces (android): `interface RadioFrameEncoder { fun encode(pcm:ShortArray):ByteArray }`, `interface RadioFrameDecoder { fun decode(payload:ByteArray?):ShortArray /* null = lost frame → PLC/FEC */ }`, `object RadioFrameCodecs { fun encoder(mode:RadioMode):RadioFrameEncoder; fun decoder(mode:RadioMode):RadioFrameDecoder }`. Opus: application VOIP, 16 kHz mono, complexity 5, frame 320; `RELIABLE` sets in-band FEC on and packet-loss percentage 20. PCM codec is little-endian 16-bit.

- [ ] **Step 1: Failing tests** — `pcmRoundTripIsExact`, `opusBalancedRoundTripPreservesTone` (1 kHz tone, after 5 warm-up frames decoded RMS within 3 dB of input), `opusFrameSizeMatchesBitrate` (BALANCED ≤ 80 bytes, RELIABLE ≤ 50 bytes per frame), `decoderConcealsLostFrame` (`decode(null)` returns 320 samples without throwing), `modeWireRoundTrips`.
- [ ] **Step 2: Run** `--tests "*RadioFrameCodec*"` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; full suite.
- [ ] **Step 5: Commit** `feat(radio): radio modes and Opus frame codec`

---

### Task 5: Hub session (pure state machine)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioHubSession.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/radio/RadioHubSessionTest.kt`

**Interfaces:**
- Consumes: `RadioJitterBuffer`, `RadioMixer`, `RADIO_FRAME_SAMPLES` (Task 4).
- Produces (decoded messages, transport-agnostic so it stays in commonMain):
  - `sealed interface RadioInbound { val from:RadioAddress; data class Hello(override val from:RadioAddress, val userId:String, val name:String, val sentAtMillis:Long); data class Audio(override val from:RadioAddress, val slot:Int, val seq:Long, val pcm:ShortArray); data class Bye(override val from:RadioAddress, val slot:Int) }`
  - `data class RadioAddress(val host:String, val port:Int)`
  - `sealed interface RadioOutbound { val to:RadioAddress; data class Roster(...entries, echoMillis); data class Audio(override val to:RadioAddress, val pcm:ShortArray); data class Full(override val to:RadioAddress) }`
  - `data class RadioMember(val slot:Int, val userId:String, val name:String, val lastSeenMillis:Long, val rttMillis:Long?)`
  - `class RadioHubSession(hubUserId:String, hubName:String, maxClients:Int = 9, evictAfterMillis:Long = 3_000) { fun receive(msg:RadioInbound, nowMillis:Long):List<RadioOutbound>; fun tick(nowMillis:Long, hubFrame:ShortArray?):List<RadioOutbound> /* call every 20 ms */; val members:List<RadioMember> }`

Mode: the constructor also takes `mode:RadioMode = RadioMode.BALANCED`; `fun setMode(mode:RadioMode)` recreates the jitter buffers with `mode.jitterTargetFrames`; every `Roster` carries `mode.wire`. The session works on decoded PCM only — encoding/decoding happens in the Android runtime (Task 9) with `RadioFrameCodecs`. Test `rosterCarriesModeAndSetModeApplies`.

Behaviour: `Hello` from a new address takes the lowest free slot 1..9 (same userId from a new address replaces its old slot); 10th → `Full`. Every `tick`: pop one frame per member jitter buffer (plus `hubFrame` as slot 0), send each client `Audio(mix of all frames except that client's)` only if the mix is non-empty; every 50 ticks (1 s) send each client `Roster` with `echoMillis` = that client's last `sentAtMillis`. Members silent > `evictAfterMillis` (no Hello or Audio) are removed. The hub's own playback mix (everything except slot 0) is exposed as `fun hubPlayback():ShortArray?` computed in the same tick.

- [ ] **Step 1: Failing tests** — `firstHelloGetsSlotOne`, `tenthClientGetsFull`, `sameUserNewAddressReplacesSlot`, `clientNeverHearsItself` (client 1 sends tone A, client 2 tone B: client 1's mix equals B, client 2's equals A after jitter target), `hubHearsEveryClient`, `rosterEverySecondEchoesSentAt`, `hubEvictsSilentClient` (no packets for 3 001 ms → removed, slot reusable), `byeFreesSlotImmediately`.
- [ ] **Step 2: Run** `--tests "*RadioHubSession*"` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; full suite.
- [ ] **Step 5: Commit** `feat(radio): hub session with mix-minus and eviction`

---

### Task 6: Client session (pure state machine)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioClientSession.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/radio/RadioClientSessionTest.kt`

**Interfaces:**
- Consumes: Task 4 gate/jitter, Task 5 message types.
- Produces: `enum class RadioClientPhase { JOINING, CONNECTED, FULL, LOST }`; `class RadioClientSession(userId:String, name:String, hub:RadioAddress, lostAfterMillis:Long = 3_000) { val phase:RadioClientPhase; val roster:List<RadioMember>; val rttMillis:Long?; fun receive(msg:RadioInbound /* Roster/Audio/Full from hub */, nowMillis:Long); fun tick(nowMillis:Long, micFrame:ShortArray):List<RadioOutbound>; fun playback():ShortArray? }`. Add the hub→client inbound variants (`Roster`, `Full`, `MixAudio`) to `RadioInbound` in this task.

Mode: `val mode:RadioMode` follows the latest `Roster`'s mode (default `BALANCED` until the first roster); the runtime swaps encoder/decoder when it changes. Test `followsHubMode`.

Behaviour: sends `Hello` immediately and every 1 s; mic frames pass through `RadioVoiceGate`, transmitted only when open (seq increments only on sent frames); first `Roster` → `CONNECTED`; `Full` → `FULL` (stop sending); nothing from hub for `lostAfterMillis` → `LOST` (stop sending); RTT = now − `echoMillis`.

- [ ] **Step 1: Failing tests** — `sendsHelloOnStartAndEverySecond`, `becomesConnectedOnRoster`, `silenceIsNotTransmitted`, `speechIsTransmittedWithIncreasingSeq`, `clientMarksLostAfterThreeSilentSeconds`, `fullStopsTransmission`, `rttFromEcho`.
- [ ] **Step 2: Run** `--tests "*RadioClientSession*"` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; full suite.
- [ ] **Step 5: Commit** `feat(radio): client session with lost detection`

---

### Task 7: Radio controller — lifecycle, lost-hub recovery, exclusivity (pure)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioController.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/radio/RadioControllerTest.kt`

**Interfaces:**
- Consumes: `RadioKey`, `RadioKeyStore` (Task 2).
- Produces:
  - `interface RadioLink { suspend fun host(community:String):RadioLinkResult; suspend fun join(community:String, timeoutMillis:Long):RadioLinkResult; suspend fun leave(); fun radioNearby(community:String):Boolean /* from last scan */ }` with `sealed interface RadioLinkResult { data class Hosting(val localPort:Int); data class Joined(val hub:RadioAddress); data object NotFound; data object Unsupported; data class Failed(val reason:String) }`.
  - `interface RadioServerVoice { fun isActive():Boolean; fun disconnect() }` (Android impl calls `NearbyVoiceController.disconnect(state.value.operation)`).
  - `sealed interface RadioState { data object Idle; data class Starting(val community:String); data class Hub(val community:String); data class Client(val community:String, val phase:RadioClientPhase); data class Lost(val community:String, val rejoinUntilMillis:Long); data class Error(val community:String?, val reason:RadioError) }`, `enum class RadioError { UNSUPPORTED_ANDROID, NO_KEY, STALE_KEY, NOT_FOUND, FULL, WIFI_FAILED }`.
  - `class RadioController(link:RadioLink, keys:RadioKeyStore, serverVoice:RadioServerVoice, clock:()->Long, scope:CoroutineScope) { val state:StateFlow<RadioState>; fun raise(actor:String, community:String); fun join(actor:String, community:String); fun reRaise(); fun leave(); fun onClientPhase(phase:RadioClientPhase); fun onServerVoiceStarted() }`

Behaviour: `raise` — no key → `Error(NO_KEY)`; first `link.join(community, 5_000)`; if `Joined` become client (never two hubs when one is reachable), else `link.host`. `join` — `link.join(community, 30_000)`, `NotFound` → `Error(NOT_FOUND)`. Both call `serverVoice.disconnect()` first when active. Client phase `LOST` → `Lost(rejoinUntil = now + 120_000)` and keep calling `link.join(community, 5_000)` until joined or the window ends (then `Error(NOT_FOUND)`); `reRaise` from `Lost` → `link.host`. `onServerVoiceStarted` → `leave()`. Stale key (`newestEpochSeen > cached.epoch`) → `Error(STALE_KEY)` before touching the link.

- [ ] **Step 1: Failing tests** with a fake `RadioLink` and virtual time (drive `scope = backgroundScope`; assert on calls *issued* to the fake, not timing): `raiseJoinsExistingRadioInsteadOfHosting`, `raiseHostsWhenNoneFound`, `raiseWithoutKeyErrors`, `staleKeyErrorsBeforeLink`, `joiningDisconnectsServerVoice`, `clientJoinTimesOutAsNotFound`, `lostKeepsRejoiningFor120Seconds` (fake returns `NotFound` → join issued repeatedly; at 120 001 ms state is `Error(NOT_FOUND)` and no more joins issued), `reRaiseFromLostHosts`, `serverVoiceStartLeavesRadio`.
- [ ] **Step 2: Run** `--tests "*RadioController*"` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS; full suite.
- [ ] **Step 5: Commit** `feat(radio): controller with lost-hub recovery and voice exclusivity`

---

### Task 8: Android Wi‑Fi Direct link and UDP transport

**Files:**
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/AndroidRadioLink.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/RadioUdpTransport.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioDuplicateRule.kt`
- Modify: `composeApp/src/androidMain/AndroidManifest.xml` (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` with `android:usesPermissionFlags="neverForLocation"`)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/radio/RadioDuplicateRuleTest.kt`

**Interfaces:**
- Consumes: `RadioCredentialDerivation` (Task 2), `RadioPacketCodec`/`RadioReplayGuard` (Task 3), `RadioLink`/`RadioLinkResult`/`RadioAddress` (Task 7), `RadioInbound`/`RadioOutbound` (Tasks 5–6).
- Produces: `class AndroidRadioLink(context:Context, keys:RadioKeyStore, actor:()->String?):RadioLink`; `class RadioUdpTransport(codec:RadioPacketCodec, port:Int = 47_820) { fun send(msg:RadioOutbound); val inbound:Flow<RadioInbound>; fun close() }`; `object RadioDuplicateRule { fun shouldYield(ownMembers:Int, ownBssid:String, otherBssid:String, otherMembersUnknown:Boolean = true):Boolean }` — yield when the other BSSID is lexicographically smaller (member count is not visible in scan results, so BSSID alone decides; deterministic on both sides).

Android facts to use: `WifiP2pManager.createGroup(channel, WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(psk).setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ).build(), listener)` for the hub (retry once with `GROUP_OWNER_BAND_AUTO`); `connect(channel, WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(psk).build(), listener)` for clients, trying each of `joinCandidates` in order; hub address from `requestConnectionInfo` → `groupOwnerAddress`. `radioNearby` / duplicate detection: `WifiManager.startScan()` + `scanResults` SSID match against `joinCandidates` names (needs location permission below API 33 — the app already holds it). Hub checks scan results every 30 s while hosting and dissolves + joins when `shouldYield`. Below API 29 every method returns `RadioLinkResult.Unsupported`. Never call `bindProcessToNetwork`; open the UDP socket bound to the P2P interface address only.

- [ ] **Step 1: Failing tests** — `smallerBssidWins`, `ruleIsAntisymmetric` (for two BSSIDs exactly one side yields), `equalBssidNeverYields`.
- [ ] **Step 2: Run** `--tests "*RadioDuplicateRule*"` — FAIL.
- [ ] **Step 3: Implement** the rule, the link and the transport. The link and transport are not unit-testable here (framework classes); say so in the commit body.
- [ ] **Step 4: Run** full suite — PASS; `.\gradlew.bat :composeApp:assembleDebug` compiles.
- [ ] **Step 5: Commit** `feat(radio): Wi-Fi Direct link and UDP transport`

---

### Task 9: Audio I/O, foreground service and wiring

**Files:**
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/RadioAudioIo.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/NearbyRadioService.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/RadioRuntime.kt`
- Modify: `composeApp/src/androidMain/AndroidManifest.xml` (service `NearbyRadioService`, `foregroundServiceType="microphone|connectedDevice"`)
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt` (bind `RadioKeyStore`, `RadioLink`, `RadioServerVoice`, `RadioController`, `RadioRuntime`)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/nearby/NearbyVoiceController.kt` (call `RadioController.onServerVoiceStarted()` when a server conversation begins)

**Interfaces:**
- Consumes: everything above.
- Produces: `class RadioAudioIo { fun start(onFrame:(ShortArray)->Unit); fun play(frame:ShortArray?); fun stop() }` — `AudioRecord(VOICE_COMMUNICATION, 16_000, MONO, PCM_16BIT)` with `AcousticEchoCanceler`/`NoiseSuppressor` when available; `AudioTrack(USAGE_VOICE_COMMUNICATION, CONTENT_TYPE_SPEECH, 16_000, MONO)`; `AudioManager.mode = MODE_IN_COMMUNICATION` while running and restored on stop; on API 31+ `setCommunicationDevice` to the first `TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET` device if present, else leave the default; `play(null)` writes silence. One dedicated thread paced by the 20 ms `AudioRecord` read drives `tick()` of the hub or client session — no extra timers.
- `RadioRuntime` encodes outgoing PCM and decodes incoming payloads with `RadioFrameCodecs` for the current mode (one decoder per sender slot, so Opus state is per stream; a missing frame calls `decode(null)`), and swaps them when the mode changes.
- `RadioRuntime` owns transport + session + audio for the current `RadioState` and starts/stops `NearbyRadioService` (notification text `Рация: N чел.` / `Radio: N people`, action `Выйти`).

Not unit-testable (framework audio/service); this task is verified by `assembleDebug` and on-device in Task 10. Say so in the commit.

- [ ] **Step 1: Implement** the three classes, manifest and DI.
- [ ] **Step 2: Run** full suite — PASS (no regressions); `.\gradlew.bat :composeApp:assembleDebug` — succeeds.
- [ ] **Step 3: Commit** `feat(radio): audio I/O, foreground service and runtime wiring`

---

### Task 10: UI, settings, diagnostics export and field checklist

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/nearby/NearbyRadioPanel.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/radio/RadioDiagnostics.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/radio/RadioDiagnosticExporter.kt`
- Create: `docs/superpowers/specs/2026-10-20-nearby-wifi-radio-field-checklist.md`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/nearby/NearbyConversationPanelScreen.kt` (place the radio panel for the open server)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/prefs/AppPrefs.kt` (`radioAutoJoin: StateFlow<Boolean>`, default `false`, + setter)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/nearby/NearbyPermissionGate.kt` (`NearbyPermissionKind.NEARBY_WIFI`) and `AndroidNearbyPermissionBridge.kt` (maps to `NEARBY_WIFI_DEVICES` on API 33+, fine location below)
- Modify: `values/strings.xml`, `values-ru/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/radio/RadioDiagnosticsTest.kt`, `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/root/nearby/NearbyRadioPanelStateTest.kt`

**Interfaces:**
- Consumes: `RadioController.state`, `RadioHubSession.members` / `RadioClientSession.roster`, `rttMillis`.
- Produces: `fun radioPanelState(state:RadioState, roster:List<RadioMember>, supported:Boolean):RadioPanelState` (pure) with `data class RadioPanelState(val title:RadioTitle, val primary:RadioAction?, val secondary:RadioAction?, val members:List<String>, val error:RadioError?)`, `enum class RadioAction { RAISE, JOIN, LEAVE, RERAISE }`; `data class RadioDiagnostics(...)` + `fun format():String` with role, member count, per-member RTT and receive loss %, battery % at start and now, elapsed time.

Strings (ru / en):
- `radio_raise` Поднять рацию / Start radio
- `radio_join` Подключиться к рации / Join radio
- `radio_leave` Выйти из рации / Leave radio
- `radio_reraise` Поднять заново / Start again
- `radio_lost` Рация потеряна / Radio lost
- `radio_full` Рация заполнена / Radio is full
- `radio_not_found` Рация не найдена / Radio not found
- `radio_stale_key` Ключ рации устарел — откройте Nearby с интернетом / Radio key is out of date — open Nearby while online
- `radio_unsupported` Рация недоступна на этой версии Android / Radio is not available on this Android version
- `radio_members` Рация: %1$d чел. / Radio: %1$d people
- `radio_mode` Режим связи / Connection mode
- `radio_mode_low_latency` Минимальная задержка / Lowest latency
- `radio_mode_low_latency_hint` Без сжатия, самый живой звук. Для плотной колонны / No compression, most immediate sound. For a tight column
- `radio_mode_balanced` Сбалансированный / Balanced
- `radio_mode_balanced_hint` Подходит почти всегда / Fits almost every ride
- `radio_mode_reliable` Надёжность / Reliability
- `radio_mode_reliable_hint` Дольше задержка, но связь держится на краю дальности / More delay, but holds at the edge of range
- `radio_auto_join` Подключаться к рации автоматически / Join radio automatically
- `radio_auto_join_battery` Телефон будет постоянно искать рацию рядом — это заметно расходует батарею / The phone will keep looking for a radio nearby, which noticeably drains the battery

Mode picker: shown only to the hub (and before raising); stored in `AppPrefs.radioMode` (default `BALANCED`); clients see the current mode read-only. `RadioController` gets `fun setMode(mode:RadioMode)` forwarded to the hub session. Panel test `modePickerOnlyForHub`.

Auto-join: when `radioAutoJoin` is on and state is `Idle`, `RadioRuntime` checks `link.radioNearby(community)` for each cached community every 30 s and calls `join` on the first hit.

- [ ] **Step 1: Failing tests** — panel: `idleSupportedOffersRaiseAndJoin`, `unsupportedShowsErrorAndNoActions`, `lostOffersReraise`, `hubShowsLeaveAndMembers`, `fullShowsErrorAndJoin`. Diagnostics: `formatsRoleMembersAndRtt`, `lossPercentFromGaps`.
- [ ] **Step 2: Run** `--tests "*Radio*Panel*" --tests "*RadioDiagnostics*"` — FAIL.
- [ ] **Step 3: Implement** state function, Compose panel (renders what it is handed — no logic), settings toggle with the battery warning, permission kind, exporter (same share-intent pattern as `VoiceDiagnosticExporter`).
- [ ] **Step 4: Run** full suite — PASS; `assembleDebug` succeeds.
- [ ] **Step 5: Write the field checklist** with: 2 phones static at 10/50/100 m; 3–5 phones riding in a column for 20 min; one hub with as many clients as available (record the cap); hub rides away → others reach `Радио потеряна` ≤ 3 s, re-raise works; BT headset + wind at speed; battery % per hour for hub and client; mobile data still works during radio (open the map); wheel BLE telemetry uninterrupted. Each item records model, Android version, result and the exported diagnostics file.
- [ ] **Step 6: Commit** `feat(radio): radio panel, settings, diagnostics and field checklist`
