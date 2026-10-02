# Nearby pure voice core Implementation Plan

Execution 2026-10-03: Tasks1–3 implemented in isolated branch
`codex/nearby-foundations`.40 new pure-core tests; full fresh suite2556 tests,
0 failures/errors. Narrow Luna/xhigh lifecycle, lease and sharing/route checks
completed; fixture assertion gaps strengthened. Ledger/evidence retained.
Core is not wired into the current app; Android routing/FGS, Xiaomi field proof,
backend/SFU revocation and new UX are subsequent stages.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. User routing overrides defaults: primary implements complex logic; only small bounded checks go to gpt-6-luna/xhigh, with complete task context. No Sol medium. No Astra without a new user request.

**Goal:** Создать тестируемое ядро управления одной голосовой сессией, которое
не возобновляет отменённый разговор и отдельно хранит transport/media/sharing.

**Architecture:** Pure reducer принимает typed события и monotonic timestamps,
выдаёт новое состояние и effects. Platform/backend adapters реализуются другим
планом; этот этап не подключает новый core к старому Nearby UI и не обещает
реальную слышимость. Существующий runtime остаётся до согласованного cutover.

**Tech Stack:** commonMain Kotlin, kotlin.test; без новых dependencies/SQL/UI.

**Spec:** `docs/superpowers/specs/2026-10-03-nearby-voice-spaces-design.md`,
`docs/superpowers/specs/2026-10-03-nearby-foundation-contracts.md`.
Обязательно прочитать source-audit и sfu-revocation-evidence рядом со spec.

## Global Constraints

- minSdk26, targetSdk36; Kotlin Multiplatform Android only.
- Never write to FFE1; этот этап вообще не изменяет BLE.
- Compose/device audio не доказываются unit tests. Xiaomi/PHONE→headphones NOT MEASURED.
- Строгий SFU revoke NOT PROVEN; подробный backend этап заблокирован этим gate.
- Не менять версии LiveKit/WebRTC, backend, schemas и manifest в этом этапе.
- State не содержит JWT; opaque credential handle существует только в adapter.
- Перед каждым commit полная Android suite; при baseline failure не commit.
- Ledger читать до начала; не удалять evidence. Русский UI и обе strings directories
  соблюдаются последующим UI этапом; в этом плане строки UI не добавляются.

## Review Focus

1. Потерянный join response после stop: late session освобождается, start не issued (Task1).
2. Old callbacks после stop/new join: новая generation не меняется (Task1/2).
3. Живой SFU без lease ACK >30s: stop issued ровно своей session (Task2).
4. Selection/transport без actual media: UI state не выдумывает capture/render (Task2/3).
5. Hide location при navigation demand: core выдаёт только remove Nearby demand (Task3).

## Preflight

- [x] Прочитать AGENTS, foundations ledger, spec/reports. Изоляция уже существует:
  `C:/Users/sodovaya/.codex/worktrees/nearby-foundations/volty`,
  branch `codex/nearby-foundations`; не создавать новый worktree без причины.
- [x] Восстановить baseline execution environment: предыдущие три запуска failed
  до tests из-за SQLDelight SQLite DLL lock в C:/WINDOWS. Диагностировать JVM
  worker properties/native loader, не менять migrations ради passing build.
  Выполнить fresh full suite, сохранить log и фактический test count. До этого
  возможен review плана, но implementation/commit не начинается.
- [x] Создать `.superpowers/sdd/2026-10-03-nearby-voice-core/progress.md` с HEAD,
  ownership, outcomes и risks; разработка только после review данного плана.

## File map

Create package `ru.sodovaya.volty.domain.voice` в commonMain/commonTest.
Все существующие domain.social APIs остаются нетронутыми на этом этапе.

