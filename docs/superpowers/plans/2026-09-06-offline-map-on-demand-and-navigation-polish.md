# Offline map, on-demand regions and navigation polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make the offline map usable from globe scale to close 3D view, allow explicit on-demand region preparation/downloads, fit route previews reliably, restore the trail, remove duplicate search places, and turn Settings into a navigable set of focused sections.

**Architecture:** Keep the signed catalog as the source of truth. Catalog entries may advertise a supported region with latestRelease null and onDemand.enabled true; a private worker control endpoint puts one idempotent build job into the existing durable queue, and Android waits for the signed release before downloading it. Map rendering uses a bundled low-detail world layer plus zoom-filtered regional PMTiles layers. Search, route camera, trail snapshots and Settings list ordering are pure policies with focused tests.

**Tech Stack:** Kotlin Multiplatform, Jetpack Compose, MapLibre Android SDK 13.0.2, Python 3.12, SQLite FTS4, Docker Compose, Ed25519-signed JSON catalogs, existing Gradle and unittest tooling.

**Spec:** docs/superpowers/specs/2026-09-06-offline-map-and-on-demand-regions-design.md

## Global Constraints

- Preserve schemaVersion = 2, Ed25519 catalog/manifest verification, APK versionCode 31, and the existing production signing key.
- A null release is valid only when onDemand.enabled = true; a ready release remains immutable and checksum-verified.
- Build requests are explicit and idempotent; offline-scheduler is never started by the deployment command.
- Private keys, .env, PBFs, generated packages, Gradle output and emulator captures remain outside Git.
- Installed routing/search/map must work with network disabled.
- Keep the existing Terra glass navigator visual language; redesign Settings as the same product, not as a new visual system.
- Every behavior change gets a failing test before implementation and a focused verification command after implementation.

## File map

- Catalog/protocol: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionCatalog.kt, OfflineRegionModels.kt, tools/offline-navigation/build-catalog.py, tools/offline-navigation/production/worker.py.
- Server build control: tools/offline-navigation/production/worker.py, package_cache.py, package_validation.py, package-service.py, docker-compose.yml.
- Android package UX: AndroidOfflineRegionPackageRepository.kt, OfflineRegionPackageState.kt, SettingsComponent.kt, SettingsScreen.kt, values-ru/strings.xml.
- Search: new PlaceCandidateDeduplicationPolicy.kt, AndroidOfflineFtsGeocoder.kt, OsmNavigationRepository.kt, HttpNavigationRepository.kt, build-search.py.
- Map/camera/trail: PlatformMapLayer.android.kt, new OfflineMapStyle.android.kt, new WorldOverview.android.kt, NavigationMapRenderPolicy.kt, RootScreen.kt, RideMapTrailPolicy.kt, AndroidOfflinePmtilesTileServer.kt, process.lua, config.json.
- Tests: existing common navigation/map/settings tests plus new policy and Python tests.
- Release verification: docs/offline-production-runbook.md and the existing emulator APK workflow.

### Task 1: Make the catalog represent supported but unbuilt regions

**Files:**
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionCatalog.kt
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionModels.kt
- Modify: composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionCatalogTest.kt
- Modify: tools/offline-navigation/build-catalog.py
- Modify: tools/offline-navigation/production/worker.py
- Modify: tools/offline-navigation/production/tests/test_worker.py
- Modify: tools/offline-navigation/production/tests/test_bundle_contract.py

**Interfaces:**
- Add serializable OfflineRegionOnDemand(enabled: Boolean = false).
- Add onDemand: OfflineRegionOnDemand to OfflineRegionCatalogEntry with a default so older signed catalogs still decode.
- build-catalog.py accepts manifest omitted only when onDemand.enabled is true and emits latestRelease: null.
- Worker._write_catalog emits every admitted configured region, sorted by display name then region ID, using the configured display name or a deterministic geographic fallback.

- [ ] **Step 1: Write failing tests**

Kotlin must accept this entry and reject the same entry without onDemand:

