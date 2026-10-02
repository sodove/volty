# Nearby Android adapter and diagnostics Implementation Plan

> **For agentic workers:** Use executing-plans; primary implements complex work, Luna/xhigh only bounded checks with context. User explicitly requested autonomous continuation without interruptions. No additional approval handoff for this stage; no Astra.

**Goal:** Build an independently usable diagnostic surface for the new LiveKit
adapter, with owned audio resources, separate input/output intent and sanitized
evidence for Xiaomi. No claim of device success from unit tests.

**Architecture:** A common driver owns one tagged SDK session and fences
asynchronous connect/publish/close. Android session injects its own ADM and audio
handler, with complete release ownership. A dedicated nonexported diagnostic
Activity uses existing authenticated group token endpoints before v2 cutover;
this is explicitly a transport experiment, not a v2 claim/lease implementation.

**Tech Stack:** Kotlin/common tests, Android36/min26, pinned LiveKit2.25.3,
WebRTC144.7559.05; no SDK version upgrade, SQL or backend changes.

**Spec:** voice-spaces design, foundation-contracts, source-audit and voice-core
plan under docs/superpowers. Strict SFU revoke remains NOT PROVEN. Existing
Nearby DI/UX is not replaced with a diagnostic implementation.

## Global Constraints

- No FFE1/BLE changes. No production server mutation/deployment.
- Credentials have redacted toString; no JWT/URLs/exception messages/PCM/coords
  in export. Trace payloads typed; errors exported as stable codes only.
- Source-backed public APIs only, no reflection into ADM/SDK.
- Custom ADM cannot use SDK local AudioTrack.addSink dispatcher; count samples
  in owned callback without saving audio. Disable SDK prewarming.
- SDK internal communication workaround cannot be composed with overridden
  ADM callbacks. Disable it and own an explicitly labelled silent keepalive,
  separate from WebRTC render/capture evidence; release it on stop.
- PHONE input selection never changes desired output priority. Missing source
  device is unsupported/unknown; do not pass null to ADM input setter.
- Actual AudioRecord/AudioTrack stream device observation remains Unknown where
  public APIs cannot correlate; communication device and recording config are
  separate diagnostic hints, never promoted to actual stream proof.
- Microphone FGS starts from visible diagnostic Activity with runtime permission,
  START_NOT_STICKY; no capture restoration after process death. Location sharing
  not available in this experiment, so no location FGS starts here.
- Before each commit full unit suite; baseline restored via cached native DLL
  in selected GRADLE_USER_HOME/workers. Compose/FGS/routing device proof separate.

## Review Focus

1. Stop during suspended connect cannot publish later (Task1).
2. Old close/failure cannot close replacement session (Task1).
3. Publish false is failure with resource cleanup (Task1).
4. Phone input request preserves headset output and exported trace contains no
   arbitrary credentials/error strings (Task2/3).
5. FGS/activity destruction closes native resources; actual device proof remains
   NOT MEASURED when adb empty (Task3).

### Task 1: SDK session ownership driver

Create commonMain data/voice VoiceSdkSession.kt and VoiceEngineDriver.kt;
commonTest data/voice VoiceEngineDriverTest.kt. No Android dependencies.

```kotlin
class VoiceConnectionCredentials(val serverUrl:String,val token:String) {
    override fun toString() = "VoiceConnectionCredentials(redacted)"
}
interface VoiceSdkSession {
    suspend fun connect(credentials:VoiceConnectionCredentials)
    suspend fun publishMicrophone(muted:Boolean):Boolean
    suspend fun setMuted(muted:Boolean):Boolean
    fun setDeafened(deafened:Boolean)
    suspend fun close()
}
fun interface VoiceSdkSessionFactory {
    fun create(tag:VoiceSessionTag, emit:(VoiceSessionEvent)->Unit):VoiceSdkSession
}
```

Produces VoiceEngineDriver(scope,factory), events SharedFlow; start(StartEngine,
credentials):Result<Unit>; stop(generation); setMuted/tag and setDeafened/tag.
Owner reservation changes before any suspended network work. Network operations
outside mutex; callbacks checked against current tag. CancellationException
rethrows after NonCancellable cleanup of owned session. Stop closes only matching
handle. No new reconnect loop: SDK owns reconnect.

- [ ] Write failing fake-session tests: suspend connect using CompletableDeferred,
  stop, release gate → publishCalls0 and closeCalls1; replacement B active while A
  later errors → B closeCalls0; publish false → own closeCalls1; desired muted and
  deafened set before publish; wrong-generation stop leaves current session open.
  Assert issued calls/events, never sleep timing. No unbounded virtual-time loops.
