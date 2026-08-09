# Telemetry graphs, ride history, and comparisons

## Status

Scope approved in conversation on 2026-08-09. This written design still needs
the rider's review before implementation starts.

## Problem

The current graph screen plots a small set of live BMS values from the in-memory
sample ring. It cannot show controller motion data, cell spread, a precise value
at a selected moment, more than one series at once, or a graph after the app has
been restarted. The rider wants both time-based telemetry and a way to inspect
relationships such as voltage versus current.

## Goals

1. Plot battery, controller, and cell-balance telemetry using the same honest
   unknown-value rules as the dashboard and alarm engine.
2. Keep a smooth live graph for the current ride and persist completed rides
   locally so they can be opened after a restart.
3. Let the rider select a time on one graph and see the corresponding timestamp
   and value; selection is shared by all visible time-series graphs.
4. Allow several graph cards to be open together, with an adaptive layout that
   works in portrait and landscape without hardcoded insets.
5. Provide an XY mode for a selected pair of compatible metrics, in addition to
   synchronized time-series graphs.
6. Make the first version testable in common code without pretending Compose
   rendering is covered by unit tests.

## Non-goals for this iteration

- Writing to any BLE characteristic or changing controller/BMS behaviour.
- Storing every raw BLE frame indefinitely.
- Arbitrary user-authored formulas or multiple independent Y axes on one plot.
- Cloud sync, account login, or export format design.
- Per-controller overlays when the repository already exposes a vehicle-level
  aggregate (`motionSamples`).

## User experience

### Opening graphs

The graph destination keeps the existing metric/window entry point, but metrics
are grouped into Battery, Motion, and Cells. The rider can add a metric card;
cards can be removed or reordered later without changing the stored ride. The
selected time window applies to all time-series cards. A horizontally scrollable
metric picker is used when the available width is small; landscape may use a
two-column card grid.

The initial card set remains small (SOC, pack voltage, and speed when available)
so opening the screen is not overwhelming. Additional cards are opt-in.

### Time-series interaction

Each card renders a timestamped series, not just an unlabelled list of floats.
Tap or drag on the plot selects the nearest sample in time. A selection marker
and compact detail row show the local time, formatted value, and metric name.
The same selected timestamp is projected onto every visible card; each card
shows its nearest honest sample, or “нет данных” when that metric was not
reported at that moment. Clearing selection returns to the live “now” summary.

The graph continues to work when samples arrive at different cadences: the
selection uses timestamps and nearest-point lookup, never list index equality.

### XY comparison

An XY action opens a separate comparison plot. The rider chooses an X metric and
Y metric from compatible numeric series (for example current → voltage, speed →
duty, or temperature → power). Each point is paired by nearest timestamp within
the comparison tolerance; unmatched or unknown values are omitted. The plot
shows the selected point's X/Y values and keeps the source time available in the
detail row. It is a relationship view, not a replacement for the time axis.

## Metrics and evidence

The graph model uses a typed metric descriptor with a source, unit, display sign,
and an evidence predicate. A point is absent when its predicate is false; zero is
kept when zero is an earned measurement.

### Battery metrics (`BmsData`)

- State of charge (`socKnown`), percentage.
- Pack voltage (`voltage`), volts.
- Battery current (`hasCurrent`), amps.
- Battery power (`hasPower`), watts, displayed with the existing
  consumption-positive sign convention.
- Highest and lowest cell voltage, volts, when `cellVoltages` is non-empty.
- Cell imbalance (`max(cellVoltages) - min(cellVoltages)`), millivolts.
- Optional per-cell lines for a selected pack when positional cell data is
  available; the aggregate vehicle view uses highest/lowest/spread instead of
  pretending concatenated cells from parallel packs are one physical string.
- Maximum BMS temperature, °C, when the temperature list is non-empty.

### Motion metrics (`ControllerData`)

- Speed (`speedKnown`), km/h.
- Duty/PWM (`hasDuty`), percent.
- Battery current (`hasBatteryCurrent`), amps.
- Input voltage (`hasInputVoltage`), volts.
- Power (`hasPower`), watts, using the same explicit sign/display convention as
  the ride dashboard.
