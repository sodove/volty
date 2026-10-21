# Ride History export integration — 2026-10-21

The stored-history screen can now export its selected interval as GPX, CSV or
JSON, send a completed file through Android's chooser, or save it through the
system document picker. This is original Volty code. The existing Vescape pin,
notices and GPL source files are unchanged.

## Stored data and files

`SqlDelightHistoryExportSource` reads raw retained samples in bounded pages,
not the graph overview. Same-time rows and explicit unknown readings survive.
GPS and boundary events are merged by elapsed time. Full captured summary
buckets inside the selected interval retain their original coverage, energy,
sample count and completeness. Crossing buckets are excluded, never rescaled.

The legacy adapter preserves original ISO timestamp strings (including distinct
submillisecond timestamps), restores the existing graph sign convention to
canonical values, masks values whose known flag is false, and retains unmapped
metrics without inventing a unit or device identity. Legacy GPX reports NO_ROUTE.
Unclosed legacy recordings require explicit inactive ownership; the default
unknown authority refuses them. No end timestamp is fabricated.

The Android file owner streams UTF-8 into an app-private temporary file, caps a
file at 64 MiB, flushes it, verifies source identity again, atomically renames it,
and returns the actual byte count and SHA-256. Stream failure and cancellation
remove its own temporary file. A separate test reproduces prompt cancellation
at the return from the IO dispatcher, after publication; that artifact is also
removed before cancellation escapes.

The disposable cache reserves space before writing: at most 32 finished files,
256 MiB, and 24 hours of age. Cleanup runs on history cache access/startup and
before export, rather than through a background expiry timer. Only owned regular
files are removed; unrelated files and directories are preserved.

Preview publication, export publication and durable derived-file recovery share
one mutex in Android DI. A ride deletion requests the purge in the same database
transaction for both legacy and V2 storage. Recovery acknowledges its generation
only after both preview and export namespaces are cleared. Post-commit deletion
already has a bounded cleanup acknowledgement; pending cleanup survives restart.

`FileProvider` exposes only `history-exports-v1/` for exports. Sending uses a
content URI, ClipData and a temporary read grant, without a write or persistable
grant. File name, size, MIME and checksum are checked again before external
access. Saving copies through ACTION_CREATE_DOCUMENT. Neither action changes the
original recording. Copies the user saves outside the app are independent of
the app's disposable cache and are not removed by ride deletion.

## Evidence gathered

- h587: all 4519 unit tests in 471 fresh XML reports passed, zero failures,
  errors, skips or stale reports. Actual database migration verification and
  debug assembly ran with `--rerun-tasks --no-build-cache --offline`, all 55 tasks
  executed in 92 seconds. Newer cancellation/cache changes have their focused
  RED/GREEN evidence below and require the subsequent full run.
- Real SQLite fixtures cover page sizes 1/2/127, duplicate ticks, explicit
  unknowns, original legacy timestamps, source identity changes/deletion,
  incomplete summary buckets, and 21,005 actual retained samples including the
  tail beyond the graph's 20,000-row limit.
- Emulator API 34, installed h587 debug APK: a previously stored legacy ride
  exported to a 379,582-byte JSON file with 832 numeric records. The Android
  document picker saved it in Downloads. The external file matched the private
  export byte for byte; parsed schemaVersion was 1, counts were 832 samples and
  zero GPS/events/summaries/unmapped records. SHA-256:
  `8ea5f58e9623105c017b442a3d5fdcfe6b4d5e0297cc587103f80f8ff7e67d04`.
- The actual Android chooser displayed that file. `dumpsys activity permissions`
  showed its exact content URI granted to `com.android.intentresolver` with
  mode `0x1`, no write/global/persistable/persisted grant. The chooser was canceled.
  This proves chooser delivery, not another third-party application's read.
- A quiescent SQLite snapshot after export/save/chooser cancellation had the
  same complete legacy table hashes as the earlier deletion acceptance: all
  16 RideRow records and 190,192 RidePointRow records were unchanged.
- An explicitly reseeded debug-only V2 fixture exported native GPX: seven actual
  saved fixes and seven track segments. Its ten-second sampling gaps exceed the
  five-second gap policy; no coordinates or connecting fixes were fabricated.
- Native RU, font scale 2.0, landscape 2220×1080: scrolled from the complete
  heading/range/privacy message to all three format buttons and Close. Native EN
  portrait showed the complete ready/share/save state. Restored readback:
  font 1.0, rotation 0, accelerometer rotation 1, app locales `[]`.
- h588 RED: six real-filesystem tests, one failure specifically because a
  canceled IO-return left a finished artifact. h589 GREEN: all six passed.
- h590 RED: two bounded-cache contracts failed at the unimplemented operations.
  h591 GREEN: all ten file-owner/cache/derived-recovery tests passed.
- h592 final: 4522 tests in 472 fresh XML reports, zero failures/errors/skips or
  stale reports. Migration verification and debug assembly repeated with all 55
  tasks executed in 88 seconds. Installed APK SHA-256:
  `2F944D4740B664266E98377250DE21F8F54323165E38DD490A643723D8CD9CA8`.
  All 199 history string keys match uniquely across EN/RU; the original GPL
  license SHA-256 remains unchanged.
- On that installed build, explicit confirmation deleted only the debug V2 ride.
  Both existing private exports (legacy JSON and V2 GPX) were removed by the
  shared disposable-cache purge; the durable generation was acknowledged. The
  saved Downloads copy remained byte-identical. A new quiescent database snapshot
  again proved that all 16 legacy rides and 190,192 legacy points were unchanged.

## Remaining gates

Task 7 is not complete. Remaining verification includes a separate recipient
reading the temporary grant, final installed-build checks, malformed/older V2
summary timing policy, and integration coverage for source changes while
exporting through the screen owner. The selected range is pinned at opening the
sheet. Native system actions are not represented as fake framework unit tests.

The production root and replacement recording runtime are still not activated.
Task 8 must retire the old writer safely, bind actual legacy recording ownership,
connect the new experience to the retained map host, complete release/provenance
checks, and produce the signed release. Physical BLE/GPS recording acceptance
requires the rider's hardware. None of the evidence above claims those gates.
