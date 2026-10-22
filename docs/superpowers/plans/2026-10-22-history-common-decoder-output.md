# Common decoder output and unconditional real-ride recording

User correction on 2026-10-22: a protocol whose live telemetry works must not need
a second history-specific implementation/allowlist before a ride can be recorded.
A missing numeric capability must never silently suppress time/GPS recording.
This amends the earlier cutover's unsupported-producer restriction. GPS-only is an
interim fallback, not completion of supported device telemetry.

## Required behavior

- A real connected device can start/stop a ride independently of numeric telemetry
  coverage. Retain GPS through BLE gaps when permission/provider allow it.
- Make partial/unavailable telemetry and recording failures visible. Do not promise
  GPS capture without location permission or confuse a normal Stop fence with IO failure.
- Use one decoder update model for live state and history. `TelemetryObservation`
  already represents fresh fields, typed unknowns and source roles; remove the
  separate history producer allowlist and duplicate per-protocol field conversion.
- Freshness comes from the successfully decoded message/update, never value inequality
  or `hasPower`/other retained validity flags. An unchanged received value is fresh;
  an omitted cached value is not. Cells/page fragments keep per-cell freshness.
- Register source addresses/roles from actual decoder configuration. Keep physical,
  derived, assembly and network-reporter scopes distinct and preserve calibration
  identities. Wire parsing, commands and the Begode FFE1 write prohibition stay intact.
- History must not compete with dashboard snapshots for the 64-element UI sample
  funnel. Its own bounded writer remains the durable owner; admission fences must
  cover direct delivery, Stop, reconnect, replacement and lost acknowledgements.
- Real guest connections need an honest device/session identity, not a fabricated
  saved profile. Demo remains an explicit simulated source, not a fake physical ride.

## Execution

1. Add RED tests for saved unsupported protocols, mixed known/unknown links, GPS
   demand, explicit limits and normal Stop/Start. Keep numerical attribution checks.
2. Decouple ride lifetime from numeric source availability and show the limitation
   in both resource languages. Do not swallow malformed setup as healthy telemetry.
3. Replace the history-specific source whitelist with common decoder source metadata
   and a shared fresh-update mapper. Migrate every working protocol; no snapshot
   polling or changed-value heuristic as a shortcut. Add unchanged-value and cached
   absent-field tests per message shape, not just fully complete fixtures.
4. Decouple native history delivery from dashboard buffering using the existing
   observation/admission gates and bounded writer. Port queue-coupled tests to the
   actual new ownership boundaries; preserve genuine loss detection/barriers.
5. Repeat canonical SQLite and Bumble native checks, source/role identity, background
   GPS, reconnect, manual stop persistence and old-ride immutability. Run full suite
   and actual migration verification before commits. Refresh signed artifacts only
   after their implementation has passed the relevant checks.

Overall Ride History export, distribution, UI performance and physical acceptance
requirements remain open; this amendment does not replace them.

## Gateway clarification from the rider

VESC BMS behind the head unit is an existing working decoder, distinct from the
unimplemented direct stock-BMS BLE factory. The existing blanket cache-absence
recording policy was excessive and is retracted. A complete accepted
COMM_BMS_GET_VALUES response records the reported snapshot at **app receipt time**;
it does not assert an unavailable sensor acquisition timestamp. This is the same
observation-time convention used for other accepted decoder messages. An identical
new response is another observation; silence never republishes the snapshot.

Checked local nyxdash firmware: `bms.c:bms_process_cmd` serializes `m_values` for
this opcode; `ant_bms.c` fills it from accepted ANT status and resets it plus
`can_id=-1` on disconnect. Keep the explicit no-data sentinel unknown and retire
removed cell indices. Preserve the existing measured ANT bridge sign/capacity
projection; this does not certify an untested stock BMS sign/counter convention.
Use a new source policy identity so old forced-unknown series are not continued
silently. A local head-unit target without a CAN ID must not reject the entire
plan or its separately attributed hosted BMS and forwarded controller.
