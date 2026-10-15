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

## 2026-10-15 — v2 bounded read facade (Task8 checkpoint)

HistoryReadRepository now exposes recorded rides, source metadata, canonical
series, cursor-paged GPS and independently queried summary stats. The v2-only
default implementation qualifies the recorded source kind, metric registry and
cell-index shape before numeric reads. Unknown sources/metrics return empty data,
never current telemetry. Each request owns its accumulator; raw pages are capped
at 20000 rows and eight pages by default (configuration 1..64), with at most 600
plotted points. Reaching a remaining page tail reports truncated; envelope thinning
alone does not. Marker overflow, foreign series, non-ordered points and a stalled
numeric sequence cursor fail explicitly. Cancellation is checked around store IO,
including a noncooperative in-flight read. No new runtime/DI activation is included.

The GPS API includes an optional continuation cursor: a timestamp range cannot
resume between multiple fixes captured at the same time. Stats never derive from
this overview. Legacy currently retains its separate compatibility reader; unified
legacy pagination and component generation/loading/error admission remain open.

h143 stopped at a nullable-Pair test compilation error (not a behavioral RED).
h143b then failed all nine new tests against the unimplemented API (40s).
h144 passed those nine (90s). h145 passed eleven including two real SQLite
fixtures (42s). Review found no production defect and requested coverage for
thinning versus truncation and advancing equal-time cursors; both are now pinned.
Readonly follow-up review found no concrete fixture defect.

h146 full fresh: 3784 tests/388 XML, all newer than UTC start, zero failures,
errors or skips; 98s, all 36 tasks executed. Real SQLite verifies a 20003-row
same-time series across a 20000-row cursor boundary, zero-width range, peaks on
both sides and unavailable uncertified stats. A synthetic two-hour fixture has
57608 points across two controllers, two batteries and four physical cell keys;
it preserves distinct values, sign, zero, BLE marker and raw wall-clock jump.
The SQL test times were 0.219s and 0.782s including fixture setup/teardown in the
full JVM suite. These are not isolated read benchmarks, Android latency or heap
measurements; physical cadence and long-ride memory acceptance remain open.

## 2026-10-15 — bounded legacy read interface (Task8 checkpoint)

LegacyHistoryReader now implements the read interface independently of the v2
reader. Ride metadata and recorded source keys do not load the trace. The selected
metric/cell and wall range are filtered before a 20001-row probe; at most 20000
rows enter its bounded overview. An unrelated metric cannot consume that budget.
Legacy origin stays null and elapsed/generation zero remain placeholders. The
reader does not run v2 cadence/integration rules against them or invent GPS,
markers or certified summary stats. Existing load/sign conversion is retained.
Parent existence is checked transactionally before source/numeric admission, even
in a deliberately incoherent old database with orphan points.

New read-only SQL queries page rides by millisecond epoch/stable ID and select
one bounded series. They strip the fractional part before SQLite parses whole
seconds, then truncate original fractional digits and preserve Z/offset suffixes.
This matches Instant.toEpochMilliseconds for tested UTC and positive/negative
ISO offsets, including .999999999 boundaries. No SQLite-version-specific rounding
bug is claimed: an independent Python SQLite3.53 probe already truncated it.
No DDL, schema snapshot, migration, old-row deletion or runtime activation changed.

h147 genuine RED: ten selected tests, all seven new read-interface cases failed
while three existing load tests passed (91s). h148 had one fixture mismatch:
canonical sign multiplication produces boxed -0.0, while the new expected list
used +0.0. The fixture was corrected without changing existing arithmetic.
h149 then failed the new orphan regression with the other ten passing (41s).
The parent-existence fence fixes that admission. Narrow readonly review and its
follow-up found no remaining concrete defect.

h150 full fresh: 3792 tests/389 XML, all newer than UTC start, zero failures,
errors or skips; 100s, all 37 tasks executed including actual database migration
verification. Fixtures cover starvation behind 20001 other-metric rows, explicit
selected-series truncation, 45 equal-time rides over three pages, canonical sign,
zero/unknown, exact fractional ranges/offsets and an orphan without a parent.
Unified old/new catalog routing, component admission/loading/errors and runtime
activation still remain; this checkpoint does not replace the existing graph UI.

## 2026-10-15 — unified recorded catalog (Task8 checkpoint)

