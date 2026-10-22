# Bumble wheel recording acceptance

The existing `tools/leaperkim-emulator/emulate_leaperkim.py` peripheral replays
129 captured Leaperkim notifications through Android Netsim. This exercise uses
a saved virtual wheel, the production MainActivity route, real Android BLE
callbacks and the app-owned recorder. It uses no physical radio or wheel and
issues no wheel configuration writes.

## Defect and correction

The previous candidate decoded the live wheel dashboard but explicitly rejected
VETERAN in native history source planning. VeteranProtocol also emitted no
telemetry observations. Enabling the enum alone would therefore record no facts.

The decoder now emits observations only after successful frame decode. Controller
and estimated controller rail have distinct CONTROLLER and DERIVED_BATTERY source
identities; both physical Smart-BMS branches remain separate BATTERY contributors.
Smart-BMS observations contain only fields earned by the current page. Retained
current, cells, reported charge and assembled cross-page pack voltage/power are
not given a new acquisition time. A reported zero charge remains a valid physical
fact; the separate rail uses the current frame's voltage curve.

An initial helper subtracted a CRC trailer twice. That claim is retracted:
parseAvailable already strips it. The regression test caught 13 emitted cells
instead of the expected 15; only the new observation helper needed correction.

The native run also exposed per-notification SQLite transactions and an incorrect
storage-error message during a normal Stop intake fence. The writer now commits
bounded prefixes of up to 128 raw events together (an already admitted larger
single packet remains atomic). Capacity limits and overflow markers are retained.
A prepared commit pins its exact receipt, payload, projection and prefix length
until acknowledgement, including retries after a lost acknowledgement while new
packets arrive. Storage errors require an explicit writer failure or closed writer.

## Verification

h633: 23 decoder/source-plan tests, four genuine behavioral failures before the
producer implementation. h640: batching and stop-status assertions failed against
the previous implementation. One supplemental test initially lacked a complete
cached physical pack; its fixture was corrected rather than changing production
behavior to satisfy an unreachable precondition.

h641: 62 targeted tests in four fresh XML reports passed. h642: all 4558 tests in
475 fresh XML reports passed with zero failures, errors, skips or stale reports;
actual database migration verification and debug assembly passed. All 55 Gradle
tasks executed with `--rerun-tasks`, offline and without build-cache reuse.
Focused read-only Luna reviews of producer freshness/source identity and writer
prefix/retry semantics found no actionable issues.

The first native replay persisted 18494 telemetry detail rows and 236 GPS fixes.
3619 detail rows and 30 fixes carry original acquisition ticks within an Android
screen-off interval (Dozing to Asleep). Quiescent snapshots preserved all 18 legacy
rides and 191504 legacy points exactly. A deliberate peripheral drop and restart
produced a BLE gap and background reconnection in the same active ride.

An early observation called the Stop message a durable failure. This is retracted:
the first run was terminated before acknowledgement; the next old-writer run
ultimately persisted COMPLETED. Its slow pending UI did not prove a failed finish.
The message itself was independently reproduced by the runtime regression test.

On the new batched build, the real Stop action reached the manually-stopped UI
with Start available and the ride marked Completed in both catalog and SQLite.
That ride retained 2333 detail rows and 46 GPS fixes, with 24 committed append
receipts. No timing or physical throughput guarantee is inferred from this AVD.
Manual Start then created a new ride ID rather than reopening the completed ride.
Final reconnection/screen-off and snapshot evidence is recorded below when complete.

## Limits

This qualifies a captured Leaperkim replay, not real hardware or RF behavior.
VESC_BMS, Fardriver, Kelly, Ninebot variants, KingSong and Inmotion still need their
own fresh-producer qualification; they must not be enabled by changing the source
planner enum alone. Broader UI performance, remaining export cases, distribution
audit and physical vehicle acceptance remain open.

## Remaining failure found in the second replay

After a real BLE drop/reconnect and screen-off period, the second batched ride
reached the manually-stopped UI but was labelled Interrupted. Direct read-only
inspection of its retained runtime diagnostic through the Android debugger found
`failureType=IngressLoss` and
`HistoryTelemetryIngressDiagnostics(droppedObservations=9, setupFailures=0,
lastFailure=FULL)`. Thus this is a real full BLE processing funnel, not a setup
failure or proof of a completed lossless run. The 64-element dashboard sample
funnel still carries history observations; removing that coupling needs its own
ownership and bounded-delivery regression coverage. Raising the capacity alone
would not address it. The debugger was detached and all threads resumed.

The emulator and Netsim processes subsequently disappeared; the cause is not
established. ADB reported no devices and a process inventory confirmed neither
process remained. The pending snapshot command was cancelled, and the same AVD
was cold-started with its existing userdata, without loading an old snapshot or
wiping data. Final canonical snapshot validation of this second run remains open.

The user's subsequent review also identified an architectural defect: unsupported
telemetry producers currently block the entire ride, including GPS, without an
explicit explanation. This restriction must be removed. `TelemetryObservation`
already supplies a common fresh-field model; producer output and recording source
identity must be shared rather than maintaining a second per-history allowlist.
A GPS/time-only fallback is an interim safety net, not completion of full protocol
recording support.
