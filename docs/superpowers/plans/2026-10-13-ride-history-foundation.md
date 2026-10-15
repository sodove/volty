# Ride History Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for primary-owned integration; use superpowers:subagent-driven-development only for narrow independent tasks. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace unreliable ride recording with durable, source-aware GPS/telemetry history while retaining legacy rides.

**Architecture:** A session-owned recording controller feeds one bounded writer from accepted decoded telemetry and GPS. SQLDelight stores detail, gaps and summaries separately; bounded read models support charts and maps. Presentation decisions do not enter BLE callbacks.

**Tech Stack:** Kotlin Multiplatform, coroutines, SQLDelight, Decompose, Koin, kotlin.test/Turbine; Android API26+.

**Spec:** `docs/superpowers/specs/2026-10-13-ride-history-rebuild-design.md` (approved).
**Status:** Approved by user reply «continue»; implementation in progress.
**Next plan:** `2026-10-13-ride-history-screens.md` consumes this plan's interfaces.

## Global Constraints

- Never write Begode FFE1; no new BLE commands, poll changes or protocol changes.
- Preserve canonical domain units/sign; unknown/partial/invalid is not zero.
- SQLite floor 3.19; N.sqm migrates N.db to N+1.db. Recount before migration.
- Queue bounds: 2048 events or 4 MiB; flush 128 events or 1 s, plus stop/switch.
- Detail target 500 ms; source cadence remains earned. Overview 600 points/series;
  detail page 20,000 samples/series; ride page 20 entries.
- No silent old-history deletion; legacy GPS and lost peaks cannot be reconstructed.
- Gradle workers4/ActiveProcessorCount4; no concurrent Gradle builds.
- Strings both values and values-ru, no Android backslash escapes.
- Direct source imports require GPLv3-or-later notices/provenance and compatible dependencies.
- Primary handles cross-module tasks; only simple Luna xhigh tasks may be delegated with full context.
- Preserve pre-existing voice edits and deleted reports; stage only this plan's owned files.

## Review Focus

1. Late old-generation telemetry after vehicle switch must never reach the new ride (Tasks3/7).
2. Stop racing an uncommitted batch must flush once and acknowledge only after commit (Tasks4/5).
3. A backward clock jump must neither reverse point ordering nor create negative energy (Tasks3/6).
4. Adding a historical metric/late read completion must not expose live or another ride's data (Task8).
5. Slow/missing contributors, cell changes and long GPS outage must show gaps without false zeros (Tasks2/6/7).

## Verification recipe for every code task

Focused RED/GREEN: use the exact test class named by the task. Example:
```powershell
$env:GRADLE_USER_HOME='C:/Users/sodovaya/git/volty/.gradle-user'
.\gradlew.bat :composeApp:testDebugUnitTest --tests '*HistoryStatisticsTest*' --rerun-tasks --no-build-cache --max-workers=4 '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=4'
```
Record fresh nonce/start, exact report counts and failure assertions. Before each
code commit run the full suite; also migration verification on DB tasks. Cancel
all test jobs; never advanceUntilIdle through an infinite delayed writer loop.
Use a finite fake clock/ticker, bounded advanceTimeBy and issued/published assertions.
Store evidence in `.superpowers/sdd/2026-10-13-ride-history-rebuild/progress.md`.
Documentation/license inventory steps use file/hash checks, not artificial unit tests.

## Task1 — provenance/license inventory and reproducible old-history failures

**Files:** Create `docs/third-party/vescape-history.md`, `THIRD_PARTY_NOTICES.md`,
`docs/superpowers/specs/2026-10-13-ride-history-field-audit.md`.
Modify tests `presentation/graph/GraphComponentTelemetryTest.kt` and
`data/history/RideTelemetryRecorderTest.kt` under commonTest.
**Consumes:** Existing GraphComponent, GraphMetric, Recorder and their existing test fakes.
**Produces:** Pinned-source inventory, compatibility decision, exact failing contracts.

- [ ] Inventory imported candidates (RecordingCoordinator/TelemetryRepository,
  playback/gpsGaps/route gradients, HistoryOverlay/SessionSheet/TelemetryPanel,
  RouteSparkline and preview tiles), source commit, copyright and each dependency.
  Check runtime/asset licenses against pinned dependency artifacts, not names.
  Record blockers; do not import a blocked resource or claim license compliance.
  Add application GPL/license notices only after the inventory confirms the scope.
- [ ] Reuse the GraphComponentTelemetryTest repository/context fixtures. Persist a
  VOLTAGE series, open the ride while POWER is selected, add VOLTAGE, assert actual
  historical voltage points. Hold loadRide(A) on CompletableDeferred, finish B
  first, release A and assert selectedRideId remains B. Destroy lifecycle finally.
  The test's assertion shape is:
  ```kotlin
  component.onRideSelected("recorded")
  runCurrent()
  component.onMetricAdded(GraphMetric.VOLTAGE)
  assertEquals(listOf(63f, 62f), component.state.value.series[GraphMetric.VOLTAGE]!!.points.map { it.value })
  ```
  The fixture must persist those points before opening; record RED evidence.
