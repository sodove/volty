# Telemetry Plot Workspace Design

## Problem

The current graph screen splits each metric into a large card and hides most
signals in horizontal pickers. The comparison dialog is an XY scatter plot, so
it does not resemble the VESC Tool workflow where several time-series are
plotted together and every available signal remains visible in a data list.

## Decision

Replace the card-first graph screen with a single telemetry workspace:

- one shared time axis and one plot surface;
- every selected metric is drawn as a colored trace on that axis;
- each trace gets its own honest vertical range, while the legend/table shows
  the unit and exact value so unrelated units are never presented as one scale;
- a complete metric table is always rendered, grouped as Battery, Cells, and
  Motion; a Plot checkbox turns each trace on or off;
- tapping the plot moves one shared time cursor and all table rows resolve the
  nearest measured point, omitting unknown values;
- portrait uses plot-over-table, landscape uses plot-beside-table;
- the existing history flow remains available, and the comparison action opens
  the same plot/table workspace instead of a clipped XY chooser.

The component state and persistence contracts remain unchanged. Existing XY
pairing helpers stay available for tests and future export, but the primary
comparison interaction is synchronized time-series overlays, matching VESC
Tool's Data/Stats style.

## Non-goals

- no BLE protocol or write-path changes;
- no fabricated values for unsupported controller metrics;
- no fixed inset or device-specific coordinate offsets;
- no new database migration.

## Verification

Pure plot geometry tests cover the shared time axis and per-series ranges.
Existing graph/component/history tests remain the regression suite. Compile,
full unit tests, migration verification, and portrait/landscape emulator
screenshots are required before release.
