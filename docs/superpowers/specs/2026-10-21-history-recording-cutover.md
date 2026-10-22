# Native recording cutover and catalog controls

Production Koin now selects the five-argument Kable constructor. Every connection
seals its recording ingress only after the app-owned HistoryRecordingRuntime has
prepared successfully. The legacy recorder is never started and is not a failure
fallback. Its automatic prune therefore never runs. Legacy storage and readers
remain intact. Demo and guest connections intentionally do not produce V2 rides.

The catalog observes the runtime without preparing a writer or acquiring GPS.
Its current-recording panel exposes Start/Stop and GPS availability separately.
Actions retain the exact displayed recording capability; a delayed Stop cannot
target a replacement connection. Leaving history cancels its UI owner, not the
app-owned recorder, location capture or an already accepted durable command.
Successful actions refresh the catalog. Archived detail and the ride chooser do
not display the live recording panel. Saved/current labels are matched by exact
vehicle ID, never assigned to an unrelated historic ride.

Only an explicit GPS action requests Android coarse/fine location or opens
provider/application settings. Returning retries existing recording demand; it
does not allocate a writer or imply a GPS fix was stored. Storage failures and
unavailable controls have separate localized messages. Panel content scrolls
within at most a third of the catalog height, preserving access to the ride list.

Production legacy export/deletion uses the proven inactive legacy-recorder
authority. An unclosed old row remains unclosed: no invented end timestamp or
new sample is written to make it exportable/deletable. Default/unknown activity
still protects unclosed rows; explicit activity protects even inconsistent closed
metadata. Deletion rechecks authority during confirmation in its transaction.

## Evidence and limits

h619 parent integration: one test failed on the inert parent stub, then h620b
passed 59 tests in three fresh XML reports (20 runtime, 36 parent, three recording
UI ownership tests), with zero failures/errors/skips/stale files. Debug assembly
succeeded. h622 added three real-SQLite authority tests; all three failed against
the previous deletion guard before implementation. h623 then passed all 4550
tests in 475 fresh XML reports, with zero failures/errors/skips/stale files.
Migration verification and debug assembly passed with all 55 tasks executed,
offline, without build-cache reuse, with --rerun-tasks, in 144 seconds.

h621 installed the production-route debug APK and traversed Welcome → Demo →
Graph → History. The actual catalog displayed the recording panel and vehicle
names. Quiescent SQLite snapshots before/after had identical complete row sets:
18 legacy rides, 191504 legacy points, zero V2 rides/samples/GPS. Integrity was
OK and schema version 22. This proves no legacy or fabricated demo recording
appeared during that scenario, not that physical BLE recording has been tested.

The first debug startup was slow and UIAutomator intermittently returned a null
root; screenshots and later fresh observations confirmed the route. No startup
performance or physical hardware acceptance is claimed. The explicit debug-only
recordingPanel fixture renders current-state actions without BLE, GPS or writes;
any checks using it are UI checks, not recorder acceptance.

h624 exercised actual Stop → manually stopped/Start → active/Stop UI transitions
through the parent component. Russian font scale 2.0 in actual 2220×1080 landscape
kept the list accessible and both Stop and subsequent Start reachable by scrolling
the bounded panel. A focused read-only Luna review found no actionable issues in
the panel, permission launcher and screen integration. System permission/provider
checks and signed-release checks are recorded separately after execution.

Final native controls/permissions/adaptive checks, signed release/source audit,
remaining export gates and physical recording acceptance remain open.
