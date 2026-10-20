# Ride History Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for primary-owned integration; use superpowers:subagent-driven-development only for narrow independent tasks. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the complete Vescape history experience to Compose with Volty's richer source/metric model, favorites, media and export.

**Architecture:** One Decompose component owns selection, requests and a shared time cursor. The existing map renders the historical route; Compose renders summary, charts and drawers. Favorites/media/export build on stable foundation data and retain recording truth.

**Tech Stack:** Compose Multiplatform, Decompose, Koin, SQLDelight, Android map renderer, Canvas, coroutines; no React Native runtime.

**Spec:** `docs/superpowers/specs/2026-10-13-ride-history-rebuild-design.md` (approved).
**Prerequisite:** `2026-10-13-ride-history-foundation.md` Tasks1–8 and acceptance.
**Status:** Approved by user reply «continue»; implementation follows foundation acceptance. The UI is implemented against stabilized foundation contracts, not speculative SQL signatures.

## Global Constraints

- Preserve foundation canonical units, sources, unknowns, gaps and GPL/provenance.
- No second MapView for every preview, chart or list item.
- No long metric-button bar: searchable grouped metric drawer and selected series list.
- One shared cursor/range for map, stats and visible charts. Gaps remain gaps.
- Old rides without GPS remain accessible; their current phone location is never the route.
- Media comes from explicit picker/copied app-owned files, not gallery scanning.
- All strings in values/values-ru; reduce motion and portrait/landscape/system insets supported.
- Unit tests exercise pure policy/components, not fake Compose rendering.
- Gradle workers4/ActiveProcessorCount4, no parallel builds; full suite before each code commit.
- Delegate only simple scoped Luna xhigh tasks; no Astra without a new request.
- Do not stage unrelated voice edits or report deletions.

## Review Focus

1. A long gap and distant sample under cursor must not look like current measured data (Tasks1/3).
2. Selection of rideB while rideA read returns must preserve B and its camera (Task1).
3. Hundreds of cell metrics must not freeze the drawer or charts (Task2).
4. GPS-less old ride/offline preview tiles must remain usable (Tasks3/4).
5. Interrupted media copy/delete and corrupt export must not damage original rides (Tasks6/7).

## Task1 — history component and selection ownership

**Files:** Create commonMain `presentation/history/HistoryComponent.kt`,
`HistoryState.kt`, `HistoryCursorPolicy.kt`, `HistoryRequestOwner.kt`;
commonTest `presentation/history/HistoryComponentTest.kt`, `HistoryCursorPolicyTest.kt`.
**Consumes:** Foundation HistoryReadRepository, HistoryMetricRegistry and model types.
**Produces:**
```kotlin
enum class HistoryUiError { UNAVAILABLE, NOT_FOUND, STORAGE, NO_ROUTE, PERMISSION }
data class HistoryUiState(
  val rides: List<HistoryRide> = emptyList(), val selectedRide: HistoryRide? = null,
  val selectedKeys: List<HistorySeriesKey> = emptyList(), val range: HistoryRange? = null,
  val cursorEpochMillis: Long? = null, val series: Map<HistorySeriesKey, HistorySeries> = emptyMap(),
  val gps: List<HistoryGpsFix> = emptyList(), val stats: Map<HistorySeriesKey, HistoryStats> = emptyMap(),
  val loading: Boolean = false, val error: HistoryUiError? = null
)
interface HistoryComponent {
  val state: StateFlow<HistoryUiState>
  fun openRide(id: String)
  fun addMetric(key: HistorySeriesKey)
  fun removeMetric(key: HistorySeriesKey)
  fun moveMetric(key: HistorySeriesKey, index: Int)
  fun selectTime(epochMillis: Long?)
  fun selectRange(range: HistoryRange)
  fun loadOlder()
  fun back()
}
```

- [x] RED with fake HistoryReadRepository Deferred gates: openA/openB/finishB/
  finishA leaves B; close lifecycle while A pending produces no state afterward.
  Add a stored key returns its series, removes/reorders preserve cursor/range.
- [x] Implement DefaultHistoryComponent(ComponentContext, parentScope,
  HistoryReadRepository, registry, onBack) with one request-generation owner.
  Own all jobs via parent and doOnDestroy; no GlobalScope. Separate list and detail
  error/loading; expand state with typed localized errors rather than exception text.