SqlDelightHistoryReadRepository combines v2 and old rides in one bounded SQL
catalog, ordered by started millisecond epoch and stable ID. A v2 ID excludes an
old row with the same ID before the 21-row probe/20-row page. Data reads choose
one format for the request; an unavailable v2 key never falls back to old points.
Old rows are preserved. Deliberate cross-format ID reuse shadows the old copy
while the v2 ride exists; this rule is consistent for list/metadata/data reads.
No migration creates such copies and no new runtime/UI wiring is activated.

h151 failed all six new integration tests against the stub (91s). h152 passed
27 of 28 selected tests, but the old catalog end date was null. Investigation of
the generated mapper showed that bare NULL aliases in the first UNION branch
were inferred as Nothing?: SQLDelight emitted constant nulls instead of reading
the old timestamp columns, making the legacy mapping branch unreachable. An
always-false warning in the new Kotlin file corroborated it. Explicit
CAST(NULL AS TEXT) fixes the inferred type. Generated code was checked for
String? fields and cursor.getString(9)/(10); the real non-null end-date assertion
now passes. This retracts the initial SQL-only assessment that the mapping was
already correct. The generated source is build output, not a handwritten patch.

Fixtures now cover 45 mixed equal-time rides across 20/20/5 pages, duplicate IDs
excluded before limiting without deleting rows, format-exclusive canonical
series/source/GPS, missing-key isolation, configured v2 read caps, fractional
positive/negative-offset cursors, and non-null completed v2 end epoch/elapsed/
generation/status. The earlier two-hour SQL fixture also now asserts actual
7201 returned raw rows for each of eight keys (57608), beyond its seed loop and
overview shape assertions. Readonly review/follow-up found no remaining defect.

h153 focused fresh: 28 tests/5 XML, zero failures/errors/skips, 95s.
h154 full fresh: 3798 tests/390 XML, all newer than UTC start, zero failures,
errors or skips; 100s, all 37 tasks executed including actual migration
verification. No DDL/schema snapshot/old-row rewrite was introduced. Native heap/
latency, source-aware component admission, final runtime integration and screens
remain open; the existing live/historical graph routes remain in place.

## 2026-10-15 — active recording setup-loss verdict (Task7 checkpoint)

Previously, an optional ingress setup exception only incremented a diagnostic
counter and returned no capture. During an active recording that could leave the
ride eligible for COMPLETED despite its unavailable observer. Setup now snapshots
the recording capability before the planner runs. Its failure can downqualify
only that exact gate/generation/ride and current connection, with a current BLE
attempt, matching non-null vehicle and nonempty telemetry registry. It reuses the
existing gate reservation/failure latch without IO, a new clock read or numeric
publication. The verdict survives reconnect and makes durable finish INTERRUPTED.

A supplier that failed cannot provide qualified source IDs. The conservative rule
therefore marks that current vehicle's telemetry recording incomplete; intentionally
unsupported links must return an empty map instead of throwing. Until source-plan
admission is complete, this can conservatively affect a configured noncontributor
failure on the same vehicle; it cannot falsely certify lost data. Null/wrong
vehicle, retired attempt, empty telemetry registry and an owner replaced inside
the planner cannot receive the verdict. An ownership-predicate exception remains
contained so ordinary live decoding continues.

h155 genuine RED: 15 tests, two new active-owner positive cases failed; the other
13 passed (41s). h156 fresh focused: 39 tests/3 XML, zero failures/errors/skips,
54s. Tests cover a throwing plan and invalid role binding, reconnect finish,
zero setup clock reads/no invented samples/live decode, four unqualified cases,
stop/start within the failed planner and a throwing ownership predicate.
Readonly Luna xhigh review found no concrete ownership/containment defect.
h157 full fresh: 3803 tests/390 XML, all newer than UTC start, zero failures,
errors or skips; 100s and all 36 tasks executed. No SQL changes in this checkpoint;
actual migration verification last passed in h154.

Initial setup failure before any recording owner exists still needs a runtime
readiness gate. This checkpoint does not solve that case or enable DI/recording.
Source planning/aliases, calibrated joins, GPS/service ownership and old-writer
retirement remain open. No BLE commands, cadence or Begode FFE1 writes changed.


### Recorded source registry changes (2026-10-15)