~~~kotlin
@Test
fun unbuilt_on_demand_entry_is_structurally_valid() {
    val entry = OfflineRegionCatalogEntry(
        region = region("region-a", "Екатеринбург"),
        latestRelease = null,
        onDemand = OfflineRegionOnDemand(enabled = true),
    )
    assertTrue(OfflineRegionCatalogPolicy.validate(catalog(entry), 31).isEmpty())
}

@Test
fun unbuilt_entry_without_on_demand_capability_is_rejected() {
    val entry = OfflineRegionCatalogEntry(region("region-a", "Екатеринбург"))
    assertTrue(OfflineRegionCatalogPolicy.validate(catalog(entry), 31).any {
        it.code == OfflineRegionCatalogErrorCode.INVALID_ON_DEMAND_ENTRY
    })
}
~~~

Python must build a signed catalog with one manifest entry and one null-release on-demand entry, and assert both entries are present.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

~~~powershell
./gradlew.bat :composeApp:commonTest --tests '*OfflineRegionCatalogTest' --console=plain
python -m unittest tools.offline-navigation.production.tests.test_worker tools.offline-navigation.production.tests.test_bundle_contract -v
~~~

Expected: Kotlin has no onDemand model/validation and build-catalog.py raises manifest is required.

- [ ] **Step 3: Implement nullable release validation**

Add INVALID_ON_DEMAND_ENTRY. Validate the region envelope for every entry; run release-region, coverage, compatibility and manifest-signature checks only when latestRelease is non-null. Reject null releases unless onDemand.enabled is true. Keep catalog signature verification unchanged.

In build-catalog.py, validate onDemand as an object with a boolean enabled. When manifest is absent, use the logical bounds as the region envelope and emit latestRelease: None; when a manifest exists, keep the current signed manifest verification and coverage check.

In Worker._write_catalog, first index published manifests by region ID, then iterate config.regions. Emit the manifest when present and latestRelease: None otherwise. Set onDemand.enabled from source admission/configuration, never from an Android-supplied value. Use a stable fallback such as Регион · 56.0–57.0°N, 60.0–61.0°E when displayName is blank.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run the commands from Step 2. Expected: ready and null-release entries both validate and catalog ordering is deterministic.

- [ ] **Step 5: Commit**

~~~powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionCatalog.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionModels.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionCatalogTest.kt tools/offline-navigation/build-catalog.py tools/offline-navigation/production/worker.py tools/offline-navigation/production/tests/test_worker.py tools/offline-navigation/production/tests/test_bundle_contract.py
git commit -m "feat: advertise on-demand offline regions"
~~~

### Task 2: Add the private build-request bridge

**Files:**
- Modify: tools/offline-navigation/production/worker.py
- Modify: tools/offline-navigation/package_cache.py
- Modify: tools/offline-navigation/package_validation.py
- Modify: tools/offline-navigation/package-service.py
- Create: tools/offline-navigation/production/tests/test_on_demand.py
- Modify: docker-compose.yml
- Modify: docs/offline-production-runbook.md

**Interfaces:**
- POST /internal/builds/{regionId} returns status, regionId, requestId and retryAfterSeconds.
- GET /internal/builds/{regionId} returns status, regionId, requestId, releaseVersion and optional errorCode.
- Worker.request_build(region_id) validates the configured region and source admission, returns an existing queued/running request for that region, or atomically queues exactly one on-demand job.
- PackageManager.ensure(region_id, release_version=None) preserves the exact-release path and uses the private bridge only for an on-demand entry with no latest release.

- [ ] **Step 1: Write failing tests**

Use a temporary queue and fake builder client. Assert the second ensure returns the first requestId, only one region job exists, an unknown region returns unavailable, resolve returns releaseVersion null, and an existing exact release still returns ready.

~~~python
first = manager.ensure("region-a")
second = manager.ensure("region-a")
assert first["status"] == "queued"
assert second["requestId"] == first["requestId"]
assert len([j for j in read_queue()["jobs"] if j["regionId"] == "region-a"]) == 1
assert manager.ensure("unknown")["status"] == "unavailable"
~~~

- [ ] **Step 2: Run and verify failure**

~~~powershell
python -m unittest tools.offline-navigation.production.tests.test_on_demand -v
~~~

Expected: PackageManager dereferences null latestRelease and the worker has no control endpoint.

