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

The regional offline Valhalla path keeps one transport-agnostic `auto` costing. Valhalla 3.6.3
does not expose a road-curvature signal, so the named touring styles may adjust highway
willingness but must not promise scenic or curvy geometry. Route diversity comes from requesting
up to three alternatives and accepting only genuinely distinct geometry. Neutral toll/ferry
preferences use the engine midpoint; avoidance uses the documented zero value. Unpaved avoidance
uses `exclude_unpaved`; unsupported `use_unpaved` and invented curvature penalties are forbidden.
Access, one-way, closure, and restriction handling stays at the engine's safe defaults.

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

### Execution checkpoint — ARM64 Valhalla package smoke under QEMU — 2026-09-03

The EKB `v0.1.2` routing archive was mounted into the multi-architecture Valhalla image on the
remote x86_64 host after installing the ARM64 binfmt handler. The ARM64 image loaded all 110 tiles,
reported ready in two seconds, and returned a successful route with one alternative. This verifies
that the regional tile format is readable and routable by the ARM64 Valhalla service under QEMU;
it is not an Android ARM64 `.so` execution test. Physical ARM64 hardware or an ARM64 host is still
required for the Valhalla Mobile Android gate, along with lifecycle, size/memory, and configured
signed-catalog end-to-end checks.

### Execution checkpoint — timezone-complete package and recovery hardening — 2026-09-03

The host artifact toolchain now generates the timezone database with the pinned
Valhalla image's `valhalla_build_timezones` helper, packages it alongside
`tiles.tar`, `admins.sqlite`, and `valhalla.json`, and verifies both the required
files and their config references before a package can be accepted. The EKB
candidate was rebuilt remotely as `ekb-package-v0.1.3`; the manifest reports
158,268,225 bytes downloaded and 293,311,018 bytes installed across routing,
search, and PMTiles. The external verifier found 276,841 FTS4 rows and all four
routing entries.

The exact Valhalla 3.6.3 ARM64 service image loaded all 110 tiles under QEMU and
returned a route with one alternative. This is still Linux service evidence, not
execution of the Android `valhalla-mobile` ARM64 `.so`; the Android hardware/host
gate remains open. The Android package store now rejects incomplete routing data,
removes failed-install download staging so it cannot loop at 100%, and garbage
collects unreferenced published packages after restart or pointer publication.
Host tool tests pass; no APK/Gradle build, production signature, HTTPS catalog, or
device Android ARM64 smoke was performed.

### Execution checkpoint — automatic download UX and network recovery — 2026-09-03

The regional package lifecycle now retries `WAITING_FOR_NETWORK`/queued automatic downloads
when Android reports connectivity, while a rider-selected `PAUSED` download is never resumed by
a later search or route request. The offline-first repository also retries catalog discovery when
verified local packages exist but the catalog has not loaded, so one failed startup refresh no
longer hides new regions forever.

Metered automatic downloads are now visible as a root-level confirmation dialog, including when
the rider is on the map or planner rather than Settings; confirming continues the same resumable
download, and dismissing leaves it available in Settings. Settings now shows catalog refresh
progress/errors, asks before deleting a ready region, displays the advertised update version, and
distinguishes network wait, mobile approval, queue, and deletion states. Failed states render the
recorded failure category. No APK/Gradle build was run; the changed Kotlin/UI paths still require
the normal compile/test gate once application builds are allowed.

### Execution checkpoint — release validation and catalog recovery — 2026-09-03

The regional publisher now verifies each signed manifest independently with the
expected Ed25519 public key and key ID before adding it to a catalog. It applies
the Android-compatible schema, app-version, Valhalla engine/data-version,
artifact/HTTPS, PMTiles, search, and coverage gates instead of checking only
that a non-empty signature field exists. The CLI requires the public key, key
ID, and consuming app version; focused host tests cover a valid signature, a
wrong signature, a newer-app manifest, and a routing-version mismatch.

The Android package path now preserves failure categories: checksum corruption,
invalid/incomplete regional archives, and incompatible manifests are no longer
reported as network failures; unclassified I/O during installation is reported
as storage failure. If startup catalog discovery fails before any region is
published, Android retries it once connectivity returns with a 30-second
cooldown, so automatic regional download does not depend on opening Settings.
Host tool tests and Python compilation pass. No APK/Gradle build was run.

The Gradle script now has an explicit `voltyProductionRelease=true` gate. It
fails closed when a production invocation lacks a real release keystore, an
HTTPS catalog URL, a non-development manifest key ID, or a valid Base64 raw
Ed25519 public key. The default developer build behavior remains unchanged;
this gate was inspected but not executed because application builds are still
paused.