- [ ] Watch RED via testDebugUnitTest --tests '*VoiceEngineDriverTest'.
- [ ] Implement lifecycle with tagged handle and idempotent close; check current
  ownership before/after connect/publish. Repeated close uses the captured handle,
  not global room. Driver emits Stopped only after close return; close failure
  remains typed failure, not successful release. Emit Failed on failed active start.
- [ ] Focused GREEN, full fresh suite, narrow Luna trace check, diff/commit.

### Task 2: Owned Android audio and LiveKit session

Create androidMain data/voice AndroidVoiceSdkSession.kt, OwnedVoiceAudio.kt,
VoiceAudioRouteMonitor.kt, VoiceCommunicationKeeper.kt. Common diagnostics typed
models/formatter under domain/voice; tests sanitize/format counters and unknowns.
Consumes Task1 factory/session interfaces; produces AndroidVoiceSdkSessionFactory.

- [ ] Read pinned LiveKit LiveKitOverrides/RTCModule/CommunicationWorkaround/
  LocalParticipant/RoomEvent/Track sources; javap exact prefixed ADM API.
- [ ] Write failing pure tests for trace formatting: only enum codes/counters/
  generation/provenance can enter exporter, credentials.toString redacted;
  selection evidence never invents routed device; PHONE input does not rewrite
  desired output preference. Keep Android types out of common functions.
- [ ] Implement typed fixed-capacity diagnostic buffer; no raw exception strings.
- [ ] Initialize LiveKit before own JavaAudioDeviceModule; capture/track callbacks
  emit Started/Stopped and stable init/start/runtime error codes. Samples callback
  counts bytes/frames only. Match SDK default VOICE_COMMUNICATION/AEC/NS setup.
- [ ] Own AudioSwitchHandler for focus/output; wrap start/stop with own silent
  communication keeper. ADM input uses AudioManager builtin mic device separately.
  AUTO starts a fresh session with no preference; changing explicit→AUTO recreates
  session, never calls nullable setter. Register/remove recording/device callbacks.
- [ ] Own ADM release after Room disconnect/release in finally, including failed
  connect. Publish respects desired mute before track creation/publication and
  checks Boolean result; deafen uses Room.setSpeakerMute. Map only real SDK
  media callbacks; aggregate peer audio publications for unsubscribe.
- [ ] Stats getRTCStats records audio outbound/inbound packet counters without
  exporting track/peer identity or bearer. Callback transport connected separate
  from publication/ADM start. Polling job cancelled with session.
- [ ] Compile Android, pure tests/full suite, narrow callback/release check, commit.

### Task 3: Explicit diagnostic Activity, FGS and APK

Create androidMain diagnostics/voice VoiceDiagnosticActivity/Screen/Controller,
VoiceDiagnosticService and dedicated exporter. Add common expect launcher and
Android actual, DebugSettingsTab entry and both values/values-ru strings.
Manifest microphone FGS permission/service and nonexported Activity; BLE
MonitoringService unchanged. Existing Nearby engine DI unchanged.

- [ ] Diagnostic controller loads existing authenticated groups, gets actual v1
  credentials only after explicit start. Tags are local experiment IDs, never
  described as server lease/claim. Stop/cancellation fences late token response.
  Before diagnostic start leave existing voice via VoiceRoomRepository without
  deleting group membership. No location/telemetry sharing started.
- [ ] Activity renders controller StateFlow; start permission via launcher and
  visible Activity. Foreground notification before connect/capture; Stop action
  calls controller disconnect; service nonsticky and stops after resource release.
  No static credential Intent extras. Configuration change retains controller via
  ViewModel; service stop reaches that same instance. Process death has no resume.
- [ ] Show desired mic/output, selected output, communication-device hint,
  uncorrelated recording config hint, ADM capture/render starts, counters/error
  codes. Clearly label unknown actual stream route. Export only dedicated typed
  trace + app/model/build metadata, not existing unrestricted logcat.
- [ ] Android strings both directories. Unit tests for late credential response
  after stop and typed export. No Compose/FGS fake proof; run compile/full suite.
- [ ] Build APK with existing local signing configuration if available. Verify
  APK artifact and adb inventory; empty inventory → device NOT MEASURED. Record
  two-phone/Xiaomi protocol from foundation report. No production deploy.

## Self-review and autonomy ruling

Only transport prototype is implemented here; servers/channel membership,
claims, SFU strict revoke, direct calls and UX cutover remain separate work.
The new core's30s backend ACK rule must not be fed fake ACKs from SFU packets.
Legacy token endpoint use is scoped to diagnostics before cutover. User asked
to continue without interruptions, overriding skill's repeated approval handoff.