- [ ] **Step 3: Implement the worker control server**

Run a ThreadingHTTPServer in the existing worker process, controlled by --control-host and --control-port. Guard queue read/modify/replace with a per-queue file lock. For POST, reject client-supplied source URL, bbox and paths; resolve all build fields from the validated production config. Deduplicate queued/running on-demand jobs by region ID. Return unavailable when the region is absent or its immutable source metadata admission is not satisfied. Keep the existing strict source metadata rule; do not fabricate OSM timestamps or geometry hashes.

- [ ] **Step 4: Implement PackageManager null-release states**

Allow null latestRelease entries in catalog validation, identity tracking and pruning. Store the private builder URL in Config.from_env as VOLTY_OFFLINE_BUILDER_URL. On ensure without a release, call the internal bridge and return queued/building/failed/unavailable. On status, query the bridge for the current request. After the worker publishes a signed manifest, refresh the catalog and hand the exact release to the existing cache acquisition path.

- [ ] **Step 5: Wire Compose without enabling the scheduler**

Add the builder URL to offline as http://offline-worker:8092. Start the worker with control port 8092, expose no host port, and leave offline-scheduler out of deploy/up service lists. Mount the queue/config read-write/read-only as appropriate; do not mount signing keys into the package-service container.

- [ ] **Step 6: Run server tests and Compose validation**

~~~powershell
python -m unittest discover -s tools/offline-navigation/production/tests -v
python -m unittest discover -s tools/offline-navigation -p 'test_*.py' -v
docker compose config --quiet
~~~

- [ ] **Step 7: Commit**

~~~powershell
git add tools/offline-navigation/production/worker.py tools/offline-navigation/production/tests/test_on_demand.py tools/offline-navigation/package_cache.py tools/offline-navigation/package_validation.py tools/offline-navigation/package-service.py docker-compose.yml docs/offline-production-runbook.md
git commit -m "feat: queue offline region builds on demand"
~~~

### Task 3: Make Android region discovery and Settings useful

**Files:**
- Modify: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRegionPackageRepository.kt
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPackageState.kt
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsComponent.kt
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsScreen.kt
- Modify: composeApp/src/commonMain/composeResources/values-ru/strings.xml
- Create: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/OfflineRegionListPolicy.kt
- Create: composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/settings/OfflineRegionListPolicyTest.kt

**Interfaces:**
- Add PREPARING or an equivalent explicit build state; it must not claim bytes are downloading while latestRelease is null.
- OfflineRegionListPolicy.filterAndOrder(states, query, maxSuggestions) puts installed/transient states first, returns at most 8 suggestions for an empty query, and searches display name plus region ID when query is non-empty.
- Settings state keeps existing actions and adds only the query text as UI-local state.

- [ ] **Step 1: Write failing state/list tests**

Assert that a null-release on-demand state shows a preparation state, empty query returns installed entries plus at most 8 suggestions, and searching екб finds Екатеринбург while a coordinate fallback is searchable by ID.

- [ ] **Step 2: Run and verify failure**

~~~powershell
./gradlew.bat :composeApp:commonTest --tests '*OfflineRegionPackageStateTest' --tests '*OfflineRegionListPolicyTest' --console=plain
~~~

- [ ] **Step 3: Implement the repository two-phase flow**

When latestRelease is null and onDemand.enabled is true, call ensure without a version, keep the state in PREPARING/QUEUED, poll using retryAfterSeconds, refresh the catalog after ready, and then run the current checksum-verified download/install code. Do not call OfflineRegionDownloadPlanFactory for a null release and do not mark this state INCOMPATIBLE.

- [ ] **Step 4: Redesign Settings around navigation, not a long dump**

Replace the single uninterrupted column with a scrollable screen containing:

1. a compact top summary card with the active vehicle and a short configured-state summary;
2. an Appearance card for theme and dynamic color;
3. a Connection card for scan timeout and auto-connect countdown;
4. a Navigation card for units and offline navigation;
5. a Vehicle card for dashboard style, fault duration and the battery list;
6. a Voice card for microphone source;
7. a Diagnostics card for sending logs.