| File | Responsibility |
|---|---|
| VoiceSessionIdentity.kt | typed operation/session/generation identifiers |
| VoiceSessionState.kt | local intent/transport/media/lease/sharing state |
| VoiceSessionEvent.kt | tagged inputs, explicit timestamps |
| VoiceSessionEffect.kt | side effects for future adapters |
| VoiceSessionReducer.kt | ordered pure transition function |
| AudioRouteObservation.kt | desired/selected/observed routes + provenance |
| VoiceSessionLifecycleTest.kt | cancellation and generation fences |
| VoiceSessionLeaseTest.kt | ACK deadlines and media readiness |
| VoiceSessionSharingTest.kt | separate sharing intent/demand |
| AudioRouteObservationTest.kt | unknown route never becomes observed |

### Task 1: Start/stop and generation fence

**Files:** first five files and VoiceSessionLifecycleTest.kt in the file map.
**Ownership:** primary handles reducer; Luna only explicit single-trace review.
**Interfaces consumes:** pure event+state, no SDK/backend references.
**Produces:** `VoiceSessionReducer.reduce(state, event): VoiceTransition`,
where `VoiceTransition(state, effects: List<VoiceSessionEffect>)`.

Contract names (define in this task, add fields/events in later tasks):

```kotlin
@JvmInline value class VoiceOperationId(val value: String)
@JvmInline value class VoiceSessionId(val value: String)
@JvmInline value class VoiceGeneration(val value: Long)
data class VoiceSessionTag(val sessionId: VoiceSessionId, val generation: VoiceGeneration)
enum class VoicePhase { IDLE, JOINING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, FAILED }
data class VoiceSessionState(
    val generation: VoiceGeneration = VoiceGeneration(0),
    val operationId: VoiceOperationId? = null,
    val sessionId: VoiceSessionId? = null,
    val phase: VoicePhase = VoicePhase.IDLE,
    val wanted: Boolean = false,
    val cleanupPending: Boolean = false,
)
data class VoiceTransition(val state: VoiceSessionState, val effects: List<VoiceSessionEffect>)
```

Events: Join(operationId), SessionGranted(operationId, generation, sessionId,
nowMillis), Stop, Stopped(generation), Failed(generation). Effects:
RequestJoin(operationId,generation), CancelJoin(operationId,generation),
StartEngine(tag), StopEngine(generation), StopSession(sessionId),
RemoveNearbyDemand, ClearSharingQueue. No effect removes server membership.
SessionGranted is an adapter event: opaque credentials never stored in reducer.

- [x] Write failing tests below (exact imports/types in this task).

```kotlin
@Test fun lateGrantAfterStopCannotStartEngine() {
    val op = VoiceOperationId("op1")
    val joining = VoiceSessionReducer.reduce(VoiceSessionState(), VoiceSessionEvent.Join(op))
    assertEquals(listOf(VoiceSessionEffect.RequestJoin(op, joining.state.generation)), joining.effects)
    val stopped = VoiceSessionReducer.reduce(joining.state, VoiceSessionEvent.Stop)
    assertTrue(stopped.effects.contains(VoiceSessionEffect.CancelJoin(op, joining.state.generation)))
    val session = VoiceSessionId("s1")
    val late = VoiceSessionReducer.reduce(stopped.state,
        VoiceSessionEvent.SessionGranted(op, joining.state.generation, session, 0))
    assertFalse(late.state.wanted)
    assertFalse(late.effects.any { it is VoiceSessionEffect.StartEngine })
    assertEquals(listOf(VoiceSessionEffect.StopSession(session)), late.effects)
}
@Test fun oldStopCompletionCannotCloseNewJoin() {
    val one = VoiceSessionReducer.reduce(VoiceSessionState(), VoiceSessionEvent.Join(VoiceOperationId("one")))
    val stopping = VoiceSessionReducer.reduce(one.state, VoiceSessionEvent.Stop)
    val two = VoiceSessionReducer.reduce(stopping.state, VoiceSessionEvent.Join(VoiceOperationId("two")))
    assertTrue(two.state.generation.value > one.state.generation.value)
    val old = VoiceSessionReducer.reduce(two.state, VoiceSessionEvent.Stopped(one.state.generation))
    assertEquals(two.state, old.state)
    assertTrue(old.effects.isEmpty())
}
```