- [x] Cursor policy returns a usable sample only within the source cadence and
  same continuity segment. Define `historyCursorSample(series: HistorySeries,
  epochMillis: Long, maxAgeMillis: Long): HistorySample?`.
  ```kotlin
  @Test fun distantSampleIsNotCursorValue() {
    val key = HistorySeriesKey("p", "voltage_v")
    val point = HistorySample(HistoryTick(0, 0, 1), key, 63.0)
    val series = HistorySeries(key, listOf(point), emptyList(), false)
    assertNull(historyCursorSample(series, 100000, 1000))
  }
  ```
- [x] Add range/cursor restoration tests on back/fullscreen, empty unavailable
  series, legacy noGPS, tie-break timestamps and paged older-list loading.
- [x] Focused RED/GREEN/full suite; commit component/policy.

Checkpoint: `a0b88673` owns the shared selection/range/cursor and atomic route
read composition. Subsequent preview ownership adds no eager source/series read.
This completes the component task, not production root/recorder activation.

## Task2 — metric drawer for contributors and cells

**Files:** Create `presentation/history/HistoryMetricPickerPolicy.kt`,
`HistoryMetricDrawer.kt`, `HistorySelectedMetrics.kt`;
test `HistoryMetricPickerPolicyTest.kt`.
**Consumes:** Recorded source registry and metric availability, not current BLE device.
**Produces:**
```kotlin
data class HistoryMetricOption(val key: HistorySeriesKey, val title: String, val group: String, val available: Boolean)
fun historyMetricOptions(options: List<HistoryMetricOption>, query: String): List<HistoryMetricOption>
fun reorderHistoryMetric(keys: List<HistorySeriesKey>, key: HistorySeriesKey, index: Int): List<HistorySeriesKey>
```

- [x] RED for search by source/cell name, duplicate aggregate/contributor titles,
  selected unavailable key, reorder clamp, 1000-cell fixture.
  ```kotlin
  @Test fun cellSearchPreservesSourceIdentity() {
    val a = HistoryMetricOption(HistorySeriesKey("A", "cell_v", 0), "Cell 1", "Battery A", true)
    val b = HistoryMetricOption(HistorySeriesKey("B", "cell_v", 0), "Cell 1", "Battery B", true)
    assertEquals(listOf(b), historyMetricOptions(listOf(a, b), "Battery B"))
  }
  ```
- [x] Port selection structure to ModalBottomSheet with search, grouped headers
  Movement/Controllers/Batteries/Cells, source subgroups, availability labels and
  selected-list reorder/remove. Use stable source/metric/cell keys, LazyColumn.
  Search local text state synchronously; include imePadding, preserve editor cursor.
  No per-cell full-trip preload: load selected/visible series only.
- [x] Persist chosen metric presets by source-kind/metric, resolve recorded IDs
  per ride; never auto-rebind BatteryA to BatteryB because their display names match.
- [x] Verify drawer on emulator portrait/landscape/keyboard and no-long-bar layout;
  common tests assert policy only. Full suite, commit drawer.

Checkpoint: `8801f773`; native evidence and limits are recorded in
`../specs/2026-10-18-history-metric-drawer-acceptance.md`. The later persistent
layout checkpoint is documented in
`../specs/2026-10-20-history-metric-presets-acceptance.md`: explicit named save,
apply and delete, exact vehicle/source/kind binding, unknown entries retained as
unavailable, versioned atomic preferences and native process-restart acceptance.
This completes the drawer/preset task; production screen/root activation remains
part of Task5 rather than being implied by this checkpoint.

## Task3 — historical map scene, cursor and animation

**Files:** Create `presentation/history/HistoryMapScene.kt`, `HistoryMapScenePolicy.kt`;
modify common `presentation/map/NavigationMapScene.kt`,
android `presentation/map/PlatformMapLayer.android.kt`;
test `HistoryMapScenePolicyTest.kt`.
**Consumes:** HistoryUiState.gps/range/cursor, earned metrics and markers.
**Produces:**
```kotlin
data class HistoryMapSegment(val coordinates: List<Pair<Double, Double>>, val metricValues: List<Double?>)
data class HistoryMapScene(val segments: List<HistoryMapSegment>, val selectedFix: HistoryGpsFix?, val fitRequest: Long)
fun historyMapScene(gps: List<HistoryGpsFix>, markers: List<HistoryMarker>, cursorEpochMillis: Long?, generation: Long, metric: HistorySeries? = null): HistoryMapScene
```