Each card has a title, optional one-line subtitle, internal spacing and a collapsed/expanded state remembered only for the current screen. The offline card opens with installed/preparing regions and a search field Добавить регион; it never renders all 5740 entries by default. Use LazyColumn for region rows, place active/preparing rows first, and keep destructive delete confirmation. Preserve the existing dark glass palette and controls; do not add a second settings navigation hierarchy.

- [ ] **Step 5: Add Russian copy and actions**

Add localized strings for card titles, Добавить регион, Подготовить, Готовится, Повторить and the empty search result. Keep technical OSM IDs and internal release paths out of visible copy.

- [ ] **Step 6: Run tests and visual smoke**

Run Step 2, build/install the debug APK, open Settings, verify one screen-height card at a time is readable, expand/collapse cards, search a second region, start preparation, and return to the navigator without losing state.

- [ ] **Step 7: Commit**

~~~powershell
git add composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRegionPackageRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/region/OfflineRegionPackageState.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsComponent.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsScreen.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/OfflineRegionListPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/settings/OfflineRegionListPolicyTest.kt composeApp/src/commonMain/composeResources/values-ru/strings.xml
git commit -m "feat: organize settings and prepare offline regions"
~~~

### Task 4: Deduplicate offline and online place search

**Files:**
- Create: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/PlaceCandidateDeduplicationPolicy.kt
- Create: composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/PlaceCandidateDeduplicationPolicyTest.kt
- Modify: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineFtsGeocoder.kt
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/OsmNavigationRepository.kt
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/HttpNavigationRepository.kt
- Modify: composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/navigation/OsmNavigationRepositoryTest.kt
- Modify: tools/offline-navigation/build-search.py
- Create: tools/offline-navigation/test_build_search.py

**Interfaces:**
- PlaceCandidateDeduplicationPolicy.deduplicate(candidates, limit) preserves relevance order, merges equal normalized OSM IDs, merges equal normalized titles within 50 m, and preserves separate branches beyond 50 m.
- The builder deduplicates by OSM identity first and by normalized title plus 50 m cluster when identity is absent.
- Category presentation maps shop:mall to Торговый центр, amenity:food_court to Фуд-корт and generic feature to no subtitle.

- [ ] **Step 1: Write failing tests**

Use three same-coordinate candidates titled Алатырь with subtitles amenity:food_court, shop:mall and feature; assert one result and the mall candidate. Use two same-name candidates 2 km apart; assert both remain. Feed duplicate GeoJSON features to the Python builder and assert one row.

- [ ] **Step 2: Run and verify failure**

~~~powershell
./gradlew.bat :composeApp:commonTest --tests '*PlaceCandidateDeduplicationPolicyTest' --tests '*OsmNavigationRepositoryTest' --console=plain
python -m unittest tools.offline-navigation.test_build_search -v
~~~

- [ ] **Step 3: Implement deterministic deduplication**

Use the existing Unicode normalization, including ё to е, an equirectangular distance check, and a stable semantic priority. Apply the policy after Photon decoding, after HTTP decoding, and after each offline FTS result set. Keep the first relevant order, but replace a generic duplicate with the more specific candidate. Map technical kinds before they reach PlaceResult.

In build-search.py, collapse duplicate OSM IDs, then cluster no-ID rows by normalized name and 50 m distance. Preserve the richest searchable name/address text in the winning row and make the winner deterministic.

- [ ] **Step 4: Run focused tests and rebuild the EKB search fixture**

Run Step 2. Build a temporary places.sqlite, query MATCH 'алат*', and assert no same-coordinate duplicate rows.

- [ ] **Step 5: Commit**

~~~powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/PlaceCandidateDeduplicationPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/PlaceCandidateDeduplicationPolicyTest.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineFtsGeocoder.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/OsmNavigationRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/HttpNavigationRepository.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/navigation/OsmNavigationRepositoryTest.kt tools/offline-navigation/build-search.py tools/offline-navigation/test_build_search.py
git commit -m "fix: deduplicate navigation search places"
~~~

### Task 5: Restore route camera fitting

**Files:**
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/NavigationMapRenderPolicy.kt
- Modify: composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/NavigationMapRenderPolicyTest.kt
- Modify: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt
- Create: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RoutePreviewCameraPolicy.kt
- Create: composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RoutePreviewCameraPolicyTest.kt

