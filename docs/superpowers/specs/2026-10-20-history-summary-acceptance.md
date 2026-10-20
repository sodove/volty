# History range summary: checkpoint

This is the selected-window summary portion of screens Task5. The complete home,
chart stack/fullscreen gestures, product routing, recorder activation, favorites,
media/export and signed/hardware acceptance remain required. The panel is presently
exercised by the Android debug fixture, without activating a second product screen.

## Data contract

`historyMetricSummary` projects existing durable `HistoryStats` per selected key;
it never derives exact statistics from the reduced chart overview. Each minimum,
maximum and net-energy value has its own EXACT/PARTIAL/UNAVAILABLE quality. A null
field stays unavailable even when the stats object's complete flag is true. An
earned zero remains zero. Partial values remain visible and explicitly qualified.

Canonical battery current/power signs require swapping the displayed minimum and
maximum when applying the negative display multiplier. Stored Wh already carries
the consumption sign and is not negated a second time. Unsupported metrics/cell
shapes fail closed. Non-power metrics cannot claim an incoherent energy field.
Sources, individual contributors and vehicle aggregates are never summed here.

The Compose panel consumes the existing detail state's ordered selected keys,
qualified sources and persisted stats. Opening it performs no storage query.
Expanding it reveals a bounded lazy list, without automatically selecting/loading
all metrics or cells. Equal source names remain distinguished using the established
recorded-source labels. Loading/read failures and missing values remain visible.
The title labels this as the displayed range, rather than current cursor values.
Whole-ride and cursor sections are still part of the remaining home composition.

The compact/expand visual hierarchy directly adapts pinned Vescape
`HistoryStatsBar.tsx` at2527c346bbdb25d4e018fdd68e916621e20fac27. The file carries
GPL-3.0-or-later provenance beside the original license, with changes recorded in
the port README and third-party inventory. Flowing metric/source headings and
stat cells replace a fixed row of tiny columns. At large font sizes the summary
scrolls internally; its body occupies at most40% of available height, additionally
capped at190dp collapsed/300dp expanded. Reduction of motion skips panel-size
animation. Partial numbers carry an inline approximate sign as well as their
explicit quality label, so scrolling a label out of view cannot hide that fact.

## Evidence and limits

The seven pure regressions first failed against an inert summary implementation
(h464, all7 NotImplementedError), then passed (h465). They cover independent field
absence, deliberately incoherent energy on a voltage metric, sign-reversed extrema,
already signed energy, real zero, partial values, unknown/wrong cell shapes and
controller power. These tests do not test Compose rendering.

Native checks use a real retained MapLibre map with a debug-only synthetic reader:
partial power(-200..4200W,12Wh), exact voltage(60..84V) and absent current from a
second same-named battery. Expansion, bounded internal scrolling and collapse were
exercised; the second battery's absent current stays a dash with a no-data label.
Font200%, dark theme, Russian strings and both orientations are checked separately
from the pure tests. The fixture neither records rides nor starts BLE/location.

Native iteration exposed excessive fixed summary height in landscape; the panel
now caps height relative to the actual viewport and flows metric/source headings.
This checkpoint does not claim complete visual parity with the video, physical
device support, performance measurement or a release APK.

Final h469 verification:4324 tests in448 fresh XML files with zero failures,
errors or skips; actual SQLDelight migration verification and debug packaging;
all55 tasks executed with rerun/no build or configuration cache and four workers.
Installed APK SHA256:
`32DE4DC5A7B5455E2B091E5ADC92B6D29C36B269877E0392A930C2599EAC8A8C`.
The final binary was rechecked in Russian font200% landscape: inline partial
qualifiers remain beside all three numbers; scrolling reaches exact voltage and
the other battery's unavailable current. Normal-font light portrait also renders
all three values and quality labels. Original font/rotation/night mode/app locale
settings were restored. Screenshots are archived as h466/h469 in the ignored
rebuild workspace. Both locales contain94 matching unique `history_*` keys.
