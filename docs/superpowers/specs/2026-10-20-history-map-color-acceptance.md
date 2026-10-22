# Recorded metric map coloring: checkpoint acceptance

This completes the chosen-metric overlay portion of screens Task3 on the composed
history home. Remaining camera/native cases, product root/retention, catalog,
favorites/media/export, recorder cutover, hardware and distribution requirements
remain open. The production history root/recorder are still not activated.

## User behavior and evidence

Each selected chart has a labelled Map action. It selects that exact source/key;
fullscreen returns to the same map, and home charts collapse to leave room for
the route and legend. The legend identifies metric/source, earned numeric range,
partial coverage and the meaning of neutral grey. Clear removes coloring without
removing the route or changing the time window. Selection survives chart Back and
range/cursor changes, clears on removing the key/preset replacement/new ride/catalog,
and starts no additional series/stats/GPS read.

The palette is blue-to-purple over persisted selected-window extrema in display
sign. It is not a safety/temperature/speed threshold. Ruling: upstream Vescape's
device-specific hot ranges are not transplanted as universal Volty limits. This
is original Kotlin integration; no new upstream source/assets/dependency is added.

The full neutral recorded route remains underneath a bounded overlay. Each
colored edge uses the actual starting sample value, requires recent earned values
at both GPS endpoints, and rejects interior unknowns/relevant acquisition
boundaries. It never averages a new number, invents GPS points, bridges GPS
segments, uses future samples, or holds a truncated omitted tail. Duplicate-time
unknown wins. Other sources' gaps do not erase this source; GPS continuity belongs
to the separately qualified route. A connection exactly at the start may begin
an edge, but a connection inside the edge cannot bridge it.

Origin/key/order/finite-span guards fail closed. A pure evidence binder checks
selected key, read readiness, exact source kind/ID/index shape, and series identity.
One deliberately incoherent fixture puts a fully coherent FOREIGN series (both
key and samples rewritten) under the selected pack's map entry. An equal display
name and complete statistics cannot make it that pack's evidence.

At most4096 earned edges are overlaid; exceeding the budget reports partial
coverage and leaves the rest neutral. Stats/series incompleteness also remains
visible. Index creation is O(samples + markers·log(markers)); edge queries use
binary searches over sample/marker arrays, including many equal-time CONNECTED
markers. Retraction: the first ledger sketch called index creation linear before
the marker sort was implemented. No measured FPS/long-route latency is claimed.

Color derivation runs off UI and binds exact identity/origin/route/series/stats/age
inputs independently of cursor frames. New input hides a retired result immediately.
The native SDK gets a separate bounded color GeoJSON/layer above the neutral route;
live/Nearby route/camera inputs and subscriptions are unchanged. The existing
13.0.2 OpenGL artifact compiles and renders the numeric interpolation expression.

## Checks executed

Thirteen policy/binder tests cover sign/range, actual start value, inner unknown,
own/global/other-source boundaries, separate GPS segments, truncation, cadence,
equal-time ambiguity, zero, missing/foreign/invalid evidence, overflow, budget,
stored wall-clock rollback, duplicate connections and source/read qualification.
Two parent tests assert positive selection, actual unchanged issued read lists,
window/cursor/fullscreen preservation, explicit clear, removal/new ride/catalog,
and ignoring commands after close.

- h494 genuine RED8/1fresh XML/8TODO failures; h496 GREEN16/2 including existing
  cursor cases. An invalid NaN Stats draft was replaced by a constructible finite
  extrema span-overflow fixture. A CLOCK_CHANGE fixture was corrected to global
  scope, as its domain constructor permits source IDs only for BLE/CONNECTED.
- h497 genuine parent RED34/3/2 assertion failures, h498 GREEN34/3 plus debug.
- h499 genuine evidence-binder RED38/3/4TODO failures; final full h500 below.

Native API34 emulator:

- Home Power Map action shows the signed-appropriately -200..4200W range and
  partial/neutral explanation, while keeping unknown sections grey.
- Catalog fixture5 clears the previous choice. Explicit Load next route section
  appends the recorded4500ms GPS fix and removes the partial-route status.
  Fullscreen Voltage Map returns home with60..81V legend. Manual Fit visibly shows
  blue1000–1500ms and purple4000–4500ms edges; unknown edges and the GPS gap remain
  neutral/separate. Screenshot: `h498-map-color-voltage-loaded.png`.
- Current from same-name Pack2 contains no values: its legend says No data and
  the whole route becomes neutral, without borrowing Pack1 voltage/power.
  Clear removes the legend. This exercises actual native ownership/rendering.
- Final h500 repeats Power/Voltage selection. `dumpsys activity top` records the
  SAME single `MapView{ce020}` through home/fullscreen/home; trace is
  `h500-color-map-retention.log`. This is not cross-Activity/process retention.
- Final Russian dark font200% portrait/landscape Map actions work; vertical
  scrolling exposes range endpoints, partial/neutral labels, slider, clear and
  attribution links. Evidence: `h500-color-font2-portrait.png` and
  `h500-color-font2-landscape.png`. Original settings restored afterward.

Native artifacts are in the ignored rebuild workspace. The debug fixture rebuilds
its owner on rotation; these layout checks do not establish product restoration.
The caller must supply qualified cadence; the synthetic fixture uses exact-only
numeric age, not a cadence inferred from thinned chart spacing.

## Final verification

h500: **4374 tests /453 fresh XML**, zero failures/errors/skips; actual migration
verification and debug assembly, all55 tasks executed in93s with rerun, no build or
configuration cache and four workers. No app source edits afterward. Installed APK:
`B908F3449046737C65C4499F100864C0B31F2CC5D6E973BD76E745DC7E795E1E` (SHA256).
Both locales have126 unique matching history keys. No schema/dependency/BLE write
changes, push, production cutover, signed-release or physical-device acceptance.