- [ ] Extend recorder fixture with two same-bucket POWER samples 4200f then 10f,
  a later bucket and disconnect. Assert stored detail shows the last value while
  the proposed exact peak is 4200f. Keep this as a documented legacy limitation,
  not a failing test left in the production suite.
- [ ] Probe reconnect retained-ring admission, vehicle switch and rapid stop using
  issued appendPoint calls. Label risks unconfirmed unless a fixture reaches them.
- [ ] Keep intended future-contract RED tests in an isolated patch/evidence file
  until Tasks6/8 implement them; do not commit a deliberately failing full suite.
  Commit audit/docs independently after checking only owned files are staged.

## Task2 — source-aware model and metric registry

**Files:** Create commonMain `domain/history/HistoryModels.kt`, `HistoryMetricRegistry.kt`,
`HistoryValuePolicy.kt`, `HistoryStore.kt`; commonTest `domain/history/HistoryModelTest.kt`.
**Consumes:** VehicleData, PackState/ControllerState, BmsReadings/MotionReadings, source known flags.
**Produces:** Shared contracts below; later tasks must use these names/signatures.

```kotlin
data class HistoryTick(val epochMillis: Long, val elapsedMillis: Long, val clockGeneration: Long)
enum class HistorySourceKind { VEHICLE_BATTERY, VEHICLE_MOTION, BATTERY, CONTROLLER }
data class HistorySeriesKey(val sourceId: String, val metricId: String, val cellIndex: Int? = null)
enum class HistoryUnknownReason { UNAVAILABLE, PARTIAL_SOURCE, INVALID_VALUE, GAP, LEGACY }
data class HistorySample(val tick: HistoryTick, val key: HistorySeriesKey, val value: Double?, val unknown: HistoryUnknownReason? = null)
data class HistorySource(val id: String, val kind: HistorySourceKind, val label: String)
data class HistoryGpsFix(val tick: HistoryTick, val lat: Double, val lon: Double, val accuracyMeters: Double, val heading: Double?)
enum class HistoryRideStatus { RECORDING, COMPLETED, INTERRUPTED, LEGACY }
data class HistoryRide(val id: String, val vehicleId: String, val started: HistoryTick, val ended: HistoryTick?, val status: HistoryRideStatus)
enum class HistoryMarkerKind { CONNECTED, BLE_GAP, GPS_GAP, CLOCK_CHANGE, OVERFLOW, MANUAL_STOP, VEHICLE_SWITCH, INTERRUPTED }
data class HistoryMarker(val tick: HistoryTick, val kind: HistoryMarkerKind)
data class HistoryRange(val startEpochMillis: Long, val endEpochMillis: Long)
data class HistoryBatch(val rideId: String, val samples: List<HistorySample>, val gps: List<HistoryGpsFix>, val markers: List<HistoryMarker>)
data class HistoryCursor(val epochMillis: Long, val stableId: String)
data class HistoryPage<T>(val items: List<T>, val next: HistoryCursor?, val truncated: Boolean)
data class HistoryStats(val minimum: Double?, val maximum: Double?, val energyWh: Double?, val complete: Boolean)
interface HistoryStore {
  suspend fun begin(ride: HistoryRide, sources: List<HistorySource>)
  suspend fun ride(rideId: String): HistoryRide?
  suspend fun append(batch: HistoryBatch)
  suspend fun finish(rideId: String, ended: HistoryTick, status: HistoryRideStatus)
  suspend fun recoverInterrupted(): Int
  suspend fun rides(before: HistoryCursor?, limit: Int = 20): HistoryPage<HistoryRide>
  suspend fun sources(rideId: String): List<HistorySource>
  suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int = 20000): HistoryPage<HistorySample>
  suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?, limit: Int = 20000): HistoryPage<HistoryGpsFix>
  suspend fun markers(rideId: String, range: HistoryRange): List<HistoryMarker>
  suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats
  suspend fun delete(rideId: String)
}
```

- [ ] RED for finite/range checks, legitimate zero and incoherent known flags:
  ```kotlin
  @Test fun partialPowerIsUnknown() {
    assertEquals(null, historyValue(4200.0, known = false))
    assertEquals(0.0, historyValue(0.0, known = true))
    assertEquals(null, historyValue(Double.NaN, known = true))
  }
  ```
  Define `historyValue(value: Double, known: Boolean): Double?` in HistoryValuePolicy.
- [ ] Implement registry descriptors with canonical unit, display conversion/sign,
  source kind and grouped labels; all current graph metrics plus each battery cell.
  Energy consumption sign is registry metadata: BMS canonical positive-charge
  power integrates with multiplier -1; controller input power follows its earned
  domain convention. Do not reuse a renderer's displaySign as stored truth.
  Persist explicit contributor IDs scoped to the ride; names are not IDs.
