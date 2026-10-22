# History recording: decoder output and gateway correction

The rider's review exposed a real regression: live decoders worked while a
separate history source allowlist silently rejected their entire ride, including
time and GPS. This correction follows the amendment in
`../plans/2026-10-22-history-common-decoder-output.md`.

## Changed behavior

- A real link without numeric observations can still own a ride and location
  demand. The recording panel explicitly reports limited telemetry in EN/RU.
  Ordinary manual Stop/Start and selected physical alias suppression remain intact.
- Kelly, FarDriver, KingSong, Ninebot, legacy Ninebot and Inmotion publish fresh
  fields through a shared mapper at the accepted decode boundary. The returned
  snapshot remains the live snapshot. Cached fields omitted by the message are
  not republished; equal values in another accepted message are observed again.
- Cell fragments retain wire indices and typed invalid values. A fragment cannot
  certify whole-pack extrema. KingSong's board temperature is controller data,
  not a virtual rail's pack thermistor.
- Hosted VESC BMS replies now record their received numeric fields using the same
  decoded battery snapshot as live telemetry. Their timestamps mean app receipt,
  not an unavailable internal sensor acquisition timestamp. The no-data sentinel
  stays unavailable, removed cells are retired, malformed/unsolicited replies
  remain excluded, and source IDs explicitly version the changed policy.
- A local head unit without a configured CAN ID no longer rejects its hosted BMS
  or separately forwarded controller. Existing per-reply controller identity
  checks and the serial request/late-reply guard are unchanged.
- Real guest connections use the dashboard's existing transient address-based
  identity for history. No saved vehicle profile is created. Demo stays excluded.

## Evidence

The focused RED runs reproduced missing observations, lost GPS-only recording,
gateway numeric erasure, local-head-unit plan rejection, real adapter source-role
incompatibility, and both guest rejection paths before their respective fixes.
Regression checks exercise accepted raw frames, repeated equal replies, incomplete
and unrelated pages, exact cell indices, real recorder adaptation, source topology,
and manual controls. Some additional review regressions were written after their
fixes; they are not claimed as test-first evidence.

- h658: complete unit suite, actual database migration verification and debug APK
  assembly with all 55 tasks executed; 4585 tests / 477 fresh XML, no failures,
  errors, skips or stale results.
- h661, after guest changes and two gateway identity regressions: complete unit
  suite and debug assembly; 4589 tests / 477 fresh XML, no failures, errors, skips
  or stale results. Results were deleted by `cleanTestDebugUnitTest` first; no
  build cache was used. Gradle was serial, with four JVM processors.
- Read-only focused Luna reviews covered shared mapping/source identity and the
  gateway reply correction. Primary review also found and repaired cell-index,
  aggregate-temperature and adapter-role issues missed by decoder-only checks.

Firmware checked locally: nyxdash `components/vesc_express/src/bms.c` serializes
`m_values` for `COMM_BMS_GET_VALUES`; `ant_bms.c` updates it from accepted ANT
status and resets it plus `can_id=-1` on disconnect. The existing ANT bridge sign
and capacity projection is preserved. Direct stock VESC BMS BLE is not implemented
by the native factory and has not been certified by this correction.

Retraction: a proposed test incorrectly equated the reply's BMS producer CAN ID
with the `FORWARD_CAN` hosting-controller target. Stock `bldc/bms.c` shows these
can differ. No such equality guard was introduced; regression tests retain this
distinction. The CAN ID is not a transaction ID.

## Shared topology and independent native intake

Retraction of the earlier remaining-work list: native decoder construction and
history now use one `DecoderProtocolPlan`. Source roles/counts are declared beside
the actual decoder factory, including hosted-only BMS links and numbered virtual
gateway packs. Sizing no longer constructs throwaway protocol instances. Physical
source attribution and single selected alias admission remain checked separately.

Managed native observations enter the existing bounded history writer directly
inside the observation/admission fences. They no longer compete with dashboard
snapshots in the 64-element channel. Stop, replacement and close await admitted
writer calls, and bounded-writer rejection is latched before admission release;
a lossy ride cannot be marked completed. Compatibility queue tests remain for
unmanaged callers, and native tests now assert actual numeric writer batches.

Computed gateway readings use only identity-qualified controller decode snapshots
and their receipt ages. Equal fresh replies remain observations; cached/live values
cannot be substituted after an unattributed reply. A silent BMS timeout has no
notification receipt: it retains a bounded slot intent, which is folded in the
next round's final controller VALUES notification scope. The live fallback cadence
and hosted-BMS precedence are preserved.

- h663 reproduced missing direct delivery and computed-gateway output; factory
  topology tests passed. h664 exposed marker/count assumptions in new tests.
- h666: 4608 tests, four failures solely in old native queue-ownership assertions;
  those tests now fence an actual in-flight synchronous offer.
- h667: actual `SessionTelemetryCapture` regression reproduced lost silent-BMS
  fallback, with four ownership tests passing. h668 exposed a test driver ending
  before the next notification after the qualifying timeout; the driver now
  asserts both issued serial rounds before checking receipt-scoped output.
- h669: 4609 tests / 480 fresh XML, no failures/errors/skips/stale results, followed
  by successful debug assembly (65 seconds). No build cache; serial Gradle,
  four JVM processors. h670: actual database migration verification executed with
  `--rerun-tasks` (18 seconds), not an up-to-date result.
- Focused read-only Luna reviews found no further transport-fence or topology
  identity issue. Primary review found the controller snapshot overwrite and
  missing native receipt scope, and added the integration regression above.

## Native Bumble acceptance

h671 exercised the rebuilt debug app through its real saved
`Bumble_history_wheel` vehicle and passive Leaperkim replay, not a fake recording
repository. Android subscribed to FFE1, decoded telemetry, and automatically began
one ride. Stopping/restarting the Bumble peripheral while Volty was in the
background produced a 45,711 ms BLE gap and an automatic reconnect into the same
ride. Inside that gap the database contains zero telemetry rows and 30 GPS fixes.
During a conservatively bounded 38,260 ms interval with Android reporting
`Asleep`, it contains 4,051 telemetry rows and 27 GPS fixes.

Manual Stop finished the ride as `COMPLETED`: four distinct sources, 11,701 sample
rows, 102 GPS fixes, 288 buckets and 188 batch receipts. There is no OVERFLOW
marker. The only markers are CONNECTED, initial GPS_GAP, BLE_GAP, reconnected
CONNECTED and MANUAL_STOP. Physical BMS branches do not acquire fabricated rail
voltage/power from the controller.

A SQLite `.backup` after durable Stop and a later quiescent snapshot after another
peripheral disconnect/reconnect compare exactly across all nine history tables.
No additional ride appeared and the completed ride did not change. All 18 legacy
rides / 191,504 legacy points and all seven-table rows of the four earlier V2 rides
also compare exactly with the pre-install baseline. Both snapshots pass SQLite
integrity checking. Ignored evidence: `h671-proof.json`, the fresh native UI dumps,
replay logs, atomic `h671-after-stop.db` and quiescent `h671-final.db`.

The app was force-stopped after verification, all owned Bumble actors stopped,
and ADB returned to shell mode. This confirms the virtual replay scenario;
physical vehicles and phone vendor behavior remain separate acceptance work.

## Still open

This is not whole-feature acceptance. The earlier Bumble run's nine dropped
observations and INTERRUPTED ride remain an observed failure of the old transport;
the new implementation passed the canonical native scenario above. These
paths have not received physical-device acceptance, and no signed APK/source
distribution has been refreshed for this correction. Export, distribution and the
remaining Ride History plan are not declared complete.