- Electrical RPM (`eRpm`), RPM.
- ESC temperature (`hasEscTemp`), °C.
- Motor temperature (`hasMotorTemp`), °C.

Vehicle-level motion points come from `BmsRepository.motionSamples(window)`;
battery/cell points come from `samples(window)`. The two streams retain their
own timestamps and are merged only by the presentation graph model. No metric
may manufacture a zero for a missing source.

## Data model

Introduce a pure common graph model along these lines:

```text
TelemetryMetric(id, group, label, unit, source, evidence, transform)
GraphPoint(timestamp, value)
GraphSeries(metric, points)
GraphSelection(timestamp, point-by-metric)
```

`GraphComponent.State` exposes the active window, visible metric cards, series,
selection, and current/summary values. The component owns collection and
selection logic; Compose only renders state and sends intents (`addMetric`,
`removeMetric`, `selectTimestamp`, `openComparison`).

The nearest-point and timestamp-pairing functions are pure and deterministic.
They must define tie-breaking (earlier sample wins) and a maximum pairing gap so
an old sample is never presented as if it were simultaneous.

## Persistence

The current ride is kept in the existing in-memory ring buffers for responsive
rendering. A ride recorder observes the same already-aggregated BMS and motion
streams and writes downsampled points to SQLDelight at a bounded cadence (one
record per metric bucket, not one row per BLE notification). Recording starts
when a vehicle connection becomes active and closes after the existing ride
session stop/idle rule.

Persist:

- ride id, vehicle id, start/end timestamps, and a small summary;
- metric id, timestamp, value, and an evidence/known bit for each stored point;
- optional cell index for per-cell series.

Do not persist fabricated values. A missing point is represented by no row (or a
known bit of false where preserving a gap is useful), never by a numeric zero.
Retention is bounded by a configurable maximum number of completed rides and a
reasonable per-ride point cap. The first implementation can expose deletion of
an individual ride and prune oldest rides when the cap is exceeded; cloud sync
and export remain separate work.

SQLDelight work must follow the repository migration rule: inspect the current
schema/version first, add the next numbered migration and snapshot, and rebuild
tables instead of using `DROP COLUMN` on API levels whose SQLite is too old.

## Rendering and adaptive layout

The chart primitive receives timestamped points and a selected timestamp. Its
geometry (range, x mapping, nearest point, and XY pairing) lives in pure common
code. Compose is responsible for adaptive card layout, labels, markers, and
accessibility descriptions. Cards use measured available width and window
insets; no device-specific inset constants are introduced.

Landscape is treated as a first-class layout: cards may flow into columns and
the metric picker remains reachable without clipping. Emulator/screenshot QA is
performed when a device is available; the repository has no Compose UI test
source set, so common tests cover behaviour rather than pixels.

## Testing strategy

- Unit-test every metric evidence predicate, sign/unit transform, cell spread,
  and unknown-vs-zero case with deliberately incoherent fixtures.
- Unit-test timestamp ordering, nearest-point selection, tie-breaking, and the
  XY maximum-gap rule.
- Test stream merging with different BMS/motion cadences.
- Test ride recorder bucketing, restart loading, deletion, retention pruning,
  and migration snapshots.
- Keep existing graph tests for legacy metrics and add motion/cell/history cases.
- Run the full `:composeApp:testDebugUnitTest` suite with a fresh results
  directory before claiming completion; Compose visual checks remain a device
  responsibility.

## Rollout order

1. Pure metric/series model and selection/pairing tests.
2. Extend the component to collect BMS + motion timestamped series while
   preserving the existing graph entry point.
3. Add cell spread and optional per-cell series.
4. Add multi-card adaptive UI and synchronized selection.
5. Add ride recorder, SQLDelight migration, history list/detail screen, and
   retention/deletion.
6. Add XY comparison mode and its tests.
7. Run device screenshots in portrait and landscape, then the full test suite.
