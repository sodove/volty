# Ride History source freshness audit

The recording pipeline cannot treat a new merged `BmsData` instance as proof that
all its fields were measured again. Source observations are separate from dashboard
snapshots and contain only measured or explicitly unavailable fields from that decode.
No BLE command, polling cadence or Begode FFE1 write is added.

## Local findings

- JBD basic-info and cell replies each rebuild a dashboard object with the other
  group's cached fields. The new observer publishes every complete decoded reply
  separately, including multiple replies in one BLE notification.
- Daly replies update different accumulator groups. Cell/temperature fragments
  immediately publish mixed-age snapshots; a fault reply can also restamp cached
  scalar fields. Daly now emits separate numeric groups; multi-fragment summary
  certification and production consumer integration remain pending.
- ANT emits a whole status snapshot, but missing payload guards can leave scalar
  defaults marked available. History qualification must use actual field coverage,
  not those defaults. ANT now emits qualified per-status observations; production
  consumer integration is still pending.
- Begode smart branches now emit fresh current/cell groups; branch aggregates and
  assembly sensor attribution remain open. JK and the VESC gateway still need qualified observations.
  Begode controller observations now separate frame families. Plain VESC
  now emits local-field observations; SETUP aggregate attribution remains pending.
  Gateway overlays and derived contributors require explicit freshness evidence.

The generic observation vocabulary carries physical contributor kind/local index,
canonical values, typed absence and physical cell index. The history adapter binds
these to an attributed source; vehicle aggregates cannot duplicate contributor rows.
Multiple frames with one receipt timestamp can earn peaks and sample counts, but
cannot prove distinct measurement intervals or energy between them.

## JBD qualification and retraction

Checked the reference client's [`jbd.py` at 76317567a51fcfbf9df9e65315fcb2d5af125237](https://github.com/fl4p/batmon-ha/blob/76317567a51fcfbf9df9e65315fcb2d5af125237/bmslib/models/jbd.py)
on 2026-10-14. Its response validation rejects nonzero status; cell-voltage reads
preserve raw reported millivolts, including zero. The NTC declaration must fit the
payload; checksum bytes are not temperature measurements.

Retraction: the first observation prototype reused the dashboard's 1..5000mV
filter and treated zero as invalid. That would hide an earned zero reading and
could conceal a cell fault. New history observations retain unsigned raw cell
readings and their physical positions. The existing dashboard list filter remains
unchanged; this audit does not claim that its cell presentation is repaired.

Failed responses publish typed unavailability for the affected fields and retain
the prior live snapshot. Smaller cell layouts explicitly retire previously known
indices. Reset clears the decoder's cell-layout history; cross-session layout
retirement must also be handled by the attributed runtime source state.

JBD observer exceptions are contained and counted without logging payloads.
The new observer is not connected to production DI or the BLE consumer yet.

Retraction: an even JBD cell reply proves its returned pairs, not completeness of
the physical pack. Basic-info payload offset 21 declares the expected cell count.
Whole-pack summaries now require that declaration and exact, even payload coverage.
A shorter reply invalidates absent declared indices on every occurrence; only a new
successful basic-info declaration can certify a smaller layout. Zero/missing count
and reset remove that evidence without suppressing raw cell readings.

## ANT qualification

