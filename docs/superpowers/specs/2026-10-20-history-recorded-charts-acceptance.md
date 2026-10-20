# Recorded chart stack: rendering checkpoint

This is the recorded-series rendering part of screens Task5. It is not complete
fullscreen/home acceptance: time-window pan/pinch, accessible timeline controls,
time-axis labels, remaining visual/navigation composition and product routing are
still required. Recorder cutover, favorites/media/export and signed/device
acceptance remain outside this checkpoint. No production entry was switched.

## Projection and ownership

`HistoryChartScene` accepts the already bounded recorded overview and requested
window, validates series identity/order/range/clock metadata and projects points
onto the same canonical time axis as the map/cursor. Legacy uses saved wall time.
Display sign is applied once. Empty/all-unknown series remain empty. Constant
values stay drawable; finite extreme values and full-Long legacy bounds do not
produce nonfinite coordinates. Truncated reads retain their warning and never
extend a point to an unrecorded viewport endpoint. Plot min/max describe the
drawn scale only; exact statistics still come from the independent durable reader.

Acquisition gaps are inserted before overview thinning. Sparse retained timestamps
do not establish new gaps. Unknown rows split paths in their saved order, and
only relevant global/source markers split numeric lines; GPS/other-source losses
do not erase healthy telemetry. An earned first point after a gap remains drawn.

Retraction: the initial chart implementation reused the cursor's equal-time
unknown-wins rule. Actual `historyOverview` output can contain a synthetic GAP
separator followed by a real sample at the same tick; grouping away that sample
hid an earned84V peak as70V in the regression. Ordered line breaks now preserve
all earned points. Cursor VALUE ambiguity stays conservative and unchanged; line
geometry and selection-value qualification have different responsibilities.
A marker at a restart point splits the entering edge once, allowing the next
healthy edge, matching the raw continuity index's strict previous bound.

`HistoryChartPanel` lazily renders only selected series already owned by the detail
component. Scene calculation runs off the UI dispatcher, with exact input binding
to avoid an old range/ride flash. No live GraphComponent or recorder is created.
Separate Canvas paths/single-point dots represent loaded segments, and a shared
cursor shows only earned values allowed by the caller-supplied age policy.
Taps issue a time selection to the existing owner. The fullscreen wrapper reuses
the existing metric drawer/presets and parent navigation rather than duplicating
selection, requests or streams. This is original Kotlin integration code; it
does not import another charting runtime.

## Executed checks

Initial eight projection contracts failed against an inert implementation (h470),
then passed alongside existing cursor/summary tests(h471:22 tests/3 XML). Primary
review added an actual-overview round-trip and corrected the overly broad initial
line-ambiguity contract. h473 reproduced the erased post-gap peak; h474 reproduced
all three failures: earned point deletion,84V becoming70V, and an unnecessary
second break after a restart marker. The ten projection cases cover these along
with source/GPS independence, explicit unknowns, sign/clock rollback, empty/zero,
truncated tails, invalid metadata, legacy and numeric extremes.

Native base rendering(h472) verified split power/voltage paths, a second source's
unavailable current, and selection of time3000 from a real Canvas tap. Both rows
then showed700W/72V. Opening metrics and pressing Back returned to the same
charts/cursor; the next Back returned to the recorded map with cursor3000 and
the actual cyan recorded GPS point. This proves shared state, not complete gesture,
frame-rate, accessibility or physical-device acceptance.

Final corrected run h475:4334 tests/449 fresh XML files, zero failures/errors/skips,
actual SQLDelight migration verification and debug assembly; all55 tasks executed,
rerun tasks, no build/configuration cache, four workers. Installed APK SHA256:
`3786FC4550C48B86789422AE1CDCB2B48F8298F336BF7BFC2F015C2002682782`.

The final native fixture uses the actual overview projector plus a scoped BLE
marker, rendering three numeric segments without deleting the first post-gap
point. Power and voltage share the selected3000 cursor(700W/72V); Back preserves
that time and displays the map's actual cyan recorded fix. Numeric and GPS gaps
remain independent. Russian dark font200% portrait/landscape supports vertical
scroll to the second source's no-data card; text wraps instead of truncating the
scale. Normal font/rotation/theme/app locale settings were restored. Both locales
contain100 matching unique history keys. Screenshots h475-recorded-charts-cursor,
h475-charts-font2-ru and h475-shared-cursor-map are in the ignored rebuild workspace.