- [x] Run filtered failing tests: command at end of plan with lifecycle class.
- [x] Implement reducer. Join increases generation and issues old cleanup before
  new RequestJoin; Stop fences intent immediately, emits cancel for pending op,
  stops engine, stops known session, clears queue/removes Nearby demand.
  Duplicate Stop has no new effects once intent false/cleanup already issued.
  Stopped clears cleanupPending only for matching generation; old grant emits
  StopSession except duplicate grant for currently active tag (must not close it).
- [x] Add duplicate grant/duplicate stop and failed join tests: same active grant
  has no second StartEngine/StopSession; Failed ends wanted, CancelJoin issued;
  Join while active emits StopEngine+StopSession before RequestJoin. Assert effects
  ordered, not just state. No state-machine test relies on wall-clock sleep.
- [x] Focused red/green, full suite, scoped review, git diff --check and commit
  only task1 files after full suite success. Record counts and review in ledger.

### Task 2: Media evidence and lease deadline

**Files:** State/Event/Effect/Reducer, VoiceSessionLeaseTest.kt.
**Consumes:** Task1 types/reduce. **Produces:** media proof fields, Tick and
tagged transport/media/lease events, Stop resources on finite lease policy.

Add to state: transportConnected, captureStarted, published, subscribedPeers
(Set<String>), renderStarted, lastLeaseAckMillis(Long?), muted/deafened.
Events: TransportConnected(tag), CaptureStarted(tag), Published(tag),
Subscribed(tag,peer), RenderStarted(tag), Reconnecting(tag), LeaseAck(tag,nowMillis),
Tick(nowMillis), SetMuted(Boolean), SetDeafened(Boolean). Effects SetMuted(tag,value),
SetDeafened(tag,value). Success CONNECTED means confirmed transport only; fields
remain separate and there is no aggregate `audible=true`.

- [x] Write failing tests with explicit tag and timestamps:

```kotlin
@Test fun transportDoesNotInventMedia() {
    val tag = VoiceSessionTag(VoiceSessionId("s1"), VoiceGeneration(1))
    val initial = VoiceSessionState(generation=tag.generation, sessionId=tag.sessionId,
        phase=VoicePhase.CONNECTING, wanted=true, lastLeaseAckMillis=0)
    val result = VoiceSessionReducer.reduce(initial, VoiceSessionEvent.TransportConnected(tag))
    assertTrue(result.state.transportConnected)
    assertFalse(result.state.captureStarted)
    assertFalse(result.state.published)
    assertFalse(result.state.renderStarted)
    assertTrue(result.state.subscribedPeers.isEmpty())
}
@Test fun aliveTransportCannotOutliveUnconfirmedLease() {
    val tag = VoiceSessionTag(VoiceSessionId("s1"), VoiceGeneration(1))
    val initial = VoiceSessionState(generation=tag.generation, sessionId=tag.sessionId,
        wanted=true, transportConnected=true, lastLeaseAckMillis=0)
    val before = VoiceSessionReducer.reduce(initial, VoiceSessionEvent.Tick(30_000))
    assertTrue(before.state.wanted)
    val expired = VoiceSessionReducer.reduce(before.state, VoiceSessionEvent.Tick(30_001))
    assertFalse(expired.state.wanted)
    assertTrue(expired.effects.contains(VoiceSessionEffect.StopEngine(tag.generation)))
    assertTrue(expired.effects.contains(VoiceSessionEffect.StopSession(tag.sessionId)))
}
```

- [x] Run red tests. Implement tagged matching on BOTH sessionId and generation;
  SessionGranted establishes initial ACK timestamp, only monotonic nondecreasing
  LeaseAck matching active intent updates it. Tick elapsed>30_000 stops once.
  Reconnecting clears current media evidence; it emits no RequestJoin/StartEngine
  (SDK reconnect owner). New media callbacks re-establish evidence explicitly.
- [x] Add cases: old session same generation and same session old generation
  ignored; ACK after expiry doesn't revive; ACK time regression ignored; late
  Published after stop ignored; muted/deafened prefs generate one matching effect
  when active and survive reconnect without claiming capture/render evidence.
- [x] Focused red/green then full suite/review/diff/commit task2 files; record proof.