The catalog envelope is now signed with the same external Ed25519 key as its
release manifests. The publisher verifies that the signing and verification
keys match, emits schema version 2 with `catalogSignature`, and the Android
repository rejects a catalog whose signature does not verify before publishing
its regions. Existing release-manifest verification remains in place. Focused
catalog codec/policy coverage still needs to run in the app test suite when
Gradle builds are allowed again.

The updated publisher was also run against the real remote EKB `v0.1.3`
manifest with an ephemeral key: it produced a schema-2 one-region catalog and
the signature/key-ID checks passed. The temporary key and workspace were
removed after the smoke. The app test suite and Android runtime still need to
execute this path when the build pause is lifted.

### Execution checkpoint — process-scoped current-region download — 2026-09-03

Automatic map package discovery is now observed from the process application scope,
not only from `PlatformMapLayer`. An already-owned location fix is combined with
catalog state, so a region is reconsidered both when the rider moves and when the
verified catalog arrives after startup. The observer does not request location
permission or create a location demand; it preserves the existing map source's
deduplication, metered-data approval, retry cooldown, and online-map fallback.

No APK/Gradle build was run; the Kotlin wiring still needs the normal compile/test
gate when the explicit build pause is lifted.

### Execution checkpoint — enforce the regional routing buffer — 2026-09-03

The package builder now expands the logical region bbox by the requested
`--routing-buffer-km` for a routing-only extract. The map and search extracts
remain logical, while the Valhalla input is filtered to highway/ferry and
restriction data before `complete_ways`, preventing administrative multipolygons
from expanding the graph to the whole source. The previous script only recorded
the buffer in the manifest while extracting the unexpanded bbox, which could cut
routes at the published edge. Expansion is latitude-aware and clamps to world
bounds; host tests cover normal EKB geometry and world-edge clamping.

The remote EKB pilot package `v0.1.4` was rebuilt with a 20 km routing buffer.
The package is 154 MB and passed its verifier with 276,841 FTS rows and all
three component checks. A Valhalla 3.6.3 service smoke against the packaged
`tiles.tar` returned status `0` and two alternatives; the three route summaries
were 9.674, 9.483, and 7.694 km. This validates the artifact pipeline and
alternative generation, not the Android ABI/runtime gate.

The build log exposed that a clipped logical extract can leave administrative
boundary relations incomplete (`0` rows inserted). The builder is therefore
adjusted to derive `admins.sqlite` from the source PBF while keeping the
routing graph on the filtered, buffered extract; this keeps admin context out
of the graph-size problem and is validated by the next package rebuild.

The follow-up `v0.1.5` package passed the same verifier with one admin area,
276,841 FTS rows, 161,325,985 downloaded bytes, and 301,050,211 installed
bytes. Search prefix `плот*` still returns Russian place results, and the
packaged Valhalla service again returned three distinct route summaries
(9.674, 9.483, and 7.694 km) with status `0`. The package remains an unsigned
pilot artifact with a placeholder CDN URL; it is not a production catalog
release.

As a separate publisher smoke, that manifest was signed with a throwaway
Ed25519 key and accepted by `build-catalog.py` as a one-region schema-2 catalog;
both the manifest and catalog carried the same non-development key ID. The key
and temporary signed outputs were deleted on the remote host.

No APK/Gradle build was run.

### Execution checkpoint — offline Valhalla costing contract — 2026-09-03

The common Valhalla codec now emits the supported generic `auto` costing contract: top speed,
the existing highway-willingness style bias, up to three alternatives, safe engine defaults for
access/restrictions/oneways/closures, neutral toll/ferry midpoint values, and documented
`exclude_unpaved` avoidance. It does not emit the unsupported `use_unpaved` key or invent a road
curvature control; Valhalla 3.6.3 cannot guarantee scenic geometry from the four style names.

The change was compiled on the x86 host without Gradle: main compile `0`, test compile `0`, and
all five codec test methods passed. It is committed as `f2d6a88e` and pushed to
`codex/light-navigator`. No APK or production application build was run.

The same host also ran the exact request against the packaged Valhalla 3.6.3 service from
`v0.1.5`: the status endpoint became ready, `alternates` was present, and three returned trips
had distinct geometry hashes with lengths 1.834, 1.816, and 3.182 km. The one-shot container and
temporary extract were removed after the smoke.

### Execution checkpoint — geometry-only route deduplication — 2026-09-03

The shared alternative filter no longer treats distance or duration as route identity. A provider
can return the same road sequence with different metrics after costing/style changes; such a
candidate must not consume one of the three alternative slots. The filter now compares only the
bounded route geometry tolerance, while genuinely parallel corridors remain distinct. A fresh
direct x86 JVM compile and five-method route-policy run passed, including a regression with the
same geometry and deliberately different distance/time. No APK or Gradle build was run.

### Execution checkpoint — relevance-first offline autocomplete — 2026-09-03