- [ ] Test aggregate versus contributor keys never collide, and cells from packA/B
  stay distinct despite equal cellIndex. Add source availability, partial quality
  and no-known-sample fixtures; don't infer controller known flags from nonzero.
- [ ] GREEN and full suite; commit model/registry.

## Task3 — pure recording lifecycle

**Files:** Create `domain/history/RideRecordingPolicy.kt`, `RecordingClockPolicy.kt`;
test `domain/history/RideRecordingPolicyTest.kt` and `RecordingClockPolicyTest.kt`.
**Consumes:** Task2 HistoryTick/HistoryRide; connection identity from Task7 adapter.
**Produces:**
```kotlin
data class RecordingIntent(val ride: HistoryRide? = null, val connectionGeneration: Long? = null, val manuallyStopped: Boolean = false, val linkConnected: Boolean = false)
sealed interface RecordingInput {
  data class Connected(val vehicleId: String, val generation: Long, val tick: HistoryTick) : RecordingInput
  data class LinkLost(val generation: Long, val tick: HistoryTick) : RecordingInput
  data class Stop(val tick: HistoryTick, val generation: Long? = null, val rideId: String? = null) : RecordingInput
  data class ExplicitDisconnect(val tick: HistoryTick, val generation: Long? = null) : RecordingInput
  data class Start(val vehicleId: String, val generation: Long, val tick: HistoryTick) : RecordingInput
}
data class RecordingTransition(val intent: RecordingIntent, val markers: List<HistoryMarker>, val finish: HistoryRide?, val begin: HistoryRide?, val finishedMarkers: List<HistoryMarker> = emptyList())
fun recordingTransition(current: RecordingIntent, input: RecordingInput, newId: () -> String): RecordingTransition
```

- [ ] RED using a connected state, link loss and same-vehicle reconnect: begin.id
  remains unchanged and there is one BLE_GAP marker, not a second Begin effect.
  Stop->Connected leaves ride null; Start explicitly re-arms. New vehicle produces
  Finish(old)+Begin(new). A late LinkLost(oldGeneration) has no effect.
- [ ] Implement reducer and clock mapping with independent record IDs and monotonic
  event order. `clockBoundary(previous: HistoryTick, next: HistoryTick): Boolean`
  returns true for generation change/backward epoch; integration cannot cross it.
  Ruling during implementation: connection generations increase across sessions;
  a finished connection remains a tombstone against late Connected events.
  `markers` belong to the active/new ride and `finishedMarkers` to `finish`, so
  VEHICLE_SWITCH cannot be accidentally appended to the new ride. linkConnected
  separates retained ride participation from admission of BLE telemetry.
  Source-owned terminal events carry generation; null is reserved for deliberate
  global UI intent. A stale source Stop/ExplicitDisconnect cannot finish a new ride.
  A recording-owned Stop also carries rideId: two recordings can share the same
  BLE generation, so connection generation alone is insufficient for that command.
  Clock continuity additionally rejects wall/monotonic delta skew above1000ms,
  including forward jumps; elapsed time governs integration, not wall time.
  Chart/range coordinates use `historyAxisEpochMillis(ride.started, capturedTick)`:
  start epoch anchored to elapsed progression. Original epoch remains available
  for calendar/export. Different monotonic origins are rejected and require a
  new interrupted/recovered ride. Store range filters convert axis coordinates
  to elapsed within ride.started.clockGeneration; do not sort charts by raw epoch.
  ```kotlin
  @Test fun backwardsClockIsBoundary() {
    assertTrue(clockBoundary(HistoryTick(2000, 100, 1), HistoryTick(1000, 200, 1)))
  }
  ```
- [ ] Test no sample before ride.started/generation admission, duplicate connection
  callbacks, partial link loss and explicit last-link disconnect separately.
- [ ] GREEN/full suite, commit policy. Policy itself starts no background jobs.

## Task4 — SQLDelight schema and legacy-safe repository

**Files:** Create `RideHistoryV2.sq`, `RideHistorySource.sq`, `RideHistorySample.sq`,
`RideHistoryGps.sq`, `RideHistoryMarker.sq`, `RideHistoryBucket.sq` under existing db path;
create `data/history/SqlDelightHistoryStore.kt`, `LegacyHistoryReader.kt`;
test `data/history/SqlDelightHistoryStoreTest.kt`.
Expected migration `14.sqm`, snapshot `15.db`: verify actual max first (currently13.sqm/14.db).
**Consumes:** Task2 HistoryStore/models; existing VoltyDatabaseProvider/SqlDriver factory.
**Produces:** Transactional implementation; canonical values and LEGACY reader.

