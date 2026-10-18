# Bounded visible route previews

`HistoryPreviewComponent` reads only the foreground catalog viewport supplied by
the UI. `DefaultHistoryComponent` resolves those IDs against its current catalog,
filters duplicates/unknown IDs, invalidates previews on refresh and retires removed
rows. Leaving the list clears visibility; returning can reuse reduced geometry.

The first implementation reuses the existing atomic `route()` page, rather than
adding a second SQL summary facade. Each page contains at most 20,000 GPS fixes
and 20,000 markers. At most two requests, including validation/reduction, hold
permits concurrently. Validation and shape reduction use a background dispatcher;
commands and publication use the parent's UI dispatcher.

The owner retains at most 16 LRU cache entries plus at most 20 visible states,
with at most 128 actual fixes per preview. Two transient read pages and projection
working arrays are additional memory; this is a structural bound, not a measured
heap-size or phone performance claim. No numeric series/source metadata is loaded
for thumbnails, and legacy rides never request GPS.

Shape reduction preserves retained segment endpoints and real corners. It never
joins GPS segments or rewrites coordinates/timestamps. A dropped page, an open
recording or excess segments produces an explicit incomplete flag. Malformed
origin, clock, order, range, cursor or oversized payload produces an error, rather
than a successful empty preview. Errors do not retry under an unchanged viewport;
the row exposes an explicit retry action.

The cache key includes immutable ride metadata and the explicit refresh revision.
It stores geometry independent of style/dimensions; raster/tile caching is still
open. A late retired request cannot populate state or warm the current cache.

## Verification

- Reduction: five causal RED tests, then GREEN with projection/scene policies.
- Owner: seven causal RED tests, then GREEN with actual issued-read counters,
  held NonCancellable responses, semaphore bounds, LRU/revision, retry, legacy/
  open/empty-truncated quality, malformed payloads and lifecycle destruction.
- Parent binding: a new test first failed before implementation, then proved
  current-catalog ID filtering, pruning on refresh, no legacy preloads and close
  fencing. Re-showing a retired ride explicitly issues a new read, exercising
  the cache-poisoning guard rather than only checking another ride's state.
- Final h431: 4,246 tests / 437 fresh XML, zero failures/errors/skips. All 55
  tasks executed, including actual migration verification and debug packaging.
  No source edits during the run. Both locales contain 62 unique matching
  history keys.
- Final debug APK SHA-256:
  `F36F5FD6F3D6CF7606FDDA2F7BAD52D830B88A947146B6B16786DAE647E3DF72`.

## Native acceptance on API 34

The catalog probe now uses the actual preview owner and atomic fixture reader;
the earlier fixed preview geometry supplied directly to rows was removed.
Six recorded fixture fixes render two L-shaped segments. A deliberately incomplete
fixture page displays the incomplete-preview label; legacy rows keep the neutral
fallback.

A one-shot fixture failure displayed the unavailable label and retry button.
Tapping retry restored the route while remaining in the catalog, verifying that
the nested button does not open the containing card. A later card click opened
the recorded map, and system Back returned to the catalog.

That check originally exposed a scroll reset. The list now accepts a hoisted
LazyListState retained by the screen host. On the final binary, the scrolled
viewport before opening a ride and after Back had identical UI positions and
byte-identical screenshots. Fully visible incomplete/error cards remain readable
at font scale 200%, with labels wrapping and row height growing. Font scale 1.0
and rotation accelerometer=1/user=0 were restored afterward.

Fresh `stored=true` mode still lists actual legacy rows through the ordinary app
Koin reader. It does not insert recordings or start BLE/location. Its closed-only
detail limitation is unchanged; old records without a usable closed duration
need a recorded-availability window before production root activation, rather
than an unqualified current-clock end.

Ignored evidence: h430 visible/error/retry screenshots; h431 catalog-before-detail,
catalog-after-detail, preview-partial-font2 and stored-previews screenshots in the
execution directory. h430 demonstrates the initial retry path; h431 is the final
accepted binary and repeats retry/open/back with preserved scroll.

## Still open

Tile/image cache and provider acceptance, name/distance/date/favorite bindings,
automatic older-page triggering, persistent metric presets, full home/charts/root
composition, actual recorded windows for unclosed legacy data, recorder activation,
phone/performance acceptance and final distribution audit remain open. This
checkpoint completes visible geometry ownership, not the complete screen rewrite.
