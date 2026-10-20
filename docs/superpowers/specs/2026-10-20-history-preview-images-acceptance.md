# Recorded-history preview images: checkpoint acceptance

This completes the image-background part of screens Task4, not the whole catalog,
history rewrite or production cutover. Product history routing and the new recorder
remain inactive. Favorites/media/export and the full home/chart composition remain
required. No physical-device or steady-state frame-rate acceptance is claimed.

## Ownership and limits

Only visible catalog rows request images. The existing segmented Canvas route stays
visible while a background loads or fails; legacy/noGPS rows allocate no native map.
The image owner admits at most20 distinct visible rides, fences obsolete responses,
and copies geometry/encoded bytes. Keys include ride ID, revision, dimensions,
theme, route color, segmented geometry and style revision. The Android disk digest
also includes the actual derived style hash. Parent admission checks the current
catalog/revision/geometry; rendered images must match the exact current key.

Encoded LRU limits are16 entries/8MiB, separate from visible state and native/decoded
image memory. Disk PNGs are limited to64 entries/32MiB/1MiB per item, with a24h
last-access lifetime, atomic publication and namespaced orphan-temp cleanup. Cache
operations avoid following links or claiming differently cased foreign filenames.
Construction performs no disk/network/native rendering work.

The owner queues at most two loads; Android serializes one snapshotter. Each native
attempt has an8s deadline and bounded cleanup. A fresh temporary offline region
requires a positive precise complete resource count with no download error; it
aborts above256 resources or4MiB reported download bytes. These thresholds do not
bound all in-flight/native ambient-cache overhead. Temporary owned regions are
deleted after completion/cancellation, and orphan cleanup retries on later work.
The shared map provider/cache/connectivity policy is unchanged.

Styles derive from the already bundled approved OpenFreeMap styles, removing labels
and unused glyph/sprite requests. Textured fill/line/background layers retain an
explicit base color or fail closed. Two immutable bundled style bodies are served
over loopback only; no filesystem paths or arbitrary payloads are exposed. Default
SDK attribution remains, with readable provider links outside the tiny images.

Retraction: canonical local-file style URIs alone did not make native offline
preflight work on the tested API34 runtime. The unused URI helper was removed.
Snapshot-ready alone also does not prove successful tile downloads. Resource
preflight rejects observed download failures; this is not proof against every
possible malformed HTTP200 vector tile or renderer parsing defect.

## Renderer backend

The default MapLibre13.0.2 Vulkan artifact aborted in native Snapshotter code on the
test AVD without a usable Vulkan GPU. The AVD still advertised a Vulkan feature,
so a PackageManager feature check would not have prevented that abort. Use the
same-version official OpenGL artifact, `android-sdk-opengl:13.0.2`, instead. See
[MapLibre rendering backends](https://maplibre.org/maplibre-native/docs/book/platforms/android/android-rendering-backends.html).
The ordinary recorded map was rechecked after this application-wide artifact change.

## Executed verification

RED/GREEN evidence in the ignored rebuild ledger covers image-owner retirement,
reentry, key identity/cache bounds, camera coverage, style derivation, actual
loopback HTTP behavior, file eviction/expiry/atomic cleanup and Windows case aliases.
Parent tests assert which image keys were actually issued and reject unknown IDs,
wrong revision or geometry. These are logic/storage tests, not Compose tests.

Final run h463 used clean unit-test results, `--rerun-tasks`, no build/configuration
cache, four workers, actual database migration verification and debug assembly:
**4317 tests in447 fresh XML files, zero failures/errors/skips; all55 tasks executed**.
Debug APK SHA256:
`B5774AA3BC48B2D716AA558E7A6C126617218957CBBE352AA69DC9DA74162C5C`.
No source edits followed that build before this checkpoint's native checks.

Native API34 checks used the installed final binary on the existing test AVD,
host OpenGL with Vulkan disabled:

- Bright and dark basemaps render behind split recorded routes, with no per-row
  MapView; partial-geometry labels and route-less legacy rows remain honest.
- Process restart without networking restores cached PNGs. An uncached area
  without networking resolves to route-only Canvas previews after bounded timeouts,
  rather than publishing a partial tile mosaic.
- Read-only native SQLite inspection finds zero temporary regions after timeouts.
  Cancellation was exercised after asserting one real native region existed;
  leaving the Activity subsequently returned its count to zero.
- Ordinary retained map still renders two route segments and the shared cursor's
  recorded cyan point after its render frame settles.
- Dark portrait/landscape with font scale200% keeps cards and provider links readable.
  Font, rotation, theme and networking settings were restored afterward.
- A visible failed-geometry row's retry loads its preview while staying in the
  catalog. After scrolling, opening a ride and returning preserves the exact
  visible row positions (including the partial-preview row).

Screenshots/logs are archived under the ignored rebuild workspace (h462/h463).
No phone compatibility, smooth60fps, signed release, complete catalog metadata,
trim/delete image invalidation integration or whole-history completion is inferred.
