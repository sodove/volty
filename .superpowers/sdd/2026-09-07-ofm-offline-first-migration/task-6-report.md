# Task 6 report — current-region preparation coordinator

Implemented one keyed preparation boundary for native OFM map packs and independent navigation
package state.

## Changes

- Added common `OfflineRegionPreparationState`, trigger enum, component states, coordinator API,
  and durable completion-store contract.
- Added `DefaultOfflineRegionPreparationCoordinator`: coordinate lookup is bounds-based; first map
  opening and explicit Settings preparation share one region/style job; repeated GPS/recomposition
  events are ignored; map manager and navigation package states are published independently.
- Added Android SharedPreferences persistence of completed region/style/revision markers.
- Moved the map-manager contract to common domain code and retained an Android typealias for source
  compatibility; Android manager now exposes keyed state lookup required for multiple regions/styles.
- Wired Koin and RootScreen's first-map-open effect. Settings actions now go through the coordinator,
  including pause/retry and metered approval. Removed application-wide automatic current-region
  preparation and its `AndroidOfflineMapSource` call site.

## Verification

`.\gradlew.bat :composeApp:testDebugUnitTest --tests '*OfflineRegionPreparationCoordinatorTest' --no-build-cache --rerun-tasks --max-workers=1`

Passed: 5 tests, 0 failures. Full device smoke was not run in this task.

## Fix round 1

Addressed the review's three Important findings:

- Coordinator jobs, pending requests, styles, definitions, and request markers are protected by one
  `Mutex`; pause snapshots and clears state before cancellation, and duplicate retries share one
  keyed job. The in-memory completion store is synchronized as well.
- Android network status and package-repository wake-up paths require both `INTERNET` and
  `VALIDATED`; an INTERNET-only capability is classified as offline.
- A blocked first-map request is retained as pending and retried on a non-offline network change,
  with stale/duplicate retry snapshots ignored.

Added deterministic coverage for concurrent first opens, offline-to-validated retry, duplicate
allowed-network transitions, and INTERNET-without-VALIDATED classification.

Verification command:

`.\gradlew.bat :composeApp:testDebugUnitTest --tests '*OfflineRegionPreparationCoordinatorTest' --tests '*AndroidOfflineNetworkStatusTest' --no-build-cache --rerun-tasks --max-workers=1`

The focused suite passed after setting `ANDROID_HOME` to the installed Android SDK. SQLDelight
still logs its known Windows `C:\WINDOWS\sqlite-*.dll.lck` access warning; it does not fail the
test task. Full device smoke was not run.