- [x] RED for two routes divided by GPS_GAP: two segments, no line bridge;
  noGPS yields empty segments/selectedFix=null. Distant cursor point remains null.
  ```kotlin
  @Test fun legacyRideHasNoInventedRoute() {
    assertTrue(historyMapScene(emptyList(), emptyList(), 1000, 1).segments.isEmpty())
  }
  ```
- [x] Extend existing retained map scene with a historical layer input. Render
  route/start/end/gap markers/selected point, color by selected metric only where
  samples earned. Unknown metric segment uses neutral missing-data style.
  Preserve navigation/live/Nearby map ownership; historical overlay cannot alter
  their subscriptions, auto-follow or session state.
- [ ] Port Vescape route-highlight/camera-fit responsibilities with attribution;
  use current map engine animations, panel-aware bounds, one fit per ride change.
  Cursor movement updates marker without fitting camera every frame. Gestures
  temporarily suspend follow; reduceMotion disables highlight/animated fit.
- [ ] Test generation fences old fit, longitude ordering, valid finite coordinates,
  zero-distance route and keyboard/panel inset bounds. Check rendering/animation
  natively at near/far zoom, allgap and offline styles.
- [ ] Full suite/native visual evidence, commit map adapter.

Partial checkpoint: `eaa8b429` implements the neutral historical layer, recent
recorded cursor, loaded-portion endpoints/gaps and fenced camera requests on the
existing retained map. Evidence is in `../specs/2026-10-18-history-map-acceptance.md`.
Metric coloring, full screen/camera integration and the remaining native cases
stay open; the partial adapter commit does not check off those requirements.

Color-overlay checkpoint (2026-10-20): explicit selected-source chart Map action,
earned range legend, bounded indexed coloring above the complete neutral route,
unknown/gap/source fences and explicit clear are now integrated into the composed
home/fullscreen owner. Final4374 tests and native actual colored/neutral edges,
route paging, unknown second pack, font200% and retained MapView evidence are in
`../specs/2026-10-20-history-map-color-acceptance.md`. This supersedes the preceding
metric-coloring-pending statement only. Remaining camera/motion/native/root gates
are still open; Task3 as a whole is not marked complete.

## Task4 — route previews and history list

**Files:** Create `presentation/history/HistoryRoutePreview.kt`, `HistoryRideList.kt`,
`HistoryRideCard.kt`, `data/history/HistoryPreviewCache.kt`,
android `data/history/AndroidHistoryPreviewRenderer.kt`;
test `HistoryRoutePreviewPolicyTest.kt`, `HistoryPreviewCacheTest.kt`.
**Consumes:** Foundation ride pages and bounded route preview query; add
`HistoryReadRepository.routePreview(rideId: String, maxPoints: Int = 128): List<HistoryGpsFix>`
in foundation facade with extrema/segment-safe read and its tests.
**Produces:** Cached preview image + Canvas route fallback, paged ride drawer.

- [x] RED using pure `historyPreviewPath(gps: List<HistoryGpsFix>, width: Double,
  height: Double): List<Pair<Double, Double>>` on zero-area bounds, crossing
  longitude wrap and gaps. Empty input returns empty path, no native map allocation.
- [x] Port Vescape thumbnail/layout/tile-cache approach. Key caches by ride ID,
  data revision, dimensions and style revision; bound disk cache and concurrent
  requests. Reuse approved tile provider/cache, obey attribution/provider terms.
  If a tile fails/offline, route-only fallback remains visible, no broken partial tile mosaic.
- [ ] List rows contain transport/date/distance/duration, preview and selected
  indication; date groups and favorites tabs use stable ride IDs. Empty/error/
  loading/legacy states visible; page20 and on-end trigger only once per cursor.
- [ ] Fake renderer gate tests late image for A cannot populate B, deduplicate tile
  requests, cancel retired reads, cache invalidation after trim/delete. Native
  verify fast long-list scroll without growing MapView count.
- [ ] Full suite/native evidence, commit previews/list.