**Interfaces:**
- RoutePreviewCameraPolicy.paddingFor(width, height) returns the HUD-safe left/top/right/bottom padding.
- FitAlternatives carries a monotonic sequence and all alternative geometry; Android fits it with tilt 0 and bearing 0.

- [ ] **Step 1: Write failing policy tests**

Assert padding is positive and bottom padding exceeds top padding, two route builds produce distinct fit sequences, and RouteReady never emits a live-follow request.

- [ ] **Step 2: Run and verify failure**

~~~powershell
./gradlew.bat :composeApp:commonTest --tests '*RoutePreviewCameraPolicyTest' --tests '*NavigationMapRenderPolicyTest' --console=plain
~~~

- [ ] **Step 3: Implement neutral route fit**

Use RoutePreviewCameraPolicy in fitAlternatives. Calculate the bounds camera with the existing HUD padding, then build the final CameraPosition with tilt 0.0 and bearing 0.0 before moving the map. If the first style callback occurs before layout, defer the fit until width and height are non-zero. The live-follow loop must not write a camera while the scene is RouteReady.

- [ ] **Step 4: Run tests and emulator smoke**

Run Step 2. On the emulator manually zoom and tilt the map, build a 9 km route, verify the complete route fits in the unobstructed band, build a second route, and verify it refits.

- [ ] **Step 5: Commit**

~~~powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/NavigationMapRenderPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/NavigationMapRenderPolicyTest.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RoutePreviewCameraPolicy.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RoutePreviewCameraPolicyTest.kt
git commit -m "fix: fit route previews in the map viewport"
~~~

### Task 6: Restore trail rendering and make the tile server crash-safe

**Files:**
- Modify: composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/RootScreen.kt
- Modify: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt
- Modify: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflinePmtilesTileServer.kt
- Modify: composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapTrailPolicyTest.kt
- Create: composeApp/src/androidTest/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflinePmtilesTileServerTest.kt

**Interfaces:**
- The map renderer receives an immutable trail snapshot and stores its own immutable previous snapshot.
- Broken pipe, connection reset, and closed-peer write failures during canceled tile responses are treated as expected request cancellation; disk, parse, and lifecycle failures still fail loudly.

- [ ] **Step 1: Write failing regression tests**

Add a policy test proving that a trail ending at the current fix keeps the preceding connected segment. Add a server test that invokes the response writer with a closed client and verifies the worker survives while a real storage error is still reported.

- [ ] **Step 2: Run and verify failure**

~~~powershell
./gradlew.bat :composeApp:commonTest --tests '*RideMapTrailPolicyTest' --console=plain
./gradlew.bat :composeApp:testDebugUnitTest --tests '*AndroidOfflinePmtilesTileServerTest' --console=plain
~~~

- [ ] **Step 3: Implement immutable snapshots and narrow cancellation handling**

Pass trail.toList() from RootScreen, compare snapshots by value, and copy current.trail after rendering. Preserve the existing age, gap, accuracy, and distance filters. Catch only the socket exceptions that indicate MapLibre canceled a response, close the connection cleanly, and leave all other IOException paths visible in logs/tests.

- [ ] **Step 4: Run focused tests and emulator smoke**

Run Step 2, restart the emulator if necessary, install the debug APK, ride or inject several fixes, and verify the blue trail remains visible behind the location point after camera movement and route preview.

- [ ] **Step 5: Commit**

~~~powershell
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/RootScreen.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflinePmtilesTileServer.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapTrailPolicyTest.kt composeApp/src/androidTest/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflinePmtilesTileServerTest.kt
git commit -m "fix: keep ride trails visible through map updates"
~~~

### Task 7: Restore a calm map at every zoom and bring back 3D buildings

**Files:**
- Modify: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt
- Create: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/OfflineMapStyle.android.kt
- Create: composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/WorldOverview.android.kt
- Add/modify: composeApp/src/androidMain/assets/map/world-overview.geojson
- Modify: tools/offline-navigation/process.lua
- Modify: tools/offline-navigation/production/worker.py
- Create: tools/offline-navigation/test_process.py