Earlier same-vehicle reconnect handling ignored a newly supplied source registry.
A replacement BMS could therefore leave the old registry attached to the active
ride and reject all observations under the replacement ID without opening a new
recording. The controller now compares source IDs and kinds through the reducer.
A changed registry closes the prior ride before beginning the new one; its
SOURCE_CHANGE terminal marker belongs only to the prior ride. The source change
is intentional, like VEHICLE_SWITCH, and does not itself assert lost evidence.
Marker kinds are stored as text, so no schema migration is involved.

Labels and list order do not define identity. Renaming or reordering sources keeps
the ride and its originally recorded display metadata. On an active same-vehicle
connection, an omitted registry retains that snapshot; an explicit empty registry
rotates to a GPS-only ride. A new ride with no registry starts empty and cannot
inherit metadata from a stopped ride or another vehicle. Duplicate IDs are rejected
before closing the current admission gate.

The existing ingress fence drains the prior segment before durable finish. A
profile-change event captured before the latest admitted sample is clamped to that
sample, for both the old end and new start. Captured packets from the retired gate
cannot be delivered to the replacement ride even on the same BLE generation.
Stale-generation events and automatic connects after manual stop retain their
previous guards.

h159: genuine RED, 19 selected tests, four new failures, 42s. h160: 42 selected
tests / 3 fresh XML reports, zero failures/errors/skips, 93s. Read-only Luna xhigh
review found no actionable defect. Full verification is recorded in the ledger.

This boundary uses the actual registry rather than an unused opaque revision ID.
The runtime source factory still must encode physical and calibration identity in
source IDs; equal IDs/kinds cannot distinguish a geometry change by themselves.
This remains a runtime activation gate. No new recorder DI, GPS/service demand,
legacy-writer retirement, UI route, BLE command or polling changes are activated.


### App-owned GPS observation and expiry (2026-10-15)

HistoryGpsRecorder observes an owned ride and location status in the injected
application scope. BLE loss/reconnect does not restart this GPS lifetime. The
existing adapter preserves actual acquisition wall/monotonic timestamps and
rejects missing metadata, pre-origin/future/stale coordinates. The observer stores
accepted fixes once; demand-only updates do not resample the same coordinate.

Searching, unavailable permission/provider, invalid fix and expiry produce one
GPS_GAP per outage, without an invented coordinate. Each accepted fix has a finite
one-shot timer at maxAge+1 (default maximum age 10 seconds), so an unchanged
Available state cannot extend the route forever. A fix at/before an open gap does
not close it. An older provider fix cannot replace a newer admitted fix or move
its deadline. Retraction: freshness-first handling originally marked a delayed
stale fix as a new outage even while a newer accepted fix remained fresh; the
adversarial regression reproduced this and the observer now orders that check
before freshness admission.

Stopping/changing rides cancels the prior observer and timer; retained pre-origin
coordinates cannot become a new route. Explicit observer close and parent-scope
cancellation stop observation without issuing a recording stop. Cancellation is
rethrown, not converted to a storage failure. Retraction of a test assumption:
CancellationException in an individual collectLatest action cancels that action;
it need not cancel its parent collector. Subsequent status updates remain usable.

Clock and writer exceptions are contained within the optional GPS observer. A
captured GPS recording capability uses identity/generation/ride qualification and
survives BLE admission changes, but cannot cross controllers or recording owners.
Clock failure latches an incomplete-recording verdict without inventing a tick.
GPS offer failure latches on its reserved owner BEFORE gate release, so a waiting
stop cannot persist COMPLETED ahead of the failure verdict. Valid future location
updates can resume observation. GPS gap admission validates ride/clock and
participates in delayed finish bounds through the same reservation gate.

Evidence: h162 seven initial behavioral RED failures; h163 33 selected green.
h164 reproduced four exception/concurrent-stop defects among 31 tests. h165 53
selected green. h166 fresh XML reproduced the stale-provider false gap and the
incorrect cancellation fixture expectation; capability guards were green. Final
focused/full verification and exact counts are recorded in the ledger.

This observer is not activated in DI and does not yet acquire native location
or foreground-service demand. Runtime source/calibration planning, provider-source
persistence (the current GPS row/model has no provider field), native permission/
service ownership, recorder activation and old-writer retirement remain open.
Unit fixtures establish observer policy, not Android background GPS acceptance or
an unconflated native callback stream. No BLE requests/cadence/writes changed.


