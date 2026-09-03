# Offline routing feasibility report

Date: 2026-08-31

## Result

The smallest real offline foundation is implemented. It does not pretend to
route: it defines and verifies which local package is trustworthy and whether
a route's origin and destination are covered by it.

Implemented files:

- `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineRoutingPackagePolicy.kt`
  - immutable serializable manifest models for format/package versions, package
    identity, rectangular latitude/longitude bounds, and declared payload files;
  - strict `manifest.json` parsing;
  - validation of format/version, package ID, finite geographic bounds, safe
    payload names, duplicate names, SHA-256 syntax, missing/unexpected files,
    and actual-vs-declared checksums;
  - inclusive origin+destination coverage policy with typed uncovered endpoint;
  - device-agnostic policy explicitly has no network fallback;
  - activation decision that only allows a validated candidate and otherwise
    retains the previous package while requesting staging cleanup.
- `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineRoutingPackageManager.kt`
  - accepts only a local downloaded directory containing `manifest.json` and
    exactly its declared regular payload files;
  - validates source, copies through a unique staging directory, re-hashes the
    staged and final package, and publishes an atomic pointer under
    `context.filesDir/offline-routing`;
  - never replaces the old pointer until the new package is fully copied and
    validated, so validation, storage, and interruption failures leave the
    previous valid package active;
  - removes interrupted staging directories and temporary pointer files at
    construction and before each install;
  - exposes `activeManifest` and validated `activeCoverage`;
  - checks the thread interrupt flag while hashing/copying and cleans staging on
    interruption.
- `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/offline/OfflineRoutingPackagePolicyTest.kt`
  - 9 tests for endpoint coverage, malformed JSON, bounds/version/name
    validation, checksum mismatch, valid package validation, failed-install
    retention, interruption cleanup decision, activation gating, and the
    no-network/device-agnostic policy.

## Package contract

The accepted directory shape is:

```text
downloaded-package/
  manifest.json
  segments.dat
  <other declared payload files>
```

`manifest.json` uses the following shape:

```json
{
  "formatVersion": 1,
  "packageVersion": 3,
  "packageId": "ural",
  "bounds": {"south": 55.0, "west": 60.0, "north": 57.0, "east": 62.0},
  "files": [{"name": "segments.dat", "sha256": "<64 hex characters>"}]
}
```

Payload names are single safe path components; traversal, separators,
`manifest.json`, duplicates, and undeclared files are rejected. Bounds are
inclusive and do not wrap across the antimeridian.

## Verification

Commands run:

```text
.\gradlew.bat :composeApp:testDebugUnitTest --tests ru.sodovaya.volty.domain.navigation.offline.OfflineRoutingPackagePolicyTest --no-daemon --no-build-cache
```

Result: `BUILD SUCCESSFUL`; XML result for the new class: 9 tests, 0 failures,
0 errors, 0 skipped.

```text
.\gradlew.bat :composeApp:testDebugUnitTest --no-daemon --no-build-cache
```

Result: `BUILD SUCCESSFUL`; 2270 tests, 0 failures/errors in 197 XML result
files.

```text
.\gradlew.bat :composeApp:compileDebugKotlinAndroid --no-daemon --no-build-cache
```

Result: `BUILD SUCCESSFUL`. Existing project warnings remain; no warning was
introduced by the offline files.

The first red TDD run failed because the new offline symbols did not yet
exist. The subsequent green runs compiled the Android source and passed the
focused and full suites.

## BRouter feasibility and remaining integration

The local feasibility check found no BRouter references in the repository, no
BRouter entry in `composeApp/build.gradle.kts` or `gradle/libs.versions.toml`,
and no matching artifact in the local Gradle cache. A bounded local filesystem
scan was also stopped after it found no candidate and began traversing unrelated
user data; no user files were changed.

The upstream project does have two real integration surfaces:

- the published [Maven package](https://github.com/abrensch/brouter/packages/118857)
  `org.btools:brouter-core:1.7.10`;
- the Android service contract `btools.routingapp.IBRouterService`, whose
  concrete operation is `getTrackFromParams(Bundle)`.

The service contract is documented in upstream's
[AIDL](https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl)
and [Android-service documentation](https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md).
The [upstream integration discussion](https://github.com/abrensch/brouter/issues/149)
also states that there is no well-documented embedded/in-process API, so a
direct `RoutingEngine` adapter would be guesswork without pinning and inspecting
a chosen core artifact. Neither surface is available locally without adding
dependency/repository or Android service wiring, which this task explicitly
forbade. No BRouter adapter was therefore added.

The current generic manifest can carry BRouter-related files, but it is not yet
a BRouter data contract: BRouter's own distribution organizes routing data as
5-by-5-degree `segments4` files and also needs compatible profiles/configuration.
The next implementer must choose whether to embed the pinned core or bind to an
installed BRouter service, then align package contents, profile selection,
result parsing, and the existing `RoutePlan` model with that choice.

Still required for a usable offline route feature:

1. choose/approve the routing engine and package format it actually consumes;
2. add the required build/service integration and pin the exact BRouter API;
3. add a downloader that writes the agreed directory format into a local
   download location (the manager itself never performs network I/O);
4. wire the manager and a BRouter adapter through DI/repository integration;
5. map engine routes into the existing `RoutePlan` model and surface typed
   uncovered/invalid-package states in UI;
6. add Android filesystem/instrumentation coverage for real `Context.filesDir`
   failure and process-death scenarios. The requested test-location scope here
   allowed only common pure tests, so the committed tests cover the shared
   transaction decision semantics rather than requiring Robolectric or an
   instrumented source set.
