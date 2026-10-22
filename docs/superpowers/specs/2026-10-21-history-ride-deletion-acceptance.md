# Whole-recording deletion checkpoint

This extends Task6 of the approved Ride History screen plan. It does not enable
the new recorder or production root route and is not a release acceptance claim.

## Contract

An explicit catalog trash action prepares a confirmation for one exact closed
recording, including its displayed favorite and private attachment counts.
Confirmation revalidates recording identity, completion and counts in the same
SQLite transaction. Changed children require a new confirmation; renaming or
trimming an existing favorite does not expand whole-recording deletion scope.
Open recordings cannot be deleted. A same-ID recording in the other storage
format is isolated by full source identity.

The transaction retires matching media, removes favorites and explicitly deletes
all recording payload tables, including with foreign keys disabled. A failed SQL
delete rolls the transaction back. Private file cleanup follows the commit with
a five-second acknowledgement deadline. Failure or timeout reports pending
cleanup; it cannot turn an already committed deletion into a claim of rollback.
Gallery originals are outside this deletion scope.

Preview PNGs contain private drawn routes. A new singleton purge journal in
`21.sqm` (version21 to22) advances in the same V2 deletion transaction. Cleanup
removes only owned PNG/temp names, then acknowledges the exact generation.
A newer request during clearing remains pending. Startup and subsequent native
preview loads retry pending work. Public map resources and foreign cache files
are preserved; other history thumbnails can be regenerated.

The presentation owner retires selected detail/route/media and cached previews,
refreshes catalog/favorites and preserves a newer selection. Late reads cannot
restore the deleted selected source, including during a same-source reload.

## Verification recorded so far

- SQL tests inspect actual payload rows with foreign keys off, use an aborting
  deletion trigger, and cover stale counts, active recordings, source reuse,
  both storage kinds, idempotent replay, cleanup failure/deadline and durable
  pending cleanup without an optional file cleaner.
- Purge tests use real SQLite transactions, a real version21 snapshot, reopened
  file-backed databases and real filesystem caches. Failed clears keep requests;
  a newer generation cannot be acknowledged by an older clear.
- Component tests inspect issued operations and late noncancellable responses.
  Native Compose layout is checked on the emulator, not through mocked UI tests.
- h557 and final h558: 4483 tests in 465 fresh XML reports, zero
  failures/errors/skips/stale reports; all 55 Gradle tasks executed, migration
  verification and debug APK built. Final h558 took 93 seconds. APK SHA-256:
  `7DB4038C4AC95A4CF6E4C3D17CD1B552DF764FF76D9B8BBEC5BFCE83727F3162`.

An actual 200% font landscape check caught a fixed title consuming the impact
body. The title now belongs to the scrollable body, with action buttons outside.
The final APK was installed on the API34 emulator. Russian 200% font landscape
can scroll through the impact counts and gallery-preservation text while both
buttons stay accessible. English normal-font portrait displays the full impact.

Native checks used the explicitly created debug recording, not a real ride:

- Before: one closed recording, seven samples, seven GPS fixes, one source,
  one favorite, one READY private attachment and 18 owned preview PNGs.
- Cancel: the recording, favorite, attachment, private file and previews remain.
- Confirm: the recording and its payload, favorite, media rows, private file and
  all owned preview PNGs disappear; the durable purge generation is acknowledged.
- Both comparisons used quiescent, integrity-checked SQLite copies. All 16 legacy
  records and 190192 legacy points retain their row hashes; metadata of all 62
  gallery images retains its hash. The fixture was not reseeded before checking.
- Font scale1, portrait rotation0, automatic rotation enabled and app locale[]
  were restored and read back. No PC power action was performed.

An earlier live database copy was inconsistent because it was copied during
writes; this was a snapshot failure, not evidence of app database corruption.
An initial gallery audit command printed usage because the sort expression was
split by adb; the final audit requires actual rows and uses a valid single-field
sort. Tuple/list normalization in its comparison script was also corrected.
These failed audit attempts are not counted as acceptance evidence.

## Remaining whole-plan work

Streaming GPX/CSV/JSON export, production navigation/recorder cutover, complete
visual and physical recording acceptance, and signed distribution/source audit
remain open. This checkpoint makes no claim about physical BLE or voice audio.
