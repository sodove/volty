# Recorded metric layouts — acceptance checkpoint

Date: 2026-10-20. This checkpoint extends the approved history-screen plan's
metric drawer. It does not activate the new recorder or production history route.

## Behavior

The drawer provides explicit named save/apply/delete actions for ordered metric
layouts. Each layout belongs to one vehicle and stores the exact source ID, source
kind, metric ID and optional cell index. Saving the same name within that vehicle
updates the layout in place. Names are trimmed, limited to 80 characters; storage
holds at most 24 layouts with 128 metrics each and a bounded encoded payload.
Limits fail without changing existing preferences.

Recorded native source IDs already qualify the physical source and producer
policy (protocol and, where relevant, calibration); labels are display metadata.
Resolution also requires the vehicle, because legacy sources use generic IDs.
A renamed source with the same identity resolves. A replacement with an equal
display name, changed kind, ambiguous duplicate ID or incompatible metric/cell
shape stays unavailable. Unknown future metric IDs survive persistence and show
as unavailable rather than being silently discarded. An entirely unavailable
layout cannot replace the current selection. Partial layouts show their available
and unavailable counts before application.

The DataStore adapter shares the existing preferences instance through DI. It
decodes, updates and encodes inside one atomic update, preserves other preferences
and layouts, and reports corrupt or unsupported-version payloads without erasing
them. No database migration is needed. Reading layouts never reads telemetry;
application explicitly loads only selected compatible series. A layout replaces
the ordered selection while retaining the current time range/cursor and already
loaded matching values. Retired reads cannot repopulate removed metrics.

## Automated evidence

- h436: 15 tests, 14 causal TODO failures before resolver/capture and storage
  implementation; model invariant test passed.
- h437/h438: compilation failures, not behavioral RED evidence.
- h439: 51 tests, nine causal TODO failures in the preset owner (four), atomic
  selection replacement (three), and parent integration (two). Policy/storage and
  existing detail/parent cases passed.
- h441: all 51 focused tests passed; five fresh XML files, no failures, errors or
  skips. Both locales contain 74 unique matching `history_*` keys.
- h442: complete 4284-test suite in 442 fresh XML files, no failures, errors or
  skips; actual database migration verification and debug APK packaging executed.
  All 55 Gradle tasks executed in 90 seconds, workers/processor cap four.

The policy tests cover equal-name wrong-source rejection, renamed correct source,
vehicle and kind mismatch, sparse cells, unknown/invalid metric shape, ambiguous
IDs, order/deduplication and capture immutability. Persistence tests exercise a
new adapter reading previous encoded preferences, updates/deletes, limits,
unknown metrics and corruption/version failures. Component tests use actual
issued reads and gated noncancellable replies, including closing during a save,
changing ride during a save, and synchronous ride change during publication.
All delayed gates are released in `finally`.

## Native evidence

API34 x86_64 emulator, existing app data preserved. Debug-only rendering fixture
uses the real parent, drawer, preset owner and DI-backed persistent DataStore.
Synthetic source/series data does not claim physical telemetry acceptance.

- Selected Voltage from Pack 1; saved `native-h442` with the keyboard open. Name
  and save action remained visible. Archived `h442-preset-save-ime.png`.
- Stopped/restarted the app process with `am start -S`. Opening layouts restored
  the saved entry as one available metric; applying it restored Voltage / Pack 1
  in the selected list. Archived `h442-preset-restored.png`.
- Restarted with debug-only `omitFirstSource=true`. Only the other identically
  named pack remained. The saved layout showed zero available / one unavailable
  and disabled application, with no automatic binding to the remaining pack.
- Landscape scrolling reached the name field; typed text remained above the IME.
  Native scrolling was verified using the observed Android viewport; an earlier
  automation swipe missed that viewport and is not counted as an app defect.

Native review found the disabled layout's availability label too faint. The final
patch supplies explicit readable text colors while retaining disabled semantics.
Final h443 ran all 55 tasks in 90 seconds: 4284 tests / 442 fresh XML files, zero
failures, errors or skips, with actual migration verification and debug assembly.
APK SHA-256: `BB2CBA80EFC252124EBEAB255CBD66A16A8DB09B5DB63EAEE6CFC3761735A87E`.
The installed final build showed readable unavailable name/count at font scale
2.0; archived `h443-preset-unavailable-font2.png`. Deleting the test layout showed
the empty state; after another process restart the layout remained deleted.
Effective emulator settings restored to font scale 1.0, auto-rotation 1 and user
rotation 0. No physical-device rendering or FPS measurement is claimed.

## Remaining scope

Full home/summary/charts, metric-colored map, bitmap/tile preview cache,
favorites/trim/media/export and production root/recorder activation remain in the
approved plans. This checkpoint is neither signed delivery nor physical-device
acceptance of the recorder or vehicle protocols.
