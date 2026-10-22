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

## Still open

This is not whole-feature acceptance. Source topology is still described separately
from decoder construction; computed gateway fallback recording needs completion.
Native history still shares the dashboard's 64-element funnel: the earlier Bumble
run measured nine dropped observations and an INTERRUPTED ride. That transport
must be separated and the canonical native checks repeated. These new paths have
not received physical-device acceptance, and no signed APK/source distribution
has been refreshed for this correction. Export, distribution and the remaining
Ride History plan are not declared complete.
