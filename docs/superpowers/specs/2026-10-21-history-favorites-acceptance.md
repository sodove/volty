# Recorded history favorites and trim acceptance

This milestone adds persistent named references to complete recordings or selected
sections. It does not complete Task 6 media, export, production recorder activation,
root navigation/rotation retention, physical-device acceptance or the release audit.

## Recording and storage contract

- Each reference pins storage kind (legacy/v2), ride ID, vehicle ID and original
  clock tick. Finishing an active recording does not change its identity.
- Source validation, range intersection and write happen in one SQLite transaction.
  Closed v2 uses its canonical elapsed axis. Active v2 uses its last persisted
  elapsed tick, never the favorite's creation clock. Unclosed legacy reuses the
  recorded timestamp bounds, including wall rollback; empty sections are rejected.
- Names may repeat; IDs remain distinct. Names are trimmed and limited to 120
  characters. Catalog reads use 20-row keyset pages, not an unbounded database load.
- Revision checks reject stale edits/deletes; an edit cannot recreate a deleted
  reference. An exact create retry is idempotent. Rename/trim/delete leave the
  recording intact. Existing recorder deletion and legacy replacement reject
  referenced recordings; old automatic pruning skips them.
- Favorite opening and retry retain the original source qualification. Replaced
  or unavailable recordings cannot silently become a different vehicle's trace.
- Pending saves capture selection and window intent. Their durable result can
  appear in the list after navigation, but cannot undo a newer ride/window choice.
- Migration 19→20 adds the favorite table/indexes without rewriting existing
  recording rows; the real committed 19.db is exercised by a preservation test.

## Verification

Final run h517: 4,399 tests in 455 fresh XML reports, zero failures/errors/skips;
results were cleaned, every XML postdates the recorded start, exact totals asserted.
All 55 Gradle tasks executed in 98s, including actual migration verification and
debug assembly. Source was frozen throughout the build and subsequent native checks.

APK SHA-256:
`22FFA432A3DC5D647F986300F440445D3FEAA49730ABBD8455E04EA1F71E9A2B`.

Earlier RED runs demonstrated missing persistence, request ownership, source-safe
retry, deletion state, recording pin protection and current-save activation. The
final suite adds 25 tests over the prior 4,374-test baseline. These are component,
policy and real SQLite tests; they do not pretend to exercise Compose appearance.

## Native checks

API 34 emulator, final APK installed over the existing database. Explicit
debug-only `favorites=true` seeds a named 60s recording through the actual store;
ordinary stored/synthetic probes do not write recordings or start BLE/location.
The favorite owner, SQL repository and composed home are the same production code.

- New favorite saves, closes the editor and changes the star to Edit favorite.
- Existing 34s favorite survives process restart and opens the displayed-range
  summary while keeping the full recording extent available for editing.
- Rename to Renamed, reset to full recording, then drag both endpoints. Final
  home shows Renamed and 08:53:34.514–08:54:07.682. Catalog shows 33s, while the
  original recording remains 60s. Screenshot: h517-favorite-edited.png.
- Native testing found the first trim thumb inside Android's back-gesture zone.
  A horizontal drag visibly invoked system Back and dismissed the sheet before
  saving. Retraction: the initial observation was not a failed SQL save.
  Final slider respects horizontal systemGestures insets; its full-range thumb
  moved from x71 to x153 on this emulator. Repeating both thumb drags leaves the
  editor open and saves the changed range.
- Delete confirmation names the reference and explains recording retention.
  Cancel keeps Renamed; confirmation removes it while the independent 60s Ride
  favorite and underlying recording remain. Screenshot: h517-favorite-deleted.png.
- Russian/font 200%: whole history/favorites chips wrap onto separate lines rather
  than splitting a word. Editor scroll reaches Save; endpoint seconds and duration
  remain visible. Screenshots: h516-favorites-font2.png and h516-trim-font2.png;
  h517 changes only slider gesture insets after that native check.
- Settings restored: font 1.0, portrait/user_rotation 0, automatic rotation enabled,
  app locales []. The probe reconstructs its owner on recreation, so these checks
  do not establish production root retention across rotation/process death.

Screenshots and run logs are retained in the ignored plan workspace. GPL adaptation
origins are recorded beside the ported files and in the third-party evidence file.
No new SDK dependency or BLE write was introduced.