- [ ] RED on a real test SqlDriver (reuse SqlDelightRideHistoryRepositoryTest setup):
  begin->append->finish->read yields exact key/value/ticks/unknown; foreign source
  IDs do not merge. Induce append transaction failure; no partial rows published.
- [ ] Create v2 tables with integer epoch/elapsed/generation, stable sequence tie
  breaker, nullable values/typed unknown, indexed ride/source/metric/time.
  Preserve old tables; read legacy rows lazily, invert old displaySign exactly once.
  Canonical SQL shape:
  ```sql
  CREATE TABLE HistorySampleV2 (
    rideId TEXT NOT NULL, sequence INTEGER NOT NULL, sourceId TEXT NOT NULL,
    metricId TEXT NOT NULL, cellIndex INTEGER NOT NULL DEFAULT -1,
    epochMillis INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL,
    clockGeneration INTEGER NOT NULL, value REAL, unknownReason TEXT,
    PRIMARY KEY (rideId, sequence), FOREIGN KEY (rideId) REFERENCES HistoryRideV2(id)
  );
  CREATE INDEX HistorySampleV2_series_time ON HistorySampleV2(rideId, sourceId, metricId, cellIndex, epochMillis, sequence);
  ```
  `HistoryRideV2` comes from RideHistoryV2.sq; add unique source registry and ordered
  GPS/marker tables. Keep same-time rows deterministic; sequence cursor tie-breaks.
- [ ] Test 21 rides page20+1, exact page boundary at equal timestamps, deletion
  transaction, invalid range/limits, unknown rows and schema recovery statuses.
  `recoverInterrupted` marks only RECORDING and uses last committed tick.
- [ ] Test migration against existing14.db; compare old RideRow/RidePointRow rows
  before/after byte-for-value. No GPS synthesis or extrema reconstruction.
- [ ] Migration verification/full suite; commit schema/repository/legacy adapter.

## Task5 — bounded writer with flush/stop commit ordering

**Files:** Create `data/history/HistoryWriter.kt`, `HistoryIngress.kt`, `HistoryWriterDiagnostics.kt`;
test `data/history/HistoryWriterTest.kt`.
**Consumes:** HistoryStore and ordered HistoryBatch.
**Produces:**
```kotlin
enum class HistoryOffer { ACCEPTED, RETIRED, OVERFLOW }
interface HistoryIngress { fun offer(generation: Long, batch: HistoryBatch): HistoryOffer }
interface HistoryWriter : HistoryIngress {
  suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>)
  suspend fun flush()
  suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String? = null)
  suspend fun delete(rideId: String)
  suspend fun close()
}
```

- [ ] RED with a fake store whose append waits on CompletableDeferred: start
  finish, assert finishReceipt not completed and store.finish not issued; release
  commit, assert append once then finish once. Assert on calls/order, not delays.
- [ ] Implement one actor/serialized queue and independent IO scope; ingress offer
  never suspends. Reject generation mismatch before queue insertion. Flush trigger
 128 events/1s, bytes/events bounds as spec; Stop drains accepted batches.
  Control messages cannot be discarded by a full sample queue. On overflow record
  skipped count/time and gap/partial summary; disk failure retires ingestion and
  exposes failed persistence. Never label unsuccessful data Saved.
- [ ] Tests: queue2049 events and >4MiB batch, old generation during finish,
  concurrent close/stop/delete, store failure/retry without duplicate append,
  pending partial batch survives controller stop. Finite fake ticker prevents runTest hang.
- [ ] GREEN/full suite, commit writer.

## Task6 — exact summaries and peak/gap-preserving projection

**Files:** Create `domain/history/HistoryStatistics.kt`, `HistoryOverview.kt`, `HistoryContinuity.kt`;
test `HistoryStatisticsTest.kt`, `HistoryOverviewTest.kt`, `HistoryContinuityTest.kt`.
**Consumes:** Accepted Task2 samples BEFORE500ms detail gate; markers, cadence/clock facts.
**Produces:**
```kotlin
fun historyStats(samples: List<HistorySample>, markers: List<HistoryMarker>, consumptionMultiplier: Double? = null): HistoryStats
fun historyOverview(samples: List<HistorySample>, markers: List<HistoryMarker>, maxPoints: Int = 600): List<HistorySample>
fun nearestHistorySample(samples: List<HistorySample>, tick: HistoryTick, maxAgeMillis: Long): HistorySample?
```

- [x] RED for narrow peak and outage; sample/tick/key helpers use Task2 constructors:
  ```kotlin
  @Test fun overviewKeepsSpike() {
    val key = HistorySeriesKey("battery", "power_w")
    val input = (0..999).map { i -> HistorySample(HistoryTick(i*100L, i*100L, 1), key, if(i==501) 4200.0 else 10.0) }
    assertEquals(4200.0, historyOverview(input, emptyList(), 600).maxOf { it.value!! })
    assertEquals(4200.0, historyStats(input, emptyList()).maximum)
  }
  ```