Partial checkpoint: `073e2696` ports the first GPL-attributed Vescape card/Canvas
UI; `74d22153` preserves the original license bytes. The catalog reads existing
legacy data on API34; evidence is in `../specs/2026-10-18-history-catalog-acceptance.md`.
Visible preview reads and bounded geometry caching continue in the execution
ledger. Source previews use the existing bounded atomic route page with an
explicit incomplete flag when data/segments are omitted; this does not claim a
new full-route SQL summary, tile/image cache, name/distance/date-group/favorite
binding or production root activation.

Image-background checkpoint (2026-10-20): visible-only image ownership, bounded
PNG memory/disk caches and one serialized native OpenGL snapshotter now sit behind
the existing Canvas route fallback. The owner's exact key and parent admission
fence late/stale images. The approved OFM styles/provider are reused with readable
credits; failed/uncached offline requests preserve route-only previews. Native
light/dark/offline/cancellation/font/orientation/back-scroll checks and full4317
tests are recorded in `../specs/2026-10-20-history-preview-images-acceptance.md`.
This supersedes the earlier image-cache-pending statement only. Remaining Task4
items include full row metadata/date groups/favorites, future trim/delete binding
and measured long-list performance; Task4 as a whole stays open.

## Task5 — full history home, summary and fullscreen charts

**Files:** Create `presentation/history/HistoryScreen.kt`, `HistorySummaryPanel.kt`,
`HistoryChartPanel.kt`, `HistoryChartsScreen.kt`, `HistoryNavigationStrip.kt`;
modify `presentation/root/RootComponent.kt`, root screen routing,
both resource string files and `di/AppModule.kt`.
Tests `HistoryScreenStatePolicyTest.kt`; actual Compose checked natively.
**Consumes:** Tasks1–4 and existing graph geometry; Foundation true summary stats.
**Produces:** Complete screens and root navigation retaining one shared component.

- [x] RED on per-field `historyMetricSummary(key, stats): HistoryMetricSummary`
  (`SummaryQuality.EXACT`, `PARTIAL`, `UNAVAILABLE`): unknown energy is not 0Wh.
  Ruling: a single quality for the whole stats object would hide earned extrema
  or incorrectly qualify absent energy; quality belongs to each displayed value.
  ```kotlin
  @Test fun missingEnergyIsUnavailable() {
    assertEquals(SummaryQuality.UNAVAILABLE,
      historyMetricSummary(HistorySeriesKey("pack", "power_w"),
        HistoryStats(null, null, null, false)).energy.quality)
  }
  ```
- [x] Port home composition: same full-screen map, header/back/title, compact
  expandable summary, bottom series stack/navigation/drawers. Port source tokens/
  card shapes/spacing with Volty fonts/branding; add original/source attribution.
  Summary current cursor, selected range and whole-ride stats have distinct labels.
- [x] Charts share time window/cursor, allow pan/pinch and selection, render gaps,
  units and unavailable cards. Stack is lazy; source data loading is bounded.
  Fullscreen charts reuses component state, not duplicate live streams.
- [ ] Animate panel height, drawer, route highlight and data transitions without
  oldride flash; reduceMotion and system/IME insets follow spec. Back closes active
  drawer/fullscreen before leaving; in-progress recording is independent.
- [ ] Native acceptance: reproduce video's home/navigation/detail flow with demo
  GPS fixture; landscape/largefont/keyboard, noGPS legacy, longgap, no series,
  midloading back, repeated ride switching and 20+metric picker.
  Unit tests do not substitute for screenshots/gesture results.
- [ ] Full suite/signed candidate, commit home/fullscreen/routing.

Summary-only checkpoint (2026-10-20): the source-qualified compact/expand panel
and per-field quality/sign policy are implemented and exercised on Android,
including font200% Russian landscape and unknown/partial fixtures. See
`../specs/2026-10-20-history-summary-acceptance.md`. This is a GPL-attributed
adaptation of pinned HistoryStatsBar hierarchy, using Volty's persisted range
stats instead of reduced chart samples. No root activation or completed home,
cursor/whole-ride sections, full chart interactions or signed candidate is claimed.

Recorded-chart rendering checkpoint (2026-10-20): original Kotlin chart projection,
lazy selected-series cards and a fullscreen wrapper reuse the existing detail
owner and metric drawer. Actual overview gap separators preserve earned post-gap
points, and native chart taps update the same cursor later shown on the map.
The acceptance record is `../specs/2026-10-20-history-recorded-charts-acceptance.md`.
This does not close the chart-interaction checkbox: shared pan/pinch window,
accessible timeline/time-axis controls, full Vescape-shaped home/navigation,
remaining source/cursor/whole-ride sections and root cutover still need completion.

