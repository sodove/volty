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
- Begode smart branches, JK and the VESC gateway still need qualified observations.
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