- [ ] Port selected Vescape algorithmic responsibilities with provenance, adapt
  overview to min/max envelope instead of uniform points when spikes would vanish.
  Split segments BEFORE projection; preserve boundary and endpoint evidence.
  Require a single HistorySeriesKey in each stats/projection call. Compute energy
  for power_w using registry consumption multiplier with monotonic usable
  adjacent samples and no gap; unknown interval yields complete=false.
  A missing consumptionMultiplier leaves energyWh unknown; peak/min do not need it.
- [ ] Test500ms detail gate versus full-stream peak, partial/missing zero, backwards
  time, legitimate regen, unequal cadence, cell count change, last point and limit.
  Persist summary buckets transactionally with accepted recording batches. Reads
  aggregate buckets and boundary detail rather than recalculating from overview.
- [x] GREEN/full suite, commit summary/projection (3620 tests, actor + real SQLite ACK-loss verification).

Task6 checkpoint (2026-10-13): pure statistics/continuity/overview and streaming
minute summaries plus the 500 ms detail envelope are implemented. Summary snapshots,
detail and immutable payload receipts share one transaction; migration16.sqm and
snapshot17.db preserve legacy data. Source/clock/slot admission, clipped-range reads,
summary-only recovery and mutable bucket retry identity have real SQLite tests.
The writer bounds and copies summary packets as well as detail. The recording projection is connected to the actor behind explicit projectRecording
opt-in. Forked state is installed only after ACK; retries retain exact prepared payload.
Captured-prefix draining keeps controls responsive under new arrivals. Runtime
activation remains Task7; no Vescape source/screen transfer has been completed.
## Task7 — recording owner, full-stream adapter and GPS service demand

**Files:** Create `data/history/RideRecordingController.kt`, `HistoryTelemetryAdapter.kt`,
`HistoryGpsAdapter.kt`, `HistoryServiceOwnership.kt`; modify `di/AppModule.kt`,
`data/ble/KableBmsRepository.kt`, `domain/location/RideLocation.kt`,
`androidMain/kotlin/ru/sodovaya/volty/service/MonitoringService.kt`,
`androidMain/kotlin/ru/sodovaya/volty/data/location/AndroidRideLocationRepository.kt`,
`androidMain/AndroidManifest.xml`;
test `data/history/RideRecordingControllerTest.kt`, `HistoryTelemetryAdapterTest.kt`.
**Consumes:** Tasks2–6, BmsRepository.activeVehicleData, accepted decoded funnel,
RideLocationRepository.setDemand/history consumer, app-owned lifecycle.
**Produces:** Public controller `startRecording()`, `stopRecording()`, `state: StateFlow<RecordingIntent>`.

- [ ] RED on contributor adapters: packA cell0=4.0, packB cell0=3.9, partial motion
  power4200/hasPower=false; assert exact source IDs and unknown, no aggregate duplication.
- [ ] Feed a nonblocking offer from the EXISTING serialized decoded consumer, after
  domain acceptance; StateFlow alone cannot promise full-stream extrema because
  it conflates. Hook is not IO and does not alter decoder/poll/BLE writes. Capture
  actual contributor update identity/timestamp, not resampling stale snapshots.
- [ ] Own reducer/controller outside Kable/UI. Add LocationConsumer.HISTORY_RECORDING.
  GPS collector admits earned fixes and emits GAP/status on denied/searching;
  drop stale/mismatched generations, reject bogus/nonfinite coordinates.
  Start/stop manage service retention and GPS demand independently of Nearby/map.
  MonitoringService currently declares connectedDevice only. Add location to its
  declared capabilities and select runtime foreground types explicitly: connectedDevice
  for telemetry, location only with admitted GPS demand/permission. Never start
  both types by default and thereby break telemetry when GPS is denied. Verify
  Android34+ foreground-type enforcement and startup from allowed lifecycle.
  Example actual service contract:
  ```kotlin
  location.setDemand(LocationConsumer.HISTORY_RECORDING, true)
  // Finish owns the accepted queue before demand is removed.
  writer.finish(generation, stoppedTick, HistoryRideStatus.COMPLETED)
  location.setDemand(LocationConsumer.HISTORY_RECORDING, false)
  ```
- [ ] Test same-vehicle reconnect keeps ride, manual stop blocks auto-restart,
  explicit disconnect ends once, vehicle switch retires old events, denied GPS
  still persists telemetry, UI close/Nearby leave does not stop, process recovery
  ends at last committed tick. Demo recordings explicitly marked synthetic.
- [ ] Activate new writer behind one runtime choice; disable old recorder for that
  mode to avoid duplicate recording. Preserve legacy reader and old graphs until
  Task8 adapter works. No parallel unbounded shadow writer.
- [ ] GREEN/full suite/native service demand check, commit integration.