Checked [`ant.py` at the same pinned batmon-ha revision](https://github.com/fl4p/batmon-ha/blob/76317567a51fcfbf9df9e65315fcb2d5af125237/bmslib/models/ant.py)
and [ESPHome's ANT decoder](https://github.com/syssi/esphome-ant-bms/blob/main/components/ant_bms_ble/ant_bms_ble.cpp)
on 2026-10-14. Declared cell and NTC counts determine later field offsets; NTC/MOS
temperature words are signed, and -40 is an explicit absent-sensor sentinel.
The local `nyxdash/firmware/components/vesc_express/src/ant_bms.c` was also read:
it says its layout matches the Kotlin parser, so it is corroboration rather than
independent proof. Its unsigned temperature handling is not copied.

Only CRC-valid status replies accepted by the existing decoder emit observations.
Coverage ends before CRC/trailer, even when another frame follows in the accumulator.
Raw cell positions and zero readings survive dashboard filtering. Missing declared
pairs produce partial values; pack summaries require all declared pairs. A new
smaller declaration retires older series. Reset clears that decoder history.
Voltage, current and SOC each require their own payload bytes; calculated power
requires both voltage and current. Current retains the domain's charge-positive
sign. Hottest battery temperature follows the existing dashboard's NTC+MOS scope,
excluding balancer: absent sensors are excluded, but truncated or invalid declared
measurements make the maximum partial. No command, cadence or live dashboard
decoding behavior was changed. Exceptions remain contained by the common observer.

These are decoder tests, not field verification and not production activation.

## Plain VESC: local fields versus SETUP totals

Read the rider's local VESC firmware `bldc/comm/commands.c` (GET_VALUES and
GET_VALUES_SETUP), `bldc/motor/mc_interface.c` (`mc_interface_get_setup_values`),
and reference client's `vesc_tool-master/commands.cpp` on 2026-10-14.
Read-file SHA-256 receipts, respectively:
`7647827A16CF9163699F40C82C4D96B0BA38D0B89652E9831727F983AE70EA80`,
`8D8FBE43F75A1572030A4B55221E28F318DE7E201A7A586DD44C7C623261FBA9`,
`A8A33F0B6BC2AA48B5A2CD414FE8BA87054B99E485E1ACD8E4FC2F1B2DF7F5AA`.
Plain GET_VALUES supplies local currents; SETUP uses `current_tot` and
`current_in_tot`, including live CAN neighbors. The SETUP trailer's `num_vescs`
counts status-1 entries while input current is summed from independently aged
status-4 entries, so `num_vescs == 1` alone cannot certify local input-current scope.

Every decoded values frame now produces one physical-controller observation, retaining
intermediate peaks when several frames share a notification. Local voltage, speed,
duty, eRPM and temperatures keep decoder availability and canonical units/signs.
SETUP's summed motor/input current and derived power are explicitly partial in this
physical-controller scope. Separate attributed system-sum observations are still
required before runtime activation; this does not claim their usable data was captured.
No duplicate derived-battery observation is emitted: binding the derived source and
avoiding aggregate double-counting belongs to the accepted source runtime.

Plain GET_VALUES rotor counts have not been converted into a distance, regardless of
the old `hasDistance` default. Only SETUP's decoded metre counter earns kilometre
observations. A separate history baseline starts on the first such counter, survives
intervening plain replies, and resets with the BLE decoder session. The old dashboard
trip baseline is unchanged, including its previously possible plain-zero seeding.
Malformed/unknown frames do not restamp cached fields; observer failures do not break
motion or derived-battery updates. Polling, commands and opcode election are unchanged.

## Daly: declared fragments and current polarity

Read [`daly.py` at the pinned batmon-ha revision](https://github.com/fl4p/batmon-ha/blob/76317567a51fcfbf9df9e65315fcb2d5af125237/bmslib/models/daly.py)
and [ESPHome's UART decoder](https://github.com/esphome/esphome/blob/dev/esphome/components/daly_bms/daly_bms.cpp)
on 2026-10-14. Cell triplets and temperature groups have one-based fragment
numbers; declared counts separate actual sensors from fixed-frame padding.

Retraction: the old live decoder/test copied the discharge-positive UART current
directly into the charge-positive BmsData domain. Raw current above 30000 means
discharge. Live decoding and new observations now negate that wire convention;
power follows it. Existing legacy rows are not rewritten: their stored values do
not carry enough protocol provenance to infer a safe correction.
Cross-checked against [dbus-serialbattery's Daly implementation](https://github.com/mr-manuel/venus-os_dbus-serialbattery/blob/master/dbus-serialbattery/bms/daly.py),
which divides the offset-subtracted current by -10 for its battery convention.

Only normal board-1 responses with eight payload bytes qualify for history.
Basic replies emit their four fresh scalar metrics. Status/alarm replies emit
none of the cached numeric groups. A separate reset-cleared declaration tracks
cell/temperature counts even without an attached observer. Count changes invalidate
summaries and retire excess cell series; duplicate declarations do not restamp data.
Known cell count preserves physical fragment indices and raw zero/over-dashboard-range
values, excluding padding. Unknown count cannot prove physical presence. A declared
raw temperature zero is -40C; it is not discarded as padding.

Ruling: do not certify multi-fragment pack extrema/max-temperature from fragment
numbers alone. They identify positions, not a unique poll round; delayed parts could
combine old and new values. Individual cell facts remain fresh, while those summaries
are partial. A whole declared cell/temperature group fitting one reply can qualify.
Polling remains unchanged, notably it still does not request the 0x94 declaration.
Profile/declaration binding and multi-fragment aggregate provenance remain activation
gates. The live dashboard's fragment accumulation/header filtering is not repaired
by the observation path; the polarity correction is the live change in this checkpoint.

## Begode controller frame families

Read [WheelLog's GotwayAdapter](https://github.com/Wheellog/Wheellog.Android/blob/master/app/src/main/java/com/cooper/wheellog/utils/GotwayAdapter.java)
on 2026-10-14 and the existing Volty decoder's checked field reports. No new wire
decoder/scaling or commands are introduced: the observer projects only fields
refreshed by an accepted frame, after the existing boot-placeholder gates.

Live 0x00 publishes speed magnitude, phase motor current, ESC temperature and
calibrated input voltage. Calibration requires the existing configured/proven
series count; the 67.2V reference is not recorded as the actual rail when unknown.
Odometer 0x04 publishes only odometer and session trip; 0x07 publishes only battery
current, motor temperature and duty, preserving the decoder's evidence latches.
No unsupported eRPM zero, cached temperature, cached speed or battery duplicate is
minted. Controller-current sign remains discharge-positive, distinct from the pack
domain. Multiple frames in a notification each publish their own fresh fields.

Ruling: computed power remains partial in this prototype even when the merged
motion object contains a nonzero product. Voltage and battery current belong to
different frame families; phase current cannot substitute for battery current.
The accepted source runtime must join qualified same-session receipts under an
explicit age limit before certifying a product. Cost: power/energy recording for
this controller remains an activation gate rather than silently using stale fields.

The real ET Max stationary fixture exercises 114 controller observations (38 of
each family), interleaved with smart-BMS/cell frames. It proves existing captured
values and boundaries, not moving-wheel accuracy or phone/background behavior.
Synthetic cases cover nonzero peaks, reset/boot gates and unknown-to-earned-zero
sensor transitions. FFE1 stays read-only: handshake and polling lists remain empty.
Smart-branch and synthetic-battery source observations still require implementation.

## Begode smart branches: partial summaries are explicit

Further checkpoint: accepted 0x01 replies now emit per-branch charge-positive
current; boot-shaped section replies mark it unavailable rather than earning
their placeholder zero. A genuine zero with live section evidence remains known.
Cell pages 0x02/0x03 preserve branch identity and physical `page * 8 + offset`
positions, including pages arriving before telemetry or out of order.

Configured/proven cell topology removes slots outside the real pack. Within a
nonzero page, declared cell zero and raw reported values survive dashboard
filtering. Without topology, nonzero words establish reported positions; zeros
are ambiguous padding. Previously observed positions become unavailable when
those slots turn zero; unseen zero padding creates no series. All-zero pages
are ambiguous boot/fault data and invalidate known positions rather than minting
eight confident zero measurements. Reset clears this history independently per branch.

Ruling: this prototype does not use the merged branch's cached cell sum as a fresh
voltage, nominal 0x01 voltage as a calibrated value, or one assembly's two sensors
as a whole-branch maximum. Voltage/power/whole-branch temperature remain partial;
SOC remains unavailable because the frame has no gauge. Complete extrema are
earned only when one non-boot page contains the whole configured/proven pack.
Multi-page summaries need receipt/acquisition qualification. Cost: these aggregate
and derived values remain activation gates; assembly sensor/voltage facts still
need an attributed scope before they can be captured fully. No live decoder field,
cell filter, source cadence or BLE command changed in this checkpoint.

The ET Max fixture tests 76 battery observations (38 telemetry, 38 pages), both
branches, initial boot-current absence, later current availability, physical
indices 0..39 and recorded cell values, alongside the 114 controller observations.
This validates the stored stationary capture, not new phone/background acceptance.

## Connection receipt boundary

`SessionTelemetryCapture` optionally wraps the exact incoming-notification boundary
used by ConnectionSession. It reads an injected receipt clock once before trace
callbacks and decoding, then attaches one immutable session ID/notification ordinal/
wall-and-monotonic time to all observations that this notification completes.
Fragments acquire the completion receipt; coalesced frames cannot invent distinct
measurement times. A receipt is arrival evidence, not a hardware acquisition clock.

Entry, post-clock and post-prefix ownership checks reject superseded/closed attempts.
Callbacks also check ownership and scope; closing during one fact suppresses later
facts without aborting already-running live decoding. The observer is restored in
finally only if it is still ours, and an expired scope cannot retain its receipt.
Clock failures consume an ordinal and increment a saturating diagnostic counter:
live decode continues inside a scope that emits no unstamped recording facts.
Recording callback exceptions remain contained/countable by BmsProtocol. Real decoder
exceptions retain the previous observer before propagating to existing BLE recovery.

Connection teardown closes capture before cancel/reset. This lifetime belongs to
the BLE attempt, not a recording: stopping a ride while a dashboard still owns BLE
must not close its decoder capture. One fresh protocol and incoming collector per
attempt is the existing repository construction contract; concurrent/reentrant
shared-protocol decoding is not supported by this helper or the decoder.

The optional constructor defaults to null; production repository binding is pending.
Stable source/role attribution, recording-generation capture, receipt diagnostics
as loss markers and the final queued-packet session fence remain mandatory. The helper
does not claim to reject already-queued old records by itself. Its real ET Max test
captures 190 facts (114 controller/76 battery) across original BLE fragment boundaries,
with one clock read per notification and the first completed frame on receipt 2.


## Queued live-sample attempt ownership

Battery and motion envelopes in the existing shared BLE funnel now retain an
immutable binding to the exact link and fresh protocol for their connection
attempt. The consumer checks that binding under sessionLock before submitting
or publishing, in addition to the existing whole-pipeline ownership check.
A reconnection at the same address/index cannot make an older envelope current;
other attempts keep their own ownership. Demo/test envelopes may omit the binding.

Drop detection retires the binding immediately, before suspending into repository
recovery. Teardown remains deferred because joining the calling state/watchdog
job there would deadlock. A volatile retirement flag closes the drop-to-teardown
window even while the old session remains installed. An already accepted consumer
transaction may finish; later queued samples from that attempt are rejected.

Earlier this audit listed the final queue fence as wholly pending. The existing
live battery/motion queue now has that fence. Raw History observation envelopes
still need their production source/recording-context binding and corresponding
consumer checks; this change does not activate the recording controller or turn
merged dashboard snapshots into fresh historical observations.

Regression tests enqueue packets while their attempt is current inside an accepted
publication transaction, retire it before draining, and assert that stale battery
and motion values cannot overwrite valid packets. Both failed before the consumer
check and again with an inert retirement method. Final fresh h104 full run:
3713 tests across 380 XML reports, zero failures/errors/skips, all reports newer
than the recorded UTC start; 105 seconds, all 36 Gradle tasks executed.


## Captured recording admission and attributed receipt envelopes

An internal CapturedHistoryRecording binds one immutable ride ID, writer generation,
BLE generation and exact controller admission gate. The controller's context-aware
offer reads its owner once, verifies this capability and reserves that same owner's
gate. A stopped ride, a reconnect with an identical connection number, or another
controller with identical string IDs cannot reattribute an old packet. The gate
still fences concurrent controls; rejection occurs before writer offer.

HistorySessionTelemetryCapture composes the native receipt boundary with this
capability and an immutable copy of the attempt's physical contributor map. It
captures recording admission before reading the receipt tick and before prefix/
decode callbacks, then creates immutable source/tick/received-observation envelopes.
The full clock generation comes from the injected receipt tick, never from a later
ride. A single notification preserves every decoded observation/peak with shared
arrival time. No recording or absent ownership mapping emits no historical envelope,
while live decoding continues. Wrong-kind and aggregate maps fail at construction.

Envelope offer additionally checks BLE attempt ownership and must run in the shared
serialized consumer under its session lock. Source maps here only support unambiguous
origin/local-index roles; synthetic and assembly roles must gain explicit attribution
before activation. Raw queue wiring, overflow-to-loss diagnostics, native source
factory and GPS/service/DI activation remain pending. Earlier text calling the
recording snapshot wholly pending is superseded by this implemented helper; it does
not mean the opt-in helpers are already wired into production Kable connections.

Final fresh h108: 3721 tests/381 reports, no failures/errors/skips, every XML newer
than recorded UTC start, 107 seconds, all 36 tasks executed. New controller guards
failed three genuine RED tests; native envelope helper failed four tests before
implementation. One no-recording test already passed with the inert emission stub.


## Raw observations in the shared consumer

HistoryTelemetrySample now carries an attributed envelope, its recording controller
and a mandatory BLE-attempt ownership binding in the existing Sample channel. The
single consumer checks both active pipeline and attempt under sessionLock, then
calls the envelope/context admission path. It does not submit a mixed-age battery
or motion snapshot or publish historical readings onto the dashboard. Three queue
regressions cover all coalesced peaks, queued retired attempts and stop/start while
an old recording envelope waits behind a live publication transaction.

Recording writer exceptions are latched on the admitted ride before releasing its
send reservation. Finish therefore cannot miss the failure; the ride ends INTERRUPTED.
Copies of its owner preserve health across reconnects, while a new ride gets fresh
health and clears the observable telemetryFailure. The raw consumer contains these
exceptions so live packets keep draining. It checks actual coroutine cancellation
with ensureActive rather than treating every writer CancellationException as a BLE
consumer cancellation. The failure regression tests live continuation, reconnect,
interrupted finish, fresh ride recording and completed finish.

This implements the raw consumer path, superseding its wholly-pending status above.
The production notification producer/factory still does not construct these samples;
DI activation, ownership/source planning and bounded-ingress loss reporting remain
required. No production recording mode or Vescape screens are activated by this seam.
Final fresh h112: 3725 tests/382 XML reports, all newer than UTC start, no failure,
error or skip, 119 seconds, all 36 tasks executed. Raw queue no-op RED failed 3/3;
writer-failure RED failed the fourth queue regression before exception containment.


## Native attempt producer and qualified bounded-ingress loss

HistoryTelemetryIngress builds the native capture from an attempt's LinkSpec and
vehicle, resolves/copies its contributor map once, and sends mandatory-owned raw
envelopes to the same bounded channel with trySend. Saturating atomic diagnostics
distinguish FULL, CLOSED and SETUP; no raw packet, location or token is retained.
Setup failure returns null so the ordinary live decoder remains available.

Kable now passes the optional capture into its production ConnectionSession.
Configuration is sealed under sessionLock before the first doConnect or connectDemo
preparation and cannot change thereafter, including after disconnect or failed
connection. The earlier links-empty-only guard had a preparation race; it was
replaced by this explicit startup seal. Each attempt has fresh receipt identity
and immutable binding, and the existing session closes capture at teardown.

Failed sends additionally qualify loss on the captured recording owner. Exact gate,
writer/BLE generation, ride, allowed source and receipt tick are checked before a
reservation; the verdict is latched before release so finish waits for it. No numeric
writer offer or IO bypasses the shared consumer. The ride finishes INTERRUPTED,
which conservatively makes its statistics partial. This deliberately qualifies the
whole ride rather than inventing a precise missing interval or placing out-of-order
markers ahead of queued facts. A stale recording's failed send still increments
transport diagnostics but cannot poison the next ride.

The constructor seam is now wired, superseding earlier producer-hook-pending text.
DI does not configure it yet. Actual source planning, ambiguous synthetic/assembly
roles, setup/receipt-clock failure qualification, calibrated joins, missing JK/gateway
observers, GPS/service ownership and legacy recorder retirement still gate activation.
No new recording mode, screen import, APK or device acceptance is claimed.

Final fresh h117: 3731 tests/382 reports, all newer than UTC start, zero failures,
errors/skips, 116 seconds and all 36 Gradle tasks executed. The native API-only stub
failed four tests; the repository hookup stub failed the integration; inert startup
seal and unqualified ingress loss failed two more checks before implementation.


## Receipt failure qualifies actual decoded loss

Native capture now has an optional unreceipted-observation callback using the same
scope, observer identity and BLE ownership gates as normal receipts. A failed clock
still leaves live decoding running; only an actually decoded observation can report
loss. Fragments/control replies with no observations cannot qualify telemetry loss.
The history factory keeps the original recording context before clock acquisition,
resolves only mapped sources, and reserves that exact owner's existing valid bound
to latch ReceiptLoss. It does not invent a receipt tick, append numeric rows without
time, or borrow a new recording's owner after prefix callbacks.

A clock failure followed by a valid receipt still admits new facts, but its ride
finishes INTERRUPTED. Unmapped sources and retired context across stop/start cannot
poison current recording. Observer restoration and decoder failure behavior remain
unchanged. The earlier receipt-failure qualification gate is implemented for decoded,
attributed losses; setup failures still require lifecycle-owner qualification.

Retraction: ordinal exhaustion formerly failed before reading time/context, allowing
loss callbacks to see the prior receipt's owner. Capture now refreshes once even
when no ordinal remains, discards the unusable receipt and reports actual decoded
loss against the refreshed scope. A boundary-injected initial ordinal tests the last
valid MAX value and two exhausted notifications with changing owners; production
attempts continue starting at zero with fresh session identity.

Fresh final h121: 3736 tests/382 XML reports, all newer than UTC start, no failures,
errors/skips, 113 seconds/all 36 tasks executed. h118 failed two of 19 selected tests
before clock-loss implementation; h120 failed the ordinal-boundary regression before
context refresh. No activation or phone/UI acceptance is inferred from these tests.

## JK native status observations

JK now emits optional frame-local battery observations from each complete status
reply, including coalesced replies. Cell indices retain their physical positions;
active measured zero and high voltages survive the legacy UI's filtering. Settings
use the full u32 cell-count declaration for history. An enabled-cell mask with
missing positions cannot certify whole-pack cell extrema. Neither the constructor's
24-cell default nor a shortened settings word is a physical declaration.

Device-info metadata qualifies the 24S/32S scalar offsets. Before that declaration,
only positive voltage words in the shared 24-word prefix are usable; scalar values
and whole-pack summaries remain partial. Metadata changes invalidate summaries
without restamping cached numbers. Retraction: the first implementation invalidated
only summaries on a layout downgrade; it now also invalidates tracked positions
24–31 immediately, even when no subsequent status arrives. This means unobservable,
not physical removal. Metadata and seen-state update even without an observer.

Temperature observations respect absence bits, -2000 sentinels and invalid active
sensors. A corrupt active sensor leaves the maximum partial. On 32S, MOS temperature
is at 144; 166 is a fault word. The legacy live decoder, commands and polling are
unchanged; this optional recording path does not silently alter the dashboard.

Field references read: [batmon-ha jikong.py at 76317567](https://github.com/fl4p/batmon-ha/blob/76317567a51fcfbf9df9e65315fcb2d5af125237/bmslib/models/jikong.py),
[ESPHome JK decoder at 25822a8](https://github.com/syssi/esphome-jk-bms/blob/25822a8f9ab784fa89ccdd1f3002f8e4a2e06916/components/jk_bms_ble/jk_bms_ble.cpp).
Only field facts were used; no source/assets imported from these projects.

## Gateway audit boundary (implementation pending)

The local VESC firmware's commands.c emits controller identity after the mandatory
SETUP body (payload offset 56), and after GET_VALUES' optional position field
(payload offset 58). The existing live decoders ignore those trailers. Serial
request attribution alone cannot certify a CAN controller after a very late reply;
history must require matching identity for forwarded replies, or emit uncertainty.
SETUP currents remain CAN sums, not per-controller measured currents.

The rider's nyxdash bms.c serializes cached m_values for opcode 96, including cached
cell/sensor arrays, but no per-field acquisition time or age. Receiving that reply
cannot independently refresh all physical pack facts. Its ANT current projection
also carries an explicitly documented field-tested sign policy; do not generalize
that one gateway's convention to arbitrary stock VESC BMS producers. Gateway
observers, role policy and calibrated joins remain open; no new polling is proposed.

### Subsequent controller implementation checkpoint

Gateway controller replies now emit optional observations directly from the decoded
reply, before any live SETUP overlay or cached per-unit projection. Source indices
follow the link-owned controller order. SETUP CAN-summed currents/power remain
partial; VALUES cannot borrow cached distance or speed facts. History distance uses
a separate qualified SETUP baseline, cleared on reset.

Forwarded controller observations require a matching identity trailer at the audited
offset, including the optional VALUES position preceding that ID. Missing/mismatched
identity emits typed partial fields and cannot advance the baseline. Retraction: the
first hook bypassed identity for every native/null target. A late CAN reply can land
in a native request slot too. Until an explicit native identity is available, native
facts on a mixed local/CAN controller link remain partial. A link with no forwarded
controllers retains normal native observations without requiring a CAN trailer.

The bounded real-poll-loop tests assert issued commands before checking observations,
exercise local-vs-global indices, genuine zero, SETUP/VALUES separation, identity
failures, mixed-source late replies, distance baseline and reset. These observations
do not alter live projection, request cadence or serial timeout behavior. Hosted BMS
cache freshness, synthetic/aggregate roles and runtime activation remain open.

Local wire sources read on 2026-10-15, SHA-256:
- `C:/Users/sodovaya/Desktop/Software/vesc_tool_free_windows/bldc/comm/commands.c`:
  `7647827a16cf9163699f40c82c4d96b0ba38d0b89652e9831727f983ae70ea80`.
- `E:/sodovaya/nyxdash/firmware/components/vesc_express/src/bms.c`:
  `4e293ba2f8c93b0903e05ded3d5d5aec0b35ad492b55c7964ccedfccb11b8165`.

Final fresh h130: 3750 tests/384 XML reports, all newer than UTC start, zero
failures/errors/skips, 242 seconds/all 36 tasks executed. h127 failed all three
new gateway tests before the hook; h129 failed the mixed-native regression before
its identity qualification fix. Narrow readonly review found no remaining defect
in this controller scope. Runtime activation and phone acceptance remain pending.

## Native role/address admission fence

Observations and capture-map keys now include a CONTRIBUTOR, ASSEMBLY or DERIVED
role. Existing producers default to CONTRIBUTOR. ASSEMBLY requires a nonnegative
assembly index; other roles cannot carry one. Auxiliary assemblies and virtual
derived batteries are battery addresses, not controller addresses. Canonical unit
or geometry conversion of a controller field does not change its contributor role.

Receipt and unreceipted-loss attribution use the complete role/address key. An
unmapped assembly or virtual battery cannot borrow a contributor's identical
origin/local index, append its values or qualify a physical recording's clock loss.
Direct adaptation and binding currently reject non-contributor roles until their
durable source kinds and metric permissions are explicit. No auxiliary producer,
durable schema or runtime activation changes in this checkpoint.

h131: four new regressions failed among 80 selected tests before admission fences.
h132: full fresh 3754 tests/384 reports, zero failures/errors/skips, 101 seconds/all
36 tasks executed. Narrow readonly review found no concrete defect in this scope.

## Subsequent durable role qualification

The admission fence now has explicit durable BATTERY_ASSEMBLY and DERIVED_BATTERY
source kinds. Their names round-trip through the existing source registry TEXT
column; no schema or SQL migration changes. An assembly offers voltage/temperature
only. A virtual battery offers voltage/current/power/SOC only, retaining canonical
signs; it cannot offer physical cells or battery-temperature sensors. Geometry/unit
conversion of physical controller telemetry still remains a controller contributor.

Both adapter and capture mapping require the kind corresponding to the complete
role/address. Distinct role keys cannot reuse a source ID. The earlier temporary
rejection of every non-contributor is superseded by this explicit kind and metric
qualification; the rejection of roles masquerading as physical BATTERY remains.
Unmapped-role receipt/loss behavior is unchanged. Producer freshness/calibration,
source-plan activation and hierarchy metadata remain separate work; labels are
snapshots, not evidence from which to infer a parent or assembly index.

h133: 53 selected tests, three new positive-admission/registry regressions failed
before implementation; forbidden-metric and real SQLite kind round-trip cases
already passed. A real capture-to-writer test keeps physical, assembly and derived
zero measurements in three source IDs. This checkpoint does not activate producers.

Final h134 fresh verification: 3759 tests/384 XML, all newer than UTC start, zero failures/errors/skips, 100 seconds/all 36 tasks executed. Narrow readonly review found no concrete role/kind/metric-admission defect. No schema, producer activation or phone/UI acceptance is implied.

## Begode accepted per-assembly facts

Each accepted smart-BMS 0x01 frame now emits a separate ASSEMBLY observation with
the branch local index and the frame's assembly parity. Its temperature is the
maximum of only that frame's two signed sensors. Boot-shaped replies invalidate
the previous temperature; real winter zero is preserved. Any active sensor outside
the decoder's existing sanity range leaves the maximum partial, never a falsely
low maximum selected from the remaining sensor. Whole-branch temperature remains
partial until its own receipt-qualified aggregation exists.

Retraction: the first assembly helper incorrectly inherited the whole-branch
voltage calibration gate. The signed section field at byte 14 is independently
reported in 0.1 V units. The ET Max capture's section/cell-sum comparison and
[WheelLog GotwayAdapter at f44e39e](https://github.com/Wheellog/Wheellog.Android/blob/f44e39e5388e500a5d2f315d1dcf32f167607bf8/app/src/main/java/com/cooper/wheellog/utils/GotwayAdapter.java)
confirm this; it does not use byte 6's biased whole-branch voltage or 0x00's
nominal-wheel scale. Assembly voltage now records its own field, including zero
on a live non-boot frame. Boot zero is unavailable and a negative voltage invalid.
This does not release the separate whole-branch calibration/voltage-current join gate.

The real stationary capture now yields 228 observations: 114 controller, 76
physical battery and 38 assembly. Receipt tests still prove completion timing,
one clock per notification and shared receipt identity for coalesced observations.
Six assembly tests cover addresses, peaks, cold zero, invalid sensors, independent
voltage fields, boot, fragments, tail/address rejection, reset and the real capture.
Begode commands/polling and live fields remain unchanged; FFE1 is never written.

h135 failed six of 22 selected tests before the producer. h136 full ran 3764
tests with one old fixture-count expectation failure (190 vs the new 228); it was
not a passing run. h137 failed three of 46 selected voltage regressions before
the field correction, with the receipt/count checks passing. Final h138 fresh:
3765 tests/385 reports, zero failures/errors/skips, 102 seconds/all 36 tasks
executed. Narrow readonly re-review found no concrete remaining defect. DI/source
planning, alias admission and other runtime activation gates remain open.

## GPS measurement time versus consumer freshness time

Retraction: the original GPS adapter treated RideLocationFix.capturedAtEpochMillis
as raw acquisition wall time. Android live callbacks intentionally populate that
field with receipt time for navigation freshness; last-known fixes follow their
own clamping policy. The location model now retains a separate optional immutable
measurementAtEpochMillis, supplied directly from Android Location.time. Existing
consumer freshness, location-source and monotonic timestamp behavior is preserved.

History requires the explicit measurement field and combines it with the actual
monotonic fix time. A fresh receipt cannot invent missing acquisition metadata.
Existing origin/generation, age, coordinate, accuracy and heading guards remain.
Logical history range selection still uses the monotonic ride axis, independent
of raw wall-clock jumps. GPS collection demand, source metadata persistence and
foreground-service ownership remain separate runtime gates.

h139 failed both new regressions among seven GPS tests before the adapter change.
h140 full fresh: 3767 tests/385 XML reports, all newer than UTC start, zero
failures/errors/skips and all 36 tasks executed. The log reports an elapsed span
of 5h46m17s; it is not used as a performance result. Narrow readonly source review
found no concrete defect; Android plumbing was compiled and inspected, not tested
with a physical GPS or instrumentation.

## 2026-10-15 — bounded paged overview for Task8

Ordered v2 pages now feed a bounded overview accumulator. Continuity is checked
against adjacent raw samples before thinning; sparse retained envelopes are never
reinterpreted as acquisition cadence. The actual trailing unknown survives page
compaction, so a following page cannot bridge an omitted gap. Statistics remain
an independent durable-store query. The accumulator retains at most the point
budget plus one raw boundary; the current page is temporary.

h141 genuine RED: all six new regressions failed before implementation (94s,
eight tasks executed). h142 full fresh: 3773 tests/386 XML, all newer than UTC
start, zero failures/errors/skips, 98s and all 36 tasks executed. Tests cover page
peaks, dense unknown boundaries, marker/cadence edges, immutable snapshots and a
synthetic two-hour 72000-point stream in twelve pages. This does not certify a
physical sampling cadence or end-to-end database performance. Narrow readonly
Luna xhigh review found no concrete fidelity/bounds defect. Task8 read facade and
legacy integration, Task7 runtime activation and all new screens remain open.
