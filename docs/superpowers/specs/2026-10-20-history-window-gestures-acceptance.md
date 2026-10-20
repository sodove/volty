# Shared chart gestures: checkpoint acceptance

This completes the shared pan/pinch/selection portion of screens Task5. It does
not complete the whole history rebuild: the composed home/navigation, transitions,
root retention/cutover, remaining map/catalog work, favorites/media/export,
recording/device acceptance and signed distribution remain required.

## Ownership

`HistoryComponent` owns a transient `windowPreview` and an opaque gesture token.
Frames transform the bounded preview through the existing window policy. They do
not change the committed detail/route window or start series/stats/GPS reads.
Release retires preview and commits once through the existing read owners. Cancel
retires preview without reads. A token is fenced by ride selection, committed
window, recorded bounds and a window-command intent. New gestures, explicit range
buttons, navigation and close retire old callbacks. Synchronous observers can
navigate or begin another gesture while preview clears; the old commit is checked
again after that publication.

Visible charts reproject existing real points against the preview, under a Canvas
clip. Their loaded Y scale/quality and original segments remain intact. No new
boundary samples, peaks, cursor values or statistics are synthesized. Going outside
the loaded window during a gesture leaves unobserved portions blank; release asks
the reader for the committed interval. Scene computation still runs off UI, with
exact input binding preventing an old ride/range result from replacing new data.

Input routing claims horizontal movement and two-finger scaling after touch slop.
One-finger vertical movement remains with the lazy chart stack. Taps retain shared
cursor selection. Motion cancellation, input disposal and lost ownership cancel
the preview. Existing translated icon controls and named slider remain available.
The time labels preview the window without adding/removing a row above the Canvas.

## Executed logic checks

Six parent cases cover preview/read separation, exactly-once commit, cursor/keys,
cancel/supersession, explicit range commands, navigation/close, synchronous
retirement reentry, invalid updates and no-op completion. Fake readers record
actual issued series/stats/route windows. The final stress fixture makes100 real
alternating window changes, asserting every preview before settling, with no
pre-release reads. Four viewport cases check immutable geometry/quality, points
outside the clip, separate gaps, missing regions, zero duration, large epochs and
invalid/overflow input.

Retraction: the first h480 draft had two tests passing under the inert gesture
implementation because they did not assert that preview was reached. Preconditions
were corrected before implementation. h481 then failed20 tests/2XML with all10
new contract failures; h482 passed28 tests/3XML including window policy controls.
The final stress fixture also replaces100 no-op calls with100 asserted changes.
These tests cover state/read contracts, not Compose appearance or pointer routing.

## Real native input checks

An Android-debug-only bounded MotionEvent helper dispatches real touchscreen
DOWN/POINTER_DOWN/MOVE/UP or CANCEL through the existing Activity decor view. It
never calls history state/owner commands. Shell intent coordinates come from the
observed native Canvas bounds. The helper is absent from release sources, validates
coordinates/main thread, recycles events and retires scheduled frames on detach.
Native diagnostics are confined to the synthetic debug fixture.

On the installed h484 binary:

- Tap selected3s, showing700W and72V through the same component.
- A two-pointer pinch moved1–5s to approximately2.001–4.002s. Incremental integer
  rounding accounts for millisecond offsets; no sample timestamps were rewritten.
  Logs show multiple distinct preview frames and **zero reads between them**.
  Release issued one batch:3 series,3 stats and1 route read.
- A real horizontal swipe moved that window to1.542–3.543s with one further batch.
- A second native pinch ended with ACTION_CANCEL after reaching an active preview;
  the committed window stayed unchanged and the total read count stayed21
  (7 initial +7 pinch +7 pan).
- Vertical swipe scrolled the list to the second source's unavailable card,
  preserving the committed window/cursor and the same21 reads.
- Russian dark font200% landscape, after scrolling the plot into view, accepted
  native pinch and issued one settled batch. The Canvas stayed at the same vertical
  position; no transient instruction row moved it under the fingers.

Logs/screenshots h483-pinch-cancel-trace, h484-native-gestures and h484-wide-pinch
are archived in the ignored rebuild workspace. This does not establish physical
device compatibility, steady60fps, cross-Activity retention or complete visual
parity with Vescape. Production root integration must still verify retention and
loading/transition behavior.

Final h485:4353 tests/451 fresh XML, zero failures/errors/skips; actual SQLDelight
migration verification and debug assembly, all55 tasks executed with rerun/no
build/configuration cache and four workers. APK SHA256:
`0841C3615B4759322FF792C5AFFF97D7CE4CFBB934922F7AB22CCA0DFAFB3673`.
This is byte-identical to the h484 binary used for the native matrix; h485 only
strengthened the unit fixture. Original font/rotation/night mode/app locales were
restored, and the final APK reinstalled. Both locales retain106 matching history
keys, with translated gesture accessibility instructions.
