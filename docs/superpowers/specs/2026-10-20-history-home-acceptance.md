# Composed history home: checkpoint acceptance

`HistoryScreen` now composes the existing catalog/detail/route/metric/preset read
owners into the map-backed home. It is exercised through the debug-only native
probe; production root routing and recorder activation remain OFF. This is a
Task5 composition checkpoint, not completion of the full history rewrite.

## Screen and ownership

- One native map remains mounted while fullscreen charts replace the home
  chrome. Both screens use the same component, range, selected keys and cursor.
  No live graph, BLE, location or recorder subscription is introduced.
- The home ports Vescape's header/summary, bottom telemetry/navigation panel and
  ride-list sheet responsibilities. The central title opens the catalog; loaded
  adjacent rides have previous/next controls. A separate metric sheet replaces
  the fixed upstream metric-button bar. Favorite/media/export actions are not
  represented by inactive buttons while those owners are still pending.
- Source-qualified compact summary shows whole-recording or selected-window
  scope explicitly. Cursor values are separately labelled in charts. Whole
  recording means the original available recorded window, not today's clock.
- Portrait uses a bottom panel and landscape a side panel. Compact home plots
  omit the redundant numeric scale row; fullscreen retains it. Narrow windows
  below360dp or font scale above1.3 use a two-row ride selector/action layout.
  Large-font/short panels allow scrolling of their chrome as well as the bounded
  lazy chart stack; no controls depend on being simultaneously visible.
- Panel sizing uses180ms animation unless the caller requests reduce motion.
  Summary expansion and native fit also receive that reduce-motion flag. Drawer transitions
  remain Material3. No claim of measured60fps or complete motion parity is made.
- Camera fits use measured header/panel padding, frozen for each explicit fit
  command. Cursor/window updates and panel animation do not issue repeated fits.
  A fit button remains available after manual camera movement/panel expansion.
- Map scene work runs off UI. Results are bound to exact ride/route/origin/cursor
  inputs. A changing cursor may temporarily hide its marker but keeps matching
  earned route geometry; old-ride/window geometry is cleared immediately.
- A slower range request clears old numbers/paths but preserves each chart body's
  measured height. Loading/error/empty states do not display the previous window
  as a placeholder. Map read/partial/noGPS status is independent of numeric data.

The original Kotlin screen policy has six tests: same qualified detail/route in
home and fullscreen, catalog/loading/foreign identity exclusion, separate missing
and storage failures, exact route/window join, retained partial/noGPS quality, and
loaded-order adjacency without wrapping/sorting/ambiguous duplicate IDs. h486 was
genuine RED6/1XML/6TODO failures; h489 passed6/1fresh XML. These test data/state
contracts, not Compose layout or native pointer behavior.

## Native evidence

API34 x86_64 emulator,1080×2220 portrait /2220×1080 landscape:

- The composed home shows the complete compact plot and cursor slider together.
  Summary minimum/maximum/energy and partial-quality labels are readable.
  Earlier acceptance drafts exposed clipped labels and a mostly clipped plot;
  the host/body bounds and compact chart/navigation layout were corrected.
- Home slider selected3s/700W. Fullscreen showed the same700W and72V; zoom to
 2–4s then Back preserved that window/cursor and range statistics (maximum1200W,
  not the whole-window4200W). The summary label switched from whole recording
  to displayed range. Repeated on the final h493 binary.
- `dumpsys activity top` on h491 recorded the SAME `MapView{bc9e49f}` in home,
  fullscreen and returned home, one instance each. This establishes native
  view retention for that transition, not cross-Activity/root/process retention.
  Trace: `h491-map-retention.log` in the ignored rebuild workspace.
- Catalog sheet selection opened Bicycle; Next opened Wheel. The metrics sheet
  accepted a search with the keyboard visible. Back first hid IME, then the sheet,
  returning to the same home. No second history component is allocated.
- Russian dark font200% portrait/landscape: adaptive title/actions wrap; vertical
  chrome/plot scrolling reaches the charts, slider and all attribution links.
  The slider produced72V. Expanded large-font summary exposes source/value/partial
  labels, rather than only the heading. Screenshots include
  `h491-home-wide-font2.png` and `h492-summary-wide-font2.png`.
- Debug-only3000ms series/stats/route delay exercised an actual changed range.
  The loading screenshot has progress and no old values/paths. Native XML places
  Power/Voltage/Current headings at the same top bounds319/985/1651 before/during
  loading. Evidence: `h491-range-loading.png/.xml`.
- Stored-mode catalog reads actual existing legacy rides. Unclosed rows and the
  closed2026-10-09 17:38:03.950–17:42:16.251 row open in this screen. NoGPS is
  explicit, fit is disabled, and no current-location marker is invented. Empty
  selection prompts for recorded metrics. Screenshot: `h492-home-stored-legacy.png`.
  This is not a measured loading-latency/performance acceptance.
- Final h493 dark home fixes the window background under system insets: status
  icons are readable over the theme background. Screenshot: `h493-home-dark.png`.
  Original font1/rotation0+auto1/nightno/app locales[] restored afterward.

The debug fixture reconstructs its owner across Activity recreation. Rotation
checks therefore prove adaptive layout, NOT production retained/restored selection.
Physical devices, sustained rendering performance, the20+metric composed-screen
matrix, all map camera cases, and final root integration remain later gates.

## Final source/binary verification

h493: **4359 tests /452 fresh XML**, zero failures/errors/skips; actual SQLDelight
migration verification and debug assembly, all55 tasks executed,103s with rerun,
no build/configuration cache and four workers. Source frozen after this run.
Final debug APK SHA256:
`9EADE094DF48B5A1EE0A8806CAF96502A998C4BD7A38C7553B511738E54830F8`.

Both locales have121 unique matching history keys. GPL attribution/source changes
are listed beside the port and in `docs/third-party/vescape-history.md`; original
LICENSE bytes remain unchanged. No new runtime dependency/schema/BLE write was
introduced. Combined-distribution audit, signed release and the remaining
map/catalog/favorites/trim/media/export/recorder/device requirements are still open.