Explicit time-window controls (2026-10-20): detail now retains original recorded
bounds for reset, with tested anchor zoom/clamped pan and numeric/stale guards.
Fullscreen charts expose earlier/later, zoom in/out/reset and a named shared-cursor
slider, with clock endpoints and an adaptive wide layout. Native narrowed-window
checks cover all series plus map/range summary. Evidence:
`../specs/2026-10-20-history-time-window-acceptance.md`. Pinch/drag preview with
settled read commit remains required, as do product retention and full home/root
integration. The gesture checkbox above stays open.

Shared gesture checkpoint (2026-10-20): parent-owned transient preview now moves
all visible recorded plots without per-frame reads; release commits once, while
cancel/navigation/newer commands fence old callbacks. Real native pinch, horizontal
pan, ACTION_CANCEL, vertical-list cooperation and shared cursor were checked,
including Russian font200% landscape. See
`../specs/2026-10-20-history-window-gestures-acceptance.md`. This supersedes the
preceding gesture-pending statement only. Complete home/navigation/transitions,
product retention/root cutover and all later tasks remain required.

Home-composition checkpoint (2026-10-20): `HistoryScreen` now renders the shared
map-backed home, bounded compact chart panel, loaded-ride navigation/list sheet,
source-aware summary and metric drawer. Fullscreen charts keep the same native
map/component/window/cursor. Native input/layout/stored legacy and actual slow-read
checks plus final4359 tests are recorded in
`../specs/2026-10-20-history-home-acceptance.md`. This completes the composition
checkbox only. Product root/rotation retention, remaining motion/map/native
matrix, favorites/media/export, recorder activation and signed acceptance remain
required; Task5 and the full rewrite are not marked complete.

## Task6 — favorites, trim and curated media

**Files:** Create `domain/history/HistoryFavorite.kt`, `FavoriteRangePolicy.kt`,
`data/history/HistoryFavoriteRepository.kt`, `HistoryMediaManifest.kt`,
`HistoryMediaImport.kt`, `HistoryMediaRecovery.kt`,
`presentation/history/HistoryFavoritesScreen.kt`, `HistoryTrimPanel.kt`,
`HistoryMediaDrawer.kt`; add SQLDelight `HistoryFavorite.sq`, `HistoryMedia.sq`;
android `presentation/history/HistoryMediaPicker.android.kt`.
Tests `FavoriteRangePolicyTest.kt`, `HistoryMediaImportTest.kt`, `HistoryMediaRecoveryTest.kt`.
Recount migration version after foundation schema; no assumption it remains15.
**Consumes:** Selected ride/range and confirmed GPS; Android Photo Picker/content URI.
**Produces:**
```kotlin
data class HistoryFavorite(val id: String, val rideId: String, val range: HistoryRange, val name: String)
fun clampFavoriteRange(selected: HistoryRange, ride: HistoryRange): HistoryRange
data class HistoryMediaItem(val id: String, val favoriteId: String, val capturedEpochMillis: Long?, val mime: String, val bytes: Long, val sha256: String, val relativePath: String)
interface HistoryFavoriteRepository {
  suspend fun save(favorite: HistoryFavorite)
  suspend fun list(): List<HistoryFavorite>
  suspend fun delete(id: String)
}
```

- [x] RED on range clamp/no empty trim, favorite source unchanged after editing,
  duplicate labels distinct IDs. Test preserved whole ride and selected interval.
  ```kotlin
  @Test fun trimStaysWithinRide() {
    assertEquals(HistoryRange(10, 90), clampFavoriteRange(HistoryRange(0, 90), HistoryRange(10, 100)))
  }
  ```
- [x] Port favorites/list/trim visual interaction; favorite is a reference/range,
  not destructive rewrite of recorded trace. Delete favorite leaves ride intact.
- [ ] Explicit picker imports bounded files to temp app-owned path, computes hash,
  commits manifest with stable ID, atomically publishes final file. At startup
  reconcile pending imports/deletes across filesystem/SQLite. Report failed copy;
  no manifest claiming an absent file. Don't persist arbitrary content URI access
  as permanent truth or scan gallery automatically.