Task7 checkpoint (2026-10-14): the app-independent controller and pure GPS
admission helper are implemented. Controller state carries RecordingIntent plus
an observable demand failure; execute accepts typed start/stop/link events. Writer
recording generation remains stable across BLE reconnects. A per-admission gate
closes intake and waits for all reserved sends before durable finish; stale ride,
BLE generation, source and clock inputs are rejected. Delayed controls end no
earlier than the latest reserved point or lifecycle marker. GPS can participate
while BLE is absent. Request-demand errors leave telemetry ownership intact.
Real SQLite integration checks reconnect, GPS participation and durable stop.
Native GPS observer/service ownership, contributor field freshness/source capture,
production DI activation and retirement of the old recorder are still pending.
Task7 source-observation checkpoint (2026-10-14): canonical typed observations and
an attributed contributor adapter are implemented. JBD emits every decoded frame
with only its fresh field group; shared receipt time preserves peaks/counts without
inventing intervals. Raw cell indices/values, typed errors, sensor payload boundaries
and disappearing indices are tested through the real writer/store. Optional decoder
observer failures are counted and contained. JBD error-status replies preserve the
old live snapshot and emit unavailable fields. The observer remains unbound in
production; other protocol observers and the owned-session BLE funnel remain open.
Qualification and the zero-cell retraction are recorded in
`docs/superpowers/specs/2026-10-14-ride-history-source-freshness.md`.

Further Task7 checkpoint (2026-10-14): JBD summaries now require the declared
whole-pack layout, and ANT status observations qualify individual payload fields
without borrowing CRC/trailer bytes. Plain VESC emits fresh local fields per frame;
SETUP CAN-summed currents/power still require separate aggregate-source attribution.
GET_VALUES rotor counts cannot earn kilometres or seed history's distance baseline.
These decoder milestones do not activate recording. Remaining protocols, accepted
source/session binding, derived/aggregate scope, GPS, foreground ownership, DI and
legacy recorder retirement keep Task7 open.

Latest Task7 decoder checkpoint (2026-10-14): Daly now separates UART numeric
groups, verifies history header/declarations, preserves physical cell positions,
and fixes the live/domain current polarity. Multi-fragment extrema need poll-round
provenance; the unchanged polling still does not request the 0x94 declaration.
Begode controller observations separate live/odometer/motion families and use the
existing accepted-frame gates/calibration/latches. The real ET Max fixture covers
114 controller observations. Smart branches, synthetic pack binding and a qualified
cross-frame voltage/current product remain open. These are decoder milestones,
not activation or new phone acceptance.

Receipt-boundary checkpoint (2026-10-14): optional native capture now stamps every
decoded observation with one immutable session/notification/time receipt, preserves
completion/coalesced timing, checks attempt ownership and closes before teardown.
Clock/callback failures preserve live decoding. The production repository still
passes the null default: source/role and recording-generation binding plus the
queued-packet session fence are the next integration work, not completed by this seam.

## Task8 — bounded read facade and repair historical graph admission

Task7 checkpoint (2026-10-15): native capture is now optionally wired through the
attempt-owned shared queue and recording capability, with bounded ingress and
decoded receipt-loss qualification. Startup configuration seals before connection
preparation. JK frame-local observations qualify metadata, indices and temperature
coverage; gateway controller observations validate forwarded identity and separate
fresh replies from live overlays. Unknown native identity on mixed local/CAN links
stays partial. DI/source planning still leaves the runtime hook unconfigured; hosted
BMS age, setup-failure owner qualification, calibrated aggregate/auxiliary/synthetic
roles, GPS/foreground ownership and legacy retirement keep Task7 open. These
checkpoints do not enable new recording or new screens.

**Files:** Create `domain/history/HistoryReadRepository.kt`, `data/history/DefaultHistoryReadRepository.kt`;
modify `presentation/graph/GraphComponent.kt`; tests `HistoryReadRepositoryTest.kt`,
`presentation/graph/GraphComponentTelemetryTest.kt`.
**Consumes:** HistoryStore and projection; old graph compatibility.
**Produces:**
```kotlin
data class HistorySeries(val key: HistorySeriesKey, val samples: List<HistorySample>, val markers: List<HistoryMarker>, val truncated: Boolean, val origin: HistoryTick? = null)
interface HistoryReadRepository {
  suspend fun rides(before: HistoryCursor? = null): HistoryPage<HistoryRide>
  suspend fun ride(rideId: String): HistoryRide?
  suspend fun sources(rideId: String): List<HistorySource>
  suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int = 600): HistorySeries
  suspend fun gps(rideId: String, range: HistoryRange): HistoryPage<HistoryGpsFix>
  suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats
}
```

- [ ] Bring Task1 RED patches into real suite: adding metric reads the stored
  series, late A cannot replace B, live samples cannot enter history.