### Task 3: Separate sharing and route observation

**Files:** State/Event/Reducer, AudioRouteObservation.kt, sharing/route tests.
**Consumes:** active session/generation fence. **Produces:** sharing intent
independent of voice and pure route evidence value; no platform routing commands.

Add sharingEnabled:Boolean=false; events EnableSharing, HidePosition.
Effect AddNearbyDemand. EnableSharing only active wanted+sessionId;
HidePosition removes Nearby demand/clears queue, never StopEngine/StopSession.
Full Stop resets sharingEnabled. No event restarts sharing after process death.

Audio types: AudioDeviceRef(id:String,type:String), RouteObservation(device nullable,
provenance nullable, observedAtMillis nullable); provenance STREAM_ROUTING,
RECORDING_CONFIGURATION, COMMUNICATION_DEVICE. AudioRouteState holds desiredInput,
desiredOutput, selectedOutput, observedInput, observedOutput. `withSelectedOutput`
updates selectedOutput only. Unknown observations default nullable; the desired
pair is not automatically supported, and this class does not infer support.

- [x] Write and run red tests:

```kotlin
@Test fun hidePositionKeepsVoiceAndRemovesOnlyNearbyDemand() {
    val active = VoiceSessionState(generation=VoiceGeneration(1),
        sessionId=VoiceSessionId("s1"), wanted=true, sharingEnabled=true)
    val hidden = VoiceSessionReducer.reduce(active, VoiceSessionEvent.HidePosition)
    assertTrue(hidden.state.wanted)
    assertFalse(hidden.state.sharingEnabled)
    assertEquals(listOf(VoiceSessionEffect.RemoveNearbyDemand,
        VoiceSessionEffect.ClearSharingQueue), hidden.effects)
}
@Test fun selectedOutputCannotClaimActualStreamRoute() {
    val headset = AudioDeviceRef("headset", "wired")
    val route = AudioRouteState(desiredOutput=headset).withSelectedOutput(headset)
    assertEquals(headset, route.selectedOutput)
    assertNull(route.observedOutput.device)
    assertNull(route.observedOutput.provenance)
}
```

- [x] Implement transitions, route value types and pure update. Add cases: full
  disconnect clears sharing and keeps no claim; EnableSharing while joining/idle
  has no effect; hide twice is idempotent; COMMUNICATION_DEVICE observation is
  preserved as that provenance, never promoted to STREAM_ROUTING. Test fixtures
  may deliberately disagree desired/selected/observed devices.
- [x] Focused red/green, full suite/review/diff/commit task3 files.

## Verification and commit commands

Task-specific filter uses one of the exact test classes in file map:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests 'ru.sodovaya.volty.domain.voice.VoiceSessionLifecycleTest' --rerun-tasks --no-build-cache --no-configuration-cache
.\gradlew.bat :composeApp:testDebugUnitTest --rerun-tasks --no-build-cache --no-configuration-cache
git diff --check
```

Read actual results generated by that run; no cached XML/1s green claims.
If running a mutation sweep, use audited harness with fresh nonce/clean results
and exact count. This plan doesn't invent a sweep or require one for value types.
Commit staged ownership only; messages `feat: add cancellable voice session core`,
`feat: fence voice media by session lease`, `feat: separate voice sharing intent`.
Final code review can be several narrow Luna checks; primary does whole-core
review rather than assigning a complex context-free review to Luna.

## Self-review / handoff

Scope intentionally implements local pure contract only: backend claims, direct
call signalling, SFU epoch/admission, Android AudioRecord/FGS and Discord UX
remain subsequent stages. No backend readiness claim from these tests.
Five review-focus failures each have owning test; actual issued effects are
asserted. No unbounded runTest loop. New core not wired to production prevents
pretending a fake backend fixes the current app. Diagnostic Android plan follows
after the reducer API is stable and separately validates real devices.

User approved this concrete plan with `+`; execution evidence is recorded above.
Preserved method: primary handles complex tasks, Luna/xhigh handles simple
bounded checks with sufficient context. Astra is not invoked again.