- [ ] Media positions use nearest recorded fix inside SAME continuous segment,
  within30s; unknown capture time retains unpositioned item. EXIF location is not
  fallback. Tests import cancellation/oversize/no permission, corrupt file, process
  interruption, deletion overlap, noGPS/outage and source file later removed.
- [ ] User deletes a ride with favorites/media only after UI confirmation, expected
  cascades visible. Native verify picker/back/gallery lifecycle plus restore.
- [ ] Full suite/migration/native checks, commit favorites/media.

## Task7 — honest export and sharing

**Files:** Create `domain/history/HistoryExport.kt`, `data/history/HistoryExporter.kt`,
`presentation/history/HistoryExportSheet.kt`, android `HistoryShare.android.kt`;
tests `HistoryExportTest.kt`, `HistoryExporterTest.kt`.
**Consumes:** Foundation paged samples/GPS/events, favorites, canonical unit registry.
**Produces:** `exportHistory(rideId: String, format: HistoryExportFormat): HistoryExportResult`
where format=GPX/CSV/JSON and result=Success(relativePath,bytes)/Failed(typedReason).

- [ ] RED on GPS outage producing two `<trkseg>` sections and legacy noGPS yielding
  typed NO_ROUTE for GPX rather than today's location. CSV carries key/unit/source,
  empty value+unknown reason; JSON carries schemaVersion/dataQuality/sign metadata.
- [ ] Streaming writer reads bounded pages, escaping CSV/XML/JSON correctly.
  Write temp, atomically finish, checksum and only then offer Android share intent
  via FileProvider with scoped read grant. Cancel leaves no finished artifact.
  No tokens/credentials/device MAC/global private storage paths in exports.
- [ ] Test same-time samples, nonASCII names, formula-leading labels in CSV,
  invalid value/gap, storage full, cancellation after first page and content grant.
  Parse exported GPX/JSON back and assert point/segment counts, not string looks.
- [ ] Native verify recipient receives readable bounded file, canceled chooser
  does not delete original ride; full suite, commit export.

## Task8 — cutover, visual/device acceptance and final provenance

**Files:** Modify existing graph HistoryDialog navigation and root destinations;
update `docs/third-party/vescape-history.md`, THIRD_PARTY_NOTICES, release notes,
progress ledger and version only after complete acceptance.
**Consumes:** Both plans' tested features; old reader remains for existing data.
**Produces:** One released history experience, not two competing recorders/screens.

- [ ] Verify all approved spec sections map to tasks; original-source commit/notices
  and distribution-source obligations satisfied. No imported resource unlicensed.
- [ ] Route old history entry into new screen, preserve live graph/XY use cases;
  remove obsolete recording activation and auto-prune only when new path active.
  No unnecessary schema deletion; old data still readable and exportable honestly.
- [ ] Fullfresh tests with nonce/start/exactcount and zero stale XML. Migration,
  ARM/x86 signed builds, secret/probe scan and signature/package version checks.
  No claim of complete hardware support from an emulator.
- [ ] Native matrix: small/largeportrait/landscape/largefont/reducemotion,
  recorder offscreen/screenoff/restart/permissions, allgap/noGPS legacy,
  sources2controllers+2packs+cells, rapid ride/metric switches, offline preview,
  favorites trim/import/delete/export. Archive screenshots and measured latency.
- [ ] Physical recording on rider vehicles: continuous and reconnect ride, precise
  time alignment, actual gap rendering, energy/peak completeness, storage growth.
  No BLE writes introduced; compare a capture's accepted samples with stored facts.
- [ ] Record unverified device cases separately. Ship signed APK and commit/push
  only authorized integration with matching source revision and provenance.


## Read-owner preparation checkpoint (2026-10-15)

Foundation Task8 now provides presentation/history/HistoryDetailComponent for
one ride's sources, selected metrics, range/cursor and canonical series/stats.
It also owns explicit source key expansion via recordedKeys. Reuse this owner
when assembling the history screen component; do not recreate its request/cache
lifetime logic. Parent/lifecycle retirement, late A/B reads, per-key/source retry,
sparse metadata, monotonic/legacy viewport bounds and synchronous StateFlow
observer transitions have focused regressions. No product route is activated.