### GPS provider provenance and compatible receipts (2026-10-15)

The previous checkpoint's provider-persistence gap is addressed by nullable
HistoryGpsProvider (GPS/NETWORK/PASSIVE). The adapter preserves the existing
RideLocationFix source classification. It does not infer GPS for network/passive
fixes, and historical rows without this metadata remain null/unknown. No request
policy, platform source classification or native permissions are changed here.

Migration 17.sqm adds one nullable TEXT column to HistoryGpsV2. The existing17.db
is the pre-migration snapshot; generated18.db contains the provider column.
ADD COLUMN is compatible with the project's SQLite3.19 floor. Detail paging and
both ordinary and receipt-verification reads preserve the field, including
same-acquisition-time GPS rows with different providers.

Known-provider batches use a distinct v2-gps-provider fingerprint encoding with
nullable provider facts for every GPS entry. Batches whose providers are all
unknown keep the exact previous v1 byte encoding. Existing committed v1 receipt
fingerprints are not rewritten. Thus a post-upgrade exact retry still acknowledges
its old payload, while adding/changing provider metadata under that receipt is a
conflicting payload. Old row-verified receipts also compare the provider field;
they cannot silently acknowledge a provider-only change.

h169 reproduced four behavior failures among 42 selected tests. The independent
pinned v1 fingerprint fixture already passed before implementation. h170 generated
18.db in one executed schema task (2s); that is schema generation, not test or
migration-verification evidence. Final focused/full/migration evidence belongs in
the ledger. A real17 snapshot fixture seeds an old GPS row and pinned v1 receipt,
upgrades it, verifies the provider remains unknown and the fingerprint unchanged,
then checks exact retry after finish and rejection of invented GPS provenance.

Recorder activation, physical/calibration/alias source planning and native GPS/
foreground-service demand remain open. This change enables no new runtime path
and imports no Vescape code or resources.


### Independent history location demand (2026-10-15)

LocationConsumer.HISTORY_RECORDING owns a separate demand key, so removing MAP
and Nearby sharing demand cannot stop an active recording's registration.
Removing the final history owner retires its callback generation. The request
policy uses the existing 1000ms/zero-distance navigation request for history;
ordinary MAP-only demand returns to its existing 5m distance filter. This avoids
requiring movement before another requested GPS callback, without introducing a
new poller or changing any existing consumer's standalone policy.

h173 reproduced the stationary-history policy failure among 15 tests (77s).
The narrowly scoped Luna worker hit its usage limit after RED; primary took over,
reviewed the four-file delta and finished the one missing request-policy arm.
h174 fresh focused:15 tests/2 XML, zero failures/errors/skips, 86s. Full evidence
is recorded in the ledger. Native permission/foreground-service admission and
runtime demand binding are still pending; adding a consumer enum does not itself
activate location collection for new history.


### Fresh plain-VESC virtual rail observation (2026-10-15)

VescProtocol previously updated its live derived battery but emitted typed
history observations only for the physical controller. It now emits a separate
BATTERY/DERIVED local0 event for each successfully decoded values reply when
deriveBattery is enabled. It remains a virtual rail source, with its own durable
kind/ID binding; it cannot claim physical cells or pack thermistors.

Voltage uses the fresh input-rail known flag; current and power convert exactly
once to the battery's charge-positive canonical sign. SETUP's bus-summed current/
power retain PARTIAL_SOURCE qualification, matching the already audited physical
controller observation. Plain values have no earned gauge, so they cannot reuse
the prior SETUP battery level. SETUP uses its freshly decoded gauge under the
existing decoder absence policy, with invalid range rejected. This checkpoint
does not redefine the decoder's treatment of a zero/unconfigured gauge.

Measured zero current/power survives; missing rail voltage remains unknown rather
than reusing the preceding frame. Disabled virtual battery mode and rejected CRC/
short payloads emit no virtual observations. An observer exception still leaves
live decode intact; diagnostic counts now reflect both attempted source callbacks.
No new command, polling, handshake or BLE write was added.