The local FTS adapter now uses one platform-neutral ordering policy for both location-aware and
location-free searches. Exact names and title prefixes outrank address-only matches; when a GPS
fix is available, proximity breaks ties between equally relevant candidates instead of replacing
relevance entirely. This prevents a nearby weak hit from hiding the exact place the rider typed,
while preserving local ordering for equally good results. Two focused regressions cover both
rules; a fresh direct x86 JVM compile and four-method run passed. No APK or Gradle build was run.

### Execution checkpoint — Russian ё/е autocomplete folding — 2026-09-03

Offline search now folds Russian `ё` to `е` consistently in Kotlin query normalization and in the
host-side FTS index generator. The visible OSM display name remains unchanged, while the indexed
search text allows either spelling to match the same place. Focused Kotlin query/ranking checks
pass `8/8`; the host tool suite passes `17/17`, Python compilation passes, and a temporary SQLite
FTS smoke finds `Ёлка` through the `ел*` prefix. No APK or Gradle build was run.

### Execution checkpoint — rebuilt EKB pilot with folded search index — 2026-09-03

The remote EKB pilot was rebuilt as `v0.1.6` from the original full
`/home/sodovaya/nyxmap/sverdlovsk.osm.pbf`, so admin data and the existing 20 km routing buffer
remain intact. The package verifier accepted routing, search, and PMTiles; the search database
contains 276,841 rows and its compressed archive returns real `ел*` prefix results, including the
display name `Ёлочка` backed by normalized `search_text`. The manifest reports routing
102,584,133/198,139,367 bytes, search 14,592,861/58,368,000 bytes, and map 44,570,434/44,570,434
bytes; compatibility remains `valhalla-3.6.3`, minimum app version code `28`, and coverage
`59.10,56.00–61.90,57.55` with a 20 km routing buffer. An x86 Valhalla 3.6.3 service smoke on
the new archive returned status `0` for two EKB routes. This is an unsigned pilot artifact with
a placeholder CDN URL, not a production catalog release; no APK or Gradle build was run.

### Retraction — clipped PBF is not a package source — 2026-09-03

An intermediate attempt used an already clipped `test-region.osm.pbf` after the full source
reported an ordering warning. It produced incomplete admin data and Tilemaker later aborted, so
that input and the sorted temporary copy were removed. The successful `v0.1.6` build uses the
original full source and is the only current pilot candidate.

### Execution checkpoint — offline map glyphs and regional labels — 2026-09-03

The offline MapLibre style now renders labels from the PMTiles `place`, `transportation_name`,
and `poi` layers. Because MapLibre Native Android 13.0.2 does not provide usable local-font
fallback when the style omits glyphs, the app ships three generated Noto Sans Regular glyph
ranges (Latin/common punctuation, Cyrillic, and general punctuation) totaling 252,923 bytes.
The loopback PMTiles server serves only the fixed font stack and those fixed ranges from APK
assets; unsupported font/range/path requests return 404, and the glyph response is bounded.
The generator is checked in and reproduced the asset SHA-256 values on the remote Linux host.
JSON style validation, Node syntax validation, and a direct Android-server Kotlin compile passed.
The actual MapLibre visual smoke remains pending because it needs a device/emulator; it is not a
unit-test claim.

### Execution checkpoint — release APK with offline map labels — 2026-09-03

The release build includes the loopback glyph server and Noto glyph assets while continuing to use
the precompiled Maven `io.github.rallista:valhalla-mobile:0.6.3` dependency. The current release
is `0.7.6` / version code `28`; `:composeApp:testDebugUnitTest` passed and
`:composeApp:assembleRelease` completed successfully. `apksigner` verified the APK with APK
Signature Scheme v2. The single production APK is `79,604,111` bytes and contains only the
`arm64-v8a` and `armeabi-v7a` native ABIs; x86/x86_64 remain available only to debug/test
artifacts. The APK was not visually smoke-tested on a device in this checkpoint.
### Execution checkpoint — integrate offline navigation on the latest Terra visual base — 2026-09-04

The offline-navigation branch had diverged from the visual line at `dabe6b1b` and therefore did
not contain the later Terra/UI fixes `e5e1f77e`, `18c6e84a`, `9ac7ec33`, and `4c56ae91`. The branch
was merged with current `main`: the latest navigation glass, IME/map handling, overlay geometry,
and light ride dashboard are now present together with the regional offline runtime, local FTS,
Valhalla alternatives, package settings, and automatic downloads. Route profile controls were
reintroduced into the latest glass planner so the offline route-costing contract remains exposed.

