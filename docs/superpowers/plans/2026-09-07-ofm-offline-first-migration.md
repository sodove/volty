# OFM Offline-First Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Volty's incompatible custom offline map pipeline with native MapLibre OpenFreeMap regional packs, keep search and routing as independent offline components, and make local/online fallback deterministic.

**Architecture:** The Android map always loads the normal OpenFreeMap Bright or Dark style URL. MapLibre `OfflineManager` owns regional map packs and automatically serves their cached resources when the network is unavailable; Volty only owns pack metadata, progress, and user-facing lifecycle. The backend no longer builds map artifacts: it publishes signed FTS and Valhalla artifacts from a pinned OSM snapshot, while the application uses validated-network online providers for missing resources.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, MapLibre Android SDK 13.0.2, Android `OfflineManager`, Decompose, Koin, SQLDelight/DataStore, Valhalla mobile 0.6.3, SQLite FTS4, Python 3.12, Docker, Ed25519-signed JSON catalogs.

**Spec:** `docs/superpowers/specs/2026-09-07-ofm-offline-first-design.md`

## Global Constraints

- Use the same OpenFreeMap Bright/Dark style URL online and offline; do not rewrite the style JSON or remove OFM layers.
- The only map-owned Volty layer is the legacy 3D building extrusion appended above OFM road layers.
- The first map opening prepares the current region once; GPS updates, search, route requests, and recompositions never start a new download.
- Other regions are prepared only by an explicit Settings action.
- Local data is selected first when it covers the request; validated online data is used only for missing coverage; with neither source, return no-data/unavailable.
- OpenFreeMap is never treated as a geocoder or routing provider.
- Keep Ed25519 catalog/manifest verification, atomic installation, FTS, and Valhalla; remove only the map artifact path after the native-pack acceptance gate.
- Do not erase existing routing/search data or uninstall the user's signed application during migration.
- Preserve Russian UI strings in both `values/` and `values-ru/`.
- Run `.\gradlew.bat :composeApp:testDebugUnitTest` before every commit that changes Kotlin.
- Compose rendering claims require device evidence; pure tests must cover policies and state, not pretend to validate MapLibre pixels.

## File Map

- Map domain: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineMapPackModels.kt`, `OfflineMapPackPolicy.kt`, and their common tests.
- Map bridge: new `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapPackManager.kt` and Android/device tests.
- Renderer: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt`, `RootScreen.kt`, and a small 3D-layer helper if extraction improves ownership.
- Navigation package contract: `OfflineRegionPackageManifest.kt`, `OfflineRegionModels.kt`, `OfflineRegionPackageState.kt`, `AndroidOfflineRegionPackageStore.kt`, `AndroidOfflineRegionPackageRepository.kt`.
- Preparation orchestration: new `OfflineRegionPreparationCoordinator.kt` and `OfflineRegionPreparationState.kt` plus tests; Android Koin wiring in `AndroidModule.kt`.
- Search/route policy: `OfflineRegionAccessPolicy.kt`, `OfflineFirstNavigationRepository.kt`, `AndroidHybridNavigationRepository.kt`, `AndroidOfflineFtsGeocoder.kt`, `OsmNavigationRepository.kt`, and focused tests.
- Backend build: `tools/offline-navigation/build-package.sh`, `build-manifest.py`, `verify-package.py`, `package_validation.py`, `package_cache.py`, `package-service.py`, `production/bootstrap.py`, `production/source_cache.py`, `production/worker.py`, `production/config.py`, and their tests.
- Backend documentation: `tools/offline-navigation/README.md`, `docs/offline-production-runbook.md`, and deployment examples.
- Removal after acceptance: `AndroidOfflineMapSource.kt`, `AndroidOfflinePmtilesTileServer.kt`, `OfflineMapStylePolicy.kt`, `offline-map-styles/`, `offline-map-glyphs/`, `process.lua`, map portions of `config.json` and `Dockerfile`.

---