- [ ] Implement generation-fenced read facade and historicalGraph source path.
  Metric changes load by recorded key, not latestSamples. Keep canonical sign until
  Graph display mapper; legacy adapter sign correction is explicit. Mark unknown
  summary stats unavailable instead of calculating fake exactness from old detail.
- [ ] Test empty/legacy/unavailable metric, backwards/zero-width range, loading/error,
  equal-time page cursor, repeated adds and rapid close. Cancellation never republishes.
- [ ] Load2h multi-controller/multi-pack/cells fixture and report sample/read limits,
  queue bound and memory/time measurements; do not claim all-day reads without data.
- [ ] GREEN/full suite/migration/signed candidate, commit facade and historical fix.

## Foundation acceptance and handoff

- [ ] Evidence for every Review Focus item and saved fixture, source notices verified.
- [ ] Confirm old RideRow/points retained, no automatic prune50 in new path.
- [ ] Verify new route requires no Nearby account and no BLE write changes.
- [ ] On emulator/native fixture: background recording, stop receipt, restart, GPS denial,
  interrupted process and reopened historical graph. Physical multi-device sample
  cadence/foreground behaviour remains phone acceptance, not unit-test inference.
- [ ] Update progress ledger with commits, fresh test counts, migration and APK hashes.
  Start screen plan only against these stabilized interfaces and reviewed ownership.

Task8 read checkpoint (2026-10-15): bounded paged v2 overview and the v2-only
HistoryReadRepository facade are implemented, without DI activation. Default
reads cap raw pages at 20000 rows/eight pages and plotted output at 600, reporting
an omitted tail separately from thinning. Recorded source/metric/index admission,
canonical signs, independent store stats, same-time cursors, malformed pages and
in-flight cancellation are tested. GPS accepts a continuation cursor. Real SQLite
covers the 20000-row page edge and a synthetic two-hour multi-source fixture.
Legacy unified reads/pagination, component admission/loading/errors and isolated
memory/native performance acceptance still keep Task8 open. Existing Task1 graph
cache/generation regressions were already fixed; they are not an open rework.

Task8 compatibility checkpoint (2026-10-15): the old-format read interface and
unified SQL catalog/format router are implemented and verified on real SQLite.
Legacy queries filter metric/range before their 20000-row budget; they preserve
wall-only/null-origin semantics and report no invented GPS or exact summary.
The shared catalog excludes v2-owned duplicate IDs before pagination; unavailable
v2 data does not borrow old rows. Both old/new fractional cursor and end metadata
are tested, including the SQLDelight UNION null-inference regression. No runtime
activation or UI route replacement yet. Component admission/loading/errors and
native performance/activation acceptance still keep Task8 open.


Task7 source-registry checkpoint (2026-10-15): the recording controller rotates
on a changed source ID/kind registry, even for the same vehicle/connection, and
writes SOURCE_CHANGE to the finished ride. Label/order changes keep the existing
recorded metadata. Omitted active registry and explicit empty GPS-only registry
have distinct meanings. Retired captured packets and delayed transition bounds
are tested. Runtime source identity/calibration planning, readiness, GPS/service
ownership and production activation remain open; this checkpoint enables none.


Task7 GPS-observer checkpoint (2026-10-15): app-owned HistoryGpsRecorder observes
only an owned ride and continues across BLE loss. It qualifies acquisition time,
records fixes once, emits GPS_GAP on unavailable/expired position and uses a finite
one-shot expiry even without another provider update. Old/stale provider callbacks,
retired rides, cancellation and optional clock/writer failures have regressions.
Failure qualification uses recording identity; writer failure is latched before
reservation release. This does not activate DI/native GPS demand or foreground
service. Provider-source persistence is also still pending: the approved design
asks for it, but the current HistoryGpsFix/row contract has no provider field.


Task7 provider-provenance checkpoint (2026-10-15): nullable GPS/NETWORK/PASSIVE
provider facts now pass through the adapter, GPS row and read/receipt paths. Old
rows remain unknown. Migration17.sqm upgrades committed17.db to generated18.db.
All-unknown-provider batches keep their exact v1 fingerprint; known-provider
batches use a distinct v2 encoding. Native source classification, request cadence,
permissions, services and DI activation are unchanged. Source planning/calibration/
alias readiness and runtime service/recorder ownership remain open.


Task7 location-demand policy checkpoint (2026-10-15): HISTORY_RECORDING is an
independent consumer and selects the existing zero-distance navigation request.
MAP/Nearby removal preserves history ownership; removing the last history owner
retires old callbacks. Native permission/service admission and actual recording
runtime binding remain open. No new recorder or native location demand activated.


Task7 plain-VESC derived-source checkpoint (2026-10-15): successful decoded values
replies now emit an optional distinct BATTERY/DERIVED source alongside controller
facts. Canonical signs, fresh gauge, SETUP partial current/power, known zero,
missing rail and disabled/invalid-frame guards are covered. A real writer/store
fixture preserves two source IDs and coalesced extrema without invented energy.
No native commands/cadence or production DI activation changed. Source planning,
startup readiness, aliases/joins, gateway capture and GPS/service ownership remain
open; this checkpoint does not close Task7 or foundation acceptance.


