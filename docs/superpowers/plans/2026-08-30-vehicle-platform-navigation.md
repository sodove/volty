# Light Navigator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` task-by-task, with a fresh implementer, task review, and fix loop for every task. Steps use checkbox (`- [ ]`) syntax for tracking. The intended implementer is `gpt-5.6-luna` with `xhigh` reasoning.

**Goal:** Turn the existing map-first Light dashboard into an honest foreground navigator with destination search, one device-agnostic route policy, ordinary route alternatives, visual next-turn guidance, follow mode, off-route detection, rerouting, and confidence-gated arrival SoC.

**Architecture:** A root-retained `LightNavigationComponent` owns the navigation state machine, location demand, route selection, pure progress, reroute orchestration, and energy-confidence projection in common code. The Android `LocationManager` implementation is a shared app-scoped source, Ktor client/backend adapters hide the hosted route vendor, and `PlatformMapLayer.android.kt` becomes a MapLibre renderer of an immutable common `NavigationMapScene` plus gesture callbacks.

**Tech Stack:** Kotlin Multiplatform (Android target only), Compose Multiplatform, Decompose, Koin, kotlinx-coroutines/StateFlow, Ktor client/server, kotlinx.serialization, MapLibre Android 13.0.2, kotlin.test, Turbine, Ktor MockEngine/test host. SQLDelight remains unchanged for this MVP.

**Spec:** Product brief in this plan plus `docs/superpowers/specs/2026-08-22-smooth-map-follow-mode-design.md` and `docs/superpowers/specs/2026-08-27-nearby-live-map-stability-design.md`.

## Requirement amendment — 2026-08-30

Routes are device-agnostic. A Begode, scooter, bicycle, or any future vehicle uses the same
destination search, routing policy, alternatives, guidance, and rerouting contract. The active
vehicle may affect telemetry, BMS confidence, and arrival-charge estimation only; it must never
select a different route profile or create a different route request. This supersedes every older
reference in this plan to rider-confirmed `BICYCLE`, `LIGHT_EV`, or `MOTOR_SCOOTER` profiles.

The backend keeps one provider profile, configured as `VOLTY_NAV_PROFILE`. The legacy wire
`profile` field may be accepted and ignored during rollout so an older app cannot change routing,
but new app requests and responses do not expose it. No charging search is included. Arrival SoC
remains confidence-gated: it is shown only when BMS and consumption evidence have earned it.

## Global Constraints

1. Android target only, `minSdk 26`; use the API-31 `LocationRequest` branch and the existing legacy `requestLocationUpdates` branch for API 26–30.
2. Do not change BLE protocol behavior. In particular, **never write to Begode FFE1**. Navigation consumes already-earned repository state and GPS only.
3. Preserve the existing map host, blur/vignette, smooth frame-cadence marker/camera behavior, group markers, and OSM/OpenFreeMap attribution unless a task below explicitly changes their input boundary.
4. Route engine, progress, off-route, reroute decisions, trust gates, and UI decisions live in common/pure code. Compose renders component state. The Android MapLibre layer renders scene data and reports platform gestures/lifecycle only.
5. Distance-to-turn and maneuver guidance exist only for a selected valid route and a fresh, sufficiently accurate position. A stale/unknown/poor position clears guidance immediately; the last route may remain visible but visibly inactive.
6. Arrival SoC is never inferred from `soc`, `capacity`, power, distance, or consumption values whose earned flags/preconditions are false. A partial pack set, unknown SoC, unknown capacity, insufficient consumption window, stale telemetry, or non-finite input yields an explicit unknown reason and no numeric percentage.
7. Routing is transport-agnostic. Vehicle identity is not part of a route request and cannot change the provider policy. Vehicle-specific telemetry is allowed to affect only arrival-energy confidence.
8. Charging search is absent from domain types, provider requests, UI strings, tests, and rollout. Voice, offline routing, background navigation, traffic, lane guidance, favorites/history, map-tap destination, and multi-stop routing remain non-goals.
9. Strings are added to both `composeApp/src/commonMain/composeResources/values/strings.xml` and `values-ru/strings.xml`; never rely on Android backslash escapes. Russian is the field-test language.
10. There is no Compose UI test harness. Test state/reducers/mappers/policies in common code; use Android debug/release compilation and a device smoke for Compose, MapLibre, permission, and lifecycle wiring.
11. Do not add a SQLDelight migration for routes or destinations. Routing is session-scoped in MVP, so the current SQLite 3.19/minSdk migration constraints are not exercised.
12. Preserve all unrelated dirty work. As observed while writing this plan, the checkout was `main` ahead of `origin/main` with extensive tracked and untracked user work, including map, Light HUD, backend contracts, resources, manifest, and DI edits.

---

## Product Boundary and Acceptance Contract

### MVP rider flow

1. From the Light dashboard, the rider taps the existing navigation control.
2. A planning sheet accepts a text destination and shows debounced geocoding results. Selecting a result never starts navigation by itself.
3. After selecting a destination, the rider explicitly starts route construction. No vehicle profile choice is shown.
4. Volty requests up to three ordinary A-to-B alternatives. The map displays all returned routes, highlights the selected one, and the sheet shows distance/duration deltas. One returned route is valid; alternatives are optional provider output, not a success precondition.
5. The rider selects an alternative and starts navigation. The map follows the rider, the HUD shows a visual maneuver and distance-to-turn, and route remaining distance/ETA update from fresh GPS progress.
6. A gesture enters existing FREE camera mode. Recenter returns to FOLLOWING; moving auto-return retains the existing common follow policy.
7. Accuracy-aware off-route evidence is accumulated in common code. Once confirmed, old guidance disappears, the old route is visually stale, one reroute request is issued, and a replacement route atomically becomes active. Request generations prevent stale responses from resurrecting an old destination.
8. Clearing/stopping the destination cancels search/route/reroute jobs, increments the generation, releases navigation location demand, clears all route/progress/energy scene data, and returns to idle without disconnecting BLE or touching Nearby sharing.

### Required observable states

- `Idle`: no destination or route.
- `Planning`: query/result selection and an explicit route-build action; search may be loading, empty, failed, or rate-limited.
- `RouteReady`: valid alternatives and selected route; navigation not started.
- `Navigating`: route plus fresh progress; guidance may temporarily be unavailable when GPS is not trustworthy.
- `Rerouting`: destination/profile retained, old route rendered inactive, no old maneuver/distance claimed.
- `Arrived`: destination retained for acknowledgement; no next maneuver.
- Orthogonal `LocationStatus`: `NotRequested`, `PermissionRequired`, `PermissionDenied`, `ProviderDisabled`, `Searching`, `Fresh`, `Stale`, `PoorAccuracy`.
- Typed failures: `NoRoute`, `Offline`, `RateLimited(retryAfterSeconds)`, `ProviderUnavailable`, `InvalidRequest`, and malformed provider response. Initial provider failure stays in planning; reroute failure stays off-route with retry/stop controls.

### Acceptance criteria

- Search → destination selection → 1–3 alternatives → select → start works without a Nearby/social account.
- Begode, scooter+VESC Express, and bicycle+plain VESC use the same route contract; vehicle identity does not alter route geometry or provider policy.
- A valid route with a fresh fix shows a correct route line, next maneuver icon, non-negative distance-to-turn, remaining route distance, and ETA.
- Unknown/stale/poor GPS shows no maneuver distance and cannot advance progress, declare arrival, or trigger a reroute.
- Three accuracy-aware off-route fixes spanning at least two seconds trigger exactly one reroute; isolated GPS noise does not.
- A new destination, stop, or lifecycle teardown fences every in-flight result. An old response cannot restore a route after reset.
- Provider 429 honors `Retry-After`; no search, route, or reroute retry storm occurs.
- Arrival SoC is numeric only when all configured packs are online and have earned SoC/capacity and consumption has the minimum evidence window. Deliberately incoherent fixtures such as `soc = 80f, socKnown = false`, `powerW = 4200f, hasPower = false`, or `isPartial = true` produce no number.
- Existing Nearby markers, trail, map smoothing, FREE/FOLLOWING behavior, Light telemetry, and non-Light dashboards remain intact.
- Permission denial/revocation, GPS disabled, app background/foreground, process/activity recreation, offline provider, no-route, and destination reset have deterministic UI states and no crashes.

## Provider Decision

### Chosen initial integration: hosted GraphHopper behind Volty backend

Use a backend-owned `NavigationProvider` interface and a first `GraphHopperNavigationProvider`. The hosted API is the achievable pilot because its documented surface includes geocoding, route geometry, localized turn instructions, custom profiles, and alternative routes. Request unencoded coordinates (`points_encoded=false`) so the backend normalizes GeoJSON-like `[lon, lat]` arrays once; the app never decodes a vendor polyline or imports a vendor SDK. The key and provider profile IDs stay in backend environment variables.

Official references the implementer must re-open before coding the wire adapter:

- [GraphHopper Directions API overview](https://docs.graphhopper.com/openapi/section/explore-our-apis/api-explorer)
- [GraphHopper routing web API fields and response shape](https://github.com/graphhopper/graphhopper/blob/master/docs/web/api-doc.md)
- [GraphHopper alternative-route behavior](https://github.com/graphhopper/graphhopper/blob/master/docs/core/routing.md)
- [GraphHopper custom models](https://github.com/graphhopper/graphhopper/blob/master/docs/core/custom-models.md)

The backend uses one provider profile for personal mobility. Environment configuration supplies its provider profile ID:

```text
PERSONAL_MOBILITY -> VOLTY_NAV_PROFILE
```

No compiled default is allowed. Before enabling the provider, the rider must inspect a small local route corpus for the configured ID: ordinary road, cycleway, footway, high-speed road, and an access-restricted segment. If it is absent or semantically wrong, the provider remains disabled; it never falls back to `car`, `bike`, or another profile.

### Self-host/open-source path

The backend boundary also permits:

- self-hosted GraphHopper plus Photon, minimizing adapter drift from the pilot; or
- Valhalla plus a separately operated geocoder, gaining open-source dynamic costing and documented `bicycle`/`motor_scooter` costing while accepting more operations and a second external contract.

[Valhalla turn-by-turn](https://valhalla.github.io/valhalla/api/turn-by-turn/overview/) returns route shape and maneuvers and supports customizable costing. [OSRM](https://project-osrm.org/docs/v5.24.0/api/) supports alternatives, steps, and GeoJSON, but profiles are prepared statically and it has no geocoder, so it is a poorer initial fit for three explicit personal-EV policies. The public [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/) forbids client-side autocomplete, caps use at one request per second, and asks apps to proxy/cache and remain switchable; therefore public Nominatim is not the MVP geocoder. No provider name appears in Compose state or strings.

### Transport, privacy, and resilience rules

- App → Volty backend uses `/v1/navigation/search` and `/v1/navigation/routes`; the app never calls GraphHopper directly.
- The pilot endpoints are public so core navigation does not require social login. Apply dedicated per-IP quotas stricter than the existing global 120/min limiter: 20 searches/minute and 10 route calculations/minute, request size/coordinate bounds, and a hard maximum of three alternatives.
- Search is user-driven, minimum three trimmed characters, 350 ms debounce, previous-job cancellation, max eight results. Do not log query text, coordinates, provider keys, response bodies, or complete provider URLs.
- Keep only bounded in-memory caches: normalized search key for five minutes and route key for two minutes; no destination/location persistence in SQLDelight or logs.
- Backend maps upstream timeout/5xx to 503, no route to typed 422, and upstream/local quota to 429 with `Retry-After`. Initial planning retries only on rider action. Automatic rerouting gets at most two attempts for one off-route episode, after 2 s then 5 s, and never before `Retry-After`.

## Locked File/Unit Map

| Unit | Responsibility |
|---|---|
| `domain/navigation/NavigationModels.kt` | Vendor-free coordinates, destinations, routes, maneuvers, failures |
| `domain/navigation/NavigationRepository.kt` | Search/route port used by common component |
| `domain/location/RideLocation.kt` + `RideLocationRepository.kt` | Shared earned platform-fix state and demand contract |
| `data/location/AndroidRideLocationRepository.kt` | Sole Android `LocationManager` owner |
| `backend/src/main/kotlin/ru/sodovaya/volty/backend/NavigationProvider.kt` + `backend/src/main/kotlin/ru/sodovaya/volty/backend/GraphHopperNavigationProvider.kt` | Backend provider port and hosted adapter |
| `backend/src/main/kotlin/ru/sodovaya/volty/backend/NavigationRoutes.kt` | Public validated/rate-limited Volty navigation API |
| `data/navigation/HttpNavigationRepository.kt` | Ktor client adapter and typed failure mapping |
| `domain/navigation/RouteProgressEngine.kt` | Pure projection, maneuver progress, off-route, arrival |
| `domain/navigation/ArrivalEnergyEstimator.kt` | Pure earned-data gate and arrival SoC estimate |
| `data/navigation/BmsNavigationEnergySource.kt` | Repository/sample adapter producing consumption evidence |
| `presentation/navigation/NavigationReducer.kt` | Pure phase transitions and generation fencing |
| `presentation/navigation/LightNavigationComponent.kt` | Decompose/coroutine orchestration and retained state |
| `presentation/map/NavigationMapScene.kt` | Immutable common scene consumed by renderer |
| `presentation/navigation/LightNavigationOverlay.kt` | Compose-only planning/guidance/error UI |
| `presentation/map/PlatformMapLayer.android.kt` | MapLibre sources/layers/camera rendering only |

---

### Task 0: Establish the safe SDD workspace and provider go/no-go evidence

**Dependencies:** none. This task must complete before any implementation edit.

**Files:**
- Create at execution time: `.superpowers/sdd/2026-08-30-vehicle-platform-navigation/progress.md` (git-ignored ledger)
- Read: `AGENTS.md`
- Read: this plan and both specs named in the header
- Read: current `git status`, `git diff`, and every existing ledger whose task overlaps map/location/backend
- No production/test file changes

**Interfaces:**
- Produces: an isolated non-`main` execution workspace, recorded base commit, dirty-work preservation decision, provider profile corpus results, and per-task commit/test ledger.

- [ ] **Step 1: Audit the checkout without changing it.** Run `git status --short --branch`, `git diff --stat`, `git diff -- composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt`, and list `.superpowers/sdd/*/progress.md` with PowerShell. Record current branch, HEAD, tracked/untracked overlap, and whether the current dirty map/backend work is required by this plan.
- [ ] **Step 2: Get off `main` safely.** Invoke `superpowers:using-git-worktrees`. Because uncommitted changes cannot be silently moved into another worktree, do not stash, clean, reset, or copy them. If they are the intended base, ask the rider to either snapshot them on a non-main branch or explicitly authorize `git switch -c codex/light-navigation` carrying the working tree. If they are not the base, create an isolated worktree from the rider-selected committed branch. Stop until one safe route is explicit.
- [ ] **Step 3: Create the ledger.** Record plan path, worktree path, base commit, worker model/effort, dirty-work decision, task status, commits, RED evidence, GREEN evidence, review findings, and device observations. Read it before every resumed task.
- [ ] **Step 4: Validate the generic provider profile outside production code.** Run the five-case local route corpus through the official API explorer/curl, save only redacted summaries in the ledger, and confirm that the configured personal-mobility profile has the intended behavior. Do not record keys or precise home coordinates.
- [ ] **Step 5: Gate execution.** If the generic profile is unavailable or semantically wrong, keep routing disabled and record the blocker. Do not implement a hidden fallback.

---

### Task 1: Define the vendor-free navigation domain contract

**Dependencies:** Task 0.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/NavigationModels.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/NavigationRepository.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/NavigationModelsTest.kt`

**Interfaces:**

```kotlin
data class GeoCoordinate(val latitude: Double, val longitude: Double)
data class PlaceCandidate(val id: String, val title: String, val subtitle: String?, val coordinate: GeoCoordinate)
enum class ManeuverKind { DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, ROUNDABOUT, ARRIVE, UNKNOWN }
data class RouteManeuver(val id: String, val kind: ManeuverKind, val instruction: String, val streetName: String?, val shapeIndex: Int, val distanceMeters: Double)
data class RouteAlternative(val id: String, val distanceMeters: Double, val durationSeconds: Long, val geometry: List<GeoCoordinate>, val maneuvers: List<RouteManeuver>)
data class RoutePlan(val destination: PlaceCandidate, val alternatives: List<RouteAlternative>)
data class RouteRequest(val origin: GeoCoordinate, val destination: PlaceCandidate, val languageTag: String, val alternativesLimit: Int = 3)
sealed interface NavigationFailure { data object Offline; data object NoRoute; data class RateLimited(val retryAfterSeconds: Long); data object ProviderUnavailable; data class InvalidRequest(val reason: String); data object MalformedResponse }
sealed interface NavigationResult<out T> { data class Success<T>(val value: T) : NavigationResult<T>; data class Failure(val reason: NavigationFailure) : NavigationResult<Nothing> }
interface NavigationRepository {
    suspend fun search(query: String, near: GeoCoordinate?, languageTag: String): NavigationResult<List<PlaceCandidate>>
    suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan>
}
```

- [ ] **Step 1 — RED:** Add tests named `coordinate_rejects_non_finite_or_out_of_range_values`, `route_rejects_short_geometry_and_invalid_shape_indices`, `route_plan_accepts_one_route_and_caps_three`, and `route_request_has_no_vehicle_choice`. Use constructor failures; no provider fixtures yet.
- [ ] **Step 2 — prove RED:** Run `./gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.navigation.NavigationModelsTest"`. Expected: compilation failure because the domain types do not exist.
- [ ] **Step 3 — GREEN:** Implement only validation/invariants: finite coordinate ranges, non-negative finite distance, positive duration, geometry size ≥ 2, non-empty maneuvers ending in `ARRIVE`, `shapeIndex` within geometry, unique alternative IDs, and alternatives count in `1..3`. Keep serialization out of domain models.
- [ ] **Step 4 — prove GREEN:** Re-run the focused command; expected all tests pass.
- [ ] **Step 5 — review/commit:** Review for vendor words and accidental default profiles, then commit `feat: define navigation domain contracts` and record commit/tests in the ledger.

---

### Task 2: Create one shared Android location owner without changing map rendering yet

**Dependencies:** Task 1.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/location/RideLocation.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/location/RideLocationRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/location/LocationDemandPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/location/LocationDemandPolicyTest.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/location/RideLocationTest.kt`
- Create: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/location/AndroidRideLocationRepository.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/social/AndroidLocationProvider.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/social/LocationOnlySharingCoordinatorTest.kt`

**Interfaces:**

```kotlin
enum class LocationConsumer { MAP, NAVIGATION, SOCIAL_SHARING }
enum class LocationSource { GPS, NETWORK, PASSIVE }
data class RideLocationFix(val coordinate: GeoCoordinate, val accuracyMeters: Double, val speedMetersPerSecond: Double?, val bearingDegrees: Double?, val capturedAtEpochMillis: Long, val elapsedRealtimeMillis: Long?, val source: LocationSource)
sealed interface RideLocationStatus { data object NotRequested; data object PermissionRequired; data object PermissionDenied; data object ProviderDisabled; data object Searching; data class Available(val fix: RideLocationFix) }
data class RideLocationState(val status: RideLocationStatus = RideLocationStatus.NotRequested, val demands: Set<LocationConsumer> = emptySet())
interface RideLocationRepository {
    val requiredPermissions: List<String>
    val state: StateFlow<RideLocationState>
    suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean)
    suspend fun refreshPermissionAndProviders()
}
```

- [ ] **Step 1 — RED:** Test finite/range/accuracy invariants; `MAP` stopping must not stop an active `SOCIAL_SHARING` or `NAVIGATION` demand; duplicate enable/disable must be idempotent; no demand means `NotRequested`; an old generation callback is rejected. In the social test, start/stop sharing and assert the exact `SOCIAL_SHARING` demand issued rather than waiting on wall time.
- [ ] **Step 2 — prove RED:** Run the two location test classes and `LocationOnlySharingCoordinatorTest`; expected missing types/behavior.
- [ ] **Step 3 — GREEN common policy:** Implement a pure demand reducer and fix/provider precedence. Prefer GPS after the first accepted GPS fix, reject non-finite/out-of-order fixes using monotonic time when present, and expose raw accepted fixes; do not predict or route-match here.
- [ ] **Step 4 — GREEN Android owner:** Move the robust provider registration shape from `AndroidLocationProvider` into `AndroidRideLocationRepository`: transactional GPS/network/passive registration, replay one recent last-known fix, API 31+/legacy requests, generation fencing, permission/provider states, and main-dispatcher start/stop. Start platform updates when the first demand appears and remove them only after the last demand disappears.
- [ ] **Step 5 — social adapter:** Change `AndroidLocationProvider` to inject `RideLocationRepository`, map `Available` fixes to the existing 15-second `LocationSnapshot`, and toggle only `SOCIAL_SHARING`. Starting sharing remains the only action that publishes/collects social location; another consumer keeping GPS active must not publish by itself.
- [ ] **Step 6 — prove GREEN/build:** Run focused common tests, then `./gradlew.bat :composeApp:assembleDebug`. The Android repository has no fake-Android unit claim; compilation plus Task 11 device smoke is its wiring evidence.
- [ ] **Step 7 — review/commit:** Verify the old map still compiles and still owns its temporary second listener at this intermediate point; record that deliberate staging. Commit `refactor: add shared ride location source`.

---

### Task 3: Add the provider-neutral Volty backend navigation API and hosted adapter

**Dependencies:** Tasks 0–1. This can run in parallel with Task 2 only in a separate SDD worktree/commit queue.

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Database.kt`
- Modify: `backend/src/main/kotlin/ru/sodovaya/volty/backend/Application.kt`
- Modify: `.env.example` (preserve current user edits and merge only navigation keys)
- Create: `backend/src/main/kotlin/ru/sodovaya/volty/backend/NavigationModel.kt`
- Create: `backend/src/main/kotlin/ru/sodovaya/volty/backend/NavigationProvider.kt`
- Create: `backend/src/main/kotlin/ru/sodovaya/volty/backend/GraphHopperNavigationProvider.kt`
- Create: `backend/src/main/kotlin/ru/sodovaya/volty/backend/NavigationRoutes.kt`
- Create: `backend/src/test/kotlin/ru/sodovaya/volty/backend/NavigationProviderTest.kt`
- Create: `backend/src/test/kotlin/ru/sodovaya/volty/backend/NavigationEndpointContractTest.kt`
- Create: `backend/src/test/kotlin/ru/sodovaya/volty/backend/NavigationConfigTest.kt`

**Interfaces:**

```kotlin
data class GeoCoordinateDto(val latitude: Double, val longitude: Double)
data class ProviderSearchRequest(val query: String, val near: GeoCoordinateDto?, val languageTag: String, val limit: Int)
data class ProviderRouteRequest(val origin: GeoCoordinateDto, val destination: GeoCoordinateDto, val languageTag: String, val alternativesLimit: Int)
data class NavigationPlaceDto(val id: String, val title: String, val subtitle: String?, val latitude: Double, val longitude: Double)
data class NavigationManeuverDto(val id: String, val kind: String, val instruction: String, val streetName: String?, val shapeIndex: Int, val distanceMeters: Double)
data class NavigationRouteDto(val id: String, val distanceMeters: Double, val durationSeconds: Long, val geometry: List<GeoCoordinateDto>, val maneuvers: List<NavigationManeuverDto>)
data class NavigationRouteResponse(val schemaVersion: Int = 1, val destination: NavigationPlaceDto, val routes: List<NavigationRouteDto>)
sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>
    data object NoRoute : ProviderResult<Nothing>
    data class RateLimited(val retryAfterSeconds: Long?) : ProviderResult<Nothing>
    data object Unavailable : ProviderResult<Nothing>
    data object MalformedResponse : ProviderResult<Nothing>
}
interface NavigationProvider {
    suspend fun search(request: ProviderSearchRequest): ProviderResult<List<NavigationPlaceDto>>
    suspend fun routes(request: ProviderRouteRequest): ProviderResult<NavigationRouteResponse>
}
fun Route.installNavigationRoutes(dependencies: AppDependencies)
```

Wire DTOs mirror Task 1 names with primitive latitude/longitude fields and `schemaVersion = 1`. `AppConfig` gains `navigationProvider`, `graphHopperApiKey`, one optional `navigationProfileId`, provider timeouts, and `navigationEnabled`; `AppDependencies` receives an injectable `navigationProvider` and dedicated limiter/cache so test host never reaches the network.

- [ ] **Step 1 — RED provider fixtures:** Add redacted official-shape JSON fixtures inline in `NavigationProviderTest`. Assert `/geocode` request escaping/language/bias/max-eight behavior; `/route` sends two points, the configured generic profile ID, localized instructions, unencoded points, `alternative_route`, and max three; response normalization maps `[lon,lat]`, instruction sign/kind, shape indices, distance/time, and 1–3 paths. Assert malformed coordinates/indices fail closed.
- [ ] **Step 2 — RED endpoint contracts:** With a fake `NavigationProvider`, assert public search/routes work without JWT, input bounds reject before provider invocation, an arbitrary legacy Volty profile is ignored, one route is success, no-route is 422 with code `navigation_no_route`, provider failure is 503, and 429 includes exact `Retry-After`. Assert quota limits independently from social endpoints.
- [ ] **Step 3 — RED config:** Assert provider `disabled` requires no key; `graphhopper` requires a key and one non-blank `VOLTY_NAV_PROFILE`; secrets are absent from `toString`/errors. Run `./gradlew.bat -p backend test --tests "ru.sodovaya.volty.backend.Navigation*"`; expected failures.
- [ ] **Step 4 — GREEN dependencies/config:** Add Ktor client core/CIO/content-negotiation and test MockEngine dependencies using the existing backend Ktor 3.3.1 version. Add env parsing for `VOLTY_NAV_PROVIDER`, `GRAPHHOPPER_API_KEY`, and `VOLTY_NAV_PROFILE`. Add redacted placeholders to `.env.example`; do not add real IDs/keys.
- [ ] **Step 5 — GREEN adapter:** Implement the exact documented GraphHopper calls, a 5-second connect/request timeout, explicit `User-Agent`, no request/response logging, strict JSON parsing, and typed upstream mapping. Never pass a Volty enum text as a provider profile without looking it up in config.
- [ ] **Step 6 — GREEN routes:** Install public `/v1/navigation/search` and `/v1/navigation/routes` before the JWT block. Validate query length `3..160`, language tag length, finite coordinate ranges, destination title/id bounds, alternatives `1..3`, and body size. Add dedicated 20/min search and 10/min route limiters and bounded in-memory TTL caches.
- [ ] **Step 7 — prove GREEN:** Re-run backend navigation tests, then all backend tests with `./gradlew.bat -p backend test`. If the root wrapper cannot run the standalone build on the executor host, run installed `gradle -p backend test`; report the exact limitation rather than inferring success from compose tests.
- [ ] **Step 8 — review/commit:** Inspect logs/error envelopes for query/coordinate/key leakage. Commit `feat: add backend navigation provider boundary`.

---

### Task 4: Implement the Ktor client navigation repository

**Dependencies:** Tasks 1 and 3.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/HttpNavigationRepository.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/navigation/HttpNavigationRepositoryTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt`

**Interfaces:**
- Implements Task 1 `NavigationRepository`.
- Constructor: `HttpNavigationRepository(client: HttpClient = HttpClient(), baseUrl: String = "https://volty.sodove.ru/v1")`.
- Produces no auth/social dependency and no provider-specific model.

- [ ] **Step 1 — RED:** Using Ktor `MockEngine`, assert URL encoding and near-bias omission/presence, POST schema/alternatives without a vehicle profile, successful 1/2/3-route decoding, empty route list → `MalformedResponse`, 422 code → `NoRoute`, 429 + `Retry-After` → `RateLimited`, I/O exception → `Offline`, 503 → `ProviderUnavailable`, malformed JSON → `MalformedResponse`, and cancellation is rethrown rather than mapped.
- [ ] **Step 2 — prove RED:** Run `./gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.navigation.HttpNavigationRepositoryTest"`; expected missing implementation.
- [ ] **Step 3 — GREEN:** Implement private serializable DTOs, strict domain conversion, `Content-Type: application/json`, no bearer token, and bounded body decoding. Do not retry in the transport; orchestration owns retry policy.
- [ ] **Step 4 — DI:** Bind one `HttpNavigationRepository` as `NavigationRepository` in `AppModule.kt`. Reuse Ktor versions already present; add no vendor SDK.
- [ ] **Step 5 — prove GREEN/review/commit:** Run focused tests plus `:composeApp:assembleDebug`, review cancellation and failure mapping, and commit `feat: add navigation client transport`.

---

### Task 5: Build pure route progress, fresh-position, off-route, and arrival policies

**Dependencies:** Task 1.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/RouteProgressEngine.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/RouteProgressPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/RouteProgressEngineTest.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/RouteProgressPolicyTest.kt`

**Interfaces:**

```kotlin
enum class NavigationPositionProblem { UNKNOWN, STALE, POOR_ACCURACY }
data class RouteGuidance(val routeId: String, val maneuver: RouteManeuver, val distanceToManeuverMeters: Double, val remainingDistanceMeters: Double, val remainingDurationSeconds: Long, val projectedShapeIndex: Int)
sealed interface RouteProgressUpdate {
    data class Unavailable(val problem: NavigationPositionProblem) : RouteProgressUpdate
    data class OnRoute(val guidance: RouteGuidance) : RouteProgressUpdate
    data class OffRouteCandidate(val distanceFromRouteMeters: Double) : RouteProgressUpdate
    data class OffRouteConfirmed(val distanceFromRouteMeters: Double, val episodeId: Long) : RouteProgressUpdate
    data object Arrived : RouteProgressUpdate
}
class RouteProgressEngine(private val policy: RouteProgressPolicy = defaultRouteProgressPolicy) {
    fun reset(routeId: String?)
    fun update(route: RouteAlternative, fix: RideLocationFix?, nowEpochMillis: Long): RouteProgressUpdate
}
```

`defaultRouteProgressPolicy`: fresh age ≤ 5,000 ms; accuracy ≤ 50 m; off-route distance `max(30 m, 2 × accuracy)`; confirmation requires three consecutive fresh fixes spanning ≥ 2,000 ms; arrival requires remaining route distance ≤ 40 m and destination distance ≤ 25 m on two consecutive fresh fixes; backwards progress tolerance 30 m; reroute cooldown belongs to Task 7.

- [ ] **Step 1 — RED geometry/progress:** Build tiny fixed polylines and test projection at segment start/middle/end, loops choose a forward candidate near prior progress, distance-to-turn never negative, maneuver advances at its `shapeIndex`, remaining duration scales monotonically, and a new route ID resets progress.
- [ ] **Step 2 — RED trust:** Test null, 5,001-ms-old, >50-m accuracy, NaN, and out-of-order fixes. Each must return `Unavailable` and expose no `RouteGuidance`.
- [ ] **Step 3 — RED off-route/arrival:** Test one noisy point recovers, three fixes under two seconds do not confirm, third spanning two seconds confirms once with stable episode ID, accuracy expands the threshold, stale points reset evidence, and arrival needs two fresh qualifying points.
- [ ] **Step 4 — prove RED:** Run both focused test classes; expected missing types.
- [ ] **Step 5 — GREEN:** Implement haversine/segment projection in pure Kotlin, cumulative geometry distances, prior-progress search window with a full-route fallback, maneuver index lookup, and evidence counters. No clocks, coroutines, MapLibre, or Compose imports.
- [ ] **Step 6 — prove GREEN/review/commit:** Re-run focused tests, mutation-review every threshold comparison manually, and commit `feat: add pure navigation progress engine`.

---

### Task 6: Build the pure arrival-SoC confidence gate and BMS evidence adapter

**Dependencies:** Tasks 1 and 2. May precede Task 5.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/ArrivalEnergyEstimator.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/navigation/NavigationEnergySource.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/BmsNavigationEnergySource.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/navigation/ArrivalEnergyEstimatorTest.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/navigation/BmsNavigationEnergySourceTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt`

**Interfaces:**

```kotlin
enum class ConsumptionProvenance { CONTROLLER_COUNTERS, POWER_INTEGRAL }
data class ConsumptionEvidence(val whPerKm: Double, val distanceKm: Double, val durationMillis: Long, val measuredSampleCount: Int, val provenance: ConsumptionProvenance)
data class NavigationEnergyEvidence(val vehicleData: VehicleData, val motion: ControllerData, val consumption: ConsumptionEvidence?)
enum class ArrivalSocUnknownReason { NO_ROUTE, BMS_DISCONNECTED, PACKS_PARTIAL, SOC_UNEARNED, CAPACITY_UNEARNED, TELEMETRY_STALE, CONSUMPTION_UNEARNED }
sealed interface ArrivalSocEstimate { data class Known(val percent: Int, val approximate: Boolean = true) : ArrivalSocEstimate; data class Unknown(val reason: ArrivalSocUnknownReason) : ArrivalSocEstimate }
interface NavigationEnergySource { val evidence: StateFlow<NavigationEnergyEvidence> }
object ArrivalEnergyEstimator { fun estimate(evidence: NavigationEnergyEvidence, remainingDistanceMeters: Double?, nowEpochMillis: Long): ArrivalSocEstimate }
```

Numeric output requires: non-partial `VehicleData`; every configured/collapsed physical pack online with `socKnown`, finite SoC, finite positive capacity, and fresh timestamp; aggregate connected; consumption finite and positive; at least 2.0 km, 5 minutes, and 20 measured samples. Capacity energy uses the existing topology-aware aggregate `capacity × voltage`; no estimate is emitted when any capacity contributor is zero/unknown because `BmsData.capacity` has no separate known flag.

- [ ] **Step 1 — RED trust fixtures:** Use deliberately incoherent fixtures: `soc = 80f, socKnown = false`; two-pack vehicle with one offline; one online pack capacity zero; `powerW = 4200f, hasPower = false`; route distance null; consumption at 1.99 km, 4:59, or 19 samples; stale BMS; NaN. Assert the exact unknown reason and no recoverable numeric field.
- [ ] **Step 2 — RED known path:** Test parallel and series aggregate capacity semantics, measured-counter precedence over integral, regen/net non-positive consumption refusal, percent clamp only after all gates, and a deterministic known estimate.
- [ ] **Step 3 — prove RED:** Run both focused classes; expected missing implementation.
- [ ] **Step 4 — GREEN source:** Adapt `BmsRepository.activeVehicleData`, `activeMotion`, and `motionSamples(RideEnergy.SESSION_WINDOW)`. Use `MotionReadings.sessionWhPerKm` for earned controller counters; otherwise use `RideEnergy.windowedRide` and retain its distance/duration/sample provenance. Reset evidence on vehicle/session change. Do not mutate `ControllerData.hasEnergyCounters`.
- [ ] **Step 5 — GREEN estimator:** Implement the pure gate in the listed order so partial/missing BMS cannot be masked by a plausible aggregate. Return `Known` only after every predicate passes; keep `approximate = true` in MVP.
- [ ] **Step 6 — prove GREEN/review/commit:** Run focused tests. Review specifically against Part N’s absent-contributor rule. Bind `BmsNavigationEnergySource` as `NavigationEnergySource`, commit `feat: gate navigation arrival energy estimates`.

---

### Task 7: Implement the retained navigation reducer and planning/route-ready component

**Dependencies:** Tasks 1, 2, 4, and 6.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/navigation/NavigationReducer.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationComponent.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/navigation/NavigationReducerTest.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationComponentPlanningTest.kt`

**Interfaces:**

```kotlin
enum class LocationUiStatus { NOT_REQUESTED, PERMISSION_REQUIRED, PERMISSION_DENIED, PROVIDER_DISABLED, SEARCHING, FRESH, STALE, POOR_ACCURACY }
sealed interface NavigationPhase {
    data object Idle : NavigationPhase
    data class Planning(
        val query: String,
        val searchResults: List<PlaceCandidate>,
        val destination: PlaceCandidate?,
        val requestInFlight: Boolean,
        val failure: NavigationFailure?
    ) : NavigationPhase
    data class RouteReady(val plan: RoutePlan, val selectedRouteId: String) : NavigationPhase
    data class Navigating(val plan: RoutePlan, val selectedRouteId: String, val guidance: RouteGuidance?) : NavigationPhase
    data class Rerouting(val plan: RoutePlan, val selectedRouteId: String, val attempt: Int, val failure: NavigationFailure?) : NavigationPhase
    data class Arrived(val plan: RoutePlan, val selectedRouteId: String) : NavigationPhase
}
data class LightNavigationState(
    val phase: NavigationPhase = NavigationPhase.Idle,
    val locationStatus: LocationUiStatus = LocationUiStatus.NOT_REQUESTED,
    val arrivalSoc: ArrivalSocEstimate = ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.NO_ROUTE),
    val followState: RideMapFollowState = RideMapFollowState(),
    val requestGeneration: Long = 0
)
interface LightNavigationComponent {
    val state: StateFlow<LightNavigationState>
    fun onPlannerRequested()
    fun onQueryChanged(query: String)
    fun onPlaceSelected(place: PlaceCandidate)
    fun onAlternativeSelected(routeId: String)
    fun onStartNavigation()
    fun onRetry()
    fun onStopNavigation()
    fun onMapVisibilityChanged(visible: Boolean)
    fun onLocationPermissionResult(granted: Boolean)
    fun onCameraGesture(nowElapsedMillis: Long)
    fun onRecenterRequested()
    fun close()
}
```

- [ ] **Step 1 — RED reducer:** Test the exact state matrix: idle→planning; query change clears old result/route; place selection provides the route intent; one route is valid; alternative selection is by stable ID; stop from every phase returns a fully empty scene/guidance/energy state and increments generation.
- [ ] **Step 2 — RED component concurrency:** With fake repository and `runTest`, assert `<3` characters issues nothing, 350 ms debounce issues once, a newer query cancels/fences the older result, route requests require destination+fresh origin, old generation response is ignored after stop, 429 exposes retry time, and retry is explicit. Do not start an unbounded delayed ticker in `runTest`; inject clocks and advance bounded jobs.
- [ ] **Step 3 — prove RED:** Run the two focused classes; expected missing types.
- [ ] **Step 4 — GREEN reducer/component:** Implement immutable events/reducer plus a Decompose component with `SupervisorJob`, injected epoch/elapsed clocks, request generation, and child jobs for search/route. Keep search results and provider failure typed; do not place resource strings in component state.
- [ ] **Step 5 — location demand:** `onPlannerRequested`/recenter requests permission state; `onMapVisibilityChanged(true)` enables `MAP` only after permission; route construction enables `NAVIGATION`; idle/stop/close releases only the component’s demands. A denied permission leaves planning available for search but disables route construction with a typed location state.
- [ ] **Step 6 — prove GREEN/review/commit:** Run focused tests and `:composeApp:testDebugUnitTest`. Review all request-generation branches, then commit `feat: add retained navigation planning state`.

---

### Task 8: Integrate progress, follow, off-route, rerouting, failures, and destination reset

**Dependencies:** Tasks 5–7.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/navigation/NavigationReducer.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapFollowPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationComponentProgressTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapFollowPolicyTest.kt`

**Interfaces:**
- Consumes: `RideLocationRepository.state`, `RouteProgressEngine`, selected `RouteAlternative`, existing `RideMapFollowState`, and Task 7 generation/jobs.
- Produces: guidance only from `RouteProgressUpdate.OnRoute`, `Rerouting` episodes, retry schedule state, and recenter/follow commands for the map scene.

- [ ] **Step 1 — RED navigation progress:** Assert start requires `RouteReady` + fresh position; fresh fixes update guidance; stale/poor fixes immediately null guidance while preserving inactive route; `Arrived` requires engine evidence; acknowledgement/stop resets destination; switching destination resets engine and generation.
- [ ] **Step 2 — RED reroute:** Assert one off-route candidate does nothing; confirmed episode issues one route call from the latest fresh fix; repeated fixes/in-flight calls issue none; success atomically resets progress on the replacement; stale result after stop/new destination is ignored; failure attempts at 2 s then 5 s only, max two; 429 waits its `Retry-After`; manual retry remains available.
- [ ] **Step 3 — RED lifecycle/follow:** Assert background releases platform demand and marks GPS unavailable without discarding destination/route; foreground reacquires and reconciles; gesture enters FREE; recenter enters FOLLOWING; existing moving auto-return thresholds remain unchanged. Assert on repository calls/state, not wall time.
- [ ] **Step 4 — prove RED:** Run progress and follow tests; expected new failures.
- [ ] **Step 5 — GREEN:** Collect location in the component, feed only valid fixes to the engine, schedule bounded reroute jobs, and preserve route/destination across foreground stop. Rerouting clears guidance before network I/O. A failed reroute never revives old maneuver text.
- [ ] **Step 6 — destination reset audit:** Ensure `onStopNavigation` cancels search, route, retry, and location collection jobs; increments generation; resets engine/follow state; releases `NAVIGATION`; and leaves `SOCIAL_SHARING` demand untouched.
- [ ] **Step 7 — prove GREEN/review/commit:** Run focused tests and full common suite, review every phase × failure branch, commit `feat: add navigation progress and rerouting`.

---

### Task 9: Project immutable navigation state into a renderer-only map scene

**Dependencies:** Tasks 2, 5, 7, and 8.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/NavigationMapScene.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/NavigationMapRenderPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/NavigationMapRenderPolicyTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/presentation/map/PlatformMapLayer.android.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/RideMapHostPolicy.kt`
- Test: existing `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/*Test.kt`

**Interfaces:**

```kotlin
data class NavigationRouteLine(val routeId: String, val points: List<GeoCoordinate>, val selected: Boolean, val active: Boolean, val completedFraction: Double)
data class NavigationTrailPoint(val coordinate: GeoCoordinate, val sample: RideMapTrailSample)
sealed interface MapCameraRequest {
    val sequence: Long
    data class FitAlternatives(override val sequence: Long, val points: List<GeoCoordinate>) : MapCameraRequest
    data class FollowFix(override val sequence: Long, val fix: RideLocationFix) : MapCameraRequest
    data class Recenter(override val sequence: Long, val fix: RideLocationFix) : MapCameraRequest
}
data class NavigationMapScene(val ownFix: RideLocationFix?, val trail: List<NavigationTrailPoint>, val participantMarkers: List<ParticipantMarker>, val routes: List<NavigationRouteLine>, val destination: GeoCoordinate?, val followState: RideMapFollowState, val cameraRequest: MapCameraRequest?)
@Composable expect fun PlatformRideMapLayer(scene: NavigationMapScene, darkTheme: Boolean, onCameraGesture: (Long) -> Unit, modifier: Modifier = Modifier)
```

- [ ] **Step 1 — RED scene policy:** Test route-ready fits all alternatives once; selected route has stronger style/z-order; navigating follows own fix and does not fit bounds; completed/remaining split is clamped; rerouting keeps old route inactive; reset produces no route/destination source; participant markers remain independent; FREE preserves user camera.
- [ ] **Step 2 — prove RED:** Run `NavigationMapRenderPolicyTest` and existing map policy tests; expected missing scene API.
- [ ] **Step 3 — remove platform ownership:** Delete location permission requests, `LocationManager`, location listener, last-known lookup, raw `own`/trail ownership, and progress/follow decisions from `PlatformMapLayer.android.kt`. Do not delete the common motion estimator/policies; the common component/scene builder may continue to use them. The Android file receives already-selected scene values.
- [ ] **Step 4 — render routes:** Add stable GeoJSON sources/layers for inactive alternatives, selected route, completed route segment, and destination marker. Reuse sources across updates; style reload reconstructs them. Preserve own marker, participant markers, trail, city labels, buildings, blur, texture mode, attribution, and frame-cadence camera smoothing.
- [ ] **Step 5 — gesture/camera callbacks:** Report MapLibre API gestures with elapsed time; never call reducer logic in Android. Apply fit/follow/recenter requests idempotently by request sequence. No per-fix `easeCamera` queue.
- [ ] **Step 6 — prove GREEN:** Run all map policy tests and `./gradlew.bat :composeApp:assembleDebug`. Inspect the Android file: imports may include MapLibre/AndroidView/lifecycle rendering, but no `LocationManager`, route provider, progress engine, BMS repository, or navigation reducer.
- [ ] **Step 7 — review/commit:** Device behavior remains unclaimed until Task 12 smoke. Commit `refactor: make MapLibre a navigation scene renderer`.

---

### Task 10: Add pure UI mapping, visual maneuver guidance, planning, alternatives, and honest unknown copy

**Dependencies:** Tasks 6–9.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationUiMapper.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationOverlay.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationUiMapperTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/LightRideDashboard.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-ru/strings.xml`

**Interfaces:**
- `LightNavigationUiMapper.map(state, units)` produces display values/resource keys only: phase, destination/search state, alternatives, maneuver icon kind, distance/duration, GPS/provider banner, and arrival-SoC visibility/reason.
- `LightNavigationOverlay(state, callbacks, modifier)` contains no repository, clock, route projection, retry calculation, or provider logic.

- [ ] **Step 1 — RED mapper:** Test all phase/status combinations; metric/imperial distance formatting through existing `UnitFormatter`; every `ManeuverKind` maps to a visual icon category; stale GPS hides maneuver distance; rerouting hides old guidance; numeric arrival SoC appears only for `Known`; each unknown reason maps to a non-numeric resource key; planning exposes no vehicle profile choice.
- [ ] **Step 2 — prove RED:** Run the focused mapper test; expected missing types.
- [ ] **Step 3 — GREEN planner UI:** Replace the existing no-op `Icons.Default.Navigation` action with planner open. Render search field/results, explicit route construction, route loading/failure/no-route, and alternative cards. Start is disabled until destination/route/fresh origin are valid.
- [ ] **Step 4 — GREEN guidance UI:** Render a large next-turn icon/banner above the existing telemetry, distance to turn, street/instruction fallback, remaining/ETA, rerouting/GPS banners, and stop/reset control. Keep Light controls readable over map/vignette and avoid covering group sheet controls.
- [ ] **Step 5 — permission UI:** Use the existing `rememberLauncherForActivityResult(RequestMultiplePermissions())` pattern only after the rider taps navigation/recenter/permission CTA. Return the result to the component; never poll permission in Compose.
- [ ] **Step 6 — localization:** Add English and Russian strings for search, route construction/alternatives, start/stop, rerouting, arrival, GPS statuses, provider/rate/no-route failures, and each unknown energy reason. Replace the existing hard-coded Russian map-control content descriptions touched by this task with resources in both files.
- [ ] **Step 7 — prove GREEN/build:** Run mapper tests and `:composeApp:assembleDebug`. Do not add fake Compose tests. Manually inspect compact/medium/wide behavior in Task 12.
- [ ] **Step 8 — review/commit:** Verify there is no charging, voice, provider name, or hard-coded Russian left in touched navigation UI. Commit `feat: add Light navigation planning and guidance UI`.

---

### Task 11: Wire root retention, Decompose lifecycle, Koin, location scene, and energy updates

**Dependencies:** Tasks 2, 4, and 6–10.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/LightRideDashboard.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/ru/sodovaya/volty/di/AndroidModule.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/root/RootNavigationTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/root/RootNavigationChromePolicyTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/map/RideMapHostPolicyTest.kt`
- Create: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/navigation/LightNavigationLifecycleTest.kt`

**Interfaces:**
- `RootComponent` exposes one retained `val navigation: LightNavigationComponent` alongside stack/social state.
- `DefaultRootComponent` constructs it once from Koin ports and closes it from root lifecycle; changing Decompose tabs never recreates it.
- `RootScreen` collects navigation state, builds/passes `NavigationMapScene`, and passes component callbacks down to `RideDashboardScreen`/`LightRideDashboard`.

- [ ] **Step 1 — RED retention:** Assert the same navigation component survives Ride→Battery/Nearby→Ride; switching vehicle resets route/destination/energy but not the component instance; root destroy closes exactly once; GroupMap/Nearby do not acquire own-location demand; Light Ride map becomes visible without auto-requesting permission.
- [ ] **Step 2 — RED lifecycle:** Assert root stop/background releases `MAP`/`NAVIGATION`, preserves route intent, and root start reacquires only still-needed demands. Activity/root recreation with saved Decompose stack does not restore an unearned active route from SQL/preferences because MVP persists none.
- [ ] **Step 3 — prove RED:** Run the four focused test classes; expected interface/wiring failures.
- [ ] **Step 4 — GREEN root/Koin:** Bind `RideLocationRepository`, `NavigationRepository`, and `NavigationEnergySource` once. Construct the component once in `DefaultRootComponent`; do not inject repositories into Compose. Collect BMS evidence in the component and recompute arrival estimate from remaining route distance.
- [ ] **Step 5 — GREEN scene host:** Replace `RootScreen`’s local `gpsSpeedKmh` callback/location ownership with component location scene. Preserve GPS speed fallback to `LightDashboardMapper` only when the shared fix is fresh; stale shared GPS cannot become a dashboard speed.
- [ ] **Step 6 — nearby separation:** Verify Nearby can keep social sharing active while Light navigation stops and vice versa. The single platform registration may remain active, but each consumer’s publication/rendering follows its own demand and consent.
- [ ] **Step 7 — prove GREEN:** Run focused tests, full `:composeApp:testDebugUnitTest`, and `:composeApp:assembleDebug`.
- [ ] **Step 8 — review/commit:** Review root teardown, Koin scope, duplicate collectors, and dirty-file overlap. Commit `feat: integrate retained Light navigation`.

---

### Task 12: Aggregate review, backend/app verification, rollout, and three-vehicle smoke

**Dependencies:** Tasks 0–11.

**Files:**
- Modify only files required by review findings; every change returns to the owning task’s RED/GREEN cycle.
- Update at execution time: `.superpowers/sdd/2026-08-30-vehicle-platform-navigation/progress.md`
- Do not create a new smoke document; record observations in the ledger and final field report requested by the rider.

**Interfaces:**
- Produces: reviewed commits, exact verification evidence, provider/profile readiness, and an explicit rollout decision. No merge to `main` without user authorization.

- [ ] **Step 1: Diff audit.** Run `git status --short --branch`, `git diff --check`, and compare changed paths to this plan. Verify unrelated pre-existing changes are byte-for-byte preserved and no secret/provider key entered git.
- [ ] **Step 2: Task reviews.** Run a fresh spec-compliance reviewer and code-quality reviewer over each task commit, then an aggregate review. Fix Critical/Important findings with the original implementer and re-run affected RED/GREEN tests.
- [ ] **Step 3: Backend verification.** Run `./gradlew.bat -p backend test`; assert navigation endpoint/provider/config tests and existing backend contracts all execute. Record test count and command output path.
- [ ] **Step 4: App verification.** Run `./gradlew.bat :composeApp:testDebugUnitTest`, `./gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration` (proves no schema drift despite no migration), and `./gradlew.bat :composeApp:assembleRelease`. A one-second cached success is not fresh evidence; use `--rerun-tasks` for the final focused navigation set and record exact test counts.
- [ ] **Step 5: Generic device smoke.** On API 26-equivalent emulator/device and current device: deny permission, deny permanently, grant, revoke in settings, disable GPS, network-only first fix, stale GPS, app background/foreground, rotate/recreate Activity, offline backend, 429, 503, no route, one route, three alternatives, gesture FREE, recenter FOLLOWING, destination change during in-flight route, stop during reroute, and arrival acknowledgement. Inspect compact/medium/wide overlays; verify attribution and Nearby markers remain visible.
- [ ] **Step 6: Begode ET Max/EXN smoke.** Use the same generic route policy, inspect route choices, navigate/off-route/reroute, and verify logs/diff contain no new BLE write path and no FFE1 write. During the known partial-pack warm-up, arrival SoC must be unknown even if aggregate `soc` is numerically plausible; only show a number if every Task 6 gate later becomes true.
- [ ] **Step 7: Scooter + VESC Express smoke.** Confirm the same generic route policy, guidance/reroute work independently of the gateway’s unusual controller response, and arrival SoC appears only with complete ANT BMS capacity plus sufficient measured/synthesized consumption.
- [ ] **Step 8: Bicycle + plain VESC smoke.** Confirm the same generic route policy and navigation work even when plain-VESC telemetry is absent/partial; arrival SoC remains unknown until BMS/capacity/consumption evidence is earned. Navigation must not redial or otherwise alter BLE behavior.
- [ ] **Step 9: Rollout gate.** Deploy backend with `VOLTY_NAV_PROVIDER=disabled`, verify stable unavailable UI, then enable GraphHopper with the single generic `VOLTY_NAV_PROFILE` only after corpus and contract tests. Watch 429/503 counts without logging queries/coordinates. Keep immediate rollback as provider disable; app remains a working map-first Light dashboard.
- [ ] **Step 10: Handoff.** Record commits, commands, test counts, device/profile outcomes, known limitations, and any disabled profile in the ledger. Use `superpowers:finishing-a-development-branch`; do not merge/push to `main` without explicit rider direction.

## Non-goals

- Charging-station search or charging-aware routing.
- Voice prompts, TTS/audio focus, vibration maneuver cues, media ducking, or headset controls. The normalized `ManeuverKind`/`RouteGuidance` stream is the future extension point; MVP has no audio code.
- Background/locked-screen navigation, foreground location service, Android Auto, Wear OS, or notifications.
- Offline maps/routing, prefetch, route persistence/resume after process death, destination history/favorites, multi-stop trips, round trips, traffic, closures, lane guidance, speed-limit warnings, elevation-aware energy, weather/wind, or group-route synchronization.
- Automatic route-policy inference from BLE names/controller type, and any legal claim that a returned route is permitted for every jurisdiction.
- Changes to BLE protocol cadence, controller commands, alarms, vehicle composer, SQLDelight schema, map tile provider, or Nearby/voice contracts.

## Principal Risks and Mitigations

| Risk | Mitigation / release gate |
|---|---|
| Personal-EV access rules differ by jurisdiction | One explicit personal-mobility provider profile; it is configured and corpus-tested before enablement; no universal fallback |
| GPS currently belongs to MapLibre and social has another owner | Task 2 introduces one demand-counted source; Task 9 removes the map listener only after shared-source tests/build pass |
| Noisy GPS causes reroute storms | Freshness/accuracy gate, three fixes over two seconds, episode IDs, one in-flight request, cooldown, max two automatic attempts |
| Old async response restores reset destination | Monotonic request generation on query/destination/stop/vehicle changes; tests hold and release fake responses |
| Provider key/cost/privacy exposure | Backend-only key, public endpoint quotas, bounded cache, input limits, no query/coordinate/body logging, kill switch |
| Hosted provider behavior or pricing changes | Vendor-free app contract, backend adapter, contract fixtures, self-host GraphHopper/Photon or Valhalla adapter path |
| Public geocoder policy violation | Do not use public Nominatim autocomplete; hosted geocoder initially, self-operated geocoder later |
| Partial BMS creates plausible but false arrival charge | Full per-pack online/SoC/capacity gate plus consumption evidence; incoherent fixtures; no numeric fallback |
| Capacity and Wh/km are technically present but statistically weak | Minimum distance/time/sample gates and approximate marker; elevation/weather model remains out of scope |
| Renderer refactor regresses smooth map/blur/group markers | Existing map tests remain; immutable scene policy tests; preserve cached root host/TextureView/frame loop; device smoke |
| Compose behavior cannot be unit-tested here | Keep decisions in reducer/mapper/policies; compile Android and state plainly what device smoke proves |
| Dirty `main` loses user work | Task 0 blocks implementation until a non-main isolated base is explicit; no stash/reset/clean/cross-shell file movement |

## Integration Order Summary

```text
Task 0 safe workspace + profile evidence
  ├─ Task 1 domain contracts
  │    ├─ Task 2 shared location
  │    ├─ Task 3 backend provider/API ─ Task 4 client transport
  │    └─ Task 5 progress engine
  ├─ Task 6 energy gate
  └─ Tasks 7–8 retained state machine
         ├─ Task 9 MapLibre scene renderer
         └─ Task 10 Light UI/localization
                └─ Task 11 root/DI/lifecycle integration
                       └─ Task 12 verification + rollout
```

Task 3 may be implemented in parallel with Task 2 after Task 1, and Task 6 may be implemented in parallel with Task 5. All other ordering is load-bearing.

---

## Requirement amendment — free OSM navigation directly from the app (2026-08-30)

This amendment supersedes the hosted GraphHopper rollout path for the MVP. The rider chose a
free online OSM stack, with no Volty navigation backend dependency. The app uses Photon for
Russian-first place search and the public FOSSGIS OSRM service for routing. Both calls are
bounded, attribution-preserving, and rate-limit aware; the app does not send vehicle identity,
controller type, battery data, or a device/profile selector to either service.

The routing profile is fixed in the provider adapter (`routed-bike`) and is not part of the
domain request, UI, or route identity. `alternatives=true` means ordinary navigator alternatives
returned by OSRM, not faster/economical/vehicle-specific variants. Charging-station search and
charge-aware routing remain out of scope. Offline BRouter is a future fallback only; we must not
claim offline routing until OSM segment data is shipped and exercised on-device.

### Task 13: replace backend navigation transport with direct Photon + OSRM

**Dependencies:** Tasks 1, 4, 7–11.

**Files:**
- Create `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/navigation/OsmNavigationRepository.kt`.
- Create focused common tests for Russian Photon parameters, OSRM request/response mapping, alternatives, maneuvers, no-route, rate-limit, malformed, and offline failures.
- Modify `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/di/AppModule.kt` to bind the OSM repository.
- Remove obsolete GraphHopper-only deployment defaults from `.env.example`, `docker-compose.yml`, and `deploy.sh`; retain backend navigation code only if existing backend contracts still require it, but the app must never call it.
- Update the SDD ledger with exact RED/GREEN evidence and public-service limitations.

**Interfaces and behavior:**
- Photon request: `q`, `lang=default` for Russian locale because the current public Photon instance rejects `lang=ru`, `limit`, optional GPS bias, and `Accept-Language: ru-RU,ru`; preserve Cyrillic without logging queries or coordinates. `default` keeps the OSM-local Russian names; this was verified live with `Плотинка` near Екатеринбург.
- OSRM request: fixed `routed-bike` endpoint, `overview=full`, `geometries=geojson`, `steps=true`, and `alternatives=true`.
- Parse only bounded response bodies. Normalize GeoJSON coordinates, ordinary route alternatives, localized maneuver labels, and an explicit final arrival maneuver.
- Map 429/5xx/4xx, `NoRoute`, malformed payloads, cancellation, and network failure into the existing typed domain result without retries or fabricated route data.
- Use a descriptive User-Agent/attribution contract suitable for the public services; cache/debounce remains owned by the existing component.

- [x] **Step 1 — RED:** Add repository contract tests with MockEngine; assert the exact Cyrillic query, Photon `lang=default`, locale header, fixed OSRM endpoint/parameters, route/maneuver/alternative mapping, bounded payload behavior, and typed failures. Prove they fail before the direct provider exists.
- [x] **Step 2 — GREEN:** Implement Photon/OSRM transport and strict domain mapping. Preserve cancellation and never expose vehicle/device/profile inputs.
- [x] **Step 3 — GREEN wiring:** Bind the direct repository in Koin and remove misleading GraphHopper deployment automation/defaults. Do not create a production `.env` or secret.
- [x] **Step 4 — prove GREEN:** Run focused tests, full app tests, migration verification, backend tests, and release build. Exercise live Photon/OSRM with a public Russian query and generic coordinates from host, then reinstall the debug APK and repeat the no-crash navigation smoke.
- [x] **Step 5 — review:** Fresh task review and diff audit; record public endpoint limits/no-SLA and the explicit fact that offline routing is not yet shipped.

### Self-review of this amendment

- No device type, transport type, battery state, charging station, or backend credential enters
  the navigation contract.
- Russian search is explicit (Cyrillic query, Photon `lang=default`, locale header, GPS bias) and
  is covered by a request-level test plus a live `Плотинка` check. The public instance currently
  rejects `lang=ru`; a future self-hosted Photon can opt into a supported language configuration.
- Public services are acceptable for the rider's low-volume MVP but are not treated as an SLA;
  429/5xx and offline behavior remain visible in the existing UI.
- A future BRouter fallback is named as a separate deliverable and cannot silently be represented
  by an online error message as if offline maps were available.

## Requirement amendment — alternatives, Light HUD polish, and offline routing (2026-08-31)

The rider reports that the Alatyr → Osnova Center request still presents only the direct route,
the navigation panel does not belong to the Light dashboard visual language, and navigation has no
motion. Offline navigation is now in scope, subject to an honest regional-coverage boundary.

### Confirmed defects

- `OsmNavigationRepository` returns the first successful OSRM response. A successful response with
  one route therefore prevents the second identical road-profile endpoint from contributing its
  alternatives. The adapter must aggregate successful responses when fewer than three unique
  routes are available, namespace route IDs, and deduplicate equivalent geometry/distance results.
- `RoutePlan` and the reducer already retain all supplied alternatives; no UI or reducer truncation
  is allowed. One returned route remains valid when both providers return only one.
- The navigation overlay is a nearly opaque full-width Material surface positioned above the Light
  HUD. It must become a bounded Light-specific HUD card with existing `surfaceContainer`/palette
  language, compact guidance mode, semantic controls, and animated phase/content changes.
- `NO_ROUTE` is the default empty-state reason and is incorrectly rendered after a valid route has
  loaded when progress/energy evidence is not yet available. A valid route must never display a
  “no route found” arrival-energy reason.
- During stale/poor GPS guidance is cleared, but the selected route is still rendered as active and
  the follow camera can consume the stale fix. Stale navigation must render the route inactive and
  must not drive follow-camera movement; recenter may still use the last known fix.

### Offline boundary

- Add an Android-only BRouter-backed repository using one fixed `volty` profile for every vehicle.
- Offline routing uses a downloaded regional package containing BRouter `.rd5` segments, the fixed
  profile, manifest/version/checksums, and a matching offline map region. Installation is atomic;
  failed or interrupted updates keep the previous package usable.
- The first package targets a small Russian region around Yekaterinburg. Do not inflate the APK or
  imply Russia-wide/worldwide offline coverage.
- Offline destination selection initially supports map point, coordinates, and recent/cached
  online destinations. Photon address search remains online-only and is labelled accordingly;
  there is no fake offline geocoder.
- The online repository remains available as fallback when no installed offline package covers both
  endpoints. When a covered package exists, route calculation must not touch the network.
- Offline map attribution and BRouter/OSM licenses remain visible without network access.

### Implementation slices and acceptance tests

1. Aggregate both online OSRM responses, with unique IDs, deterministic deduplication, and tests for
   one-plus-two, duplicate, provider-failure, and limit-one cases.
2. Correct arrival-energy copy/state and stale route/camera rendering; add reducer, mapper, and map
   policy regressions.
3. Replace the overlay with bounded Light HUD cards, real maneuver vectors, semantic selection,
   localized copy, and phase/route/loading/progress animations. Verify compact, medium, and wide
   layouts on the emulator; Compose rendering itself remains device-tested rather than unit-tested.
4. Add offline package domain/manager, BRouter Android adapter, offline map-region lifecycle, and
   deterministic tests for coverage, checksum/atomic replacement, route parsing, no-network mode,
   and license/attribution state.
5. Run full Android/backend suites, migration check, release build, online and offline emulator
   smoke, stale GPS, provider failures, alternatives, reroute, recreation, and BLE-write scan.

## Requirement amendment — Valhalla Mobile and complete offline MVP (2026-09-03)

The 2026-08-31 offline-boundary section is retracted where it names BRouter as the target engine,
keeps offline address search out of scope, or defers the offline map. The current product decision
is the corrected Sol plan: **Valhalla Mobile + a full regional package containing routing, offline
geocoding, and PMTiles map data**. BRouter remains only a measured fallback if the Valhalla gate
fails; both engines must not ship in the production app.

The same amendment also retracts the proposed `TransportAccessProfile` enum (`EUC`, scooter,
bicycle). The app and domain request use one generic `VoltyRide` profile. The four user-visible
styles are `FAST_WITH_HIGHWAYS`, `FAST_WITHOUT_HIGHWAYS`, `CURVY`, and `MAX_CURVY_TOURING`.
Speed preference (20–130 km/h) affects costing only; it never grants vehicle access or creates a
jurisdictional restriction. No type-derived motorway ban is allowed. Real OSM access and safety
restrictions remain engine data, not a hidden vehicle selector.

### Decision gate before implementation

The target library is currently published as `io.github.rallista:valhalla-mobile:0.6.3`. Its Android
AAR was inspected on 2026-09-03: 10,455,999 bytes and native libraries for `arm64-v8a`,
`armeabi-v7a`, `x86`, and `x86_64`. The wrapper exposes local `route`/`routeRaw` calls and config
factories for a tiles directory or tile extract. See the [Valhalla Mobile README](https://github.com/Rallista/valhalla-mobile)
and [Valhalla tile specification](https://github.com/valhalla/valhalla-docs/blob/master/tiles.md).

The full gate is not passed yet: ARM64 hardware/emulation and the final process/lifecycle check
remain open. The x86_64 debug subgate was exercised on 2026-09-03 with the real EKB `tiles.tar`
from `ekb-package-v0.1.1`: the published AAR loaded in 239 ms, returned three route trips (with
the `alternates` field present) in 171 ms, and produced a 20,133-byte response without a native
crash. The throwaway APK was 43,689,550 bytes and the post-route emulator sample was 101,987 KiB
PSS / 172,572 KiB RSS; these are measurements for the gate, not release budgets. Before deleting
the BRouter prototype, exercise the same package through ARM64, repeat a cold start and multiple
route/close cycles, and measure peak memory/native size and lifecycle stability. Debug must also
exercise x86_64; release must exclude x86 and x86_64. PASS requires a usable first route, at least
one genuinely distinct alternative on the route corpus, no native crash, and agreed size/memory
budgets. FAIL keeps BRouter as a separately documented fallback decision, not as an unseen second
production engine.

### MVP boundary after the gate

MVP includes the regional catalog and downloader from search/route/map/Settings; staged,
checksum-validated, atomic install with rollback; full offline autocomplete/geocoding; Valhalla
offline routing; the four styles with progressive diversity filtering; and PMTiles map rendering
with automatic local/online source selection. The pilot is a logical Yekaterinburg agglomeration
region with a routing buffer, not a large APK asset.

The manifest is the source of truth for exact download/installed sizes. Provisional pilot budgeting
is 135–360 MB downloaded and 200–540 MB installed across routing, search, and map components; the UI
must show only published manifest values. Post-MVP: delta updates, LRU cleanup, seamless multi-region
routing, advanced scenic signals, traffic, and custom map styles.

### First implementation slices

1. Complete the Valhalla gate without changing the current provider or removing BRouter assets.
2. Add common contracts and tests for `VoltyRide`, the four styles, coverage, package states,
   progressive route events, and route diversity.
3. Build reproducible Yekaterinburg Valhalla/search/PMTiles artifacts and a signed manifest.
4. Implement the multi-region package repository, resumable downloads, network policy, recovery,
   and Settings surface.
5. Add the offline geocoder, PMTiles source selector, and Valhalla route adapter in separate
   increments; only after each failing test is observed may production behavior be added.
6. Integrate search/route/map auto-download and online parity, then remove the old bundled BRouter
   flow only after the end-to-end gate and device smoke pass.

### Execution checkpoint — regional artifact toolchain (2026-09-03)

The first real regional dataset was built on `sodovaya@192.168.1.141`, where Docker and the
existing `/home/sodovaya/nyxmap/sverdlovsk.osm.pbf` are available. The reproducible tooling now
lives in `tools/offline-navigation/`; it uses an Ubuntu 24.04 tool image with `osmium-tool`,
Tilemaker, Tippecanoe, SQLite, the Valhalla Docker image, and the Protomaps MBTiles converter.

Evidence from the EKB pilot build:

- Valhalla built 98 routing tiles and a 63 MiB tile extract from the smart EKB extract. A local
  service smoke returned three routes in about 13 ms; the same three routes were returned after
  extracting the packaged routing archive: 2.233 km / 168 s, 3.601 km / 279 s, and 4.342 km / 340 s.
- The FTS4 index contains 276,841 searchable OSM features. A prefix query for `плот*` returns
  Russian results with coordinates, so autocomplete no longer needs a complete query or network.
- PMTiles is zoom 5–14, contains the declared vector layers, and passes the converter's structural
  verification.
- Release `0.1.1` was rebuilt on the remote Docker host after syncing the current manifest tool:
  84,089,008 bytes downloaded (about 80.2 MiB) and about 175 MB after installation: routing
  25,339,889/72,191,286 bytes, search 14,176,803/58,347,520 bytes, and map
  44,572,316/44,572,316 bytes (download/installed). The package passes the component verifier,
  the FTS4 smoke still finds `Плотинка`, and the packaged Valhalla smoke still returns three
  route trips. The checked-in toolchain does not include these generated artifacts or any
  signing key.

This checkpoint does not pass the Valhalla Mobile Android gate: the package was tested through the
Valhalla service, not through the published Android wrapper, and the manifest is deliberately
`UNSIGNED_DEV`. The build also reports incomplete regional admin polygons and omits timezone and
elevation data; route graph construction and the packaged route smoke succeed, but admin/timezone
behavior remains a release-gate item. The next implementation step is the ARM64/x86_64 wrapper
smoke with this real package, followed by signed catalog/downloader integration. BRouter remains
untouched until that gate is passed or explicitly failed.

The release tooling now includes `sign-manifest.py`. It uses an external Ed25519 PEM key for
regional manifests; the APK's `123.jks` remains Android artifact signing material and is not used
for package verification. The signer removes the nullable fields omitted by the Kotlinx
serialization codec, signs the compact UTF-8 payload, verifies the generated signature before the
atomic write, and never copies the private key into the repository. A real EKB unsigned manifest
was signed with an ephemeral key and verified with `cryptography`; the Python payload SHA-256 and
the payload emitted by the actual Kotlin manifest codec both equal
`91418f9dcba3fea7d480410fdb09fbc901d826a8220d1e951b46b52992440ca9`. The remote Ubuntu tool image
also now contains `python3-cryptography` and starts successfully. Package scripts derive the
PMTiles filename from `region_id`, and the verifier rejects packages with zero or multiple map
archives.

`build-catalog.py` now assembles the HTTPS-facing catalog from a metadata spec and signed regional
manifests. It rejects unsigned or non-Ed25519 releases, duplicate/invalid region IDs, mismatched
manifest IDs, malformed bounds, and logical bounds outside signed coverage. A remote smoke against
the real EKB `manifest.unsigned.json` signed it with an ephemeral Ed25519 key, produced a
deterministic one-region catalog, and rejected the unsigned input; local `python3 -m py_compile`
also passes for all four tooling scripts.

The Android package store and catalog repository now receive the installed APK's actual package
`versionCode` instead of a duplicated literal. This keeps manifest compatibility checks correct
when the app version is bumped for a release.

The planner now exposes the four generic route styles (`fast`, `fast without highways`, `curvy`,
and `maximum curvy touring`) plus a 20–130 km/h speed slider. The selected values are carried into
the Valhalla `auto` costing request and reused for reroutes; no EUC/scooter/bicycle selector or
type-derived motorway rule was added. RED coverage checks reducer state, UI mapping, and the
component's actual request payload. Both Russian and fallback English strings are present.

The direct OSM fallback now starts its two route-provider requests concurrently whenever more than
one alternative is requested, then aggregates their responses in deterministic primary-first
order. A 35 m corridor tolerance keeps genuinely parallel roads instead of collapsing them as
duplicates, while the exact-duplicate and limit-one behaviors remain unchanged. This shortens the
slow-provider path without fabricating alternatives when both public routers return the same road.

### Execution checkpoint — regional runtime contracts (2026-09-03)

The common/runtime implementation now includes a strict catalog and release download plan,
background offline-first selection, FTS4 prefix search, a resumable Android HTTP downloader, and
atomic Android package installation. Search/route requests continue online while a missing region
downloads in the background; a ready region is selected locally on the next request. The package
store extracts all three published components before changing its active pointer and rejects
unsigned releases through the manifest policy plus an injected Ed25519 verifier.

The Valhalla JSON codec requests up to three alternatives with generic `auto` costing, maps
polyline6/legs/maneuvers into the existing route contract, and applies the existing geometry
diversity filter. The Android bridge now creates the version-matched AAR config through
`ValhallaConfigFactory.usingTileExtract(...)` and closes each native engine after a request; it
remains intentionally unbound until the ARM64 gate is complete. No BRouter assets were removed,
no APK/Gradle application build was run, and Settings/DI/PMTiles renderer wiring remains after
the gate.

### Execution checkpoint — offline source wiring and latency guard — 2026-09-03

The regional repository is now wired behind build-time catalog/key metadata: a build without the
configured HTTPS catalog remains on the existing online/BRouter path, while a configured build
can refresh the signed catalog, expose region lifecycle actions in Settings, and use one shared
package store for search, route, and map. Mobile-data downloads remain approval-gated unless the
user enables the Settings switch. Search/route fallback on metered data now queues that region for
later approval instead of silently losing the automatic download request.

The repository also reconstructs verified installed package states from the active local
manifests before a catalog refresh. Therefore a previously downloaded region remains usable after
a process restart with no network; a later catalog refresh supplies its canonical display name
and release metadata and retains local-only verified packages rather than hiding them.

The first-request catalog refresh is genuinely asynchronous: it is launched in the background and
never makes search or route construction wait for the catalog's network timeout. Automatic map
downloads have a retry cooldown, so repeated GPS updates cannot create a retry storm after a
network failure or an offline transition.

PMTiles is served through a loopback-only local vector-tile endpoint because MapLibre Android
13.0.2 exposes no native PMTiles protocol callback. A real 44,572,316-byte EKB archive was read
through that endpoint: a z10 tile returned 611,182 bytes and TileJSON was valid. Cleartext is
explicitly permitted only for `127.0.0.1`/`localhost`; all other traffic remains HTTPS-only. The
initial offline style is deliberately label-free until glyph assets and the final map style are
selected; routing/search/map package mechanics are still independent of that presentation step.

The published Valhalla Mobile AAR passed five fresh x86_64 cold-start/route/close cycles against
the real EKB routing extract, each returning three trips and `alternates` without a native crash.
The ARM64 system image was installed on the remote host, but Android's QEMU2 emulator refuses an
ARM64 guest on this x86_64 host; ARM64 hardware or an ARM64 host remains required to close the
gate. No production Gradle/APK build was run. BRouter stays present and is not removed until the
ARM64/lifecycle/size gate plus configured signed-catalog smoke are complete.

### Execution checkpoint — BRouter alternatives and ARM64 artifact audit — 2026-09-03

The reported "90% one route" behavior had a concrete local cause: the old BRouter fallback
always used a `firstResultBudget` with `maxAlternatives = 1`, so its alternative loop could never
reach indexes 1 or 2. The fallback now honors the requested limit up to three alternatives,
launches one independent `RoutingEngine` per alternative concurrently, preserves deterministic
primary-first ordering, and applies the shared route-diversity filter before renumbering route and
maneuver IDs. A limit of one still performs exactly one calculation.

A throwaway JVM smoke against the bundled EKB `.rd5` confirmed that BRouter alternative indexes
`0`, `1`, and `2` all return usable tracks (125, 85, and 125 nodes respectively, with distinct
formatted JSON hashes). Five concurrent three-index runs completed without a routing-engine
failure, so the parallel adapter is based on observed behavior rather than only API assumptions.

The upstream `io.github.rallista:valhalla-mobile:0.6.3` artifact was also audited rather than
rebuilding the native library: the AAR contains `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`
wrapper binaries. The corresponding ARM64 `.so` is present in the Gradle cache and in the fresh
throwaway gate APK. No Android ARM64 emulator is available on the current Windows or remote
x86_64 hosts: Android Emulator rejects an ARM64 system image before boot, even with `-no-accel`.
The ARM64 runtime gate therefore still needs physical ARM64 hardware or an ARM64 host; this does
not block the BRouter source fix or the existing x86_64 Valhalla smoke.

The asynchronous catalog boundary had one more race: a first search/route could fall back online
while the catalog was still loading, observe no region entries, and lose the automatic download
request permanently. The offline-first repository now retains the refresh job and re-evaluates the
same request coordinates after it completes. This queues the current region without delaying the
online result, while the existing metered-data policy still turns the queued request into a prompt
unless the rider enabled the no-prompt setting. A regression test covers the suspended-refresh
interleaving and confirms that it queues exactly one region after catalog publication.

### Execution checkpoint — upstream AAR release verification — 2026-09-03

The Android bridge now consumes the ready `io.github.rallista:valhalla-mobile:0.6.3` artifact
directly, together with its version-matched models/config artifacts; Valhalla itself was not
rebuilt. Reflection was removed from the production path, so R8 renaming cannot break engine
creation. A clean `:composeApp:assembleRelease --rerun-tasks` completed successfully, and the full
`:composeApp:testDebugUnitTest --rerun-tasks` suite completed successfully. The signed `0.7.6`
APK is 79,431,244 bytes and contains only the release ABIs `arm64-v8a` and `armeabi-v7a`.

This verifies packaging and JVM wiring, not native execution on ARM64 hardware. The ARM64
Valhalla Mobile smoke therefore remains the next release gate; BRouter assets stay present until
that gate, the configured signed-catalog smoke, and the end-to-end regional package path pass.

### Retraction — routing artifact version alignment — 2026-09-03

The earlier regional-artifact checkpoint described the pilot routing data as Valhalla 3.8.3. That
was the service-container version used for the first data smoke, not the engine shipped in the
ready `valhalla-mobile:0.6.3` AAR. The upstream mobile tag points at Valhalla 3.6.3, so the 3.8.3
regional artifact is not a release candidate for this APK. The artifact toolchain now defaults to
the pinned amd64 Valhalla 3.6.3 image and records `valhalla-3.6.3` in new manifests. The old
3.8.3 package remains a diagnostic artifact only and must not enter the signed catalog.

### Execution checkpoint — matching EKB regional candidate — 2026-09-03

The pilot package was rebuilt on `sodovaya@192.168.1.141` as
`/home/sodovaya/volty-navigation-build/ekb-package-v0.1.2` with the pinned Valhalla 3.6.3
tile compiler and the ready mobile engine's declared routing-data version. The app runtime is
the already-built `io.github.rallista:valhalla-mobile:0.6.3` Android AAR; the Docker image only
compiles regional OSM data and does not rebuild that mobile library. The package verifier accepted
all three components and 276,841 FTS4 rows; the Valhalla 3.6.3 service smoke returned three trip
branches including alternatives. The manifest reports 82,401,294 bytes downloaded and
173,498,489 bytes installed: routing 23,657,305/70,583,783, search 14,176,803/58,347,520, and
PMTiles 44,567,186/44,567,186.

An ephemeral Ed25519 key was used only to exercise the signer and catalog builder; its private
key, signed manifest, and catalog were deleted. This proves the publishing mechanics, not a
production release: the real catalog still requires the deployment signing key and HTTPS object
storage. The package itself remains outside git.

### Execution checkpoint — routing format fail-closed gate — 2026-09-03

The regional compatibility policy now requires the exact `valhalla-3.6.3` tile format consumed by
the ready `valhalla-mobile:0.6.3` Android AAR, rather than accepting any non-empty routing version.
The check is used by the common download-plan/catalog path and by the Android package store both
when installing and when reconstructing an active package after restart. The publisher CLI applies
the same default and exposes `--routing-data-version` for a deliberate, reviewed engine upgrade;
its mismatch regression test and Python 3 compile check pass. A direct store install cannot create
a `READY` package for an incompatible core even if a future caller bypasses the repository.

### Execution checkpoint — location-independent offline autocomplete — 2026-09-03

Offline autocomplete no longer requires a current GPS fix. When the query is valid and at least one
regional package is `READY` or `UPDATE_AVAILABLE`, the repository queries every distinct installed
region's local FTS index concurrently, merges results in deterministic region order, removes duplicate
candidate IDs, and applies the requested result limit. With a location, the existing coverage-based
single-region path remains in place for fast proximity ranking. If no region is installed, the
location-free request keeps the existing online fallback while connected and returns the typed offline
failure without a network call when fully offline. A common repository test covers two installed
regions, concurrent fan-out, deterministic merge, deduplication, and the zero-online-call contract.

### Execution checkpoint — verify catalog releases before download — 2026-09-03

The Android catalog repository now applies the injected Ed25519 verifier to every advertised
`latestRelease` before publishing the catalog or allowing any component download. Structural
validation and the package-store install check remain in place, so a forged or otherwise
unverified release fails closed before network bytes are committed to staging. Verification errors
are treated as invalid signatures, and catalog entries without a downloadable release remain valid.
The common signature-policy regression test passes; no production key or catalog endpoint was added.

### Execution checkpoint — bounded relevance for local autocomplete — 2026-09-03

The regional FTS adapter now reads a bounded candidate window (at most eight times the requested
limit), scores exact display-name matches and title prefixes ahead of address-only matches, then
returns the original requested limit. GPS-aware searches still prioritize proximity, using
relevance only as a stable tie-breaker; location-free searches use relevance first. The ranking and
normalization rules are pure common code with focused regression tests, while the Android adapter
continues to bind all query values and never interpolates user text into SQL.