### Task 1: Define the native map-pack contract and budget

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineMapPackModels.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineMapPackPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineMapPackPolicyTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionModels.kt`

**Interfaces:**
- `enum class OfflineMapStyleVariant { BRIGHT, DARK }`.
- `data class OfflineMapPackKey(val regionId: String, val style: OfflineMapStyleVariant, val ofmStyleUrl: String)`.
- `data class OfflineMapPackDefinition(val key: OfflineMapPackKey, val bounds: OfflineRegionBounds, val minZoom: Int, val maxZoom: Int, val tileLimit: Int)`.
- `sealed interface OfflineMapPackState` with `Missing`, `Preparing`, `Downloading(completedResources: Long, completedTiles: Long, totalResources: Long?)`, `Ready`, and `Failed(code: String)`.
- `OfflineMapPackPolicy.definition(region, style, tileLimit): OfflineMapPackDefinition` validates bounds, style URL, zoom range, and positive tile limit.
- `OfflineMapPackPolicy.estimatedTileCount(bounds, minZoom, maxZoom): Long` counts the XYZ pyramid used to size a pack; it is an upper-bound check, not a claim of exact server resources.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test
fun definition_uses_the_pinned_openfreemap_style_url() {
    val definition = OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.BRIGHT, 30_000)
    assertEquals("https://tiles.openfreemap.org/styles/bright", definition.key.ofmStyleUrl)
    assertEquals(13, definition.maxZoom)
}

@Test
fun invalid_bbox_and_default_tile_limit_are_rejected() {
    assertFails { OfflineMapPackPolicy.definition(invalidRegion(), OfflineMapStyleVariant.DARK, 0) }
    assertTrue(OfflineMapPackPolicy.estimatedTileCount(region().bounds, 5, 14) > 6_000)
}
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests '*OfflineMapPackPolicyTest' --no-build-cache --rerun-tasks`

Expected: FAIL because the map-pack models and policy do not exist.

- [ ] **Step 3: Implement the pure contract**

Use the existing `OfflineRegionBounds` validation. Define Bright as `https://tiles.openfreemap.org/styles/bright` and Dark as `https://tiles.openfreemap.org/styles/dark`. Set the first production EKB definition to the measured zoom range chosen by the spike, not the SDK default. Keep the policy independent of Android classes.

- [ ] **Step 4: Run the focused test and verify pass**

Run the command from Step 2. Expected: all map-pack policy tests pass.

- [ ] **Step 5: Commit**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineMapPackModels.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineMapPackPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineMapPackPolicyTest.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionModels.kt
git commit -m "feat: define native OFM map pack contract"
```

### Task 2: Build the native MapLibre OfflineManager bridge and run the device gate

**Files:**
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapPackManager.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapPackStore.kt`
- Create: `composeApp/src/androidTest/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapPackManagerTest.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/VoltyApplication.kt`

**Interfaces:**
- `interface OfflineMapPackManager { val states: StateFlow<List<OfflineMapPackState>>; suspend fun prepare(definition: OfflineMapPackDefinition); suspend fun pause(key: OfflineMapPackKey); suspend fun resume(key: OfflineMapPackKey); suspend fun invalidate(key: OfflineMapPackKey); suspend fun delete(key: OfflineMapPackKey) }`.
- `AndroidOfflineMapPackManager` owns one SDK `OfflineManager`, translates `OfflineTilePyramidRegionDefinition`/`OfflineRegionObserver` callbacks into the common states, and stores metadata bytes containing region ID, style variant, style URL, bounds, zooms, and OFM revision.
- `AndroidOfflineMapPackStore` maps a pack key to the SDK pack metadata and does not copy tile bytes into Volty storage.

- [ ] **Step 1: Write failing Android tests**

Test that a definition creates a TilePyramid with the exact OFM style URL and bounds, repeated `prepare` calls reuse one metadata key, and a callback sequence `DOWNLOAD_STATE_ACTIVE → COMPLETE` becomes `Ready`. Test that an SDK `TILE_LIMIT`/error becomes `Failed("tile_limit")`, not `Ready`.

