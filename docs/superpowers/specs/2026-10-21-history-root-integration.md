# History root integration — 2026-10-21

The production graph toolbar now opens the new History destination. The live
graph and comparison UI remain available. History no longer needs its debug
activity to be reachable. This is a UI/read-path cutover; recording activation
and final release acceptance are separate, unfinished gates.

## One map and one selected recording

Root keeps its existing `PlatformRideMapLayer` and native `ride` cache key.
HistoryScreen's external-map mode publishes recorded geometry into that host
instead of mounting a second MapView. Catalog and fullscreen charts stay opaque.
The host rejects other publishers, other viewport generations and live
navigation/location geometry. Root additionally qualifies every displayed frame
against the current recording's full storage identity and selected range.
Loading or a changed selection gives an empty scene, never the live ride's fix,
trail or Nearby markers.

Each history viewport receives a monotonically increasing root-local generation.
Reopening the same ride therefore does not reuse an already-consumed camera fit
command. Fit padding includes the actual safe drawing insets because the root
map is outside HistoryScreen's safe area. The existing history panel renders the
required map credits; the root renderer suppresses its duplicate credit line
while history is active. Other map consumers retain their existing attribution.

`PreparedHistoryReadRepository` gates catalog and direct-ride admission on the
shared recording clock's preparation. Failed or canceled preparation cannot
issue those reads. Clock recovery is idempotent and does not create a writer.
The component's existing subsequent detail reads use the admitted selection.
Unclosed legacy ranges continue to use persisted bounds without borrowing today's
clock or inventing an end timestamp.

History navigation is a normal serialized Decompose leaf. Detail Back returns
to its catalog, catalog Back reveals the retained graph, and a sole restored
History entry has the same home fallback as Graph/Settings. System and toolbar
Back share that root fallback.

## Verification

- h593: three new map-host tests failed at the unimplemented ownership/selection
  operations. h594: those passed; the new history map-host policy test failed
  because a disconnected history screen did not mount the root map.
- h595: 34 focused map-host, root-navigation and map-host-policy tests passed.
- h596 was a fixture compilation error (missing HistoryPage.truncated), not a
  behavioral RED. h597 then ran seven tests with four expected failures:
  three preparation contracts and one stale viewport contract.
- h598: all 38 focused tests passed and a debug APK was assembled/installed.
- h599: the sole-restored-History fallback assertion failed before the root
  Back policy was extended. h600 included its GREEN result in the full run.
- h600: 4531 tests in 474 fresh XML reports, zero failures/errors/skips/stale
  results. Migration verification and debug assembly executed again with
  `--rerun-tasks --no-build-cache --offline`, all 55 tasks executed in 91 seconds.
  Installed APK SHA-256:
  `887BB5569C63C2598B152464E3E6A966428055DA21E7A26B451A1540E070B83E`.
- Actual MainActivity, API 34 emulator, normal portrait: Welcome → Try demo →
  Ride → Graph → History catalog → an existing closed legacy ride. Both catalog
  and detail exposed exactly one MapLibre view in fresh UIAutomator trees. The
  legacy detail showed its recorded interval and explicit No GPS state; no
  current-location marker or live navigation route appeared in history.
- On h600, system Back traversed detail → catalog → retained graph → Ride. The
  graph's original time-window start was preserved while its live tail advanced.
  A direct Graph → Ride system Back also passed. The provider attribution appeared
  once in the history panel; the old duplicate root line was absent.
- Retraction/limit: on h598, one later Back observation reached the Android
  launcher and the app process was not seen. No AndroidRuntime exception or
  authoritative exit reason was captured. That observation is retained as
  unexplained; the complete h600 repetition passed. This is not claimed to be a
  diagnosed or fixed crash.
- Demo was disconnected through the actual vehicle chooser before the final
  quiescent database snapshot. All 16 original RideRow records and all 190,192
  original RidePointRow records had unchanged full hashes. Two additional demo
  recordings were produced by the still-active legacy recorder during these
  tests. No original recordings were deleted. Database integrity was OK.
- History resources still contain 199 matching unique EN/RU keys. Original GPL
  port license SHA-256 is unchanged. No new upstream files, assets or SDKs.

## Recording control bridge

The data/domain bridge is implemented, but is not yet wired into the production
history component or Koin recorder choice. `HistoryRecordingRuntime.status`
publishes a binding only after complete assembly. Observing it does not prepare
or allocate a writer. A terminal closing tombstone removes UI controls before
awaiting resource cleanup; a failed close retains the original private resources
for retry without republishing controls.

GPS observation is a cold stream of the original capture owner plus the actual
native session state. Submitted startup is not capture readiness, and failed
retirement does not relabel an old session as a replacement recording. The
repository also observes admission fences, so a pending durable Stop reports
STOPPING even before controller intent or native session state changes. Its
RECORDING route state denotes admitted native capture readiness, not proof that
a particular GPS fix has passed quality checks or been durably saved.

`HistoryRecordingRepository.captureControls` takes the displayed status. Its
opaque issued target pins the exact runtime binding and physical selection.
The UI must pass that rendered snapshot before scheduling Start/Stop, not query
the latest status inside the deferred action. Targets compare by issuer,
binding and selection, preserving status deduplication while making a replaced
physical selection observable. Stop additionally carries the displayed ride ID.
Writer acceptance/failure is reported independently of recording intent; raw
exception text is not part of the UI model.

Two concrete observer/control defects were reproduced and fixed during this
integration. h611 issued an unintended second begin when an old displayed Start
was redirected to a replacement selection; h612's 35 focused tests passed after
issuing targets with the status. h614 reported RECORDING while an issued finish
was waiting for its receipt and GPS admission was already closed; h615's 36
focused tests passed after observing admission changes. These are real controller
and native selection fixtures with a controlled writer, not physical BLE/GPS
acceptance. Production recorder activation remains the next integration gate.

h616 verified the final bridge with 4542 tests in 474 fresh XML reports and zero
failures, errors, skips or stale results. Migration verification and debug
assembly repeated with all 55 tasks executed in 168 seconds, offline and without
build-cache reuse. APK SHA-256:
`3701802A72E6E042BB5549B3402F95DD01771B9A52C4F68E852326DC79FF8CB8`.
The bridge has not received new native UI acceptance: its Koin/presentation
binding and the production recorder switch are still pending. Existing 199
history resource keys match across EN/RU and the original GPL license is unchanged.

## Remaining gates

The four-argument production Kable constructor still chooses the old recorder.
Task 8 must switch to the prepared native runtime, expose its recording/GPS
controls, bind actual legacy inactivity, and retire obsolete activation/pruning
without running two writers. The root needs further recorded-GPS camera,
fullscreen/XY restoration, rapid-selection, offline, large-font/landscape and
permission/foreground/restart acceptance. Vehicle display names should be wired
from saved identities rather than inferred from the currently connected vehicle.

Task 7 still has its explicitly recorded recipient-read, older-summary-timing
and parent export integration gates. Signed ARM/x86 release checks, complete
source/distribution provenance and physical recording acceptance remain open.
This document does not declare either plan or the full goal complete.