The Android compile passed after resolving the integration seams. The first x86 runtime smoke
had already exposed that API 34 did not provide the JCA Ed25519 `KeyFactory`; the verifier now
uses the precompiled Bouncy Castle lightweight Ed25519 API while Valhalla remains the precompiled
Maven `io.github.rallista:valhalla-mobile:0.6.3` artifact. The signed EKB package survived app
startup, Settings showed `Готово · 0.1.6`, and local `ekb` autocomplete returned `Ekb-Cars`.
The integrated tree now also has fresh verification evidence: the full Android unit suite is
`2393` tests with zero failures, errors, or skips; the common database migration task and the
18-case offline-navigation host-tool suite pass; and the x86 emulator can use the installed EKB
package with Wi-Fi and mobile data disabled. In that offline smoke, `ekb` returned local FTS
results, Valhalla returned three route choices (`2.1`, `3.6`, and `4.2 km`), and starting the
selected route rendered a real localized maneuver (`Поверните налево на улица 8 Марта`). The
latest Terra planner surface, route chips, glass card, and Settings region lifecycle were
visually checked on the emulator.

The production packaging gate was run with a deliberately fake catalog URL/key only as a build
smoke: the signed `0.7.6` APK is `80,718,109` bytes, contains only `arm64-v8a` and
`armeabi-v7a`, and its dex files contain no `btools/`, `btools.`, or `RoutingEngine` markers.
The gate is configuration-cache clean. This artifact is not distributable until the real signed
HTTPS catalog/key is supplied. The full Valhalla Mobile Android ARM64 gate likewise remains open:
the existing ARM64 evidence is Linux service execution under QEMU, not execution of the Android
native library; BRouter therefore remains available only to debug builds until that external
device/host gate is passed.

The signed-catalog path then received a real cross-platform regression fix. The publisher's
catalog canonicalization now omits nullable-default fields inside nested `latestRelease` manifests
exactly as Android `kotlinx.serialization` does; a regression test reproduced the old invalid
signature and now passes. With an ephemeral E2E key and a short-lived HTTPS server on the remote
Docker host, the debug APK fetched the signed EKB `0.1.6` catalog, published `Екатеринбург и
окрестности` in Settings, automatically downloaded routing/search/map artifacts, and atomically
installed the complete seven-file package. The local CA trust was a temporary debug smoke aid and
is not part of the source or release configuration. Backend tests also passed freshly: 45 tests,
zero failures/errors/skips. A real deployment signing key/HTTPS object host and the Android ARM64
native Valhalla gate remain the only external release inputs.

### Execution checkpoint — production build 30 installed on Pixel 7 — 2026-09-04

The Android build number was raised from `28` to `30` while keeping the user-facing version name
`0.7.6`. A fresh production-gate build ran all 63 actionable Gradle tasks and passed
`verifyProductionReleaseOmitsBRouter`. The signed APK is 80,718,105 bytes, has only the release
ABIs `arm64-v8a` and `armeabi-v7a`, and contains no `btools/`, `btools.`, or `RoutingEngine` dex
markers. Its SHA-256 is
`75C25AC04593147515571C423198138AC42D66EA0A4B5E9463CFB75AC31051D5D`.

The APK was installed successfully on the connected Pixel 7 (`versionCode=30`, `versionName=0.7.6`)
and launched without a fresh `FATAL EXCEPTION` in logcat. This was a device launch smoke only:
the build used the previously documented smoke catalog URL/key because deployment credentials and
the real HTTPS object host are still not present, so this APK is not a distributable regional-data
release.

## Requirement amendment — experimental Valhalla costings and route diversity (2026-09-04)

The generic `auto` costing remains the only user-visible and default production policy. The
experimental Valhalla costings are now explicitly part of the plan as **candidate probes**, not as
transport selectors: `pedestrian` for low-speed experiments, `bicycle` for cycleway/road-access
comparison, and `motorcycle` for trail/track comparison including `use_trails`. They must not add
`vehicle`, `profile`, EUC, scooter, bicycle, or jurisdiction fields to the domain request or UI.

The probes are allowed to run only behind the routing experiment policy and must never silently
replace the generic route. Every probe result is tagged internally with its costing and compared
against the generic candidate before it can be promoted. Until the gate passes, the app keeps the
generic route as the safe answer and may show the probe only in a debug/diagnostic build or test
fixture.

### Experimental matrix

1. **Low-speed pedestrian probe.** Sweep `top_speed` values 20, 25, and 30 km/h. Use Valhalla
   `pedestrian` with the declared speed mapped to `walking_speed`, then inspect footway, path,
   steps, access, surface, distance, ETA, and route geometry. A result is not promotable if it
   introduces steps/access violations or if its ETA is presented as a vehicle ETA. The initial
   promotion candidate is a hybrid generic route only when it demonstrably improves the corridor
   for low-speed personal EVs without changing the route contract.
2. **Bicycle probe.** Sweep `use_roads` from trail-oriented through road-oriented values and
   compare cycleways, footways, access restrictions, surfaces, hills, distance, ETA, and geometry
   against `auto`. Keep it experimental because bicycle costing changes both access semantics and
   ETA; it is not a silent fallback for the generic profile.