- [ ] **Step 2: Run the focused Android test and verify failure**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests '*AndroidOfflineMapPackManagerTest' --no-build-cache --rerun-tasks`

Expected: FAIL until the bridge and Koin binding exist. If the SDK's observer names differ in 13.0.2, adapt the bridge to the actual dependency API while keeping the interfaces above unchanged.

- [ ] **Step 3: Implement one lifecycle owner**

Initialize `OfflineManager` once from `VoltyApplication`/Koin. Register packs in a paused state, call `resume` only from `prepare`/`resume`, persist only metadata and pack IDs, and set an explicit tile limit before creating the production EKB pack. Make deletion call SDK `delete` and update state only after the callback confirms removal. Do not use a loopback server or a custom tile protocol.

- [ ] **Step 4: Run the focused test and verify pass**

Run the command from Step 2. Expected: callback/state tests pass.

- [ ] **Step 5: Run the mandatory EKB device spike before deleting the old map path**

Build and install a debug APK on a clean emulator or a matching-signature test install. Exercise:

1. Online Bright: download the current EKB bbox and capture a close 3D frame.
2. Airplane mode: verify the same buildings, road order, labels, icons, and attribution.
3. With network enabled, pan just outside the bbox and verify ordinary OFM online tiles appear.
4. In airplane mode, pan outside the bbox and verify missing data, not a synthetic alternative style.
5. Restart the process in airplane mode and verify the pack remains usable.
6. Open and close the navigator and verify the map source/style does not change.
7. Repeat for Dark.
8. Record total resources, tile count, bytes, completion state, and logcat errors.

Use `adb devices`, `adb logcat -c`, the existing debug install workflow, and `adb logcat -d | Select-String -Pattern 'FATAL EXCEPTION|MapLibre|Offline'`. Expected: the pack is complete, both themes render, and no fatal exception occurs. The SDK default tile limit of 6,000 must not be used for the EKB definition.

- [ ] **Step 6: Commit only after the spike is accepted**

```powershell
git add composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapPackManager.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapPackStore.kt composeApp/src/androidTest/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapPackManagerTest.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/VoltyApplication.kt
git commit -m "feat: add native MapLibre offline packs"
```

### Task 3: Make the renderer OFM-only and restore the legacy 3D overlay

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/RootScreen.kt`
- Create or modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/LegacyBuildingLayer.android.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/MapRenderSourcePolicyTest.kt`
- Delete after the spike: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/OfflineMapStylePolicy.kt`, `composeApp/src/androidMain/assets/offline-map-styles/*`

**Interfaces:**
- `MapRenderSourcePolicy.styleUrl(darkTheme): String` returns only the two OFM URLs.
- `LegacyBuildingLayer.add(style: Style)` appends one `FillExtrusionLayer` sourced from the standard OFM `building` source and uses the pre-offline height/base/opacity behavior.

- [ ] **Step 1: Write failing source-policy tests**

Assert Bright/Dark return the exact OFM URLs and no offline source URL, JSON localization, or style clone can be selected. Assert the building layer contract is appended after the style's road layers.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests '*MapRenderSourcePolicyTest' --no-build-cache --rerun-tasks`

Expected: FAIL until the source policy and layer helper are present.

- [ ] **Step 3: Remove source switching from the renderer**

Delete `scene.ownFix`-based `offlineSourceUrl` selection and the `Style.Builder().fromJson(offlineStyleJson(...))` branch. Always call `Style.Builder().fromUri(styleUrl)`, then append `LegacyBuildingLayer` in the style-loaded callback. Preserve route, trail, current-location, and HUD overlays. Do not call `check`/`require` as a substitute for ordering; the code path must unconditionally append the layer after the OFM layers.

- [ ] **Step 4: Run tests and inspect the diff**

Run the command from Step 2 and `git diff --check`. Expected: policy tests pass and no map renderer code refers to `AndroidOfflineMapSource`, `offlineStyleJson`, loopback tile URLs, or `OfflineMapStylePolicy`.

- [ ] **Step 5: Commit**

```powershell
git add composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/RootScreen.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/LegacyBuildingLayer.android.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/MapRenderSourcePolicyTest.kt
git commit -m "fix: render one OFM style online and offline"
```

