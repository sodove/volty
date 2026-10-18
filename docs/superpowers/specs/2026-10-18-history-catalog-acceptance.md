# History catalog and first Vescape UI adaptations

The catalog renders the recorded catalog owner's state with stable ride IDs,
refresh/older-page callbacks, errors, incomplete-list warning and legacy status.
It shows a duration only from a recorded closed interval. Missing names use a
localized generic title, not an opaque vehicle ID. This checkpoint does not
activate the destination in the production root or enable the new recorder.

`history/ported/HistoryRideCard.kt` and `HistoryRoutePreview.kt` adapt the pinned
Vescape row/Canvas hierarchy to Compose and Volty's theme. Their README records
source paths, revision, author attribution and changes; the adjacent GPLv3 text
matches upstream byte-for-byte. Per-file GPL-3.0-or-later labels identify the
transferred material, without claiming the final distribution audit is complete.

Preview projection is an independently implemented/tested Mercator policy. It
keeps GPS segments separate, handles the short longitude arc, centers stationary
routes and clamps only the display projection at the poles. No recorded fix is
rewritten. Missing previews use a neutral fallback. Drawing rows does not start
data queries or construct native maps; preview read/cache ownership is unfinished.

## Verification

- h419: six fresh tests failed on missing projection/duration implementations.
- h420: those six passed; two new relative-timeline tests failed before their
  implementation. A four-second interval at a real Unix epoch remains distinct;
  the UI uses a fraction and retains selected time as Long. NaN/infinite input,
  overflowed ranges and a legitimate zero-duration interval are covered.
- h421: 4,233 tests / 435 fresh XML, zero failures/errors/skips. All 55 Gradle
  tasks executed, including actual database migration verification and debug APK
  packaging. No source edits during that run. Both locales contain 59 identical,
  unique history resource keys.
- Debug APK SHA-256:
  `312B9892A814DA64109EA5680B176E9CDAFC90DA560B905361665592E2297040`.

## Observed on API 34 emulator

The debug-only activity supports `catalog=true` (synthetic 24-ride fixture,
20-row first page) and `stored=true` (ordinary app reader resolved through Koin).
Modes are read at Activity creation; use a fresh launch when changing modes.

- Canvas route cards render separate segments. The synthetic legacy card has the
  neutral missing-preview fallback. Selecting a ride opens its recorded map;
  system Back returns to the catalog.
- The older-page button loads the remaining fixture page and disappears at the
  end. This is native interaction evidence, not a scrolling FPS benchmark.
- At font scale 200%, fully visible cards keep title/date/details readable in
  portrait and landscape. Row height can grow instead of clipping fixed-height
  text. Effective font scale was restored to 100%; Android recreated the previously
  unset setting as 1.0. Rotation settings were restored to accelerometer=1/user=0.
- A fresh `stored=true` launch lists existing legacy recordings from the emulator's
  app database, including their actual dates and the recorded 4:12 duration of a
  closed ride. Opening that ride obtains a detail range, shows zero GPS segments
  and accepts a relative timeline selection; Back returns to the real catalog.
  The fixture does not insert or rewrite recordings, start BLE/location, or
  recover an active ride. The stored debug mode deliberately supports closed
  detail only and does not invent a current v2 clock generation.

Ignored screenshots: `h421-catalog-page.png`, `h421-catalog-font2.png`,
`h421-catalog-landscape-font2.png`, `h421-catalog-stored.png` in the execution ledger
directory. The 1970 dates in synthetic mode are fixture data; stored mode uses the
ordinary persisted timestamps.

## Remaining work

Bounded preview reads, revision/style-aware tile caching, transport-name binding,
recorded distance/date grouping, automatic end paging, persistent metric presets,
full home/summary/chart composition and production root/clock/recorder integration
remain open. Native checks here do not establish phone performance, long-list
memory bounds, a complete Vescape port or signed release readiness.