3. **Motorcycle probe.** Sweep `use_trails` and `use_tracks` for `CURVY` and
   `MAX_CURVY_TOURING`, while keeping the generic route's access/safety contract visible. Record
   whether the regional graph actually contains usable trail branches. `use_trails` must not be
   treated as evidence that every trail is suitable for the rider.
4. **Generic curvy route.** Keep `auto` as the baseline and generate alternatives as separate
   requests with interior `avoid_locations`. Score returned geometry by heading change per
   kilometre after resampling, enforce a bounded detour budget, and reject candidates that are
   only small parallel-line noise. A named `CURVY` style is successful only when the selected
   geometry is measurably bendier than the fastest eligible candidate; it must not claim scenery
   from a highway-bias flag alone.

### Promotion and rollback gate

- [ ] Record each request's costing/options, wall time, distance, ETA, geometry hash, access/surface
  observations, steps, and route-diversity score in a redacted local experiment report.
- [ ] Require at least one repeatable beneficial low-speed corridor result and one repeatable
  genuinely different curvy/touring route in the EKB corpus; one corridor where a flag has no
  effect is useful negative evidence and is recorded rather than hidden.
- [ ] Verify that the generic route remains available when a probe fails, times out, returns no
  route, or produces a worse/unsafe candidate. Secondary probe failures must never fail the whole
  route request.
- [ ] Add common tests for costing JSON, low-speed thresholds, candidate tagging, detour limits,
  geometry scoring, and fallback ordering; add Android/debug smoke coverage against the packaged
  Valhalla extract before enabling any probe outside diagnostics.
- [ ] Promote only the smallest proven policy behind a feature flag with an immediate disable
  path. Do not remove the generic `auto` path or expose a transport/profile selector in settings.

### Current evidence and implementation order

The codec already has explicit encoders for `auto`, `pedestrian`, `bicycle`, and `motorcycle`, and
the offline runtime already uses iterative generic candidates plus geometry ordering. Initial
Valhalla 3.6.3 EKB probes showed that bicycle costing changed the corridor, pedestrian costing
produced a distinct path, and motorcycle `use_trails` did not change the tested urban corridor.
These are observations, not promotion decisions. The next implementation slice is the experiment
policy and result metadata, followed by the EKB matrix and a device smoke; only then may a proven
low-speed probe be considered for the generic route planner.

### Execution checkpoint — Valhalla experimental costing matrix — 2026-09-04

The first redacted EKB matrix was rerun against the pinned Valhalla 3.6.3 service. On the tested
long urban corridor, generic `auto` returned the same 24.213 km / 3090.460 s geometry for
`use_highways` values 0.0, 0.15, 0.4, and 1.0, and also for the tested `use_tracks` /
`use_living_streets` values. This is negative evidence for that corridor, not a reason to invent
curvature semantics. The motorcycle probe likewise returned the same 24.236 km / 3097.812 s
geometry for `use_trails` values 0.0, 0.35, 0.7, and 1.0; the graph exposed no useful trail branch
there.

Bicycle costing did change the route: `use_roads` values 0.0, 0.35, 0.7, and 1.0 returned
25.962, 24.948, 23.949, and 24.156 km respectively, with distinct geometry hashes. Pedestrian
costing also changed the route: walking speeds 20 and 25 returned 23.557 km / 4459.303 s and
23.572 km / 3611.406 s. These timings are costing-specific and must not be shown as generic
vehicle ETAs; access, steps, and surfaces still need explicit inspection before promotion.

The generic iterative alternative strategy returned three distinct candidates in about two
seconds: 24.213, 26.925, and 38.864 km, using 0, 8, and 8 interior avoid points. The common
`ValhallaExperimentPolicy` and `ValhallaRouteCandidate` metadata wrapper now encode the explicit
experiment boundary and promotion gate; its focused test class passes 5/5. The online OSRM fallback
now applies the same provider-independent geometric ordering for `CURVY` candidates after diversity
filtering. The complete debug unit-test suite passes 2407/2407 with zero failures, errors, or skips.
No experimental costing is wired into the default runtime yet, and no promotion decision has been made.

### Execution checkpoint — multi-corridor experiment repeatability — 2026-09-04

The matrix was repeated on two additional redacted EKB corridors. Bicycle costing changed the
geometry on all three corridors (`use_roads` 0.0/0.7/1.0): the returned distances were
25.962/23.949/24.156 km, 27.657/26.094/26.876 km, and 27.950/26.866/26.809 km. Pedestrian
costing also returned a distinct route on each corridor; at walking speeds 20/25 its distances
were 23.557/23.572 km, 22.249/22.249 km, and 25.837/25.826 km. The equal-distance pair on the
second corridor is useful evidence that changing the costing does not guarantee a different
geometry.