Task1 remains open for catalog pagination, route read ownership, shared cursor
sample policy, back/fullscreen restoration and final screen composition. The
foundation acceptance prerequisite remains in force. No Vescape source/assets
or complete screen port are claimed by this preparation checkpoint.

## Catalog-owner preparation checkpoint (2026-10-17)

Task1 now also has original Kotlin HistoryCatalogComponent alongside the existing
HistoryDetailComponent. It explicitly pages the unified legacy/v2 reader without
preloading sources, metric/cell series, stats or GPS. A single request token owns
refresh/older reads, fences late noncancellable responses and synchronous StateFlow
reentry, and retires with the parent or Decompose lifecycle. Overlapping IDs update
metadata without duplicate rows; failed reads retain displayed rows/cursor for
retry. Repeated continuation cursors report typed storage failure instead of an
implicit loop. Truncated-read evidence remains separate from hasMore pagination.

Ruling: this extends the earlier read-owner preparation against stable foundation
interfaces while native/product activation remains gated. It imports no Vescape
source/assets and does not activate a second history UI. The eventual one parent
history component composes catalog/detail owners; do not duplicate their read
ownership logic. Task1 remains open for parent navigation/selection composition,
route paging, shared cursor policy and back/fullscreen restoration; visual port,
native acceptance and later screen-plan tasks remain required.

## Parent screen and recorded route ownership (2026-10-17)

Task1 now has HistoryComponent/DefaultHistoryComponent composing existing catalog,
detail and new route read owners under one parent/lifecycle. Its HistoryUiState
keeps list/detail/route loading and typed errors independent, with one selected
ride, shared detail range/cursor and Catalog/Ride/Charts navigation. Fullscreen
back preserves metrics/range/cursor; returning to catalog retires pending detail
and route reads. Selection publication is guarded against synchronous reentry;
late old ride/route responses cannot replace a newer selected screen.

HistoryReadRepository.route returns HistoryRoutePage: recorded origin, bounded GPS
page and range markers from one storage snapshot. SqlDelightHistoryStore reads
these inside one database transaction, without schema changes. The unified reader
preserves format precedence; legacy/missing routes do not borrow live coordinates.
Custom stores without atomic route support fail explicitly. Route ownership checks
origin/range/order and stored numeric cursors, preserves equal-time fixes, exposes
retry and truncation/cap status and retains at most8pages of20000fixes by default.
A narrowed range starts a new budget. It never starts location acquisition or
preloads telemetry series. Real SQLite coverage verifies origin/GPS/global and
source marker contents and range/legacy/missing behavior; transaction scope is
reviewed code evidence, not an artificially forced concurrency-race test.

Task1 remains open for cursor-value continuity/cadence policy and final screen
rendering/restoration; Tasks2–8 remain required. These are original Kotlin read
owners, not a claimed Vescape visual/source port or product navigation cutover.
Native recording activation/acceptance and full visual/map/favorites/media/export
requirements remain unchanged. Compose appearance/gestures/animations must still
be verified on Android.

## Shared cursor and read-quality checkpoint (2026-10-17)

HistoryCursorPolicy now returns a recent earned PAST sample on the stable chart
axis within caller-supplied cadence. It never interpolates or picks a future point.
Unknown observations win ambiguous equal-time ties; zero remains earned. Relevant
source/global boundaries block holding a number across a gap, while GPS outages
and other sources' scoped gaps do not erase healthy telemetry. V2 uses monotonic
axis mapping through raw wall jumps; legacy uses its recorded wall timestamps.
An omitted overview tail cannot become an extended cursor value. Invalid key/order/
clock metadata and overflowing age fail closed. Exact stored timestamps remain
usable even at a boundary; holding across a coincident gap is blocked.

Also corrected two observed UI-quality contracts: an empty truncated snapshot does
not prove no route exists, and completing the last catalog continuation clears
resolved page truncation. Earlier cursor-policy-pending status is superseded for
the pure policy only. The actual chart/map renderers still need to consume it.

HistoryState.kt holds the composed state. Ruling: the planned HistoryRequestOwner
helper is superseded by existing catalog/detail/route request tokens plus parent
selection fencing, already exercised by late-response and synchronous-publication
tests. Do not add a second unused request manager. State/navigation/read logic is
implemented; actual visual port, map scene, persistence/native rendering acceptance
and all later plan tasks remain required, with no partial product cutover claimed.
