# Adaptive portrait and landscape UI

## Goal

Keep the existing portrait dashboard composition while making landscape a usable
dashboard rather than a vertically clipped version of the portrait column.

## Design

The UI derives a small, pure layout decision from the available content width and
height. It never uses device names, fixed screen dimensions, or hand-written
status/navigation-bar sizes. System insets continue to come from Compose's
`statusBarsPadding()` and `navigationBarsPadding()`.

On a wide window, the ride dashboard keeps its vehicle/status header full width,
then places the speed hero in one pane and metric/consumption content in the
other. Graph, odometer, and telemetry details remain reachable in the same
scrollable content. The battery dashboard uses the same two-pane shape: the
hero remains visually primary while power, voltage, cells, and temperature
cards occupy the second pane. Portrait keeps the current order and spacing.

The picker, wizard, settings, graph, and detail screens retain their scrollable
flows; their rows use available width and may wrap, so this pass does not
introduce a second visual language for those screens.

## Verification

The pure layout decision is covered by common unit tests for portrait, square,
and landscape constraints. Existing dashboard component tests remain unchanged.
Compose itself is verified with emulator screenshots at the existing 1080x2220
portrait and 2220x1080 landscape sizes: no content is clipped behind the
bottom bar, and the primary metric cards are visible without a second gesture.
