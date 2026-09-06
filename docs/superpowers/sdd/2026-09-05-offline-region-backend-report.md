# Offline region backend implementation — 2026-09-05

Correction after the user's production-readiness question: this report establishes
tested delivery of prebuilt packages, not a complete nationwide offline service.
An existing production artifact origin and an automatic build/publish pipeline were
assumed without evidence. Repository build/sign scripts exist, but automatic OSM
acquisition, nationwide region definitions and build/sign/publish scheduling are
not implemented. The original end-to-end production goal is therefore incomplete.

Implemented a private signed-package manager, public Ktor distribution API and
Android readiness integration in the existing dirty worktree. Existing unrelated
changes were preserved. No commit, APK, phone action or production deployment.

## Result

- Coverage lookup chooses the smallest available matching catalog region.
- Original Ed25519 catalog bytes and independently verified versioned manifests
  describe Valhalla routing, FTS4 search and PMTiles components.
- Missing regional packages are fetched from the configured pipeline artifact
  origin on client ensure, with queue/concurrency/disk/expansion/time limits.
- Signed sizes/hashes, routing archive safety/config references, SQLite schema and
  metadata and PMTiles headers are checked before atomic publication.
- Concurrent requests deduplicate work. Restart drops staging and revalidates
  published hashes. Active catalog releases survive pruning; retired releases have
  a grace interval. Persistent fingerprints prevent versioned URL reuse.
- Controlled operator ingest uses the same verifier; the prior unsafe shell
  signature-envelope check and arbitrary catalog replacement were removed.
- Android waits for the requested signed version, handles static CDN compatibility,
  bounds polling/cancellation, and rechecks network permissions before transfer.

API, ENV, limits, lifecycle and operator commands are documented in
[backend/OFFLINE.md](../../../backend/OFFLINE.md).

## Exact files changed by this task

This list excludes pre-existing user changes, generated Gradle/Python outputs and
the git-ignored execution ledger.

```text
.env.example
backend/API.md
backend/README.md
backend/OFFLINE.md
backend/src/main/kotlin/ru/sodovaya/volty/backend/Application.kt
backend/src/main/kotlin/ru/sodovaya/volty/backend/Database.kt
backend/src/main/kotlin/ru/sodovaya/volty/backend/OfflineRegionRoutes.kt
backend/src/test/kotlin/ru/sodovaya/volty/backend/OfflineRegionRoutesTest.kt
composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRegionPackageRepository.kt
composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/HttpOfflineRegionAcquisition.kt
composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/navigation/HttpOfflineRegionAcquisitionTest.kt
deploy.sh
deploy-offline.sh
docker-compose.yml
docs/superpowers/plans/2026-09-05-offline-region-backend.md
docs/superpowers/sdd/2026-09-05-offline-region-backend-report.md
tools/offline-navigation/Dockerfile.service
tools/offline-navigation/README.md
tools/offline-navigation/package-service.py
tools/offline-navigation/package_cache.py
tools/offline-navigation/package_validation.py
tools/offline-navigation/test_package_service.py
```

## Verification

- Entire backend suite: exactly **44 tests**, zero failures/errors; 5 Gradle tasks
  executed with `--no-build-cache --rerun-tasks`.
- Relevant app suites: exactly **56 tests**, zero failures/errors; 35 Gradle tasks
  executed with `--no-build-cache --rerun-tasks`.
- Python toolchain/service: **40 tests**, including **19 package-service tests**,
  passed both locally on Windows/Python 3.12 and in Linux Docker on homeserver.
- The new `Dockerfile.service` built successfully. The test image ran all 40 tests
  as UID 10001 with no external network, a read-only filesystem/source mount, and
  temporary writable `/tmp`. An initial test-harness directory permission error
  was corrected before the successful run.
- `docker compose --profile offline -f - config --quiet` passed on
  `homeserver` (192.168.1.141), using placeholder environment values and stdin;
  no Compose services were launched.
- `bash -n deploy.sh`, `bash -n deploy-offline.sh`, and `git diff --check` passed.

Red/green evidence included missing gateway/config implementation, empty streamed
catalog body, missing Content-Length, structured unavailable 404 bypass, missing
ingest, generic checksum failures, stale failed-release substitution, release URL
reuse after restart, missing ETag/If-Range handling, and incorrect PMTiles zoom
offsets. These were ordinary test runs, not mutation sweeps. Deliberate checksum
corruption fixtures log expected acquisition failures while their tests pass.

Reproduce from the repository root in PowerShell:

```powershell
.\gradlew.bat -p backend test --no-build-cache --rerun-tasks
$env:ANDROID_HOME='C:/Users/sodovaya/AppData/Local/Android/Sdk'
.\gradlew.bat :composeApp:testDebugUnitTest --tests '*HttpOfflineRegionAcquisitionTest' --tests '*OfflineFirstNavigationRepositoryTest' --tests '*OfflineRegionCatalogTest' --tests '*OfflineRegionAccessPolicyTest' --tests '*OfflineRegionDownload*Test' --tests '*OfflineDownloadPolicyTest' --tests '*OfflineRegionPackageManifestTest' --no-build-cache --rerun-tasks
py -3.12 -m unittest discover -s tools/offline-navigation -p 'test_*.py' -q
& 'C:/Program Files/Git/bin/bash.exe' -n deploy.sh
& 'C:/Program Files/Git/bin/bash.exe' -n deploy-offline.sh
git diff --check
Get-Content -Raw docker-compose.yml | ssh homeserver 'POSTGRES_PASSWORD=config-check VOLTY_JWT_SECRET=config-check LIVEKIT_API_KEY=config-check LIVEKIT_API_SECRET=config-check VOLTY_PUBLIC_IP=192.0.2.1 docker compose --profile offline -f - config --quiet'
```

## Boundaries

The server requires an existing signed catalog, corresponding pipeline artifacts
and the configured public trust anchor. It does not build an arbitrary area absent
from the catalog. Signing/building new regional datasets remains the pipeline's
job. Real production origins/keys were not configured or exercised.

Validation covers container/schema/integrity contracts; it does not prove native
Valhalla route quality or render actual map data on a device. Device UI/network
transition testing and a production smoke test were not performed. The full app
suite was not run; only the relevant 56 tests were selected. Ktor uses blocking
stream I/O on Dispatchers.IO, bounded by a 30-second read timeout; cancellation
cleanup may wait for that timeout. Cached catalog versions older than the default
seven-day retirement grace can require a catalog refresh before downloading.
