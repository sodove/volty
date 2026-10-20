# Shared recorded time window: control checkpoint

This extends the recorded chart checkpoint with explicit zoom, earlier/later pan,
whole-recording reset, clock endpoint labels and a named shared-cursor slider.
Pinch/drag preview with a settled read commit remains required; these accessible
controls complement it. Full Vescape-shaped home/navigation, product/recorder
cutover, favorites/media/export, release and physical acceptance remain open.

## Contracts

Detail state exposes `availableRange`: the qualified recorded window determined
when opening the ride. It survives narrowed-range reads and clears immediately on
new selection/retirement. An unclosed legacy recording continues using its actual
persisted timestamp bounds, never today's clock. Range changes still use the
existing serialized/fenced detail and route ownership; cursor is retained only
inside the new window, and selected keys do not change. Reset uses original bounds.

`historyTransformWindow` operates relative to recorded bounds, avoiding loss of
millisecond endpoints near Long limits. Zoom preserves the clamped anchor;
positive finger-style pan reveals earlier time by a fraction of the current span.
Clamp preserves width at either edge. Default minimum window is1s or the actual
recording duration when shorter; a single-point recording stays a single point.
Invalid/nonfinite parameters, stale/outside bounds and overflowing clock spans
return unavailable rather than wrapping timestamps. No-op preserves exact endpoints.

Only user commands change the committed window. Cursor slider changes do not
reload series/stats. All numeric rows share the same window, and the map and
summary consume that committed state. Clock labels display milliseconds when
needed and include dates when crossing a day. Each icon has a translated label;
the slider has a translated shared-cursor description. Wide layouts place icons
and slider on one row to retain chart space in landscape.

## Verification

h476 first failed32 tests/2XML with9 expected failures:8 inert transform-policy
cases and1 missing available-bounds owner case;23 existing detail cases passed.
The owner regression asserts actual issued series-read windows, selected cursor,
reset and retirement. Policy cases exercise anchors, clamp/pan direction, minimum
and short/point recordings, invalid inputs, stale bounds, extreme finite zoom/pan,
large positive/negative epochs, exact no-op and overflow rejection.

Native iteration found that the old debug fixture returned whole-ride GPS/static
summary even after zoom. The fixture now filters raw GPS/markers by requested
window and calculates stats from accepted raw samples before overview thinning;
production readers were not relaxed. This keeps the test capable of observing
range propagation instead of hiding it behind constant fake values.

Native h478 verified1–5s →2–4s zoom, earlier1.5–3.5s, later2–4s and reset1–5s.
Both plotted scales changed together. After zoom, chart tap selected3s with700W/
72V; Back retained the map's two recorded segments and3s cursor, and the range
summary maximum changed from4200W to1200W. Exact per-range energy remains the
reader's result (the tiny fixture's fractional Wh rounds at the UI's display
precision). No hardware/voice/BLE work or live recording was started.

The first font200% landscape check showed excessive footer height; wide layout
now puts icons and slider side by side. Final binary/layout checks are recorded
in the rebuild ledger before this checkpoint is committed. This is not a claim
of TalkBack end-to-end acceptance or completed pan/pinch interaction.

Final h479:4343 tests/450 fresh XML, zero failures/errors/skips; actual migration
verification plus debug assembly, all55 tasks executed with rerun/no caches and
four workers. Installed APK SHA256:
`62883D0D8591FB30E0B740E882B8B093554DDB6D0A920C2CBBB6EDC637330D4B`.
Russian dark font200% wide-layout zoom and the actual named slider were exercised:
2–4s window, midpoint selecting3s/700W. Internal scrolling keeps the plot reachable
with large text. Portrait retains the full-width slider. These are separate layout
checks: the debug Activity reconstructs its synthetic owner on rotation; production
retention/restoration must be verified during real root integration, not inferred.
Original font/rotation/night mode/app locales were restored. Both locales contain
106 matching unique history keys. Native screenshots h478-window-summary-map and
h479-window-wide-font2-ru are archived in the ignored rebuild workspace.