### Task 4: Split map-pack metadata from signed navigation artifacts

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPackageManifest.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionModels.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPackageState.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRegionPackageStore.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRegionPackageRepository.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionManifestV3Test.kt`
- Modify: existing manifest/store tests

**Interfaces:**
- New signed manifest schema is version 3 and contains only `routing` and `search` artifacts plus source provenance; it does not contain a downloadable map artifact.
- `data class OfflineRegionMapPackMetadata(val styleUrls: List<String>, val bounds: OfflineRegionBounds, val minZoom: Int, val maxZoom: Int, val ofmStyleRevision: String?)` lives in the catalog/region metadata, not in the artifact list.
- `OfflineRegionPackageState` exposes `navigationStatus` and receives map-pack state separately; it must not calculate map bytes from `components.map`.
- `OfflineRegionLegacyManifestCodec` parses v2 only for migration and exposes routing/search files without selecting the legacy map.

- [ ] **Step 1: Write failing compatibility tests**

Assert a v3 manifest with routing/search and map metadata validates, a v3 manifest with `components.map` is rejected, and a v2 manifest can be inspected for routing/search migration while its map artifact is marked ignored. Assert progress totals never include map bytes.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests '*OfflineRegionManifestV3Test' --tests '*OfflineRegionPackageStoreTest' --no-build-cache --rerun-tasks`

Expected: FAIL because the current manifest requires a PMTiles map component and has schema version 2.

- [ ] **Step 3: Implement the v3 contract and safe migration**

Update canonical signing payload and validation to require routing/search, source URL/digest/timestamp/sequence, and no map artifact. Keep old v2 decoding in a separate compatibility parser. During install, preserve an existing v2 directory but copy/activate only valid routing/search files; never feed its PMTiles into the renderer. Make catalog state report map readiness from `OfflineMapPackManager`, not from a manifest byte count.

- [ ] **Step 4: Run focused tests and verify pass**

Run the command from Step 2. Expected: v3, v2 migration, and byte-progress tests pass.

