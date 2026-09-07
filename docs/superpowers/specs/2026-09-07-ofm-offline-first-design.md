# OFM offline-first migration design

## Decision

Volty will use one OpenFreeMap style for both connected and disconnected map
rendering. Android will register regional packs with MapLibre's native
`OfflineManager` using the same OFM style URL, a region envelope, and an
explicit zoom range. MapLibre will own the local tile/style/sprite/glyph cache;
Volty will not rewrite OFM style JSON, generate a second map schema, or serve
PMTiles through a loopback HTTP server.

The existing offline region package remains responsible for navigation data
only: the FTS search index and the Valhalla routing extract. The backend will
continue to build those two components from a pinned OSM snapshot, but it will
stop producing or publishing a map PMTiles artifact.

## Goals

- Make the online and offline basemap the same OFM visual product, including
  roads, bridges, buildings, 3D extrusion, labels, sprites, and glyphs.
- Download the rider's current region on the first map opening, including its
  map, search, and routing data when a validated network is available.
- Prepare any other region only after an explicit user action in Settings.
- Use installed local data whenever it covers the requested map/search/route;
  use the online provider for a missing component only with validated network;
  show an honest unavailable/no-data state when neither source exists.
- Keep search and routing independently versioned and testable because OFM is a
  basemap provider, not a geocoder or routing service.
- Remove the custom map pipeline and the source-switching lifecycle that made
  the map change to online when the navigator was opened or closed.

## Non-goals

- OpenFreeMap will not be made responsible for geocoding, route calculation,
  navigation directions, or elevation. Its documented scope excludes those
  services.
- We will not self-host the OpenFreeMap planet or mirror its 90+ GB snapshot as
  the default implementation. A regional PMTiles extract from a pinned OFM
  snapshot is a fallback only if the native pack spike fails.
- We will not add a synthetic world overview, a second low-detail style, or
  labels that are absent from the OFM source.
- We will not silently erase existing routing/search data during migration.

## User-visible behavior

### Map

The renderer always receives the normal OFM Bright or Dark style URL. A small
Volty-owned 3D building extrusion layer may be appended after the OFM style
layers, using the standard OpenMapTiles building attributes. It must remain
above road layers, matching the pre-offline visual baseline; it must not change
the source tile schema or remove OFM layers.

On the first map opening with a current fix, the coordinator identifies the
catalog region containing that fix. It starts an idempotent preparation for the
current map style and the navigation components. The operation is not repeated
on every location update or every Compose recomposition. A metered-network
preference may defer the operation, but the map must remain online while it is
deferred.

When a map viewport leaves an installed pack, MapLibre continues using online
OFM resources if the network is validated. With no validated network, missing
resources remain missing; the app must not substitute a visually different
custom map. Returning to an installed region must not require tapping the
current-location control.

Bright and Dark are separate style definitions because an OfflineManager pack
is tied to a style URL. The current theme is prepared first. The other theme is
prepared when explicitly selected or as a follow-up operation when network and
storage policy permit it. The map state must show which theme pack is present.

### Search

The local FTS index is attempted first when a query is covered by an installed
region. If it has no useful result or the region is not installed, Photon is
used when the network is validated. If the network is unavailable, only local
results are returned. Empty local results and provider failures must not be
reported as successful empty data when an online retry is possible.

Local and online candidates are deduplicated by stable OSM identity first and
by normalized title plus a small coordinate radius only when identity is
missing. Technical categories are not displayed as place names. A valid empty
result remains empty; it is not converted into an automatic download request.

### Routing

The local Valhalla extract is attempted first only when the complete origin to
destination corridor is covered by one installed routing package. A coverage
miss or local runtime failure may fall back to OSRM when the network is
validated. A valid `NoRoute` result is not hidden by an unbounded fallback.
Without network and without complete local coverage, the UI reports that a
route is unavailable.

### Region preparation

The current region's map pack and navigation package are independent resources
under one user-visible preparation state:

```text
current region
    ├─ OFM MapLibre pack (style + tiles + sprites + glyphs)
    ├─ signed FTS search artifact
    └─ signed Valhalla routing artifact
```

Each child has its own progress and error. A successful map pack must not claim
that search or routing is ready, and a failed navigation artifact must not
delete a valid MapLibre pack. Settings shows partial preparation and offers a
retry for the failed component. Explicit preparation of another region uses the
same coordinator.

## Architecture

### Android map source

Add a focused Android bridge around the existing MapLibre SDK 13.0.2
`OfflineManager`:

- `create` a `TilePyramid` definition with style URL, validated bounds, and
  measured min/max zoom;
- attach stable metadata containing region ID, style variant, OFM style URL,
  source revision (when known), and schema version;
- `resume`, `pause`, `invalidate`, and `delete` through one lifecycle owner;
- expose pack state and `DownloadProgress` to the region coordinator;
- use the ordinary style URI in `MapView`; never swap to a local source URL
  based on the current scene or a transient location fix.

The first implementation must run a device spike before deleting the old map
path. The spike verifies that a downloaded pack is used for an already cached
tile with network enabled, remains usable in airplane mode, and does not lose
state across process restart or navigator open/close.

The current region envelope is deliberately measured before production rollout.
The current EKB bounds contain roughly 22,000 XYZ tiles through z14, while
MapLibre's default offline tile limit is 6,000. The bridge must set and test an
explicit limit or choose a product-appropriate zoom ceiling; it must not let a
default limit produce a partial pack presented as complete.