h176 genuine RED:11 tests/3 new failures,43s. h177 fresh focused:42 tests/4 XML,
zero failures/errors/skips,95s. h178 fresh focused:46 tests/5 XML, zero failures/
errors/skips,43s. Real native-VESC→receipt→controller→buffered projection→SQLite
integration asserts six actual accepted offers from three coalesced replies,
one receipt clock read, separate physical/virtual IDs, opposite canonical power
signs, exact extrema and sample count3 each, and unavailable energy rather than
invented intervals. Full verification is recorded in the ledger. Primary reviewed
the native delta; Luna review quota was unavailable and no alternate reviewer
model was invoked. Whole-branch acceptance review is not claimed here.

Runtime source planning/readiness, alias-path admission and calibrated joins,
gateway hosted/derived capture, native location/service binding and activation
remain open. The new recorder/ingress remains unconfigured in production DI.


### Recorded metric metadata (2026-10-15)

The picker can ask for actual keys in one recorded source group without reading
numeric traces. A filtered UNION of detail and summary keys preserves sparse
cell numbering, unknown-valued recorded rows and summary-only metrics, while
avoiding duplicate budget consumption. Invalid metric/index shapes and oversized
stored cell indices are rejected before the 1..20000-key output budget. Results
explicitly report a truncated tail. Historical source IDs remain distinct even
when labels match; current connected vehicle metadata is not consulted.

Legacy metadata recognizes the existing sixteen scalar names, applies their
canonical mapping and does not invent cell series. A v2-owned ride never borrows
legacy keys. Real SQLite fixtures cover sparse cells, summary-only keys, malformed
row starvation, 1001 cells, legacy/v2 shadowing and actual orphan rows. Invalid
budgets are checked before format lookup; the fixture removes the queried table
to make an accidental lookup observably fail. The direct store orphan assertion
exercises its own parent guard rather than relying solely on unified routing.

The first integrated run exposed a fixture error: bucket writes require an
immutable append receipt. Production correctly rejected the incomplete fixture;
the fixture now supplies that receipt. No production validation was relaxed.
Verification counts are recorded in the execution ledger. This is a read-contract
extension, not runtime recorder or screen activation.


### Read-only detail ownership (2026-10-15)

HistoryDetailComponent prepares the foundation's component read path without
activating a product route. It owns one ride's recorded sources, selected keys,
viewport/cursor and per-key series/stats. It does not subscribe to BmsRepository,
preload catalog/routes or resample live state. New ride selection clears the
previous detail immediately. Child jobs inherit the supplied UI scope and retire
on parent cancellation or Decompose destruction. Ride/range generations and
per-key capabilities fence late completions, including remove/readd of the same
key while an old stats read is pending. Series and stats publish atomically per
key; one failed metric does not erase other successful keys. Errors are typed,
without exposing database exception text.

Known descriptor/source-kind/index admission rejects foreign keys. Duplicate
adds and identical ranges issue no extra reads. Range/reorder/remove operations
retain a cursor only inside the selected viewport. Empty/unavailable series and
zero-width ranges remain usable. Completed v2 viewports use the monotonic plot
axis, including when the saved wall clock moved backwards; legacy retains its
wall-only axis. An initial revision seeded unfinished viewports from injected wall time; that
choice was retracted during review (see below). No viewport endpoint is a
recorded end or sample.

The lifecycle regression initially destroyed an INITIALIZED test registry and
did not verify the transition. It now starts RESUMED and asserts DESTROYED before
releasing the late read; production lifecycle handling was unchanged. Fresh
focused/full evidence is in the ledger. This does not complete catalog/route
ownership, screen transfer, recorder activation or native acceptance.

Recorded-source expansion now explicitly loads/caches only key metadata for the
selected ride. Sparse cell keys and truncation remain visible. Source metadata
jobs belong to the ride, separately from viewport-owned numeric jobs: changing a
range does not restart or discard an expanded group's pending metadata. A new
ride or close retires those jobs, including a late response for an identically
named source ID on another ride. Source read failures are typed and independently
retryable; no numeric series is fetched by group expansion.

Retraction: raw current wall time is insufficient even for an unfinished v2
viewport. A backwards clock change can hide already recorded rows on the stable
monotonic plot axis. The detail clock contract now supplies HistoryTick, including
actual elapsed time and generation. The fix derives v2 bounds with
historyAxisEpochMillis; legacy remains explicitly wall-only. Incomparable clocks
must fail rather than manufacture an endpoint. Runtime integration must supply
the same process clock and reconcile interrupted recordings before activation.