- [ ] **Step 5: Commit**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPackageManifest.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionModels.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPackageState.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRegionPackageStore.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRegionPackageRepository.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionManifestV3Test.kt
git commit -m "refactor: separate map packs from navigation artifacts"
```

### Task 5: Simplify the backend to build only search and routing

**Files:**
- Modify: `tools/offline-navigation/build-package.sh`
- Modify: `tools/offline-navigation/build-manifest.py`
- Modify: `tools/offline-navigation/verify-package.py`
- Modify: `tools/offline-navigation/package_validation.py`
- Modify: `tools/offline-navigation/package_cache.py`
- Modify: `tools/offline-navigation/package-service.py`
- Modify: `tools/offline-navigation/production/worker.py`
- Modify: `tools/offline-navigation/production/bootstrap.py`
- Modify: `tools/offline-navigation/production/source_cache.py`
- Modify: `tools/offline-navigation/production/config.py`
- Modify: `tools/offline-navigation/Dockerfile`, `Dockerfile.worker`, and `config.json`
- Modify: `tools/offline-navigation/test_offline_navigation_toolchain.py`, `test_build_catalog.py`, package/production tests

**Interfaces:**
- `build-package.sh` produces `routing/valhalla-routing.tar.gz`, `search/places.sqlite.gz`, and a v3 manifest; it does not create `map/`, `map.mbtiles`, or invoke tilemaker/PMTiles.
- `OfflineRegionSource` gains `sourceUrl` and `sourceSha256` while retaining `osmReplicationSequence` and `osmTimestamp`.
- `verify-package.py` validates exactly routing/search for v3 and rejects a map component in a new package.
- `Worker` publishes only navigation artifacts. `request_build(regionId)` never starts an OFM map build.

- [ ] **Step 1: Write failing backend tests**

Add assertions that the build script has no `tilemaker`, `map.mbtiles`, or PMTiles conversion, that the v3 verifier rejects `map/` in a newly built package, and that source metadata requires URL, SHA-256, timestamp, and sequence. Assert an on-demand request creates one navigation build job only.

- [ ] **Step 2: Run Python tests and verify failure**

Run: `python -m unittest tools.offline-navigation.test_offline_navigation_toolchain tools.offline-navigation.test_build_catalog tools.offline-navigation.production.tests.test_worker -v`

Expected: FAIL because the current package contract requires `map/*.pmtiles` and the builder still invokes tilemaker.

- [ ] **Step 3: Remove the map build and pin navigation provenance**

Delete the map staging directories and commands from `build-package.sh`. Keep the logical OSM extract for FTS and the buffered extract for Valhalla. Pass the fetched source URL and digest from `SourceSnapshot` into `build-manifest.py`; reject a release if either is absent. Retain Geofabrik as an explicit raw OSM input for navigation only; document that it is not the OFM map source. Remove map size/PMTiles checks from cache and package-service code, and update Docker images so tilemaker is not installed for the production worker.

- [ ] **Step 4: Run backend tests and verify pass**

Run the command from Step 2, `python -m unittest discover -s tools/offline-navigation -p 'test_*.py' -v`, and `docker compose config --quiet`. Expected: all backend tests pass and the package contains only routing/search artifacts.

- [ ] **Step 5: Commit**

```powershell
git add tools/offline-navigation/build-package.sh tools/offline-navigation/build-manifest.py tools/offline-navigation/verify-package.py tools/offline-navigation/package_validation.py tools/offline-navigation/package_cache.py tools/offline-navigation/package-service.py tools/offline-navigation/production/worker.py tools/offline-navigation/production/bootstrap.py tools/offline-navigation/production/source_cache.py tools/offline-navigation/production/config.py tools/offline-navigation/Dockerfile tools/offline-navigation/Dockerfile.worker tools/offline-navigation/config.json tools/offline-navigation/test_offline_navigation_toolchain.py tools/offline-navigation/test_build_catalog.py tools/offline-navigation/production/tests
git commit -m "refactor: remove custom map build from offline backend"
```

### Task 6: Add one preparation coordinator for current-region and Settings flows

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPreparationState.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPreparationCoordinator.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPreparationCoordinatorTest.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/VoltyApplication.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/RootScreen.kt`
- Modify: Settings component/screen files and Russian/English resource files

**Interfaces:**
- `enum class OfflinePreparationTrigger { FIRST_MAP_OPEN, SETTINGS }`.
- `data class OfflineRegionPreparationState(val regionId: String, val map: OfflineMapPackState, val search: OfflineNavigationComponentState, val routing: OfflineNavigationComponentState)`.
- `interface OfflineRegionPreparationCoordinator { val states: StateFlow<List<OfflineRegionPreparationState>>; suspend fun prepareCurrentRegion(coordinate: GeoCoordinate, style: OfflineMapStyleVariant); suspend fun prepareExplicitRegion(regionId: String, style: OfflineMapStyleVariant); suspend fun pause(regionId: String); suspend fun retry(regionId: String) }`.

- [ ] **Step 1: Write failing coordinator tests**

Use fakes for the map manager and package repository. Assert two `FIRST_MAP_OPEN` events for the same region create one map preparation and one navigation request, GPS updates do not create new work, a second Settings region starts only after the button action, and map-ready/search-failed/routing-downloading is exposed as a partial state.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests '*OfflineRegionPreparationCoordinatorTest' --no-build-cache --rerun-tasks`

Expected: FAIL because the coordinator/state do not exist.

- [ ] **Step 3: Implement idempotent orchestration**

Use one keyed job per region and persist the last prepared OFM style/revision so process restarts do not redownload a completed pack. On first map open, call the coordinator once from the map host after a non-null fix; do not call it from every location update. Remove `VoltyApplication.startAutomaticCurrentRegionDownload()` and `AndroidOfflineMapSource.considerDownload()`. Keep explicit Settings actions for other regions and for retrying a failed component. Respect validated network and the existing metered preference before starting transfers.

- [ ] **Step 4: Run tests and Settings/map smoke**

Run the command from Step 2, build/install debug, open the map twice, rotate/recompose, open/close navigator, and verify one preparation. In Settings, prepare a second region and verify its three component states are visible. Expected: no duplicate jobs and no navigation-triggered download.

- [ ] **Step 5: Commit**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPreparationState.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPreparationCoordinator.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPreparationCoordinatorTest.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/VoltyApplication.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/RootScreen.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings
git commit -m "feat: prepare the current offline region once"
```

### Task 7: Make map/search/route fallback local-first without hidden downloads

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineResourceFallbackPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineResourceFallbackPolicyTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionAccessPolicy.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineFirstNavigationRepository.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/AndroidHybridNavigationRepository.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineFtsGeocoder.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/OsmNavigationRepository.kt` and `HttpNavigationRepository.kt`
- Modify: related common tests

**Interfaces:**
- `enum class OfflineResourceKind { MAP, SEARCH, ROUTING }`.
- `sealed interface OfflineResourceDecision { UseLocal; UseOnline; Unavailable; }`.
- `OfflineResourceFallbackPolicy.decide(localCoverage: Boolean, network: OfflineNetworkAvailability, localFailure: LocalFailure = None, resource: OfflineResourceKind): OfflineResourceDecision`.

- [ ] **Step 1: Write failing fallback tests**

Assert local coverage always returns `UseLocal`, missing coverage with `UNMETERED`/`METERED` returns `UseOnline`, and missing coverage while `OFFLINE` returns `Unavailable`. Assert search and route decisions never contain a download trigger.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `.\gradlew.bat :composeApp:testDebugUnitTest --tests '*OfflineResourceFallbackPolicyTest' --tests '*OfflineFirstNavigationRepositoryTest' --no-build-cache --rerun-tasks`

Expected: FAIL because current access policy schedules downloads and does not consistently fall back after local empty/error results.

- [ ] **Step 3: Remove implicit acquisition from requests**

Change `OfflineFirstNavigationRepository.search` and `.routes` to use installed local data first, then call the online adapter only when the network status is validated. Remove `scheduleDownload`, `scheduleMissingRegionDownload`, and catalog-refresh-triggered downloads from request paths. Keep `requestDownload` available only to the coordinator/Settings. Preserve a valid local `NoRoute` as final; use online fallback only for coverage miss or runtime failure classified as retryable. Make `AndroidOfflineFtsGeocoder` return a typed empty/unavailable result instead of pretending every failure is a network request.

- [ ] **Step 4: Run focused tests and verify pass**

Run the command from Step 2 plus the existing navigation repository tests. Expected: local-first/fallback tests pass and no search/route test observes a download request.

- [ ] **Step 5: Commit**

```powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineResourceFallbackPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineResourceFallbackPolicyTest.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionAccessPolicy.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineFirstNavigationRepository.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/AndroidHybridNavigationRepository.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineFtsGeocoder.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/OsmNavigationRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/HttpNavigationRepository.kt
git commit -m "fix: make navigation resources local-first"
```

### Task 8: Remove the obsolete map zoo and document the new backend contract

**Files:**
- Delete after Tasks 2–7 acceptance: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapSource.kt`, `AndroidOfflinePmtilesTileServer.kt`, `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/OfflineMapStylePolicy.kt`, `composeApp/src/androidMain/assets/offline-map-styles/`, `composeApp/src/androidMain/assets/offline-map-glyphs/`, `tools/offline-navigation/process.lua`.
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt`, package/store tests, `tools/offline-navigation/README.md`, `docs/offline-production-runbook.md`, `docker-compose.yml`.
- Modify or delete only map-specific test fixtures after replacing them with v3 navigation fixtures.

**Interfaces:**
- No production class may reference `AndroidOfflineMapSource`, `AndroidOfflinePmtilesTileServer`, `OfflineMapStylePolicy`, `process.lua`, or a map artifact in a v3 package.
- The production runbook describes OFM map packs as client-side MapLibre resources and Geofabrik as the explicit navigation-input source.

- [ ] **Step 1: Add a no-zoo guard test**

Add a source scan test that fails when production Kotlin or the worker references the deleted map server, `tilemaker`, `process.lua`, `map.mbtiles`, or `components.map` in v3 code.

- [ ] **Step 2: Run the guard and full focused suites**

Run: `python -m unittest tools.offline-navigation.test_offline_navigation_toolchain -v` and `.\gradlew.bat :composeApp:testDebugUnitTest --tests '*Offline*' --no-build-cache --rerun-tasks`. Expected: the guard initially finds the old references.

- [ ] **Step 3: Delete only obsolete map code after migration checks**

Remove the old files and assets, update Koin bindings, remove map artifact paths from Docker/package docs, and leave legacy user directories untouched. Keep a migration log entry explaining that old PMTiles are ignored rather than deleted.

- [ ] **Step 4: Run guards and inspect tracked files**

Run the commands from Step 2, `rg -n "AndroidOfflineMapSource|AndroidOfflinePmtilesTileServer|OfflineMapStylePolicy|tilemaker|process.lua|map.mbtiles|components\.map" composeApp tools docs`, and `git diff --check`. Expected: only migration-history documentation may mention removed names.

- [ ] **Step 5: Commit**

```powershell
git add composeApp tools/offline-navigation docker-compose.yml docs/offline-production-runbook.md
git commit -m "chore: remove obsolete offline map pipeline"
```

### Task 9: Verify, migrate, and publish the server-ready implementation

**Files/artifacts:**
- Modify: `docs/offline-production-runbook.md` with exact build/source/rollback checks.
- Modify: production fixture configuration with an explicit pinned navigation source.
- Create only in ignored output directories: a v3 EKB navigation package and emulator capture/log bundle.

**Interfaces:**
- Catalog entry contains region bounds and OFM map-pack metadata; v3 release manifest contains signed routing/search artifacts and source provenance.
- Current-region first-open preparation is idempotent and has no hidden search/route download side effect.

- [ ] **Step 1: Run the complete code/test matrix**

Run:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --no-build-cache --rerun-tasks
python -m unittest discover -s tools/offline-navigation -p 'test_*.py' -v
docker compose config --quiet
```

Expected: all Kotlin/Python tests pass and Compose configuration is valid. Check `git status --short`, `git diff --check`, and that no generated PBF, PMTiles, MapLibre database, signing key, `.env`, or emulator capture is tracked.

- [ ] **Step 2: Run Android acceptance**

Install a matching-signature debug/release build without uninstalling the user's existing package. Verify current-region map/search/routing preparation, online/offline map parity, both themes, 3D above roads, outside-region network fallback, outside-region airplane-mode no-data, navigator transitions, second-region Settings preparation, local FTS, and local Valhalla in airplane mode. Save logcat and pack progress metrics.

- [ ] **Step 3: Validate backend release contents**

Build one EKB v3 package from a pinned source snapshot. Run `verify-package.py`, inspect the manifest signature and source digest, list the archive, and assert it contains routing/search only. Confirm the worker publishes no map artifact and the catalog points to the v3 manifest plus map-pack metadata.

- [ ] **Step 4: Run the rollback check**

Start with a device containing a legacy v2 package. Upgrade the app, verify routing/search remain usable, verify the old map PMTiles is not selected, then delete only through the new Settings action and confirm the MapLibre pack lifecycle does not touch navigation files.

- [ ] **Step 5: Commit documentation and release handoff**

```powershell
git add docs/offline-production-runbook.md
git commit -m "docs: record OFM offline migration verification"
```

## Conditional fallback if the native-pack gate fails

Do not revive tilemaker. Record the failing device evidence and implement the
approved fallback only if MapLibre 13.0.2 cannot satisfy local-first behavior:

1. Download one version-pinned OFM MBTiles/PMTiles snapshot into a server cache.
2. Extract a regional bbox plus routing-independent tile halo with `pmtiles extract` without rewriting vector bytes.
3. Publish the exact OFM style/sprite/glyph resources alongside that extract.
4. Keep the same renderer style URL and make the resolver local-first; do not
   introduce a second style or custom layer schema.
5. Add an explicit storage budget: the full OFM snapshot is tens of gigabytes,
   regional extraction needs large staging space, and the device acceptance
   must measure the result before production is enabled.

This branch is not part of the default execution path. The native MapLibre
pack is the required first implementation because it is the only option that
removes the custom map server and preserves the public OFM resource contract.