### Navigation package

Keep the signed catalog, Ed25519 verification, resumable download, checksum
validation, atomic installation, FTS builder, and Valhalla builder. Change the
manifest contract so `routing` and `search` are navigation artifacts and `map`
is MapLibre pack metadata rather than a downloadable PMTiles file. Existing
legacy map artifacts may remain on disk for safe migration but are never chosen
by the new renderer. Existing valid routing/search artifacts remain usable.

The preparation coordinator is the only component allowed to combine map-pack
state with navigation-package state. Search and route requests do not enqueue a
new region build; they either use installed data, use the online adapter, or
return an explicit unavailable result according to network and coverage.

### Backend build and source policy

The current backend flow is:

```text
Geofabrik index → regional .osm.pbf → osmium extracts
    ├─ tilemaker/process.lua → custom map.pmtiles   (remove)
    ├─ build-search.py → places.sqlite             (keep)
    └─ Valhalla → routing archive                  (keep)
```

The target flow is:

```text
pinned OSM source snapshot → osmium extracts
    ├─ build-search.py → signed places.sqlite
    └─ Valhalla → signed routing archive
```

Geofabrik may remain the transport for the pinned OSM input because OpenFreeMap
does not provide search or routing. The production manifest records source ID,
source URL, SHA-256, OSM timestamp, and replication sequence so the input is
auditable and reproducible. `latest` URLs are not accepted as the only source
identity for a release.

Remove tilemaker, `process.lua`, map MBTiles conversion, map PMTiles validation,
map artifact publication, and map-specific cache limits from the production
pipeline after the native pack acceptance gate. The backend does not proxy or
rebuild OFM map tiles; the Android client downloads regional OFM resources from
the public OFM endpoint through MapLibre.

If the native pack spike fails, the only approved map fallback is an extract of
the exact version-pinned OFM MBTiles/PMTiles snapshot, preserving its vector
bytes and style schema. It is not permissible to return to the current
tilemaker schema.

### Network policy

Use Android's validated-network signal (`NET_CAPABILITY_VALIDATED`) for all
online fallbacks and preparation decisions. A mere `INTERNET` capability or a
stale cached status must not cause an online source to be selected while the
device is in airplane mode.

There are exactly three outcomes for each resource request:

1. local resource covers it — use local;
2. local resource does not cover it and validated network exists — use online;
3. neither is available — return missing/unavailable without fabricated map
   content or an invisible download.

The only automatic download is the idempotent current-region preparation on
the first map opening. GPS updates, map pans, search calls, and route calls do
not initiate downloads for other regions.

## Migration and compatibility

1. Ship the native-pack bridge behind a diagnostic flag and run the device
   spike against the current EKB region for Bright and Dark.
2. Keep existing PMTiles map files readable by the old store while the new app
   migrates; do not silently delete user data.
3. Once native pack acceptance passes, stop selecting legacy map files and
   remove the map component from newly published manifests.
4. Preserve valid installed FTS and Valhalla data. Mark a legacy package
   partial if its map artifact is the only invalid component.
5. After one release has exercised migration and rollback, remove the loopback
   server, cloned style assets, glyph subset, and tilemaker map pipeline.

## Acceptance tests

### Pure/unit tests

- Map-pack definition uses the exact OFM style URL, bounds, zoom range, and
  stable metadata for Bright and Dark.
- Tile-limit policy rejects an envelope that would be presented as complete
  above the configured resource budget.
- Coordinator is idempotent for repeated first-map events and does not start
  preparation from GPS updates, search, or route calls.
- A partially ready region reports map/search/routing independently.
- Local-first search falls back to Photon only for no coverage/no useful local
  result and validated network; offline search never calls Photon.
- Local-first routing falls back only for coverage/runtime failure and keeps a
  valid NoRoute final.
- Legacy map artifacts are ignored by the new map source while valid routing
  and search artifacts remain available.
- Backend manifest contains source provenance and no map component after the
  migration.

### Python/backend tests

- The builder no longer invokes tilemaker or emits `map/*.pmtiles`.
- The package verifier accepts the routing and search components and rejects an
  unexpected map artifact in a new manifest.
- Source metadata requires URL, digest, OSM timestamp, and sequence.
- Rebuilding the same source snapshot and region produces deterministic search
  and routing package metadata.
- On-demand requests remain idempotent and never trigger a map build.

### Device acceptance

- Online Bright and Dark match the pre-offline OFM baseline, including 3D
  buildings overlaying roads.
- Downloaded current-region pack renders the same frames with network enabled
  and in airplane mode.
- Outside the pack, online tiles render with network; without network the map
  shows no data instead of a different synthetic style.
- First map opening prepares current-region map/search/routing once; a second
  opening and location updates do not redownload.
- Opening and closing the navigator preserves the selected map resources.
- Settings explicitly prepares a second region and exposes partial/error state.
- Local search and Valhalla route are exercised in airplane mode using real EKB
  queries. Online Photon/OSRM are smoke-tested separately because they are
  public services without an application SLA.
- Logcat contains no fatal exception during tile cancellation, style reload,
  pack deletion, process restart, or navigator transitions.

## Rollback

The release flag can disable native map packs while retaining the old renderer
for one diagnostic build only. Production rollback must preserve the user's
navigation artifacts and MapLibre database. No rollback path may re-enable the
incompatible tilemaker output as if it were OFM data.