**Interfaces:**
- OfflineMapStyle applies explicit zoom tiers: a sparse world overview below z8, major roads and selected labels at z8-z12, local streets and POIs from z12, and 3D building extrusion only from z13.
- WorldOverview is a bundled, simplified local GeoJSON layer and does not depend on a downloaded region.
- Processed building features expose numeric render_height and render_min_height values used by the extrusion layer.

- [ ] **Step 1: Write failing style/data tests**

Test the style contract for required zoom filters and the world overview source. Test the Lua processing fixture to ensure building height properties are emitted and default to safe low values when source height is absent. Test that the overview remains available with no installed region package.

- [ ] **Step 2: Run and verify failure**

~~~powershell
python -m unittest tools.offline-navigation.test_process -v
./gradlew.bat :composeApp:commonTest --tests '*OfflineMapStyle*' --console=plain
~~~

- [ ] **Step 3: Implement the tiered style and 3D data contract**

Move style construction into the dedicated file, add the bundled overview source, constrain transportation and labels by class/minzoom, and keep the dark blue-green Terra-like palette with fewer low-zoom lines. Add render height/base properties in process.lua and guard extrusion against invalid or extreme values. Keep route, trail, current-location, and HUD layers above the map style.

- [ ] **Step 4: Build the EKB fixture and run visual smoke**

Run the processor tests and regenerate only the local test fixture if needed. On the emulator verify city, regional, and globe-like zooms: no wireframe mess, a useful overview beyond downloaded tiles, and 3D buildings when close enough. Capture a screenshot and check logcat for tile/server crashes.

- [ ] **Step 5: Commit**

~~~powershell
git add composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/OfflineMapStyle.android.kt composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/WorldOverview.android.kt composeApp/src/androidMain/assets/map/world-overview.geojson tools/offline-navigation/process.lua tools/offline-navigation/production/worker.py tools/offline-navigation/test_process.py
git commit -m "fix: simplify offline map zoom levels and restore buildings"
~~~

### Task 8: Verify, build, publish the server-ready release, and finish safely

**Files/artifacts:**
- Modify: docs/runbooks/production-offline.md and deployment files as required by the implementation.
- Create: outputs/volty-0.7.8-production-versionCode31.apk (or the next semver patch selected by the current version state).
- Create: outputs/volty-server-<commit>.tgz without secrets, caches, build artifacts, or local state.

**Interfaces:**
- Release remains versionCode 31 with compile/target SDK unchanged; only versionName is bumped.
- The production catalog is signed with the existing offline catalog key and may list supported on-demand regions with latestRelease null only when the builder admission metadata is valid.
- No auto-publisher or offline-scheduler is enabled. The server bundle is copied to /home/sodovaya/volty and the existing Docker deployment is updated only through the documented manual commands.

- [ ] **Step 1: Run the complete verification matrix**

Run common tests, Android unit/instrumentation tests that are available, Python worker/package tests, compose config validation, release lint, and a clean release build. Check git diff, tracked-file status, APK SHA-256, versionCode/versionName, and that no keystore, env, catalog private key, cache, emulator, or generated database is tracked.

- [ ] **Step 2: Execute emulator acceptance checks**

Install the release APK, verify settings show a usable region list and on-demand states, download EKB, test search deduplication, route fit, trail continuity, offline relaunch, zoom tiers, and crash-free map tile cancellation. If the emulator is down, restart it and repeat the checks.

- [ ] **Step 3: Prepare and deploy the server bundle**

Push the tested commits to main, fast-forward /home/sodovaya/volty, validate the production env and Docker Compose without enabling the scheduler, rebuild/restart only the required offline services, and verify the catalog URL, package-service health, and static region package. Do not invent region metadata or require a production JSON/private key from the user; unsupported on-demand regions must remain clearly unavailable.

- [ ] **Step 4: Build and expose the final release artifact**

Copy the signed production APK and sanitized server bundle into the project outputs directory, compute hashes, and leave a concise server handoff with exact paths, commands, volume paths, and rollback/check commands.

- [ ] **Step 5: Put the computer to sleep only after all checks pass**

Use the Windows sleep command as the final external action. Only report completion after the release, server state, hashes, and clean diff are recorded.