A synchronous StateFlow observer can itself change ride/key selection before a
command returns. Three real unconfined-observer fixtures reproduced old ride
requests borrowing a new generation, removed metric reads still starting, and
retired group metadata still issuing IO. The owner now reserves identities before
publication, rechecks them after publication and installs lazy jobs before start.
A selected-key guard prevents a removed metric from dispatching. These tests
assert actual issued repository reads as well as final state, not timing alone.

A fourth observer regression changes viewport synchronously during metric add.
It reproduced two actual reads for the same new-range key. Dispatch now retains
the generation reserved before publication and suppresses an already reserved or
completed read in that range. Range replacement explicitly clears numeric cache
before reloading; it cannot reuse a different range's old graph data.


### Direct native recording registry (2026-10-15)

The optional HistoryTelemetryIngress now defaults to a concrete direct-link
source plan. It supports the already qualified JK/JBD/ANT/Daly producers, plain
VESC and Begode. Gateway/alias and other unqualified producers are explicitly
rejected by this default; custom reviewed planning remains injectable. This is
not product-wide recorder activation or a claim that unsupported live BLE links
stop working. The existing ingress contains ordinary setup exceptions.

Versioned length-prefixed source IDs include normalized physical address,
protocol, native branch, origin/role and assembly address. UI labels, list order
and vehicle-global slot indices are excluded. Controller/virtual-source IDs also
retain the decoder's exact motor configuration; Begode controller identity retains
its first owned pack's voltage-calibration cell count. Native protocol creation
and planning share the same immutable configuration lookups. A configuration
change can therefore rotate the recorded registry without silently mixing
calibrations, while renaming/reindexing a profile does not invent a new source.

VESC's virtual rail keeps DERIVED_BATTERY identity, distinct from its physical
controller and from physical BMS contributors. Begode reserves two native branch
slots and each branch's two assembly slots, plus the controller. A reserved slot
is permission to bind a future qualified observation, not proof that the source
reported: it creates no sample or value by itself. Empty latent slots must not
be presented as measured contributors. Controller-only/latent Begode profiles do
not fabricate a battery cell-count calibration.

Default-ingress tests exercise actual owned envelope delivery. The existing
real VESC→receipt→controller→buffered projection→SQLite fixture now uses the
generated plan rather than hand-written source IDs and still verifies six actual
offers, physical/virtual extrema and no invented energy/cells. Identity tests
cover address/protocol changes, address case, label/global-index stability,
motor/cell-count changes, explicit virtual rails and unsupported/malformed plans.
No native command, polling or BLE write behavior changed. Whole-vehicle startup
readiness, alias/gateway acquisition/admission, calibrated aggregates, native
location/service ownership and final DI/legacy retirement remain open.


### Native observation preparation (2026-10-16)

Optional recording preparation is now represented on the attempt-owned capture.
ConnectionSession awaits it after physical connect/transport configuration and
before launching notification observation or handshake. Preparation is once per
attempt, serialized by a mutex; an acknowledged callback can establish the
recording owner before its first receipt. Existing externally prepared callers
remain ready by default. The ingress supplies the immutable attempted source
plan to the callback. Whole-vehicle source merging/lifecycle remains the caller's
responsibility and is not activated in DI here.

A failed preparation disables recorded delivery for that attempt but leaves
ordinary protocol decode available. Readiness is frozen at notification entry;
receipt context is still refreshed so actual unrecorded observations use the
existing exact-owner/source loss qualification rather than replaying stale
context. Preparation failures and unprepared notifications have separate bounded
counters; ingress setup diagnostics count ordinary preparation failures without
raw exception text. Actual missing facts mark an active ride incomplete. Inactive
recording intent is not itself a loss or an automatic start trigger.

Concurrent preparation callers share one ACK. Retirement/close while waiting
cannot publish readiness. Cancellation after a callback that ignores cancellation
must still propagate. Tests use actual deferred writer.begin ACK, issued native
envelopes and accepted sample values, not elapsed waiting alone. Every test gate
is unconditionally released in finally. This does not claim physical BLE/CCCD,
background service or phone acceptance: whole-vehicle lifecycle, bounded startup
policy, native clock/service/location binding and final activation remain open.

The real generated-plan VESC/SQLite fixture also establishes its owner through
preparation, proving that the first six coalesced facts enter the acknowledged
ride without an earlier manual begin. A self-cancelled READY waiter regression
reproduced success publication without suspension; explicit cancellation checks
now fence the ready fast path without poisoning healthy shared readiness.

