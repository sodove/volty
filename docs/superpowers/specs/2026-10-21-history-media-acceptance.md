# Curated history media acceptance — 2026-10-21

Scope: Task6 curated attachments for source-bound saved favorites, on the
existing composed history home. This is not production root/recorder activation,
confirmed whole-ride deletion, export, hardware acceptance or a signed release.

## Automated evidence

Final h542: **4448 tests, 461 fresh XML files, zero failures/errors/skips/stale
reports**. Clean results, `--rerun-tasks`, no build/configuration cache, offline,
four workers and four active processors. All 55 actionable tasks executed in 81s,
including actual SQLDelight migration verification and debug assembly.

APK `volty-0.7.27-debug.apk` SHA-256:
`E0328905CE1CED8AA088665449EAB5DBCBA70235DEAE5B0E4A05301EDEFB3A9E`.

The tests cover real temporary files/streams/hashes and SQLite, transaction and
quota invariants, deletion/import overlap, cancelled jobs, interrupted staged or
published copies, missing/corrupt files, failed cleanup retained for retry, MIME
qualification, strict EXIF time parsing, parent/picker ownership, and nearest
recorded fix placement. They do not simulate Android decoders or Photo Picker.
Migration 20.sqm/21.db preserves existing favorite revisions and recordings.

Early cancellation was tested RED before the fix: cancelling a scheduled launch
before its body starts never runs its finally and stranded the busy state. The
owner now retires publication and releases busy before cancellation; a new pick
works and the cancelled import was never issued.

## Native evidence

API 34 emulator, 1080×2220 portrait / 2220×1080 landscape, real app SQLite and
framework Photo Picker, BitmapFactory, ExifInterface, FileProvider and VideoView.

- Open a persisted favorite through the real catalog. Its media drawer launches
  the system selective-access picker; cancellation leaves Add available.
- A selected PNG imports and opens. Unknown capture time stays unknown and
  unpositioned. No gallery scan or persisted source URI is used.
- Explicit debug-only `seedPickedMedia` creates one named JPEG fixture with
  DateTimeOriginal, explicit +05:00 offset and 90-degree orientation. Choosing it
  through Photo Picker preserves capture epoch 1792400020000 and renders the
  orientation in thumbnail and viewer.
- A real moving emulator MP4 imports, produces a thumbnail and opens native
  playback controls. Native NuPlayer reached EOS with video/avc and no recorded
  AndroidRuntime/MediaPlayerNative error. Its date remains unknown. This short
  fixture does not prove audio quality, sustained performance or synchronized
  video telemetry. A separate zero-duration fixture is visibly rejected.
- The JPEG appears at its exact recorded fix as a purple map point. Tapping the
  point opens the verified private copy. The explicitly owned gallery fixture
  was then deleted; directory inspection confirmed the original was absent.
  After process restart and APK reinstall all three private attachments remained,
  including the JPEG and its source-qualified position.
- A native failure initially hid that position after an equal-value immutable
  list refresh: produceState used value-equality keys but its binder checked
  reference identity. The binder now uses the same value equality. Native
  before/after changed from unpositioned to On recorded route.
- Russian font 200% initially split labels inside narrow columns and hid Add
  below the gallery. The minimum tile width now scales with font size; actions
  are pinned below the scrolling grid. Portrait and landscape checks reached
  readable complete labels and accessible actions.

Native screenshot evidence is retained in the ignored task ledger directory
(`h539-media-restored.png`, `h540-media-font2-ru.png`,
`h540-media-font2-wide-ru.png`). These debug fixtures are explicit opt-ins and
ordinary startup never writes to the gallery or starts recording/location.

On final h542, cancelling attachment deletion retained all three items;
confirming deletion removed only the PNG copy, with its gallery original still
present. Cancelling favorite deletion retained the favorite and two remaining
copies. Its confirmation explicitly mentioned attachments. Confirming removed
the favorite, both remaining media rows and all owned private files. A fresh
native database snapshot retained the original 60s recording and all 7 GPS fixes;
the gallery PNG original remained. No whole-ride deletion is claimed here.
Font 1, portrait 0, automatic rotation 1 and empty app locale override were restored
and read back. Screenshot `h542-media-favorite-deleted.png` is in the ledger.

## Limits

Up to 256 MiB/file, 64 files/1 GiB per favorite, 512 files/2 GiB globally; pending
imports/deletions reserve quota until files are actually removed. Copies are
private UUID paths, source URIs are ephemeral and only verified content URIs are
offered to the viewer. Favorites retire child rows in the same transaction;
best-effort post-commit cleanup and startup reconciliation preserve tombstones
when removal fails.

Capture time requires an explicit EXIF UTC offset. Android video DATE may mean
creation or modification and is not a capture-time fallback. Location is only a
nearest actual recorded fix within 30s inside the same continuous segment and
the favorite/current window; no interpolation, EXIF GPS, current location or
bridge across missing GPS. Clock mismatch/change fails closed.

The gallery workflow transfer is attributed in its GPL-noticed source and
`docs/third-party/vescape-history.md`; the original upstream LICENSE bytes are
preserved. Final combined-distribution/source/notice audit remains open.