Motorcycle `use_trails` changed the geometry on the two additional corridors (including a
27.861 km / 5625.511 s result on the east-northwest corridor), while the original urban corridor
remained unchanged. This supports keeping motorcycle probes diagnostic-only: trail preference
can affect the graph, but it does not establish that the resulting trail is suitable or faster.
The repeated data is recorded as aggregate corridor labels and geometry hashes only; no precise
origin/destination coordinates are added to the plan.

### Execution checkpoint — experimental costing wire compatibility and route-corpus smoke — 2026-09-04

The encoder compatibility pass found and fixed a real Valhalla option-name bug before any
experimental profile could be enabled. An earlier revision of this checkpoint incorrectly
claimed that pinned `motorcycle` used singular `use_highway`, based on the current API-reference
page. The actual pinned Valhalla 3.6.3 parser reads plural `use_highways` (see the
[pinned motorcycle costing source](https://github.com/valhalla/valhalla/blob/3.6.3/src/sif/motorcyclecost.cc));
the encoder and regression now use that spelling. The earlier singular-key route smoke therefore
did not prove the option was honored: the unknown key was ignored and the default remained in
effect.

A short EKB route-corpus smoke against the pinned Valhalla 3.6.3 service returned the expected
mode/type pairs: generic `auto` → `drive/car`, `motorcycle` → `drive/motorcycle`, `bicycle` →
`bicycle/hybrid`, and `pedestrian` → `pedestrian/foot`. Bicycle and pedestrian produced distinct
geometry from generic on this corridor; motorcycle matched generic there. `rough`, `toll`, and
`ferry` were all zero in this smoke, so this is protocol/metadata evidence only, not proof that
the candidate is safe for every road surface or access rule. The temporary service and config
were removed after the smoke; package/image sources were untouched.

The promotion gate remains open: no experimental costing is wired into the default runtime,
and no candidate is promoted until the access/surface/steps/ETA checks and Android/debug smoke
criteria below are complete.

### Execution checkpoint — route evidence boundary — 2026-09-04

Added an internal `ValhallaRouteEvidence` decoder for the maneuver-level facts that the route
response actually carries: normalized `travel_mode`/`travel_type`, rough, toll, ferry, and
unknown-mode detection. An earlier synthetic `gate` field was removed: the pinned route serializer
does not emit it, so ordinary route responses cannot be used to claim gate evidence. The decoder
deliberately remains outside `RouteAlternative` and the UI. Missing or unknown travel modes are
marked unknown instead of being treated as safe. The decoder does not invent surface, access, or
steps verdicts: those remain explicit corpus/trace inputs to the promotion assessment, because the
ordinary route response does not expose a reliable combined verdict for them.

The promotion assessment now requires the unknown-mode bit to be false in addition to the existing
distinctness, steps, access, ETA, and detour checks. Thus evidence can be carried forward for
diagnostics without making an experimental route eligible through an omitted provider field.

### Retraction — pinned Valhalla maneuver enum and evidence boundary — 2026-09-04

The first codec fixture used maneuver type `2` as arrival, which masked an incorrect mapping. The
pinned [Valhalla 3.6.3 directions enum](https://github.com/valhalla/valhalla/blob/3.6.3/proto/directions.proto)
defines `1..3` as start variants and `4..6` as destination variants. The decoder now follows that
enum, with regression coverage for the turn, U-turn, roundabout, merge, ramp, ferry, and transit
ranges that are represented by the app's smaller maneuver vocabulary. This is a decoder
compatibility correction only; it does not promote an experimental costing or change the default
generic runtime.

### Requirement amendment — production adaptive routing profiles — 2026-09-04

The earlier diagnostic-only boundary is superseded. `pedestrian`, `bicycle`, and `motorcycle`
are now production Valhalla engine profiles selected automatically from route style and the
rider's declared top speed. They remain internal routing semantics, not a vehicle-type selector:
the request and UI still do not gain EUC, scooter, bicycle, jurisdiction, or transport fields.

The production matrix is deliberately speed-aware:

| Request | Primary profile | Profile fallback | Highway rule |
|---|---|---|---|
| Any style, 20–30 km/h | `bicycle` | `pedestrian`, then generic | highway bias is hard-zeroed for every costing, including generic fallback |
| `CURVY`/touring, 31–60 km/h | `motorcycle` | `bicycle`, then generic | motorcycle highway preference is 0.15/0.0; trails/tracks are stronger in the 31–60 band |
| Fast styles, 31–130 km/h | `motorcycle` | generic | `FAST` may use highways; `FAST_WITHOUT_HIGHWAYS` excludes them |
| `CURVY`/touring, 61–130 km/h | `motorcycle` | generic | highways stay strongly disfavored; adventure bias is reduced for high-speed safety |

The offline Valhalla runtime now routes with the primary profile and generates its bounded,
geometry-diverse alternatives using that same costing. It tries the profile fallback only when
the selected profile returns no route/provider failure; this keeps the normal path fast while
making pedestrian/bicycle/motorcycle real production behavior rather than background probes.
The OSRM online fallback hard-excludes `motorway,trunk` for low-speed and highway-avoiding
requests. The HTTP/backend contract carries `routingProfile`; GraphHopper has one explicit
mapping per profile and refuses a missing mapping instead of silently substituting generic.

Valhalla option tuning is also speed-aware: low-speed `motorcycle` fallback has zero highway,
trail, and track preference; bicycle uses zero road preference up to 30 km/h; curvy motorcycle
uses `use_trails/use_tracks` 0.55/0.40 in the 31–60 band and 0.35/0.25 above it, while maximum
touring uses 0.8/0.75 and 0.6/0.5 respectively. These are routing-cost inputs, not claims that
every trail is suitable; route access/surface/steps still need field validation.

Common tests cover the profile matrix, speed boundary at 30/60, low-speed highway hardening,
curvy adventure scaling, OSRM exclusion, HTTP wire profile, and GraphHopper mappings. No APK
build is part of this amendment.

### Execution checkpoint — production profile route-corpus smoke — 2026-09-04

The current EKB Valhalla 3.6.3 extract was queried with the exact production costing options on
one redacted long urban corridor. The low-speed primary `bicycle` profile returned
`has_highway=false` at both 20 and 30 km/h, with `travel_mode=bicycle` and
`travel_type=hybrid`. The `pedestrian` fallback at 25 km/h returned `has_highway=false` with
`travel_mode=pedestrian` and `travel_type=foot`. Generic `auto` with the low-speed hardening
also returned `has_highway=false` at 20 and 30 km/h.

The 50 and 90 km/h `motorcycle` curvy profiles returned `drive/motorcycle` and
`has_highway=false`. Iterative avoidance produced three distinct geometry hashes for both
curvy speed bands; the route lengths were 24.050/28.161/25.982 km at 50 km/h and
27.862/32.381/24.518 km at 90 km/h. The latter is provider evidence that alternatives are
actually being generated, while the style-specific motorcycle options still do not guarantee
a different primary shape on every corridor. The route-level `has_highway` gate is now enforced
in the decoder: a true or missing highway verdict is rejected for low-speed, curvy/touring, and
no-highway requests, allowing the profile fallback to run instead of exposing an unsafe route.

The smoke ran through the remote Docker Valhalla service only; no APK was built or installed.

### Execution checkpoint — vehicle-scoped navigation preferences — 2026-09-04

The planner's route style and declared top speed are now persisted in DataStore under the active
vehicle id. The retained navigation component observes the active vehicle and swaps to that
vehicle's saved values immediately; changing a profile or speed writes it back to the same key.
Destination text, search results, route alternatives, and an active route remain transient. A
legacy global preference key is still read as a migration fallback for vehicles with no dedicated
value, while new edits always create a vehicle-scoped value. No UI transport-type selector was
introduced. Common tests cover restoring a vehicle's values, isolating two vehicles, switching
vehicles while the planner is retained, and persistence across `AppPrefs` instances. The full
debug unit-test suite passes 2,416/2,416 with zero failures, errors, or skips; no APK was built.

### Execution checkpoint — profile-aware online fallback — 2026-09-04

The direct OSRM fallback now follows the same internal profile order as the offline planner while
keeping alternatives within one costing. At 20–30 km/h it tries the public FOSSGIS bicycle graph,
then foot, then the car graph only after a provider/no-route failure. Above 30 km/h the public car
graph is used as the closest available road-access equivalent for the internal motorcycle/generic
profiles; the public service has no motorcycle endpoint. Two car providers can still be queried in
parallel for alternatives, while bike/foot attempts are not mixed with car routes in one result.
The existing motorway/trunk exclusion remains active for low-speed, curvy, and no-highway requests.
Focused OSRM tests pass, including bicycle selection and bike-to-foot fallback. No APK was built.

### Execution checkpoint — live online profile endpoint smoke — 2026-09-04

The public FOSSGIS endpoints were checked on a redacted EKB test corridor with the same route
shape used by the adapter. `routed-bike`, `routed-foot`, and `routed-car` each returned `Ok` and
one route; the first maneuver modes were respectively `cycling`, `walking`, and `driving`. The
returned distances/durations were distinct, confirming that the low-speed fallback is backed by
different routing graphs rather than a URL-only label. No query coordinates were recorded and no
APK was built.

The same live request with `alternatives=true` returned two routes from each of the bike, foot,
and car graphs on that corridor. The adapter therefore keeps the provider's native alternatives
for the selected costing; it does not need to mix profile types merely to inflate the count.

### Execution checkpoint — release runtime variant wiring — 2026-09-04

The release BuildConfig had a subtle configuration leak: the offline-runtime flag was previously
declared in `defaultConfig`, so a plain release invocation without the production catalog gate
could select the debug-era BRouter repository. The flag is now declared per build type: every
release variant selects Valhalla/OfflineFirst, while debug keeps BRouter compatibility unless the
offline runtime is explicitly enabled for a local smoke. The generated release config was checked
without assembling an APK (`VOLTY_OFFLINE_RUNTIME_ENABLED=true`); debug remains `false` by default.
The full debug unit suite then passed with exactly 2,417 tests and zero failures, errors, or skips.

### Execution checkpoint — repeatable iterative production alternatives — 2026-09-04

The exact production costing options were rerun against the pinned Valhalla 3.6.3 EKB extract
through the same sequential `avoid_locations` strategy used by the offline runtime. On the
redacted long corridor, `CURVY` at 50 km/h returned three unique geometry hashes in 557/661/643 ms
with lengths 24.050/28.161/25.982 km; at 90 km/h it returned three unique hashes in 531/500/593 ms
with lengths 27.862/32.381/24.518 km. The 20 km/h bicycle primary also produced three unique
iterative candidates in 602/708/752 ms (25.962/29.132/33.126 km), all with `has_highway=false`.

This is evidence that the production alternative path is fast enough on the host and does not
collapse to one route on this corridor. It does not promote trails or pedestrian routing by
itself: the route responses still require field/corpus checks for access, surface, steps, and
vehicle-ETA compatibility. The temporary service used a copied config with the missing
`auto_pedestrian` service limit restored; the checked-in config, package, and old containers were
not modified. No APK was built or installed.

### Execution checkpoint — regional service config normalization — 2026-09-04

The pinned Valhalla 3.6.3 image's `valhalla_build_config` output was compared
with its service startup behavior. The generated config omitted
`service_limits.auto_pedestrian`, while the service failed before `/status` with
`No such node (service_limits.auto_pedestrian.max_locations)`. The regional
toolchain now runs an idempotent normalizer after config generation, adding the
required distance/location/matrix limits and preserving any existing block. Focused
toolchain coverage passes 21/21, Python compilation and shell syntax checks pass,
and a temporary service with the normalized config reaches `/status` and returns a
three-trip route response; no package
or checked-in runtime data was changed and no APK was built.

### Execution checkpoint — rebuilt EKB package with normalized config — 2026-09-04

The remote Docker toolchain was synchronized with the checked-in normalizer and rebuilt as a new
`ekb-package-v0.1.7`; the existing `v0.1.6` package and source PBF were not overwritten. The new
unsigned manifest requires app version code 30 and reports 102,586,976 bytes routing /
198,139,538 bytes installed, 14,592,861 / 58,368,000 bytes search, and 44,573,148 bytes PMTiles.
The package verifier accepted all three components and 276,841 FTS rows.

The packaged `valhalla.json` contains the normalized `auto_pedestrian` block. After extracting
the routing archive, a temporary pinned 3.6.3 service reached `/status` and returned three route
trips (`24.321`, `26.420`, and `28.261` km) from the route corpus. This closes the regional
artifact/config regression on the host; the artifact remains an unsigned pilot with a placeholder
CDN URL and is not a production catalog release. No APK was built or installed.

### Execution checkpoint — signed catalog publisher on EKB `v0.1.7` — 2026-09-04

The new package was passed through the publisher with an ephemeral Ed25519 key and the real
version-code/data-version gates: the manifest was signed, a schema-2 catalog with one region and
release `0.1.7` was emitted, and its catalog key ID matched the manifest key ID. A separate run
with the original unsigned manifest was rejected with the expected non-zero result and the
`unsigned manifest cannot enter catalog` error. Keys, signed outputs, and specs were removed after
the smoke; this is publisher evidence only, not a distributable release. No APK was built.

The same signed package was also rejected when the consuming app version was set to `29` and when
the expected routing data version was changed to `valhalla-3.8.3`; both compatibility checks
returned non-zero with the specific newer-app/mismatched-engine errors. The publisher therefore
fails closed on both catalog compatibility dimensions before publication.

### Execution checkpoint — migration and backend verification — 2026-09-04

The migration verifier initially hit a Windows-only SQLiteJDBC extraction failure because the
SQLDelight worker inherited `C:\WINDOWS` as its native-library temporary directory. Inspection of
`sqlite-jdbc 3.51.0.0` confirmed that it uses the dedicated `org.sqlite.tmpdir` property. Rerunning
with an isolated workspace temp directory and the existing pre-extracted native library made
`:composeApp:verifyCommonMainVoltyDatabaseMigration` pass (`BUILD SUCCESSFUL`, one task executed)
without touching system files. A fresh `backend test` rerun also passed (`BUILD SUCCESSFUL`, five
tasks executed). No APK was built or installed.