Retraction: ConnectionSession.connect previously claimed to await notification
subscription. Source inspection shows it launches observeJob and returns while
CCCD/handshake continue asynchronously. Its comment now records that correction.
The new preparation seam is before that launch; it does not turn connection
success into proof that a live notification/decode arrived.


## Whole-vehicle connection ownership checkpoint (2026-10-16)

`HistoryRecordingConnections` serializes physical link intent above the real
`RideRecordingController`. Its installed selection snapshots the entire per-link
registry before any attempt can become ready. First-ready acknowledges durable
begin, additional ready links leave the admission capability intact, and only the
last ready link's loss opens the vehicle BLE gap. Reconnect retains the full
registry and ride rather than rotating a ride when another planned device joins.

Selections and native attempts use identity capabilities. Replacement retires
old callbacks, and globally increasing selection generations fence the controller.
An old selection cannot disconnect or stop a successor. Retraction during review: the first draft deferred terminal retirement until a
new ready link. That leaves old GPS recording alive if the next vehicle never
connects, contradicting the approved switch contract. An incompatible selection
now ends the old ride immediately with its vehicle/source terminal marker and
flush, retiring both telemetry and GPS. The next ride begins only at first ready.
A same-vehicle/same-registry replacement remains a reconnect gap. The selection
input carries the actual current controller generation, since a prior selection
may never have issued a Connected input. Duplicate
source IDs are rejected across links; physical aliases still require the separate
native admission planner and cannot be double-bound here.

Manual stop remains latched through reconnect. Explicit restart requires a ready
link. Stop includes the expected ride ID so a delayed command cannot stop a restart
on the same selection. Disconnect invalidates pending attempts, including when
no new link became ready yet. The caller must use this owner as the controller's
exclusive lifecycle command source; native sample/session ownership remains a
separate requirement. Accepted controls reconcile link bookkeeping in the same
noncancellable region as controller durable ACK, with cancellation checks before
admission and after mutex acquisition. This does not bound writer startup latency.

This checkpoint does not install the owner into Kable/DI or activate the new
recorder. Whole-vehicle planning must use installed immutable decoder configuration,
not a later mutable vehicle profile. Native lifecycle hooks/shared clock, source
specific interruption quality, gateway/alias admission, service/location ownership,
legacy retirement and phone/performance acceptance remain open.


## Optional native lifecycle binding checkpoint (2026-10-16)

Ingress can opt into one `NativeHistoryRecordingLifecycle`. It snapshots the
whole installed native spec registry before capture, retains exact spec identity,
and binds a fresh protocol object to one physical attempt during preparation.
The first ready waits for durable begin; a second link preserves the same ride
and admission. Drop/failed setup retirement identifies the exact protocol, so a
late old callback cannot remove its replacement. Install and disconnect predicates
are rechecked after lifecycle mutex admission. Local bookkeeping reconciles in
the accepted noncancellable control region, followed by caller cancellation checks.

Native repository hooks run outside `sessionLock`: installed initial/restored
plans, preparation, drop, failed/cancelled attempts, replaced sessions, explicit
disconnect, partial removals and foreground stale teardown. The opaque protocol
identity does not expose a wire command path. External/manual ingress remains the
default. A managed ingress and an external preparation owner cannot both be set.

An unsupported whole-vehicle source plan records a setup diagnostic, retires the
incompatible old ride/GPS using an empty unstarted selection, and disables capture
for the new plan. It does not block ordinary live decoding or fabricate samples.
Guest and demo selections retire real history without starting synthetic history.
The default qualified source planner still excludes gateway/physical aliases.

Review found native drop handling must additionally qualify the concrete session
inside its lock: a wait for history retirement can outlive that session. Link
identity alone cannot authorize reconnection of a successor. Explicit disconnect
also needs to reconcile history after native intent acceptance if its caller is
cancelled. These are native command lifetime checks, separate from common recorder
capabilities; tests exercise issued reconnect work and durable recording completion.

This is opt-in native wiring, not production activation or phone acceptance.
Shared Android elapsed clock/recovery, bounded startup, foreground/location
ownership, per-source gap/full-funnel retirement quality, gateway/alias/aggregate
admission, manual UI commands, legacy retirement and native performance remain open.