Task8 recorded-key checkpoint (2026-10-15): read/store contracts now expose
recordedKeys(rideId, sourceId, limit) with explicit truncation. Metadata combines
detail and summary-only series, preserves sparse cell indices and includes
recorded unknown-valued facts. Source-kind/metric/index admission precedes the
SQL cap; duplicate detail/summary keys consume one slot. Legacy exposes only
recognized scalar keys, with exclusive v2 ownership retained. This enables the
source-aware drawer without numeric trace preloading or current-vehicle guesses.
No new recorder, screen or DI route is activated by this checkpoint.


Task8 detail-owner ruling (2026-10-15): implement the read-only
presentation/history/HistoryDetailComponent against the stabilized repository,
without activating a route or starting the screen transfer. It owns one selected
ride, recorded sources, selected keys, viewport range/cursor and per-key stored
series/stats. Parent scope and Decompose destruction retire reads. Ride/range
replacement and key removal fence even reads that ignore cancellation. Duplicate
adds and unchanged ranges do not reread; failures are typed and isolated per key.
Catalog, route paging, metric drawer rendering and native acceptance remain open.
This factors the planned component read ownership out of screen work; the full
foundation acceptance prerequisite still applies to screen/native activation.

The detail owner also exposes explicit source expansion through recordedKeys,
with ride-owned metadata jobs independent of viewport-owned numeric jobs.
Changing a viewport preserves an expanded source's pending key read; switching
rides retires it. Sparse keys, read truncation and source-specific retry errors
remain explicit. The injected HistoryTick must share the recording clock for an
unfinished v2 ride; wall-only time cannot determine its monotonic viewport end.


Task7 direct-source-plan checkpoint (2026-10-15): optional native ingress now has
a concrete default for direct non-aliased JK/JBD/ANT/Daly, plain VESC and Begode.
Stable physical/role/branch IDs exclude labels/global slots and preserve native
calibration. Configuration lookup is shared with actual protocol construction.
Generated plans are exercised by owned funnel and real VESC/SQLite integration.
Gateway/alias/unqualified shapes require their own reviewed plan and are rejected
by this default, rather than pretending coverage. No runtime recorder activation
or wire/cadence change. Whole-vehicle startup/readiness, gateway/alias admission,
aggregates and native location/service/DI/legacy retirement still keep Task7 open.


Task7 preparation checkpoint (2026-10-16): optional ingress preparation receives
its immutable source plan, and ConnectionSession awaits it after physical connect
before observation/handshake launch. Attempt readiness is once-only and fenced
against close, retirement and cancellation. Ordinary failure preserves live decode
and routes actual lost facts through receipt-loss qualification. Existing callers
without a preparation hook retain prior behavior. This is the startup seam only:
whole-vehicle lifecycle/registry coordination, bounded startup policy, native
clock/location/service binding, aliases/gateway/aggregates and final DI/legacy
retirement still keep Task7 and product activation open. Real CCCD/phone behavior
must be verified natively; common tests do not claim that acceptance.


Task7 connection-owner checkpoint (2026-10-16): `HistoryRecordingConnections`
coordinates the actual controller with a frozen complete vehicle registry and
identity capabilities for selections and individual physical attempts. First
ready awaits begin, additional ready links preserve admission, last loss opens
the vehicle BLE gap, stale/foreign events cannot affect a successor, and manual
stop stays latched. `RecordingInput.Selected` retires an incompatible current
ride immediately, including GPS, without starting an unconnected replacement;
same-plan replacement remains a reconnect. The selection action carries the
actual controller generation, including when intermediate selections never
connected. This owner must exclusively issue lifecycle commands for its controller.
Native Kable installation/retirement callbacks, shared native clock/recovery,
source-specific interruption quality, bounded startup, service/location ownership,
alias/gateway admission, legacy retirement and final activation remain open.


Task7 optional native binding checkpoint (2026-10-16): managed ingress freezes the
complete installed spec/source registry, prepares fresh protocol attempts through
the common connection owner and retires exact native protocol identities. Initial
and restored plans, drop/failure, replacements, explicit disconnect and partial
removal hooks are outside the BLE session lock. External/manual ingress remains
default; production DI still supplies no new recorder. Unsupported/guest/demo plans
retire old real GPS and cannot auto-start an unqualified replacement. Native drop
callbacks additionally need a session predicate rechecked under the native drop
lock; accepted explicit disconnect must finish history despite caller cancellation.
Common tests and repository seams do not prove physical BLE/service acceptance.
Native shared clock, bounded startup, foreground/location, full-funnel retirement
and per-source gaps, aliases/gateways/aggregates, manual UI/legacy retirement and
performance still gate activation.
