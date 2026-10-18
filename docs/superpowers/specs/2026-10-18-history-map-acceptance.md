# Recorded map layer: first native acceptance

This checkpoint adds a history scene to the existing retained MapLibre renderer.
It does not activate the new history destination in the production root or enable
the replacement recorder.

## Recorded-data policy

- Positions come from recorded GPS fixes. There is no live-location fallback,
  interpolation or dead reckoning in the historical layer.
- Explicit GPS/recording continuity boundaries and silence beyond the caller's
  tolerated GPS cadence split the line. An independent battery BLE outage does
  not split GPS geometry.
- The cursor uses a recent recorded past fix on the stable monotonic chart axis.
  It disappears across a recorded boundary, outside the accepted age or before
  the first fix. Invalid clock generations and reversed fix order fail closed.
- Endpoint markers describe the loaded portion. They do not assert that a paged
  route contains the whole ride's start and finish.
- The line is neutral. Metric coloring remains unfinished; missing telemetry is
  not rendered as a measured zero.

Camera fits carry ride identity, selection generation and host-provided pixel
insets. A history command cannot act on a different ride/generation or a live map.
An empty viewport or route has no fit. Cursor changes do not issue a fit command.
Live overlays and the live motion-prediction loop are suppressed while the
historical scene is supplied; their owning subscriptions are not changed.

## Native observations

Emulator Volty_Nearby_UI_API34, API 34, debug APK 0.7.27 from h415. The debug-only
`HistoryUiProbeActivity` with the `map=true` extra uses the real history component
and MapLibre renderer with a synthetic read-only repository. Six fixes form two
segments around an explicit GPS gap; the fixture never accesses user recordings,
BLE or platform location.

- Both segments render independently with no connecting line across the gap.
- Selecting axis time 3000 displays the recorded selected point. Moving the
  slider leaves the camera's position and zoom unchanged.
- Selecting time 2513, inside the gap, removes the selected point. The gap marker
  remains visible and the camera stays in place.
- In landscape, both segments fit above the fixture's bottom slider panel.
  Original emulator rotation settings were restored after verification.

Local ignored screenshots: `h415-map-cursor.png`, `h415-map-gap-cursor.png`,
`h415-map-landscape.png` in the execution directory.

After separating route, anchor and cursor GeoJSON updates, h418 ran the complete
suite: 4,225 tests in 434 fresh result files, zero failures/errors/skips. Database
migration verification and debug packaging both executed. On the newly installed
APK, cursor time 3000 and removal at 2510 were checked again without a camera
shift (`h418-map-cursor.png`). APK SHA-256:
`CF8D096568B99FA04FD7EC9A404BCB784BE9E8C03C8ACB7C7291958B2BC215B9`.
This identifies the debug acceptance binary, not a signed release delivery.

Camera-command regression tests first failed on a changed participant position
and then explicitly on a changed live fix under a consumed `Recenter`. The final
policy retains command kind/sequence deduplication and separately fences history
identity/insets, so payload updates do not repeat old live camera actions.

## Remaining acceptance

The fixture proves native rendering and cursor interaction with synthetic data.
It does not prove a complete Vescape screen port, actual recorded-route UI reads,
phone performance, dateline camera framing, singleton framing under large panels,
animated fits, style-switch/back-stack transitions or production navigation
activation. Route/anchor/cursor GeoJSON updates are separated; caching the common
scene's geometry during scrubbing belongs to the screen integration. Metric
coloring, panels and full visual adaptation are still open.
